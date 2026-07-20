package cloud.xuantong.server.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRecoveryScriptTest {
    @TempDir
    Path tempDirectory;

    @Test
    void h2DumpPublishesCompleteFileAndRefusesUnsafeTargets() throws Exception {
        Path source = tempDirectory.resolve("source.mv.db");
        Path output = tempDirectory.resolve("backup.mv.db");
        byte[] content = "xuantong-h2-backup".getBytes(StandardCharsets.UTF_8);
        Files.write(source, content);

        CommandResult result = run(List.of(
                dumpScript().toString(),
                "--dialect", "h2",
                "--h2-file", source.toString(),
                "--output", output.toString(),
                "--offline-confirmed"), Map.of());
        assertTrue(result.succeeded(), result.output());
        assertArrayEquals(content, Files.readAllBytes(output));

        CommandResult duplicate = run(List.of(
                dumpScript().toString(),
                "--dialect", "h2",
                "--h2-file", source.toString(),
                "--output", output.toString(),
                "--offline-confirmed"), Map.of());
        assertNotEquals(0, duplicate.exitCode());
        assertArrayEquals(content, Files.readAllBytes(output));

        Path dangling = tempDirectory.resolve("dangling.mv.db");
        Files.createSymbolicLink(dangling, tempDirectory.resolve("missing.mv.db"));
        CommandResult symbolicLink = run(List.of(
                dumpScript().toString(),
                "--dialect", "h2",
                "--h2-file", source.toString(),
                "--output", dangling.toString(),
                "--offline-confirmed"), Map.of());
        assertNotEquals(0, symbolicLink.exitCode());
        assertTrue(Files.isSymbolicLink(dangling));
        assertFalse(Files.exists(dangling));
    }

    @Test
    void failedExternalDumpLeavesNoPublishedOrStagedBackup() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews()
                        .contains("posix"),
                "POSIX file permissions are required for this script test");
        Path fakeBin = Files.createDirectories(tempDirectory.resolve("bin"));
        Path fakeDump = fakeBin.resolve("mysqldump");
        Files.writeString(fakeDump, "#!/bin/sh\nexit 41\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fakeDump, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Path output = tempDirectory.resolve("failed.sql");
        String path = fakeBin + System.getProperty("path.separator")
                + System.getenv().getOrDefault("PATH", "");

        CommandResult result = run(List.of(
                dumpScript().toString(),
                "--dialect", "mysql",
                "--host", "127.0.0.1",
                "--port", "3306",
                "--database", "xuantong",
                "--user", "xuantong",
                "--output", output.toString()), Map.of(
                "PATH", path,
                "XUANTONG_DB_PASSWORD", "test-password"));

        assertNotEquals(0, result.exitCode());
        assertFalse(Files.exists(output));
        assertFalse(Files.isSymbolicLink(output));
        try (var paths = Files.list(tempDirectory)) {
            assertTrue(paths.noneMatch(pathValue -> pathValue.getFileName().toString()
                    .startsWith(".failed.sql.partial.")));
        }
    }

    @Test
    void timeoutTerminationRemovesShellAndDescendantProcess() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews()
                        .contains("posix"),
                "POSIX signals are required for this script test");
        Path childPidFile = tempDirectory.resolve("child.pid");
        Path fakeBin = Files.createDirectories(tempDirectory.resolve("timeout-bin"));
        Path fakeDump = fakeBin.resolve("mysqldump");
        Files.writeString(fakeDump, """
                #!/bin/sh
                echo $$ > "$CHILD_PID_FILE"
                exec sleep 300
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fakeDump, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Path output = tempDirectory.resolve("timeout.sql");
        ProcessBuilder builder = new ProcessBuilder(
                dumpScript().toString(),
                "--dialect", "mysql",
                "--host", "127.0.0.1",
                "--port", "3306",
                "--database", "xuantong",
                "--user", "xuantong",
                "--output", output.toString())
                .redirectErrorStream(true);
        builder.environment().put("CHILD_PID_FILE", childPidFile.toString());
        builder.environment().put("XUANTONG_DB_PASSWORD", "test-password");
        builder.environment().put("PATH", fakeBin
                + System.getProperty("path.separator")
                + System.getenv().getOrDefault("PATH", ""));
        Process parent = builder.start();
        ProcessHandle child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.isRegularFile(childPidFile)
                    && System.nanoTime() < deadline) {
                Thread.sleep(25L);
            }
            assertTrue(Files.isRegularFile(childPidFile));
            long childPid = Long.parseLong(Files.readString(childPidFile).trim());
            child = ProcessHandle.of(childPid).orElseThrow();
            assertTrue(parent.isAlive());
            assertTrue(child.isAlive());

            ExternalDatabaseBackupRestoreDrillTest.terminateProcessTree(parent);

            assertFalse(parent.isAlive());
            assertFalse(child.isAlive());
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
            if (parent.isAlive()) {
                parent.destroyForcibly();
            }
        }
    }

    private CommandResult run(List<String> command, Map<String, String> environment)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command))
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Command timed out: " + command);
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    private Path dumpScript() {
        return repositoryRoot().resolve("scripts/dump-xuantong-database.sh");
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path script = current.resolve("scripts/dump-xuantong-database.sh");
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isExecutable(script)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Xuantong repository root was not found");
    }

    private record CommandResult(int exitCode, String output) {
        private boolean succeeded() {
            return exitCode == 0;
        }
    }
}
