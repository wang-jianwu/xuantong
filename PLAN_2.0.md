# 玄同 2.0 开发计划

> 状态：实施中
>
> 更新日期：2026-07-18
>
> 目标：先完成纯 2.0 配置/注册发现生产闭环，再演进为面向 Java 生态的一站式分布式服务治理控制面

## 1. 唯一实施基线

实现与评审只使用以下文档：

1. [快速入门](README.md)
2. [最终架构设计](doc/architecture.md)
3. [功能设计](doc/features.md)
4. [开发计划](PLAN_2.0.md)

如文档与代码冲突：

- 已实现行为以自动化测试和真实运行路径为证据。
- 目标行为以最终架构和功能设计为准。
- 任何新的重大架构变更必须先更新这四份文档，不再新建平行“最终方案”。

## 2. 当前结论

玄同 2.0 的配置与注册发现控制面正确性主干已完成，但还不能标记为生产可用。这两项能力是未来一站式服务治理控制面的事实和传输基础。

已完成：

- 原生 Socket.D TCP/Netty `/control-v2`。
- Protobuf 协议、Hello、Probe、结构化失败和有界排空。
- Apache Ratis Config/Registry Multi-Raft、WAL、Snapshot 和 ReadIndex。
- Config 权威发布、灰度、回滚、operationId 和 SQL 投影恢复。
- Registry 权威 Lease、generation、tombstone、fencing 和逻辑 TTL。
- Config/Discovery Snapshot、长 Watch、ACK 背压、committed cursor 和 last-known-good。
- Token 鉴权、作用域、单 Gateway 配额/限流/鉴权失败限速。
- Spring Boot、Spring Cloud 2025.1、Solon 和 Solon Cloud 集成。
- Spring Cloud ConfigData、DiscoveryClient、ServiceRegistry、自动注册/注销和 LoadBalancer 标准实例供应。
- 配置客户端 API 已明确命名为 `XuantongConfigClient`，不保留含义模糊的旧总入口兼容类。
- 管理端主要页面与真实发布结果校验。
- 真实 Socket.D TCP + Ratis 的 Config/Discovery 端到端测试。

未完成的工作分为 P0、P1 和 P2，不再用“大部分完成”模糊描述。

## 3. P0：公开 Beta 前必须完成

### P0-01 管理端认证和会话安全

当前问题：

- `UserService.authenticate` 没有强制拒绝 `isActive=false` 的用户。
- 登录缺少账号/IP 维度的失败限速与退避。
- 多 Server 下的管理会话仍需统一方案。
- SameSite Cookie 不能替代完整 CSRF 防护。

实施：

- [ ] 登录时强制校验用户启用状态。
- [ ] 停用用户、修改密码或降权后使现有会话在有界时间内失效。
- [ ] 实现账号 + 远端 IP 登录失败限速、递增退避和审计。
- [ ] 管理会话采用签名的无状态 Session/JWT，多 Server 共享签名密钥，不强制引入 Redis。
- [ ] 为管理写 API 增加 CSRF Token 校验。
- [ ] 强制 Secure、HttpOnly、SameSite、过期时间和登出失效策略。

验收：

- [ ] 停用用户无法新登录，旧会话按策略失效。
- [ ] 密码爆破测试能触发结构化限流，且不影响正常用户。
- [ ] 两个 Server 之间不需要粘性会话即可访问已登录管理端。
- [ ] 跨站管理写请求被拒绝。

### P0-02 配置编辑与校验能力

当前问题：

- 管理端只有基础 textarea，缺少 JSON/YAML/XML/Properties 校验和美化。
- 数字、布尔值、字符串的编辑体验不完整。
- 草稿保存缺少用户可见的乐观并发控制。

实施：

- [ ] 为 text/properties/yaml/json/xml 定义服务端校验合同，前端只做快速反馈。
- [ ] 增加 JSON/YAML/XML 格式化、压缩和错误定位。
- [ ] 增加 Properties 重复 Key、转义和格式校验。
- [ ] 恢复数字、布尔、字符串的类型化编辑和预览。
- [ ] 草稿保存携带 `expectedDraftRevision`，冲突时返回可比较的差异。
- [ ] 编辑器大文本实施大小限制、延迟校验和主线程保护。

