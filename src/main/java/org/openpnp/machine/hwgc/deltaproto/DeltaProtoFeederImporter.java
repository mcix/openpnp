/*
 * DeltaProto-specific feeder importer for the HWGC machine.
 *
 * This file is intentionally isolated in the org.openpnp.machine.hwgc.deltaproto
 * subpackage so that it does not touch any upstream OpenPNP source files. All
 * DeltaProto integration code lives under this one directory, which keeps
 * rebases/merges against upstream openpnp/openpnp trivial — nothing here is
 * expected to be accepted upstream.
 *
 * Invocation: call DeltaProtoFeederImporter.run(endpointUrl) from a Groovy/JS
 * script placed in the user's ~/.openpnp2/scripts/ directory, or from a unit
 * test / main() for a one-shot sync. No menu wiring, no upstream edits.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openpnp.machine.hwgc.HwgcFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;

/**
 * Imports Package / Part / feeder-slot data from the DeltaProto backend and
 * reconciles it with the current OpenPNP configuration.
 *
 * Separation of concerns:
 *  - The DeltaProto backend owns: slot index, which part is in which slot,
 *    package/footprint id, part height.
 *  - OpenPNP owns: physical feeder location, tape pitch, feed duration and
 *    any calibration settings on each {@link HwgcFeeder}.
 *
 * Reconciliation strategy (full sync keyed by {@code feederNumber == slotIndex}):
 *  - <b>Create</b>: a payload slot with no matching HwgcFeeder yields a new
 *    {@link HwgcFeeder} with {@code feederNumber = slotIndex} and an empty
 *    location. The operator captures the physical pick location once in the
 *    GUI; subsequent imports preserve it.
 *  - <b>Update</b>: an existing HwgcFeeder has its {@code part} and
 *    {@code enabled} flag refreshed. Location, tape pitch and feed duration
 *    are deliberately left untouched — they are OpenPNP's responsibility.
 *  - <b>Remove</b>: any HwgcFeeder whose {@code feederNumber} is not present
 *    in the payload is removed from the machine. Non-Hwgc feeders are never
 *    touched.
 */
public class DeltaProtoFeederImporter {

    public static class ImportResult {
        public int packagesCreated;
        public int baselineFootprintsApplied;
        public int partsCreated;
        public int feedersCreated;
        public int feedersUpdated;
        public int feedersRemoved;
        public int feedersSkipped;
        public final List<String> warnings = new ArrayList<>();

        @Override
        public String toString() {
            return String.format(
                    "ImportResult{packagesCreated=%d, baselineFootprints=%d, partsCreated=%d, feedersCreated=%d, feedersUpdated=%d, feedersRemoved=%d, feedersSkipped=%d, warnings=%d}",
                    packagesCreated, baselineFootprintsApplied, partsCreated, feedersCreated,
                    feedersUpdated, feedersRemoved, feedersSkipped, warnings.size());
        }
    }

    /**
     * Fetches the DeltaProto feeder payload and applies it to the current
     * machine configuration. Persists the configuration on success.
     *
     * Must be called on the Swing EDT. OpenPNP config objects are bound to
     * Swing components via JGoodies BeansBinding, which throws
     * "Can not call this method on an unbound binding" when bean mutations
     * are performed off the EDT. Callers that start on a background thread
     * should fetch the payload first (see {@link #fetch(String)}) and then
     * invoke {@link #apply(Machine, Payload)} plus
     * {@link Configuration#save()} from within
     * {@link javax.swing.SwingUtilities#invokeAndWait}.
     */
    public static ImportResult run(String endpointUrl) throws Exception {
        Payload payload = fetch(endpointUrl);
        Machine machine = Configuration.get().getMachine();
        ImportResult result = apply(machine, payload, FeederLayout.load());
        Configuration.get().save();
        Logger.info("DeltaProto feeder import complete: {}", result);
        return result;
    }

    /** Package-private accessor so {@link DeltaProtoPanel} can split the fetch
     *  (background) from the apply (EDT). */
    static Payload fetchPayload(String endpointUrl) throws Exception {
        return fetch(endpointUrl);
    }

    // ── Core apply logic (package-private so DeltaProtoPanel can call it on EDT) ──

    static ImportResult apply(Machine machine, Payload payload) throws Exception {
        return apply(machine, payload, null);
    }

