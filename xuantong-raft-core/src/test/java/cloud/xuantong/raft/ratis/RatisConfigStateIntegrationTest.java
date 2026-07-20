package cloud.xuantong.raft.ratis;

import cloud.xuantong.config.state.ApplicableRelease;
import cloud.xuantong.config.state.ApplicableReleaseRequest;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigClientIdentity;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigMutationResult;
import cloud.xuantong.config.state.ConfigProjectionSnapshot;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ConfigStateMachine;
import cloud.xuantong.config.state.ConfigWatchSelector;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.WatchBatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisConfigStateIntegrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void configStateRoundTripsAndRecoversThroughRealRatis() throws Exception {
        StateGroupId groupId = StateGroupId.config("config-business-test");
        RatisPeerDefinition peer = new RatisPeerDefinition(
                "config-state-1", "127.0.0.1", freePort());
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, List.of(peer));
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                tempDirectory.resolve("config-state-1"),
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofSeconds(2),
                10_000,
                false);
        ConfigKey key = new ConfigKey("public", "DEFAULT_GROUP", "demo.value");
        ConfigMutation mutation = new ConfigMutation(
                new ConfigActor("tenant-a", "admin-a"),
                key,
                0,
                ConfigContentDraft.inline("text", 1, bytes("raft-value")),
                ConfigContentReference.newContent(),
                List.of());
        StateCommand command = ConfigStateCodec.mutationCommand(
                groupId, "config-op-1", mutation);

        try (RatisStateNode node = newNode(options, groupId)) {
            node.start();
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                ApplyResult applied = submitEventually(client, command);
                assertEquals(ApplyStatus.APPLIED, applied.status());
                ConfigMutationResult mutationResult = ConfigStateCodec.decodeMutationResult(
                        applied.payload());
                assertEquals(1, mutationResult.decision().decisionRevision());

                ApplicableRelease release = fetch(client, groupId, key);
                assertTrue(release.found());
                assertEquals("raft-value", text(release.content().payload()));

                QueryResult projectionQuery = client.query(
                                ConfigStateCodec.projectionSnapshotQuery(
                                        groupId, ReadOptions.linearizable()))
                        .get(5, TimeUnit.SECONDS);
                ConfigProjectionSnapshot projection =
                        ConfigStateCodec.decodeProjectionSnapshot(
                                projectionQuery.payload());
                assertEquals(1, projection.entries().size());
                assertEquals(release.content().contentHash(), projection.entries()
                        .getFirst().referencedContents().getFirst().contentHash());

                ApplyResult replay = submitEventually(client, command);
                assertEquals(ApplyStatus.UNCHANGED, replay.status());

                WatchBatch watch = client.watch(ConfigStateCodec.changesWatch(
                                groupId,
                                0,
                                new ConfigWatchSelector(
                                        key.namespace(), key.group(), List.of(key)),
                                10,
                                ReadOptions.linearizable()))
                        .get(5, TimeUnit.SECONDS);
                assertEquals(1, watch.coveredThrough().value());
                assertEquals(1, watch.events().size());

                client.forceSnapshot(Duration.ofSeconds(5), peer.nodeId());
            }
        }

        try (RatisStateNode restarted = newNode(options, groupId)) {
            restarted.start();
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                ApplicableRelease release = fetchEventually(client, groupId, key);
                assertEquals("raft-value", text(release.content().payload()));

                ApplyResult replay = submitEventually(client, command);
                assertEquals(ApplyStatus.UNCHANGED, replay.status());
            }
        }
    }

    private RatisStateNode newNode(RatisNodeOptions options, StateGroupId groupId)
            throws Exception {
        return new RatisStateNode(options, ignored -> new RatisStateMachineAdapter(
                new ConfigStateMachine(groupId)));
    }

    private ApplyResult submitEventually(
            RatisStateClient client, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.submit(command).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last == null ? new IllegalStateException("Config submit did not complete") : last;
    }

    private ApplicableRelease fetch(
            RatisStateClient client, StateGroupId groupId, ConfigKey key) throws Exception {
        QueryResult result = client.query(ConfigStateCodec.applicableReleaseQuery(
                        groupId,
                        new ApplicableReleaseRequest(
                                key,
                                new ConfigClientIdentity(
                                        "client-a", "demo", "127.0.0.1", Map.of())),
                        ReadOptions.linearizable()))
                .get(5, TimeUnit.SECONDS);
        return ConfigStateCodec.decodeApplicableRelease(result.payload());
    }

    private ApplicableRelease fetchEventually(
            RatisStateClient client, StateGroupId groupId, ConfigKey key) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return fetch(client, groupId, key);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last == null ? new IllegalStateException("Config query did not complete") : last;
    }

    private int freePort() throws Exception {
        try {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
