# 玄同 2.0 开发计划

> 状态：实施中
>
> 更新日期：2026-07-20
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
- Token 鉴权、作用域、跨 Gateway 主动吊销、集群配额分片/限流和鉴权失败限速。
- Spring Boot、Spring Cloud 2025.1、Solon 和 Solon Cloud 集成。
- 2.0 官方数据库矩阵固定为 H2（本地/测试）和 MySQL（生产）；PostgreSQL 方言代码与手动脚本保留为实验能力，但不进入 CI、生产承诺和发布门槛。
- Spring Cloud ConfigData、DiscoveryClient、ServiceRegistry、自动注册/注销和 LoadBalancer 标准实例供应。
- 配置客户端 API 已明确命名为 `XuantongConfigClient`，不保留含义模糊的旧总入口兼容类。
- 配置管理支持 8 种内容类型、统一服务端校验/转换、1 MiB 限制和 `draftRevision` 并发冲突对比。
- 灰度管理支持精确实例/IP/稳定百分比策略、跨 Gateway 集群命中预览、小样本警告和规则/revision 解释。
- 配置生命周期支持权威 Tombstone、客户端默认值回退、重新发布和历史回滚恢复。
- 管理端主要页面与真实发布结果校验。
- 管理端 BCrypt 登录、用户启停校验、账号/IP 退避、签名无状态会话和 CSRF 防护。
- 真实 Socket.D TCP + Ratis 的 Config/Discovery 端到端测试。

公开 Beta 的 P0 项已全部完成；后续工作分为 P1 生产发布门槛和 P2 产品扩展，不再用“大部分完成”模糊描述。

## 3. P0：公开 Beta 前必须完成

### P0-01 管理端认证和会话安全

状态：**已完成**。

实现结论：

- 管理会话采用 HMAC-SHA256 签名的无状态 Cookie，多 Server 共享 `XUANTONG_ADMIN_SESSION_SECRET`。
- 用户表使用 `securityVersion`；停用、密码/角色变化和 Scope 调整都会使旧会话失效。
- 登录失败状态按账号和 TCP 远端 IP 分别记录在共享 SQL 表中，实现跨 Server 递增退避，不引入 Redis。
- 写 API 同时校验 Origin/Referer 和绑定当前签名会话的双提交 CSRF Token。
- 生产模式强制签名密钥、Secure、HttpOnly、SameSite、有效期和显式登出清理。

实施：

- [x] 登录时强制校验用户启用状态。
- [x] 停用用户、修改密码或降权后使现有会话在有界时间内失效。
- [x] 实现账号 + 远端 IP 登录失败限速、递增退避和审计。
- [x] 管理会话采用签名的无状态 Session/JWT，多 Server 共享签名密钥，不强制引入 Redis。
- [x] 为管理写 API 增加 CSRF Token 校验。
- [x] 强制 Secure、HttpOnly、SameSite、过期时间和登出失效策略。

验收：

- [x] 停用用户无法新登录，旧会话按策略失效。
- [x] 密码爆破测试能触发结构化限流，且不影响其他账号/IP。
- [x] 相同签名密钥和共享用户库的两个 Server 不需要粘性会话。
- [x] 跨站管理写请求和缺失/错误 CSRF Token 的请求被拒绝。

### P0-02 配置编辑与校验能力

状态：**已完成**。

实现结论：

- 支持 `text/string/number/boolean/properties/yaml/json/xml` 8 种内容类型。
- JSON/YAML/XML/Properties 使用统一服务端校验合同，返回错误行、列和原因。
- YAML 使用 SafeConstructor 并限制重复 Key、Alias、深度和大小；XML 禁止 DOCTYPE/外部实体；Properties 检查重复 Key 和转义。
- 管理端提供显式校验、美化和压缩，不在普通保存时偷偷改写内容。
- 单条内联配置限制为 1 MiB，前端延迟校验，保存、发布和灰度入口再次执行服务端校验。
- SQL 草稿新增独立 `draftRevision`，保存使用 CAS；HTTP 409 返回本地和服务器内容及双方 revision。
- 包目录重构后的 Server 入口统一扫描 `cloud.xuantong`，可执行 JAR 能加载配置、注册发现、数据源、状态面和管理组件。

实施：

- [x] 为 text/string/number/boolean/properties/yaml/json/xml 定义服务端校验合同，前端只做快速反馈。
- [x] 增加 JSON/YAML/XML 格式化、压缩和错误定位。
- [x] 增加 Properties 重复 Key、转义和格式校验。
- [x] 恢复数字、布尔、字符串的类型化编辑和预览。
- [x] 草稿保存携带 `expectedDraftRevision`，冲突时返回可比较的差异。
- [x] 编辑器大文本实施 1 MiB 限制和 450ms 延迟校验，转换操作交由服务端执行。

验收：

- [x] 非法 JSON/YAML/XML/Properties 不能保存，也不能进入发布或灰度流程。
- [x] 两个用户同时编辑不会静默覆盖，冲突弹窗展示“我的内容”和“服务器当前内容”。
- [x] JSON 美化、Number/Boolean 类型切换、保存与重新编辑通过真实浏览器验收。
- [x] H2/MySQL/PostgreSQL Schema 兼容测试覆盖 `draft_revision`，服务端内容与并发测试通过。

### P0-03 灰度发布可解释性

状态：**已完成**。

实现结论：

- 新增 `GRAY_CLIENT_INSTANCE / CLIENT_INSTANCE_ID` 精确选择器，可直接选择具体 JAR/Pod/JVM。
- 创建灰度前必须预览；预览与正式 Config State 规则由同一规则工厂构造、调用同一 `ConfigReleaseSelector`，并复用相同 `rolloutKey`。
- 预览经 P1-03 升级为 `CLUSTER_AGGREGATED`，返回 active/stale/truncated/complete 元数据；不完整或当前 Gateway 降级视图拒绝创建灰度预览。
- 百分比预览显示实际 bucket、命中数、期望命中数和小样本警告；不对零命中偷偷向上取整。
- 配置历史展示当前权威 `matchedRuleId/contentRevision/decisionRevision` 计算结果；连接页展示 Gateway 最近成功返回的选择，并明确它不是客户端应用 ACK。
- 可见数和命中数按所有活跃 Gateway 的去重配置客户端计算；预览返回明细最多 1000 条，Gateway 快照明细截断时拒绝预览。
- 删除基于 SQL 投影和 `rolloutId` 重新计算灰度命中的旧 `ConfigRolloutResolver`，仓库只保留 Config State 权威选择路径。

