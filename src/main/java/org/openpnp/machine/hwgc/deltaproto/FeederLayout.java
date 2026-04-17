/*
 * DeltaProto HWGC feeder layout configuration.
 *
 * Holds the four corner positions of the feeder bed and linearly interpolates
 * per-slot pick locations from them. Persisted in the user's Java Preferences
 * so it survives across sessions without touching any OpenPNP config file.
 *
 * Slot convention (matches the user's feeder numbering table):
 *   - Front row: slotIndex 1..25, interpolated between FL (slot 1) and FR (slot 25).
 *   - Back row:  slotIndex 26..50, interpolated between BL (slot 26) and BR (slot 50).
 *
 * Units: the corner X/Y values are stored as raw doubles in whatever unit the
 * source machine reports. A {@code scale} factor (default 0.01) multiplies the
 * raw values when building the {@link Location}, converting HWGC-native
 * hundredths-of-a-millimeter into OpenPNP's millimeters. Users whose data is
 * already in mm can set scale to 1.0.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.prefs.Preferences;

import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

class FeederLayout {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(FeederLayout.class);

    // Default corner coordinates supplied by the operator, in millimeters.
    // Front row = slots 1..25 = bottom of machine = low Y in OpenPNP coords.
    // Back row  = slots 26..50 = top of machine  = high Y.
    // Values measured on the real DeltaProto machine via the down-looking camera.
    static final double DEFAULT_FL_X = 24.727;
    static final double DEFAULT_FL_Y = 19.952;
    static final double DEFAULT_FR_X = 208.781;
    static final double DEFAULT_FR_Y = 20.283;
    static final double DEFAULT_BL_X = 20.326;
    static final double DEFAULT_BL_Y = 511.652;
    static final double DEFAULT_BR_X = 404.699;
    static final double DEFAULT_BR_Y = 511.805;
    static final double DEFAULT_SCALE = 1.0;
    /** Common pick Z for every feeder on this bed, in millimeters. */
    static final double DEFAULT_Z = -90.500;

    static final int FRONT_FIRST = 1;
    static final int FRONT_LAST = 25;
    static final int BACK_FIRST = 26;
    static final int BACK_LAST = 50;

    double flX, flY;
    double frX, frY;
    double blX, blY;
    double brX, brY;
    double scale;
    double z;

    static FeederLayout load() {
        FeederLayout l = new FeederLayout();
        l.flX = PREFS.getDouble("layout.fl.x", DEFAULT_FL_X);
        l.flY = PREFS.getDouble("layout.fl.y", DEFAULT_FL_Y);
        l.frX = PREFS.getDouble("layout.fr.x", DEFAULT_FR_X);
        l.frY = PREFS.getDouble("layout.fr.y", DEFAULT_FR_Y);
        l.blX = PREFS.getDouble("layout.bl.x", DEFAULT_BL_X);
        l.blY = PREFS.getDouble("layout.bl.y", DEFAULT_BL_Y);
        l.brX = PREFS.getDouble("layout.br.x", DEFAULT_BR_X);
        l.brY = PREFS.getDouble("layout.br.y", DEFAULT_BR_Y);
        l.scale = PREFS.getDouble("layout.scale", DEFAULT_SCALE);
        l.z = PREFS.getDouble("layout.z", DEFAULT_Z);
        return l;
    }

    void save() {
        PREFS.putDouble("layout.fl.x", flX);
        PREFS.putDouble("layout.fl.y", flY);
        PREFS.putDouble("layout.fr.x", frX);
        PREFS.putDouble("layout.fr.y", frY);
        PREFS.putDouble("layout.bl.x", blX);
        PREFS.putDouble("layout.bl.y", blY);
        PREFS.putDouble("layout.br.x", brX);
        PREFS.putDouble("layout.br.y", brY);
        PREFS.putDouble("layout.scale", scale);
        PREFS.putDouble("layout.z", z);
    }

    /**
     * Linearly interpolates the pick location for the given slot. Returns
     * {@code null} if the slot is outside the configured 1..50 range.
     */
    Location locationForSlot(int slotIndex) {
        double x, y;
        if (slotIndex >= FRONT_FIRST && slotIndex <= FRONT_LAST) {
            double t = (slotIndex - FRONT_FIRST) / (double) (FRONT_LAST - FRONT_FIRST);
            x = flX + t * (frX - flX);
            y = flY + t * (frY - flY);
        }
        else if (slotIndex >= BACK_FIRST && slotIndex <= BACK_LAST) {
            double t = (slotIndex - BACK_FIRST) / (double) (BACK_LAST - BACK_FIRST);
            x = blX + t * (brX - blX);
            y = blY + t * (brY - blY);
        }
        else {
            return null;
        }
        return new Location(LengthUnit.Millimeters, x * scale, y * scale, z, 0);
    }
}
