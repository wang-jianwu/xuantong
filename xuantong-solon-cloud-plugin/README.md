# Xuantong Solon Cloud Plugin 2.0

同时适配 Solon Cloud Config 与 Discovery：

实现遵循 [Solon Cloud 插件适配文档](https://solon.noear.org/article/1268)，向 `CloudManager` 注册标准的 `CloudConfigService` 与 `CloudDiscoveryService`。

```text
Cloud namespace -> Xuantong namespace
Cloud group     -> Xuantong group
Cloud name      -> Xuantong dataId
Cloud service   -> Xuantong serviceName
Cloud instance  -> Xuantong ServiceInstance
```

## 依赖

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-solon-cloud-plugin</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## 配置

```yaml
solon.app:
  name: order-service
  group: DEFAULT_GROUP

solon.cloud.xuantong:
  server: node1:8088,node2:8088
  namespace: public
  token: ${XUANTONG_ACCESS_TOKEN:}
  config:
    enable: true
    load: db.yml,logging.yml
  discovery:
    enable: true
    healthCheckInterval: 10s
```

空 Group 会映射为 `DEFAULT_GROUP`。不同 Group 使用独立客户端连接和本地缓存，不会混读配置。应用完成启动后，Solon Cloud 会自动把 `solon.app.name` 注册到对应 Namespace 和 Group；首次注册会自动创建 Service 定义。

通过 `config.load` 加载的配置会进入 `Solon.cfg()`，可用 `@Inject` 注入。`@CloudConfig` 可以读取并自动刷新其他 dataId。

```java
@Configuration
public class AppConfig {
    @CloudConfig(value = "app.payment.timeout", autoRefreshed = true)
    private String paymentTimeout;
}
```

服务发现通过 Solon Cloud 标准接口使用，例如 `@NamiClient`。插件实现了 `CloudDiscoveryService` 的注册、健康状态、注销、服务查询、服务目录和变更关注接口。

应用启动时，Solon 会根据 `solon.app.group` 和本地 Signal 自动注册实例；插件负责周期心跳，并在应用正常停止时注销实例。ServiceDefinition 在首次注册时自动创建，实例注销后继续保留。