实施：

- [x] 增加 `CLIENT_INSTANCE_ID` 精确选择器的管理 API 和界面。
- [x] 灰度创建前计算当前可见实例的命中预览。
- [x] 预览结果明确标识为“当前 Gateway”或“集群聚合”，不用本地视图冒充集群全量。
- [x] 对“在线实例数 × 灰度比例 < 1”显示小样本警告。
- [x] 连接页和配置详情显示 `matchedRuleId`、当前 content/decision revision。

验收：

- [x] 真实单实例 10% 预览显示期望命中 0.10；不同稳定 bucket 分别得到命中 1 和命中 0，证明未强制取整。
- [x] 真实 Spring Boot JAR 按自动生成的 `clientInstanceId` 精确命中，Watch 刷新到候选值，终止后恢复基线；历史和连接页 revision 一致。
- [x] API、界面和文档均标识集群聚合范围与完整性；跨 Gateway 聚合、过期剔除和同实例去重由 P1-03 测试覆盖。

### P0-04 删除虚假加密承诺

状态：**已完成**。

实现结论：

- 删除未接入发布与客户端链路的配置加密字段、环境变量、AES 工具类和测试，避免把孤立的加解密工具冒充完整安全能力。
- H2、MySQL 和 PostgreSQL 的纯 2.0 Schema 不再保留无效列。
- 文档明确配置内容当前以应用层明文处理，依赖 TLS/mTLS、数据库和主机访问控制保护；未来能力必须基于 KMS/信封加密重新设计。

实施：

- [x] 2.0 首个可用版本删除未实现的字段、配置、工具类和对外承诺。
- [x] 后续如需实现，必须单独设计 KMS/信封加密、密钥版本、轮换、审计和客户端解密边界。

验收：

- [x] UI、API、Schema、配置和部署示例不再出现无效的加密开关。
- [x] 敏感数据安全说明只宣称已验证的 Token 指纹与服务端 TLS/mTLS 能力，并明确客户端参数闭环仍未完成。

### P0-05 配置下线与生命周期

状态：**已完成**。

设计结论：

- Config State 决策显式区分 `ACTIVE / TOMBSTONE`；Tombstone 不携带内容和灰度规则，并推进 decision/event revision。
- Fetch 使用 `MISSING / ACTIVE / TOMBSTONE` 三态合同。网络失败和普通 Missing 保留 last-known-good，只有更高 revision 的 Tombstone 才删除缓存。
- 客户端应用 Tombstone 后移除内存/文件快照、监听回调传 `null`，普通读取回退调用方默认值。
- 下线后重新发布草稿或回滚历史 Release 都会创建新的 ACTIVE 决策；历史内容、Release、operation 和 audit 保留。
- SQL 投影显示 `DRAFT / ACTIVE / TOMBSTONE`。只有从未发布的草稿允许物理删除，物理归档不改变客户端可见状态。
- 配置历史的当前选择接口显式返回 `decisionState/valueState`；Tombstone 不再被显示成 content revision 0 的模糊活动版本，也不能进入灰度预览。
- Spring Boot 客户端在运行期和冷启动首次 Fetch Tombstone 时都会回退 `@ConfigValue.defaultValue`，并把 Tombstone 作为负缓存，避免重复拉取。

实施：

- [x] 实现 Config tombstone，下线生成新 decision/event revision。
- [x] 实现客户端显式缺失合同：清除缓存、通知 `null` 并回退默认值；非权威失败保留 last-known-good。
- [x] 实现历史保留、重新发布/回滚恢复、SQL 生命周期投影和审计。

验收：

- [x] 下线、重建、回滚和客户端重连都有确定性测试。
- [x] 不依赖直接删除 SQL 行来改变客户端可见状态。
- [x] 真实 H2 + 单节点 Ratis + Socket.D TCP + Spring Boot JAR 验证发布、Tombstone、文件快照删除、冷启动默认值、重新发布和历史回滚；Server 重启后从 Ratis Snapshot 恢复，新的无缓存客户端读取到最终权威值。

## 4. P1：生产发布门槛

### P1-01 2.0 Schema 演进

状态：**已完成**。

实现结论：

- 引入 Flyway 11，History 表固定为 `xuantong_schema_history`，H2/MySQL/PostgreSQL 使用方言独立 Migration。
- 只接受不可变的 `2.0.x` Migration；不 baseline、不迁移 1.x，也不接管无版本的预发布 2.0 Schema。
- 启动前验证版本、checksum、成功状态和当前 catalog/schema 边界；失败时在 HTTP、Socket.D 和 State Plane 开放前终止进程。
- H2 固定到 Flyway 已验证的 2.3.232；多 Server 共享数据库时由 Flyway 数据库锁串行 Migration。
- 升级前检查与失败恢复手册已整合到 README，明确禁止手工修改 History/checksum 或把部分 DDL 当成已回滚。

实施：

- [x] 引入 Flyway 显式 Schema 版本管理。
- [x] 只支持 2.0.x 之间的可审计升级，不迁移 1.x。
- [x] 每个 Migration 提供 H2/MySQL/PostgreSQL 自动化验证和可选真实数据库验收入口。
- [x] 实现升级前检查、失败中止和恢复手册。

验收：

- [x] 三种方言首次建库和重复启动幂等测试通过。
- [x] checksum 篡改、1.x/3.x History、完整无版本 Schema 和 SQL Migration 中途失败均会拒绝启动。
- [x] 全仓测试通过；当前全新 H2 执行 3 条 Migration，重复启动执行 0 条；`2.0.0` 升级到 `2.0.2` 执行两条增量，真实 JAR 成功开放管理端、Socket.D 和 Gateway 集群协调。

### P1-02 查询、分页、索引与审计

状态：**已完成**。

实现结论：

