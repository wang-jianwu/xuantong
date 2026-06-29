# 玄同(Xuantong) Config Spring Boot Starter

## 功能特性

- 动态配置管理：从玄同配置中心获取配置
- **@ConfigValue注解**：功能强大的单一注解，支持所有配置场景
- 类型支持：
  - 基本类型：String, Integer, Long, Boolean, Double, Float
  - 复杂对象：自动JSON反序列化
  - 列表/数组：支持泛型和数组类型
- 自动刷新：实时配置更新支持
- 必填校验：关键配置缺失保护

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 配置参数

在`application.yml`中添加配置：

```yaml
xuantong:
  config:
    # Broker 地址（支持多地址，自动 failover）
    server-addresses: ["node1:8088/xuantong-admin", "node2:8088/xuantong-admin"]
    app-name: ["your-application-name"]       # 应用名称
    environment: "prod"                      # 环境标识
```

### 3. 使用示例

#### 基本类型

```java
@Component
public class MyService {
    @ConfigValue(value = "server.port", type = ValueType.INTEGER, defaultValue = "8080")
    private int serverPort;

    @ConfigValue(value = "feature.enabled", type = ValueType.BOOLEAN, defaultValue = "true")
    private boolean featureEnabled;
}
```

#### 复杂对象

```java
@Component
public class PaymentService {
    @ConfigValue(value = "payment.config", type = ValueType.JSON)
    private PaymentConfig paymentConfig;
}
```

#### 列表/数组

```java
@Component
public class DatabaseService {
    @ConfigValue(value = "database.servers", type = ValueType.LIST)
    private List<DatabaseServer> servers;

    @ConfigValue(value = "database.replicas", type = ValueType.JSON)
    private DatabaseReplica[] replicas;
}
```

## 最佳实践

1**配置示例**：
   ```yaml
   xuantong:
     config:
       # Broker 地址（单地址即可，集群内部自动同步）
       server-addresses: ["config-center:8088/xuantong-admin"]
       app-name: ["your-app-name", "another-app"]
       environment: "dev"
   ```

2**配置变更监听**：
   ```java
   // 自动刷新配置，无需手动监听
   @Component
   public class ApplicationConfig {
       @ConfigValue(value = "app.feature.rate-limit", type = ValueType.INTEGER, autoRefresh = true)
       private int rateLimit;
   }
   ```

## 注意事项

1. 建议为关键配置提供合理的默认值
2. 复杂对象需要有无参构造函数
3. 必填配置缺失时会抛出运行时异常，请确保生产环境配置完整
4. 配置Bean的依赖关系需要确保配置客户端先初始化

## 版本兼容性

- Spring Boot 3.x
- Java 17+