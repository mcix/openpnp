/*
 * DeltaProto covered strip feeder V1: one cut strip of tape components,
 * located by a pin on the "plankje" carrier board, with a sliding cover (lid)
 * that must be pushed open by nozzle N4 before every pick.
 *
 * Lives in the isolated org.openpnp.machine.hwgc.deltaproto subpackage so
 * upstream merges never touch DeltaProto code.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.prefs.Preferences;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.hwgc.HwgcDriver;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

/**
 * A strip on the DeltaProto plankje (see {@link PlankjeLayout}) with a
 * sliding cover.
 *
 * <p>The feeder's origin is the pin the strip is placed on, which is also the
 * reference hole of the strip. The machine origin is at the bottom left
 * (+Y = north, away from the operator); the strip runs in the negative Y
 * direction (southwards, the plankje default). The strip itself remains
 * static in the machine and the pick location advances along it, like a
 * {@code ReferenceStripFeeder} without vision.
 *
 * <p>Before every pick the cover is slid open with the SAME nozzle that will
 * pick the component (no nozzle change needed between slide and pick):
 * <ol>
 *   <li>Move the nozzle (at safe Z) to the cover start point just north of
 *       the origin: X +2.25 mm, Y +5 mm from the pin.</li>
 *   <li>Plunge to the plankje's configured cover Z.</li>
 *   <li>Move SLOWLY in −Y (south), overshooting the Y of the component to
 *       pick by 1 mm, pushing the lid open.</li>
 *   <li>Retract to safe Z; the same nozzle then picks the component from
 *       the paper strip at the plankje's configured pick Z.</li>
 * </ol>
 *
 * <p>After the pick, {@link #postPick} moves the nozzle (retracted, Z 0)
 * back over the cover plunge point, so it exits the stack of feeders
 * straight north through this strip's own lane instead of dragging the
 * picked part diagonally across neighboring covers.
 *
 * <p>{@code feedPosition} is the index of the NEXT component to pick
 * (0 = first component of the strip). It is advanced on every feed and
 * persisted immediately to Java Preferences so the position survives a
 * restart or crash — the machine.xml copy is only a mirror for visibility.
 */
public class DeltaProtoCoveredStripFeederV1 extends ReferenceFeeder {

    /** How far the cover slide overshoots the pick Y (southwards, −Y), mm. */
    static final double COVER_OVERSHOOT_MM = 1.0;

    /** Crash-safe store for feed positions, keyed by feeder id. */
    private static final Preferences FEED_POS_PREFS =
            Preferences.userNodeForPackage(DeltaProtoCoveredStripFeederV1.class);

    /** Pin number on the plankje, 1..8. */
    @Attribute(required = false)
    private int pin = 1;

    /** Component pitch along the strip: 4 mm (standard) or 2 mm. */
    @Attribute(required = false)
    private double pitchMm = 4.0;

    /** Lateral (+X) offset from the hole line to the component centers, mm.
     *  3.5 mm as per EIA-481 for 8 mm tape (tape width / 2 − 0.5 mm). Note
     *  this differs from the cover plunge line, which is at +2.25 mm. */
    @Attribute(required = false)
    private double partLateralMm = 3.5;

    /** Along-strip distance from the origin hole to the first component, mm.
     *  The strip runs in −Y, so the first part is this far SOUTH of the pin. */
    @Attribute(required = false)
    private double firstPartAlongMm = 2.0;

    /** Cover slide start, relative to the origin pin (mm): just north of the
     *  origin — on the component line, 5 mm before it (+Y). */
    @Attribute(required = false)
    private double coverStartXOffsetMm = 2.25;

    @Attribute(required = false)
    private double coverStartYOffsetMm = 5.0;

    /** Speed factor (0..1) for the slow −Y cover-opening move. */
    @Attribute(required = false)
    private double coverSlideSpeed = 0.05;

    /** Mirror of the persisted feed position (next pickup index, 0-based). */
    @Attribute(required = false)
    private int feedPosition = 0;

    /** True once the Preferences override has been applied after load. */
    private transient boolean feedPositionLoaded = false;

    /** Pick location computed by the last feed(). */
    private transient Location lastPickLocation;

    private String feedPosKey() {
        return "feedpos." + getId();
    }

    private synchronized void ensureFeedPositionLoaded() {
        if (!feedPositionLoaded) {
            // Preferences are written on every advance and therefore survive
            // a crash; machine.xml is only saved when the config is saved.
            feedPosition = FEED_POS_PREFS.getInt(feedPosKey(), feedPosition);
            feedPositionLoaded = true;
        }
    }

