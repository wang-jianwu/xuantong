# Socket.D + Multi-Raft 微服务控制面技术方案

> 状态：Proposed  
> 日期：2026-07-16  
> 设计类型：全新架构，不受现有项目实现约束  
> 目标产品：注册中心、服务发现、配置中心、配置灰度与控制面长连接  
> 关联背景：[Socket.D 目标架构设计](socketd-target-architecture-design.md)

## 1. 方案摘要

本方案将控制面拆分为三类职责：

1. Socket.D 负责客户端长连接、请求响应、Watch 和通知。
2. Raft 负责权威状态的多数派提交、日志复制、快照和故障选主。
3. 业务状态机负责定义配置版本、灰度规则、注册租约、fencing、Watch 和回滚语义。

推荐架构：

> Client Agent + 无状态 Socket.D Gateway + 独立 Multi-Raft State Plane。

核心技术选择：

| 链路 | 技术选择 |
|---|---|
| 微服务客户端到 Gateway | 原生 Socket.D over TCP + Netty + TLS/mTLS |
| 受限网络兼容入口 | 原生 Socket.D over WSS + Netty，可选 |
| 管理控制台 | HTTPS/REST |
| Gateway 到 State Node | 同进程直接调用，分离部署后使用版本化内部 RPC |
| Raft 节点间通信 | 成熟 Raft 实现自带的 TCP Transport，独立端口 |
| 业务消息编码 | Protobuf 或等价的版本化二进制 Schema |

本方案不使用 Socket.D Broker、Session attr、客户端 Multi-Broker fan-out 或节点间 Full-Mesh 广播来承担分布式一致性。

## 2. 设计目标

### 2.1 功能目标

- 服务注册、续租、注销、发现快照和增量 Watch。
- 配置读取、订阅、发布、灰度、回滚和历史审计。
- 一个客户端连接同时承载注册发现和配置业务。
- 支持节点滚动升级、故障切换和安全排空。
- 支持紧凑三节点部署和 Gateway/State Node 分离扩容。
- 支持单地域多可用区部署和跨地域异步灾备。

### 2.2 一致性目标

- 配置发布和回滚经过多数派提交后才算成功。
- 注册、续租和注销具有明确 lease、leaseEpoch 和 fencing 语义。
- 可重试业务写通过 operationId 保证单一业务效果；续租使用单 lease 单调 renewSequence，内部时钟和过期命令使用单调/CAS 语义。
- Push、Pull、重连修复和定期校准对同一客户端产生一致配置结果。
- Watch 可以从 revision 恢复；过旧 revision 明确要求重新获取快照。
- 客户端只向前推进 revision，不把陈旧不存在当成权威不存在。

### 2.3 可用性目标

- 单个 Gateway 故障不影响权威状态。
- 单个 State Node 故障不影响多数派写入。
- 少数派节点拒绝权威写入，不产生脑裂。
- 客户端使用一个活动 Gateway，在总 deadline 内有界顺序切换。
- 控制面短时不可用时，客户端继续使用 last-known-good 配置和有明确 maxStale 的服务视图。

### 2.4 非目标

- 不从零实现 Raft 选举、WAL、日志复制、快照和成员变更。
- 不复制真实 Socket.D Session。
- 不承诺跨 Raft Group 的廉价全局顺序。
- 不承诺 Watch 恰好一次投递。
- 不在第一版实现跨地域强一致 Raft。
- 不允许没有幂等语义的写操作自动跨节点重试。

## 3. 架构原则

### 3.1 Session 与事实分离

Session 对象、Session attr、订阅集合和待发送队列只属于当前 Gateway。它们是连接状态，不是集群事实。

用于 fencing 的标量所有权信息是例外：leaseId、leaseEpoch、ownerGatewayId 和 ownerConnectionGeneration 必须进入 Raft。Gateway 本地 Session 只缓存这些权威字段，不能自行修改。

Raft 状态中只能保存可序列化、可重放、与具体网络连接无关的业务状态，例如：

- 配置内容和发布决策；
- 服务实例描述；
- leaseId、leaseEpoch、renewRevision 和租约截止水位；
- 幂等记录；
- Watch 变更记录；
- ACL、命名空间和分片映射。

### 3.2 传输与共识分离

客户端 Socket.D 流量和 Raft 内部通信必须使用：

- 不同端口；
- 不同 EventLoop 或线程池；
- 不同队列；
- 不同超时；
- 不同监控指标。

客户端推送风暴不能影响 Raft 心跳，Raft 快照安装也不能阻塞 Socket.D 连接心跳。

### 3.3 配置与注册分离

配置发布是低频写、高频读；注册续租是高频写、TTL 驱动。二者不能竞争同一个 Raft Group、WAL 队列和 Apply 线程。

### 3.4 Push 只负责加速

Push 丢失、重复、乱序或客户端离线都不能破坏最终正确性。正确性来自：

- 权威 revision；
- 可恢复 Watch；
- 客户端 Pull；
- 重连校准；
- last-known-good。

## 4. 总体架构

~~~mermaid
flowchart TB
    APP["微服务实例"]
    SDK["Client Agent<br/>一个活动连接 + 本地缓存<br/>Lease / Revision / RPC Health"]
    APP --> SDK

    subgraph GW["Gateway Plane：可水平扩展"]
        G1["Gateway A<br/>原生 Socket.D TCP / Netty<br/>本地 Session / Subscription"]
        G2["Gateway B<br/>原生 Socket.D TCP / Netty<br/>本地 Session / Subscription"]
        GN["Gateway N<br/>原生 Socket.D TCP / Netty<br/>本地 Session / Subscription"]
    end

    SDK ==>|"活动连接：请求、续租、Watch"| G1
    SDK -.->|"同兼容池内热备 / 有界切换"| G2
    SDK -.->|"候选地址"| GN

    ADMIN["管理控制台 / OpenAPI"] --> HTTP["HTTPS 管理面"]

    subgraph CORE["Consensus Plane：固定 3/5 个 State Node，独立内网"]
        CFG[("Config Raft Groups<br/>内容、发布决策、灰度规则<br/>Config ChangeLog")]
        REG[("Registry Raft Groups<br/>实例、Lease、Epoch、TTL<br/>Registry ChangeLog")]
    end

    HTTP --> CFG

    G1 -->|"命令 / ReadIndex / Watch"| CFG
    G1 -->|"注册 / 续租 / 发现"| REG
    G2 --> CFG
    G2 --> REG
    GN --> CFG
    GN --> REG

    CFG -.->|"可恢复配置变更流"| G1
    CFG -.->|"可恢复配置变更流"| G2
    CFG -.->|"可恢复配置变更流"| GN
    REG -.->|"可恢复注册表变更流"| G1
    REG -.->|"可恢复注册表变更流"| G2
    REG -.->|"可恢复注册表变更流"| GN
~~~

Gateway 与 State Plane 是逻辑边界。第一版可以物理共置在同一批三节点中，连接量增加后再独立部署。

## 5. 部署形态

### 5.1 紧凑三节点

第一版推荐：

~~~text
Node A = Socket.D Gateway + Config Replica + Registry Replica
Node B = Socket.D Gateway + Config Replica + Registry Replica
Node C = Socket.D Gateway + Config Replica + Registry Replica
~~~

要求：

- 三个节点分布于三个故障域或可用区；
- 客户端端口、管理端口、Raft 端口分离；
- Config 与 Registry 分别使用独立 WAL、快照目录和执行队列；
- Config Leader 与 Registry Leader 可以位于不同节点；
- 节点二进制相同，但模块资源配额独立。

### 5.2 分离扩容

连接规模增长后：

~~~text
N 个无状态 Socket.D Gateway
        +
固定 3/5 个 State Node
~~~

Gateway 可以独立扩缩，不自动成为 Raft voter。State Node 数量保持奇数，通过成熟 Raft 实现进行成员变更。

### 5.3 跨地域

