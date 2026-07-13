package cloud.xuantong.client.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCacheManagerTest {
    private static final String NAMESPACE = "public";
    private static final String GROUP = "DEFAULT_GROUP";

    @TempDir
    Path tempDir;

    @Test
    void persistsLatestSnapshotAndSpecialCharacters() {
        ConfigCacheManager cache = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        cache.batchUpdate(Collections.singletonMap("obsolete", "value"));

        Map<String, String> update = new HashMap<>();
        update.put("app.yml", "server:\n  port: 8080\nmessage=你好");
        update.put("escaped:key", "a=b:c\\d");
        cache.batchUpdate(update);
        cache.remove("obsolete");
        cache.shutdown();

        ConfigCacheManager reloaded = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        try {
            assertEquals(update, reloaded.getAll());
        } finally {
            reloaded.shutdown();
        }
    }

    @Test
    void clearWinsOverQueuedWrites() {
        ConfigCacheManager cache = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        for (int i = 0; i < 200; i++) {
            cache.batchUpdate(Collections.singletonMap("key", "value-" + i));
        }
        cache.clear();
        cache.shutdown();

        Path cacheFile = tempDir.resolve(NAMESPACE).resolve(GROUP).resolve("all.properties");
        assertFalse(Files.exists(cacheFile));

        ConfigCacheManager reloaded = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        try {
            assertTrue(reloaded.getAll().isEmpty());
        } finally {
            reloaded.shutdown();
        }
    }

    @Test
    void emptyReplacementRemovesStaleSnapshot() {
        ConfigCacheManager cache = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        cache.batchUpdate(Collections.singletonMap("stale", "value"));
        cache.batchUpdate(Collections.emptyMap(), true);
        cache.shutdown();

        ConfigCacheManager reloaded = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        try {
            assertTrue(reloaded.getAll().isEmpty());
        } finally {
            reloaded.shutdown();
        }
    }

    @Test
    void loadedEntriesReceiveFreshAccessTimeAndExpirationIsPersisted() {
        ConfigCacheManager seed = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        seed.batchUpdate(Collections.singletonMap("app.yml", "cached"));
        seed.shutdown();

        ConfigCacheManager cache = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        long loadedAt = System.currentTimeMillis();
        cache.cleanupExpiredCache(loadedAt);
        assertEquals("cached", cache.getAll().get("app.yml"));

        cache.cleanupExpiredCache(loadedAt + 24L * 60 * 60 * 1000 + 1);
        assertTrue(cache.getAll().isEmpty());
        cache.shutdown();

        ConfigCacheManager reloaded = new ConfigCacheManager(NAMESPACE, GROUP, tempDir);
        try {
            assertTrue(reloaded.getAll().isEmpty());
        } finally {
            reloaded.shutdown();
        }
    }
}