    public int getFeedPosition() {
        ensureFeedPositionLoaded();
        return feedPosition;
    }

    public void setFeedPosition(int feedPosition) {
        ensureFeedPositionLoaded();
        int oldValue = this.feedPosition;
        this.feedPosition = Math.max(0, feedPosition);
        FEED_POS_PREFS.putInt(feedPosKey(), this.feedPosition);
        try {
            FEED_POS_PREFS.flush();
        }
        catch (Exception e) {
            Logger.warn("Failed to flush feed position for {}: {}", getName(), e.getMessage());
        }
        lastPickLocation = null;
        firePropertyChange("feedPosition", oldValue, this.feedPosition);
    }

    /** Pin number clamped into the plankje range, so derived locations are
     *  always available (the setter clamps too; this guards XML edits). */
    private int clampedPin() {
        return Math.max(PlankjeLayout.PIN_FIRST, Math.min(PlankjeLayout.PIN_LAST, pin));
    }

    /** Origin of this feeder: the pin the strip is placed on, which is the
     *  reference hole of the strip. Never null. */
    public Location getOriginLocation() {
        return PlankjeLayout.load().pinLocation(clampedPin());
    }

    /** Pickup location for the given component index (strip runs −Y, south). */
    private Location computePickLocation(int index) {
        Location origin = getOriginLocation();
        // origin.Z is the plankje pick Z.
        return new Location(LengthUnit.Millimeters,
                origin.getX() + partLateralMm,
                origin.getY() - (firstPartAlongMm + index * pitchMm),
                origin.getZ(),
                0);
    }

    @Override
    public Location getPickLocation() throws Exception {
        if (lastPickLocation != null) {
            return lastPickLocation;
        }
        return computePickLocation(getFeedPosition());
    }

    /** The pickup location of the next component (ignores the feed cache). */
    public Location getNextPickLocation() throws Exception {
        return computePickLocation(getFeedPosition());
    }

    /** Cover slide start (plunge) point at cover Z, relative to the origin pin. */
    public Location getCoverStartLocation() {
        Location origin = getOriginLocation();
        return new Location(LengthUnit.Millimeters,
                origin.getX() + coverStartXOffsetMm,
                origin.getY() + coverStartYOffsetMm,
                PlankjeLayout.load().coverZ,
                0);
    }

    /**
     * Teach the cover plunge point. X/Y are stored as offsets from the origin
     * pin; Z is stored as the plankje-wide cover Z (shared by all 8 feeders).
     */
    public void setCoverStartLocation(Location location) {
        Location old = getCoverStartLocation();
        location = location.convertToUnits(LengthUnit.Millimeters);
        Location origin = getOriginLocation();
        this.coverStartXOffsetMm = location.getX() - origin.getX();
        this.coverStartYOffsetMm = location.getY() - origin.getY();
        PlankjeLayout l = PlankjeLayout.load();
        if (l.coverZ != location.getZ()) {
            l.coverZ = location.getZ();
            l.save();
        }
        firePropertyChange("coverStartLocation", old, getCoverStartLocation());
    }

    /** The first pickup location of the strip (component index 0). */
    public Location getFirstPickLocation() {
        return computePickLocation(0);
    }

    /**
     * Teach the first pickup location (component index 0). X/Y are stored as
     * offsets from the origin pin; Z is stored as the plankje-wide pick Z
     * (shared by all 8 feeders).
     */
    public void setFirstPickLocation(Location location) {
        Location old = getFirstPickLocation();
        location = location.convertToUnits(LengthUnit.Millimeters);
        Location origin = getOriginLocation();
        this.partLateralMm = location.getX() - origin.getX();
        // Along-strip distance is measured southwards (−Y) from the pin.
        this.firstPartAlongMm = origin.getY() - location.getY();
        PlankjeLayout l = PlankjeLayout.load();
        if (l.z != location.getZ()) {
            l.z = location.getZ();
            l.save();
        }
        lastPickLocation = null;
        firePropertyChange("firstPickLocation", old, getFirstPickLocation());
    }

    /** XY tolerance for the step guards, mm: a step only runs when the nozzle
     *  is where the previous step left it, so we can never drag across covers. */
    private static final double STEP_GUARD_XY_MM = 0.5;
    private static final double STEP_GUARD_Z_MM = 0.25;

