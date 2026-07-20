package cloud.xuantong.raft.ratis;

import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.util.MD5FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies Snapshot bytes against the checksum stored in the adjacent Ratis MD5 file. */
public final class RatisSnapshotChecksum {
    private RatisSnapshotChecksum() {
    }

    public static void verify(Path snapshot) throws IOException {
        if (snapshot == null || !Files.isRegularFile(snapshot)) {
            throw new IOException("Snapshot file does not exist: " + snapshot);
        }
        MD5Hash stored;
        try {
            stored = MD5FileUtil.readStoredMd5ForFile(snapshot.toFile());
        } catch (IOException | RuntimeException e) {
            throw new IOException(
                    "Snapshot checksum file is invalid: " + snapshot + ".md5", e);
        }
        if (stored == null) {
            throw new IOException("Snapshot checksum file does not exist: " + snapshot + ".md5");
        }
        MD5Hash computed = MD5FileUtil.computeMd5ForFile(snapshot.toFile());
        if (!stored.equals(computed)) {
            throw new IOException(
                    "Snapshot checksum mismatch: file=" + snapshot
                            + ", stored=" + stored + ", computed=" + computed);
        }
    }
}
