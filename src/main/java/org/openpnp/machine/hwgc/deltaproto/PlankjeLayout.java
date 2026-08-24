/*
 * DeltaProto "Plankje" (strip carrier board) layout configuration.
 *
 * The plankje is a jig with 8 pins; each pin locates a covered cut strip of
 * tape components (a {@link DeltaProtoCoveredStripFeederV1}). Pin 1 and pin 8
 * are taught by the operator; every other pin position is linearly
 * interpolated between them. The nominal distance from pin 1 to pin 8 is
 * 73.5 mm (7 gaps of 10.5 mm).
 *
 * The pin is the origin of the strip: the strip's reference hole is placed
 * over the pin. The machine origin is at the bottom left (+Y = north, away
 * from the operator); strips run in the negative Y direction (southwards).
 *
 * Two Z heights are shared by all strips on the plankje:
 *   - z:      pick Z, the top of the components in the tape pockets.
 *   - coverZ: the Z at which nozzle N4 engages the sliding cover (lid).
 *
 * Persisted in the user's Java Preferences like {@link FeederLayout} so it
 * survives across sessions without touching any OpenPNP config file.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.prefs.Preferences;

import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

class PlankjeLayout {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(PlankjeLayout.class);

    static final int PIN_FIRST = 1;
    static final int PIN_LAST = 8;

    /** Nominal distance from pin 1 to pin 8 (7 gaps of 10.5 mm). */
    static final double PIN1_TO_PIN8_MM = 73.5;

    // Placeholder defaults — teach the real pin 1 / pin 8 positions with the
    // down-looking camera and save them from the DeltaProto Settings tab.
    // Pin 8 defaults to pin 1 + 73.5 mm along X.
    static final double DEFAULT_PIN1_X = 100.000;
    static final double DEFAULT_PIN1_Y = 100.000;
    static final double DEFAULT_PIN8_X = DEFAULT_PIN1_X + PIN1_TO_PIN8_MM;
    static final double DEFAULT_PIN8_Y = DEFAULT_PIN1_Y;
    /** Common pick Z for every strip on the plankje, in millimeters. */
    static final double DEFAULT_Z = -90.500;
    /** Common cover-slide Z (nozzle N4 pressing on the lid), in millimeters. */
    static final double DEFAULT_COVER_Z = -89.000;

    double pin1X, pin1Y;
    double pin8X, pin8Y;
    double z;
    double coverZ;

    static PlankjeLayout load() {
        PlankjeLayout l = new PlankjeLayout();
        l.pin1X = PREFS.getDouble("plankje.pin1.x", DEFAULT_PIN1_X);
        l.pin1Y = PREFS.getDouble("plankje.pin1.y", DEFAULT_PIN1_Y);
        l.pin8X = PREFS.getDouble("plankje.pin8.x", DEFAULT_PIN8_X);
        l.pin8Y = PREFS.getDouble("plankje.pin8.y", DEFAULT_PIN8_Y);
        l.z = PREFS.getDouble("plankje.z", DEFAULT_Z);
        l.coverZ = PREFS.getDouble("plankje.coverZ", DEFAULT_COVER_Z);
        return l;
    }

    void save() {
        PREFS.putDouble("plankje.pin1.x", pin1X);
        PREFS.putDouble("plankje.pin1.y", pin1Y);
        PREFS.putDouble("plankje.pin8.x", pin8X);
        PREFS.putDouble("plankje.pin8.y", pin8Y);
        PREFS.putDouble("plankje.z", z);
        PREFS.putDouble("plankje.coverZ", coverZ);
    }

    /** Interpolated pin position, pins 1..8. Returns null outside that range.
     *  Z is the plankje pick Z. */
    Location pinLocation(int pin) {
        if (pin < PIN_FIRST || pin > PIN_LAST) {
            return null;
        }
        double t = (pin - PIN_FIRST) / (double) (PIN_LAST - PIN_FIRST);
        double x = pin1X + t * (pin8X - pin1X);
        double y = pin1Y + t * (pin8Y - pin1Y);
        return new Location(LengthUnit.Millimeters, x, y, z, 0);
    }
}
