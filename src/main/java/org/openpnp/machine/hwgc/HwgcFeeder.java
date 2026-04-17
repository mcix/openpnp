/*
 * Copyright (C) 2026 mcix
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.hwgc;

import java.util.ArrayList;
import java.util.List;

import javax.swing.Action;

import org.apache.commons.io.IOUtils;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.hwgc.wizards.HwgcFeederConfigurationWizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

/**
 * Feeder driver for HWGC SMT machines.
 * Activates feeders using the FEEDER_SWITCH (0x40) protocol command.
 *
 * <p>Slot numbering: feederNumber is the user-visible 1..50 slot index
 * (front 1..25, back 26..50). The HwgcDriver protocol is 0-indexed,
 * so we subtract 1 before sending to hardware.
 *
 * <h3>EIA-481 tape geometry (used by vision calibration)</h3>
 * <pre>
 *   sprocket holes: D0 = 1.5 mm, pitch P0 = 4 mm
 *   hole center to tape edge: E = 1.75 mm
 *   hole center to part center (along tape): 2.0 mm
 *   hole center to part center (perpendicular): tapeWidth/2 - 0.5 mm
 *     → 3.5 mm for 8 mm tape, 5.5 mm for 12 mm, etc.
 * </pre>
 */
public class HwgcFeeder extends ReferenceFeeder {

    // ── EIA-481 standard constants ─────────────────────────────────────
    /** Sprocket-hole pitch along the tape (P0). */
    private static final double HOLE_PITCH_MM = 4.0;
    /** Sprocket-hole diameter (D0). */
    private static final double HOLE_DIAMETER_MM = 1.5;
    /** Distance from the nearest sprocket hole center to the part center,
     *  measured along the tape direction. */
    private static final double HOLE_TO_PART_LINEAR_MM = 2.0;

    @Attribute(required = false)
    private int feederNumber = 0;

    @Attribute(required = false)
    private int feedCount = 0;

    @Element(required = false)
    private Length partPitchInTape = new Length(4, LengthUnit.Millimeters);

    /** Time in ms to keep the feeder solenoid active. */
    @Attribute(required = false)
    private int feedDurationMs = 200;

    /**
     * Vector from the detected sprocket-hole center to the part pick point.
     * Captured once via {@link #captureFromCurrentPosition(Camera)} and then
     * applied on each {@link #calibrateLocation(Camera)} pass to compensate
     * for tape drift relative to the slot anchor.
     */
    @Element(required = false)
    private Location holeToPartOffset = new Location(LengthUnit.Millimeters);

    /** Nominal sprocket-hole diameter for the vision pipeline. */
    @Element(required = false)
    private Length holeDiameter = new Length(HOLE_DIAMETER_MM, LengthUnit.Millimeters);

    /** Search radius for the closest hole to the expected pick location. */
    @Element(required = false)
    private Length holeSearchDistance = new Length(4, LengthUnit.Millimeters);

    /**
     * Allowed relative deviation from the expected sprocket-hole diameter
     * when filtering pipeline results. 0.5 = accept circles whose diameter
     * is within ±50 % of the expected pixel size. Range 0.0 – 1.0.
     */
    @Attribute(required = false)
    private double holeDiameterTolerance = 0.5;

    /**
     * Allowed deviation from the expected 4 mm sprocket-hole pitch when
     * validating that two candidates are genuine sprocket holes.
     * E.g. 0.5 mm means the distance between two holes must be
     * within N × 4 mm ± 0.5 mm for some integer N.
     */
    @Element(required = false)
    private Length holePitchTolerance = new Length(0.5, LengthUnit.Millimeters);

    /**
     * Shared vision pipeline for sprocket-hole detection. All HwgcFeeders
     * use the same pipeline instance so that tuning done via the pipeline
     * editor on one feeder applies to all feeders immediately.
     *
     * <p>Not serialized per-feeder — the pipeline is loaded once from the
     * embedded default resource. Edit it at runtime via the pipeline editor.
     */
    private static CvPipeline sharedPipeline;

    /** Legacy per-feeder pipeline element, ignored on load. */
    @Element(name = "pipeline", required = false)
    private CvPipeline legacyPipeline;

    // ── EIA-481 vision calibration ─────────────────────────────────────

    /**
     * Tape width in millimeters. Determines the perpendicular offset from
     * the sprocket-hole line to the part center:
     * {@code lateral = tapeWidth / 2 − 0.5 mm} per EIA-481.
     */
    @Element(required = false)
    private Length tapeWidth = new Length(8, LengthUnit.Millimeters);

