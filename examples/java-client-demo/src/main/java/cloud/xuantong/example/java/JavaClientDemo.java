package cloud.xuantong.example.java;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.TlsOptions;
import cloud.xuantong.client.XuantongConfigClient;
import cloud.xuantong.client.listener.ListenerRegistration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class JavaClientDemo {

    private JavaClientDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        List<String> serverAddresses = Arrays.stream(
                        System.getenv().getOrDefault(
                                "XUANTONG_SERVER_ADDRESSES", "127.0.0.1:8090")
                                .split(","))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .toList();

        String dataId = System.getenv().getOrDefault(
                "XUANTONG_DATA_ID", "demo.message");
        boolean tlsEnabled = Boolean.parseBoolean(System.getenv().getOrDefault(
                "XUANTONG_CLIENT_TLS_ENABLED", "false"));
        TlsOptions tls = new TlsOptions(
                tlsEnabled,
                System.getenv().getOrDefault("XUANTONG_CLIENT_TLS_TRUST_STORE", ""),
                System.getenv().getOrDefault("XUANTONG_CLIENT_TLS_TRUST_STORE_TYPE", "PKCS12"),
                System.getenv().getOrDefault("XUANTONG_CLIENT_TLS_TRUST_STORE_PASSWORD", ""),
                System.getenv().getOrDefault("XUANTONG_CLIENT_TLS_KEY_STORE", ""),
                System.getenv().getOrDefault("XUANTONG_CLIENT_TLS_KEY_STORE_TYPE", "PKCS12"),
                System.getenv().getOrDefault("XUANTONG_CLIENT_TLS_KEY_STORE_PASSWORD", ""),
                System.getenv().getOrDefault("XUANTONG_CLIENT_TLS_KEY_PASSWORD", ""),
                true,
                Long.parseLong(System.getenv().getOrDefault(
                        "XUANTONG_CLIENT_TLS_RELOAD_INTERVAL_MS", "30000")));
        ControlPlaneOptions controlPlane = ControlPlaneOptions.defaults().withTls(tls);

        try (XuantongConfigClient client = new XuantongConfigClient(
                serverAddresses,
                System.getenv().getOrDefault("XUANTONG_NAMESPACE", "public"),
                System.getenv().getOrDefault("XUANTONG_GROUP", "DEFAULT_GROUP"),
                System.getenv().getOrDefault("XUANTONG_ACCESS_TOKEN", ""),
                new ClientIdentity("java-client-demo", null),
                controlPlane);
             ListenerRegistration registration = client.listen(dataId, event ->
                     System.out.println("revision=" + event.getRevision()
                             + ", value=" + event.getNewValue()))) {
            System.out.println(dataId + "=" + client.get(dataId, "hello-xuantong"));

            System.out.println("Watching configuration changes. Press Ctrl+C to exit.");
            new CountDownLatch(1).await();
        }
    }
}