- 注册中心保持地域内强一致，服务实例默认只在本地域注册。
- 配置内容和发布决策通过异步复制进入其他地域。
- 每个配置命名空间只有一个权威 homeRegion；异步副本默认只读，禁止双地域同时发布。
- 灾备提升必须先在入口层 fencing 旧地域，再写入更高 authorityEpoch；没有外部仲裁或人工 fencing 时，不承诺自动跨地域无脑裂切换。
- 不在高延迟 WAN 上直接拉伸一个 Raft Group。
- 地域故障切换使用独立的灾备策略、clusterId 和 transportGeneration。

## 6. 通信协议设计

### 6.1 客户端传输

默认：

~~~text
Socket.D application protocol
  → native TCP transport
  → Netty
  → TLS / mTLS
~~~

选择 TCP 的原因：

- 微服务客户端通常位于内网或 Kubernetes；
- 不需要浏览器兼容；
- 帧和连接语义更直接；
- 避免 HTTP Upgrade、Ingress 和代理空闲超时；
- 更容易控制背压、连接队列和写缓冲；
- 适合高频续租和长期 Watch。

### 6.2 WebSocket 兼容入口

只有在以下场景提供 WSS：

- 浏览器客户端；
- 企业网络仅允许 80/443；
- 跨公网代理；
- 多语言 SDK 只能稳定使用 WebSocket。

WSS 必须使用原生 Socket.D WebSocket Transport 和 Netty 独立入口，不经过普通 HTTP Server 的 WebSocket bridge。

TCP 和 WSS 属于两个基础设施兼容池。一次请求尝试不能同时进入两种 Transport，自动故障切换也不能跨池。

### 6.3 Raft 内部通信

Raft 使用成熟实现提供的内部 TCP Transport：

- 独立内网地址；
- 独立端口；
- mTLS；
- 独立 EventLoop；
- 独立重试和流控；
- 不经过 Gateway；
- 不使用客户端 Socket.D Session。

### 6.4 管理面

配置发布、回滚、审计和集群管理使用 HTTPS/REST。服务客户端没有发布配置或修改 Raft 成员的权限。

### 6.5 消息编码

Socket.D 负责 framing，业务 payload 使用 Protobuf 或等价的版本化二进制 Schema，不使用 Java 对象序列化。

公共 Envelope：

~~~text
protocolVersion
clusterId
transportGeneration
requestId
operationId
traceId
tenant
namespace
remainingBudgetMs
revisionType
groupId
knownRevision
minRevision
payloadType
payload
~~~

可由客户端重试的业务写必须携带 operationId。Registry Renew 使用 renewSequence；AdvanceLeaseClock 和 ExpireBatch 是内部命令，分别依赖单调 tick 与条件 CAS。

Client Agent 使用本地单调时钟维护整次逻辑调用的总 deadline，每次尝试只发送 remainingBudgetMs。Gateway 对预算设置上限并转换为自己的单调截止时间，在排队、转发和重试时持续扣减，不能在切换 Gateway 后重新开始计算。

revisionType 的 Wire 枚举固定为：

| revisionType | 作用域 | 可比较范围 |
|---|---|---|
| CONFIG_DECISION | 单个 Config Group + configKey | 同一 groupId、同一 configKey 的发布决策 |
| CONFIG_EVENT | 单个 Config Group | 同一 groupId 的 Config Watch 和 Snapshot 水位 |
| REGISTRY | 单个 Registry Group | 同一 groupId 的 Registry Snapshot、ChangeLog 和 Watch 水位 |

contentRevision 是不可变内容标识，不作为 knownRevision/minRevision 的游标类型。knownRevision 和 minRevision 必须同时携带 revisionType 与统一字段 groupId；CONFIG_DECISION 还必须携带 configKey。禁止跨类型、跨 Group 或跨 configKey 比较 revision。

### 6.6 事件与交互模型

| 事件 | Socket.D 模型 | 说明 |
|---|---|---|
| system/hello | Request | 握手、能力、clusterId 和版本校验 |
| system/probe | Request | 验证真实请求响应链路 |
| registry/register | Request | 注册并返回 leaseId、leaseEpoch、revision |
| registry/renew | Request | 续租，可由 Gateway 合并为 RenewBatch |
| registry/deregister | Request | 条件注销 |
| registry/resolve-operation | Request | 线性一致查询 operationId 是否已提交及其原结果 |
| registry/lease-state | Request | 线性一致读取当前 leaseEpoch、recoveryEpoch 和 owner fencing 状态 |
| registry/snapshot | Request | 获取服务视图快照 |
| watch/open | Subscribe | 按 Group 原子返回 Bootstrap Snapshot，并订阅 snapshotRevision 之后的变更 |
| config/fetch | Request | 拉取适用配置 |
| config/invalidated | Reply/Watch | 带游标的配置失效通知 |
| registry/delta | Reply | 服务视图增量 |
| watch/reset-required | Reply | revision 已压缩，要求重拉 |
| system/drain | Send | 节点排空通知 |

推荐只维护一个可恢复的多路 Watch，而不是为每个配置键和服务名创建一个永久流。

### 6.7 响应状态

至少定义：

| 状态 | 语义 |
|---|---|
| OK | 已完成 |
| NOT_MODIFIED | 客户端 revision 已是最新 |
| NOT_LEADER | State Node 内部响应：当前节点不是 Leader，返回 groupId 和内部 leaderHint；Gateway 默认不向客户端透传 |
| STATE_UNAVAILABLE | Gateway 在路由或选主超时内无法访问可用 Leader |
| NO_QUORUM | 已知 Leader 的 proposal 在有界 proposal timeout 内无法获得多数派 |
| DRAINING | Gateway 正在排空，可重试 |
| STALE_REPLICA | 指定 groupId/revisionType 的本地水位低于 minRevision |
| REVISION_COMPACTED | Watch 游标过旧，需要重新拉快照 |
| OPERATION_CONFLICT | 同一 operationId 对应的业务请求或目标 owner 已发生变化 |
| LEASE_FENCED | leaseEpoch/recoveryEpoch 已落后，必须重新同步租约状态 |
| LEASE_EXPIRED | 权威逻辑 tick 已到截止水位，旧 lease 不可复活 |
| UNAUTHORIZED | 认证或授权失败 |
| RATE_LIMITED | 超过租户或连接配额 |

State Plane 一致性错误必须携带 groupId、revisionType、observedRevision、retryable、retryAfterMs，并在内部 RPC 可用时携带 State leaderHint。Gateway 必须在 remainingBudgetMs 内消化 NOT_LEADER、更新 Group 路由并重新请求；不得向客户端泄露 State Node 内网地址。内部重路由预算耗尽后，对客户端返回 STATE_UNAVAILABLE。UNAUTHORIZED、RATE_LIMITED、连接级 DRAINING 等没有目标 Group 的错误不强制携带 groupId 和 observedRevision。

Gateway 无法瞬时判断全局 quorum，只能在 election/routing timeout 后返回 STATE_UNAVAILABLE；只有已知 Leader 才能在 proposal timeout 后返回 NO_QUORUM。不能把这些状态或 draining 表现为无限普通请求超时。

## 7. Gateway 设计

### 7.1 职责

- Socket.D 连接和协议处理；
- mTLS、token 和租户鉴权；
- 本地 SessionDirectory；
- 本地 SubscriptionIndex；
- RPC 健康和 drain 状态；
- 请求路由到 State Node；
- Watch 恢复和本地推送；
- 限流、配额和背压；
- 日志、指标和 Trace。

### 7.2 非职责

Gateway 不负责：

- 权威配置状态；
- 权威服务注册状态；
- 独立判断 lease 过期；
- 复制真实 Session；
- 在无 quorum 时接受注册或续租成功；
- 使用本地 Map 作为集群注册表。

### 7.3 线程和队列

Netty EventLoop 只执行：

- 网络读写；
- frame 编解码；
- 轻量协议校验；
- 投递到有界业务队列。

业务线程池至少分为：

1. Lease/Registry 高优先级队列；
2. Config 读取队列；
3. Watch 推送队列；
4. Admin 管理队列；
5. State RPC 回调队列。

Raft Apply 线程不能直接写 Socket.D Session。Apply 只写业务 ChangeLog，再由独立 Dispatcher 推送。

### 7.4 本地 Session 状态

