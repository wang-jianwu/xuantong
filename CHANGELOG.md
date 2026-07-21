# Changelog

本文件记录面向使用者的版本变化。

## [Unreleased]

暂无。

## [2.0.0] - 2026-07-22

### Added

- Socket.D TCP 控制面。
- Config 与 Registry Apache Ratis 双状态组。
- 配置草稿、发布、灰度、转全量、终止、回滚、下线和恢复。
- Text、String、Number、Boolean、Properties、YAML、JSON、XML 校验与格式化。
- 服务定义、实例注册、Lease 续租、摘除、发现和 Watch。
- Java Client、Spring Boot、Spring Cloud、Solon 和 Solon Cloud 集成。
- 管理控制台、用户权限、应用 Token、审计和 TLS/mTLS。
- H2 零配置单机模式和 MySQL 三/五节点部署模式。
- 外部 Probe、Prometheus 指标、Grafana Dashboard、备份与恢复脚本。
- Maven Central、GitHub Release、SBOM、签名和可重现构建配置。

### Changed

- 2.0 使用全新协议、数据库 Schema 和集群模型。
- 多地址客户端使用单活动 Session，并在失败时有界顺序切换。
- applicationName 标识逻辑服务，clientInstanceId 自动生成并标识具体实例。
- Provider 第一次注册时自动创建服务；管理员删除过的服务仍然需要手动重新创建。
- Solon 对同一 HTTP Signal 的重复注册改为幂等处理。
- 普通 `XuantongConfigClient` 构造不再修改全局默认客户端，静态门面改为显式初始化或绑定。
- 配置监听器返回可关闭句柄，Spring Bean 销毁时自动解除监听，prototype Bean 不再挂长期监听。
- Spring Cloud ConfigData 在同一启动作用域内复用客户端连接，并在 Bootstrap 结束时关闭。
- Spring Cloud 增加 ReactiveDiscoveryClient；Spring Cloud 与 Solon Cloud 的服务 Agent 复用 discovery 连接。
- 服务目录使用独立查询，不再创建 `xuantong-service-catalog` 假服务。
- 本地配置快照目录支持按客户端配置。
- Solon Cloud Config 的 `push/remove` 明确为只读异常，不再静默返回 false。
- Solon Cloud 对同一个 Signal 只注册和打印一次。

### Removed

- 1.x Broker 通知路径。
- 1.x 协议、Schema、灰度规则和客户端身份兼容逻辑。
- 未形成完整安全合同的配置加密开关。

[Unreleased]: https://github.com/wang-jianwu/xuantong/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/wang-jianwu/xuantong/releases/tag/v2.0.0
