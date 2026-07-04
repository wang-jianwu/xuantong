# 玄同配置中心

<p align="center">
  <img src="img/img_02.png" alt="玄同配置中心" width="90%">
</p>

<p align="center">轻量级分布式配置中心 · 基于 Socket.D 主动推送</p>

---

## 设计理念

**极简** — 管理后台、Broker、持久化在同一进程。不拆三个服务，不引入额外中间件。一个 jar 包，一条命令，就是一个完整的配置中心。

**轻量** — 不绑死数据库，不强制依赖 Redis。开发用内置 H2，生产用 MySQL、PostgreSQL、达梦——你的环境用什么，玄同就适配什么。

**高效** — 基于 Socket.D 双向长连接，配置变更主动推送。不是客户端定时去问"有没有更新"，是服务端有变更立刻推过去。

**稳定** — 客户端持有内存缓存和本地文件快照。配置中心完全宕机时，业务应用仍能从本地缓存启动并正常运行。基础设施不应成为单点故障。

**可控** — 每个在线连接、每次推送结果、每条配置变更历史都有记录。不是黑盒运行，出问题能查到具体环节。

## 快速开始

```bash
java -jar xuantong-admin.jar
```

打开 http://localhost:8088 — 账号 `admin` / `admin123`

无需数据库、无需 Redis，开箱即用。

### 客户端接入

**方式一：原生客户端**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-client</artifactId>
    <version>1.3.0</version>
</dependency>
```

```java
// 初始化（配多个 Broker 地址自动 failover）
XuantongConfig.init(
    Arrays.asList("node1:8088", "node2:8088"),
    Arrays.asList("your-app-name"),
    "prod"
);

// 获取配置
String timeout = XuantongConfig.get("payment.timeout", "5000");

// 监听变更
XuantongConfig.addListener("payment.timeout", event -> {
    System.out.println("配置变更: " + event.getNewValue());
});
```

**方式二：Solon Plugin（`@ConfigValue` 注入）**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-solon-plugin</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
# app.yml
xuantong.config:
  serverAddresses:
    - config-center:8088
  appNames:
    - your-app-name
  environment: prod
```

```java
@Component
public class AppConfig {
    @ConfigValue(value = "server.port", defaultValue = "8080")
    private int serverPort;

    @ConfigValue(value = "app.name", autoRefresh = true)
    private String appName;
}
```

**方式三：Solon Cloud Plugin**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-solon-cloud-plugin</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
# app.yml
solon.cloud.xuantong:
  server: "config-center:8088"
  namespace: "prod:app1,app2"   # 格式: 环境:订阅应用列表
  config:
    enable: true
    load: "db.yml,redis.yml"     # 启动时加载指定配置键，可用 @Inject 注入
```

```java
@Configuration
public class AppConfig {
    @CloudConfig("app.payment.timeout")
    private String paymentTimeout;

    @CloudConfig("app.db.url", autoRefreshed = true)
    private PaymentConfig paymentConfig;
}

// 监听配置实时更新
@Component
public class ConfigChangeHandler implements CloudConfigHandler {
    @Override
    public void handler(Config config) {
        System.out.println("配置变更: " + config.key() + " = " + config.value());
    }
}
```

**方式四：Spring Boot Starter**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-spring-boot-starter</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
# application.yml
xuantong.config:
  server-addresses: ["config-center:8088"]
  app-name: ["your-application-name"]
  environment: "prod"
```

```java
@Component
public class AppConfig {
    @ConfigValue("app.name")
    private String appName;

    @ConfigValue(value = "db.config", type = ValueType.JSON, autoRefresh = true)
    private DatabaseConfig dbConfig;
}
```

## 架构

```mermaid
graph TB
    subgraph Admin["玄同 Admin（单进程）"]
        A[Web 管理台]
        B[Socket.D Broker]
        C[ConfigService]
        D[(数据库)]
        A --> C
        C --> D
        C --> B
    end

    B <-->"Socket.D 长连接" AppA["App A<br/>内存 + 文件快照"]
    B <-->"Socket.D 长连接" AppB["App B<br/>内存 + 文件快照"]
    B <-->"Socket.D 长连接" AppC["App C<br/>内存 + 文件快照"]
```

**推送机制** — 配置变更时，Broker 按 `project:env` 精确路由推送到订阅客户端，不是广播。

**容灾设计** — 客户端保有内存缓存和本地文件快照。配置中心完全不可达时，业务从本地缓存启动并正常运行。

**集群同步** — 多个 Admin 节点通过 Broker 互联组播，对等架构，无需选主，无需消息队列。

## 功能

**配置管理**
- 多项目、多环境隔离
- 配置值 AES 加密存储
- 搜索过滤、批量操作

**发布管控**
- 变更实时推送，毫秒级到达
- 灰度发布：随机节点 / 指定 IP / 按比例
- 完整变更历史，一键回滚

**运维监控**
- 在线客户端、连接状态实时查看
- 推送日志记录
- RBAC 权限控制

## 截图

| 配置编辑 | 灰度推送 |
|:---:|:---:|
| <img src="img/img_03.png" width="100%"> | <img src="img/img_04.png" width="100%"> |