~~~text
sessionId
clientInstanceId
bootId
connectionGeneration
authenticatedIdentity
leaseId / leaseEpoch
subscriptions
lastSuccessfulRpcTime
drainDeadline
~~~

Session 对象、认证上下文和订阅队列不进入 Raft。leaseId、leaseEpoch、ownerGatewayId 和 ownerConnectionGeneration 在这里只是本地缓存，其权威值位于 Registry Raft Group。客户端切换 Gateway 后重新认证、原子执行 TakeoverAndRenew，并从 revision 恢复 Watch。

## 8. Multi-Raft State Plane

### 8.1 Raft 实现原则

- 集成成熟 Raft 实现；
- 不自研选举、复制、WAL、快照和联合共识；
- 写请求只有多数派 commit 且接收并回复该命令的 Leader 已 apply 后才能回复成功；
- 状态机 apply 必须确定性执行；
- apply 中禁止读取本地时间、随机数、网络和 Socket.D Session；
- 客户端可重试的业务命令携带 operationId；
- 业务幂等键至少包含 tenant、authenticatedPrincipal 和 operationId；
- 状态机保存有时限的 operationId → result 幂等记录，保留期覆盖最大重试和迟到消息窗口；
- Renew 使用单 lease 单调 renewSequence；
- Expire 使用 expectedLeaseEpoch、expectedRecoveryEpoch、expectedLeaseState、expectedDeadlineTick 和 expectedRenewRevision 做 CAS；
- AdvanceLeaseClock 只接受 Leader 提交的单调 nextTick。

### 8.2 Group 划分

| Group | 权威数据 | 流量特征 |
|---|---|---|
| Config Group | 内容、发布决策、灰度规则、配置 ChangeLog | 低频写、高频读 |
| Registry Group | 实例、Lease、Epoch、TTL、注册表 ChangeLog | 高频写、TTL 驱动 |
| Meta Group，可选 | 租户、ACL、协议策略、分片映射 | 低频、强一致 |

第一版使用一个 Config Group 和一个 Registry Group。规模扩大后：

- Registry 按 tenant + namespace + serviceName 固定虚拟分片；
- 同一服务的所有实例必须落在同一 Group；
- Config 按命名空间或发布单元分片；
- 跨 Group 发布使用 Manifest，不引入隐式分布式事务。

### 8.3 读取语义

API 必须明确标识：

- Linearizable；
- Monotonic；
- Bounded Stale；
- Offline Cache。

推荐：

- 管理端发布、回滚和写后验证：Leader/ReadIndex 线性一致；
- 配置 Fetch：携带 revisionType=CONFIG_DECISION、groupId、configKey 和 minDecisionRevision，本地达不到则代理 Leader 或返回 STALE_REPLICA；
- 服务 Snapshot：返回 Registry Group 的 groupId 和 registryRevision；
- Registry Watch 的 eventRevision 与 registryRevision 使用同一 Registry Group 序列；
- Config Watch 使用对应 Config Group 的 eventRevision；
- 所有 Watch 订阅均从 Snapshot 返回的 revision 下一条开始；
- 陈旧读取必须返回 stale=true 和实际水位。

### 8.4 快照和日志压缩

Raft 快照包含：

- 对应 Raft Group 的权威配置状态或权威注册状态；
- lease、leaseEpoch、recoveryEpoch、acceptedRenewSequence 和 renewRevision；
- 幂等记录的有效部分；
- ChangeLog 的 compactionRevision、event high watermark 和仍在保留窗口内的有界事件后缀；
- appliedIndex 和校验和。

快照不包含：

- Session；
- 订阅者对象；
- Socket Channel；
- Gateway 推送队列。

Raft Log 会压缩，因此客户端 Watch 不能直接依赖任意历史 Raft Log。状态机维护有界业务 ChangeLog；快照安装后必须原样恢复保留事件后缀、压缩边界和 Watch 水位。若实现不在快照中携带该事件后缀，则所有早于 snapshotRevision 的游标都必须明确视为已压缩，不能假装可恢复。

## 9. 注册中心设计

### 9.1 数据模型

~~~text
ServiceInstance {
  tenant
  namespace
  serviceName
  instanceId
  bootId
  endpoints
  metadata
  weight
  leaseId
  leaseEpoch
  ownerGatewayId
  ownerConnectionGeneration
  acceptedRenewSequence
  renewRevision
  expiresAtLeaseTick
  leaseState
  recoveryEpoch
  recoveryDeadlineTick
  metadataRevision
  registryRevision
}
~~~

~~~text
RegistryGroupState {
  currentTick
  currentRecoveryEpoch
  configuredTtlTicks
  compactionRevision
  registryRevision
}
~~~

客户端只能提交非权威注册字段：

~~~text
RegisterRequest {
  namespace
  serviceName
  instanceId
  bootId
  endpoints
  metadata
  requestedWeight
}
~~~

tenant 和 authenticatedPrincipal 来自认证上下文；leaseId、leaseEpoch、ownerGatewayId、ownerConnectionGeneration、租约时间和所有 revision 均由 Gateway/状态机生成，客户端不能覆盖。

幂等记录：

~~~text
IdempotencyRecord {
  tenant
  authenticatedPrincipal
  operationId
  commandType
  requestHash
  fenceRecoveryEpoch  // fence-sensitive command 才使用
  result
  expiresAtGroupRevision
}
~~~

普通业务重试先按幂等键返回原结果；对 Register/TakeoverAndRenew 等受 Recovery 屏障影响的结果，只有 fenceRecoveryEpoch 仍等于当前 Group recoveryEpoch 时才允许重放成功，否则返回 LEASE_FENCED，客户端重新读取状态并创建新的操作。

### 9.2 注册命令

~~~text
Register(RegisterRequest, tenant, authenticatedPrincipal, ownerGatewayId, connectionGeneration, operationId)
TakeoverAndRenew(leaseId, expectedLeaseEpoch, expectedRecoveryEpoch, authenticatedLeaseOwner, newGatewayId, newConnectionGeneration, operationId)
RenewBatch[(leaseId, leaseEpoch, recoveryEpoch, ownerGatewayId, ownerConnectionGeneration, renewSequence)]
Deregister(leaseId, expectedLeaseEpoch, expectedRecoveryEpoch, ownerGatewayId, ownerConnectionGeneration, operationId)
ExpireBatch[(leaseId, expectedLeaseEpoch, expectedRecoveryEpoch, expectedLeaseState, expectedDeadlineTick, expectedRenewRevision)]
AdvanceLeaseClock(nextTick)
EnterLeaseRecovery(nextRecoveryEpoch, renewalGraceTicks, operationId)

ResolveOperation(tenant, authenticatedPrincipal, operationId)  // Linearizable Query
GetLeaseState(tenant, authenticatedPrincipal, instanceId, bootId | leaseId)  // Linearizable Query
~~~

语义：

- 首次 Register 原子分配 leaseId/leaseEpoch，绑定当前 currentRecoveryEpoch，设置 leaseState=ACTIVE、acceptedRenewSequence=0，并返回 nextRenewSequence=1；
- 同一业务幂等键重试返回原 leaseId 和 leaseEpoch；
- Gateway 或连接迁移必须执行 TakeoverAndRenew，在一个 Raft Command 中提升 leaseEpoch、绑定新 owner、完成首次续租并返回 nextRenewSequence=2；只有目标 ownerGatewayId/ownerConnectionGeneration 不变的同一次尝试才能复用 operationId；
- authenticatedLeaseOwner 必须匹配原 lease 的 tenant、authenticatedPrincipal、instanceId 和 bootId；进程重启导致 bootId 改变时必须重新 Register，不能接管旧进程租约；
- ACTIVE lease 若 currentTick >= expiresAtLeaseTick，即使 ExpireBatch 尚未 Apply，也不得被 Renew 或 TakeoverAndRenew 复活，只能重新 Register；
- RECOVERING lease 只有在 recoveryDeadlineTick 之前、携带当前 Group recoveryEpoch 的 TakeoverAndRenew 才能恢复为 ACTIVE；
- 新连接取得 leaseEpoch 后，旧 Gateway/Session 的 Renew、Deregister 和迟到关闭立即失效；Renew/Deregister 除 leaseEpoch/recoveryEpoch 外还必须匹配当前 ownerGatewayId/ownerConnectionGeneration；
- RenewBatch 中同一 lease 的 renewSequence 必须单调递增；
- Renew 还必须匹配当前 recoveryEpoch 且 leaseState=ACTIVE；重复 renewSequence 返回原结果，较旧序号拒绝，较新序号成功后递增 renewRevision；
- ExpireBatch 只有在 leaseEpoch、recoveryEpoch、leaseState、deadlineTick 和 renewRevision 均与观察值一致，且当前逻辑 tick 已到期时才能删除；ACTIVE 使用 expiresAtLeaseTick，RECOVERING 使用 recoveryDeadlineTick；
- RenewBatch 不接受客户端或 Gateway 指定 deadline；状态机 Apply 时统一计算 newDeadlineTick=currentTick+configuredTtlTicks；
- onClose 只作为提示，不直接删除实例；
- 正常下线主动条件注销，异常下线等待租约过期；
- AdvanceLeaseClock 只接受 nextTick=currentTick+1 的 Leader 内部命令。

