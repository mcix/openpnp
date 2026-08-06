/*
 * DeltaProto "Plankje" (strip carrier board) layout configuration.
 *
 * The plankje is a jig with 40 pins; each pin locates a cut strip of tape
 * components (a DeltaProtoStripFeeder). Pin 1 and pin 40 are taught by the
 * operator; every other pin position is linearly interpolated between them.
 *
 * Geometry per strip (in the default Y-down orientation):
 *   - The first component is 8.5 mm to the right of and 2 mm below the pin.
 *   - Subsequent components continue in the strip direction (Y down) at the
 *     feeder's pitch (4 mm or 2 mm).
 * For the other orientations the whole local frame is rotated with the strip
 * direction, so "right of the pin" follows the strip.
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

    /** Strip direction: which way the components run, seen from the pin. */
    enum StripDirection {
        Y_DOWN, Y_UP, X_LEFT, X_RIGHT;

        static StripDirection parse(String s, StripDirection fallback) {
            try {
                return valueOf(s);
            }
            catch (Exception e) {
                return fallback;
            }
        }
    }

    static final int PIN_FIRST = 1;
    static final int PIN_LAST = 40;

    /** First pickup offset from the pin, in the default Y-down frame (mm). */
    static final double FIRST_PICKUP_OFFSET_ALONG_MM = 2.0;   // "2 mm down"
    static final double FIRST_PICKUP_OFFSET_ACROSS_MM = 8.5;  // "8.5 mm to the right"

    // Placeholder defaults — teach the real pin 1 / pin 40 positions with the
    // down-looking camera and save them from the DeltaProto Settings tab.
    static final double DEFAULT_PIN1_X = 100.000;
    static final double DEFAULT_PIN1_Y = 100.000;
    static final double DEFAULT_PIN40_X = 490.000;
    static final double DEFAULT_PIN40_Y = 100.000;
    static final StripDirection DEFAULT_DIRECTION = StripDirection.Y_DOWN;
    /** Common pick Z for every strip on the plankje, in millimeters. */
    static final double DEFAULT_Z = -90.500;

    double pin1X, pin1Y;
    double pin40X, pin40Y;
    StripDirection direction = DEFAULT_DIRECTION;
    double z;

    static PlankjeLayout load() {
        PlankjeLayout l = new PlankjeLayout();
        l.pin1X = PREFS.getDouble("plankje.pin1.x", DEFAULT_PIN1_X);
        l.pin1Y = PREFS.getDouble("plankje.pin1.y", DEFAULT_PIN1_Y);
        l.pin40X = PREFS.getDouble("plankje.pin40.x", DEFAULT_PIN40_X);
        l.pin40Y = PREFS.getDouble("plankje.pin40.y", DEFAULT_PIN40_Y);
        l.direction = StripDirection.parse(
                PREFS.get("plankje.direction", DEFAULT_DIRECTION.name()), DEFAULT_DIRECTION);
        l.z = PREFS.getDouble("plankje.z", DEFAULT_Z);
        return l;
    }

    void save() {
        PREFS.putDouble("plankje.pin1.x", pin1X);
        PREFS.putDouble("plankje.pin1.y", pin1Y);
        PREFS.putDouble("plankje.pin40.x", pin40X);
        PREFS.putDouble("plankje.pin40.y", pin40Y);
        PREFS.put("plankje.direction", direction.name());
        PREFS.putDouble("plankje.z", z);
    }

    /** Unit step vector (dx, dy) along the strip direction, in OpenPNP mm coords
     *  where +Y is towards the back of the machine. "Y down" = decreasing Y. */
    private double[] stepVector() {
        switch (direction) {
            case Y_UP:    return new double[] {0, 1};
            case X_LEFT:  return new double[] {-1, 0};
            case X_RIGHT: return new double[] {1, 0};
            case Y_DOWN:
            default:      return new double[] {0, -1};
        }
    }

    /** "Across" unit vector: 90° clockwise from the strip direction, i.e. the
     *  "to the right of the pin" direction in the default Y-down frame. */
    private double[] acrossVector() {
        double[] step = stepVector();
        // Rotate step 90° counter-clockwise: (x, y) -> (-y, x).
        // For Y-down (0,-1) this yields (1, 0) = +X = "right", matching the
        // spec's point of view; the other orientations follow the rotation.
        return new double[] {-step[1], step[0]};
    }

    /** Interpolated pin position, pins 1..40. Returns null outside that range. */
    Location pinLocation(int pin) {
        if (pin < PIN_FIRST || pin > PIN_LAST) {
            return null;
        }
        double t = (pin - PIN_FIRST) / (double) (PIN_LAST - PIN_FIRST);
        double x = pin1X + t * (pin40X - pin1X);
        double y = pin1Y + t * (pin40Y - pin1Y);
        return new Location(LengthUnit.Millimeters, x, y, z, 0);
    }

    /**
     * Pickup location for component {@code index} (0 = first) of the strip on
     * {@code pin}, at the given pitch. Returns null for an invalid pin.
     */
    Location pickupLocation(int pin, int index, double pitchMm) {
        Location pinLoc = pinLocation(pin);
        if (pinLoc == null) {
            return null;
        }
        double[] step = stepVector();
        double[] across = acrossVector();
        double along = FIRST_PICKUP_OFFSET_ALONG_MM + index * pitchMm;
        double x = pinLoc.getX()
                + across[0] * FIRST_PICKUP_OFFSET_ACROSS_MM + step[0] * along;
        double y = pinLoc.getY()
                + across[1] * FIRST_PICKUP_OFFSET_ACROSS_MM + step[1] * along;
        return new Location(LengthUnit.Millimeters, x, y, z, 0);
    }
}
