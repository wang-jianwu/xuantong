package cloud.xuantong.core;

import cloud.xuantong.core.cluster.ClusterConfig;
import cloud.xuantong.core.cluster.ClusterSyncPlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSyncPlayerTest {

    @Test
    void clusterConnectionIncludesEncodedBrokerToken() throws Exception {
        ClusterConfig config = new ClusterConfig();
        setField(config, "brokerSecretKey", "secret with + symbols");

        ClusterSyncPlayer player = new ClusterSyncPlayer();
        setField(player, "clusterConfig", config);

        String url = buildConnectionUrl(player, "sd:ws://127.0.0.1:8088/config");

        assertTrue(url.contains("@=config-node-"));
        assertTrue(url.contains("token=secret+with+%2B+symbols"));
    }

    @Test
    void clusterConnectionOmitsTokenWhenAuthenticationDisabled() throws Exception {
        ClusterConfig config = new ClusterConfig();
        setField(config, "brokerSecretKey", "");

        ClusterSyncPlayer player = new ClusterSyncPlayer();
        setField(player, "clusterConfig", config);

        String url = buildConnectionUrl(player, "sd:ws://127.0.0.1:8088/config");

        assertFalse(url.contains("token="));
    }

    private String buildConnectionUrl(ClusterSyncPlayer player, String address) throws Exception {
        Method method = ClusterSyncPlayer.class.getDeclaredMethod("buildConnectionUrl", String.class);
        method.setAccessible(true);
        return (String) method.invoke(player, address);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
