/*
 * Identity and configuration for the DeltaProto server link: server URL,
 * per-machine token and local (office LAN) IP detection.
 *
 * Persisted in the user's Java Preferences like {@link FeederLayout}, so each
 * machine PC keeps its own token even when the .openpnp2 config is copied.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Collections;
import java.util.Locale;
import java.util.prefs.Preferences;

import org.pmw.tinylog.Logger;

public final class ServerLinkConfig {

    static final String PREF_KEY_BASE_URL = "server.baseUrl";
    static final String PREF_KEY_TOKEN = "server.token";
    static final String PREF_KEY_LOCAL_IP_INTERFACE = "server.localIpInterface";
    static final String PREF_KEY_LAN_PORT = "server.lanPort";

    public static final String DEFAULT_BASE_URL = "https://deltaproto.com";
    public static final int DEFAULT_LAN_PORT = 8765;
    public static final String TOKEN_HEADER = "X-Buddy-Token";

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(ServerLinkConfig.class);

    /** Interface name fragments that are never the office LAN. */
    private static final String[] VIRTUAL_NAME_HINTS = {"docker", "vethernet", "virtualbox",
            "vmware", "hyper-v", "wsl", "tap-", "tunnel", "vpn", "loopback", "bluetooth"};

    public String baseUrl = DEFAULT_BASE_URL;
    public String token = "";
    /** Optional: report the address of this interface instead of auto-detecting. */
    public String localIpInterface = "";
    public int lanPort = DEFAULT_LAN_PORT;

    public static ServerLinkConfig load() {
        ServerLinkConfig c = new ServerLinkConfig();
        c.baseUrl = stripTrailingSlashes(PREFS.get(PREF_KEY_BASE_URL, DEFAULT_BASE_URL).trim());
        c.token = PREFS.get(PREF_KEY_TOKEN, "").trim();
        c.localIpInterface = PREFS.get(PREF_KEY_LOCAL_IP_INTERFACE, "").trim();
        c.lanPort = PREFS.getInt(PREF_KEY_LAN_PORT, DEFAULT_LAN_PORT);
        return c;
    }

    public void save() {
        PREFS.put(PREF_KEY_BASE_URL, stripTrailingSlashes(baseUrl.trim()));
        PREFS.put(PREF_KEY_TOKEN, token.trim());
        PREFS.put(PREF_KEY_LOCAL_IP_INTERFACE, localIpInterface.trim());
        PREFS.putInt(PREF_KEY_LAN_PORT, lanPort);
    }

    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    /** {@code <baseUrl with http→ws / https→wss>/api/buddymachine/ws} */
    public String webSocketUrl() {
        String b = baseUrl;
        if (b.regionMatches(true, 0, "https://", 0, 8)) {
            b = "wss://" + b.substring(8);
        }
        else if (b.regionMatches(true, 0, "http://", 0, 7)) {
            b = "ws://" + b.substring(7);
        }
        return b + "/api/buddymachine/ws";
    }

    public String url(String path) {
        return baseUrl + path;
    }

    /**
     * Adds the machine token to a request to the DeltaProto server. The token
     * is a secret: it is only attached when the request goes to the configured
     * server host, never to some other URL typed into an endpoint field.
     */
    public static HttpRequest.Builder authorize(HttpRequest.Builder builder, String url) {
        try {
            ServerLinkConfig c = load();
            if (c.hasToken() && sameHost(c.baseUrl, url)) {
                builder.header(TOKEN_HEADER, c.token);
            }
        }
        catch (Exception e) {
            Logger.warn("ServerLink: could not attach machine token: {}", e.getMessage());
        }
        return builder;
    }

    private static boolean sameHost(String a, String b) {
        String ha = URI.create(a).getHost();
        String hb = URI.create(b).getHost();
        return ha != null && ha.equalsIgnoreCase(hb);
    }

    /**
     * Normalises what the operator typed to a base URL. Pasting the websocket
     * URL from the protocol doc (wss://host/api/buddymachine/ws) is an easy
     * mistake, so ws(s):// and a trailing /api/… path are accepted and removed.
     */
    private static String stripTrailingSlashes(String s) {
        if (s.regionMatches(true, 0, "wss://", 0, 6)) {
            s = "https://" + s.substring(6);
        }
        else if (s.regionMatches(true, 0, "ws://", 0, 5)) {
            s = "http://" + s.substring(5);
        }
        else if (!s.isEmpty() && !s.contains("://")) {
            s = "https://" + s;
        }
        int api = s.indexOf("/api/");
        if (api > 0) {
            s = s.substring(0, api);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ── Local IP detection ──

    /**
     * The office-LAN IPv4 address of this machine: the address of the
     * configured interface, else the one carrying the route to the server.
     * Never loopback, link-local or a virtual bridge. Null when undetectable.
     */
    public String detectLocalIp() {
        try {
            if (!localIpInterface.isEmpty()) {
                String ip = addressOfInterface(localIpInterface);
                if (ip != null) {
                    return ip;
                }
                Logger.warn("ServerLink: interface '{}' not found or has no usable IPv4 address,"
                        + " falling back to auto-detect", localIpInterface);
            }
            String routed = routedAddress();
            if (routed != null) {
                return routed;
            }
            return firstUsableAddress();
        }
        catch (Exception e) {
            Logger.warn("ServerLink: local IP detection failed: {}", e.getMessage());
            return null;
        }
    }

    /** Source address the OS picks for the route to the server. A UDP
     *  connect() only selects the route; no packet is sent. */
    private String routedAddress() {
        try (DatagramSocket s = new DatagramSocket()) {
            URI uri = URI.create(baseUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            s.connect(new InetSocketAddress(uri.getHost(), port));
            InetAddress a = s.getLocalAddress();
            if (usable(a) && !isVirtual(NetworkInterface.getByInetAddress(a))) {
                return a.getHostAddress();
            }
        }
        catch (Exception e) {
            Logger.debug("ServerLink: routed address lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private static String firstUsableAddress() throws Exception {
        String fallback = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || isVirtual(ni)) {
                continue;
            }
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (!usable(a)) {
                    continue;
                }
                if (a.isSiteLocalAddress()) {
                    return a.getHostAddress();
                }
                if (fallback == null) {
                    fallback = a.getHostAddress();
                }
            }
        }
        return fallback;
    }

    private static String addressOfInterface(String wanted) throws Exception {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            boolean match = wanted.equalsIgnoreCase(ni.getName())
                    || (ni.getDisplayName() != null
                            && wanted.equalsIgnoreCase(ni.getDisplayName()));
            if (!match || !ni.isUp()) {
                continue;
            }
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (usable(a)) {
                    return a.getHostAddress();
                }
            }
        }
        return null;
    }

    private static boolean usable(InetAddress a) {
        return a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                && !a.isAnyLocalAddress() && !a.isMulticastAddress();
    }

    private static boolean isVirtual(NetworkInterface ni) {
        if (ni == null) {
            return false;
        }
        if (ni.isVirtual()) {
            return true;
        }
        String n = ((ni.getName() == null ? "" : ni.getName()) + " "
                + (ni.getDisplayName() == null ? "" : ni.getDisplayName()))
                        .toLowerCase(Locale.ROOT);
        for (String hint : VIRTUAL_NAME_HINTS) {
            if (n.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