验收：

- [ ] 非法 JSON/YAML/XML/Properties 不能进入发布流程。
- [ ] 两个用户同时编辑不会静默覆盖。
- [ ] 各内容类型的美化、保存、重新编辑和发布结果一致。

### P0-03 灰度发布可解释性

当前问题：

- 百分比稳定分桶在小样本下可能零命中，界面没有说明。
- 没有按 `clientInstanceId` 精确灰度。
- 没有显示某个客户端命中了哪条规则。

实施：

- [ ] 增加 `CLIENT_INSTANCE_ID` 精确选择器的管理 API 和界面。
- [ ] 灰度创建前计算当前可见实例的命中预览。
- [ ] 预览结果明确标识为“当前 Gateway”或“集群聚合”，不用本地视图冒充集群全量。
- [ ] 对“在线实例数 × 灰度比例 < 1”显示小样本警告。
- [ ] 连接页和配置详情显示 `matchedRuleId`、当前 content/decision revision。

验收：

- [ ] 用户在发布 10% 前能明确知道单实例可能不命中。
- [ ] 按实例灰度能精确选中指定 JAR/Pod，且 Push/Pull/重连结果一致。
- [ ] 多 Gateway 环境中不会将局部预览误报为全集群结果。

### P0-04 删除虚假加密承诺

当前问题：

- Schema 和配置中存在 `isEncrypted` / `CONFIG_ENCRYPT_KEY`，但没有真正的加密、解密、密钥轮换和客户端信任闭环。

决策：

- [ ] 2.0 首个可用版本先删除未实现的字段、配置和对外承诺。
- [ ] 如后续实现，必须单独设计 KMS/信封加密、密钥版本、轮换、审计和客户端解密边界。

验收：

- [ ] UI、API、Schema、配置和文档不再出现无效的加密开关。
- [ ] 敏感数据安全说明只宣称已验证的 Token 指纹和 TLS/mTLS 能力。

### P0-05 配置下线与生命周期

当前问题：

- 只能删除从未发布的草稿。
- 已发布配置没有明确的下线/tombstone 语义。

实施：

- [ ] 设计 Config tombstone，下线生成新 decision/event revision。
- [ ] 定义客户端收到下线后的行为：回退默认值、保留 last-known-good 或显式缺失，必须由 API 合同决定。
- [ ] 定义历史保留、恢复、物理清理和审计策略。

验收：

- [ ] 下线、重建、回滚和客户端重连都有确定性测试。
- [ ] 不依赖直接删除 SQL 行来改变客户端可见状态。

## 4. P1：生产发布门槛

### P1-01 2.0 Schema 演进

- [ ] 引入 Flyway 或等价的显式 Schema 版本管理。
- [ ] 只支持 2.0.x 之间的可审计升级，不迁移 1.x。
- [ ] 每个迁移提供 H2/MySQL/PostgreSQL 自动化验证。
- [ ] 实现升级前检查、失败中止和恢复手册。

### P1-02 查询、分页、索引与审计

- [ ] 配置、Release、Rollout、服务、实例、Token、用户和审计 API 统一分页合同。
- [ ] 按真实查询模式补齐组合索引和唯一约束。
- [ ] 审计覆盖登录、权限变更、Token、服务生命周期、配置编辑和生产运维操作。
- [ ] 审计详情对 Token、密码、证书和敏感配置做强制脱敏。

### P1-03 跨 Gateway 集群安全边界

- [ ] 实现集群级 Session/Tenant/Credential/Watch 配额视图。
- [ ] 实现跨 Gateway Token 主动吊销通知，周期复核作为补偿。
- [ ] 实现集群级连接实例汇总，为灰度预览和运维观测提供真实全局视图。
- [ ] 集群限流不得引入每请求强一致热点；先定义安全上限和局部令牌分配模型。

### P1-04 Raft 运维与版本演进

