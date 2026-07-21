package cloud.xuantong.client;

import java.nio.file.Path;

/** 配置客户端的本地运行选项。 */
public record ConfigClientOptions(Path cacheRoot) {

    public ConfigClientOptions {
        if (cacheRoot != null) {
            cacheRoot = cacheRoot.toAbsolutePath().normalize();
        }
    }

    public static ConfigClientOptions defaults() {
        return new ConfigClientOptions(null);
    }
}
