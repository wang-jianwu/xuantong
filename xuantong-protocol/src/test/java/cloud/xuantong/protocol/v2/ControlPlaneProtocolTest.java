package cloud.xuantong.protocol.v2;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneProtocolTest {
    @Test
    void envelopeRoundTripPreservesVersionIdentityBudgetAndPayload() throws Exception {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("order-service@node-a-1234")
                .setApplicationName("order-service")
                .setGroupName("DEFAULT_GROUP")
                .setClientVersion("2.0.0")
                .setSdkName("xuantong-client-java")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .addCapabilities("protobuf-envelope")
                .setTransportPool("tcp-default")
                .build();
        String requestId = UUID.randomUUID().toString();
        Envelope encoded = Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-a")
                .setTransportGeneration(3)
                .setRequestId(requestId)
                .setTraceId("trace-1")
                .setTenant("default")
                .setNamespaceId("public")
                .setRemainingBudgetMs(5_000)
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();

        Envelope decoded = Envelope.parseFrom(encoded.toByteArray());
        HelloRequest decodedHello = HelloRequest.parseFrom(decoded.getPayload());

        assertEquals(ControlPlaneProtocol.CURRENT_VERSION, decoded.getProtocolVersion());
        assertEquals(requestId, decoded.getRequestId());
        assertEquals(5_000, decoded.getRemainingBudgetMs());
        assertEquals("order-service", decodedHello.getApplicationName());
        assertEquals("order-service@node-a-1234", decodedHello.getClientInstanceId());
        assertEquals("DEFAULT_GROUP", decodedHello.getGroupName());
    }

    @Test
    void responseStatusCarriesScopedRetryInformation() throws Exception {
        ResponseStatus status = ResponseStatus.newBuilder()
                .setCode(ResponseCode.STALE_REPLICA)
                .setMessage("registry replica is behind")
                .setRetryable(true)
                .setRetryAfterMs(25)
                .setGroupId("registry-07")
                .setRevisionType(RevisionType.REGISTRY)
                .setObservedRevision(99)
                .setCommitStatus(CommitStatus.UNKNOWN)
                .build();
        Envelope encoded = Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setRequestId("request-1")
                .setPayload(ByteString.EMPTY)
                .setResponseStatus(status)
                .build();

        ResponseStatus decoded = Envelope.parseFrom(encoded.toByteArray()).getResponseStatus();

        assertEquals(ResponseCode.STALE_REPLICA, decoded.getCode());
        assertEquals(RevisionType.REGISTRY, decoded.getRevisionType());
        assertEquals("registry-07", decoded.getGroupId());
        assertEquals(99, decoded.getObservedRevision());
        assertEquals(CommitStatus.UNKNOWN, decoded.getCommitStatus());
        assertTrue(decoded.getRetryable());
    }

    @Test
    void storageAdmissionFailureCarriesSafeWriteRetrySemantics() throws Exception {
        ResponseStatus status = ResponseStatus.newBuilder()
                .setCode(ResponseCode.STORAGE_EXHAUSTED)
                .setMessage("storage watermark reached")
                .setRetryable(true)
                .setGroupId("config-default")
                .setCommitStatus(CommitStatus.NOT_COMMITTED)
                .build();

        ResponseStatus decoded = ResponseStatus.parseFrom(status.toByteArray());

        assertEquals(ResponseCode.STORAGE_EXHAUSTED, decoded.getCode());
        assertEquals(CommitStatus.NOT_COMMITTED, decoded.getCommitStatus());
        assertTrue(decoded.getRetryable());
    }

    @Test
    void configFetchAndWatchContractsPreserveIndependentRevisions() throws Exception {
        ConfigCoordinate key = ConfigCoordinate.newBuilder()
                .setNamespaceId("public")
                .setGroupName("DEFAULT_GROUP")
                .setDataId("demo.json")
                .build();
        ConfigFetchResponse fetch = ConfigFetchResponse.newBuilder()
                .setState(ConfigValueState.CONFIG_VALUE_STATE_ACTIVE)
                .setConfig(key)
                .setDecisionRevision(7)
                .setContent(ConfigContentValue.newBuilder()
                        .setContentRevision(3)
                        .setContentHash("a".repeat(64))
                        .setContentType("json")
                        .setSchemaVersion(1)
                        .setPayload(ByteString.copyFromUtf8("{\"enabled\":true}")))
                .setMatchedRuleId("gray-cn-east")
                .build();
        ConfigWatchBatchResponse watch = ConfigWatchBatchResponse.newBuilder()
                .setRequestedAfterRevision(20)
                .setCoveredThroughRevision(24)
                .setCompactionRevision(10)
                .addEvents(ConfigInvalidation.newBuilder()
                        .setEventRevision(23)
                        .setConfig(key)
                        .setDecisionRevision(7))
                .build();

        ConfigFetchResponse decodedFetch = ConfigFetchResponse.parseFrom(fetch.toByteArray());
        ConfigWatchBatchResponse decodedWatch =
                ConfigWatchBatchResponse.parseFrom(watch.toByteArray());

        assertEquals(3, decodedFetch.getContent().getContentRevision());
        assertEquals(ConfigValueState.CONFIG_VALUE_STATE_ACTIVE, decodedFetch.getState());
        assertEquals(7, decodedFetch.getDecisionRevision());
        assertEquals(23, decodedWatch.getEvents(0).getEventRevision());
        assertEquals(24, decodedWatch.getCoveredThroughRevision());

        ConfigFetchResponse tombstone = ConfigFetchResponse.newBuilder()
                .setState(ConfigValueState.CONFIG_VALUE_STATE_TOMBSTONE)
                .setConfig(key)
                .setDecisionRevision(8)
                .build();
        ConfigFetchResponse decodedTombstone = ConfigFetchResponse.parseFrom(
                tombstone.toByteArray());
        assertEquals(ConfigValueState.CONFIG_VALUE_STATE_TOMBSTONE,
                decodedTombstone.getState());
        assertFalse(decodedTombstone.hasContent());
    }

    @Test
    void watchAcknowledgementCarriesSubscriptionIdentityAndCommittedRevision()
            throws Exception {
        WatchAckRequest request = WatchAckRequest.newBuilder()
                .setSubscriptionRequestId("subscription-request-1")
                .setCommittedRevision(42)
                .build();

        WatchAckRequest decoded = WatchAckRequest.parseFrom(request.toByteArray());

        assertEquals("subscription-request-1", decoded.getSubscriptionRequestId());
        assertEquals(42, decoded.getCommittedRevision());
        assertEquals("system/watch-ack", ControlPlaneProtocol.SYSTEM_WATCH_ACK);
    }

    @Test
    void discoveryLeaseContractCarriesFencingAndRegistryRevision() throws Exception {
        ServiceInstanceCoordinate coordinate = ServiceInstanceCoordinate.newBuilder()
                .setService(ServiceCoordinate.newBuilder()
                        .setNamespaceId("public")
                        .setGroupName("DEFAULT_GROUP")
                        .setServiceName("orders"))
                .setInstanceId("orders-1")
                .build();
        DiscoveryServiceInstance instance = DiscoveryServiceInstance.newBuilder()
                .setInstance(coordinate)
                .setIp("10.0.0.8")
                .setPort(8080)
                .setWeight(1D)
                .setEnabled(true)
                .setServiceGeneration(4)
                .setLeaseId("lease-1")
                .setLeaseEpoch(3)
                .setRecoveryEpoch(2)
                .setRenewSequence(9)
                .setOwnerClientInstanceId("orders@node-1")
                .setOwnerApplicationName("orders")
                .setRegisteredAtEpochMs(1000)
                .setLastRenewedAtEpochMs(2000)
                .setExpiresAtEpochMs(32000)
                .build();
        DiscoveryMutationResponse response = DiscoveryMutationResponse.newBuilder()
                .setAction("RENEW_BATCH")
                .setRegistryRevision(17)
                .setServerTimeEpochMs(2000)
                .addInstances(instance)
                .build();

        DiscoveryMutationResponse decoded = DiscoveryMutationResponse.parseFrom(
                response.toByteArray());

        assertEquals(17, decoded.getRegistryRevision());
        assertEquals(4, decoded.getInstances(0).getServiceGeneration());
        assertEquals(3, decoded.getInstances(0).getLeaseEpoch());
        assertEquals(2, decoded.getInstances(0).getRecoveryEpoch());
        assertEquals(9, decoded.getInstances(0).getRenewSequence());
    }
}