- 公共 `PageResult` 固定 `items/page/pageSize/totalItems/totalPages/hasPrevious/hasNext/metadata`，页码从 1 开始，单页限制 1-200。
- 配置、Release、Rollout、服务、Token、用户和审计在数据库侧分页；服务实例从 Registry State 权威 Snapshot 稳定排序后分页，并在 `metadata` 返回 service/revision/onlyAvailable。
- `2.0.1` 为配置、Rollout、服务、资源审计、审计类型、Token、用户状态和用户角色查询增加组合索引；资源坐标、Release revision、operationId、Token 指纹和 Scope 使用唯一约束防重。
- 审计覆盖登录/登出/失败限流、用户与 Scope、Token、Namespace/Group、服务定义与实例摘除、配置草稿、发布、灰度、回滚和下线。
- 审计详情在持久化与返回边界强制脱敏密码、Token、Authorization/Cookie、证书/PEM、KeyStore/TrustStore 和配置正文；审计保存失败不会静默忽略。
- 管理端配置、服务/实例、Token、用户、全局审计和配置历史均消费统一分页合同，全局审计筛选改为服务端查询。

实施：

- [x] 配置、Release、Rollout、服务、实例、Token、用户和审计 API 统一分页合同。
- [x] 按真实查询模式补齐组合索引和唯一约束。
- [x] 审计覆盖登录、权限变更、Token、服务生命周期、配置编辑和生产运维操作。
- [x] 审计详情对 Token、密码、证书和敏感配置做强制脱敏。

验收：

- [x] 公共分页合同、Repository 测试桩、管理页面静态合同和 JavaScript 语法检查通过。
- [x] H2、MySQL 兼容模式和 PostgreSQL 兼容模式执行 `2.0.1`，首次建库、重复启动、checksum 和失败 Migration 测试通过。
- [x] 真实持久化 H2 `2.0.0` 数据库由当前可执行 JAR 升级到 `2.0.1`，健康检查返回数据库、Gateway、Config State 和 Registry State 全部 `UP`，并优雅关停。

### P1-03 跨 Gateway 集群安全边界

状态：**已完成**。

实现结论：

- `2.0.2` 新增 `gateway_runtime_snapshot` 和 `credential_revocation_event`。Gateway 使用 `clusterId + gatewayId + runtimeId` 低频续租并上报有界连接/配额快照，重复且未过期的 Gateway 身份被 fencing 拒绝。
- 集群连接视图只聚合未过期租约，保留 stale/truncated/complete 元数据；同一 `clientInstanceId` 按最近活跃时间、连接代次和 Gateway ID 确定唯一逻辑实例。
- 管理连接页、灰度候选、灰度预览和当前选择详情已切换为集群视图。不完整、截断或当前 Gateway 降级视图不能创建灰度预览。
- Token 停用和持久化吊销事件在一个数据库事务中提交；本 Gateway 的 EventBus 立即断开，其他 Gateway 使用独立 event cursor 主动消费，`authRevalidateIntervalMs` 继续补偿漏通知。
- 配置中的 Session/Tenant/Credential/Watch/Request 限额在开启协调后解释为集群硬上限。Gateway 按活跃成员数和安全余量分配本地额度，Session 接入、Watch 和 Tenant token bucket 只访问进程内存，不执行请求级 SQL。
- 新 Gateway 加入已有集群时等待一个租约 TTL 后开放接入；无法续租的 Gateway 在租约到期后停止新 Session、Watch 和请求。关停不提前删除租约，避免存量进程尚未关闭时其他节点过早扩容。

实施：

- [x] 实现集群级 Session/Tenant/Credential/Watch 配额视图。
- [x] 实现跨 Gateway Token 主动吊销通知，周期复核作为补偿。
- [x] 实现集群级连接实例汇总，为灰度预览和运维观测提供真实全局视图。
- [x] 定义并实现安全余量、本地额度、加入等待和租约过期 fail-closed；普通请求不访问共享数据库。

验收：

- [x] 两 Gateway 聚合、过期快照剔除、同 `clientInstanceId` 去重、连接明细截断和集群灰度选择测试通过。
- [x] Gateway 身份租约冲突、加入等待、两节点额度分片、节点退出后额度重分配和租约过期 fail-closed 测试通过。
- [x] Token 停用/吊销事件事务回滚、两个 Gateway 独立消费同一事件和本地周期复核补偿路径有自动化覆盖。
- [x] 当前 JAR 使用持久化 H2 `2.0.2` 启动，Gateway 集群协调、Socket.D TCP、SmartHTTP 和 `/health` 成功开放；关停语义使用本地停止接入 + 租约自然过期。

### P1-04 Raft 运维与版本演进

状态：**已完成**。

实现结论：

- 新 State 节点支持 `JOIN_EXISTING` 空 Server 启动，不使用本地目标 peer 列表伪造已提交 Raft 配置；加入完成前 State Plane 健康状态不就绪。
- `RatisMembershipManager` 对 Config/Registry 紧凑双 Group 执行相同目标拓扑：Candidate 建立 Listener division、等待 applied index 追平、查询真实 division capability、移除 Leader 前转移领导权、最后使用 `COMPARE_AND_SET` 提升/移除 voter。
- 成员策略只允许 3/5 voter（显式开发模式可单节点），一次变更必须保留当前多数派交集，Node ID 地址不能原地修改；部分 Group 完成后可用同一 current/target 请求续跑。
- State Machine 暴露命令与 Snapshot 可读/写版本范围；Ratis State Envelope 新增本地 capability request/response。管理端不接受调用方自报能力，目标 voter 缺失能力或不覆盖激活版本时 fail-closed。
- 兼容矩阵固定区分 Socket.D Protocol、State Envelope、Config/Registry Command 和 Config/Registry Snapshot；升级必须先扩可读范围、滚完全部 voter、通过 capability gate，再激活新写格式。
- Config/Registry operation 完整结果使用确定性 replay window，默认 `75,000/150,000` 条；达到窗口会淘汰最旧结果而不是永久拒写。窗口外的迟到写继续受 decision revision、generation、Lease epoch/recovery epoch 和 renew sequence fencing。
- Config/Registry ChangeLog 继续按独立容量推进 `compactionRevision`，旧 cursor 返回 `resetRequired` 并重拉 Snapshot。
- 新增系统管理员 API `/api/v2/state-cluster` 与 `/api/v2/state-cluster/membership`，成员变更成功/失败写入审计，本地 State Client 在成功后刷新目标 peer 拓扑。

实施：