### 9.3 模糊提交与跨 Gateway 恢复

operationId 标识一次内容固定的状态机尝试，不是可以改写目标 owner 的通行证：

- Register 的 clientRequestHash 只覆盖认证身份和 RegisterRequest，不包含 Gateway 注入的 owner 字段；同一 Register 在其他 Gateway 重试时可以解析到第一次提交结果；
- TakeoverAndRenew 的 requestHash 必须包含目标 ownerGatewayId 和 ownerConnectionGeneration；目标 Gateway 或连接代次改变后，不能继续复用原 operationId；
- 同一个 operationId 携带不同 requestHash 时返回 OPERATION_CONFLICT，不能猜测客户端意图；
- ResolveOperation 返回历史提交结果、原 requestHash、原 owner 和 stillCurrent 标记，但不把历史成功自动解释为当前 owner 仍有效；
- GetLeaseState 返回当前 leaseEpoch、recoveryEpoch、ownerGatewayId、ownerConnectionGeneration、leaseState 和 deadline 水位。

TakeoverAndRenew 已提交但回复丢失，随后目标 Gateway 又故障时，Client Agent 必须：

1. 通过任意可用 Gateway 对原 operationId 执行线性一致 ResolveOperation；
2. 若 FOUND，先吸收已提交的 leaseEpoch，再用新的 operationId 和当前 Gateway/connectionGeneration 提交下一次 TakeoverAndRenew；
3. 若线性一致结果为 NOT_FOUND，废弃旧目标绑定的 operationId，使用新的 operationId 和仍然有效的 expectedLeaseEpoch 发起接管；
4. 若结果 UNKNOWN、幂等记录已过保留期或控制面暂不可线性读取，则调用 GetLeaseState 重新建立当前权威水位；在解析完成前不得并发发起另一个 owner 变更；
5. Register 模糊提交采用相同流程：FOUND 后取得 leaseId/leaseEpoch；若 owner 不是当前 Gateway，再以新 operationId 执行 TakeoverAndRenew。

Gateway 可以在服务端代客户端完成上述 Resolve → Takeover 流程，但必须遵守同一个 remainingBudgetMs，且把每个新状态机尝试记录为新的 operationId。

### 9.4 租约时钟

本方案采用提交到 Registry Group 的逻辑租约时钟：

~~~text
AdvanceLeaseClock(nextTick)
RenewBatch(..., recoveryEpoch, renewSequence)
ExpireBatch(..., expectedRecoveryEpoch, expectedLeaseState, expectedDeadlineTick, expectedRenewRevision)
EnterLeaseRecovery(nextRecoveryEpoch, renewalGraceTicks, operationId)
~~~

tick 由 Registry Leader 按固定配置频率生成。Leader 变化后，新 Leader 从已提交 currentTick 继续，只能提交 currentTick+1。

要求：

- tick 是单调递增的逻辑计数，不是 Unix 时间戳；
- Lease 时间只随已提交的逻辑 tick 推进；
- Follower 不使用本地 System.currentTimeMillis() 删除实例；
- Leader 切换后从已提交 tick 和过期索引恢复，不根据本地时钟跳跃；
- 到期必须通过 Raft Apply 后才对外可见；
- 无 quorum 时权威租约时间停止推进；
- quorum 恢复后不按停机时长瞬间跳过大量 tick，停机时间不计入逻辑 TTL；
- 无 quorum 期间 Registry 对外标记为 STATE_UNAVAILABLE，客户端只能使用带 maxStale 的缓存；
- 全集群冷启动或灾难恢复后，Registry 在对外提供健康服务视图前，无条件由 bootstrap 流程提交并 Apply EnterLeaseRecovery；
- 普通 Snapshot 安装和 Follower 恢复必须原样恢复 leaseState/recoveryEpoch，不能自行把 lease 改为 RECOVERING；只有已提交的 EnterLeaseRecovery 能改变恢复状态；
- 运行期间普通 quorum 丢失与恢复不自动进入 Recovery，逻辑 TTL 随 tick 暂停；恢复后可能在剩余逻辑 TTL 内短暂保留 outage 期间已经死亡的实例，这是该时间模型的明确取舍；
- 运维可以在确认需要作废历史在线状态时显式提交 EnterLeaseRecovery；
- EnterLeaseRecovery 只接受 nextRecoveryEpoch=currentRecoveryEpoch+1，并为既有 lease 设置 RECOVERING、recoveryEpoch 和 recoveryDeadlineTick；默认不作为健康实例返回；
- recoveryEpoch 形成 Group 级 fencing barrier。所有旧 recoveryEpoch 的在途 Renew、Deregister、Expire 和 TakeoverAndRenew 命令在 Recovery 之后 Apply 时都必须失败；
- 当前客户端必须在 recoveryDeadlineTick 之前执行 TakeoverAndRenew，原子提升 leaseEpoch、续租并转回 ACTIVE；
- 宽限期结束后，未恢复 lease 使用同时匹配 recoveryEpoch、leaseState、deadlineTick 和 renewRevision 的 ExpireBatch 条件删除；
- 客户端续租预算必须满足：

续租间隔 + 批处理等待 + 选主时间 + 提交延迟 + 客户端切换预算 < TTL。

### 9.5 续租写放大

Socket.D 连接心跳与权威租约续约分离：

1. TCP Keepalive 检测底层连接；
2. Socket.D heartbeat/system probe 检测传输和 RPC；
3. registry/renew 经过 Registry Raft Group 更新业务租约。

Gateway 合并短时间窗口内的续租为 RenewBatch：

- 同一 lease 的重复续约合并；
- 每个 lease 维护单调 renewSequence；
- 客户端续租加入 jitter；
- 只有批次多数派提交后才能确认续租成功；
- 监控 WAL 增长、fsync、批大小和选主期间积压。

大规模场景可以引入 Gateway Owner Lease 优化，但第一版保持逐实例 lease，优先保证语义简单。

### 9.6 服务发现

~~~text
Snapshot(serviceKey)
  → SnapshotResponse {
       groupId,
       revisionType=REGISTRY,
       snapshotRevision=R,
       compactionRevision,
       payload=instances
     }

Watch(serviceKey, fromRevision=R+1)
  → filtered deltas + coveredThroughRevision
~~~

Registry Snapshot 中 registryRevision == snapshotRevision。registryRevision 同时是 Registry Group ChangeLog 的 eventRevision，二者属于同一数字序列。过滤订阅允许跳过与 serviceKey 无关的 revision，因此 Watch 消息必须携带 coveredThroughRevision，表示服务端已经完整处理到该水位。

当出现以下情况时必须重新获取 Snapshot：

- coveredThroughRevision 倒退、缺失或无法证明覆盖；
- 校验失败；
- Watch 游标已压缩；
- Gateway 切换后无法从原 revision 恢复；
- 客户端状态与服务端摘要不一致。

## 10. 配置中心设计

### 10.1 数据模型

~~~text
ConfigContent {
  configKey
  contentRevision
  contentHash
  schemaVersion
  payload | blobReference
}

