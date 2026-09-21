/*
 * The machine's link to the DeltaProto server (buddy-machine-protocol.md):
 *
 *  1. identity and config — {@link ServerLinkConfig} (server URL, token, local IP)
 *  2. websocket session   — connect, HELLO, keepalive, reconnect, event dispatch
 *  3. peer table          — the other machines and their LAN addresses, kept
 *                           fresh from PEER_CHANGED and GET /peers; consumed by
 *                           the master/slave {@link BuddyLanLink}
 *
 * All session state is confined to one scheduler thread; websocket callbacks
 * hop onto it and carry the generation of the session they belong to, so a
 * late callback of a dead session can never disturb the current one.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openpnp.Main;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

public final class ServerLink {

    public enum State {
        /** No token configured. */
        NOT_CONFIGURED("not configured — paste the machine token in Settings"),
        CONNECTING("connecting…"),
        CONNECTED("connected"),
        OFFLINE("offline — reconnecting"),
        /** HTTP 403: valid token, but not from the office network. Retried every 60 s. */
        NOT_ON_OFFICE_NETWORK("not on the office network — the server refuses this address"),
        /** HTTP 401: wrong or regenerated token. Not retried until the token changes. */
        TOKEN_REJECTED("token rejected — paste the new token from the settings page");

        public final String text;

        State(String text) {
            this.text = text;
        }
    }

    public interface Listener {
        /** State, identity, peer table or LAN link state changed. Any thread. */
        void onChanged();

        /** A line for the operator log. Any thread. */
        void onLog(String line);
    }

    private static final int PING_INTERVAL_S = 30;
    private static final long DEAD_AFTER_MS = 90_000;
    private static final int MAX_BACKOFF_S = 30;
    private static final int FORBIDDEN_RETRY_S = 60;
    private static final int STATUS_INTERVAL_S = 2;
    private static final int LOG_BACKLOG = 100;

    private static final ServerLink INSTANCE = new ServerLink();

    public static ServerLink get() {
        return INSTANCE;
    }

    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "deltaproto-serverlink");
                t.setDaemon(true);
                return t;
            });
    private final Gson gson = new Gson();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Deque<String> logBacklog = new ArrayDeque<>();
    private final ServerFeederSync feederSync = new ServerFeederSync(this::log);
    private final BuddyLanLink lanLink = new BuddyLanLink(this::log, this::fireChanged);
    private final BoardTransfer boardTransfer = new BoardTransfer(lanLink, this::log);

    // ── Confined to the exec thread ──
    private boolean started;
    private ServerLinkConfig config = new ServerLinkConfig();
    private HttpClient http;
    private int generation;
    private WebSocket socket;
    private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);
    private ScheduledFuture<?> keepalive;
    private ScheduledFuture<?> reconnect;
    private ScheduledFuture<?> statusTicker;
    private long lastFrameAt;
    private int backoffS = 1;
    private String reportedIp;
    private String lastStatusJson;

    // ── Read from other threads (UI) ──
    private volatile State state = State.NOT_CONFIGURED;
    private volatile String stateDetail;
    private volatile long connectedSince;
    private volatile PeerView self;
    private final Map<String, PeerView> peers = new LinkedHashMap<>();

    private ServerLink() {}

    // ── Public API ──

    /** Start the link (idempotent). Does nothing visible when no token is configured. */
    public void start() {
        exec.execute(() -> {
            if (started) {
                return;
            }
            started = true;
            statusTicker = exec.scheduleWithFixedDelay(this::publishStatus,
                    STATUS_INTERVAL_S, STATUS_INTERVAL_S, TimeUnit.SECONDS);
            connect();
        });
    }

    /** Configuration was saved: drop the session and connect with the new values. */
    public void reconfigure() {
        exec.execute(() -> {
            started = true;
            if (statusTicker == null) {
                statusTicker = exec.scheduleWithFixedDelay(this::publishStatus,
                        STATUS_INTERVAL_S, STATUS_INTERVAL_S, TimeUnit.SECONDS);
            }
            dropSession("reconfigured");
            backoffS = 1;
            connect();
        });
    }

    /** Manual "refetch feeders now" from the UI. */
    public void refetchFeeders() {
        feederSync.requestRefetch("manual");
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public State getState() {
        return state;
    }

    /** E.g. the last connection error. May be null. */
    public String getStateDetail() {
        return stateDetail;
    }

    /** Millis timestamp of the CONNECTED frame, 0 when not connected. */
    public long getConnectedSince() {
        return connectedSince;
    }

    /** This machine as the server sees it (name, role …). Null before the first CONNECTED. */
    public PeerView getSelf() {
        return self;
    }

    /** The other machines, as last reported by the server. */
    public List<PeerView> getPeers() {
        synchronized (peers) {
            List<PeerView> others = new ArrayList<>();
            PeerView me = self;
            for (PeerView p : peers.values()) {
                if (me == null || !p.machineId.equals(me.machineId)) {
                    others.add(p);
                }
            }
            return others;
        }
    }

    public BuddyLanLink getLanLink() {
        return lanLink;
    }

    public BoardTransfer getBoardTransfer() {
        return boardTransfer;
    }

    public List<String> getLogBacklog() {
        synchronized (logBacklog) {
            return new ArrayList<>(logBacklog);
        }
    }

    // ── Session lifecycle (exec thread) ──

    private void connect() {
        reconnect = null;
        config = ServerLinkConfig.load();
        if (!config.hasToken()) {
            setState(State.NOT_CONFIGURED, null);
            return;
        }
        final int gen = ++generation;
        setState(State.CONNECTING, null);
        try {
            if (http == null) {
                http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            }
            http.newWebSocketBuilder()
                    .header(ServerLinkConfig.TOKEN_HEADER, config.token)
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(config.webSocketUrl()), new SessionListener(gen))
                    .whenCompleteAsync((ws, error) -> {
                        if (error != null) {
                            onConnectFailed(gen, error);
                        }
                    }, exec);
        }
        catch (Exception e) {
            // E.g. a malformed base URL. Retrying will not fix it, but a
            // reconfigure() will, and the backoff keeps this cheap.
            onConnectFailed(gen, e);
        }
    }

    private void onConnectFailed(int gen, Throwable error) {
        if (gen != generation) {
            return;
        }
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof WebSocketHandshakeException) {
            int status = ((WebSocketHandshakeException) cause).getResponse().statusCode();
            if (status == 401) {
                // Wrong or regenerated token: stop until the operator pastes a new one.
                setState(State.TOKEN_REJECTED, null);
                log("! Server rejected the machine token (401). Paste the new token from"
                        + " the settings page (DeltaProto tab → Settings → Server).");
                return;
            }
            if (status == 403) {
                boolean first = state != State.NOT_ON_OFFICE_NETWORK;
                setState(State.NOT_ON_OFFICE_NETWORK, null);
                if (first) {
                    log("! Server refused the connection (403): this PC is not on the office"
                            + " network. Retrying every " + FORBIDDEN_RETRY_S + " s.");
                }
                scheduleReconnect(FORBIDDEN_RETRY_S * 1000L);
                return;
            }
            cause = new RuntimeException("HTTP " + status + " on websocket upgrade of "
                    + config.webSocketUrl()
                    + (status == 400 ? " (does the reverse proxy forward the Upgrade header?)"
                            : ""));
        }
        boolean first = state != State.OFFLINE;
        setState(State.OFFLINE, String.valueOf(cause.getMessage() != null
                ? cause.getMessage() : cause.getClass().getSimpleName()));
        if (first) {
            log("Server connection failed: " + stateDetail + " — retrying.");
        }
        scheduleReconnectWithBackoff();
    }

    private void onOpened(int gen, WebSocket ws) {
        if (gen != generation) {
            ws.abort();
            return;
        }
        socket = ws;
        sendTail = CompletableFuture.completedFuture(null);
        lastFrameAt = System.currentTimeMillis();
        // The server speaks first (CONNECTED); HELLO follows from there.
        keepalive = exec.scheduleWithFixedDelay(() -> keepalive(gen),
                PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void onClosed(int gen, String why) {
        if (gen != generation) {
            return;
        }
        boolean wasConnected = state == State.CONNECTED;
        dropSession(why);
        setState(State.OFFLINE, why);
        if (wasConnected) {
            log("Server connection lost (" + why + ") — reconnecting.");
        }
        scheduleReconnectWithBackoff();
    }

    /** Invalidate the current session, if any, without scheduling anything. */
    private void dropSession(String why) {
        generation++;
        if (keepalive != null) {
            keepalive.cancel(false);
            keepalive = null;
        }
        if (reconnect != null) {
            reconnect.cancel(false);
            reconnect = null;
        }
        if (socket != null) {
            socket.abort();
            socket = null;
        }
        connectedSince = 0;
        lastStatusJson = null;
        Logger.debug("ServerLink: session dropped ({})", why);
    }

    private void scheduleReconnectWithBackoff() {
        // 1 s → 2 → 4 → … capped at 30 s, plus up to 25 % jitter.
        long delay = backoffS * 1000L;
        delay += (long) (Math.random() * delay / 4);
        backoffS = Math.min(MAX_BACKOFF_S, backoffS * 2);
        scheduleReconnect(delay);
    }

    private void scheduleReconnect(long delayMs) {
        if (reconnect != null) {
            reconnect.cancel(false);
        }
        reconnect = exec.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
    }

    private void keepalive(int gen) {
        if (gen != generation || socket == null) {
            return;
        }
        if (System.currentTimeMillis() - lastFrameAt > DEAD_AFTER_MS) {
            onClosed(gen, "no frame from the server for " + DEAD_AFTER_MS / 1000 + " s");
            return;
        }
        send("PING", null);
        // DHCP may have moved us; the server tells the other machines.
        if (state == State.CONNECTED) {
            String ip = config.detectLocalIp();
            if (ip != null && !ip.equals(reportedIp)) {
                JsonObject v = new JsonObject();
                v.addProperty("localIp", ip);
                send("SET_LOCAL_IP", v);
                log("Local IP changed " + reportedIp + " → " + ip + ", reported to the server.");
                reportedIp = ip;
            }
        }
    }

    // ── Frames ──

    private void onFrame(int gen, String text) {
        if (gen != generation) {
            return;
        }
        lastFrameAt = System.currentTimeMillis();
        String type;
        JsonElement value;
        try {
            JsonObject frame = new JsonParser().parse(text).getAsJsonObject();
            type = frame.get("type").getAsString();
            value = frame.has("value") ? frame.get("value") : JsonNull.INSTANCE;
        }
        catch (Exception e) {
            Logger.debug("ServerLink: unparseable frame ignored: {}", text);
            return;
        }
        try {
            switch (type) {
                case "CONNECTED":
                    onConnected(value);
                    break;
                case "PONG":
                    break;
                case "ERROR":
                    // Never a reason to disconnect.
                    log("! Server could not handle " + stringOf(value, "type", "a frame") + ": "
                            + stringOf(value, "message", String.valueOf(value)));
                    break;
                case "FEEDER_CONFIG_CHANGED":
                    log("FEEDER_CONFIG_CHANGED"
                            + (has(value, "location")
                                    ? " (lane " + stringOf(value, "location", "?") + ")" : "")
                            + (has(value, "manual") ? " [manual test]" : "")
                            + " — refetching feeders.");
                    feederSync.requestRefetch("server event");
                    break;
                case "MACHINE_CONFIG_CHANGED":
                    onSelfChanged(gson.fromJson(value, PeerView.class));
                    break;
                case "PEER_CHANGED":
                    onPeerChanged(gson.fromJson(value, PeerView.class));
                    break;
                default:
                    Logger.debug("ServerLink: unknown frame type {} ignored", type);
                    break;
            }
        }
        catch (Exception e) {
            Logger.warn(e, "ServerLink: failed to handle {} frame", type);
        }
    }

    private void onConnected(JsonElement value) {
        PeerView me = gson.fromJson(value, PeerView.class);
        if (me == null) {
            me = new PeerView();
        }
        PeerView before = self;
        self = me;
        connectedSince = System.currentTimeMillis();
        setState(State.CONNECTED, null);
        log("Connected to " + config.baseUrl + " as " + me.name + " (" + me.role + ").");
        if (before != null && before.role != null && !before.role.equals(me.role)) {
            log("Role changed " + before.role + " → " + me.role + " (server's role adopted).");
        }

        // HELLO — without localIp when detection fails; the link still works.
        reportedIp = config.detectLocalIp();
        JsonObject hello = new JsonObject();
        if (reportedIp != null) {
            hello.addProperty("localIp", reportedIp);
        }
        else {
            log("! Could not detect the office-LAN address; HELLO sent without localIp."
                    + " Set the interface name in Settings → Server.");
        }
        hello.addProperty("version", Main.getVersion());
        send("HELLO", hello);

        fetchPeers(generation);
        // Events are not queued while offline: always refetch after (re)connect.
        feederSync.requestRefetch("connected");
        reconcileLan();
    }

    private void onSelfChanged(PeerView me) {
        if (me == null) {
            return;
        }
        PeerView before = self;
        self = me;
        synchronized (peers) {
            if (me.machineId != null) {
                peers.put(me.machineId, me);
            }
        }
        if (before == null || !String.valueOf(before.role).equals(String.valueOf(me.role))
                || !String.valueOf(before.name).equals(String.valueOf(me.name))) {
            log("Machine config changed on the server: " + me.name + " (" + me.role + ").");
        }
        reconcileLan();
        fireChanged();
    }

    private void onPeerChanged(PeerView peer) {
        if (peer == null || peer.machineId == null) {
            return;
        }
        PeerView before;
        synchronized (peers) {
            before = peers.put(peer.machineId, peer);
        }
        if (before == null || !String.valueOf(before.localIp).equals(String.valueOf(peer.localIp))
                || !String.valueOf(before.role).equals(String.valueOf(peer.role))) {
            log("Peer changed: " + peer + ".");
        }
        reconcileLan();
        fireChanged();
    }

    private void fetchPeers(int gen) {
        String url = config.url("/api/buddymachine/peers");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header(ServerLinkConfig.TOKEN_HEADER, config.token)
                .header("Accept", "application/json")
                .GET()
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenCompleteAsync((response, error) -> {
                    if (gen != generation) {
                        return;
                    }
                    if (error != null || response.statusCode() / 100 != 2) {
                        log("! Fetching the peer table failed: " + (error != null
                                ? error.getMessage() : "HTTP " + response.statusCode()));
                        return;
                    }
                    List<PeerView> list = gson.fromJson(response.body(),
                            new TypeToken<List<PeerView>>() {}.getType());
                    // Replace wholesale; PEER_CHANGED patches it from here on.
                    synchronized (peers) {
                        peers.clear();
                        if (list != null) {
                            for (PeerView p : list) {
                                if (p != null && p.machineId != null) {
                                    peers.put(p.machineId, p);
                                }
                            }
                        }
                    }
                    List<PeerView> others = getPeers();
                    log("Peers: " + (others.isEmpty() ? "none" : others.toString()));
                    reconcileLan();
                    fireChanged();
                }, exec);
    }

    private void reconcileLan() {
        List<PeerView> all;
        synchronized (peers) {
            all = new ArrayList<>(peers.values());
        }
        lanLink.reconcile(self, all, config.lanPort);
    }

    /** STATUS on state change only, checked every 2 s — well under "a few per second". */
    private void publishStatus() {
        try {
            if (state != State.CONNECTED || socket == null) {
                return;
            }
            JsonObject status = JobStateProbe.status();
            List<String> lan = lanLink.describe();
            if (!lan.isEmpty()) {
                status.addProperty("lan", String.join("; ", lan));
            }
            String json = gson.toJson(status);
            if (!json.equals(lastStatusJson)) {
                lastStatusJson = json;
                send("STATUS", status);
            }
        }
        catch (Throwable t) {
            Logger.debug("ServerLink: status publish failed: {}", t.getMessage());
        }
    }

    /** Sends are chained: WebSocket.sendText must not overlap a pending send. */
    private void send(String type, JsonElement value) {
        final WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("type", type);
        frame.add("value", value != null ? value : JsonNull.INSTANCE);
        final String text = gson.toJson(frame);
        sendTail = sendTail
                .exceptionally(e -> null)
                .thenCompose(v -> ws.sendText(text, true))
                .thenApply(w -> (Void) null);
        sendTail.exceptionally(e -> {
            Logger.debug("ServerLink: sending {} failed: {}", type, e.getMessage());
            return null;
        });
    }

    private final class SessionListener implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder buffer = new StringBuilder();

        SessionListener(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            exec.execute(() -> onOpened(gen, webSocket));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                exec.execute(() -> onFrame(gen, text));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            exec.execute(() -> onClosed(gen, "closed by server: " + statusCode
                    + (reason != null && !reason.isEmpty() ? " " + reason : "")));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            exec.execute(() -> onClosed(gen, String.valueOf(error.getMessage() != null
                    ? error.getMessage() : error.getClass().getSimpleName())));
        }
    }

    // ── Helpers ──

    private static boolean has(JsonElement value, String key) {
        return value != null && value.isJsonObject() && value.getAsJsonObject().has(key)
                && !value.getAsJsonObject().get(key).isJsonNull();
    }

    private static String stringOf(JsonElement value, String key, String fallback) {
        return has(value, key) ? value.getAsJsonObject().get(key).getAsString() : fallback;
    }

    private void setState(State newState, String detail) {
        state = newState;
        stateDetail = detail;
        fireChanged();
    }

    private void fireChanged() {
        for (Listener l : listeners) {
            try {
                l.onChanged();
            }
            catch (Throwable t) {
                Logger.debug("ServerLink: listener failed: {}", t.getMessage());
            }
        }
    }

    private void log(String line) {
        Logger.info("ServerLink: {}", line);
        synchronized (logBacklog) {
            logBacklog.addLast(line);
            while (logBacklog.size() > LOG_BACKLOG) {
                logBacklog.removeFirst();
            }
        }
        for (Listener l : listeners) {
            try {
                l.onLog(line);
            }
            catch (Throwable t) {
                Logger.debug("ServerLink: listener failed: {}", t.getMessage());
            }
        }
    }
}