- [x] 实现动态成员增删和安全配置变更流程。
- [x] 实现节点 capability gate，防止新协议/快照过早启用。
- [x] 定义协议、State Command、Snapshot Schema 和客户端版本兼容矩阵。
- [x] 完成三节点滚动升级、中途回滚和快照恢复演练。
- [x] 实现 operationId 历史和 ChangeLog 的安全压缩/保留策略。

验收：

- [x] 真实 3 voter + 1 joining node 测试覆盖 Config/Registry 双 Group Listener 追赶、capability gate、CAS 替换、旧 voter 停止后继续提交与查询。
- [x] 真实三 voter 测试覆盖逐节点升级、首节点升级后回滚、持续写入、再次升级、所有节点版本能力确认、强制 Snapshot 和全节点恢复。
- [x] capability 缺失、命令/快照范围不支持、非多数派交集和 State Group 集合变化均有拒绝测试。
- [x] operation replay window 淘汰最旧结果后仍可继续写，窗口内重放保持 `UNCHANGED`；Config/Registry State 与 Server 编译通过。

### P1-05 TLS/mTLS 客户端闭环

状态：**已完成**。

实现结论：

- `TlsOptions` 作为 Java Client、Spring Boot、Spring Cloud、Solon 和 Solon Cloud 的统一合同，暴露 TrustStore、KeyStore、Store/Key 密码、hostname verification 和重载周期；密码不会出现在 `toString`。
- Socket.D Netty 2.6.0 的客户端 `SSLEngine` 不携带 peer host，玄同在标准 CA 链校验之后执行独立的 SAN/CN DNS/IP 校验，并检查证书有效期。
- 客户端按 TrustStore/KeyStore SHA-256 内容摘要检测轮换；新材料可解析后，在有界周期内淘汰旧 Session，并沿用单活动 Gateway 和同兼容池有界顺序切换重建连接。
- Server 继续使用原生 Socket.D TLS，支持 `NONE/WANT/REQUIRE` client auth。mTLS 与 Token 鉴权可同时开启，分别承担传输身份与应用权限。
- 生产轮换固定使用双信任窗口，不把关闭 hostname verification 作为修复方案。

验收：

- [x] Java Client、Spring Boot、Spring Cloud、Solon 和 Solon Cloud 统一暴露 trustStore、keyStore、密码和 hostname verification。
- [x] 支持证书更换后有界重建连接。
- [x] 真实 Socket.D TCP 测试验证 WANT/REQUIRE client auth、缺少客户端证书、错误 CA、主机名不匹配、过期证书和双证书轮换。
- [x] 生产文档只提供 SAN/DNS/IP 修复与双信任轮换，不通过关闭验证解决证书问题。

### P1-06 容量、长稳和混沌验收

状态：**实施中**。

当前已完成：

