# Xuantong Spring Boot Starter 2.0

Spring Boot 3.x 集成，使用 `namespace + group + dataId` 读取和监听已经发布的配置。

## 依赖

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-spring-boot-starter</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## 配置

```yaml
xuantong:
  config:
    server-addresses: ["node1:8088", "node2:8088"]
    namespace: public
    group: DEFAULT_GROUP
    access-token: ${XUANTONG_ACCESS_TOKEN:}
    application-name: ${spring.application.name}
    client-id: ${XUANTONG_CLIENT_ID:}
```

`namespace` 默认是 `public`，`group` 默认是 `DEFAULT_GROUP`。生产环境建议通过环境变量提供 Token。

## 使用

```java
@Component
public class ApplicationConfig {
    @ConfigValue(value = "server.port", defaultValue = "8080")
    private int serverPort;

    @ConfigValue(value = "feature.enabled", defaultValue = "true", autoRefresh = true)
    private boolean featureEnabled;

    @ConfigValue("payment.config")
    private PaymentConfig paymentConfig;

    @ConfigValue("database.servers")
    private List<DatabaseServer> servers;
}
```

字段类型会自动推断。支持字符串、数字、布尔值、JSON 对象、List 和 Map。配置删除时，非必填字段回退到默认值；必填字段保留旧值并记录警告。

## 运行要求

- Spring Boot 3.x
- Java 17+
- Xuantong Server 2.0

## 2.0 依赖排查

如果 Server 持续提示 `Rejecting Broker session without 2.0 client identity`，请检查运行 classpath。旧坐标 `xuantong-config-spring-boot-starter` 和 `xuantong-client` 不包含 2.0 客户端身份握手，必须替换为 `xuantong-spring-boot-starter` 和它传递依赖的 `xuantong-client-core`。

```bash
mvn dependency:tree -Dincludes=cloud.xuantong
```

修改 POM 后需要在 IntelliJ IDEA 中重新加载全部 Maven 项目，并彻底停止旧 Java 进程后再启动。
