package cloud.xuantong.client;

import cloud.xuantong.client.core.ConfigCore;
import cloud.xuantong.client.model.ConfigGroupSnapshot;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ConfigWatchBatch;
import cloud.xuantong.client.transport.ConfigTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XuantongConfigOwnershipTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetFacade() {
        XuantongConfig.close();
    }

    @Test
    void constructingClientDoesNotMutateGlobalFacade() {
        try (XuantongConfigClient client = new XuantongConfigClient(new ConfigCore(
                List.of("gateway-a:8090"),
                "public",
                "DEFAULT_GROUP",
                "",
                new EmptyTransport(),
                tempDir))) {
            assertFalse(XuantongConfig.isInitialized());

            XuantongConfig.setDefault(client);
            assertTrue(XuantongConfig.isInitialized());
            assertSame(client, XuantongConfig.getDefault());

            XuantongConfig.clearDefault(client);
            assertFalse(XuantongConfig.isInitialized());
        }
    }

    private static final class EmptyTransport implements ConfigTransport {
        @Override
        public void connect(
                List<String> serverAddresses,
                String namespace,
                String group,
                String accessToken) {
        }

        @Override
        public ConfigSnapshot fetch(String dataId, long minDecisionRevision) {
            return null;
        }

        @Override
        public ConfigGroupSnapshot snapshot(
                Collection<String> dataIds, long minEventRevision) {
            return new ConfigGroupSnapshot(0L, 0L, Map.of());
        }

        @Override
        public ConfigWatchBatch watchBatch(
                Collection<String> dataIds,
                long afterEventRevision,
                int maxBatchSize) {
            return new ConfigWatchBatch(
                    afterEventRevision, afterEventRevision, 0L, false, List.of());
        }

        @Override
        public void close() {
        }
    }
}
