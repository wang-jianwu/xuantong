package cloud.xuantong.client.transport;

import cloud.xuantong.client.model.ConfigSnapshot;

import java.util.List;

public interface ConfigTransport {
    interface ConfigChangeListener {
        void onChanged(String eventJson);
    }

    void connect(List<String> serverAddresses,
                 String namespace,
                 String group,
                 String accessToken,
                 ConfigChangeListener listener);

    ConfigSnapshot fetch(String dataId);

    void close();

    default void setOnReconnect(Runnable listener) {
    }
}
