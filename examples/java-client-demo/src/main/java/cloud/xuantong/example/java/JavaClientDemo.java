package cloud.xuantong.example.java;

import cloud.xuantong.client.XuantongConfigClient;

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

        try (XuantongConfigClient client = new XuantongConfigClient(
                serverAddresses,
                System.getenv().getOrDefault("XUANTONG_NAMESPACE", "public"),
                System.getenv().getOrDefault("XUANTONG_GROUP", "DEFAULT_GROUP"),
                System.getenv().getOrDefault("XUANTONG_ACCESS_TOKEN", ""),
                "java-client-demo")) {
            System.out.println(dataId + "=" + client.get(dataId, "hello-xuantong"));

            client.addListener(dataId, event ->
                    System.out.println("revision=" + event.getRevision()
                            + ", value=" + event.getNewValue()));

            System.out.println("Watching configuration changes. Press Ctrl+C to exit.");
            new CountDownLatch(1).await();
        }
    }
}
