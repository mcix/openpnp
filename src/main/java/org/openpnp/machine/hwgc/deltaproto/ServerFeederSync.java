/*
 * Refetches the feeder configuration when the server says it changed
 * (FEEDER_CONFIG_CHANGED) and after every (re)connect — the server does not
 * queue events for an offline machine.
 *
 * Safe while a job is running: only an in-place part swap on a lane the job
 * does not pick from is applied right away. Lanes whose current part is used
 * by the running job, and lanes that would be created or removed (the job
 * thread iterates the machine's feeder list), wait until the job pauses or
 * stops. The refetch is idempotent, so the catch-up is simply another apply.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.hwgc.deltaproto.DeltaProtoFeederImporter.LaneChange;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;

final class ServerFeederSync {

    private static final String FEEDERS_PATH = "/api/openpnp/feeders";
    /** Several FEEDER_CONFIG_CHANGED within this window cause one refetch. */
    private static final long DEBOUNCE_MS = 1000;
    private static final long DEFERRED_RETRY_MS = 3000;

    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "deltaproto-feeder-sync");
                t.setDaemon(true);
                return t;
            });
    private final Consumer<String> log;

    private ScheduledFuture<?> pendingFetch;
    private ScheduledFuture<?> pendingRetry;
    /** Latest payload with lanes still waiting for the job to pause. */
    private DeltaProtoFeederImporter.Payload deferredPayload;
    private Set<Integer> lastDeferred = new HashSet<>();

    ServerFeederSync(Consumer<String> log) {
        this.log = log;
    }

    /** Debounced: schedules one refetch ~1 s after the last request. */
    synchronized void requestRefetch(String reason) {
        if (pendingFetch != null) {
            pendingFetch.cancel(false);
        }
        pendingFetch = exec.schedule(() -> refetch(reason), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void refetch(String reason) {
        try {
            String url = ServerLinkConfig.load().url(FEEDERS_PATH);
            DeltaProtoFeederImporter.Payload payload = DeltaProtoFeederImporter.fetchPayload(url);
            log.accept("Feeder config refetched (" + reason + ")"
                    + (payload != null && payload.machine != null
                            ? " for " + payload.machine : "") + ".");
            applyOnEdt(payload);
        }
        catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.accept("! Feeder refetch failed: " + cause);
        }
    }

    private void applyOnEdt(DeltaProtoFeederImporter.Payload payload) throws Exception {
        // OpenPNP config beans are bound to Swing components and must only be
        // mutated on the EDT (see DeltaProtoFeederImporter#run).
        SwingUtilities.invokeAndWait(() -> apply(payload));
    }

    private void apply(DeltaProtoFeederImporter.Payload payload) {
        try {
            Machine machine = Configuration.get().getMachine();
            List<LaneChange> changes = DeltaProtoFeederImporter.diff(machine, payload);

            Set<Integer> deferred = new HashSet<>();
            if (JobStateProbe.isJobRunning()) {
                Set<String> jobParts = JobStateProbe.partIdsInJob();
                for (LaneChange c : changes) {
                    boolean inPlace = c.kind == LaneChange.Kind.UPDATE;
                    boolean pickedByJob = c.oldPartId != null && jobParts.contains(c.oldPartId);
                    if (!inPlace || pickedByJob) {
                        deferred.add(c.slot);
                    }
                }
            }

            for (LaneChange c : changes) {
                if (!deferred.contains(c.slot)) {
                    log.accept("  " + c);
                }
            }
            if (changes.isEmpty()) {
                log.accept("  no lane changes.");
            }

            boolean applyNow = changes.size() > deferred.size();
            if (applyNow || changes.isEmpty()) {
                DeltaProtoFeederImporter.ImportResult r = DeltaProtoFeederImporter.apply(
                        machine, payload, FeederLayout.load(), deferred);
                for (String w : r.warnings) {
                    log.accept("  ! " + w);
                }
                if (applyNow) {
                    Configuration.get().save();
                    if (MainFrame.get() != null && MainFrame.get().getFeedersTab() != null) {
                        MainFrame.get().getFeedersTab().refresh();
                    }
                }
            }

            scheduleDeferred(payload, deferred);
        }
        catch (Exception e) {
            log.accept("! Applying feeder config failed: " + e.getMessage());
        }
    }

    private synchronized void scheduleDeferred(DeltaProtoFeederImporter.Payload payload,
            Set<Integer> deferred) {
        if (pendingRetry != null) {
            pendingRetry.cancel(false);
            pendingRetry = null;
        }
        if (deferred.isEmpty()) {
            deferredPayload = null;
            lastDeferred = new HashSet<>();
            return;
        }
        if (!deferred.equals(lastDeferred)) {
            log.accept("  job running — lanes " + new java.util.TreeSet<>(deferred)
                    + " are applied when the job pauses or stops.");
        }
        lastDeferred = deferred;
        deferredPayload = payload;
        pendingRetry = exec.schedule(this::retryDeferred, DEFERRED_RETRY_MS,
                TimeUnit.MILLISECONDS);
    }

    private void retryDeferred() {
        DeltaProtoFeederImporter.Payload payload;
        synchronized (this) {
            payload = deferredPayload;
        }
        if (payload == null) {
            return;
        }
        try {
            if (JobStateProbe.isJobRunning()) {
                // Still running: check again later without touching anything.
                synchronized (this) {
                    pendingRetry = exec.schedule(this::retryDeferred, DEFERRED_RETRY_MS,
                            TimeUnit.MILLISECONDS);
                }
                return;
            }
            log.accept("Job no longer running — applying deferred feeder lanes.");
            applyOnEdt(payload);
        }
        catch (Exception e) {
            log.accept("! Applying deferred feeder lanes failed: " + e.getMessage());
        }
    }
}