- [x] 新增可参数化的真实 Socket.D TCP + Ratis 容量基准，输出吞吐、P50/P95/P99、客户端资源增量、Gateway 峰值和 WAL/Snapshot 大小，并在关闭后验证 Session、Watch、pending ACK、在途请求和连接级线程归零。
- [x] 修复 Ratis 3.2.2 默认无限保留 Snapshot：新增 `snapshotRetentionFileCount`，默认保留 3 份，真实连续 5 次 Snapshot 验证保留上限并验证重启恢复最新状态。
- [x] 修复 Ratis Snapshot checksum 伪校验与错误 `.md5` 被 `loadLatestSnapshot()` 吞掉后静默回退 WAL 的漏洞：恢复前重新计算最新 Snapshot 正文 MD5；正文损坏、checksum 缺失或格式错误均拒绝 Division 上线。
- [x] 修复 Socket.D 客户端默认执行器按连接创建 `CPU × 4` 工作线程且 Session 关闭不回收的问题：改为 JVM 级有界共享工作执行器/维护调度器，每连接固定 1 个 Netty I/O/codec 线程。
- [x] `/metrics` 增加请求 accepted/completed/rejected、Session/Watch 打开关闭与峰值、队列峰值、JVM heap/non-heap/线程/Buffer Pool/GC、WAL/Snapshot 文件数和字节数。
- [x] `/health` 将 State 就绪从 Ratis Server 进程存活升级为所有已托管 Division 生命周期健康、Router Group 完整、存储目录可读写且剩余空间不低于水位；完整 checksum 扫描只由 `/metrics` 执行，避免健康探针反复读取大 Snapshot。`/metrics` 增加剩余空间/最低水位和 Snapshot checksum verified/mismatch/unverified/failure 指标并补合同测试。
- [x] 新增真实 Socket.D TCP 慢消费者验收：Watch Reply 未 ACK 时，Gateway 在 ACK deadline 后关闭 Session，并将 Subscription、pending ACK 和 Session 全部回收到零。
- [x] 新增 12 Client 双 Gateway 重连风暴与抖动验收：客户端从 A→B→A→B 连续三次切换，每轮每个 Client 只打开一次目标 Gateway，Watch 从 committed cursor 恢复并依次收敛到 revision 2/3/4，后续每次 Fetch 只产生一个 Gateway 请求，关闭后 Session/Watch/ACK 全部回收。
- [x] 新增真实丢 Reply 验收：Gateway A 已完成 Fetch 且物理 Session 仍打开，但线上的 Reply 被丢弃；客户端按 RPC deadline 将 A 判为不可路由，只顺序打开一次 B 并成功取值，A/B 请求生命周期均无泄漏。
- [x] 新增真实迟到 Reply 验收：A 在客户端超时并切换 B 后才完成响应；迟到结果不会替换健康 B 或触发额外请求，Server 将已关闭 Session 的结果计入 `lateReplyDroppedTotal` 并以 DEBUG 丢弃，不再打印 ERROR。
- [x] 新增真实 `preclose` 无 final close 验收：Client stable Session shell 进入 closing 后不依赖 Socket.D 心跳自愈，玄同在 `closingTimeout` 后强制关闭 A，只打开一次 B 并恢复请求。
- [x] 新增真实单向半开 TCP 验收：在 A 前放置字节转发代理，Hello/Probe 后黑洞 Server→Client 方向但保持两侧 Socket 和 Client→Server 可写；Fetch 到达 A 后无响应，Client 按 RPC deadline 只切换一次 B。
- [x] 新增 24 Client/24 Watch 大批慢消费者验收：全部收到 Watch Reply 后不发送 ACK，Gateway 在统一 deadline 后关闭全部 Session，Subscription、pending ACK、accepted/completed 和 opened/closed 计数全部配平。
- [x] 重跑并增强真实三 voter Ratis 故障验收：Leader 停止后新 Leader 继续提交；Registry Lease 在 Leader 切换后继续续租；再停止一个 follower 时单 voter 不确认写；恢复一个 voter 后先线性读收敛最终状态，再继续写并从恢复节点读取。超时写保持 `commitStatus=UNKNOWN` 语义，不假设一定未提交。
- [x] 新增真实 WAL 文件头损坏验收：节点持久化提交后关闭，破坏 `log_inprogress_*` 文件头，重启触发 Ratis `CorruptedFileException`，节点拒绝上线且 `isHealthy=false`。
- [x] 增加 `storageFreeSpaceMinBytes` 启动水位，Server 默认预留 512 MiB；真实测试将门槛设置为不可满足值，验证 Ratis bootstrap Division 初始化失败会被玄同提升为节点启动失败。`JOIN_EXISTING` 空节点保留“可启动但未就绪”语义。
- [x] 增加运行期存储写入门禁：Config/Registry 的 Socket.D Handler、管理端 State 写和 Registry 过期提案统一经过 `RatisStateRouter.submit()`；目录不可写返回 `STATE_UNAVAILABLE + NOT_COMMITTED`，可用空间低于水位返回 `STORAGE_EXHAUSTED + NOT_COMMITTED`，请求不会进入 Raft。客户端只在同兼容池顺序切换一个 Gateway，Discovery 写复用原 `operationId`。
- [x] 新增独立 JVM 进程强杀验收：确认 3 次 WAL 写入后直接 `destroyForcibly()`，不执行 `close()` 或 Snapshot；同目录重启恢复值 3，并继续提交到 4。
- [x] 显式固定 Ratis WAL corruption policy 为 `EXCEPTION`；真实重启测试覆盖记录头、已提交记录 checksum 损坏，以及在完整已确认 WAL 后追加一个未完成记录字节的 crash-tail。头/记录损坏拒绝上线，合法未完成尾部修剪保留已确认值 2 并继续提交到 3，不发生状态回退；直接截断已确认记录属于数据损坏，不冒充 crash-tail。
- [x] 新增真实 TCP 网络分区验收：Raft 公布地址与本地 bind 地址分离，在三 peer 前放置可切断字节代理；Leader 与 quorum 断路时写不获确认，完整恢复后先按 `UNKNOWN` 读取最终值、再继续提交，三节点最终收敛。
- [x] 新增真实受限 Socket 接收窗口验收：客户端将 `SO_RCVBUF` 限制为 1 KiB 并暂停 Netty `autoRead`，Gateway 对约 512 KiB Watch Reply 最多只保留一个 pending ACK，deadline 后关闭 Session 并回收全部 Watch/ACK。
- [x] Spring Cloud Discovery 演进为单连接多订阅：同一应用的服务级 Agent 复用一个 Socket.D Session 和 JVM 级 2 线程调度器，每个服务保留独立 Snapshot/cursor/Watch；真实 64 下游服务测试得到 1 Session、64 Watch，关闭后全部回收。
- [x] Discovery SDK 增加显式 Lease takeover；接管必须携带权威旧 Lease 作为 fencing 前置条件，只允许同一 `applicationName` 内接管，不做隐式自动抢占。真实 Socket.D TCP 测试验证接管后旧 owner 的续租和注销均返回 `LEASE_FENCED`，新 owner 可继续续租和注销。
- [x] 三 voter Registry Raft 测试在 Leader 故障重选后执行 takeover，再验证旧 owner 的 renew/deregister 不能穿透，新 owner 仍可提交并由线性读确认。
- [x] Config/Discovery 后台重复失败使用 JVM 级 30 秒警告窗口并累计 `suppressedSinceLast`；64 个 Discovery Agent 同时心跳失败每窗口最多产生一条同类 WARN。Config stale-fetch 测试验证 revision 4 失效事件读到 revision 2 时保留 revision 3、Watch cursor 不提交，权威 revision 4 到达后再收敛。
- [x] 正常关闭先关闭 Watch Registration，再中断 Watch Executor；关闭过程中产生的 `InterruptedException` 不触发业务 `onError`、重试退避或“订阅失败” WARN，真实配置集成测试验证停机日志干净。
- [x] 新增 `ControlPlaneSoakTest` 与 `scripts/run-control-plane-load.sh`：支持 duration、Client、Watch、Fetch 并发/速率、发布速率、Payload、采样周期和 JSONL 输出，可直接执行 staircase、24 小时和 72 小时模式；延迟使用有界直方图，周期采样 heap/non-heap、线程、Session、Subscription、ACK、在途请求、队列、WAL 和 Snapshot。
- [x] 新增 `ControlPlaneProductionTopologyLoadTest` 与 Runner 的 `topology/topology-staircase` 模式：在单测试 JVM 内启动 3 个独立 Gateway、3 个独立 State Runtime 和真实三 voter Config Ratis Group，客户端首选地址轮转分布且每实例最多保留 1 个活动 Socket.D Session；中途停止 Gateway A 后，受影响客户端只顺序打开 1 个备用 Gateway并最终恢复。报告逐 Gateway 输出请求/Session/Watch/队列峰值，逐 voter 输出 leader、term、committed/applied index 和 WAL/Snapshot 大小，并在关闭后验证客户端 wait/Watch/SubscribeStream 与 Gateway Session/Subscription/in-flight 全归零。该入口验证生产逻辑拓扑，不冒充目标机器上的拆分进程容量结果。
- [x] 新增 `ControlPlaneSplitProcessTopologyLoadTest`、测试子进程入口和 Runner 的 `split-topology/split-topology-staircase` 模式：驱动 JVM 启动 3 个独立 Server 子 JVM，每个进程包含生产原生 Socket.D Gateway 和一个 Config Ratis voter；中途 `destroyForcibly()` 强杀完整 Gateway/voter 进程，剩余 quorum 必须继续发布并让 Watch/revision、committed/applied index 收敛。客户端任何时刻最多一个活动 Session，故障窗口允许短暂无 Session，最终必须恢复为一个。报告逐进程采集 Gateway/State/存储与有界 WARN/ERROR 样本，并记录被杀 voter 是否为 Leader；follower-loss 要求 Fetch 零失败，leader-loss 只允许不超过故障瞬间 Fetch 并发数的选主窗口瞬时失败，随后必须完全恢复。关闭后客户端和存活 Gateway 生命周期归零。显式运行但不能绑定本机 TCP 时硬失败，不允许 skip 后假绿；同机子进程结果不冒充目标生产规格。
- [x] 增加 `split-topology-matrix/split-topology-matrix-staircase`：自动覆盖 follower-loss 与 leader-loss，按故障角色和 Client 档位使用独立报告文件。拆分报告为多行 `type=sample` 加最后一行 `type=summary`，并把样本明确分为 `pre-crash/post-crash/final` 三阶段；故障前采集三个子 JVM，故障后只采集两个存活子 JVM。每条样本写入后立即 flush，记录全程 `elapsedMs` 与阶段内 `phaseElapsedMs`，所以长测中途失败仍保留已完成的资源轨迹。增长只使用 `post-crash + final` 的固定两节点集合，不跨 3→2 进程数量变化直接做差；final sample 在总期限内等待 Server/Client in-flight 同时归零，持续不归零仍硬失败。
- [x] 新增 `scripts/verify-control-plane-load-report.sh` 并强制接入全部 `split-topology*` Runner：使用 `jq` 复验完整 JSONL 的行序、三阶段节点数、样本/growth 一致性、故障角色、Session/Watch/失败预算、双 voter 收敛、Gateway 生命周期、关闭回收和有界日志。Runner 使用同目录唯一临时文件，只有 Maven 与验收器都成功后才原子发布最终报告，旧报告不能冒充本次证据；Maven 或验收器失败时把已实时落盘的临时报告保留为带 UTC 时间、PID 和随机后缀的 `.failed-*.jsonl`，供长测诊断。验收器已用真实 follower/leader 报告通过，并用角色错配、双 Session、关闭后 Watch 残留三个反例证明会 fail-closed；3/4 Client 的四场景短矩阵阶梯也已全部经过自动门禁。
- [x] 修复拆分负载结束时用 `shutdownNow()` 中断在途发布回执、以及 Raft 首次响应丢失后同一 `operationId` 重试返回 `UNCHANGED` 被误判为发布失败的问题；发布器停止后不再接新任务，已开始的 publish 最多在原请求期限内完成。`APPLIED` 与精确匹配目标 decision/event revision 的幂等 `UNCHANGED` 都视为同一逻辑发布成功，其他状态或 revision 不一致仍硬失败。
- [x] GitHub CI 增加独立 `control-plane-fault-matrix` Job：Ubuntu 24.04 + JDK 21 上执行最小 3 Client follower/leader 拆分进程矩阵，Runner 自动复验报告，并上传两份 JSONL Artifact。该 Job 只防止生产传输、故障合同和验收器回退，不作为目标规格容量或长稳证据。
- [x] 修复 Soak Runner 将无节流容量测试隐藏套用生产租户配额、再要求零失败的语义错误：`fetchRate=0` 明确定义为 `capacity-saturation`，使用显式高测试配额；`fetchRate>0` 定义为 `controlled-lossless`，测试 rate 必须高于目标速率。JSONL 写出模式、实际 rate/burst 和限流总量，任何 `RATE_LIMITED` 都使无损验收失败；配额行为继续由独立 Quota 测试负责。
- [x] 新增 `ControlPlaneTransportMetricsSnapshot`，Soak 周期采样每个客户端的活动 Session、玄同拥有的 in-flight request waits、注册 Watch 和活动 Socket.D SubscribeStream，并在关闭后断言全部归零。Socket.D 2.6.0 不公开内部 RequestStream manager 大小，因此不伪造内部精确计数；RequestStream 泄漏判断组合使用客户端 waits、Gateway accepted/completed、Session/线程回收。
- [x] Soak JSONL 增加 run label、Git revision/clean-dirty、生产传输路径、Java/JVM、Socket.D/Solon/Ratis、OS/CPU/物理内存/最大堆等可复现元数据；周期增加 GC 后存活堆、GC 和 Direct/Mapped Buffer，summary 以显式 warmup 后样本计算资源增长。普通 heap used 不再被单独用作泄漏结论。
- [x] 修复 State Runtime 在 Ratis 正常选主完成前创建客户端并开放处理器、导致启动冒出 `NotLeader/AlreadyClosed` ERROR 的时序缺陷：普通启动等待所有 Config/Registry Group 观察到可用 Leader，本地 Leader 达到 leader-ready、Follower 应用当前任期启动配置条目后再开放业务；`JOIN_EXISTING` 允许空节点等待成员变更，但健康状态保持 DOWN。
- [x] 在 State Runtime 已观察到 leader 后，将它作为内部 Ratis Client 的初始提示，消除首次线性读随机命中 follower 产生的正常重定向 `NotLeader` ERROR；真实换主仍由 Ratis 更新 leader。拆分 JVM 报告中三个进程启动 ERROR 均为 0，强杀 voter 后 Ratis 对失联 peer 的有界 WARN 保留。
- [x] 新增受限卷真实 ENOSPC 验收与 `scripts/run-ratis-enospc-test.sh`：默认测试跳过，macOS 临时创建并自动卸载 256 MiB APFS 镜像；测试拒绝根目录、用户目录、工作区、未标记或超过 1 GiB 的外部卷。请求进入 Raft 后递进填满卷，Ratis WAL index 3 预分配扩容真实抛出 `No space left on device`；客户端保持 `UNKNOWN`，释放空间重启后先 Resolve，只有未提交才复用原 `operationId`，最终只产生一次业务效果。

