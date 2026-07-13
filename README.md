# 玄同（Xuantong）

<p align="center">
  <strong>简体中文</strong> ｜ <a href="./README_EN.md">English</a>
</p>

<p align="center">
  <img src="img/img_02.png" alt="玄同控制台" width="90%">
</p>

<p align="center">轻量级微服务控制面 · 配置发布 · 服务发现 · 服务治理</p>

## 2.0

当前主线为 `2.0.0-SNAPSHOT`。项目直接采用 2.0 架构，不提供 1.x API、数据库、页面或客户端协议兼容层。

配置资源使用唯一的三元组定位：

```text
namespace + group + dataId
```

```text
Namespace
└── Group
    ├── Config: dataId
    └── Service: serviceName
        └── Instance: instanceId
```

详细设计和进度见 [PLAN_2.0.md](PLAN_2.0.md)，版本变化见 [RELEASE_NOTES_2.0.0.md](RELEASE_NOTES_2.0.0.md)。

## 设计特点

- 单进程部署：管理端、持久化和 Socket.D 推送端点在同一个服务中。
- 零外部依赖启动：默认使用内嵌 H2，不要求额外中间件。
- 发布态读取：编辑保存为草稿，客户端只能读取已经发布的 Release。
- 不可变历史：每次发布生成新 revision；回滚同样生成新的 Release。
- 原子批量发布：一个批次内先完成全量校验，再在同一事务中写入 Release 和审计记录。
- 精确推送：客户端订阅 `config:{namespace}:{group}`，配置事件按 Group 路由。
- 本地容灾：客户端按 `namespace/group` 保存本地快照。
- 服务治理：Service 定义持久化，ServiceInstance 保存在内存中，通过心跳续租并自动摘除。
- Socket.D multi-broker：客户端同时连接所有 Broker；Broker 彼此独立，不做节点间事件同步。
- 多 Broker 服务发现：Provider 向每个 Broker 使用同一租约注册和心跳，Consumer 合并所有可达 Broker 的实例视图。
- 统一管理端：登录后进入运行概览，集中展示健康状态、配置发布、服务实例、Token、安全和 JVM 指标。
- 配置运维：可视化查看发布与审计历史，从任意历史 Release 回滚，并管理未发布草稿。
- 安全运维：提供客户端 Token 签发/吊销、全局审计日志和用户 Namespace/Group 授权范围管理。
- 连接观测：Config 与 Discovery 会话独立统计，按 `clientId` 展示逻辑客户端，并保留当前 Broker 的物理 Socket.D 连接视图。
- 响应式界面：统一侧栏、主题切换、非原生确认框和桌面/手机布局；宽表只在表格容器内部滚动。

## 启动服务端

需要 JDK 21：

```bash
mvn -pl xuantong-server -am package -DskipTests
java -jar xuantong-server/target/xuantong-server.jar
```

打开 <http://localhost:8088>，默认账号为 `admin` / `admin123`。

登录后默认进入 `/dashboard`。管理端包含运行概览、配置管理、命名空间与资源组、服务与实例、访问令牌、审计日志和用户授权页面。访问令牌与全局审计页面仅向 `SYSTEM_ADMIN` 开放。

默认使用 `./data/xuantong-2.mv.db`。无需 Docker 或外部数据库即可启动。2.0 不会读取或迁移旧的 `./data/xuantong.mv.db`；如显式配置了旧数据库，Server 会在写入前拒绝启动并提示改用全新空库。生产环境可通过 `XUANTONG_DB_*` 环境变量切换数据源。

客户端 Token 通过 `/api/v2/tokens` 创建，明文只在创建响应中返回一次，数据库仅保存 SHA-256 哈希。Token 可限制到指定 Namespace 和 Group。生产环境应设置：

```bash
export XUANTONG_PRODUCTION=true
export XUANTONG_CLIENT_AUTH_REQUIRED=true
```

`XUANTONG_PRODUCTION=true` 时如果管理员仍使用默认密码 `admin123`，Server 会拒绝启动。

管理端使用四级权限：`SYSTEM_ADMIN` 管理全局资源；`NAMESPACE_ADMIN` 管理授权 Namespace；`DEVELOPER` 可在授权 Namespace/Group 内编辑配置和服务；`VIEWER` 仅可读取授权范围。用户范围通过用户管理页面或 `/api/user/{id}/scopes` 分配。

健康检查位于 `/health`，包含数据库与 Discovery 清理任务状态；Prometheus 文本指标位于 `/metrics`，覆盖配置发布、Config 逻辑客户端与物理连接、Token 鉴权、实例注册/心跳/摘除、JVM 内存和进程运行时间。

配置客户端会在 Socket.D 握手中提交 `applicationName`、`clientId` 和客户端版本。管理端 `/connection` 页面将配置客户端与服务发现实例分开显示，因此只使用配置中心、不使用服务发现的应用也能正常出现在面板中。Multi-Broker 下逻辑客户端按 `clientId` 去重；每个 Broker 页面展示该节点实际持有的物理连接。

如果 Server 日志出现 `Rejecting Broker session without 2.0 client identity`，说明运行中的应用仍加载了重构前的 `xuantong-client` 或 `xuantong-config-spring-boot-starter`。2.0 必须使用 `xuantong-client-core` / `xuantong-spring-boot-starter`。在 IntelliJ IDEA 中重新加载全部 Maven 项目、停止旧进程并重新启动；可用 `mvn dependency:tree -Dincludes=cloud.xuantong` 核对实际依赖，运行 classpath 中不应再出现旧 artifactId。