- [ ] 实现动态成员增删和安全配置变更流程。
- [ ] 实现节点 capability gate，防止新协议/快照过早启用。
- [ ] 定义协议、State Command、Snapshot Schema 和客户端版本兼容矩阵。
- [ ] 完成三节点滚动升级、中途回滚和快照恢复演练。
- [ ] 实现 operationId 历史和 ChangeLog 的安全压缩/保留策略。

### P1-05 TLS/mTLS 客户端闭环

- [ ] Java Client、Spring Boot、Spring Cloud、Solon 和 Solon Cloud 统一暴露 trustStore、keyStore、密码和 hostname verification。
- [ ] 支持证书更换后有界重建连接。
- [ ] 验证 WANT/REQUIRE client auth、错误 CA、过期证书和双证书轮换。
- [ ] 生产文档不允许通过关闭验证解决证书问题。

### P1-06 容量、长稳和混沌验收

- [ ] 先建立可重复的容量基准，再确定默认 Session、Watch、队列和限流值。
- [ ] 验证 Spring Cloud 应用订阅大量下游服务时按服务复用的 Discovery Agent、Session 和 Watch 容量，必要时演进为单连接多订阅复用。
- [ ] 完成 24 小时和 72 小时长稳，记录内存、线程、RequestStream、SubscribeStream、WAL 和 Snapshot 增长。
- [ ] 测试慢读、丢包、延迟、半开连接、preclose 无 final close、重连风暴和大批慢消费者。
- [ ] 测试 Leader 故障、少数派、失去 quorum、网络分区、磁盘写满/损坏和进程强杀。
- [ ] 证明警告速率有界，连接和流能回收，revision 不倒退，Lease 不被旧 owner 穿透。

### P1-07 备份、恢复、SLO 和告警

- [ ] 备份覆盖管理数据库与每个 Raft Group 的 Snapshot/WAL 策略。
- [ ] 完成全集群恢复、单节点替换、误操作回复和数据一致性校验。
- [ ] 定义可用性、请求 P99、Watch lag、Lease 续租余量、State apply 延迟和 Snapshot/WAL 容量 SLO。
- [ ] 提供默认 Prometheus 告警规则和 Dashboard。

## 5. P2：功能扩展与工程化

### P2-01 多配置原子发布

- [ ] 评估 Config Manifest/Release Set。
- [ ] 只在 State Machine 内实现跨 dataId 原子决策，不使用 SQL 事务冒充。

### P2-02 大配置与 Blob

- [ ] 定义内联内容上限与外部 Blob 分界。
- [ ] 设计 Blob 内容地址、校验和、引用计数、回收和备份。
- [ ] Client Agent 只在验证摘要后更换 last-known-good。

### P2-03 一站式服务治理控制面

该阶段在 2.0 配置/注册发现生产门槛通过后启动，属于新产品能力，不得与已实现的注册发现混为一谈。

#### 阶段 A：Governance 协议与权威状态

- [ ] 定义 Governance Policy ID、Scope、revision、operationId、状态和审计合同。
- [ ] 定义流量策略与稳定性策略的 Protobuf Schema。
- [ ] 建立独立 Governance State Group、Snapshot、ChangeLog 和 Watch。
- [ ] 所有策略支持校验、草稿、发布、灰度、回滚和下线。
- [ ] 不把 Spring、Solon 或某个 RPC 框架的类型写入权威策略模型。

#### 阶段 B：服务拓扑和版本治理

- [ ] 关联 Registry Service/Instance、应用版本、标签、机房、地域和健康状态。
- [ ] 建立服务依赖和最近变更视图。
- [ ] 展示配置、服务版本、实例和治理策略的关联影响面。

#### 阶段 C：流量治理

- [ ] 实现版本、标签、权重、请求特征、机房和地域路由。
- [ ] 实现金丝雀、流量切换、主备和就近访问。
- [ ] 实现策略命中预览和解释接口。
- [ ] 流量策略只在应用 Runtime/数据面适配器执行，Gateway 不代理业务流量。

#### 阶段 D：稳定性 Runtime

