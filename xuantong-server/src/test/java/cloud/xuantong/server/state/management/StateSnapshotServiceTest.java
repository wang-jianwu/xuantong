package cloud.xuantong.server.state.management;

import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateSnapshotServiceTest {
    @TempDir
    Path storageDirectory;

    @Test
    void forcesEveryEnabledGroupOnTheRequestedNode() throws Exception {
        ConfigStatePlaneProperties config = configProperties();
        RegistryStatePlaneProperties registry =
                new RegistryStatePlaneProperties(true, "registry-main", 3_000, 120_000);
        List<String> calls = new ArrayList<>();
        StateSnapshotService service = new StateSnapshotService(
                config,
                registry,
                (group, timeout, nodeId) -> {
                    calls.add(group.groupId() + "@" + nodeId + ":" + timeout.toMillis());
                    return new StateSnapshotService.GroupSnapshot(
                            group.groupId().type().name(),
                            group.groupId().value(),
                            nodeId,
                            calls.size() * 10L);
                });

        StateSnapshotService.SnapshotBatchResult result = service.force(
                new StateSnapshotService.SnapshotBatchRequest("backup-20260719", "state-1"));

        assertEquals("backup-20260719", result.operationId());
        assertEquals("state-1", result.targetNodeId());
        assertEquals(2, result.groups().size());
        assertEquals(List.of(
                "config:config-main@state-1:30000",
                "registry:registry-main@state-1:30000"), calls);
        assertEquals(10L, result.groups().get(0).logIndex());
        assertEquals(20L, result.groups().get(1).logIndex());
    }

    @Test
    void rejectsNodeOutsideTheConfiguredVoterSetBeforeSnapshotting() {
        StateSnapshotService service = new StateSnapshotService(
                configProperties(),
                new RegistryStatePlaneProperties(false, "registry-main", 3_000, 120_000),
                (group, timeout, nodeId) -> {
                    throw new AssertionError("snapshot invoker must not be called");
                });

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> service.force(new StateSnapshotService.SnapshotBatchRequest(
                        "backup-invalid", "state-9")));

        assertTrue(failure.getMessage().contains("state-9"));
    }

    @Test
    void reportsTheGroupThatFailedInsteadOfReturningAPartialSuccess() {
        StateSnapshotService service = new StateSnapshotService(
                configProperties(),
                new RegistryStatePlaneProperties(true, "registry-main", 3_000, 120_000),
                (group, timeout, nodeId) -> {
                    if ("registry-main".equals(group.groupId().value())) {
                        throw new IOException("disk error");
                    }
                    return new StateSnapshotService.GroupSnapshot(
                            group.groupId().type().name(),
                            group.groupId().value(),
                            nodeId,
                            7L);
                });

        IOException failure = assertThrows(
                IOException.class,
                () -> service.force(new StateSnapshotService.SnapshotBatchRequest(
                        "backup-partial", "state-1")));

        assertTrue(failure.getMessage().contains("registry:registry-main"));
    }

    private ConfigStatePlaneProperties configProperties() {
        return new ConfigStatePlaneProperties(
                true,
                "state-1",
                "config-main",
                "state-1@127.0.0.1:19091",
                storageDirectory,
                true);
    }
}