多 Broker 部署不需要额外的集群传输层。所有 Broker 共享同一个外部数据库，用于配置、ServiceDefinition、用户和权限等持久化事实；ServiceInstance 仍只保存在各 Broker 内存中。节点 ID 只用于日志和事件来源诊断：

```bash
export XUANTONG_NODE_ID=config-node-1
```

按照 Socket.D 官方 multi-broker 架构，应用 Client 必须配置全部 Broker 地址并同时保持连接。配置 Client 从任一可用 Broker 读取共享数据库中的最新 Release，按配置 revision 去重，并在连接变化及固定周期重新核对已知配置。Discovery Provider 使用同一个 `instanceId + leaseId` 向全部 Broker 注册和心跳，`leaseStartedAt` 由 Broker 分配，不依赖客户端系统时间；Consumer 为每个 Broker 独立跟踪本地 revision，再向业务侧暴露可达 Broker 实例的并集。一个 Broker 故障不会影响其他已有连接，Broker 之间也不需要互相复制实例事件。

真实外部数据库测试使用专用空数据库，并按需设置 `XUANTONG_TEST_MYSQL_*`、`XUANTONG_TEST_PGSQL_*` 环境变量后执行 `mvn test`；未配置时这些测试会明确跳过。

仓库 CI 会启动真实 MySQL、PostgreSQL 服务并执行全量 Reactor 测试。

2.0 只支持全新的 2.0 Schema，请使用空数据库启动，不要直接连接旧版本数据库。

## Java 客户端

客户端保持 Java 8 兼容：

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-client-core</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```java
XuantongClient client = new XuantongClient(
    Arrays.asList("node1:8088", "node2:8088"),
    "public",
    "DEFAULT_GROUP",
    System.getenv("XUANTONG_ACCESS_TOKEN"),
    "order-service",
    System.getenv("XUANTONG_CLIENT_ID")
);

String timeout = client.get("payment.timeout", "5000");

client.addListener("payment.timeout", event ->
    System.out.println("revision=" + event.getRevision()
        + ", value=" + event.getNewValue()));
```

服务注册与发现使用独立客户端：

```java
XuantongDiscoveryClient discovery = new XuantongDiscoveryClient(
    Arrays.asList("node1:8088", "node2:8088"),
    "public",
    "DEFAULT_GROUP",
    "order-service",
    System.getenv("XUANTONG_ACCESS_TOKEN")
);

ServiceInstance local = new ServiceInstance();
local.setInstanceId("order-node-1");
local.setIp("10.0.0.8");
local.setPort(8080);
local.setWeight(2D);
discovery.register(local); // 自动发送实例心跳，close() 时自动注销

ServiceInstance target = discovery.selectInstance(LoadBalanceStrategy.WEIGHTED_RANDOM);
```

客户端支持 `RANDOM`、`ROUND_ROBIN` 和 `WEIGHTED_RANDOM`。选择过程完全发生在业务客户端内，玄同不会代理业务请求。

## Spring Boot Starter

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-spring-boot-starter</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
xuantong:
  config:
    server-addresses: ["xuantong-server:8088"]
    namespace: public
    group: DEFAULT_GROUP
    access-token: ${XUANTONG_ACCESS_TOKEN:}
    application-name: ${spring.application.name}
    client-id: ${XUANTONG_CLIENT_ID:}
```

```java
@Component
public class AppConfig {
    @ConfigValue(value = "app.name", autoRefresh = true)
    private String appName;
}
```

## Solon Plugin

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-solon-plugin</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
xuantong.config:
  serverAddresses:
    - xuantong-server:8088
  namespace: public
  group: DEFAULT_GROUP
  accessToken: ${XUANTONG_ACCESS_TOKEN:}
  applicationName: ${solon.app.name}
  clientId: ${XUANTONG_CLIENT_ID:}
```

## Solon Cloud Plugin

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-solon-cloud-plugin</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
solon.app:
  group: DEFAULT_GROUP

solon.cloud.xuantong:
  server: xuantong-server:8088
  namespace: public
  token: ${XUANTONG_ACCESS_TOKEN:}
  config:
    enable: true
    load: db.yml,logging.yml
```

Solon Cloud 请求中的 `group` 会直接映射为玄同 Group；空 Group 映射为 `DEFAULT_GROUP`。`name` 直接映射为 `dataId`。

## 2.0 API

控制面 HTTP API 位于 `/api/v2`，配置和服务发现使用两个独立 Socket.D 端点：

- `/config-v2`：配置读取和发布事件推送。
- `/discovery-v2`：服务注册、心跳、注销、实例查询和实例变更推送。

服务订阅名为 `discovery:{namespace}:{group}:{serviceName}`。Service 定义保存到数据库；实例和心跳属于临时运行态，不写数据库。默认 30 秒没有心跳会摘除实例，可通过 `XUANTONG_INSTANCE_TIMEOUT_MS` 调整。

主要流程：

1. 创建 Namespace 和 Group。
2. 保存配置草稿。
3. 发布草稿，生成不可变 Release 和递增 revision。
4. 多个草稿可在同一事务内批量发布，任一资源校验失败则整批不产生 revision。
5. 每次发布和回滚都会写入不包含配置正文的审计记录。
6. 客户端读取最新 Release，并接收后续发布事件。
7. 回滚历史版本时生成新的 `ROLLBACK` Release。

## 技术栈

| 领域 | 选型 |
|:---|:---|
| 服务端 | Solon、JDK 21 |
| 长连接 | Socket.D |
| 数据访问 | EasyQuery |
| 默认数据库 | H2 |
| 客户端 | Java 8+ |

## License

[Apache License 2.0](LICENSE)