RolloutRule {
  ruleId
  ruleGeneration
  selectorVersion
  priority
  targetContentRevision
  rolloutKey
  selectorType
  selectorData
  percentageBasisPoints
  seed
  status
  activationDecisionRevision
}

ReleaseDecision {
  configKey
  decisionRevision
  stableContentRevision
  rules[]
}

ConfigEvent {
  configGroupId
  eventRevision
  configKey
  decisionRevision
}
~~~

### 10.2 版本边界

| 版本 | 作用域 | 作用 |
|---|---|---|
| contentRevision | 不可变内容 | 标识内容 |
| decisionRevision | 单个 configKey | 标识发布、灰度和回滚决策 |
| eventRevision | 单个 Config Group | Watch 游标 |
| clientAppliedRevision | 客户端 + configKey | 防止版本倒退 |

不能使用一个全局版本同时表达这四种含义。

### 10.3 发布流程

~~~mermaid
sequenceDiagram
    participant Admin as 管理端
    participant Gateway as Admin Gateway
    participant Leader as Config Leader
    participant State as Config State Machine
    participant Dispatcher as ChangeLog Dispatcher
    participant ClientGW as Socket.D Gateway
    participant Client as Client Agent

    Admin->>Gateway: 提交内容、规则和 operationId
    Gateway->>Leader: PublishDecision command
    Leader->>State: quorum commit + apply
    State-->>Leader: decisionRevision + eventRevision
    Leader-->>Gateway: 发布成功
    State-->>Dispatcher: 追加可恢复 Config ChangeLog
    Dispatcher-->>ClientGW: 从 eventRevision 恢复消费
    ClientGW-->>Client: invalidated(configKey, decisionRevision)
    Client->>ClientGW: fetch(identity, configKey, minDecisionRevision)
    ClientGW->>State: findApplicableRelease(...)
    State-->>ClientGW: content + decisionRevision + ruleId
    ClientGW-->>Client: 原子更新
~~~

Raft Apply 线程不直接执行 ClientGW 推送。图中的变更流由独立 Dispatcher 完成。

### 10.4 大配置内容

- 配置内容设置严格大小上限；
- 小内容可以直接存入 Raft 状态；
- 大内容先写入复制的不可变 Blob Store；
- Blob 持久化成功后，Raft 才提交 hash 和 reference；
- 客户端读取后校验 contentHash；
- Snapshot 只保存必要内容或 Blob 引用。

### 10.5 灰度与回滚

百分比灰度使用稳定纯函数：

~~~text
hash(rolloutKey, clientInstanceId, seed) mod 10000
  < percentageBasisPoints
~~~

要求：

- rolloutKey 在同一次灰度策略期间保持稳定；
- targetContentRevision 明确指定规则命中后的候选内容；
- stableContentRevision 是没有任何规则命中时的默认内容；
- 规则按 priority 降序、ruleId 升序确定性排序，第一条命中规则生效；
- 同一优先级的冲突 selector 在发布时拒绝，不能依赖 Map 或数据库返回顺序；
- selectorVersion 参与规则解释和审计，升级选择器时保持旧规则可重放；
- status 只允许通过 Config Raft Group 提交 ActivateRule/DeactivateRule 改变；
- ActivateRule/DeactivateRule 必须携带 expectedRuleGeneration 和 expectedStatus 做 CAS；Leader 切换后的迟到调度命令不能覆盖更新后的规则状态；
- 定时灰度由 Leader 侧调度器在目标时间提交显式激活/失活命令，状态机选版不得读取各节点本地墙上时间；
- 每次规则激活或失活都生成更高 decisionRevision；
- 客户端身份稳定且经过认证；
- 安全相关标签不能完全由客户端自报；
- Push 与 Pull 调用同一个 findApplicableRelease；
- 所有 State Node 对同一身份和决策产生相同结果；
- 回滚创建更高 decisionRevision，重新指向历史 contentRevision；
- ACK 仅用于观测，不作为可靠交付依据。

## 11. Watch 设计

### 11.1 单一多路 Watch

Client Agent 建立一个可恢复 Watch 流，订阅多个配置键和服务。

游标：

~~~text
configGroupId -> eventRevision
registryGroupId -> registryRevision
~~~

Registry Group 的 registryRevision 与其 Watch eventRevision 是同一序列。Config Group 使用自己的 eventRevision。不存在跨 Group 的伪全局总序。

Snapshot 必须返回：

~~~text
SnapshotResponse {
  groupId
  revisionType  // Config Group 固定为 CONFIG_EVENT；Registry Group 固定为 REGISTRY
  snapshotRevision
  compactionRevision
  payload
}
~~~

watch/open 对每个涉及的 Raft Group 独立执行无缝 Bootstrap。实现必须在同一个 State 服务端临界区或等价的可重放游标事务中完成：

1. 通过 ReadIndex/Leader barrier 读取该 Group 的一致性 snapshotRevision=R；
2. 在释放读取屏障前，为 R+1 注册 ChangeLog 游标并取得 retention pin，防止 Bootstrap 期间压缩越过 R；
3. 如果 Leader 切换、游标注册失败或 compactionRevision 已越过 R，返回 REVISION_COMPACTED/STATE_UNAVAILABLE，客户端重试，不得返回成功 Snapshot；
4. 返回 SnapshotResponse；
5. 从已固定游标重放所有 revision > R 的事件，再转入实时投递；
6. Watch 关闭或重置时释放 retention pin，服务端同时对 pin 数量、存活时间和最老水位设置配额。

Config Group 的 Snapshot payload 包含订阅 configKey 的当前 decisionRevision、ruleId 和 contentRevision 摘要；Registry Group 的 payload 包含订阅 serviceKey 的当前实例视图。客户端再按需调用 config/fetch 获取配置内容。

config/fetch 不承担建立 Watch 水位的职责。首次配置同步必须通过 watch/open Bootstrap 或单独的 Config Snapshot API 完成，避免 Fetch 与 Watch 之间漏变更。

### 11.2 投递语义

- 至少一次；
- 同一 Group 内的权威 ChangeLog 按 revision 有序；
- 客户端按 revision 去重；
- 重连携带 resume token；
- token 过旧返回 REVISION_COMPACTED；
- 客户端重新拉 Snapshot 后再订阅；
- Watch Stream 到期或重建不影响业务 revision。

由于客户端通常只订阅一个 Group 中的部分 configKey 或 serviceKey，过滤后的事件序号可以出现自然空洞。每个 Watch frame 必须携带：

~~~text
groupId
revisionType
coveredFromRevision
coveredThroughRevision
events[] {
  eventRevision
  eventType
  key
  payload
}
~~~

frame 可以包含多个事件，也可以是不含事件的纯 watermark frame。coveredThroughRevision 表示服务端已经完整扫描并处理到该 Group 水位。客户端以 coveredThroughRevision 推进 resume token，而不是把数值断号直接判断为丢事件。

客户端从 Bootstrap 的 snapshotRevision 初始化 lastCoveredThroughRevision。每个后续 frame 必须满足：

- coveredFromRevision <= lastCoveredThroughRevision + 1，允许至少一次投递产生区间重叠；
- coveredThroughRevision >= lastCoveredThroughRevision，水位只能单调不减；
- coveredFromRevision <= coveredThroughRevision + 1；纯 watermark frame 也必须声明有效覆盖区间；
- events 按 eventRevision 升序，每个 eventRevision 都位于该 frame 的覆盖区间；
- 重叠区间中的事件按 eventRevision 去重后应用；
- coveredFromRevision 出现前向空洞、水位倒退、事件越界或乱序时，立即停止推进 token，并执行 reset/Snapshot，而不是继续猜测。

配置 invalidation 合并时，frame 必须保留覆盖区间，并为每个 configKey 携带区间内最新 decisionRevision。config/invalidated 只通过带游标的 Watch Reply 作为权威通知；若未来增加无游标 Send，它只能作为非权威加速提示，不能推进 resume token。只有服务端无法证明覆盖、ChangeLog 已压缩或校验失败时才要求全量重置。

### 11.3 慢客户端

