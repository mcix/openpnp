/*
 * Baseline footprint and nozzle configuration for DeltaProto standard chip
 * packages: R0201, C0201, R0402, C0402, R0603, C0603, R0805, C0805,
 * R1008, C1008, R1206, C1206.
 *
 * Footprints are IPC-style approximations — body size plus two terminal
 * pads — intended to give OpenPNP bottom vision something valid to work
 * with on the first import. The operator can refine them in the Packages
 * tab. Existing footprints (any package that already has pads) are never
 * overwritten.
 *
 * Nozzle tip assignments follow JUKI 500-series recommendations:
 *   501 → 0201   (Ø0.7/0.4 pin)
 *   502 → 0402   (Ø0.7/0.35 pin)
 *   503 → 0603   (Ø1.0/0.6 pin)
 *   504 → 0805, 1008, 1206  (Ø1.5/1.0 pin)
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.spi.Machine;
import org.openpnp.spi.NozzleTip;

final class BaselineFootprints {

    private BaselineFootprints() {}

    // ── Chip-size definitions ──────────────────────────────────────────────
    //
    // Each entry: bodyW, bodyH (mm), padW, padH (mm), padPitch (centre-to-centre),
    // part height (nominal body thickness), nozzle tip name.

    static final class ChipDef {
        final String size;       // e.g. "0402"
        final double bodyW;
        final double bodyH;
        final double padW;
        final double padH;
        final double padPitch;
        final double partHeight;
        final String nozzleTip;  // JUKI nozzle name, e.g. "502"

        ChipDef(String size, double bodyW, double bodyH,
                double padW, double padH, double padPitch,
                double partHeight, String nozzleTip) {
            this.size = size;
            this.bodyW = bodyW;
            this.bodyH = bodyH;
            this.padW = padW;
            this.padH = padH;
            this.padPitch = padPitch;
            this.partHeight = partHeight;
            this.nozzleTip = nozzleTip;
        }
    }

    /** Ordered list of chip sizes we support. */
    static final List<ChipDef> CHIP_DEFS;
    static {
        CHIP_DEFS = new ArrayList<>();
        //                   size    bodyW  bodyH  padW  padH  pitch  height  nozzle
        CHIP_DEFS.add(new ChipDef("0201", 0.60, 0.30, 0.30, 0.30, 0.65, 0.30, "501"));
        CHIP_DEFS.add(new ChipDef("0402", 1.00, 0.50, 0.50, 0.60, 1.10, 0.50, "502"));
        CHIP_DEFS.add(new ChipDef("0603", 1.60, 0.80, 0.80, 0.90, 1.60, 0.80, "503"));
        CHIP_DEFS.add(new ChipDef("0805", 2.00, 1.25, 1.00, 1.40, 2.00, 1.00, "504"));
        CHIP_DEFS.add(new ChipDef("1008", 2.50, 2.00, 1.20, 2.10, 2.50, 1.00, "504"));
        CHIP_DEFS.add(new ChipDef("1206", 3.20, 1.60, 1.10, 1.80, 3.20, 1.00, "504"));
    }

    /** Map from bare size to ChipDef for quick lookups. */
    private static final Map<String, ChipDef> BY_SIZE = new LinkedHashMap<>();
    static {
        for (ChipDef cd : CHIP_DEFS) {
            BY_SIZE.put(cd.size, cd);
        }
    }

    /** All nozzle tip names that a 503 might use (includes the second 503). */
    private static final String[] NOZZLE_503_NAMES = {"503", "503-2", "503 (1)", "503 (2)"};

    /** The two component-type prefixes we create packages for. */
    static final String[] PREFIXES = {"R", "C"};

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Extracts the bare chip size from a package id. Recognises both
     * prefixed ("R0402", "C0805") and bare ("0402") ids.
     * Returns {@code null} if not a known chip size.
     */
    static String extractSize(String packageId) {
        if (packageId == null) {
            return null;
        }
        String id = packageId.trim().toUpperCase();
        if (BY_SIZE.containsKey(id)) {
            return id;
        }
        // Strip R/C prefix and retry.
        if (id.length() > 1 && (id.charAt(0) == 'R' || id.charAt(0) == 'C')) {
            String bare = id.substring(1);
            if (BY_SIZE.containsKey(bare)) {
                return bare;
            }
        }
        return null;
    }

    /**
     * If {@code pkg.id} matches one of the baseline chip sizes (with or
     * without R/C prefix) and its footprint is currently empty, populates
     * it. Returns {@code true} iff a baseline was applied.
     */
    static boolean applyIfKnown(Package pkg) {
        if (pkg == null || pkg.getId() == null) {
            return false;
        }
        String size = extractSize(pkg.getId());
        if (size == null) {
            return false;
        }
        ChipDef cd = BY_SIZE.get(size);
        return applyFootprint(pkg, cd);
    }

    /**
     * Returns a nominal body-thickness (Z height) for the given package id
     * (bare or prefixed), or {@code null} if no baseline is known.
     */
    static Length defaultPartHeight(String packageId) {
        String size = extractSize(packageId);
        if (size == null) {
            return null;
        }
        return new Length(BY_SIZE.get(size).partHeight, LengthUnit.Millimeters);
    }

    /**
     * Returns the recommended nozzle tip name for the given package id
     * (bare or prefixed), or {@code null} if not a known chip size.
     */
    static String recommendedNozzleTip(String packageId) {
        String size = extractSize(packageId);
        if (size == null) {
            return null;
        }
        return BY_SIZE.get(size).nozzleTip;
    }

    // ── Setup: create all R/C packages, clearing old bare ones ─────────

    /**
     * Result of {@link #setupAllPackages(Machine)}.
     */
    static class SetupResult {
        int packagesCreated;
        int footprintsApplied;
        int nozzleTipsAssigned;
        int oldPackagesRemoved;
        final List<String> warnings = new ArrayList<>();

        @Override
        public String toString() {
            return String.format(
                    "SetupResult{created=%d, footprints=%d, nozzles=%d, oldRemoved=%d, warnings=%d}",
                    packagesCreated, footprintsApplied, nozzleTipsAssigned,
                    oldPackagesRemoved, warnings.size());
        }
    }

    /**
     * Creates (or updates) all R/C prefixed packages with correct
     * footprints and nozzle tip assignments. Removes bare-size packages
     * (e.g. "0402") that are no longer used.
     *
     * Must be called on the Swing EDT.
     */
    static SetupResult setupAllPackages() {
        SetupResult result = new SetupResult();
        Configuration config = Configuration.get();
        Machine machine = config.getMachine();

        // 1. Create or update R/C packages.
        for (ChipDef cd : CHIP_DEFS) {
            for (String prefix : PREFIXES) {
                String pkgId = prefix + cd.size;
                Package pkg = config.getPackage(pkgId);
                if (pkg == null) {
                    pkg = new Package(pkgId);
                    pkg.setDescription(prefix.equals("R") ? "Resistor " + cd.size
                            : "Capacitor " + cd.size);
                    config.addPackage(pkg);
                    result.packagesCreated++;
                }
                if (applyFootprint(pkg, cd)) {
                    result.footprintsApplied++;
                }
                // Nozzle tip assignment — for 503, also assign 503-2.
                String[] names = cd.nozzleTip.equals("503")
                        ? NOZZLE_503_NAMES : new String[]{cd.nozzleTip};
                boolean anyFound = false;
                for (String ntName : names) {
                    NozzleTip nt = machine.getNozzleTipByName(ntName);
                    if (nt != null) {
                        anyFound = true;
                        if (!pkg.getCompatibleNozzleTips().contains(nt)) {
                            pkg.addCompatibleNozzleTip(nt);
                            result.nozzleTipsAssigned++;
                        }
                    }
                }
                if (!anyFound) {
                    result.warnings.add("Nozzle tip '" + cd.nozzleTip
                            + "' not found — package " + pkgId + " has no nozzle assignment");
                }
            }
        }

        // 2. Remove old bare-size packages (e.g. "0402") if they exist
        // and no parts reference them.
        for (ChipDef cd : CHIP_DEFS) {
            Package old = config.getPackage(cd.size);
            if (old != null) {
                boolean inUse = false;
                for (org.openpnp.model.Part part : config.getParts()) {
                    if (part.getPackage() == old) {
                        inUse = true;
                        break;
                    }
                }
                if (inUse) {
                    result.warnings.add("Old package '" + cd.size
                            + "' still referenced by parts — not removed");
                }
                else {
                    config.removePackage(old);
                    result.oldPackagesRemoved++;
                }
            }
        }

        return result;
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private static boolean applyFootprint(Package pkg, ChipDef cd) {
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
        fp.setBodyWidth(cd.bodyW);
        fp.setBodyHeight(cd.bodyH);
        fp.addPad(pad("1", -cd.padPitch / 2, 0, cd.padW, cd.padH));
        fp.addPad(pad("2", +cd.padPitch / 2, 0, cd.padW, cd.padH));
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
