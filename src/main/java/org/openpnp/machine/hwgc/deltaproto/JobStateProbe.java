/*
 * Read-only view of what the machine is doing right now: job state, job name
 * and placement progress. Feeds the STATUS frame of the server link and the
 * "is it safe to change feeders" decision of {@link ServerFeederSync}.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.openpnp.gui.JobPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;
import org.openpnp.spi.Machine;

import com.google.gson.JsonObject;

final class JobStateProbe {

    private JobStateProbe() {}

    /**
     * JobPanel's state ("Stopped", "Paused", "Running", "Pausing",
     * "Stopping"). The field is private in upstream code and has no getter;
     * reading it reflectively keeps JobPanel.java free of DeltaProto edits.
     * Null when unavailable.
     */
    static String jobPanelState() {
        try {
            JobPanel jobPanel = MainFrame.get() != null ? MainFrame.get().getJobTab() : null;
            if (jobPanel == null) {
                return null;
            }
            Field f = JobPanel.class.getDeclaredField("state");
            f.setAccessible(true);
            Object state = f.get(jobPanel);
            return state != null ? state.toString() : null;
        }
        catch (Throwable t) {
            return null;
        }
    }

    /**
     * True while a job is actively picking and placing. A paused or stopped
     * job is not running. Falls back to "machine busy" when the job state
     * cannot be read, which errs on the side of deferring changes.
     */
    static boolean isJobRunning() {
        String state = jobPanelState();
        if (state == null) {
            Machine machine = Configuration.get().getMachine();
            return machine != null && machine.isBusy();
        }
        return !("Stopped".equals(state) || "Paused".equals(state));
    }

    static Job currentJob() {
        JobPanel jobPanel = MainFrame.get() != null ? MainFrame.get().getJobTab() : null;
        return jobPanel != null ? jobPanel.getJob() : null;
    }

    /** Ids of the parts the loaded job places (enabled placements only). */
    static Set<String> partIdsInJob() {
        Set<String> ids = new HashSet<>();
        Job job = currentJob();
        if (job == null) {
            return ids;
        }
        for (BoardLocation bl : job.getBoardLocations()) {
            if (bl.getBoard() == null) {
                continue;
            }
            for (Placement p : bl.getBoard().getPlacements()) {
                if (p.isEnabled() && p.getPart() != null) {
                    ids.add(p.getPart().getId());
                }
            }
        }
        return ids;
    }

    /** Small STATUS payload, e.g. {state:"PLACING", job:"AGIS PR14", placed:120, total:340}. */
    static JsonObject status() {
        JsonObject o = new JsonObject();
        try {
            Machine machine = Configuration.get().getMachine();
            String jobState = jobPanelState();
            String state;
            if (machine == null || !machine.isEnabled()) {
                state = "DISCONNECTED";
            }
            else if (!machine.isHomed()) {
                state = "NOT_HOMED";
            }
            else if ("Running".equals(jobState)) {
                state = "PLACING";
            }
            else if ("Pausing".equals(jobState) || "Paused".equals(jobState)) {
                state = "PAUSED";
            }
            else if ("Stopping".equals(jobState)) {
                state = "STOPPING";
            }
            else {
                state = "IDLE";
            }
            o.addProperty("state", state);

            Job job = currentJob();
            if (job != null && !job.getBoardLocations().isEmpty()) {
                BoardLocation first = job.getBoardLocations().get(0);
                if (first.getBoard() != null && first.getBoard().getName() != null) {
                    o.addProperty("job", first.getBoard().getName());
                }
                int total = job.getTotalActivePlacements(job.getRootPanelLocation());
                int remaining = job.getActivePlacements(job.getRootPanelLocation());
                o.addProperty("placed", total - remaining);
                o.addProperty("total", total);
            }
        }
        catch (Throwable t) {
            o.addProperty("state", "UNKNOWN");
        }
        return o;
    }
}
