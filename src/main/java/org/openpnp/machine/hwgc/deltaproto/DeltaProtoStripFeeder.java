/*
 * DeltaProto strip feeder: one cut strip of tape components located by a pin
 * on the "plankje" carrier board.
 *
 * Lives in the isolated org.openpnp.machine.hwgc.deltaproto subpackage so
 * upstream merges never touch DeltaProto code.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.prefs.Preferences;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

/**
 * A strip of components on the DeltaProto plankje (see {@link PlankjeLayout}).
 *
 * <p>The pick location is fully derived: pin position (interpolated between
 * the taught pin 1 and pin 40), plus the first-pickup offset, plus
 * {@code feedPosition × pitch} along the configured strip direction.
 *
 * <p>{@code feedPosition} is the index of the NEXT component to pick
 * (0 = first component of the strip). It is advanced on every feed and
 * persisted immediately to Java Preferences so the position survives a
 * restart or crash — the machine.xml copy is only a mirror for visibility.
 */
public class DeltaProtoStripFeeder extends ReferenceFeeder {

    /** Crash-safe store for feed positions, keyed by feeder id. */
    private static final Preferences FEED_POS_PREFS =
            Preferences.userNodeForPackage(DeltaProtoStripFeeder.class);

    /** Pin number on the plankje, 1..40. */
    @Attribute(required = false)
    private int pin = 1;

    /** Component pitch along the strip: 4 mm (standard) or 2 mm. */
    @Attribute(required = false)
    private double pitchMm = 4.0;

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

    /** Location of this strip's pin on the plankje. */
    public Location getPinLocation() {
        return PlankjeLayout.load().pinLocation(pin);
    }

    /** Pickup location for the given component index. */
    private Location computePickLocation(int index) throws Exception {
        Location loc = PlankjeLayout.load().pickupLocation(pin, index, pitchMm);
        if (loc == null) {
            throw new Exception("DeltaProtoStripFeeder " + getName()
                    + ": pin " + pin + " outside plankje range "
                    + PlankjeLayout.PIN_FIRST + ".." + PlankjeLayout.PIN_LAST);
        }
        return loc;
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

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        int index = getFeedPosition();
        lastPickLocation = computePickLocation(index);
        // Advance and persist immediately: the component at `index` is
        // consumed by this feed whether or not the pick succeeds, so a retry
        // (or a restart) continues at the next component instead of hammering
        // the same empty slot.
        setFeedPosition(index + 1);
        // setFeedPosition clears lastPickLocation — restore it for this pick.
        lastPickLocation = PlankjeLayout.load().pickupLocation(pin, index, pitchMm);
        Logger.info("DeltaProtoStripFeeder {}: feed index {} at {}",
                getName(), index, lastPickLocation);
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new DeltaProtoStripFeederConfigurationWizard(this);
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
}
