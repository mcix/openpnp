/*
 * Baseline Footprint generator for the four standard chip packages DeltaProto
 * uses 99% of the time: 0201, 0402, 0603, 0805.
 *
 * These are coarse IPC-style approximations — body size plus two terminal
 * pads — intended to give OpenPNP bottom vision something valid to work with
 * on the first import. The operator can refine them in the Packages tab.
 * Existing footprints (any package that already has pads) are never touched.
 */
package org.openpnp.machine.hwgc.deltaproto;

import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;

final class BaselineFootprints {

    private BaselineFootprints() {}

    /**
     * If {@code pkg.id} matches one of the baseline chip sizes and its
     * footprint is currently empty, populates it. Returns {@code true} iff a
     * baseline was applied.
     */
    static boolean applyIfKnown(Package pkg) {
        if (pkg == null || pkg.getId() == null) {
            return false;
        }
        String id = pkg.getId().trim().toUpperCase();
        switch (id) {
            case "0201":
                return apply(pkg, 0.60, 0.30, 0.30, 0.30, 0.65);
            case "0402":
                return apply(pkg, 1.00, 0.50, 0.50, 0.60, 1.10);
            case "0603":
                return apply(pkg, 1.60, 0.80, 0.80, 0.90, 1.60);
            case "0805":
                return apply(pkg, 2.00, 1.25, 1.00, 1.40, 2.00);
            default:
                return false;
        }
    }

    private static boolean apply(Package pkg, double bodyW, double bodyH,
            double padW, double padH, double padPitch) {
        Footprint fp = pkg.getFootprint();
        if (fp == null) {
            fp = new Footprint();
            pkg.setFootprint(fp);
        }
        if (!fp.getPads().isEmpty()) {
            // Operator has already drawn a footprint; leave it alone.
            return false;
        }
        fp.setUnits(LengthUnit.Millimeters);
        fp.setBodyWidth(bodyW);
        fp.setBodyHeight(bodyH);
        fp.addPad(pad("1", -padPitch / 2, 0, padW, padH));
        fp.addPad(pad("2", +padPitch / 2, 0, padW, padH));
        pkg.fireFootprintChanged();
        return true;
    }

    private static Footprint.Pad pad(String name, double x, double y, double w, double h) {
        Footprint.Pad p = new Footprint.Pad();
        p.setName(name);
        p.setX(x);
        p.setY(y);
        p.setWidth(w);
        p.setHeight(h);
        return p;
    }
}
