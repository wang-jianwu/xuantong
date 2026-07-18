package cloud.xuantong.protocol.v2;

/**
 * Stable event and payload identifiers for the Xuantong 2.0 control-plane wire protocol.
 */
public final class ControlPlaneProtocol {
    public static final int CURRENT_VERSION = 2;

    public static final String CONTROL_PATH = "/control-v2";
    public static final String SYSTEM_HELLO = "system/hello";
    public static final String SYSTEM_PROBE = "system/probe";
    public static final String SYSTEM_WATCH_ACK = "system/watch-ack";
    public static final String CONFIG_FETCH = "config/fetch";
    public static final String CONFIG_SNAPSHOT = "config/snapshot";
    public static final String CONFIG_WATCH_BATCH = "config/watch-batch";
    public static final String DISCOVERY_REGISTER = "discovery/register";
    public static final String DISCOVERY_RENEW_BATCH = "discovery/renew-batch";
    public static final String DISCOVERY_DEREGISTER = "discovery/deregister";
    public static final String DISCOVERY_TAKEOVER = "discovery/takeover-and-renew";
    public static final String DISCOVERY_SNAPSHOT = "discovery/snapshot";
    public static final String DISCOVERY_WATCH_BATCH = "discovery/watch-batch";
    public static final String DISCOVERY_GET_LEASE_STATE = "discovery/get-lease-state";
    public static final String DISCOVERY_RESOLVE_OPERATION = "discovery/resolve-operation";

    public static final String HELLO_REQUEST_TYPE = "xuantong.control.v2.HelloRequest";
    public static final String HELLO_RESPONSE_TYPE = "xuantong.control.v2.HelloResponse";
    public static final String PROBE_REQUEST_TYPE = "xuantong.control.v2.ProbeRequest";
    public static final String PROBE_RESPONSE_TYPE = "xuantong.control.v2.ProbeResponse";
    public static final String WATCH_ACK_REQUEST_TYPE =
            "xuantong.control.v2.WatchAckRequest";
    public static final String WATCH_ACK_RESPONSE_TYPE =
            "xuantong.control.v2.WatchAckResponse";
    public static final String CONFIG_FETCH_REQUEST_TYPE =
            "xuantong.control.v2.ConfigFetchRequest";
    public static final String CONFIG_FETCH_RESPONSE_TYPE =
            "xuantong.control.v2.ConfigFetchResponse";
    public static final String CONFIG_SNAPSHOT_REQUEST_TYPE =
            "xuantong.control.v2.ConfigSnapshotRequest";
    public static final String CONFIG_SNAPSHOT_RESPONSE_TYPE =
            "xuantong.control.v2.ConfigSnapshotResponse";
    public static final String CONFIG_WATCH_BATCH_REQUEST_TYPE =
            "xuantong.control.v2.ConfigWatchBatchRequest";
    public static final String CONFIG_WATCH_BATCH_RESPONSE_TYPE =
            "xuantong.control.v2.ConfigWatchBatchResponse";
    public static final String DISCOVERY_REGISTER_REQUEST_TYPE =
            "xuantong.control.v2.DiscoveryRegisterRequest";
    public static final String DISCOVERY_RENEW_BATCH_REQUEST_TYPE =
            "xuantong.control.v2.DiscoveryRenewBatchRequest";
    public static final String DISCOVERY_DEREGISTER_REQUEST_TYPE =
            "xuantong.control.v2.DiscoveryDeregisterRequest";
    public static final String DISCOVERY_TAKEOVER_REQUEST_TYPE =
            "xuantong.control.v2.DiscoveryTakeoverRequest";
    public static final String DISCOVERY_MUTATION_RESPONSE_TYPE =
            "xuantong.control.v2.DiscoveryMutationResponse";
    public static final String DISCOVERY_SNAPSHOT_REQUEST_TYPE =
            "xuantong.control.v2.DiscoverySnapshotRequest";
    public static final String DISCOVERY_SNAPSHOT_RESPONSE_TYPE =
            "xuantong.control.v2.DiscoverySnapshotResponse";
    public static final String DISCOVERY_WATCH_BATCH_REQUEST_TYPE =
            "xuantong.control.v2.DiscoveryWatchBatchRequest";
    public static final String DISCOVERY_WATCH_BATCH_RESPONSE_TYPE =
            "xuantong.control.v2.DiscoveryWatchBatchResponse";
    public static final String DISCOVERY_GET_LEASE_STATE_REQUEST_TYPE =
            "xuantong.control.v2.DiscoveryGetLeaseStateRequest";
    public static final String DISCOVERY_GET_LEASE_STATE_RESPONSE_TYPE =
            "xuantong.control.v2.DiscoveryGetLeaseStateResponse";
    public static final String DISCOVERY_RESOLVE_OPERATION_REQUEST_TYPE =
            "xuantong.control.v2.DiscoveryResolveOperationRequest";
    public static final String DISCOVERY_RESOLVE_OPERATION_RESPONSE_TYPE =
            "xuantong.control.v2.DiscoveryResolveOperationResponse";

    private ControlPlaneProtocol() {
    }
}
