/*
 * Direct machine-to-machine link over the office LAN. It does not go through
 * the DeltaProto server; the server only publishes each machine's address
 * (peer table of {@link ServerLink}).
 *
 *  - MASTER: owns one outgoing TCP connection to every SLAVE peer that has a
 *    localIp; reconnects with backoff and when the address changes.
 *  - SLAVE: listens on the LAN port and accepts a connection only from the
 *    localIp of the peer whose role is MASTER.
 *  - STANDALONE: no link.
 *
 * Wire format: newline-delimited JSON frames in the same shape as the server
 * websocket, { "type": "...", "value": ... }.
 *
 *   master → slave   HELLO {machineId, name, role, version}
 *   slave  → master  HELLO {machineId, name, role, version}
 *   either           PING / PONG            (keepalive, 10 s, dead after 30 s)
 *   either           ERROR {message, type}  (frame not understood)
 *
 * Board hand-over / job commands for the conveyor are added on top of this
 * transport through {@link #setFrameHandler}.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.openpnp.Main;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class BuddyLanLink {

    /** Handles application frames (anything but HELLO/PING/PONG/ERROR). */
    public interface FrameHandler {
        /** @return true when the frame type was understood. */
        boolean onFrame(Connection from, String type, JsonElement value);
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int PING_INTERVAL_MS = 10_000;
    private static final int DEAD_AFTER_MS = 30_000;
    private static final int MAX_BACKOFF_MS = 30_000;

    private final Consumer<String> log;
    private final Runnable onStateChanged;
    private final Gson gson = new Gson();

    private volatile PeerView self;
    private volatile FrameHandler frameHandler;

    // Master side: machineId → link to that slave.
    private final Map<String, MasterLink> masterLinks = new HashMap<>();
    // Slave side.
    private SlaveListener slaveListener;

    BuddyLanLink(Consumer<String> log, Runnable onStateChanged) {
        this.log = log;
        this.onStateChanged = onStateChanged;
    }

    public void setFrameHandler(FrameHandler handler) {
        this.frameHandler = handler;
    }

    /**
     * Bring the links in line with this machine's role and the peer table.
     * Called whenever either changes; cheap when nothing did.
     */
    synchronized void reconcile(PeerView self, Collection<PeerView> peers, int port) {
        this.self = self;
        boolean master = self != null && self.isMaster();
        boolean slave = self != null && self.isSlave();

        // ── Master side ──
        Map<String, PeerView> wanted = new LinkedHashMap<>();
        if (master) {
            for (PeerView p : peers) {
                if (p.isSlave() && p.localIp != null && !p.localIp.isEmpty()
                        && p.machineId != null && !p.machineId.equals(self.machineId)) {
                    wanted.put(p.machineId, p);
                }
            }
        }
        for (String id : new ArrayList<>(masterLinks.keySet())) {
            MasterLink link = masterLinks.get(id);
            PeerView p = wanted.get(id);
            if (p == null || !p.localIp.equals(link.address) || link.port != port) {
                link.shutdown();
                masterLinks.remove(id);
                log.accept("LAN link to " + link.peerName + " (" + link.address + ") closed"
                        + (p == null ? "." : " — address changed to " + p.localIp + "."));
            }
            else {
                link.peerName = p.name;
            }
        }
        for (PeerView p : wanted.values()) {
            if (!masterLinks.containsKey(p.machineId)) {
                MasterLink link = new MasterLink(p.machineId, p.name, p.localIp, port);
                masterLinks.put(p.machineId, link);
                link.start();
            }
        }

        // ── Slave side ──
        String masterIp = null;
        String masterName = null;
        if (slave) {
            for (PeerView p : peers) {
                if (p.isMaster() && p.localIp != null && !p.localIp.isEmpty()) {
                    masterIp = p.localIp;
                    masterName = p.name;
                    break;
                }
            }
        }
        if (!slave) {
            if (slaveListener != null) {
                slaveListener.shutdown();
                slaveListener = null;
                log.accept("LAN listener stopped (this machine is no longer a slave).");
            }
        }
        else {
            if (slaveListener != null && slaveListener.port != port) {
                slaveListener.shutdown();
                slaveListener = null;
            }
            if (slaveListener == null) {
                slaveListener = new SlaveListener(port);
                slaveListener.start();
            }
            slaveListener.setAllowedMaster(masterIp, masterName);
        }
        onStateChanged.run();
    }

    /** One line per link for the machine UI. Empty for a standalone machine. */
    public synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (MasterLink link : masterLinks.values()) {
            lines.add("Slave " + link.peerName + " @ " + link.address + ": "
                    + (link.isUp() ? "linked" : "not linked"
                            + (link.lastError != null ? " (" + link.lastError + ")" : "")));
        }
        if (slaveListener != null) {
            lines.add(slaveListener.describe());
        }
        return lines;
    }

    public boolean isMaster() {
        PeerView me = self;
        return me != null && me.isMaster();
    }

    public boolean isSlave() {
        PeerView me = self;
        return me != null && me.isSlave();
    }

    /** True when at least one peer link is up (HELLO exchanged). */
    public synchronized boolean isLinked() {
        for (MasterLink link : masterLinks.values()) {
            if (link.isUp()) {
                return true;
            }
        }
        Connection c = slaveListener != null ? slaveListener.connection : null;
        return c != null && c.isUp();
    }

    /**
     * Communication test: send ECHO to every linked peer; each reply is logged
     * with its round-trip time. @return number of peers the test was sent to.
     */
    public int testCommunication() {
        JsonObject v = new JsonObject();
        v.addProperty("t", System.nanoTime());
        return broadcast("ECHO", v);
    }

    /** Send a frame to every linked peer. @return number of peers reached. */
    public synchronized int broadcast(String type, JsonElement value) {
        int sent = 0;
        for (MasterLink link : masterLinks.values()) {
            Connection c = link.connection;
            if (c != null && c.isUp() && c.send(type, value)) {
                sent++;
            }
        }
        if (slaveListener != null) {
            Connection c = slaveListener.connection;
            if (c != null && c.isUp() && c.send(type, value)) {
                sent++;
            }
        }
        return sent;
    }

    private JsonObject helloValue() {
        JsonObject v = new JsonObject();
        PeerView me = self;
        if (me != null) {
            v.addProperty("machineId", me.machineId);
            v.addProperty("name", me.name);
            v.addProperty("role", me.role);
        }
        v.addProperty("version", Main.getVersion());
        return v;
    }

    // ── One established TCP connection (either direction) ──

    public final class Connection {
        private final Socket socket;
        private final Writer out;
        private final String label;
        private volatile long lastFrameAt = System.currentTimeMillis();
        private volatile boolean helloSeen;

        Connection(Socket socket, String label) throws IOException {
            this.socket = socket;
            this.label = label;
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            // Wakes the reader so it can notice a silent peer.
            socket.setSoTimeout(PING_INTERVAL_MS);
            this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        }

        public String getLabel() {
            return label;
        }

        public boolean isUp() {
            return helloSeen && !socket.isClosed();
        }

        public boolean send(String type, JsonElement value) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", type);
            frame.add("value", value != null ? value : JsonNull.INSTANCE);
            String line = gson.toJson(frame) + "\n";
            synchronized (out) {
                try {
                    out.write(line);
                    out.flush();
                    return true;
                }
                catch (IOException e) {
                    close();
                    return false;
                }
            }
        }

        void close() {
            try {
                socket.close();
            }
            catch (IOException ignored) {
            }
        }

        /** Blocks reading frames until the connection dies. */
        void readLoop() throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (!socket.isClosed()) {
                String line;
                try {
                    line = in.readLine();
                }
                catch (java.net.SocketTimeoutException idle) {
                    if (System.currentTimeMillis() - lastFrameAt > DEAD_AFTER_MS) {
                        throw new IOException("no frame for " + DEAD_AFTER_MS / 1000 + " s");
                    }
                    send("PING", null);
                    continue;
                }
                if (line == null) {
                    throw new IOException("closed by peer");
                }
                if (line.isBlank()) {
                    continue;
                }
                lastFrameAt = System.currentTimeMillis();
                handle(line);
            }
        }

        private void handle(String line) {
            String type = null;
            try {
                JsonObject frame = new JsonParser().parse(line).getAsJsonObject();
                type = frame.get("type").getAsString();
                JsonElement value = frame.get("value");
                switch (type) {
                    case "HELLO":
                        if (!helloSeen) {
                            helloSeen = true;
                            log.accept("LAN link up: " + label + ".");
                            onStateChanged.run();
                        }
                        break;
                    case "PING":
                        send("PONG", null);
                        break;
                    case "PONG":
                        break;
                    case "ECHO":
                        // Operator-triggered communication test: bounce it back.
                        send("ECHO_REPLY", value);
                        log.accept("LAN test from " + label + " answered.");
                        break;
                    case "ECHO_REPLY": {
                        long sentAt = value.getAsJsonObject().get("t").getAsLong();
                        double ms = (System.nanoTime() - sentAt) / 1_000_000.0;
                        log.accept(String.format(java.util.Locale.US,
                                "✔ LAN test OK: %s answered in %.1f ms.", label, ms));
                        break;
                    }
                    case "ERROR":
                        log.accept("! LAN peer " + label + " reported: " + value);
                        break;
                    default:
                        FrameHandler h = frameHandler;
                        if (h == null || !h.onFrame(this, type, value)) {
                            error("unknown frame type", type);
                        }
                        break;
                }
            }
            catch (Exception e) {
                error("bad frame: " + e.getMessage(), type);
            }
        }

        private void error(String message, String type) {
            JsonObject v = new JsonObject();
            v.addProperty("message", message);
            if (type != null) {
                v.addProperty("type", type);
            }
            send("ERROR", v);
        }
    }

    // ── Master: outgoing link to one slave ──

    private final class MasterLink extends Thread {
        final String machineId;
        final String address;
        final int port;
        volatile String peerName;
        volatile Connection connection;
        volatile String lastError;
        private volatile boolean running = true;

        MasterLink(String machineId, String peerName, String address, int port) {
            super("deltaproto-lan-master-" + address);
            setDaemon(true);
            this.machineId = machineId;
            this.peerName = peerName;
            this.address = address;
            this.port = port;
        }

        boolean isUp() {
            Connection c = connection;
            return c != null && c.isUp();
        }

        void shutdown() {
            running = false;
            Connection c = connection;
            if (c != null) {
                c.close();
            }
            interrupt();
        }

        @Override
        public void run() {
            int backoff = 1000;
            while (running) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
                    Connection c = new Connection(s, peerName + " @ " + address);
                    connection = c;
                    c.send("HELLO", helloValue());
                    c.readLoop();
                }
                catch (IOException e) {
                    // "Up" means the slave answered HELLO. A slave that accepts
                    // the TCP connection and drops it (we are not its master
                    // yet) must not reset the backoff.
                    boolean wasUp = isUp();
                    if (wasUp) {
                        backoff = 1000;
                    }
                    String error = e.getMessage();
                    // Log transitions only; a slave that is switched off would
                    // otherwise fill the log with one line per retry.
                    if (running && (wasUp || !String.valueOf(error).equals(lastError))) {
                        log.accept("LAN link to " + peerName + " @ " + address + ":" + port
                                + (wasUp ? " lost: " : " failed: ") + error);
                    }
                    lastError = error;
                }
                finally {
                    Connection c = connection;
                    connection = null;
                    if (c != null) {
                        c.close();
                    }
                    onStateChanged.run();
                }
                if (!running) {
                    break;
                }
                try {
                    Thread.sleep(backoff + (long) (Math.random() * backoff / 4));
                }
                catch (InterruptedException ie) {
                    break;
                }
                backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
            }
        }
    }

    // ── Slave: listen for the master ──

    private final class SlaveListener extends Thread {
        final int port;
        private volatile boolean running = true;
        private volatile ServerSocket server;
        private volatile String allowedMasterIp;
        private volatile String masterName;
        volatile Connection connection;
        private volatile String listenError;

        SlaveListener(int port) {
            super("deltaproto-lan-slave-" + port);
            setDaemon(true);
            this.port = port;
        }

        void setAllowedMaster(String ip, String name) {
            boolean changed = ip == null ? allowedMasterIp != null : !ip.equals(allowedMasterIp);
            allowedMasterIp = ip;
            masterName = name;
            Connection c = connection;
            if (changed && c != null) {
                // The master moved (or is gone): the old connection is no longer trusted.
                c.close();
            }
        }

        String describe() {
            if (listenError != null) {
                return "Master link: cannot listen on port " + port + " (" + listenError + ")";
            }
            Connection c = connection;
            if (c != null && c.isUp()) {
                return "Master " + c.getLabel() + ": linked";
            }
            return allowedMasterIp == null
                    ? "Master link: no master with a known address yet"
                    : "Master " + masterName + " @ " + allowedMasterIp + ": waiting for connection";
        }

        void shutdown() {
            running = false;
            try {
                if (server != null) {
                    server.close();
                }
            }
            catch (IOException ignored) {
            }
            Connection c = connection;
            if (c != null) {
                c.close();
            }
        }

        @Override
        public void run() {
            while (running) {
                try (ServerSocket ss = new ServerSocket(port)) {
                    server = ss;
                    listenError = null;
                    log.accept("Listening for the master on LAN port " + port + ".");
                    onStateChanged.run();
                    while (running) {
                        Socket s = ss.accept();
                        accept(s);
                    }
                }
                catch (IOException e) {
                    if (!running) {
                        break;
                    }
                    if (!String.valueOf(e.getMessage()).equals(listenError)) {
                        log.accept("! LAN listener on port " + port + " failed: " + e.getMessage());
                    }
                    listenError = e.getMessage();
                    onStateChanged.run();
                    try {
                        Thread.sleep(10_000);
                    }
                    catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }

        private void accept(Socket s) {
            InetAddress remote = s.getInetAddress();
            String allowed = allowedMasterIp;
            boolean ok = false;
            try {
                ok = allowed != null && remote != null
                        && remote.equals(InetAddress.getByName(allowed));
            }
            catch (IOException ignored) {
            }
            if (!ok) {
                log.accept("! Refused LAN connection from "
                        + (remote != null ? remote.getHostAddress() : "?")
                        + " — not the master" + (allowed != null ? " (" + allowed + ")." : "."));
                try {
                    s.close();
                }
                catch (IOException ignored) {
                }
                return;
            }
            // One master connection at a time; like the server, the newer one wins.
            Connection old = connection;
            if (old != null) {
                old.close();
            }
            Thread t = new Thread(() -> serve(s), "deltaproto-lan-slave-conn");
            t.setDaemon(true);
            t.start();
        }

        private void serve(Socket s) {
            Connection c = null;
            try {
                c = new Connection(s, masterName + " @ " + s.getInetAddress().getHostAddress());
                connection = c;
                c.send("HELLO", helloValue());
                c.readLoop();
            }
            catch (IOException e) {
                if (running && c != null && c.isUp()) {
                    log.accept("LAN link from the master lost: " + e.getMessage());
                }
            }
            finally {
                if (c != null) {
                    c.close();
                    if (connection == c) {
                        connection = null;
                    }
                }
                onStateChanged.run();
            }
        }
    }
}
