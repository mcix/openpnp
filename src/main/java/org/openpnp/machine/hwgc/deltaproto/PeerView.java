/*
 * What Buddy machines see of each other: the "peer view" object of the
 * DeltaProto server protocol (CONNECTED, MACHINE_CONFIG_CHANGED, PEER_CHANGED,
 * GET /api/buddymachine/peers and /me).
 */
package org.openpnp.machine.hwgc.deltaproto;

/** Public fields so Gson can deserialise it through reflection. */
public class PeerView {
    public static final String ROLE_STANDALONE = "STANDALONE";
    public static final String ROLE_MASTER = "MASTER";
    public static final String ROLE_SLAVE = "SLAVE";

    public String machineId;
    public String name;
    public String role;
    public String localIp;
    public Boolean projectFeeders;
    public Integer numberOfReels;
    public Boolean connected;

    public boolean isMaster() {
        return ROLE_MASTER.equalsIgnoreCase(role);
    }

    public boolean isSlave() {
        return ROLE_SLAVE.equalsIgnoreCase(role);
    }

    @Override
    public String toString() {
        return (name != null ? name : machineId) + " [" + role + "] "
                + (localIp != null ? localIp : "no address")
                + (Boolean.TRUE.equals(connected) ? "" : " (offline)");
    }
}