    /** Step 1: move the nozzle at safe Z until it is above the plunge point.
     *  Does NOT descend. */
    public void stepMoveAbovePlungePoint(Nozzle nozzle) throws Exception {
        Location start = getCoverStartLocation();
        nozzle.moveToSafeZ();
        // NaN Z = keep the current (safe) Z, XY travel only.
        nozzle.moveTo(start.derive(null, null, Double.NaN, null));
        Logger.info("DeltaProtoCoveredStripFeederV1 {}: {} above plunge point {}",
                getName(), nozzle.getName(), start);
    }

    /** Guard: throws unless the nozzle is within tolerance of the given XY. */
    private void assertNozzleAtXy(Nozzle nozzle, Location expected, String step)
            throws Exception {
        Location current = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location target = expected.convertToUnits(LengthUnit.Millimeters);
        double dx = current.getX() - target.getX();
        double dy = current.getY() - target.getY();
        if (Math.hypot(dx, dy) > STEP_GUARD_XY_MM) {
            throw new Exception("DeltaProtoCoveredStripFeederV1 " + getName() + ": " + step
                    + " refused — " + nozzle.getName()
                    + " is not above the plunge point (off by "
                    + String.format("%.2f/%.2f mm", dx, dy)
                    + "). Run the previous step first.");
        }
    }

    /** Step 2: plunge the nozzle straight down to cover Z. Only allowed when
     *  it is already above the plunge point (run step 1 first). */
    public void stepPlungeToCoverZ(Nozzle nozzle) throws Exception {
        Location start = getCoverStartLocation();
        assertNozzleAtXy(nozzle, start, "plunge");
        nozzle.moveTo(start);
        Logger.info("DeltaProtoCoveredStripFeederV1 {}: {} plunged to cover Z at {}",
                getName(), nozzle.getName(), start);
    }

    /** Step 3: slide slowly in −Y (south) at cover Z, overshooting the next
     *  pick Y by 1 mm, pushing the lid open. Only allowed when the nozzle is
     *  plunged at the start point (run step 2 first). */
    public void stepSlideCoverOpen(Nozzle nozzle) throws Exception {
        stepSlideCoverOpen(nozzle, getNextPickLocation());
    }

    private void stepSlideCoverOpen(Nozzle nozzle, Location pickLocation) throws Exception {
        Location start = getCoverStartLocation();
        assertNozzleAtXy(nozzle, start, "cover slide");
        Location current = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        if (Math.abs(current.getZ() - start.getZ()) > STEP_GUARD_Z_MM) {
            throw new Exception("DeltaProtoCoveredStripFeederV1 " + getName()
                    + ": cover slide refused — " + nozzle.getName() + " is not at cover Z ("
                    + String.format("%.3f vs %.3f mm", current.getZ(), start.getZ())
                    + "). Plunge first.");
        }
        // Overshoot southwards: 1 mm past (below) the pick Y.
        Location end = start.derive(null,
                pickLocation.getY() - COVER_OVERSHOOT_MM, null, null);
        HwgcDriver hwgc = findHwgcDriver();
        if (hwgc != null) {
            // The HWGC firmware lifts all Z axes to home on a normal XY goto
            // (0x60) and runs it at its own fast profile — that would release
            // the cover and slam it open. Use the calibrated open-loop slow
            // jog move instead, which keeps Z down and is slow (speed 1).
            Location from = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            double dyMm = end.convertToUnits(LengthUnit.Millimeters).getY()
                    - from.getY();
            hwgc.moveXyRelativeOpenLoop(0, dyMm);
        }
        else {
            // Simulation / non-HWGC machine: regular slow move.
            nozzle.moveTo(end, coverSlideSpeed);
        }
        Logger.info("DeltaProtoCoveredStripFeederV1 {}: cover slid open from {} to {}",
                getName(), start, end);
    }

    /** The HWGC driver of this machine, or null (e.g. in simulation). */
    private static HwgcDriver findHwgcDriver() {
        for (org.openpnp.spi.Driver d : Configuration.get().getMachine().getDrivers()) {
            if (d instanceof HwgcDriver) {
                return (HwgcDriver) d;
            }
        }
        return null;
    }

    /** Step 4: retract the nozzle to safe Z so it can come in for the pick. */
    public void stepRetractCoverNozzle(Nozzle nozzle) throws Exception {
        nozzle.moveToSafeZ();
        Logger.info("DeltaProtoCoveredStripFeederV1 {}: {} retracted to safe Z",
                getName(), nozzle.getName());
    }

