# Nimbus 分布式配置中心

## 产品介绍
Nimbus-Config是一款高性能的分布式配置管理平台，提供完整的配置管理解决方案：

### 核心特性
- ⚡ **实时推送**：基于Socket.D的毫秒级配置变更通知
- 🛡️ **多级容灾**：内存 → 本地文件快照 → Redis缓存 → 配置中心
- 📊 **完善监控**：内置性能指标采集和健康检查
- 🔒 **安全可靠**：支持配置加密和权限控制
- 🌐 **多框架支持**：原生客户端 + Solon Cloud插件

### 适用场景
- 微服务架构中的集中配置管理
- 需要实时配置更新的业务系统
- 对配置一致性和可用性要求高的场景
- 希望与Solon Cloud生态集成的项目
## 快速开始

### 方式一：使用原生客户端

#### 1. 添加依赖
```xml
<dependency>
    <groupId>com.nimbus</groupId>
    <artifactId>nimbus-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### 2. 初始化客户端
```java
// 实例化客户端
@Bean
NimBusClient client = new NimBusClient(
    Arrays.asList("config-center:8080"),
    "your-app-name", 
    "dev"
);
// 或者使用静态门面
//spring
@PostConstruct
public void init() {
    NimbusConfig.init(
            Arrays.asList("config-center:8080"),
            "your-app-name",
            "dev");
}
//solon
@Init
public void init() {
    NimbusConfig.init(
            Arrays.asList("config-center:8080"),
            "your-app-name",
            "dev");
}
@stop
public void stop() {
    NimbusConfig.close();
}
```

#### 3. 获取配置

```java
import java.util.List;

String timeout = client.get("payment.timeout", "5000");
// 或者使用静态门面
String timeout = NimbusConfig.get("payment.timeout", "5000");
PaymentConfig paymentConfig = NimbusConfig.getObject("payment.conf", PaymentConfig.class);
List<PaymentConfig> paymentConfig = NimbusConfig.getObjectList("payment.conf", PaymentConfig.class);
```

#### 4. 监听变更
```java
client.addListener("payment.timeout", event -> {
    System.out.println("配置变更: " + event.getNewValue());
});
```

### 方式二：使用Solon Cloud插件

#### 1. 添加依赖
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>nimbus-config-solon-cloud-plugin</artifactId>
    <version>${solon.version}</version>
</dependency>
```

#### 2. 配置应用
```yaml
# application.yml
solon:
  cloud:
    nimbus-conf:
      server: config-center:8080
      namespace: your-app-name:prod
      config:
        enable: true
        load: db.yml,redis.yml # 指定加载的配置key 可@Inject 注入
```

#### 3. 使用配置
```java
// 自动注入配置服务
@Configuration
public class AppConfig {

    @CloudConfig("payment.timeout")
    private String paymentTimeout;

    @CloudConfig("db.url", autoRefreshed = true)
    private PaymentConfig paymentConfig;
}
```
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
## 功能特性

### 已实现功能
✅ **核心客户端** (nimbus-client) - 高性能原生Java客户端
✅ **多级缓存架构** - 内存 → 本地文件 → Redis → 服务端
✅ **实时推送机制** - 基于Socket.D的毫秒级推送
✅ **熔断保护** - 自动降级和故障转移
✅ **本地快照** - 离线模式下自动恢复配置
✅ **Solon Cloud插件** - 与Solon Cloud生态完美集成

### 计划功能
🔲 可视化监控仪表盘
🔲 更多客户端语言支持
### 配置监听和动态更新
```java
// 监听单个配置项
client.addListener("payment.timeout", event -> {
    System.out.println("支付超时时间变更为: " + event.getNewValue());
    // 动态更新业务逻辑
    paymentService.updateTimeout(Integer.parseInt(event.getNewValue()));
});

// 监听配置组
client.addListener("db.*", event -> {
    System.out.println("数据库配置变更: " + event.getKey());
    // 重新初始化数据源
    dataSourceManager.refresh();
});
```

### 容灾和熔断策略
Nimbus-Config内置多级容灾机制：
1. **内存缓存** - 优先从内存获取，性能最佳
2. **本地快照** - 网络异常时自动使用本地备份
3. **Redis缓存** - 分布式环境下的共享缓存
4. **服务端** - 最终数据源，保证配置一致性

### 监控和健康检查
```java
// 获取客户端状态
ConfigMetrics metrics = client.getMetrics();
System.out.println("连接状态: " + metrics.getConnectionStatus());
System.out.println("配置获取次数: " + metrics.getConfigFetchCount());
System.out.println("实时推送次数: " + metrics.getPushNotificationCount());
```

## 最佳实践

### 部署架构建议
| 环境 | 架构方案 | 容灾策略 |
|------|---------|---------|
| 开发环境 | 单节点 + 本地快照 | 本地文件备份 |
| 测试环境 | 双节点集群 + Redis | Redis缓存 + 本地快照 |
| 生产环境 | 多节点集群 + Redis哨兵 | 多级缓存 + 自动故障转移 |

### 性能调优指南
1. **连接池配置**：
   ```yaml
   nimbus:
     client:
       pool-size: 20
       connection-timeout: 5000
       heartbeat-interval: 30000
   ```

2. **缓存策略**：
   ```yaml
   nimbus:
     client:
       memory-cache-size: 1000
       local-snapshot-enabled: true
       redis-ttl: 3600
   ```

3. **网络优化**：
   ```yaml
   nimbus:
     client:
       compression-enabled: true
       buffer-size: 8192
   ```

## 故障排查

### 常见问题
1. **连接失败**：检查配置中心地址和网络连通性
2. **配置不生效**：确认namespace和应用名配置正确
3. **推送延迟**：调整心跳间隔和超时时间

### 日志分析
启用DEBUG日志查看详细运行信息：
```yaml
logging:
  level:
    com.nimbus: DEBUG
```

## 技术支持
- issues