- 每个订阅队列有容量上限；
- 配置 invalidation 可以按 configKey 合并；
- Registry delta 不允许无限堆积；
- 超过上限返回 watch/reset-required 并关闭当前 Watch；
- 客户端退避后重新拉快照；
- 不允许慢消费者拖慢 Raft Apply 或其他客户端。

## 12. Client Agent 设计

### 12.1 Client Agent 迁移状态

~~~mermaid
stateDiagram-v2
    [*] --> STABLE
    STABLE --> MIGRATING: system/drain 或活动连接进入 SUSPECT

    state MIGRATING {
        [*] --> OPEN_NEW_CONNECTION
        OPEN_NEW_CONNECTION --> TAKEOVER_AND_RENEW
        TAKEOVER_AND_RENEW --> RESTORE_WATCH
        RESTORE_WATCH --> SWITCH_ACTIVE
        SWITCH_ACTIVE --> CLOSE_OLD_CONNECTION
    }

    MIGRATING --> STABLE: 迁移完成
    MIGRATING --> DEGRADED: migration deadline 到期
    DEGRADED --> MIGRATING: 退避后重试
~~~

连接自身仍使用：

~~~text
DISCONNECTED → CONNECTING → ACTIVE → SUSPECT → DRAINING → CLOSED
~~~

迁移期间旧连接和新连接短暂并存：

1. 旧连接收到 system/drain 后进入 DRAINING，但暂时保持可读和已有 in-flight 请求；
2. Client Agent 建立新连接并通过 system/hello、system/probe；
3. 新连接使用本次迁移稳定不变的 operationId 执行 TakeoverAndRenew，在一个提交中获得更高 leaseEpoch 并延长 TTL，旧连接写操作立即被 fencing；
4. 新连接从 coveredThroughRevision 恢复 Watch；
5. Client Agent 原子切换 active Gateway；
6. 主动关闭旧连接；超过 closing deadline 时强制关闭。

服务端必须先发送 system/drain，再进入 Socket.D preclose。突然断链或 RPC SUSPECT 时，Client Agent 直接启动相同迁移流程。

### 12.2 Gateway 选择

- 客户端持有多个种子地址；
- 基础设施 cohort 先选择兼容 Gateway 池；
- 正常情况下只有一个 sticky active Gateway；
- 可以保留一个不承载业务的热备连接；
- 每次尝试只访问一个 Gateway；
- 总 deadline 内最多顺序切换一次；
- 禁止常态化全 Gateway fan-out；
- 自动切换不能跨 TCP/WSS、新旧协议或新旧二进制兼容池。

### 12.3 请求健康

<code>isActive()</code> 不能作为唯一健康标准。Client Agent 维护：

- 物理连接状态；
- 最近一次真实 RPC 成功时间；
- 连续 timeout 数；
- 最近错误类型；
- closing 状态；
- 冷却或熔断截止时间；
- in-flight Request/Subscribe 数；
- 当前 clusterId 和 transportGeneration。

达到阈值的 Session 进入 SUSPECT，停止承载新请求。恢复 ACTIVE 必须通过真实请求响应，不依赖单纯 ping/pong。

### 12.4 本地缓存

配置：

- 持久化 last-known-good；
- 校验 contentHash；
- 记录每个 configKey 的 clientAppliedRevision；
- 控制面不可用时继续使用最后可用配置；
- 不自动清空业务配置。

服务发现：

- 记录 registryRevision；
- 使用明确 maxStale；陈旧时长由 Client Agent 从最后一次成功校准的本地单调时刻计算，不能用服务端 revision 数字推测时间；
- 超过 maxStale 后暴露降级状态；
- 不永久使用旧实例列表；
- 切换 Gateway 后从 revision 恢复或重新拉快照。

## 13. 一致性与故障语义

### 13.1 少数派

分区节点不能瞬时、全局地判断集群是否已经失去 quorum。错误按观察者能力区分：

- Follower 向 Gateway 的内部 RPC 返回 NOT_LEADER 和 State leaderHint；Gateway 在剩余预算内更新路由并重试，不把 State 内网地址暴露给客户端；
- Gateway 在 election/routing timeout 内找不到可用 Leader 时返回 STATE_UNAVAILABLE；
- 已知 Leader 的 proposal 在 proposal timeout 内无法获得多数派时返回 NO_QUORUM；
- 所有 State Plane 一致性错误携带目标 groupId、revisionType、observedRevision 和 retryAfterMs；
- 不接受配置发布、注册、续租和注销为成功；
- 不回复“租约已提交”；
- 只在 API 明确允许时提供带 revision 的陈旧读取；
- 引导 Client Agent 切换到可达多数派的 Gateway。

### 13.2 整体失去 quorum

- 所有权威写入停止；
- 逻辑 lease clock 停止推进；
- 不在不同节点独立过期实例；
- 可以提供明确标记的最后已知配置和服务视图；
- Client Agent 使用 last-known-good 和 maxStale 策略；
- 恢复 quorum 后继续推进租约与事件。

### 13.3 写成功但回复丢失

客户端可重试的业务写携带 operationId。幂等键使用 tenant + authenticatedPrincipal + operationId，状态机保存 operationId → requestHash/result。只有业务内容和目标 owner 均未改变的同一次尝试才能直接重放原结果；目标 Gateway/connectionGeneration 改变时，必须先 ResolveOperation/GetLeaseState，再使用新的 operationId 发起新的 owner 变更。

这适用于：

- Register；
- Deregister；
- 配置发布；
- 配置回滚；
- 灰度规则变更；
- 租约接管；
- Raft 管理操作。

Renew 使用 leaseId + leaseEpoch + recoveryEpoch + renewSequence 去重，不为每次高频续租保存独立 operationId。ExpireBatch 依靠 expectedLeaseEpoch、expectedRecoveryEpoch、expectedLeaseState、expectedDeadlineTick 和 expectedRenewRevision CAS；AdvanceLeaseClock 依靠单调 nextTick。

### 13.4 Session 断开

Session 断开不等于服务实例立即死亡：

- onClose 标记本地连接结束；
- 可提交 DisconnectHint，但不直接删除；
- 正常注销同时匹配 leaseId、leaseEpoch、recoveryEpoch、ownerGatewayId 和 ownerConnectionGeneration；
- 异常断开由 TTL/ExpireBatch 收敛；
- 旧连接关闭不能删除新 leaseEpoch。

## 14. 安全设计

### 14.1 客户端身份

- 服务客户端优先使用 mTLS；
- 可叠加短期 token；
- clientInstanceId 与证书或工作负载身份绑定；
- bootId 每次进程启动生成；
- 握手校验 clusterId、协议版本、能力和租户；
- 灰度所需可信标签由认证系统或服务端生成。

### 14.2 权限

至少区分：

- config:read；
- config:watch；
- registry:register；
- registry:discover；
- config:publish；
- config:rollback；
- cluster:admin；
- audit:read。

客户端 SDK 不能获得管理权限。

### 14.3 内部安全

- Raft 通信使用独立 mTLS；
- Gateway 到 State Node 使用内部身份；
- State Node 只暴露内网地址；
- Snapshot、WAL 和 Blob 加密存储；
- 操作日志包含 actor、operationId、traceId 和 revision；
- 敏感配置支持字段级加密或外部密钥引用。

## 15. 背压、限流与容量

### 15.1 优先级

建议优先级：

1. Raft 心跳和内部复制；
2. Registry Renew；
3. Register/Deregister；
4. Config Fetch；
5. Registry Watch；
6. Config Invalidation；
7. 管理查询和审计导出。

不同级别使用独立队列，低优先级流量不能阻塞租约续约或 Raft。

### 15.2 配额

按 tenant、namespace 和 client 限制：

- 连接数；
- 每秒请求数；
- Watch 数；
- 注册实例数；
- Renew 速率；
- 配置键数量；
- 配置大小；
- 推送缓冲；
- 最大 in-flight Stream。

### 15.3 容量验证

压测必须覆盖：

- WAL 增长和 fsync；
- RenewBatch 大小；
- Leader 切换积压；
- 快照暂停；
- Watch 慢消费者；
- 大规模租约到期；
- Gateway 重连风暴；
- 配置批量发布；
- Blob 读取；
- Netty pending bytes 和直接内存。