本机短基准结论（只证明测试工具与资源边界，不作为生产 SLO）：16 Client + 16 Watch + 128 Fetch 全部成功，连接级 Netty 线程从 16 回收到 0，Watch 线程回收到 0，共享 Socket.D 工作线程固定为 8，Gateway accepted/completed 均为 208，无 overload 或 State callback 拒绝。修复 Runner 配额语义后，Apple M2 8 核/24 GB、JDK 21、单 JVM Client + Gateway + 单节点 Ratis 的 30 秒容量阶梯全部通过：16/32/64/128 Client 对应约 15,463/15,709/15,272/14,361 Fetch/s，P99 上界为 2/10/20/20 ms，四档均 0 Fetch 失败、0 配额拒绝、0 revision 回退、0 发布失败，关闭后 Session/Subscription/在途请求归零。吞吐在 16–32 并发附近进入平台区；该短阶梯不替代目标生产拆分拓扑和 24/72 小时长稳。另以 500 Fetch/s 完成 `controlled-lossless` 冒烟，实测 1,500/1,500 成功、P99 上界 2 ms、0 限流。warmup 口径完成后又以 8 Client/8 Watch、1,000 Fetch/s 运行 60 秒：59,999 次 Fetch 与 11 次发布全部成功、P99 1 ms、0 限流；30.012–55.007 秒增长窗口内 GC 后存活堆 -3,640 字节、线程/Watch/SubscribeStream 均无增长，关闭后全部流生命周期计数归零。逻辑生产拓扑冒烟在单测试 JVM 内以 3 Gateway + 3 voter、6 Client/6 Watch、100 Fetch/s 运行 8 秒并中途停止 Gateway A：800 次 Fetch、3 次发布全部成功，P99 上界 5 ms，0 限流、0 revision 回退；每个受影响客户端只打开一次备用地址，三个 voter 最终收敛。2026-07-21 的拆分进程短验收显式覆盖 follower-loss 与 leader-loss：3 个独立 Server 子 JVM、3 Client/3 Watch、30 Fetch/s 运行 5 秒。Follower-loss 为 150 Fetch/0 失败、P99 上界 10 ms；Leader-loss 为 65 Fetch/1 次选主窗口瞬时失败，低于并发预算 3，P99 上界 10 s、最大约 8.0 s。两次均完成 3 次发布、0 发布失败、0 revision 回退，剩余两个 voter committed/applied index 分别收敛到 8/8 与 10/10，客户端和存活 Gateway 生命周期归零。Leader-loss 周期样本记录到一个客户端短暂无 Session，但全程没有双 Session，最终 Session/Watch/SubscribeStream 全部恢复。以上运行只验证报告工具、进程隔离、资源采样和故障合同，不替代目标机器拆分进程矩阵阶梯与长稳。

