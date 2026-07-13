package cloud.xuantong.client.core;

import cloud.xuantong.client.cache.ConfigCacheManager;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.ConfigTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCoreTest {
    @TempDir
    Path tempDir;

    @Test
    void refreshesFileCacheImmediatelyAtStartup() {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            ConfigCacheManager seed = new ConfigCacheManager("public", "DEFAULT_GROUP");
            seed.batchUpdate(Collections.singletonMap("app.yml", "stale"));
            seed.shutdown();

            FakeConfigTransport transport = new FakeConfigTransport();
            ConfigCore core = new ConfigCore(
                    Collections.singletonList("broker-a:8088"),
                    "public", "DEFAULT_GROUP", "", transport);
            try {
                assertTrue(transport.reconnectListenerInstalledBeforeConnect);
                assertEquals(1, transport.fetchCount.get());
                assertEquals("fresh", core.get("app.yml", null));
            } finally {
                core.close();
            }
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    private static class FakeConfigTransport implements ConfigTransport {
        private final AtomicInteger fetchCount = new AtomicInteger();
        private Runnable reconnectListener;
        private boolean reconnectListenerInstalledBeforeConnect;

        @Override
        public void connect(List<String> serverAddresses, String namespace, String group,
                            String accessToken, ConfigChangeListener listener) {
            reconnectListenerInstalledBeforeConnect = reconnectListener != null;
        }

        @Override
        public ConfigSnapshot fetch(String dataId) {
            fetchCount.incrementAndGet();
            return new ConfigSnapshot(dataId, "fresh", 2L, "sum", "text");
        }

        @Override
        public void setOnReconnect(Runnable listener) {
            reconnectListener = listener;
        }

        @Override
        public void close() {
        }
    }
}
