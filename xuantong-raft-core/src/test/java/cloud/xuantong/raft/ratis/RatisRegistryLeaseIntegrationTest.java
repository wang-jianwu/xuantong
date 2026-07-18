package cloud.xuantong.raft.ratis;

import cloud.xuantong.registry.state.InstanceKey;
import cloud.xuantong.registry.state.ActivateServiceDefinition;
import cloud.xuantong.registry.state.LeaseReference;
import cloud.xuantong.registry.state.LeaseRenewal;
import cloud.xuantong.registry.state.RegisterLease;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryInstance;
import cloud.xuantong.registry.state.RegistryMutationResult;
import cloud.xuantong.registry.state.RegistrySnapshot;
import cloud.xuantong.registry.state.RegistrySnapshotRequest;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.RegistryStateMachine;
import cloud.xuantong.registry.state.RenewLeaseBatch;
import cloud.xuantong.registry.state.ServiceKey;
import cloud.xuantong.registry.state.ServiceRegistration;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisRegistryLeaseIntegrationTest {
    @TempDir
    Path tempDirectory;

    private final List<RatisStateNode> openNodes = new ArrayList<>();

    @AfterEach
    void closeNodes() {
        for (int i = openNodes.size() - 1; i >= 0; i--) {
            try {
                openNodes.get(i).close();
            } catch (Exception ignored) {
            }
        }
        openNodes.clear();
    }

    @Test
    void leaseSurvivesLeaderFailureWithoutClientMultiWrite() throws Exception {
        StateGroupId groupId = StateGroupId.registry("registry-ha-test");
        List<RatisPeerDefinition> peers = List.of(
                new RatisPeerDefinition("state-1", "127.0.0.1", freePort()),
                new RatisPeerDefinition("state-2", "127.0.0.1", freePort()),
                new RatisPeerDefinition("state-3", "127.0.0.1", freePort()));
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, peers);
        for (RatisPeerDefinition peer : peers) {
            RatisNodeOptions options = new RatisNodeOptions(
                    peer.nodeId(),
                    group,
                    tempDirectory.resolve(peer.nodeId()),
                    Duration.ofMillis(300),
                    Duration.ofMillis(600),
                    Duration.ofSeconds(2),
                    10_000,
                    false);
            RatisStateNode node = new RatisStateNode(
                    options, ignored -> new RatisStateMachineAdapter(
                            new RegistryStateMachine(groupId)));
            node.start();
            openNodes.add(node);
        }

        RegistryActor actor = new RegistryActor("tenant-a", "orders@node-1", "orders");
        InstanceKey key = new InstanceKey(
                new ServiceKey("public", "DEFAULT_GROUP", "orders"),
                "orders-1");
        StateCommand register = RegistryStateCodec.mutationCommand(
                groupId,
                "register-1",
                new RegisterLease(
                        actor,
                        new ServiceRegistration(
                                key, 0L, "10.0.0.8", 8080, 1D, true, ""),
                        "lease-1",
                        30_000,
                        1_000));
        RegistryInstance lease;
        try (RatisStateClient client = new RatisStateClient(
                group, Duration.ofSeconds(2), 5)) {
            ApplyResult serviceActivated = submitEventually(
                    client,
                    RegistryStateCodec.mutationCommand(
                            groupId,
                            "activate-orders",
                            new ActivateServiceDefinition(
                                    RegistryActor.system("management"),
                                    key.service(),
                                    0L,
                                    500L)));
            assertEquals(ApplyStatus.APPLIED, serviceActivated.status());
            ApplyResult applied = submitEventually(client, register);
            assertEquals(ApplyStatus.APPLIED, applied.status());
            lease = RegistryStateCodec.decodeMutationResult(applied.payload())
                    .instances().getFirst();
        }

        RatisStateNode leader = awaitLeader();
        leader.close();
        openNodes.remove(leader);

        StateCommand renew = RegistryStateCodec.mutationCommand(
                groupId,
                "renew-after-leader-failure",
                new RenewLeaseBatch(
                        actor,
                        List.of(new LeaseRenewal(
                                new LeaseReference(
                                        key,
                                        lease.leaseId(),
                                        lease.leaseEpoch(),
                                        lease.recoveryEpoch()),
                                1,
                                30_000)),
                        2_000));
        try (RatisStateClient client = new RatisStateClient(
                group, Duration.ofSeconds(2), 10)) {
            ApplyResult renewed = submitEventually(client, renew);
            assertEquals(ApplyStatus.APPLIED, renewed.status());
            RegistryMutationResult result = RegistryStateCodec.decodeMutationResult(
                    renewed.payload());
            assertEquals(1, result.instances().getFirst().renewSequence());

            QueryResult query = queryEventually(client, groupId, key.service());
            RegistrySnapshot snapshot = RegistryStateCodec.decodeSnapshot(query.payload());
            assertEquals(1, snapshot.instances().size());
            assertEquals("lease-1", snapshot.instances().getFirst().leaseId());
            assertEquals(1, snapshot.instances().getFirst().renewSequence());
        }
    }

    private RatisStateNode awaitLeader() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            for (RatisStateNode node : openNodes) {
                if (node.isLeader()) {
                    return node;
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Registry Group did not elect a leader");
    }

    private ApplyResult submitEventually(
            RatisStateClient client, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.submit(command).get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(150L);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("Registry submit did not complete");
    }

    private QueryResult queryEventually(
            RatisStateClient client, StateGroupId groupId, ServiceKey service)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.query(RegistryStateCodec.snapshotQuery(
                                groupId,
                                new RegistrySnapshotRequest(
                                        service.namespace(),
                                        service.group(),
                                        List.of(service.serviceName())),
                                ReadOptions.linearizable()))
                        .get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(150L);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("Registry query did not complete");
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }
}