    static ImportResult apply(Machine machine, Payload payload, FeederLayout layout) throws Exception {
        ImportResult result = new ImportResult();
        Configuration config = Configuration.get();

        if (payload == null) {
            result.warnings.add("Empty payload from DeltaProto backend");
            return result;
        }

        // 1. Packages — create if missing, and populate a baseline footprint
        // for known chip sizes (0201/0402/0603/0805) so bottom vision has
        // something sane to work with on first import.
        if (payload.packages != null) {
            for (PackageDto dto : payload.packages) {
                if (dto.id == null || dto.id.isBlank()) {
                    continue;
                }
                Package pkg = config.getPackage(dto.id);
                if (pkg == null) {
                    pkg = new Package(dto.id);
                    pkg.setDescription(dto.description);
                    config.addPackage(pkg);
                    result.packagesCreated++;
                }
                if (BaselineFootprints.applyIfKnown(pkg)) {
                    result.baselineFootprintsApplied++;
                }
            }
        }

        // 2. Parts
        if (payload.parts != null) {
            for (PartDto dto : payload.parts) {
                if (dto.id == null || dto.id.isBlank()) {
                    continue;
                }
                Part part = config.getPart(dto.id);
                if (part == null) {
                    part = new Part(dto.id);
                    if (dto.name != null) {
                        part.setName(dto.name);
                    }
                    if (dto.height != null) {
                        part.setHeight(new Length(dto.height, LengthUnit.Millimeters));
                    }
                    if (dto.packageId != null) {
                        Package pkg = config.getPackage(dto.packageId);
                        if (pkg == null) {
                            result.warnings.add("Part " + dto.id + " references unknown package "
                                    + dto.packageId);
                        }
                        else {
                            part.setPackage(pkg);
                        }
                    }
                    config.addPart(part);
                    result.partsCreated++;
                }
            }
        }

        // 3. Feeder reconciliation — index existing HwgcFeeders by feederNumber.
        // Non-Hwgc feeders are deliberately ignored so other feeder classes on
        // the same machine are never disturbed.
        Map<Integer, HwgcFeeder> bySlot = new HashMap<>();
        for (Feeder f : machine.getFeeders()) {
            if (f instanceof HwgcFeeder) {
                HwgcFeeder hf = (HwgcFeeder) f;
                bySlot.put(hf.getFeederNumber(), hf);
            }
        }

        java.util.Set<Integer> incomingSlots = new java.util.HashSet<>();

        if (payload.feeders != null) {
            for (FeederDto dto : payload.feeders) {
                if (dto.slotIndex == null) {
                    result.warnings.add("Feeder entry without slotIndex skipped");
                    result.feedersSkipped++;
                    continue;
                }
                incomingSlots.add(dto.slotIndex);

                Part part = dto.partId != null ? config.getPart(dto.partId) : null;
                if (dto.partId != null && part == null) {
                    result.warnings.add("Feeder slot " + dto.slotIndex + " references unknown part "
                            + dto.partId);
                    result.feedersSkipped++;
                    continue;
                }

                HwgcFeeder hf = bySlot.get(dto.slotIndex);
                if (hf == null) {
                    // Create: feederNumber=slotIndex. Location is derived from
                    // the FeederLayout corner configuration when available so
                    // the operator does not have to touch every new slot.
                    hf = new HwgcFeeder();
                    hf.setFeederNumber(dto.slotIndex);
                    hf.setName("DeltaProto-" + dto.slotIndex);
                    hf.setPart(part);
                    hf.setEnabled(dto.enabled);
                    if (layout != null) {
                        Location loc = layout.locationForSlot(dto.slotIndex);
                        if (loc != null) {
                            hf.setLocation(loc);
                        }
                        else {
                            result.warnings.add("Slot " + dto.slotIndex
                                    + " outside configured feeder layout 1..50 — location left blank");
                        }
                    }
                    machine.addFeeder(hf);
                    result.feedersCreated++;
                }
                else {
                    // Update: only the DP-owned fields. Location, tape pitch
                    // and feed duration stay as the operator configured them.
                    hf.setPart(part);
                    hf.setEnabled(dto.enabled);
                    result.feedersUpdated++;
                }
            }
        }

        // Remove: any existing HwgcFeeder whose slot is no longer in the payload.
        for (Map.Entry<Integer, HwgcFeeder> entry : bySlot.entrySet()) {
            if (!incomingSlots.contains(entry.getKey())) {
                machine.removeFeeder(entry.getValue());
                result.feedersRemoved++;
                result.warnings.add("Removed HwgcFeeder for slot " + entry.getKey()
                        + " — no longer present in DeltaProto payload");
            }
        }

        return result;
    }

    // ── HTTP fetch ──

    private static Payload fetch(String endpointUrl) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("DeltaProto feeder endpoint returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return new Gson().fromJson(response.body(), Payload.class);
    }

    // ── DTOs mirroring OpenPnPController.FeederImportDao ──

    static class Payload {
        String machine;
        List<PackageDto> packages;
        List<PartDto> parts;
        List<FeederDto> feeders;
    }

    static class PackageDto {
        String id;
        String description;
    }

    static class PartDto {
        String id;
        String name;
        Double height;
        String packageId;
    }

    static class FeederDto {
        Integer slotIndex;
        String partId;
        boolean enabled;
    }
}
