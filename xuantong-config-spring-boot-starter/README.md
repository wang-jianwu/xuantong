# Nimbus Config Spring Boot Starter

## 功能特性

- 动态配置管理：从Nimbus配置中心获取配置
- `@Value`注解支持：自动刷新配置值
- 类型支持：
  - 基本类型：String, Integer, Long, Boolean, Double, Float
  - 复杂对象：自动JSON反序列化
  - 列表/数组：支持泛型和数组类型

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.nimbus</groupId>
    <artifactId>nimbus-config-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 配置参数

在`application.yml`中添加配置：

```yaml
nimbus:
  config:
    server-addresses: ["config-server:8080"] # 配置中心地址
    app-name: "your-application-name"        # 应用名称
    environment: "prod"                      # 环境标识
```

### 3. 使用示例

#### 基本类型

```java
@Component
public class MyService {
    // 有默认值
    @Value("${server.port:8080}")
    private int serverPort;

    // 无默认值
    @Value("${feature.enabled}")
    private boolean featureEnabled;
}
```

#### 复杂对象

```java
@Component
public class PaymentService {
    @Value("${payment.config}")
    private PaymentConfig paymentConfig;
}
```

#### 列表/数组

```java
@Component
public class DatabaseService {
    @Value("${database.servers}")
    private List<DatabaseServer> servers;

    @Value("${database.replicas}")
    private DatabaseReplica[] replicas;
}
```

## 最佳实践

1**配置**：
   ```yaml
   nimbus:
     config:
       server-addresses: ["localhost:8080"]
       environment: "dev"
   ```

2**配置变更监听**：
   ```java
   无需监听，插件做了自动刷新机制
   ```

## 注意事项

1. 如果没有默认值且配置中心不可用，字段将保持初始值（通常为null）
2. 复杂对象需要有无参构造函数
3. 列表/数组类型需要指定泛型或组件类型
4. 建议为关键配置提供合理的默认值（ps：目前不给配置启动会无法启动，正常启动后默认值会被刷新。加载机制问题还未解决）

## 版本兼容性

- Spring Boot 3.x
- Java 17+