- [x] 建立可重复的短时容量基准与资源回收断言。
- [x] 建立可参数化阶梯压测及 24/72 小时长稳执行入口和有界增长报告格式。
- [x] 建立单测试 JVM 的三 voter、多 Gateway 逻辑生产拓扑容量与顺序故障切换入口。
- [x] 建立三独立 Server 子 JVM 的 Gateway/voter 整进程强杀、follower/leader 自动故障矩阵、剩余 quorum、客户端收敛、周期资源增长与归档报告独立复验入口，并让端口权限不足或报告不一致 fail-closed。
- [x] 建立 `split-topology-soak24/split-topology-soak72` 拆分进程长稳入口；follower-loss 与 leader-loss 必须分别运行，报告实时 flush、失败保留 partial、成功才原子发布。
- [ ] 在目标生产规格上完成阶梯压测后，确定默认 Session、Watch、队列和限流值。
- [x] 验证 Spring Cloud 应用订阅大量下游服务时的 Discovery Agent、Session、Watch 和线程容量，并完成单连接多订阅复用。
- [ ] 完成 24 小时和 72 小时长稳，记录内存、线程、RequestStream、SubscribeStream、WAL 和 Snapshot 增长。
- [x] 测试 Socket 接收窗口受限和暂停读取时的真实慢读背压。
- [x] 测试 Leader 故障、少数派不确认写、失去 quorum 和恢复 quorum。
- [x] 测试 Raft 真实 TCP 网络分区与恢复。
- [x] 测试 WAL 文件头、已提交条目 checksum 损坏和未完成 crash-tail 恢复。
- [x] 使用可注入空间探针验证运行期低水位/目录不可写时写请求在 Raft 前 fail-closed，并验证水位恢复后无需重启即可重新放行。
- [x] 在独立受限卷测试写入已进入 Raft 后发生的真实运行中 ENOSPC；空间探针只覆盖前置门禁，真实 WAL I/O 故障保持 `UNKNOWN` 并经过重启 Resolve 收敛。
- [x] 证明警告速率有界，连接和流能回收，revision 不倒退，Lease 不被旧 owner 穿透。

### P1-07 备份、恢复、SLO 和告警

- [x] 备份覆盖管理数据库与每个 Raft Group 的 Snapshot/WAL 策略。
- [x] 使用文件 H2 完成管理数据库导入、业务误操作逻辑恢复、跨存储一致性校验与 Raft quorum 独立归档恢复的全集群演练。
- [ ] 完成正式 SLO 校准：可用性、请求 P99、Watch lag、Lease 续租余量、State apply 延迟和 Snapshot/WAL 容量阈值仍需目标规格数据支撑。
- [x] 提供默认 Prometheus 告警规则和 Dashboard。

已完成的基础设施：

