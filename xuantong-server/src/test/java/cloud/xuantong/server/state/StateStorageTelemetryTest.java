package cloud.xuantong.server.state;

import org.apache.ratis.util.MD5FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class StateStorageTelemetryTest {
    @TempDir
    Path tempDirectory;

    @Test
    void classifiesWalSnapshotAndMetadataFiles() throws Exception {
        Path current = Files.createDirectories(tempDirectory.resolve("group/current"));
        Path snapshots = Files.createDirectories(tempDirectory.resolve("group/sm"));
        Files.write(current.resolve("log_1-2"), new byte[4]);
        Files.write(current.resolve("raft-meta"), new byte[1]);
        Path snapshotFile = snapshots.resolve("snapshot.1_2");
        Files.write(snapshotFile, new byte[3]);
        MD5FileUtil.computeAndSaveMd5ForFile(snapshotFile.toFile());

        StateStorageTelemetry telemetry = new StateStorageTelemetry(
                properties(tempDirectory), 0L);
        StateStorageTelemetry.Snapshot snapshot = telemetry.snapshot();

        assertTrue(snapshot.scanComplete());
        assertTrue(snapshot.storageFreeBytes() > 0L);
        assertEquals(0L, snapshot.storageFreeSpaceMinBytes());
        assertTrue(snapshot.storageFreeSpaceAboveMinimum());
        assertEquals(4L, snapshot.totalFileCount());
        assertEquals(1L, snapshot.walFileCount());
        assertEquals(4L, snapshot.walBytes());
        assertEquals(1L, snapshot.snapshotFileCount());
        assertEquals(3L, snapshot.snapshotBytes());
        assertEquals(1L, snapshot.snapshotChecksumVerifiedCount());
        assertEquals(0L, snapshot.snapshotChecksumMismatchCount());
        assertEquals(0L, snapshot.snapshotChecksumUnverifiedCount());
        assertEquals(0L, snapshot.snapshotChecksumFailureTotal());
        assertEquals(2L, snapshot.otherFileCount());

        Files.write(snapshotFile, new byte[]{1, 2, 3});
        StateStorageTelemetry.Snapshot damaged = telemetry.snapshot();
        assertEquals(0L, damaged.snapshotChecksumVerifiedCount());
        assertEquals(1L, damaged.snapshotChecksumMismatchCount());
        assertEquals(0L, damaged.snapshotChecksumUnverifiedCount());
        assertEquals(1L, damaged.snapshotChecksumFailureTotal());
    }

    @Test
    void reusesFilesystemSnapshotWithinCacheWindow() throws Exception {
        Path current = Files.createDirectories(tempDirectory.resolve("group/current"));
        Files.write(current.resolve("log_1-2"), new byte[4]);
        StateStorageTelemetry telemetry = new StateStorageTelemetry(
                properties(tempDirectory), 15_000L);

        StateStorageTelemetry.Snapshot first = telemetry.snapshot();
        StateStorageTelemetry.Snapshot second = telemetry.snapshot();

        assertSame(first, second);
    }

    @Test
    void runtimeWatermarkRejectsWritesAndRecoversWithoutRestart() throws Exception {
        ConfigStatePlaneProperties properties = properties(tempDirectory);
        setStorageMinimum(properties, 100L);
        AtomicLong available = new AtomicLong(99L);
        StateStorageTelemetry telemetry = new StateStorageTelemetry(
                properties, ignored -> available.get());
        StateGroupId groupId = properties.stateGroupId();

        StateAccessException rejected = assertThrows(
                StateAccessException.class,
                () -> telemetry.check(groupId));

        assertEquals(StateFailureCode.STORAGE_EXHAUSTED, rejected.code());
        assertEquals(StateCommitStatus.NOT_COMMITTED, rejected.commitStatus());
        assertTrue(rejected.retryable());

        available.set(100L);
        assertDoesNotThrow(() -> telemetry.check(groupId));
    }

    @Test
    void inaccessibleStorageRejectsBeforeStateSubmission() throws Exception {
        Path missing = tempDirectory.resolve("missing-state-root");
        StateStorageTelemetry telemetry = new StateStorageTelemetry(
                properties(missing), ignored -> 1_000L);

        StateAccessException rejected = assertThrows(
                StateAccessException.class,
                () -> telemetry.check(StateGroupId.config("config-default")));

        assertEquals(StateFailureCode.STATE_UNAVAILABLE, rejected.code());
        assertEquals(StateCommitStatus.NOT_COMMITTED, rejected.commitStatus());
    }

    private ConfigStatePlaneProperties properties(Path storage) throws Exception {
        return new ConfigStatePlaneProperties(
                true,
                "state-1",
                "config-default",
                "state-1@127.0.0.1:9101",
                storage,
                true);
    }

    private void setStorageMinimum(
            ConfigStatePlaneProperties properties, long minimum) throws Exception {
        Field field = ConfigStatePlaneProperties.class.getDeclaredField(
                "storageFreeSpaceMinBytes");
        field.setAccessible(true);
        field.setLong(properties, minimum);
    }
}
