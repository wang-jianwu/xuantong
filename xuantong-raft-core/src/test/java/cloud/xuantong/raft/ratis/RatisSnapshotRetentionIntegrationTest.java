package cloud.xuantong.raft.ratis;

import cloud.xuantong.config.state.ApplicableRelease;
import cloud.xuantong.config.state.ApplicableReleaseRequest;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigClientIdentity;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ConfigStateMachine;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RatisSnapshotRetentionIntegrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void snapshotRetentionIsBoundedAndLatestStateStillRecovers() throws Exception {
        StateGroupId groupId = StateGroupId.config("snapshot-retention");
        RatisPeerDefinition peer = new RatisPeerDefinition(
                "state-1", "127.0.0.1", freePort());
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, List.of(peer));
        Path storage = tempDirectory.resolve("state-1");
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                storage,
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofSeconds(2),
                10_000L,
                false,
                2,
                RatisStartupMode.BOOTSTRAP_OR_RECOVER);
        ConfigKey key = new ConfigKey("public", "DEFAULT_GROUP", "retained.value");

        try (RatisStateNode node = newNode(options, groupId)) {
            node.start();
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                for (int revision = 1; revision <= 5; revision++) {
                    ApplyResult applied = submitEventually(
                            client, mutation(groupId, key, revision));
                    assertEquals(ApplyStatus.APPLIED, applied.status());
                    client.forceSnapshot(Duration.ofSeconds(5), peer.nodeId());
                }
                awaitSnapshotRetention(storage, 2, Duration.ofSeconds(10));
                assertEquals("value-5", fetch(client, groupId, key));
            }
        }

        try (RatisStateNode restarted = newNode(options, groupId)) {
            restarted.start();
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                assertEquals("value-5", fetchEventually(client, groupId, key));
            }
        }
    }

    private RatisStateNode newNode(RatisNodeOptions options, StateGroupId groupId)
            throws Exception {
        return new RatisStateNode(options, ignored -> new RatisStateMachineAdapter(
                new ConfigStateMachine(groupId)));
    }

    private StateCommand mutation(StateGroupId groupId, ConfigKey key, int revision) {
        return ConfigStateCodec.mutationCommand(
                groupId,
                "snapshot-retention-" + revision,
                new ConfigMutation(
                        new ConfigActor("tenant-a", "admin-a"),
                        key,
                        revision - 1L,
                        ConfigContentDraft.inline(
                                "text", revision, bytes("value-" + revision)),
                        ConfigContentReference.newContent(),
                        List.of()));
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
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException("Config submit did not complete") : last;
    }

    private String fetch(
            RatisStateClient client, StateGroupId groupId, ConfigKey key) throws Exception {
        ApplicableRelease release = ConfigStateCodec.decodeApplicableRelease(
                client.query(ConfigStateCodec.applicableReleaseQuery(
                                groupId,
                                new ApplicableReleaseRequest(
                                        key,
                                        new ConfigClientIdentity(
                                                "client-a", "demo", "127.0.0.1", Map.of())),
                                ReadOptions.linearizable()))
                        .get(5, TimeUnit.SECONDS)
                        .payload());
        return new String(release.content().payload(), StandardCharsets.UTF_8);
    }

    private String fetchEventually(
            RatisStateClient client, StateGroupId groupId, ConfigKey key) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return fetch(client, groupId, key);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException("Config query did not complete") : last;
    }

    private void awaitSnapshotRetention(
            Path storage, int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long lastCount = -1L;
        while (System.nanoTime() < deadline) {
            lastCount = snapshotFileCount(storage);
            if (lastCount == expected) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError(
                "Expected " + expected + " retained Snapshot files but found " + lastCount);
    }

    private long snapshotFileCount(Path storage) throws Exception {
        if (!Files.exists(storage)) {
            return 0L;
        }
        try (var files = Files.walk(storage)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("snapshot."))
                    .filter(name -> !name.endsWith(".md5"))
                    .count();
        }
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

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
