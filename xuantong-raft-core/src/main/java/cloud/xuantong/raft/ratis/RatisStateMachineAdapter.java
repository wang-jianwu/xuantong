package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadConsistency;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateMachine;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.MD5FileUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Bridges the transport-neutral state-machine contract to Apache Ratis. */
public final class RatisStateMachineAdapter extends BaseStateMachine {
    private static final int SNAPSHOT_MAGIC = 0x58545353;
    private static final int SNAPSHOT_FORMAT_VERSION = 1;

    private final StateMachine delegate;
    private final String implementationVersion;
    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();
    private Path stateMachineDirectory;

    public RatisStateMachineAdapter(StateMachine delegate) {
        this(delegate, detectedImplementationVersion());
    }

    public RatisStateMachineAdapter(
            StateMachine delegate, String implementationVersion) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (implementationVersion == null || implementationVersion.isBlank()) {
            throw new IllegalArgumentException("implementationVersion must not be blank");
        }
        this.delegate = delegate;
        this.implementationVersion = implementationVersion.trim();
    }

    @Override
    public void initialize(
            RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        storage.init(raftStorage);
        stateMachineDirectory = raftStorage.getStorageDir().getStateMachineDir().toPath();
        reinitialize();
    }

    @Override
    public SimpleStateMachineStorage getStateMachineStorage() {
        return storage;
    }

    @Override
    public synchronized void reinitialize() throws IOException {
        Path snapshotPath = findLatestSnapshot();
        if (snapshotPath == null) {
            return;
        }
        RatisSnapshotChecksum.verify(snapshotPath);
        TermIndex termIndex = SimpleStateMachineStorage.getTermIndexFromSnapshotFile(
                snapshotPath.toFile());
        MD5Hash md5 = MD5FileUtil.readStoredMd5ForFile(snapshotPath.toFile());
        storage.updateLatestSnapshot(new SingleFileSnapshotInfo(
                new FileInfo(snapshotPath, md5), termIndex));
        try (DataInputStream input = new DataInputStream(
                Files.newInputStream(snapshotPath))) {
            readAndInstallSnapshot(input);
        }
        setLastAppliedTermIndex(termIndex);
    }

    private Path findLatestSnapshot() throws IOException {
        Path directory = stateMachineDirectory;
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> SimpleStateMachineStorage.SNAPSHOT_REGEX
                            .matcher(path.getFileName().toString()).matches())
                    .max(Comparator
                            .comparingLong((Path path) -> snapshotTermIndex(path).getIndex())
                            .thenComparingLong(path -> snapshotTermIndex(path).getTerm()))
                    .orElse(null);
        } catch (SnapshotFileNameException e) {
            throw e.ioException;
        }
    }

    private TermIndex snapshotTermIndex(Path path) {
        try {
            return SimpleStateMachineStorage.getTermIndexFromSnapshotFile(path.toFile());
        } catch (RuntimeException e) {
            throw new SnapshotFileNameException(new IOException(
                    "Invalid Snapshot file name: " + path, e));
        }
    }

    private static final class SnapshotFileNameException extends RuntimeException {
        private final IOException ioException;

        private SnapshotFileNameException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }

    @Override
    public synchronized long takeSnapshot() throws IOException {
        TermIndex applied = getLastAppliedTermIndex();
        if (applied == null || applied.getIndex() < 1) {
            return org.apache.ratis.server.raftlog.RaftLog.INVALID_LOG_INDEX;
        }
        Path target = storage.getSnapshotFile(
                applied.getTerm(), applied.getIndex()).toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(
                    Files.newOutputStream(temporary))) {
                writeSnapshot(output);
            }
            moveAtomically(temporary, target);
            MD5Hash md5 = MD5FileUtil.computeAndSaveMd5ForFile(target.toFile());
            storage.updateLatestSnapshot(new SingleFileSnapshotInfo(
                    new FileInfo(target, md5), applied));
            return applied.getIndex();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public TransactionContext startTransaction(RaftClientRequest request) throws IOException {
        TransactionContext transaction = super.startTransaction(request);
        try {
            StateCommand command = RatisStateMessageCodec.decodeCommand(
                    request.getMessage().getContent().toByteArray());
            requireGroup(command.groupId());
        } catch (Exception e) {
            transaction.setException(e);
        }
        return transaction;
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext transaction) {
        try {
            LogEntryProto entry = transaction.getLogEntry();
            TermIndex termIndex = TermIndex.valueOf(entry);
            StateCommand command = RatisStateMessageCodec.decodeCommand(
                    entry.getStateMachineLogEntry().getLogData().toByteArray());
            requireGroup(command.groupId());
            ApplyResult result = delegate.apply(
                    command,
                    new ApplyContext(delegate.groupId(),
                            termIndex.getTerm(), termIndex.getIndex()));
            requireApplyResult(command, termIndex, result);
            byte[] encoded = RatisStateMessageCodec.encodeApplyResult(result);
            updateLastAppliedTermIndex(termIndex);
            return CompletableFuture.completedFuture(message(encoded));
        } catch (Exception e) {
            return JavaUtils.completeExceptionally(e);
        }
    }

    @Override
    public CompletableFuture<Message> query(Message request) {
        try {
            byte[] payload = request.getContent().toByteArray();
            int messageType = RatisStateMessageCodec.messageType(payload);
            if (messageType == RatisStateMessageCodec.QUERY) {
                StateQuery query = RatisStateMessageCodec.decodeQuery(payload);
                requireGroup(query.groupId());
                QueryResult result = delegate.query(query);
                requireQueryResult(query, result);
                requireGroup(result.groupId());
                return CompletableFuture.completedFuture(message(
                        RatisStateMessageCodec.encodeQueryResult(result)));
            }
            if (messageType == RatisStateMessageCodec.WATCH_REQUEST) {
                WatchRequest watchRequest = RatisStateMessageCodec.decodeWatchRequest(payload);
                requireGroup(watchRequest.groupId());
                WatchBatch batch = delegate.watch(watchRequest);
                requireWatchResult(watchRequest, batch);
                requireGroup(batch.groupId());
                return CompletableFuture.completedFuture(message(
                        RatisStateMessageCodec.encodeWatchBatch(batch)));
            }
            if (messageType == RatisStateMessageCodec.CAPABILITY_REQUEST) {
                cloud.xuantong.state.api.StateGroupId requestedGroup =
                        RatisStateMessageCodec.decodeCapabilityRequest(payload);
                requireGroup(requestedGroup);
                return CompletableFuture.completedFuture(message(
                        RatisStateMessageCodec.encodeCapabilityResponse(
                                delegate.groupId(), implementationVersion,
                                delegate.compatibility())));
            }
            throw new IOException("Unsupported state read message type: " + messageType);
        } catch (Exception e) {
            return JavaUtils.completeExceptionally(e);
        }
    }

    private void requireApplyResult(
            StateCommand command, TermIndex termIndex, ApplyResult result) {
        if (result == null) {
            throw new IllegalStateException("State machine returned no apply result");
        }
        requireGroup(result.groupId());
        if (!command.operationId().equals(result.operationId())) {
            throw new IllegalStateException(
                    "Apply result operationId does not match committed command");
        }
        if (termIndex.getIndex() != result.appliedIndex()) {
            throw new IllegalStateException(
                    "Apply result index does not match committed log index");
        }
    }

    private void requireQueryResult(StateQuery query, QueryResult result) {
        if (result == null) {
            throw new IllegalStateException("State machine returned no query result");
        }
        if (query.readOptions().consistency() == ReadConsistency.LINEARIZABLE
                && result.stale()) {
            throw new IllegalStateException(
                    "Linearizable query returned a stale state-machine result");
        }
        requireMinimumRevision(query.readOptions(), result.revisions());
    }

    private void requireWatchResult(WatchRequest request, WatchBatch batch) {
        if (batch == null) {
            throw new IllegalStateException("State machine returned no Watch batch");
        }
        if (!request.afterRevision().equals(batch.requestedAfter())) {
            throw new IllegalStateException(
                    "Watch batch requestedAfter does not match the request cursor");
        }
        StateRevision minimum = request.readOptions().minimumRevision();
        if (minimum != null && batch.coveredThrough().compareTo(minimum) < 0) {
            throw new IllegalStateException(
                    "Watch batch is behind the requested minimum revision");
        }
    }

    private static void requireMinimumRevision(
            ReadOptions options, List<StateRevision> revisions) {
        StateRevision minimum = options.minimumRevision();
        if (minimum == null) {
            return;
        }
        StateRevision observed = revisions.stream()
                .filter(minimum::sameCoordinate)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "State-machine result omitted the requested revision coordinate"));
        if (observed.compareTo(minimum) < 0) {
            throw new IllegalStateException(
                    "State-machine result is behind the requested minimum revision");
        }
    }

    private void requireGroup(cloud.xuantong.state.api.StateGroupId groupId) {
        if (!delegate.groupId().equals(groupId)) {
            throw new IllegalArgumentException("State message targets " + groupId
                    + " but state machine hosts " + delegate.groupId());
        }
    }

    private static Message message(byte[] payload) {
        return Message.valueOf(ByteString.copyFrom(payload));
    }

    private void writeSnapshot(DataOutputStream output) throws IOException {
        int schemaVersion = delegate.snapshotSchemaVersion();
        if (schemaVersion < 1) {
            throw new IOException("snapshotSchemaVersion must be positive");
        }
        output.writeInt(SNAPSHOT_MAGIC);
        output.writeInt(SNAPSHOT_FORMAT_VERSION);
        writeString(output, delegate.groupId().type().name());
        writeString(output, delegate.groupId().value());
        output.writeInt(schemaVersion);
        delegate.writeSnapshot(output);
    }

    private void readAndInstallSnapshot(DataInputStream input) throws IOException {
        if (input.readInt() != SNAPSHOT_MAGIC) {
            throw new IOException("Invalid State API snapshot magic");
        }
        int formatVersion = input.readInt();
        if (formatVersion != SNAPSHOT_FORMAT_VERSION) {
            throw new IOException(
                    "Unsupported State API snapshot format: " + formatVersion);
        }
        String groupType = readString(input);
        String groupValue = readString(input);
        if (!delegate.groupId().type().name().equals(groupType)
                || !delegate.groupId().value().equals(groupValue)) {
            throw new IOException("Snapshot belongs to another State Group: "
                    + groupType + ":" + groupValue);
        }
        int schemaVersion = input.readInt();
        if (schemaVersion < 1) {
            throw new IOException("Invalid snapshot schema version: " + schemaVersion);
        }
        delegate.installSnapshot(schemaVersion, input);
        if (input.read() != -1) {
            throw new IOException("State machine did not consume the complete snapshot");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 1024) {
            throw new IOException("Invalid snapshot string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected end of snapshot string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String detectedImplementationVersion() {
        String version = RatisStateMachineAdapter.class.getPackage()
                .getImplementationVersion();
        return version == null || version.isBlank() ? "2.0.0" : version;
    }
}
