/*
 * PCB hand-over across the conveyor from the master (Buddy 2.1) to the slave
 * (Buddy 2.2), over the {@link BuddyLanLink}.
 *
 *   master → slave   BOARD_FEED_IN      {id, delayMs, velocity}
 *   slave  → master  BOARD_FEED_IN_ACK  {id, ok, message}   "ready, I will feed in"
 *   master                              OUT_BOARD (0x31) on its own controller
 *   slave                               IN_BOARD  (0x30) after delayMs
 *   slave  → master  BOARD_FEED_IN_DONE {id, ok, message}   command sent / failed
 *
 * The master only releases its board after the slave confirmed it is able to
 * take it (controller connected, no job running), so a dead slave can never
 * cause a board to be pushed against a standing conveyor. The HWGC firmware
 * gives no completion feedback for 0x30/0x31, so "done" means the command
 * was sent, not that the board arrived.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import org.openpnp.machine.hwgc.HwgcDriver;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class BoardTransfer implements BuddyLanLink.FrameHandler {

    static final String PREF_KEY_FEED_IN_DELAY_MS = "transfer.feedInDelayMs";
    static final String PREF_KEY_VELOCITY = "transfer.psd";
    static final String PREF_KEY_OUT_DELAY_TENTHS = "transfer.outDelayTenths";
    /** Same track speed step the manual Inboard/Outboard buttons and the HWGC test panel use. */
    static final int DEFAULT_VELOCITY = HwgcDriver.BOARD_PSD_DEFAULT;
    private static final long ACK_TIMEOUT_MS = 3000;

    private static final Preferences PREFS = Preferences.userNodeForPackage(BoardTransfer.class);

    private final BuddyLanLink lanLink;
    private final Consumer<String> log;
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "deltaproto-board-transfer");
                t.setDaemon(true);
                return t;
            });
    private final AtomicLong nextId = new AtomicLong(System.currentTimeMillis());

    // Master side: the hand-over waiting for the slave's ACK. exec thread only.
    private String pendingId;
    private int pendingVelocity;
    private ScheduledFuture<?> pendingTimeout;

    BoardTransfer(BuddyLanLink lanLink, Consumer<String> log) {
        this.lanLink = lanLink;
        this.log = log;
        lanLink.setFrameHandler(this);
    }

    public static int getFeedInDelayMs() {
        return Math.max(0, PREFS.getInt(PREF_KEY_FEED_IN_DELAY_MS, 0));
    }

    public static void setFeedInDelayMs(int ms) {
        PREFS.putInt(PREF_KEY_FEED_IN_DELAY_MS, Math.max(0, ms));
    }

    /** Track speed step 0-9 for IN_BOARD / OUT_BOARD (0 = HWGC test panel value). */
    public static int getVelocity() {
        return Math.min(9, Math.max(0, PREFS.getInt(PREF_KEY_VELOCITY, DEFAULT_VELOCITY)));
    }

    public static void setVelocity(int v) {
        PREFS.putInt(PREF_KEY_VELOCITY, Math.min(9, Math.max(0, v)));
    }

    /**
     * How long the conveyor keeps running after the board reaches the out
     * sensor, in 0.1 s units (the OUT_BOARD delay byte). Used by the manual
     * Outboard button and by the hand-over to the slave.
     */
    public static int getOutDelayTenths() {
        return Math.min(255, Math.max(0, PREFS.getInt(PREF_KEY_OUT_DELAY_TENTHS, 0)));
    }

    public static void setOutDelayTenths(int tenths) {
        PREFS.putInt(PREF_KEY_OUT_DELAY_TENTHS, Math.min(255, Math.max(0, tenths)));
    }

    // ── Master ──

    /** Hand the board on this machine over to the slave. Returns immediately; progress is logged. */
    public void transferToSlave() {
        exec.execute(() -> {
            if (pendingId != null) {
                log.accept("! PCB transfer already in progress.");
                return;
            }
            String problem = !lanLink.isMaster() ? "this machine is not the master"
                    : localProblem();
            if (problem != null) {
                log.accept("! PCB transfer not started: " + problem);
                return;
            }
            String id = Long.toString(nextId.incrementAndGet());
            int delayMs = getFeedInDelayMs();
            int velocity = getVelocity();
            JsonObject v = new JsonObject();
            v.addProperty("id", id);
            v.addProperty("delayMs", delayMs);
            v.addProperty("velocity", velocity);
            if (lanLink.broadcast("BOARD_FEED_IN", v) == 0) {
                log.accept("! PCB transfer not started: no LAN link to the slave.");
                return;
            }
            pendingId = id;
            pendingVelocity = velocity;
            log.accept("PCB transfer " + id + ": asked the slave to feed in (delay " + delayMs
                    + " ms, speed " + velocity + ") — waiting for its confirmation.");
            pendingTimeout = exec.schedule(() -> {
                if (id.equals(pendingId)) {
                    pendingId = null;
                    log.accept("! PCB transfer " + id + ": no answer from the slave within "
                            + ACK_TIMEOUT_MS / 1000 + " s — board NOT fed out.");
                }
            }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        });
    }

    private void onAck(String id, boolean ok, String message) {
        if (!id.equals(pendingId)) {
            return;
        }
        pendingId = null;
        if (pendingTimeout != null) {
            pendingTimeout.cancel(false);
        }
        if (!ok) {
            log.accept("! PCB transfer " + id + ": slave refused (" + message
                    + ") — board NOT fed out.");
            return;
        }
        try {
            HwgcDriver driver = findDriver();
            int outDelay = getOutDelayTenths();
            driver.sendOutBoard(pendingVelocity, outDelay);
            log.accept("PCB transfer " + id + ": slave ready — feed out (OUT_BOARD) sent"
                    + (outDelay > 0 ? ", out-sensor delay " + outDelay / 10.0 + " s." : "."));
        }
        catch (Exception e) {
            log.accept("! PCB transfer " + id + ": feed out failed: " + e.getMessage()
                    + " — the slave's conveyor is running without a board.");
        }
    }

    // ── Slave ──

    private void onFeedInRequest(BuddyLanLink.Connection from, String id, int delayMs,
            int velocity) {
        String problem = localProblem();
        JsonObject ack = new JsonObject();
        ack.addProperty("id", id);
        ack.addProperty("ok", problem == null);
        if (problem != null) {
            ack.addProperty("message", problem);
            log.accept("! PCB transfer " + id + " from the master refused: " + problem);
            from.send("BOARD_FEED_IN_ACK", ack);
            return;
        }
        from.send("BOARD_FEED_IN_ACK", ack);
        log.accept("PCB transfer " + id + ": feeding in for the master"
                + (delayMs > 0 ? " in " + delayMs + " ms." : "."));
        exec.schedule(() -> {
            JsonObject done = new JsonObject();
            done.addProperty("id", id);
            try {
                findDriver().sendInBoard(velocity);
                done.addProperty("ok", true);
                log.accept("PCB transfer " + id + ": feed in (IN_BOARD) sent.");
            }
            catch (Exception e) {
                done.addProperty("ok", false);
                done.addProperty("message", String.valueOf(e.getMessage()));
                log.accept("! PCB transfer " + id + ": feed in failed: " + e.getMessage());
            }
            from.send("BOARD_FEED_IN_DONE", done);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ── Shared ──

    /** Why this machine cannot move its conveyor right now; null when it can. */
    private static String localProblem() {
        try {
            HwgcDriver driver = findDriver();
            if (driver == null) {
                return "no HWGC driver configured";
            }
            if (!driver.isConnected()) {
                return "machine not connected (press Connect & Home first)";
            }
            if (JobStateProbe.isJobRunning()) {
                return "a job is running";
            }
            return null;
        }
        catch (Throwable t) {
            return "machine not available: " + t.getMessage();
        }
    }

    private static HwgcDriver findDriver() {
        Machine machine = Configuration.get().getMachine();
        for (Driver d : machine.getDrivers()) {
            if (d instanceof HwgcDriver) {
                return (HwgcDriver) d;
            }
        }
        return null;
    }

    @Override
    public boolean onFrame(BuddyLanLink.Connection from, String type, JsonElement value) {
        JsonObject v = value != null && value.isJsonObject() ? value.getAsJsonObject()
                : new JsonObject();
        String id = v.has("id") ? v.get("id").getAsString() : "?";
        boolean ok = v.has("ok") && v.get("ok").getAsBoolean();
        String message = v.has("message") ? v.get("message").getAsString() : "";
        switch (type) {
            case "BOARD_FEED_IN": {
                if (!lanLink.isSlave()) {
                    // Only a slave takes boards on request.
                    JsonObject ack = new JsonObject();
                    ack.addProperty("id", id);
                    ack.addProperty("ok", false);
                    ack.addProperty("message", "this machine is not a slave");
                    from.send("BOARD_FEED_IN_ACK", ack);
                    return true;
                }
                int delayMs = v.has("delayMs") ? Math.max(0, v.get("delayMs").getAsInt()) : 0;
                int velocity = v.has("velocity")
                        ? Math.min(9, Math.max(0, v.get("velocity").getAsInt()))
                        : DEFAULT_VELOCITY;
                exec.execute(() -> onFeedInRequest(from, id, delayMs, velocity));
                return true;
            }
            case "BOARD_FEED_IN_ACK":
                exec.execute(() -> onAck(id, ok, message));
                return true;
            case "BOARD_FEED_IN_DONE":
                log.accept(ok ? "PCB transfer " + id + ": slave reports feed in sent."
                        : "! PCB transfer " + id + ": slave feed in failed: " + message);
                return true;
            default:
                return false;
        }
    }
}