| 版本回滚 | Broker 监控 |
|:---:|:---:|
| <img src="img/img_05.png" width="100%"> | <img src="img/img_06.png" width="100%"> |

## 部署

| 模式 | 说明 |
|:---|:---|
| 单机 | `java -jar` 即可，内置 H2 |
| 生产 | 修改 `core.yml` 接入任意关系型数据库 |
| 集群 | 配置多节点地址 + Redis 共享缓存 |
| Docker | `docker compose up -d` |

支持 MySQL、PostgreSQL、达梦、人大金仓等，不做绑定。

## 技术选型

| | 选型                                                      | 理由 |
|:---|:--------------------------------------------------------|:---|
| 框架 | [Solon](https://solon.noear.org/)                       | 启动快、内存省、原生支持 Socket.D |
| 通信 | [Socket.D](https://socketd.noear.org/)                  | 双向长连接，天然适合推送场景 |
| ORM | [EasyQuery](https://www.easy-query.com/easy-query-doc/) | 多数据库方言，一套代码适配多种数据库 |
| 客户端 | Java 8+                                                 | 兼容老项目 |

## License

[Apache License 2.0](LICENSE)

# 📊 Xuantong Config 真实项目适用性评估报告

> **评估结论**：Xuantong Config 是一个功能完整、架构清晰的轻量级分布式配置中心，已具备企业级核心特性。推荐中小型团队（尤其是 Solon 技术栈）在真实生产项目中采用。

---

## ✅ 核心能力矩阵

| 评估维度 | 状态 | 仓库实现依据 |
|---|---|---|
| **核心功能** | ✅ 完备 | 配置 CRUD、实时推送（SocketD WebSocket）、热更新、本地缓存（`ConfigCacheManager`） |
| **版本历史** | ✅ 已实现 | `ConfigController` 提供历史查询 API，数据持久化至数据库（非内存），支持长期追溯 |
| **版本回滚** | ✅ 已实现 | 前端历史模态框一键回滚，调用后端回滚接口实时生效 |
| **灰度发布** | ✅ 已实现 | 支持 4 种模式：随机 1 台 / 指定 IP / 按比例 / 全量推送（`ConfigController.pushMode`） |
| **多节点集群** | ✅ 已实现 | 全互联（Full Mesh）架构，`ConfigClusterBroadcaster` 实现同步，含防环机制与自动重连 |
| **框架生态** | ✅ 双支持 | 原生 `Solon Plugin` + `Spring Boot Starter`，提供 `@ConfigValue` / `@CloudConfig` 注解 |
| **部署运维** | ✅ 轻量 | Docker Compose 一键部署，支持 H2（默认）/ MySQL / Redis 多存储后端 |
| **安全机制** | ✅ 基础完善 | `AuthInterceptor` 认证拦截、配置值 AES 加密存储、Broker Secret Key 连接鉴权 |
| **测试覆盖** | ⚠️ 待加强 | 目前仅 `xuantong-client` 包含基础测试，`admin` 与 `core` 需补充集成测试 |

---

## 🏗️ 集群架构说明

项目已原生支持多节点部署，采用 **全互联（Full Mesh）** 拓扑：
- **配置同步**：基于 `/cluster-sync` Socket.D 消息通道实时广播
- **防环机制**：同步消息携带 `sourceNodeId`，节点自动忽略自身产生的事件
- **故障隔离**：连接断开自动重连（`autoReconnect(true)`），灰度推送不触发集群广播
- **监控埋点**：提供 `ClusterMonitor` 接口暴露活跃连接数，便于运维yaml


---

## 🎯 适用场景推荐

| 场景 | 推荐度 | 说明 |
|---|---|---|
| **Solon 生态项目** | ⭐⭐⭐⭐⭐ | 原生支持，API 体验优秀，几乎是目前最优开源选择 |
| **中小型团队微服务** | ⭐⭐⭐⭐⭐ | 功能完备，集群部署简单，适合 50 节点以下规模 |
| **Spring Boot 项目** | ⭐⭐⭐⭐ | Starter 集成友好，可替代轻量级 Spring Cloud Config 场景 |
| **大型团队 / 强合规** | ⭐⭐⭐ | 具备历史记录与回滚，但暂缺审批流、操作审计等企业级管控功能 |

---

## 🛠️ 生产落地建议

1. **集群规模控制**：全互联架构连接数呈 `O(n²)` 增长，建议单集群控制在 **5-10 节点**以内
2. **存储选型**：生产环境务必使用 **MySQL**，避免 H2 嵌入式数据库的并发与持久性限制
3. **监控告警**：对 `ClusterMonitor.getActiveConnectionCount()` 设置阈值，连接数异常下降时及时告警
4. **灰度规范**：灰度推送后建议建立 **二次确认机制**，防止错误配置影响范围扩大
5. **测试补充**：建议为核心模块（`admin`/`core`）补充集成测试，覆盖配置同步与集群切换场景

---
*报告生成时间：2026-07-03 | 基于仓库 v1.3.0 代码与架构分析*