    @Override
    public Location getPickLocation() throws Exception {
        return location;
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        HwgcDriver driver = findHwgcDriver();
        if (driver == null) {
            throw new Exception("No HwgcDriver found in machine configuration");
        }

        int hwIndex = feederNumber - 1;

        // Always close first so that consecutive picks from the same feeder
        // (e.g. n2 then n3) each get a full close-open cycle.  Without this,
        // the second feed() finds the feeder still open and the hardware does
        // not advance the tape.
        Logger.info("HWGC feeder {} (hw index {}): closing before feed", feederNumber, hwIndex);
        driver.sendFeeder(hwIndex, false);
        Thread.sleep(feedDurationMs);

        Logger.info("HWGC feeder {} (hw index {}): opening for pick", feederNumber, hwIndex);
        driver.sendFeeder(hwIndex, true);
        Thread.sleep(feedDurationMs);
        feedCount++;
    }

    @Override
    public void postPick(Nozzle nozzle) throws Exception {
        HwgcDriver driver = findHwgcDriver();
        if (driver == null) {
            throw new Exception("No HwgcDriver found in machine configuration");
        }
        int hwIndex = feederNumber - 1;
        Logger.info("HWGC feeder {} (hw index {}): closing after pick", feederNumber, hwIndex);
        driver.sendFeeder(hwIndex, false);
    }

    // ── Single-hole calibration (original) ─────────────────────────────

    /**
     * Refines this feeder's pick {@link #location} by moving the supplied
     * camera to the expected position, locating the nearest sprocket hole
     * via {@link #pipeline}, and snapping the location to
     * {@code detectedHole + holeToPartOffset}.
     *
     * Operator workflow:
     *  1. Use "Capture from Current Position" once after teaching the pick
     *     point — this fills in {@link #holeToPartOffset}.
     *  2. Use "Calibrate from Camera" anytime to re-anchor against the hole.
     */
    public void calibrateLocation(Camera camera) throws Exception {
        Location expected = location;
        Location holeLocation = findClosestHole(camera, expected);
        Location newLocation = holeLocation.add(holeToPartOffset)
                .derive(null, null, expected.getZ(), expected.getRotation());
        Logger.info("HWGC feeder {}: calibrated location {} -> {}",
                feederNumber, expected, newLocation);
        setLocation(newLocation);
    }

    /**
     * Captures the camera's current position as the part pick point, then
     * runs the pipeline to find the nearest sprocket hole and stores
     * {@code pickPoint - hole} as {@link #holeToPartOffset}. The pick
     * {@link #location} is also updated to the captured camera position.
     */
    public void captureFromCurrentPosition(Camera camera) throws Exception {
        Location pickPoint = camera.getLocation();
        Location holeLocation = findClosestHole(camera, pickPoint);
        Location offset = pickPoint.subtract(holeLocation)
                .derive(null, null, 0.0, 0.0);
        Logger.info("HWGC feeder {}: captured pick={} hole={} offset={}",
                feederNumber, pickPoint, holeLocation, offset);
        setHoleToPartOffset(offset);
        setLocation(pickPoint);
    }

    // ── EIA-481 sprocket-hole vision calibration ───────────────────────

    /**
     * Computes the perpendicular distance from the sprocket-hole center
     * line to the part center, per EIA-481: {@code tapeWidth / 2 − 0.5 mm}.
     */
    private double getHoleToPartLateralMm() {
        return tapeWidth.convertToUnits(LengthUnit.Millimeters).getValue() / 2.0 - 0.5;
    }