## 16. 可观测性

### 16.1 Gateway

- 当前连接数；
- ACTIVE/SUSPECT/DRAINING/CLOSED 数；
- 请求成功率和 timeout；
- in-flight Request/Subscribe；
- Netty pending bytes；
- 业务队列长度；
- 推送丢弃、合并和 reset-required；
- Gateway 切换次数；
- closing 持续时间。

### 16.2 Raft

- Leader、term 和成员；
- commitIndex、appliedIndex 和 lag；
- proposal latency；
- fsync latency；
- WAL 大小；
- snapshot 创建、传输和安装；
- Leader 切换次数；
- STATE_UNAVAILABLE 和 NO_QUORUM 持续时间；
- 每 Group QPS 和队列长度。

### 16.3 Registry

- 活跃 lease 数；
- Register/Renew/Deregister 速率；
- RenewBatch 大小；
- lease 到期数量；
- leaseEpoch fencing 拒绝数；
- registryRevision；
- Watch lag；
- maxStale 客户端数量。

### 16.4 Config

- contentRevision 和 decisionRevision；
- Config Group eventRevision；
- 发布与回滚延迟；
- 灰度 cohort 分布；
- Push 到 Fetch 收敛时间；
- 客户端 applied revision lag；
- last-known-good 使用数量。

## 17. 滚动升级与灰度

### 17.1 分别版本化

- Socket.D 客户端协议；
- Protobuf Schema；
- Raft Command；
- 业务状态机 Schema；
- WAL 和 Snapshot 格式；
- Gateway capability；
- Group capability。

Capability gate 必须是各 Raft Group 自己持久化的集群事实，不能用一个全局 membershipConfigIndex 覆盖多个 Group：

~~~text
GroupCapabilityGate {
  groupId
  capabilityEpoch
  membershipConfigIndex
  enabledFeatures
  commandSchemaVersion
  stateSchemaVersion
  snapshotFormatVersion
  minimumBinaryVersion
  supportingNodeIds
}
~~~

Gate 在其所治理的 Group 内提交，并绑定该 Group 当前 membershipConfigIndex。只有该 Group 的全部 voter 和允许晋升的 learner 都提交兼容能力证明后，Leader 才能提议开启。开启前继续写旧 Command、状态和 Snapshot 格式；开启后低于 minimumBinaryVersion 的节点不得加入该 Group、晋升或成为 Leader。

成员变更前，候选节点必须先证明支持该 Group 已启用的全部能力和快照格式；不兼容节点在加入 learner 之前就被拒绝。membershipConfigIndex 改变后，已启用能力继续有效，但必须基于新成员集合提交 RevalidateGroupCapabilityGate，才能再开启下一项能力。跨 Group 功能只有在所有相关 Group 的 gate 均开启后才能启用。Gateway 协议能力通过基础设施兼容池和握手单独治理，不假设与 State Group gate 同步。

### 17.2 升级顺序

紧凑共置部署逐节点执行：

1. 目标节点的 Gateway 先进入 DRAINING，停止接收新连接、新注册和新 Watch，并发送 system/drain。
2. Client Agent 在其他兼容节点完成 TakeoverAndRenew、Watch 恢复和 active 切换；旧 Gateway 执行 preclose → bounded drain → final close。
3. 将该节点承载的所有 Config/Registry Group Leader 转移到其他节点，确认每个 Group 仍有 quorum。
4. 停止并升级该节点，使其以 follower/learner 身份重新加入；验证旧日志回放和旧快照安装。
5. 立即启动并验证该节点的 Gateway 旧协议兼容入口，通过 system/probe 后重新加入原兼容池；它恢复承载能力之前，不得开始排空下一个节点。
6. 逐个节点重复以上步骤，任何时刻只下线一个 voter，并始终至少保留一个已验证的旧协议兼容 Gateway。
7. 全部节点升级后，每个 Group 的所有 voter 和可晋升 learner 均声明支持，再在该 Group 提交 GroupCapabilityGate。
8. 所有相关 Group gate 生效后，才启用新的 Command、必填 Schema 字段、状态写格式、Snapshot 写格式和新协议兼容池；旧客户端迁移完成前，新二进制继续提供旧协议入口。

Gateway 与 State Node 分离部署时：

1. 先逐个升级 State follower/learner，再转移各 Group Leader 并升级旧 Leader；始终保持 quorum。
2. 验证日志和快照兼容后，按 Group 提交并验证 GroupCapabilityGate。
3. 再逐个排空和升级 Gateway：system/drain → TakeoverAndRenew → Watch 恢复 → active 切换 → preclose/final close。
4. 新 Gateway 只加入与其协议和 Transport 兼容的基础设施池；旧客户端迁移完成前保留旧协议兼容入口。

二进制升级和 Raft 成员变更不能同时进行。

### 17.3 回滚边界

- 在启用旧版本不能识别的新 Command、必填 Schema、状态写格式或 Snapshot 格式前，可以二进制回滚；
- capability gate 打开后，必须使用兼容回滚版本；
- 基础设施灰度与配置灰度分别使用独立 rolloutKey、revision 和指标；
- Client Agent 不允许自动跨越不兼容池。

## 18. 测试与验收矩阵

| 场景 | 验收条件 |
|---|---|
| 单 Gateway 故障 | Client Agent 有界切换，Lease 和 Watch 恢复 |
| Gateway 排空迁移 | 新连接先以稳定 operationId 提交 TakeoverAndRenew，原子提升 leaseEpoch 和 TTL，再恢复 Watch、关闭旧连接 |
| Socket open 但 RPC 丢回复 | Session 进入 SUSPECT，不永久重试 |
| preclose 后 final close 丢失 | closing deadline 强制关闭 |
| Register 已提交但回复丢失 | operationId 重试返回原 leaseId 和 leaseEpoch |
| Register 已提交、原 Gateway 随后故障 | ResolveOperation 得到原 lease 后，用新 operationId 接管到当前 Gateway |
| 幂等成功后发生 Recovery | 旧 fenceRecoveryEpoch 的缓存成功不得重放，返回 LEASE_FENCED |
| 旧 Session 延迟 Renew | leaseEpoch fencing 拒绝 |
| 旧 owner 使用当前 epoch 尝试 Renew/Deregister | ownerGatewayId/connectionGeneration fencing 拒绝 |
| TakeoverAndRenew 提交后响应丢失且目标 Gateway 故障 | Resolve 原 operationId，再以新 operationId 接管；不返回错误 owner 的可用成功 |
| 同一 operationId 改写目标 owner | 返回 OPERATION_CONFLICT |
| Recovery 前的在途 Renew/Expire | recoveryEpoch fencing 拒绝，不改变 RECOVERING/新 ACTIVE lease |
| Expire 与 Renew 竞态 | leaseEpoch/recoveryEpoch/leaseState/deadlineTick/renewRevision CAS 不删除已续租实例 |
| 已到期但尚未删除的 Lease | Renew/TakeoverAndRenew 拒绝复活，客户端必须重新 Register |
| 旧 Session 延迟 onClose | 不删除新租约 |
| Registry Leader 切换 | 不提前删除 lease，RenewBatch 可恢复 |
| 最大允许选主延迟 | 租约预算仍小于 TTL |
| 普通 quorum 丢失后恢复 | 不自动进入 Recovery；tick 从已提交水位继续，缓存和剩余 TTL 行为符合合同 |
| 全集群冷启动/灾难恢复 | 对外提供健康视图前 Apply EnterLeaseRecovery；历史 lease 先进入 RECOVERING |
| 普通 Follower 安装 Snapshot | 原样恢复 leaseState/recoveryEpoch，不触发业务 Recovery |
| State Node 少数派 | 不接受权威写入成功 |
| 整体无 quorum | 各节点不独立形成不同注册事实 |
| Watch 断线重连 | 从 revision 恢复，不丢变更 |
| Bootstrap 读取后立即提交事件 | retention pin/游标保证该事件从 R+1 被重放，不出现 Snapshot/Watch 缝隙 |
| Bootstrap 与 ChangeLog 压缩竞态 | 无法固定 R 时返回 REVISION_COMPACTED 并重试，不返回伪成功 Snapshot |
| 过滤 Watch 出现 revision 空洞 | coveredThroughRevision 正确推进，不误判丢事件 |
| Watch frame 重叠和纯 watermark | 重叠事件正确去重，空事件 frame 仍能安全推进覆盖水位 |
| Watch frame 前向空洞、水位倒退或事件越界 | 客户端停止推进 token 并 reset，不继续猜测 |
| 配置 invalidation 合并 | 覆盖区间完整，客户端不漏相关 key 的最新决策 |
| Watch revision 已压缩 | 返回 REVISION_COMPACTED 并重拉快照 |
| 慢客户端 | 有界队列，reset-required，不阻塞 Apply |
| 快照安装 | 状态、索引和 ChangeLog 水位一致 |
| 配置重复事件 | 客户端 revision 去重 |
| 配置灰度 | 同一身份在所有节点得到相同内容 |
| 灰度定时命令迟到 | ruleGeneration/status CAS 拒绝旧 Activate/Deactivate 命令 |
| 配置回滚 | 创建更高 decisionRevision，客户端不倒退 |
| Follower 落后 | 指定 groupId/revisionType 的 minRevision 读取代理 Leader 或失败 |
| State Follower 返回 NOT_LEADER | Gateway 在内部更新路由，客户端看不到 State leaderHint |
| Gateway 找不到 Leader | election/routing timeout 后返回 STATE_UNAVAILABLE |
| Leader 无法提交多数派 | proposal timeout 后返回带 groupId 的 NO_QUORUM |
| remainingBudgetMs 跨 Gateway 切换 | 总 deadline 不重置，重试次数和耗时保持有界 |
| TCP/WSS 混合部署 | 自动切换不跨兼容池 |
| 混合版本 Raft 集群 | 能选主、回放日志和安装快照 |
| Group gate 遇到旧 voter/learner | 旧成员阻止新能力开启；不兼容节点不能加入、晋升或当选 Leader |
| Group 成员变更后 | 基于新 membershipConfigIndex 重验证后才能开启下一项能力 |
| 紧凑部署逐节点升级 | 先排空该节点 Gateway、迁移客户端和 Leader，再升级，始终保有 quorum |
| 旧客户端尚未迁移 | 不升级掉最后一个旧协议兼容入口，新二进制的旧兼容池仍可完成故障切换 |
| 大规模租约到期 | 不阻塞配置发布 |
| Gateway 重连风暴 | 退避、jitter、恢复任务合并 |