    /**
     * Slide the cover open with the given nozzle so that the component at
     * {@code pickLocation} is exposed: plunge at the fixed start point north
     * of the origin, then move slowly in −Y to pick Y − 1 mm, then retract.
     */
    public void slideCoverOpen(Nozzle nozzle, Location pickLocation) throws Exception {
        Location start = getCoverStartLocation();
        // Travel at safe Z to the start point, then plunge to cover Z.
        MovableUtils.moveToLocationAtSafeZ(nozzle, start);
        // Slide slowly in −Y, overshooting the pick Y by 1 mm.
        stepSlideCoverOpen(nozzle, pickLocation);
        // Retract so the same nozzle can come in for the pick.
        stepRetractCoverNozzle(nozzle);
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        int index = getFeedPosition();
        Location pickLocation = computePickLocation(index);

        // Slide the cover open with the SAME nozzle that will do the pick.
        slideCoverOpen(nozzle, pickLocation);

        // Advance and persist immediately: the component at `index` is
        // consumed by this feed whether or not the pick succeeds, so a retry
        // (or a restart) continues at the next component instead of hammering
        // the same empty slot.
        setFeedPosition(index + 1);
        // setFeedPosition clears lastPickLocation — restore it for this pick.
        lastPickLocation = pickLocation;
        Logger.info("DeltaProtoCoveredStripFeederV1 {}: feed index {} at {} using {}",
                getName(), index, pickLocation, nozzle.getName());
    }

    /**
     * Called by the job processor (and the manual pick action) after the
     * pick, with the nozzle already retracted to safe Z. Exit the stack of
     * feeders through this strip's own lane: XY-move (Z stays retracted at
     * 0) back over the cover plunge point north of the origin, so the next
     * move starts outside the feeder area instead of dragging the picked
     * part diagonally across neighboring covers.
     */
    @Override
    public void postPick(Nozzle nozzle) throws Exception {
        Location start = getCoverStartLocation();
        nozzle.moveToSafeZ();
        // NaN Z / rotation = keep the current (retracted) Z and rotation.
        nozzle.moveTo(start.derive(null, null, Double.NaN, Double.NaN));
        Logger.info("DeltaProtoCoveredStripFeederV1 {}: {} exited north via plunge point",
                getName(), nozzle.getName());
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new DeltaProtoCoveredStripFeederV1ConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public org.openpnp.spi.PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public javax.swing.Action[] getPropertySheetHolderActions() {
        return null;
    }

    public int getPin() {
        return pin;
    }

    public void setPin(int pin) {
        int oldValue = this.pin;
        this.pin = Math.max(PlankjeLayout.PIN_FIRST, Math.min(PlankjeLayout.PIN_LAST, pin));
        lastPickLocation = null;
        firePropertyChange("pin", oldValue, this.pin);
        // The derived, teachable locations moved along with the pin.
        firePropertyChange("coverStartLocation", null, getCoverStartLocation());
        firePropertyChange("firstPickLocation", null, getFirstPickLocation());
    }

    public double getPitchMm() {
        return pitchMm;
    }

    public void setPitchMm(double pitchMm) {
        double oldValue = this.pitchMm;
        this.pitchMm = pitchMm;
        lastPickLocation = null;
        firePropertyChange("pitchMm", oldValue, this.pitchMm);
    }

    public double getPartLateralMm() {
        return partLateralMm;
    }

    public void setPartLateralMm(double partLateralMm) {
        double oldValue = this.partLateralMm;
        this.partLateralMm = partLateralMm;
        lastPickLocation = null;
        firePropertyChange("partLateralMm", oldValue, this.partLateralMm);
    }

    public double getFirstPartAlongMm() {
        return firstPartAlongMm;
    }

    public void setFirstPartAlongMm(double firstPartAlongMm) {
        double oldValue = this.firstPartAlongMm;
        this.firstPartAlongMm = firstPartAlongMm;
        lastPickLocation = null;
        firePropertyChange("firstPartAlongMm", oldValue, this.firstPartAlongMm);
    }

    public double getCoverSlideSpeed() {
        return coverSlideSpeed;
    }

    public void setCoverSlideSpeed(double coverSlideSpeed) {
        double oldValue = this.coverSlideSpeed;
        this.coverSlideSpeed = Math.max(0.01, Math.min(1.0, coverSlideSpeed));
        firePropertyChange("coverSlideSpeed", oldValue, this.coverSlideSpeed);
    }
}
