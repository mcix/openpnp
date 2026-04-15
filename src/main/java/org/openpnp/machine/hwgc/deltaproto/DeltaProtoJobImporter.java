/*
 * DeltaProto job importer for OpenPNP.
 *
 * Sibling to DeltaProtoFeederImporter — fetches a job payload from the
 * DeltaProto backend and turns it into an OpenPNP Job containing a single
 * Board with one Placement per BOM designator. Lives under the isolated
 * org.openpnp.machine.hwgc.deltaproto subpackage so upstream merges remain
 * trivial.
 *
 * Ownership split:
 *  - DeltaProto owns: which designators exist for a project, their X/Y/
 *    rotation/side, and which ones map to a buddypart (and are therefore
 *    enabled for placement). The backend marks every other placement
 *    enabled = false so the operator sees the full board but only the
 *    buddyparts run.
 *  - OpenPNP owns: physical Part/Package definitions and feeder calibration.
 *    Parts/packages referenced by the payload are created on demand if
 *    missing — same pattern as the feeder importer.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Feeder;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;

public class DeltaProtoJobImporter {

    public static class ImportResult {
        public int packagesCreated;
        public int partsCreated;
        public int placementsAdded;
        public int placementsEnabled;
        public int placementsSkipped;
        public final List<String> warnings = new ArrayList<>();

        @Override
        public String toString() {
            return String.format(
                    "JobImportResult{packagesCreated=%d, partsCreated=%d, placementsAdded=%d, placementsEnabled=%d, placementsSkipped=%d, warnings=%d}",
                    packagesCreated, partsCreated, placementsAdded, placementsEnabled,
                    placementsSkipped, warnings.size());
        }
    }

    /**
     * Builds the URL for a job fetch given a base endpoint (which may already
     * contain query parameters or trailing slashes) and a project order id
     * such as "DEPR PR04". The id is URL-encoded.
     */
    public static String buildJobUrl(String baseEndpoint, String projectOrderId) {
        String trimmed = baseEndpoint.trim();
        // Strip trailing slash for predictable join.
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String encoded = URLEncoder.encode(projectOrderId, StandardCharsets.UTF_8);
        return trimmed + "?projectOrder=" + encoded;
    }

    /**
     * Package-private accessor used by {@link DeltaProtoPanel} so the HTTP
     * fetch can run on a background thread while {@link #buildJob(Payload)}
     * runs on the EDT.
     */
    static Payload fetchPayload(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("DeltaProto job endpoint returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return new Gson().fromJson(response.body(), Payload.class);
    }

    /**
     * Builds an OpenPNP Job from a payload. Must be called on the Swing EDT
     * because OpenPNP config objects are bound to Swing components via
     * JGoodies BeansBinding, which throws on off-EDT mutations.
     */
    static JobBuildResult buildJob(Payload payload) {
        ImportResult result = new ImportResult();
        if (payload == null) {
            result.warnings.add("Empty payload from DeltaProto backend");
            return new JobBuildResult(null, result);
        }

        Configuration config = Configuration.get();

        // 1. Packages — create on demand. Description is intentionally left
        // null when unknown so we don't overwrite better data the operator
        // might have entered manually.
        if (payload.packages != null) {
            for (PackageDto dto : payload.packages) {
                if (dto.id == null || dto.id.isBlank()) {
                    continue;
                }
                Package pkg = config.getPackage(dto.id);
                if (pkg == null) {
                    pkg = new Package(dto.id);
                    if (dto.description != null) {
                        pkg.setDescription(dto.description);
                    }
                    config.addPackage(pkg);
                    result.packagesCreated++;
                }
                if (BaselineFootprints.applyIfKnown(pkg)) {
                    // Same baseline application as feeder importer; keeps
                    // bottom vision sane on first import.
                }
            }
        }

        // 2. Parts — create on demand and backfill height. Skipped warnings
        // are surfaced in the result so the operator can correct upstream.
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
                Length heightFromDto = dto.height != null
                        ? new Length(dto.height, LengthUnit.Millimeters) : null;
                if (heightFromDto != null) {
                    part.setHeight(heightFromDto);
                }
            }
        }

        // 3. Build the Board + placements. The board is persisted to a stable
        // file under <openpnp config dir>/boards/ so that:
        //   (a) it goes through Configuration.getBoard(file), which registers
        //       it in the canonical boards map — the same path normal jobs
        //       take. Skipping this leaves the board half-initialised and
        //       breaks any panel that listens for board/placement events.
        //   (b) the Job that wraps it can be saved as a real .job.xml later
        //       because the BoardLocation has a real fileName.
        // We always emit a single Board wrapped in a single BoardLocation
        // under the Job's rootPanel — no multi-up panelisation.
        String boardName = payload.board != null && payload.board.name != null
                ? payload.board.name : payload.projectOrder;
        Board board;
        try {
            File boardFile = boardFileFor(payload.projectOrder);
            board = config.getBoard(boardFile);
        }
        catch (Exception e) {
            result.warnings.add("Failed to open board file: " + e.getMessage());
            return new JobBuildResult(null, result);
        }

        // Wipe any placements left over from a previous import for the same
        // project order — we do not merge, the backend is the source of truth.
        for (Placement old : new ArrayList<>(board.getPlacements())) {
            board.removePlacement(old);
        }

        board.setName(boardName);
        if (payload.board != null && payload.board.width != null && payload.board.height != null) {
            board.setDimensions(new Location(LengthUnit.Millimeters,
                    payload.board.width, payload.board.height, 0.0, 0.0));
        }

        if (payload.placements != null) {
            for (PlacementDto dto : payload.placements) {
                if (dto.id == null || dto.id.isBlank()) {
                    result.placementsSkipped++;
                    continue;
                }
                if (dto.partId == null || dto.partId.isBlank()) {
                    result.warnings.add("Placement " + dto.id + " has no partId");
                    result.placementsSkipped++;
                    continue;
                }
                Part part = config.getPart(dto.partId);
                if (part == null) {
                    result.warnings.add("Placement " + dto.id + " references unknown part "
                            + dto.partId);
                    result.placementsSkipped++;
                    continue;
                }

                Placement placement = new Placement(dto.id);
                placement.setPart(part);
                double x = dto.x != null ? dto.x : 0.0;
                double y = dto.y != null ? dto.y : 0.0;
                double rot = dto.rotation != null ? dto.rotation : 0.0;
                placement.setLocation(new Location(LengthUnit.Millimeters, x, y, 0.0, rot));
                placement.setSide("Bottom".equalsIgnoreCase(dto.side) ? Side.Bottom : Side.Top);
                placement.setEnabled(dto.enabled);
                if (dto.comment != null && !dto.comment.isBlank()) {
                    placement.setComments(dto.comment);
                }
                board.addPlacement(placement);
                result.placementsAdded++;
                if (dto.enabled) {
                    result.placementsEnabled++;
                }
            }
        }

        // Persist the new placements to the .board.xml — same step that
        // happens when the operator edits a board in the board tab.
        try {
            config.saveBoard(board);
        }
        catch (Exception e) {
            result.warnings.add("Failed to save board file: " + e.getMessage());
        }

        // Diagnostic: count how many enabled placements have a matching
        // enabled Feeder on the current machine. The job placements table
        // shows "Missing Feeder" using reference equality between
        // feeder.getPart() and placement.getPart(); both should resolve via
        // Configuration.getPart(id) to the same instance, so a mismatch here
        // means either the feeder importer hasn't been run for these MPNs or
        // the corresponding BuddyParts are not currently loaded.
        int placementsWithFeeder = 0;
        java.util.Set<String> missingPartIds = new java.util.LinkedHashSet<>();
        try {
            List<Feeder> feeders = config.getMachine().getFeeders();
            for (Placement placement : board.getPlacements()) {
                if (!placement.isEnabled() || placement.getPart() == null) {
                    continue;
                }
                boolean found = false;
                for (Feeder feeder : feeders) {
                    if (feeder.isEnabled() && feeder.getPart() == placement.getPart()) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    placementsWithFeeder++;
                }
                else {
                    missingPartIds.add(placement.getPart().getId());
                }
            }
        }
        catch (Exception e) {
            // Machine not available — skip diagnostic, not fatal for import.
        }
        if (!missingPartIds.isEmpty()) {
            result.warnings.add(placementsWithFeeder + "/" + result.placementsEnabled
                    + " enabled placements have a matching enabled feeder. "
                    + "Missing feeder for parts: " + missingPartIds);
        }

        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setLocation(new Location(LengthUnit.Millimeters, 0.0, 0.0, 0.0, 0.0));
        boardLocation.setSide(Side.Top);

        Job job = new Job();
        job.addBoardOrPanelLocation(boardLocation);

        Logger.info("DeltaProto job import built: {}", result);
        return new JobBuildResult(job, result);
    }

    /** Stable on-disk location for a project order's generated board file.
     *  Names are sanitised so spaces and slashes become underscores. */
    private static File boardFileFor(String projectOrder) {
        String safe = (projectOrder == null ? "unnamed" : projectOrder)
                .replaceAll("[^A-Za-z0-9._-]+", "_");
        File dir = new File(Configuration.get().getConfigurationDirectory(), "boards");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "DeltaProto-" + safe + ".board.xml");
    }

    /** Returned from {@link #buildJob(Payload)} so the caller gets both the
     *  ready-to-load Job and the diagnostics. */
    public static class JobBuildResult {
        public final Job job;
        public final ImportResult result;

        JobBuildResult(Job job, ImportResult result) {
            this.job = job;
            this.result = result;
        }
    }

    // ── DTOs mirroring OpenPnPController.JobImportDao ──

    static class Payload {
        String projectOrder;
        BoardDto board;
        List<PackageDto> packages;
        List<PartDto> parts;
        List<PlacementDto> placements;
    }

    static class BoardDto {
        String name;
        Double width;
        Double height;
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

    static class PlacementDto {
        String id;
        String partId;
        Double x;
        Double y;
        Double rotation;
        String side;
        boolean enabled;
        String comment;
    }
}
