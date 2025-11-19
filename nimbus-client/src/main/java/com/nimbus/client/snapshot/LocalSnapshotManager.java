package com.nimbus.client.snapshot;

import com.nimbus.client.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地配置快照管理器
 */
public class LocalSnapshotManager {
    private static final Logger logger = LoggerFactory.getLogger(LocalSnapshotManager.class);

    private final String appName;
    private final String env;
    private final Serializer serializer;
    private final Path snapshotDir;

    public LocalSnapshotManager(String appName, String env) {
        this.appName = appName;
        this.env = env;
        this.serializer = Serializer.defaultSerializer();
        this.snapshotDir = Paths.get(System.getProperty("user.dir"), ".nimbus-cache", appName, env, "snapshots");
        createSnapshotDir();
    }

    private void createSnapshotDir() {
        try {
            Files.createDirectories(snapshotDir);
            logger.info("Snapshot directory created: {}", snapshotDir);
        } catch (IOException e) {
            logger.warn("Failed to create snapshot directory", e);
        }
    }

    public void saveSnapshot(Map<String, String> configs) {
        try {
            Path snapshotFile = getSnapshotPath();
            StringBuilder sb = new StringBuilder();
            configs.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
            Files.write(snapshotFile, sb.toString().getBytes());
            logger.info("Snapshot saved with {} configs", configs.size());
        } catch (IOException e) {
            logger.error("Failed to save snapshot", e);
            throw new RuntimeException("Failed to save snapshot", e);
        }
    }

    /**
     * 增量更新快照，只更新指定的配置项
     * @param changedConfigs 变更的配置项
     */
    public void updateSnapshot(Map<String, String> changedConfigs) {
        try {
            // 加载现有快照
            Map<String, String> currentConfigs = loadSnapshot();

            // 合并变更
            currentConfigs.putAll(changedConfigs);

            // 保存更新后的快照
            saveSnapshot(currentConfigs);
            logger.debug("Snapshot updated with {} changed configs", changedConfigs.size());
        } catch (Exception e) {
            logger.error("Failed to update snapshot", e);
            throw new RuntimeException("Failed to update snapshot", e);
        }
    }

    public Map<String, String> loadSnapshot() {
        try {
            Path snapshotFile = getSnapshotPath();
            if (Files.exists(snapshotFile)) {
                Map<String, String> configs = new ConcurrentHashMap<>();
                Files.lines(snapshotFile)
                     .filter(line -> line.contains("="))
                     .forEach(line -> {
                         int idx = line.indexOf("=");
                         configs.put(line.substring(0, idx), line.substring(idx + 1));
                     });
                logger.info("Loaded snapshot with {} configs", configs.size());
                return configs;
            }
        } catch (IOException e) {
            logger.error("Failed to load snapshot", e);
        }
        return new ConcurrentHashMap<>();
    }

    public boolean hasValidSnapshot() {
        Path snapshotFile = getSnapshotPath();
        return Files.exists(snapshotFile) && Files.isReadable(snapshotFile);
    }

    public void clearSnapshot() {
        try {
            Path snapshotFile = getSnapshotPath();
            if (Files.exists(snapshotFile)) {
                Files.delete(snapshotFile);
                logger.info("Snapshot cleared");
            }
        } catch (IOException e) {
            logger.error("Failed to clear snapshot", e);
        }
    }

    private Path getSnapshotPath() {
        return snapshotDir.resolve("snapshot.properties");
    }
}