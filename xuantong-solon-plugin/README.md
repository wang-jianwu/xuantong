# Xuantong Solon Plugin 2.0

为 Solon IoC 提供 `@ConfigValue` 注入和自动刷新，使用 `namespace + group + dataId` 作为唯一配置寻址模型。

## 依赖

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-solon-plugin</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## 配置

```yaml
xuantong.config:
  serverAddresses:
    - node1:8088
    - node2:8088
  namespace: public
  group: DEFAULT_GROUP
  accessToken: ${XUANTONG_ACCESS_TOKEN:}
  applicationName: ${solon.app.name}
  clientId: ${XUANTONG_CLIENT_ID:}
```

## 使用

```java
@Controller
public class DemoController {
    @ConfigValue(value = "server.port", defaultValue = "8080")
    private int serverPort;

    @ConfigValue(value = "app.name", autoRefresh = true)
    private String appName;

    @ConfigValue("database.config")
    private DatabaseConfig databaseConfig;

    @ConfigValue("server.hosts")
    private List<String> serverHosts;
}
```

注解的 `value` 对应 `dataId`。字段类型会自动推断，无需声明额外类型参数。
