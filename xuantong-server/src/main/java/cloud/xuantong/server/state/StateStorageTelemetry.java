package cloud.xuantong.server.state;

import cloud.xuantong.raft.ratis.RatisSnapshotChecksum;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateWriteAdmission;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Low-frequency filesystem telemetry for Raft WAL and Snapshot growth. */
@Component
public class StateStorageTelemetry implements StateWriteAdmission {
    @Inject
    private ConfigStatePlaneProperties properties;

    private final AtomicLong scanFailureTotal = new AtomicLong();
    private final AtomicLong snapshotChecksumFailureTotal = new AtomicLong();
    private final Set<String> reportedSnapshotChecksumFailures =
            ConcurrentHashMap.newKeySet();
    private final StorageSpaceProbe storageSpaceProbe;

    public StateStorageTelemetry() {
        this.storageSpaceProbe = StateStorageTelemetry::usableSpace;
    }

    StateStorageTelemetry(ConfigStatePlaneProperties properties) {
        this(properties, StateStorageTelemetry::usableSpace);
    }

    StateStorageTelemetry(
            ConfigStatePlaneProperties properties,
            StorageSpaceProbe storageSpaceProbe) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (storageSpaceProbe == null) {
            throw new IllegalArgumentException("storageSpaceProbe must not be null");
        }
        this.properties = properties;
        this.storageSpaceProbe = storageSpaceProbe;
    }

    @Override
    public void check(StateGroupId groupId) {
        Health current = health();
        if (!current.accessible()) {
            throw StateAccessException.retryable(
                    StateFailureCode.STATE_UNAVAILABLE,
                    groupId,
                    "State storage is not accessible for writes",
                    StateCommitStatus.NOT_COMMITTED,
                    null);
        }
        if (!current.storageFreeSpaceAboveMinimum()) {
            throw StateAccessException.retryable(
                    StateFailureCode.STORAGE_EXHAUSTED,
                    groupId,
                    "State storage is below the configured write-admission watermark",
                    StateCommitStatus.NOT_COMMITTED,
                    null);
        }
    }

    /** Cheap readiness probe; full file classification and checksum work stays in metrics. */
    public Health health() {
        Path root = properties.storageDirectory();
        long minimum = properties.storageFreeSpaceMinBytes();
        long free = storageFreeBytes(root);
        boolean accessible = Files.isDirectory(root)
                && Files.isReadable(root)
                && Files.isWritable(root);
        return new Health(
                properties.isEnabled(), root.toString(), accessible, free, minimum);
    }

    public Snapshot snapshot() {
        Path root = properties.storageDirectory();
        long storageFreeSpaceMinBytes = properties.storageFreeSpaceMinBytes();
        long storageFreeBytes = storageFreeBytes(root);
        if (!Files.exists(root)) {
            return Snapshot.empty(
                    properties.isEnabled(),
                    root,
                    storageFreeBytes,
                    storageFreeSpaceMinBytes,
                    scanFailureTotal.get(),
                    snapshotChecksumFailureTotal.get());
        }
        MutableSnapshot result = new MutableSnapshot();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> accumulate(path, result));
            long failures = result.partial
                    ? scanFailureTotal.incrementAndGet() : scanFailureTotal.get();
            return result.freeze(
                    properties.isEnabled(),
                    root,
                    storageFreeBytes,
                    storageFreeSpaceMinBytes,
                    true,
                    failures,
                    snapshotChecksumFailureTotal.get());
        } catch (IOException | RuntimeException e) {
            long failures = scanFailureTotal.incrementAndGet();
            return result.freeze(
                    properties.isEnabled(),
                    root,
                    storageFreeBytes,
                    storageFreeSpaceMinBytes,
                    false,
                    failures,
                    snapshotChecksumFailureTotal.get());
        }
    }

    private long storageFreeBytes(Path root) {
        Path existing = root.toAbsolutePath().normalize();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return 0L;
        }
        try {
            return Math.max(0L, storageSpaceProbe.availableBytes(existing));
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    private static long usableSpace(Path path) {
        return path.toFile().getUsableSpace();
    }

    private void accumulate(Path path, MutableSnapshot result) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class);
            long size = attributes.size();
            long modifiedAt = attributes.lastModifiedTime().toMillis();
            String name = path.getFileName().toString();
            result.totalFileCount++;
            result.totalBytes += size;
            result.latestModifiedAtEpochMs = Math.max(
                    result.latestModifiedAtEpochMs, modifiedAt);
            if (name.startsWith("log_")) {
                result.walFileCount++;
                result.walBytes += size;
                result.latestWalModifiedAtEpochMs = Math.max(
                        result.latestWalModifiedAtEpochMs, modifiedAt);
            } else if (name.startsWith("snapshot.") && !name.endsWith(".md5")) {
                result.snapshotFileCount++;
                result.snapshotBytes += size;
                result.latestSnapshotModifiedAtEpochMs = Math.max(
                        result.latestSnapshotModifiedAtEpochMs, modifiedAt);
                verifySnapshotChecksum(path, size, modifiedAt, result);
            } else {
                result.otherFileCount++;
                result.otherBytes += size;
            }
        } catch (IOException e) {
            result.partial = true;
        }
    }

    private void verifySnapshotChecksum(
            Path snapshot,
            long size,
            long modifiedAt,
            MutableSnapshot result) {
        Path checksum = snapshot.resolveSibling(
                snapshot.getFileName().toString() + ".md5");
        if (!Files.isRegularFile(checksum)) {
            result.snapshotChecksumUnverifiedCount++;
            return;
        }
        try {
            RatisSnapshotChecksum.verify(snapshot);
            result.snapshotChecksumVerifiedCount++;
        } catch (IOException | RuntimeException e) {
            result.snapshotChecksumMismatchCount++;
            String fingerprint = snapshot.toAbsolutePath().normalize()
                    + ":" + size + ":" + modifiedAt;
            if (reportedSnapshotChecksumFailures.add(fingerprint)) {
                snapshotChecksumFailureTotal.incrementAndGet();
            }
        }
    }

    public record Snapshot(
            boolean statePlaneEnabled,
            String storageDirectory,
            long storageFreeBytes,
            long storageFreeSpaceMinBytes,
            boolean scanComplete,
            long scanFailureTotal,
            long totalFileCount,
            long totalBytes,
            long walFileCount,
            long walBytes,
            long snapshotFileCount,
            long snapshotBytes,
            long snapshotChecksumVerifiedCount,
            long snapshotChecksumMismatchCount,
            long snapshotChecksumUnverifiedCount,
            long snapshotChecksumFailureTotal,
            long otherFileCount,
            long otherBytes,
            long latestModifiedAtEpochMs,
            long latestWalModifiedAtEpochMs,
            long latestSnapshotModifiedAtEpochMs) {

        public boolean storageFreeSpaceAboveMinimum() {
            return storageFreeBytes >= storageFreeSpaceMinBytes;
        }

        private static Snapshot empty(
                boolean enabled,
                Path root,
                long storageFreeBytes,
                long storageFreeSpaceMinBytes,
                long scanFailureTotal,
                long snapshotChecksumFailureTotal) {
            return new Snapshot(
                    enabled,
                    root.toString(),
                    storageFreeBytes,
                    storageFreeSpaceMinBytes,
                    true,
                    scanFailureTotal,
                    0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, snapshotChecksumFailureTotal,
                    0L, 0L,
                    0L, 0L, 0L);
        }
    }

    public record Health(
            boolean statePlaneEnabled,
            String storageDirectory,
            boolean accessible,
            long storageFreeBytes,
            long storageFreeSpaceMinBytes) {

        public boolean storageFreeSpaceAboveMinimum() {
            return storageFreeBytes >= storageFreeSpaceMinBytes;
        }
    }

    private static final class MutableSnapshot {
        private boolean partial;
        private long totalFileCount;
        private long totalBytes;
        private long walFileCount;
        private long walBytes;
        private long snapshotFileCount;
        private long snapshotBytes;
        private long snapshotChecksumVerifiedCount;
        private long snapshotChecksumMismatchCount;
        private long snapshotChecksumUnverifiedCount;
        private long otherFileCount;
        private long otherBytes;
        private long latestModifiedAtEpochMs;
        private long latestWalModifiedAtEpochMs;
        private long latestSnapshotModifiedAtEpochMs;

        private Snapshot freeze(
                boolean enabled,
                Path root,
                long storageFreeBytes,
                long storageFreeSpaceMinBytes,
                boolean walkComplete,
                long scanFailureTotal,
                long snapshotChecksumFailureTotal) {
            return new Snapshot(
                    enabled,
                    root.toString(),
                    storageFreeBytes,
                    storageFreeSpaceMinBytes,
                    walkComplete && !partial,
                    scanFailureTotal,
                    totalFileCount,
                    totalBytes,
                    walFileCount,
                    walBytes,
                    snapshotFileCount,
                    snapshotBytes,
                    snapshotChecksumVerifiedCount,
                    snapshotChecksumMismatchCount,
                    snapshotChecksumUnverifiedCount,
                    snapshotChecksumFailureTotal,
                    otherFileCount,
                    otherBytes,
                    latestModifiedAtEpochMs,
                    latestWalModifiedAtEpochMs,
                    latestSnapshotModifiedAtEpochMs);
        }
    }

    @FunctionalInterface
    interface StorageSpaceProbe {
        long availableBytes(Path existingPath) throws IOException;
    }
}