端到端测试必须使用生产选择的原生 Socket.D TCP/Netty Transport。不能使用其他 Transport 的测试代替。

## 19. 实施阶段

### Phase 0：协议与共识验证

- 确定 Raft 实现；
- 定义 Protobuf Envelope；
- 定义业务 operationId、Renew renewSequence、Expire CAS 和 Clock 单调合同；
- 完成原生 Socket.D TCP/Netty PoC；
- 验证 quorum commit + apply 后回复；
- 验证快照、恢复和 Leader 切换。
- 验证所选 Socket.D/Netty Provider 的确切版本、mTLS、背压、preclose 和 final close 行为。

退出条件：

- 无桥接层；
- 写响应语义明确；
- Raft Apply 不访问网络和 Session；
- 基础故障注入通过。

### Phase 1：三节点基础平台

- 紧凑三节点部署；
- Gateway 认证、限流和连接管理；
- 一个 Config Group 和一个 Registry Group；
- Client Agent sticky active 连接；
- system/hello、system/probe 和 drain；
- 基础监控与日志。

退出条件：

- 单节点故障可恢复；
- 少数派拒绝写入；
- 客户端不执行请求 fan-out。

### Phase 2：配置中心

- 不可变 ConfigContent；
- ReleaseDecision；
- Config ChangeLog；
- config/fetch 和 Watch；
- last-known-good；
- 发布、回滚和审计；
- 暂不开放复杂灰度。

退出条件：

- Push 丢失后可以 Pull 收敛；
- 回滚产生更高 decisionRevision；
- 重复和乱序事件不导致版本倒退。

### Phase 3：注册中心

- instanceId、leaseId、leaseEpoch、recoveryEpoch、renewSequence 和 renewRevision；
- Register/RenewBatch/Deregister；
- TakeoverAndRenew 和完整 ExpireBatch CAS；
- ResolveOperation/GetLeaseState 模糊提交恢复；
- 逻辑 lease clock；
- EnterLeaseRecovery 和 renewal grace；
- Snapshot + Watch；
- maxStale 客户端策略；
- 大规模续租压测。

退出条件：

- 旧 leaseEpoch 全部被 fencing；
- Leader 切换不提前过期实例；
- 快照恢复不会把历史租约永久恢复为在线。

### Phase 4：生产可靠性

- Watch 压缩和恢复；
- Snapshot/WAL 备份与恢复演练；
- 慢消费者处理；
- 全故障矩阵；
- 混合版本升级；
- 基础设施兼容池；
- 安全和配额。

### Phase 5：规模化与灰度

- Config/Registry 分片；
- Gateway 与 State Node 分离；
- Meta Group；
- Gateway Owner Lease，可选；
- 完整灰度规则；
- 跨地域异步灾备；
- 容量模型和 SLO。

## 20. 最终架构不变量

1. Socket.D 只负责连接和消息，不承担分布式事实复制。
2. Session 和 Subscription 永不进入 Raft。
3. 写成功必须表示 quorum commit 且回复该命令的 Leader 已 Apply。
4. Config 与 Registry 不共享一个高频写 Group。
5. 每次客户端尝试只访问一个 Gateway。
6. 业务写重试复用 operationId；Renew、Expire 和 Clock 使用各自的序号/CAS 幂等合同。
7. 服务在线状态只由 lease 决定，Socket 心跳不能替代 lease。
8. 旧 leaseEpoch、旧 recoveryEpoch 或非当前 ownerGatewayId/ownerConnectionGeneration 不能续约、注销或删除新租约。
9. Expire 必须通过 leaseEpoch、recoveryEpoch、leaseState、deadlineTick 和 renewRevision CAS，不能删除已续租或已进入新恢复代次的实例。
10. Watch 至少一次投递，并通过 coveredThroughRevision 从过滤和合并后的流恢复。
11. Push 只加速收敛，Pull 和 revision 保证正确性。
12. 回滚创建更高 decisionRevision。
13. 无法访问 Leader 返回 STATE_UNAVAILABLE；只有 Leader proposal 失败才返回 NO_QUORUM。
14. Raft 通信和客户端通信端口、线程池、队列完全隔离。
15. 自动故障切换不跨 Transport 或二进制兼容池。
16. system/drain 后先建立新连接，通过 TakeoverAndRenew 原子提升 leaseEpoch 和 TTL，再恢复 Watch、preclose 旧连接。
17. 新 Command、必填 Schema、状态写格式和 Snapshot 写格式都必须经过对应 Raft Group 的 capability gate。
18. 普通 Snapshot restore 不改变 leaseState；只有已提交的 EnterLeaseRecovery 能建立 Recovery fencing barrier。

## 21. 待确认事项

1. 选用的成熟 Raft 实现及其许可证、快照和存储能力。
2. State Node 本地 KV 存储选型。
3. Gateway 与 State Node 分离后的内部 RPC 技术。
4. TCP 入口的四层负载均衡和种子地址发现方式。
5. WSS 兼容入口是否进入第一版。
6. 配置内容大小上限和 Blob Store 选型。
7. Lease tick、TTL、RenewBatch 窗口和选主预算。
8. Registry 和 Config 的初始分片阈值。
9. 允许的 bounded stale 和客户端 maxStale。
10. 单地域和跨地域的灾备 SLO。

## 22. 设计依据与配套资料

- [Socket.D 官方文档](https://socketd.noear.org/)
- [Solon 文档：文章 1144](https://solon.noear.org/article/1144)
- [SmartHTTP / Socket.D / Multi-Broker 架构评审](socketd-smarthttp-multibroker-architecture-review.md)
- [Socket.D 目标架构设计](socketd-target-architecture-design.md)
- [可迁移 Codex Skill 安装说明](codex-skills/INSTALL.md)
