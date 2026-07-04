# xuantong-config-solon-plugin



```xml

<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-solon-plugin</artifactId>
    <version>1.3.2</version>
</dependency>
```


### 1、描述

分布式配置扩展插件。基于 Xuantong Config 适配的 Solon 插件。提供配置服务支持。

### 2、能力支持

| 能力注解         | 说明 | 备注                                                   |
|--------------| -------- |------------------------------------------------------|
| @ConfigValue | 配置注入            | 支持 autoRefresh 支持基本类型、JSON对象、列表类型 |

### 3、配置示例

```yml
# app.yml
xuantong:
  config:
    # Broker 地址（支持多地址，自动 failover）
    serverAddresses:
      - node1:8088
      - node2:8088
    appNames:
      - your-app-name
    environment: dev
```  

### 4、代码应用

```java
public class DemoApp {
    public static void main(String[] args) {
        SolonApp app = Solon.start(DemoApp.class, args);
    }
}

@Slf4j
@Controller
public class DemoController{
    // 基础类型注入
    @ConfigValue(value = "server.port", defaultValue = "8080")
    private int serverPort;

    // 配置注入（带自动刷新）
    @ConfigValue(value = "app.name", autoRefresh = true)
    private String appName;

    // 配置注入（JSON对象）
    @ConfigValue(value = "database.config", type = ValueType.JSON)
    private DatabaseConfig databaseConfig;
    
    // 配置注入（列表类型）
    @ConfigValue(value = "server.hosts", type = ValueType.LIST)
    private List<String> serverHosts;
   
    @Mapping("/")
    public void test(){
        log.info("serverPort: {}", serverPort);
        log.info("appName: {}", appName);
        log.info("databaseConfig: {}", databaseConfig);
    }
}
```


### 5、演示项目

* [https://gitee.com/wjw_system/xuantong-config/tree/master/solon-cloud-demo](https://gitee.com/wjw_system/xuantong-config/tree/master/solon-cloud-demo)