- [ ] 通过统一 SPI 实现超时、重试预算、并发隔离、限流、熔断和降级。
- [ ] Spring Cloud/Spring Boot、Solon 先实现，其他 RPC/HTTP 框架通过 SPI 扩展。
- [ ] 每项执行策略有 last-known-good、安全默认、本地指标和执行结果观测。
- [ ] 控制面不可用时不得阻断正常业务请求。

#### 阶段 E：变更与故障闭环

- [ ] 关联变更时间线、应用版本、配置、流量策略和运行指标。
- [ ] 识别错误率、延迟、流量和实例异常与最近变更的相关性。
- [ ] 实现一键止损、一键回滚和带审批的自动回滚。
- [ ] 处置过程完整记录审计和故障报告。

验收：

- [ ] 任何治理策略都可说明“谁发布、对谁生效、为什么命中、当前 revision 是什么、如何回滚”。
- [ ] 业务流量不经过玄同 Gateway，关闭控制面不会直接中断已有业务调用。
- [ ] 从配置/服务变更到指标异常、止损和回滚形成可测试闭环。

### P2-04 前端工程化

- [ ] 将内联大段 JavaScript 拆分为可测试模块。
- [ ] 统一 API 错误、加载、空状态、分页和表单校验组件。
- [ ] 增加端到端浏览器测试，覆盖发布失败、灰度、回滚、权限和 Token 吊销。

### P2-05 发布工程

- [ ] 清理工作树并建立可重现的干净构建。
- [ ] CI 覆盖 JDK 21、H2、MySQL、PostgreSQL、真实 TCP/TLS/Ratis 集成测试。
- [ ] 增加依赖漏洞、许可证、凭据和 SBOM 检查。
- [ ] 完成 Maven 发布、签名、Git Tag 和 GitHub Release 流程。
- [ ] 将 `2.0.0-SNAPSHOT` 切换为正式 `2.0.0` 前必须通过本文档的生产门槛。

## 6. 执行顺序

```mermaid
flowchart LR
    A["P0 认证/会话"] --> B["P0 配置编辑/校验"]
    B --> C["P0 灰度可解释性"]
    C --> D["P0 加密承诺清理"]
    D --> E["P0 配置下线"]
    E --> F["P1 Schema/查询工程"]
    F --> G["P1 集群安全/Raft/TLS"]
    G --> H["P1 容量/长稳/混沌"]
    H --> I["P1 备份/SLO/告警"]
    I --> J["2.0.0 Release Candidate"]
    J --> K["P2 产品扩展"]
```

原则：

- 不再边写边更换底层架构方案。
- P0 先修正产品语义和安全缺口，P1 再做集群生产化。
- 每项任务必须同时交付代码、单测、真实路径集成测试和四份文档的必要更新。
- 不通过增加后台轮询、请求 fan-out、多写或隐藏错误来“修好”一致性问题。

## 7. 生产可用判定

只有同时满足以下条件，才能将玄同 2.0 标记为生产可用：

1. P0 全部完成。
2. P1 全部完成并有可复现的验收报告。
3. 三节点滚动升级、Leader 故障、失去 quorum、网络分区和恢复演练通过。
4. Config 的发布、灰度、终止、转全量、回滚、下线和客户端重连均有真实 TCP/Ratis 端到端测试。
5. Registry 的 Register、Renew、Deregister、Takeover、Expire、generation 重建和 fencing 均有真实 TCP/Ratis 端到端测试。
6. 24/72 小时长稳证明无未界定内存、线程、Socket.D stream、WAL 或 Snapshot 增长。
7. 备份恢复、证书轮换、Token 吊销、用户停用和安全扫描通过。
8. 默认配置、容量阈值、SLO 和告警有明确依据，不是未经验证的数字。
9. 干净工作树从零构建成功，发布产物可校验、可签名、可回滚。

## 8. 当前下一步

下一个开发批次按以下顺序执行：

1. P0-01：停用用户校验、登录限速、无状态会话和 CSRF。
2. P0-02：配置校验、美化、类型化编辑和草稿乐观锁。
3. P0-03：按实例灰度和命中预览。
4. P0-04：删除未实现的加密标记与配置。
5. P0-05：Config tombstone 与已发布配置下线。

这些完成后，再进入 P1 集群生产化和长稳验收。
