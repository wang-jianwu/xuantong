package cloud.xuantong.state.api;

/**
 * Version range that one State Machine implementation can safely execute and restore.
 *
 * <p>The command range covers commands, queries and Watch selectors for the Group.
 * Snapshot read compatibility is intentionally separate from the snapshot version emitted
 * by this binary so a rolling upgrade can keep writing the old format until every voter can
 * read the new format.</p>
 */
public record StateMachineCompatibility(
        int minimumCommandSchemaVersion,
        int maximumCommandSchemaVersion,
        int minimumReadableSnapshotSchemaVersion,
        int maximumReadableSnapshotSchemaVersion,
        int writableSnapshotSchemaVersion) {

    public StateMachineCompatibility {
        positive("minimumCommandSchemaVersion", minimumCommandSchemaVersion);
        positive("maximumCommandSchemaVersion", maximumCommandSchemaVersion);
        positive("minimumReadableSnapshotSchemaVersion",
                minimumReadableSnapshotSchemaVersion);
        positive("maximumReadableSnapshotSchemaVersion",
                maximumReadableSnapshotSchemaVersion);
        positive("writableSnapshotSchemaVersion", writableSnapshotSchemaVersion);
        if (maximumCommandSchemaVersion < minimumCommandSchemaVersion) {
            throw new IllegalArgumentException(
                    "maximumCommandSchemaVersion must not be below the minimum");
        }
        if (maximumReadableSnapshotSchemaVersion
                < minimumReadableSnapshotSchemaVersion) {
            throw new IllegalArgumentException(
                    "maximumReadableSnapshotSchemaVersion must not be below the minimum");
        }
        if (writableSnapshotSchemaVersion < minimumReadableSnapshotSchemaVersion
                || writableSnapshotSchemaVersion
                > maximumReadableSnapshotSchemaVersion) {
            throw new IllegalArgumentException(
                    "writableSnapshotSchemaVersion must also be readable");
        }
    }

    public static StateMachineCompatibility exact(
            int commandSchemaVersion, int snapshotSchemaVersion) {
        return new StateMachineCompatibility(
                commandSchemaVersion,
                commandSchemaVersion,
                snapshotSchemaVersion,
                snapshotSchemaVersion,
                snapshotSchemaVersion);
    }

    public boolean supportsCommand(int schemaVersion) {
        return schemaVersion >= minimumCommandSchemaVersion
                && schemaVersion <= maximumCommandSchemaVersion;
    }

    public boolean canReadSnapshot(int schemaVersion) {
        return schemaVersion >= minimumReadableSnapshotSchemaVersion
                && schemaVersion <= maximumReadableSnapshotSchemaVersion;
    }

    private static void positive(String field, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
