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

import java.util.List;

import javax.swing.Action;

import org.apache.commons.io.IOUtils;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.hwgc.wizards.HwgcFeederConfigurationWizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.feeder.ReferenceStripFeeder;
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
 * Slot numbering: feederNumber is the user-visible 1..50 slot index
 * (front 1..25, back 26..50). The HwgcDriver protocol is 0-indexed,
 * so we subtract 1 before sending to hardware.
 */
public class HwgcFeeder extends ReferenceFeeder {

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
    private Length holeDiameter = new Length(1.5, LengthUnit.Millimeters);

    /** Search radius for the closest hole to the expected pick location. */
    @Element(required = false)
    private Length holeSearchDistance = new Length(4, LengthUnit.Millimeters);

    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

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
        Logger.debug("HWGC feeder {} (hw index {}): opening for pick", feederNumber, hwIndex);
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
        Logger.debug("HWGC feeder {} (hw index {}): closing after pick", feederNumber, hwIndex);
        driver.sendFeeder(hwIndex, false);
    }

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

    private Location findClosestHole(Camera camera, Location expected) throws Exception {
        MovableUtils.moveToLocationAtSafeZ(camera, expected);
        try {
            pipeline.setProperty("camera", camera);
            pipeline.setProperty("feeder", this);
            pipeline.setProperty("sprocketHole.diameter", holeDiameter);
            pipeline.setProperty("sprocketHole.maxDistance", holeSearchDistance);
            pipeline.setProperty("sprocketHole.center", expected);
            pipeline.setProperty("MaskCircle.center", expected);
            pipeline.process();

            if (MainFrame.get() != null) {
                try {
                    MainFrame.get().getCameraViews().getCameraView(camera)
                            .showFilteredImage(
                                    OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), 250);
                }
                catch (Exception e) {
                    // headless / no camera view — ignore.
                }
            }

            List<CvStage.Result.Circle> results = pipeline
                    .getExpectedResult(VisionUtils.PIPELINE_RESULTS_NAME)
                    .getExpectedListModel(CvStage.Result.Circle.class,
                            new Exception("HWGC feeder " + feederNumber
                                    + ": no tape sprocket holes detected."));
            results.sort((a, b) -> {
                Double da = VisionUtils.getPixelLocation(camera, a.x, a.y)
                        .getLinearDistanceTo(expected);
                Double db = VisionUtils.getPixelLocation(camera, b.x, b.y)
                        .getLinearDistanceTo(expected);
                return da.compareTo(db);
            });
            CvStage.Result.Circle closest = results.get(0);
            return VisionUtils.getPixelLocation(camera, closest.x, closest.y);
        }
        finally {
            pipeline.setProperty("sprocketHole.center", null);
            pipeline.setProperty("MaskCircle.center", null);
        }
    }

    public CvPipeline getPipeline() {
        return pipeline;
    }

    public void resetPipeline() {
        pipeline = createDefaultPipeline();
    }

    private static CvPipeline createDefaultPipeline() {
        try {
            String xml = IOUtils.toString(ReferenceStripFeeder.class
                    .getResource("ReferenceStripFeeder-DefaultPipeline.xml"));
            return new CvPipeline(xml);
        }
        catch (Exception e) {
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