    /**
     * Refines this feeder's pick location by detecting two or more sprocket
     * holes in the tape and computing the pick position from the known
     * EIA-481 geometry. No prior capture/teach step is required — the
     * offset from sprocket hole to part center is fully determined by the
     * tape width.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Move camera to the coarse (imported) feeder location.</li>
     *   <li>Detect all sprocket holes visible in the camera frame.</li>
     *   <li>Pick the two closest to the expected position and determine
     *       the tape direction from their alignment.</li>
     *   <li>Snap to the nearest hole and apply the EIA-481 offsets:
     *       2 mm along the tape, {@code tapeWidth/2 − 0.5 mm}
     *       perpendicular to the tape.</li>
     *   <li>Update the feeder's pick location.</li>
     * </ol>
     */
    public void calibrateFromTape(Camera camera) throws Exception {
        Location coarse = location;

        // 1. Detect and filter sprocket holes near the coarse position.
        List<Location> candidates = findSprocketHoles(camera, coarse);
        Logger.info("HWGC feeder {}: {} candidates after diameter/distance filter",
                feederNumber, candidates.size());

        // 2. Validate by sprocket-hole pitch: find pairs whose distance is
        //    a multiple of 4 mm (EIA-481 P0). This eliminates random circles
        //    that happen to pass the diameter/distance filters.
        double pitchTolMm = holePitchTolerance
                .convertToUnits(LengthUnit.Millimeters).getValue();

        Logger.info("HWGC feeder {}: pitch validation — checking {} candidates, "
                        + "P0={} mm, tolerance={} mm",
                feederNumber, candidates.size(), HOLE_PITCH_MM,
                String.format("%.2f", pitchTolMm));

        // Log all pairwise distances so we can see which match.
        List<Location> validated = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                double dist = candidates.get(i).getLinearDistanceTo(candidates.get(j));
                double remainder = dist % HOLE_PITCH_MM;
                // remainder should be near 0 or near HOLE_PITCH_MM
                double pitchError = Math.min(remainder, HOLE_PITCH_MM - remainder);
                boolean match = pitchError <= pitchTolMm;
                int multiples = (int) Math.round(dist / HOLE_PITCH_MM);

                Location mi = candidates.get(i).convertToUnits(LengthUnit.Millimeters);
                Location mj = candidates.get(j).convertToUnits(LengthUnit.Millimeters);
                Logger.info("HWGC feeder {}:   pair [{},{}] dist={} mm "
                                + "({}x{} mm, error={} mm) {}",
                        feederNumber, i, j,
                        String.format("%.3f", dist),
                        multiples, HOLE_PITCH_MM,
                        String.format("%.3f", pitchError),
                        match ? "MATCH" : "no match");

                if (match) {
                    if (!validated.contains(candidates.get(i))) {
                        validated.add(candidates.get(i));
                    }
                    if (!validated.contains(candidates.get(j))) {
                        validated.add(candidates.get(j));
                    }
                }
            }
        }

        Logger.info("HWGC feeder {}: {} holes validated by pitch", feederNumber, validated.size());
        for (int i = 0; i < validated.size(); i++) {
            Location mm = validated.get(i).convertToUnits(LengthUnit.Millimeters);
            Logger.info("HWGC feeder {}:   validated[{}] = ({}, {}) mm",
                    feederNumber, i,
                    String.format("%.3f", mm.getX()),
                    String.format("%.3f", mm.getY()));
        }

        if (validated.size() < 2) {
            throw new Exception("HWGC feeder " + feederNumber
                    + ": need at least 2 sprocket holes at correct pitch, found "
                    + validated.size() + " (from " + candidates.size()
                    + " diameter/distance candidates)");
        }

        // 3. Sort by distance to coarse position, pick two closest.
        validated.sort((a, b) -> {
            Double da = a.getLinearDistanceTo(coarse);
            Double db = b.getLinearDistanceTo(coarse);
            return da.compareTo(db);
        });
        Location hole1 = validated.get(0);
        Location hole2 = validated.get(1);

        // 4. Determine tape direction from the two holes.
        Location h1mm = hole1.convertToUnits(LengthUnit.Millimeters);
        Location h2mm = hole2.convertToUnits(LengthUnit.Millimeters);
        double dx = h2mm.getX() - h1mm.getX();
        double dy = h2mm.getY() - h1mm.getY();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0) {
            throw new Exception("HWGC feeder " + feederNumber
                    + ": detected holes are too close together ("
                    + String.format("%.2f", len) + " mm)");
        }
        // Unit vector along tape.
        double ux = dx / len;
        double uy = dy / len;
        // Perpendicular unit vector (90° CCW — points from sprocket holes
        // toward the part pockets, which sit on the opposite side of the
        // tape center from the holes).
        double px = -uy;
        double py = ux;

        // 5. Compute pick location from the nearest hole using EIA-481 offsets.
        double lateral = getHoleToPartLateralMm();
        double pickX = h1mm.getX()
                + HOLE_TO_PART_LINEAR_MM * ux
                + lateral * px;
        double pickY = h1mm.getY()
                + HOLE_TO_PART_LINEAR_MM * uy
                + lateral * py;

        double tapeAngle = Math.toDegrees(Math.atan2(uy, ux));

        Location newLocation = new Location(LengthUnit.Millimeters,
                pickX, pickY,
                coarse.convertToUnits(LengthUnit.Millimeters).getZ(),
                coarse.getRotation());

        Logger.info("HWGC feeder {}: === TAPE CALIBRATION RESULT ===", feederNumber);
        Logger.info("HWGC feeder {}:   hole1 = ({}, {}) mm",
                feederNumber,
                String.format("%.3f", h1mm.getX()),
                String.format("%.3f", h1mm.getY()));
        Logger.info("HWGC feeder {}:   hole2 = ({}, {}) mm  (distance={} mm)",
                feederNumber,
                String.format("%.3f", h2mm.getX()),
                String.format("%.3f", h2mm.getY()),
                String.format("%.3f", len));
        Logger.info("HWGC feeder {}:   tape angle = {}°, lateral offset = {} mm",
                feederNumber,
                String.format("%.1f", tapeAngle),
                String.format("%.2f", lateral));
        Logger.info("HWGC feeder {}:   pick location: {} -> {}",
                feederNumber, coarse, newLocation);
        setLocation(newLocation);
    }

    /**
     * Runs the sprocket-hole pipeline and returns candidate hole locations
     * in machine coordinates, filtered by distance from the expected
     * position and optionally by diameter.
     */
    private List<Location> findSprocketHoles(Camera camera, Location expected) throws Exception {
        CvPipeline pl = getPipeline();
        MovableUtils.moveToLocationAtSafeZ(camera, expected);
        try {
            pl.setProperty("camera", camera);
            pl.setProperty("feeder", this);
            pl.process();

            if (MainFrame.get() != null) {
                try {
                    MainFrame.get().getCameraViews().getCameraView(camera)
                            .showFilteredImage(
                                    OpenCvUtils.toBufferedImage(pl.getWorkingImage()), 250);
                } catch (Exception e) {
                    // headless / no camera view — ignore.
                }
            }

            List<CvStage.Result.Circle> circles = pl
                    .getExpectedResult(VisionUtils.PIPELINE_RESULTS_NAME)
                    .getExpectedListModel(CvStage.Result.Circle.class,
                            new Exception("HWGC feeder " + feederNumber
                                    + ": no circles detected by pipeline."));

            // Compute the expected sprocket-hole diameter in pixels.
            Location upp = camera.getUnitsPerPixelAtZ();
            double uppMm = Math.abs(
                    upp.convertToUnits(LengthUnit.Millimeters).getX());
            double expectedDiameterPx = holeDiameter
                    .convertToUnits(LengthUnit.Millimeters).getValue() / uppMm;
            double maxSearchMm = holeSearchDistance
                    .convertToUnits(LengthUnit.Millimeters).getValue();

            double minDia = expectedDiameterPx * (1.0 - holeDiameterTolerance);
            double maxDia = expectedDiameterPx * (1.0 + holeDiameterTolerance);

            Logger.info("HWGC feeder {}: pipeline returned {} circles, "
                            + "units/px={} mm, expected hole diameter={} px "
                            + "(allowed {}–{}), max search distance={} mm",
                    feederNumber, circles.size(),
                    String.format("%.4f", uppMm),
                    String.format("%.1f", expectedDiameterPx),
                    String.format("%.1f", minDia),
                    String.format("%.1f", maxDia),
                    String.format("%.1f", maxSearchMm));

            List<Location> locations = new ArrayList<>();
            for (CvStage.Result.Circle c : circles) {
                Location loc = VisionUtils.getPixelLocation(camera, c.x, c.y);
                Location locMm = loc.convertToUnits(LengthUnit.Millimeters);
                double dist = loc.getLinearDistanceTo(expected);

                // Filter by distance from expected position.
                if (dist > maxSearchMm) {
                    Logger.info("HWGC feeder {}:   REJECT px({}, {}) "
                                    + "dia={} dist={} mm — too far",
                            feederNumber,
                            String.format("%.0f", c.x),
                            String.format("%.0f", c.y),
                            String.format("%.1f", c.diameter),
                            String.format("%.2f", dist));
                    continue;
                }
                // Filter by diameter (if tolerance < 1.0).
                if (holeDiameterTolerance < 1.0
                        && (c.diameter < minDia || c.diameter > maxDia)) {
                    Logger.info("HWGC feeder {}:   REJECT px({}, {}) "
                                    + "dia={} dist={} mm — diameter outside [{}, {}]",
                            feederNumber,
                            String.format("%.0f", c.x),
                            String.format("%.0f", c.y),
                            String.format("%.1f", c.diameter),
                            String.format("%.2f", dist),
                            String.format("%.1f", minDia),
                            String.format("%.1f", maxDia));
                    continue;
                }
                Logger.info("HWGC feeder {}:   ACCEPT px({}, {}) "
                                + "dia={} dist={} mm  -> mm({}, {})",
                        feederNumber,
                        String.format("%.0f", c.x),
                        String.format("%.0f", c.y),
                        String.format("%.1f", c.diameter),
                        String.format("%.2f", dist),
                        String.format("%.3f", locMm.getX()),
                        String.format("%.3f", locMm.getY()));
                locations.add(loc);
            }
            return locations;
        } finally {
            // nothing to clear
        }
    }

    private Location findClosestHole(Camera camera, Location expected) throws Exception {
        List<Location> holes = findSprocketHoles(camera, expected);
        if (holes.isEmpty()) {
            throw new Exception("HWGC feeder " + feederNumber
                    + ": no tape sprocket holes detected.");
        }
        holes.sort((a, b) -> {
            Double da = a.getLinearDistanceTo(expected);
            Double db = b.getLinearDistanceTo(expected);
            return da.compareTo(db);
        });
        return holes.get(0);
    }

    /**
     * Returns the shared vision pipeline used by all HwgcFeeders.
     * Lazily initialized on first access.
     */
    public static CvPipeline getPipeline() {
        if (sharedPipeline == null) {
            sharedPipeline = createDefaultPipeline();
        }
        return sharedPipeline;
    }

    /**
     * Resets the shared pipeline to the built-in defaults. Affects all
     * HwgcFeeders immediately.
     */
    public static void resetPipeline() {
        sharedPipeline = createDefaultPipeline();
    }

    private static CvPipeline createDefaultPipeline() {
        try {
            String xml = IOUtils.toString(HwgcFeeder.class
                    .getResource("HwgcFeeder-DefaultPipeline.xml"));
            return new CvPipeline(xml);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    /**
     * Holds the feeder solenoid open (or releases it). Used by the wizard
     * Open / Close buttons so the operator can teach the pick Z height
     * with the cover lifted. Unlike {@link #feed(Nozzle)}, this does not
     * auto-release after a delay — the operator must explicitly close.
     */
    public void setOpen(boolean open) throws Exception {
        HwgcDriver driver = findHwgcDriver();
        if (driver == null) {
            throw new Exception("No HwgcDriver found in machine configuration");
        }
        int hwIndex = feederNumber - 1;
        Logger.debug("HWGC feeder {} (hw index {}): {}",
                feederNumber, hwIndex, open ? "open" : "close");
        driver.sendFeeder(hwIndex, open);
    }

    private HwgcDriver findHwgcDriver() {
        for (Driver d : Configuration.get().getMachine().getDrivers()) {
            if (d instanceof HwgcDriver) {
                return (HwgcDriver) d;
            }
        }
        return null;
    }

    // ── Getters/setters ──

    public int getFeederNumber() {
        return feederNumber;
    }

    public void setFeederNumber(int feederNumber) {
        this.feederNumber = feederNumber;
    }

    public int getFeedCount() {
        return feedCount;
    }

    public void setFeedCount(int feedCount) {
        this.feedCount = feedCount;
    }

    public Length getPartPitchInTape() {
        return partPitchInTape;
    }

    public void setPartPitchInTape(Length partPitchInTape) {
        this.partPitchInTape = partPitchInTape;
    }

    public int getFeedDurationMs() {
        return feedDurationMs;
    }

    public void setFeedDurationMs(int feedDurationMs) {
        this.feedDurationMs = feedDurationMs;
    }

    public Location getHoleToPartOffset() {
        return holeToPartOffset;
    }

    public void setHoleToPartOffset(Location holeToPartOffset) {
        Object oldValue = this.holeToPartOffset;
        this.holeToPartOffset = holeToPartOffset;
        firePropertyChange("holeToPartOffset", oldValue, holeToPartOffset);
    }

    public Length getHoleDiameter() {
        return holeDiameter;
    }

    public void setHoleDiameter(Length holeDiameter) {
        this.holeDiameter = holeDiameter;
    }

    public Length getHoleSearchDistance() {
        return holeSearchDistance;
    }

    public void setHoleSearchDistance(Length holeSearchDistance) {
        this.holeSearchDistance = holeSearchDistance;
    }

    public Length getHolePitchTolerance() {
        return holePitchTolerance;
    }

    public void setHolePitchTolerance(Length holePitchTolerance) {
        this.holePitchTolerance = holePitchTolerance;
    }

    public double getHoleDiameterTolerance() {
        return holeDiameterTolerance;
    }

    public void setHoleDiameterTolerance(double holeDiameterTolerance) {
        this.holeDiameterTolerance = holeDiameterTolerance;
    }

    public Length getTapeWidth() {
        return tapeWidth;
    }

    public void setTapeWidth(Length tapeWidth) {
        Object oldValue = this.tapeWidth;
        this.tapeWidth = tapeWidth;
        firePropertyChange("tapeWidth", oldValue, tapeWidth);
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new HwgcFeederConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return null;
    }
}