- [x] 增加 `POST /api/v2/state-cluster/snapshot`：按目标 voter 对 Config/Registry Group 分别强制 Snapshot，返回 Group、serverId、logIndex，并记录成功/失败审计；目标节点不在配置 voter 集合时在发起任何 Snapshot 前拒绝。
- [x] 增加数据库 dump、离线节点备份、归档校验和空目录恢复脚本；备份包含完整 Ratis WAL/Snapshot/raft-meta、数据库备份和 Snapshot API 结果，分层校验 SHA-256 与 Snapshot MD5，恢复强制匹配原 nodeId。
- [x] 使用临时双 Group 数据完成脚本级备份、校验、恢复和 State/数据库逐字节比对。
- [x] 新增真实 3 voter 离线节点恢复验收：对 `state-3` 强制 Snapshot 后停机，完整复制节点目录，删除原目录，再以相同 nodeId 恢复；节点恢复已确认值 3，集群继续提交值 4，三个 voter 最终全部收敛。
- [x] 新增完整集群丢失后的 Raft quorum 恢复验收：分别为 `state-1/state-2` 强制 Snapshot 并创建独立离线目录归档，删除三个 voter 原目录后只恢复两个原 nodeId；两个归档重新形成 quorum、恢复值 3 并继续提交值 4，第三个空节点随后重新追平，最终三个 voter 继续提交并收敛值 5。
- [x] 增加 H2/MySQL/PostgreSQL 显式数据库导入脚本：拒绝覆盖 H2、拒绝非空 MySQL/PostgreSQL，凭据不进入参数；H2 成功导入与覆盖拒绝已验证。
- [x] 增加恢复后一致性接口：Config State 输出不含正文的 decision/content digest，Registry State 输出完整 service lifecycle，Server 使用带 revision/applied-index fence 的分页线性一致读核对 SQL resource/release/rollout/service projection；状态持续变化时 fail-closed，报告只读且最多返回 1,000 条问题。
- [x] `FullClusterRecoveryDrillTest` 与 `scripts/run-full-recovery-drill.sh` 已升级为 H2/MySQL 双后端联合演练：真实启动 3 voter 和 Config/Registry 双 Group，先以新 decision revision/service generation 修复错误发布与服务删除，再备份数据库和两个独立原 nodeId；删除活动数据库与全部 State 后只恢复 quorum，验证跨存储报告，重建第三空节点并继续 Config/Registry 线性写。Runner 未配置 MySQL 时只允许 MySQL 因配置缺失跳过；配置 MySQL 后强制要求 `tests=2/failures=0/errors=0/skipped=0`，端口权限等环境问题不能以 Maven 假绿结束。H2 单路径和远程 MySQL 9.5.0 双路径均已真实通过，并已配置为独立 GitHub Actions 步骤。
- [x] 新增 `ExternalDatabaseBackupRestoreDrillTest` 与 `scripts/run-external-database-recovery-drill.sh`：只创建固定安全前缀的临时 source/target 库，真实执行 MySQL dump/import、Flyway/业务 canary 比对和非空目标拒绝，并在 finally 删除自建数据库；GitHub Actions 固定 Ubuntu 24.04，配置 MySQL 8.4 + Client 8.0 在独立步骤中运行。Runner 精确核对 MySQL 必须真实执行、未配置的 PostgreSQL 按产品决策跳过，不能把配置后的测试跳过当作成功。外部命令超时会终止父脚本与数据库子进程，清理最多重试 3 次，并保留原始失败而不是被 cleanup 异常覆盖。
- [x] 数据库 dump 先写入目标目录内的唯一临时文件，成功且非空后再原子重命名；命令失败不会留下可被误认成有效备份的半截文件，目标为悬空符号链接时同样拒绝覆盖。
- [x] `DatabaseRecoveryScriptTest` 将 H2 完整发布、已有目标/悬空链接拒绝、外部 dump 失败清理和 TERM 下父子进程共同回收固化为 3 项常规回归测试。
- [x] 使用经过 MySQL Release Engineering PGP 签名验证的 MySQL 9.5.0 ARM64 客户端，在远程 MySQL 9.5.0 完成真实 Flyway、canary、`mysqldump → mysql`、源/目标比对、非空目标拒绝和最终临时库零残留；修复后的成功路径再次执行耗时 31.12 秒，MySQL 用例 1 项真实通过，未配置 PostgreSQL 1 项按预期跳过。
- [x] 在远程 MySQL 9.5.0 使用同一 `FullClusterRecoveryDrillTest` 完成数据库恢复、两个独立 voter 归档恢复 Config/Registry quorum、跨存储一致性报告、第三 voter 追平和恢复后继续写；H2/MySQL 两项 0 跳过通过，结束后安全前缀临时库为 0、无遗留客户端进程。
- [x] 使用远程 MySQL 9.5.0 按 GitHub Actions 顺序完成一次本地 CI 等价演练：全仓真实基础设施回归耗时 3 分 32 秒，Gateway TCP、Socket.D TLS/mTLS、三 voter Ratis、Spring Cloud 容量和 MySQL Schema 均实际执行；随后脚本恢复与 H2/MySQL + Raft 联合恢复继续通过。CI 新增 Surefire 报告门禁，强制这些用例不得因端口、数据库或环境假设跳过，并启用 MySQL 只读 smoke。
- [ ] 取得 MySQL 恢复测试和联合恢复测试的 CI 首次绿灯，并在目标生产数据库版本、网络、备份介质和完整 Server/Gateway 拓扑的隔离部署中复演；单机测试与通用远程 MySQL 验收不能替代该门槛。PostgreSQL 不进入 2.0 发布门槛。
- [x] Gateway 请求、Watch ACK 和 State apply 使用 JVM 内固定桶直方图并导出标准 Prometheus histogram；默认规则提供 P99、overload、可用性、慢消费者、迟到 Reply、磁盘和 Snapshot checksum 告警，Grafana Dashboard 覆盖主要运行面。
- [x] 新增独立 `xuantong-probe`：每个样本新建原生 Socket.D TCP/TLS 连接，完成 Hello + Probe Request/Reply；支持 Config/Discovery、`--once/--serve`、`/metrics`、`/health`、双 Gateway 有界顺序切换和 TLS/mTLS，且不输出 Token/密码。默认规则增加 Probe 失败、样本陈旧、RPC 慢和 30 天可用率记录，Dashboard 增加外部可用性与时延面板。
- [x] Discovery SDK 按本地注册 Agent 提供固定内存的 Lease 续租余量直方图、成功/失败计数、最近请求耗时、epoch/renew sequence/expiry；余量使用上一 Lease expiry 与 Registry State mutation 的服务端提交时间计算。原生 Client 输出 Prometheus 文本，Spring Cloud Manager 与 Solon Cloud Service 暴露结构化快照；标签不包含 leaseId 或凭据。默认规则增加 renewal margin P01、续租失败和低余量告警，Dashboard 增加 Lease 安全余量面板。
- [ ] 在真实部署网络持续采集 30 天 Probe/Lease 数据；请求/State/Watch/Probe/Lease 阈值还要用目标生产规格与 24/72 小时报告校准。

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
- [ ] CI 覆盖 JDK 21、H2、MySQL、真实 TCP/TLS/Ratis 集成测试；PostgreSQL 不属于 2.0 官方 CI 矩阵。
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

1. P1-06：完成容量、长稳和混沌验收。
2. P1-07：完成备份、恢复、SLO 和告警。

P1-06 当前最先执行的是：在目标生产规格或等价独立机器上运行 `split-topology-matrix-staircase`，随后执行 24/72 小时拆分进程长稳；同一开发机的 3 子 JVM 短测和短窗口 growth 不作为该项完成证据。

这些完成后才能进入 2.0.0 Release Candidate。
