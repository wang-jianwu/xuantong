# 玄同（Xuantong）2.0.0 发布说明

## 定位

2.0 将项目升级为轻量级微服务控制面，同时提供配置发布与服务注册发现。该版本是纯 2.0 设计，不提供任何 1.x API、数据库、页面、Artifact 或客户端协议兼容层。

统一资源定位模型为：

```text
namespace + group + resourceName
```

## 主要变化

- 配置草稿、发布、不可变 Release、回滚、批量原子发布和审计。
- ServiceDefinition、临时 ServiceInstance、心跳、自动摘除和客户端负载均衡。
- 独立的 `/config-v2` 与 `/discovery-v2` Socket.D 通道。
- `SYSTEM_ADMIN`、`NAMESPACE_ADMIN`、`DEVELOPER`、`VIEWER` 及 Namespace/Group 范围授权。
- 仅存 Token SHA-256 哈希，支持范围、过期、吊销和凭据审计。
- H2、MySQL、PostgreSQL 独立 Schema，启动时按方言 fail-fast 初始化。
- Socket.D multi-broker 多活：Client 同时连接全部 Broker，Broker 之间零事件复制。
- Discovery Provider 向全部 Broker 注册同一租约，Consumer 合并各 Broker 的可达实例视图。
- 配置 revision 来自共享数据库；服务实例 revision 保持 Broker 本地，Client 生成合并视图的逻辑 revision。
- Spring Boot、Solon、Solon Cloud 2.0 集成。
- `/health` 与 Prometheus `/metrics`。
- 新管理仪表盘，聚合组件健康、配置发布、服务实例、鉴权失败、Token 和 JVM 运行指标。
- Config/Discovery 客户端握手新增 `applicationName`、`clientId` 和版本；Dashboard 与连接页面分别展示逻辑客户端和当前 Broker 物理连接。
- 新 Token 管理与全局审计页面，支持范围、过期时间、一次性明文展示、吊销、筛选和详情查看。
- 配置页面新增发布/审计历史、历史 Release 回滚和未发布草稿删除。
- 用户授权范围与 Namespace 下 Group 管理改为可视化弹窗，不再使用浏览器原生 `prompt`、`alert` 或 `confirm`。
- 所有管理页面统一侧栏、Header、主题状态和响应式布局，手机端宽表在容器内独立滚动。

## 模块

```text
xuantong-common
xuantong-protocol
xuantong-config-core
xuantong-discovery-core
xuantong-security
xuantong-server
xuantong-client-core
xuantong-solon-plugin
xuantong-solon-cloud-plugin
xuantong-spring-boot-starter
```

## 运行要求

- Server：JDK 21。
- Java Client、Solon Plugin、Solon Cloud Plugin：Java 8+。
- Spring Boot Starter：Java 17+。
- 单节点默认使用 H2，不要求 Docker 或外部数据库。
- 多 Broker 共享外部数据库中的持久化事实，ServiceInstance 保持 Broker 本地内存状态。
- 应用 Client 必须配置全部 Broker 地址；`XUANTONG_NODE_ID` 只用于诊断，可不配置。

## 不兼容说明

- 不支持从 1.x Schema 原地升级，2.0 必须连接全新空数据库。
- 默认 H2 文件改为 `./data/xuantong-2.mv.db`，保留但不读取旧的 `xuantong.mv.db`；显式连接旧 Schema 时会在写入前 fail-fast。
- 删除 Project/Environment 寻址模型，统一改为 Namespace/Group。
- 删除旧模块名与旧 Artifact。
- 删除旧 HTTP、Socket.D 协议和旧管理页面。

## 当前验证状态

- Maven Reactor 11 个项目全量构建与测试通过（根项目 + 10 个模块）。
- 全量运行发现 80 个测试：76 个通过，4 个环境型测试跳过；其中真实 Socket.D 网络广播/`preclose` 测试和远程 MySQL 只读连接测试已另行执行通过，剩余真实 MySQL、PostgreSQL 写入型 Schema 测试需要专用空库。
- H2、MySQL 模式、PostgreSQL 模式 Schema 完整执行和幂等初始化通过。
- Multi-broker 测试覆盖并行连接、地址去重、慢节点隔离、配置读取失败转移、实例并集、Broker 独立 revision、断线重算、Session 关闭立即摘除和租约接管。
- 真实 Socket.D WebSocket 测试验证三个同名 Session 全部收到广播，接收端 `preclose` 后 `isActive()` 为 false。
- 远程 MySQL JDBC 只读连接与 `SELECT 1` 验证通过；未对非专用数据库执行建表测试。
- 单节点 JAR 实际启动通过，`/health` 和 `/metrics` 验证通过。
- 管理端真实浏览器验收通过：1280×720 桌面端与 390×844 手机端覆盖 Dashboard、Config、Namespace、Service、Token、Audit、User 页面。
- 已验证登录进入 Dashboard、侧栏与主题跨页面持久化、配置/Group/Token/用户范围弹窗，以及手机端无页面级横向溢出；浏览器控制台无应用错误。
- 配置-only Spring Boot Demo 实测显示为 1 个 Config 逻辑客户端、1 条物理连接，同时服务发现 Service/Instance 保持 0/0。
- GitHub Actions 已配置真实 MySQL、PostgreSQL 服务测试。

## 正式发布前检查

- 在专用环境运行真实 MySQL、PostgreSQL 集成测试。
- 将所有 `2.0.0-SNAPSHOT` 统一切换为 `2.0.0`。
- 执行签名、Central Publishing 校验和发布。
- 创建 `v2.0.0` Git Tag。
