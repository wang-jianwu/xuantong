# Xuantong Socket.D 目标架构设计

> 状态：Proposed  
> 日期：2026-07-16  
> 适用范围：Xuantong 配置中心、服务发现、Broker 集群与客户端 SDK  
> 关联评审：[SmartHTTP / Socket.D / Multi-Broker 架构评审](socketd-smarthttp-multibroker-architecture-review.md)

完全不受现有实现约束的产品级设计见：[Socket.D + Multi-Raft 微服务控制面技术方案](socketd-multiraft-control-plane-technical-design.md)。

## 1. 决策摘要

目标架构不是取消 Multi-Broker，而是重新定义其职责：

> Multi-Broker 只承担多地址容灾和有限负载分配，不再表示每个客户端把每个普通请求同时发送给全部 Broker。

推荐架构为：

> 原生 Socket.D 控制面 + SmartHTTP 管理面 + Broker 无状态化 + 共享状态层 + 客户端顺序单 Broker 尝试与有界故障切换。

当前实现中需要下架的是：

- 配置读取对全部 Broker 的请求 fan-out；
- 使用 Java Future 取消来假设底层 Socket.D RequestStream 已取消；
- 为推动 SmartHTTP WebSocket 输出而执行的多次 ping workaround；
- 依赖客户端向全部 Broker 多写、再合并各 Broker 本地服务视图的长期方案；
- 仅有发布类型标签、没有客户端选择规则的伪灰度能力。

需要保留的是：

- 多个 Broker 地址；
- Socket.D 的长连接、请求响应和事件推送模型；
- SmartHTTP 管理后台、REST API、健康检查和监控能力；
- 双端点并存的迁移与回滚窗口。

## 2. 设计目标

### 2.1 功能目标

- 每次配置读取尝试只访问一个健康 Broker。
- 任一 Broker 故障时，客户端在统一总截止时间内完成有界故障切换。
- 任一 Broker 都能读取相同配置事实，并向自身连接的客户端发送完整覆盖、不漏失的配置变更通知。
- 任一 Broker 都能查询由权威租约状态派生、携带明确 registryRevision 和陈旧度边界的服务注册视图。
- 配置推送、主动拉取、定时修复和回滚对同一客户端产生一致结果。
- 基础设施灰度与配置灰度使用两套独立状态机。

### 2.2 可靠性目标

- 物理连接存活不等同于 RPC 健康。
- 连续请求超时能够使 Session 退出流量选择，并触发强制重连。
- 收到 preclose 但最终 close 丢失时，客户端 closing deadline 能够结束 DRAINING 状态。
- 丢失推送后，客户端可以根据每配置键 decisionRevision 或服务端快照 token 主动补偿；Broker 使用独立事件 cursor 恢复消费。
- 跨 Broker 重试不会造成重复注册、错误注销或其他重复副作用。
- 单个 Broker 故障不会把正常请求量和超时日志放大为 Broker 数量的倍数。

### 2.3 非目标

- 本文不强制指定事件流和注册表的最终产品选型。
- 本文不要求一次性替换现有 SmartHTTP 端点。
- 本文不把增加超时时间或增加 ping 作为架构修复。
- 本文不允许在迁移期把同一个逻辑请求同时发往桥接链路和原生链路。

## 3. 总体架构

~~~mermaid
flowchart TB
    subgraph CLIENT["客户端"]
        APP["业务应用"]
        SDK["Xuantong SDK<br/>稳定 clientInstanceId + tags<br/>按 configKey revision 去重"]
        POOL["Infrastructure Cohort Router<br/>先选择兼容 Broker 池"]
        ROUTER["Broker Selector<br/>每次尝试只选择 1 个 Broker<br/>总 deadline 内最多 2 次顺序尝试"]
        HEALTH["连接与 RPC 双重健康<br/>ACTIVE → SUSPECT → DRAINING → CLOSED"]

        APP --> SDK --> POOL --> ROUTER
        ROUTER --- HEALTH
    end

    subgraph CONTROL["控制面：选中的兼容 Broker 池 / 原生 Socket.D 独立端口"]
        BA["Broker A<br/>当前选中"]
        BB["Broker B<br/>热备"]
        BC["Broker C<br/>候选"]
    end

    ROUTER <-->|"请求 / 响应<br/>唯一推送订阅"| BA
    ROUTER -.->|"超时、断链或 DRAINING 后<br/>有界故障切换"| BB
    ROUTER -.->|"后备候选"| BC

    subgraph SHARED["共享状态层"]
        CONFIG[("配置发布库<br/>不可变内容 + 灰度规则<br/>decisionRevision")]
        OUTBOX["Transactional Outbox"]
        BUS["持久事件流<br/>每个 Broker 均可恢复消费"]
        REGISTRY[("权威租约注册表<br/>线性一致或单写 Lease / Epoch / TTL<br/>可复制 Revision 视图")]
    end

    BA -->|"查询适用配置"| CONFIG
    BB -->|"查询适用配置"| CONFIG
    BC -->|"查询适用配置"| CONFIG

    CONFIG --> OUTBOX --> BUS
    BUS -->|"变更事件"| BA
    BUS -->|"变更事件"| BB
    BUS -->|"变更事件"| BC

    BA <-->|"注册 / 心跳 / 查询 / Watch"| REGISTRY
    BB <-->|"注册 / 心跳 / 查询 / Watch"| REGISTRY
    BC <-->|"注册 / 心跳 / 查询 / Watch"| REGISTRY

    subgraph MANAGEMENT["管理面：SmartHTTP 原端口"]
        ADMIN["控制台 / 发布 / 回滚 REST API"]
        OBS["监控 / 审计 / 告警"]
    end

    ADMIN -->|"事务写入发布和 Outbox"| CONFIG
    BA --> OBS
    BB --> OBS
    BC --> OBS
~~~

图中 Broker A 只是当前选中 Broker 的示例。客户端发生故障切换后，Broker B 或 Broker C 可以成为新的活动 Broker，但自动故障切换只能发生在基础设施灰度已经选定的兼容 Broker 池内。

## 4. 平面与组件职责

### 4.1 SmartHTTP 管理面

SmartHTTP 继续承载：

- 管理控制台；
- 发布、回滚和查询 REST API；
- 健康检查、指标和审计接口；
- 与长连接无关的普通 HTTP 业务。

SmartHTTP 不再承载正确性敏感的 Socket.D 请求响应。生产迁移完成后，下架继承 <code>ToSocketdWebSocketListener</code> 的配置和发现桥接端点。

### 4.2 原生 Socket.D 控制面

使用 Solon 的原生 Socket.D Server 集成，在独立监听端口提供：

- 配置读取；
- 配置失效通知；
- 服务注册、续租、注销和查询；
- 注册表增量 Watch；
- preclose、drain、final close 和重连生命周期。

原生端口必须同时定义：

- TLS 和鉴权策略；
- 连接、请求、流和空闲超时；
- 服务端摘流顺序；
- <code>preclose → bounded drain → close</code>；
- 客户端 closing deadline；
- 新旧协议与 WebSocket subprotocol 兼容策略。

原生端口是解决桥接生命周期问题的必要条件，但客户端请求健康、共享状态和幂等语义也必须一起完成。

### 4.3 Broker

Broker 应尽量成为无状态协议网关：

- 验证客户端身份和权限；
- 选择适用配置版本；
- 访问共享配置库；
- 消费配置事件并通知自身连接的客户端；
- 访问共享或复制的注册表；
- 维护连接级订阅关系；
- 输出 Broker 选择、RPC 健康、事件延迟和租约指标。

Broker 不应再把进程内 EventBus 或进程内服务实例 Map 当成集群事实源。

### 4.4 客户端 SDK

客户端负责：

- 保存稳定的 <code>Client.open()</code> Session Shell；
- 先根据基础设施 cohort 选择一个协议兼容的 Broker 池；
- 维护 Broker 地址和候选顺序；
- 每次请求尝试只选择一个 Broker，禁止并发 fan-out；
- 维护连接健康与 RPC 健康；
- 在统一 deadline 内最多执行两次顺序尝试；
- 使用每配置键 decisionRevision 或服务端快照 token 去重和补偿；
- 合并重连触发的恢复任务；
- 对定期修复使用退避和 jitter；
- 对重复超时日志进行限速。

<code>onOpen</code> 回调只更新就绪状态，不得使用回调中的临时真实 Channel Session 覆盖稳定 Session Shell。

## 5. Multi-Broker 语义

| 能力 | 目标语义 | 禁止语义 |
|---|---|---|
| 地址管理 | 保存多个 Broker 候选地址 | 把全部地址当成必须同时调用的副本 |
| 普通请求 | 每次尝试选择一个 RPC 健康 Broker | 一次尝试并发发送给全部 Broker |
| 故障切换 | 统一总 deadline 内最多两次顺序尝试 | 每个 Broker 各自拥有完整超时时间 |
| 推送订阅 | 一个活动订阅，切换后按每配置键 revision 或快照 token 补偿 | 为弥补 Broker 本地事件而永久订阅全部 Broker |
| 配置读取 | 从共享事实源读取适用版本 | first-success 竞争，包括让 <code>found=false</code> 抢赢 |
| 服务注册 | 写入具有权威租约所有权的注册表 | 客户端向独立内存注册表多写 |
| 服务查询 | 返回统一 revision 的快照或增量 | 客户端长期合并多份无版本局部视图 |
| 生命周期 | preclose、有限 drain、final close、closing deadline | 永久停留在 closing 状态 |

客户端可以维护一个活动连接和一个热备连接，也可以只维护活动连接并在故障时连接候选地址。无论采用哪种方式，每次尝试都只能发送给一个 Broker；回复丢失后的顺序重试可能被两个 Broker 处理，因此写操作必须复用同一 operationId，并由幂等或 fencing 保证只有一个业务效果。

## 6. 客户端请求健康与故障切换

### 6.1 状态机

~~~mermaid
stateDiagram-v2
    [*] --> DISCONNECTED
    DISCONNECTED --> CONNECTING: 选择候选 Broker
    CONNECTING --> ACTIVE: 连接成功并通过 RPC 健康验证
    CONNECTING --> DISCONNECTED: 连接失败或超时

    ACTIVE --> SUSPECT: 达到连续失败阈值或发生致命协议错误
    ACTIVE --> DRAINING: 收到 preclose
    ACTIVE --> DISCONNECTED: 物理断链

    SUSPECT --> CONNECTING: 停止新流量并强制重连
    DRAINING --> CLOSED: 收到 final close
    DRAINING --> CLOSED: closing deadline 到期
    CLOSED --> CONNECTING: 选择活动或备用 Broker
~~~

### 6.2 健康判定

<code>isActive()</code> 只能表示连接层状态，不能单独作为请求路由条件。建议每个 Broker 至少维护：

- 物理连接状态；
- 是否处于 closing；
- 连续请求成功数和失败数；
- 最近一次成功请求响应时间；
- 最近一次请求超时原因；
- 当前 in-flight RequestStream 数；
- 强制重连次数；
- 熔断或冷却截止时间。

从 SUSPECT 恢复到 ACTIVE 必须依赖一次真实请求响应或经过验证的应用级健康交换，不能仅依赖 ping/pong。

Socket.D 内置 ClusterClient 主要按连接活跃度选择 Session，不能直接承担这里的 RPC 健康、熔断和 cohort 边界策略。客户端需要在其外层增加 Broker Selector。

### 6.3 有界故障切换

一次逻辑调用只有一个总截止时间：

1. 从总 deadline 中为当前 Broker 分配第一段预算；
2. 请求成功则立即结束；
3. 单次调用超时或收到可重试错误时，只要剩余预算足够，即可选择一个备用 Broker 顺序重试一次；
4. 单次失败增加该 Broker 的 RPC 失败计数；达到连续失败阈值或发生致命协议错误时，才将其标记为 SUSPECT 并熔断；
5. SUSPECT 和 DRAINING Broker 不得参与任何新请求选择；
6. 总 deadline 到期后结束，不再继续遍历全部 Broker；
7. 只有 Broker 已进入 SUSPECT、处于 closing 或 Transport 已失效时，才在后台关闭和重连；未达到阈值的单次可重试失败只增加健康计数。

不能为每个 Broker 重新计算一份完整请求超时时间，否则最坏延迟会随 Broker 数量线性增加。

## 7. 重试、幂等与 fencing

读取请求通常可以安全重试，但注册、续租、注销和其他写操作必须防止“服务端已执行但回复丢失”导致的重复副作用。

建议写请求携带：

- <code>operationId</code>：一次业务操作在跨 Broker 重试时保持不变；
- <code>clientInstanceId</code>：稳定客户端身份；
- <code>leaseId</code>：当前注册租约；
- <code>epoch</code>：租约所有权代次；
- <code>expectedRevision</code>：需要条件更新时使用。

服务端要求：

- 在明确保留期内保存 <code>operationId → result</code> 幂等记录，同一操作的乱序或跨 Broker 重放返回原结果；
- 旧 epoch 的 heartbeat 或 deregister 不能修改新租约；
- epoch 由权威存储原子分配并返回，客户端不得自行递增；
- 同一次 register 的重试返回原 leaseId 和 epoch，只有租约失效或显式接管才生成更高 epoch；
- 超时后的迟到回复不能重新激活已结束的客户端请求；
- 配置发布与回滚使用数据库事务或等价原子语义。

## 8. 配置中心设计

### 8.1 概念数据模型

配置内容、投放规则和当前决策需要分离：

| 对象 | 作用 |
|---|---|
| ConfigContent | 保存不可变配置内容、内容版本和内容哈希 |
| ReleaseDecision | 表示 configKey 当前稳定版本、灰度规则引用和单调 decisionRevision |
| RolloutRule | 保存 selector 类型、selector 数据、百分比、固定 seed、有效期和 fallback |
| ConfigOutbox | 与发布或回滚事务同时写入的待投递事件，包含全局或分区单调 eventId/eventSequence |

内容版本回答“内容是什么”，decisionRevision 回答“当前选版决策发生了第几次变化”。回滚即使重新指向历史内容，也必须生成更高的 decisionRevision。

<code>configKey</code> 由 namespace、group 和 dataId 共同组成。decisionRevision 只在一个 configKey 内比较，不能充当整个 Outbox 或事件流的消费 cursor。

### 8.2 适用版本查询

所有 Broker 使用同一套服务端权威逻辑：

~~~text
findApplicableRelease(clientIdentity, namespace, group, dataId)
~~~

客户端身份至少包含稳定 <code>clientInstanceId</code>，按需要附加 IP、应用、环境和可信 tags。

百分比灰度使用固定 rollout key 和 seed 进行确定性哈希。不能在每次查询时随机选择，也不能因为新建 releaseId 就意外重洗整个灰度人群。

### 8.3 推送与补偿

Broker 不推送完整配置内容，只推送失效通知：

~~~text
{
  namespace,
  group,
  dataId,
  decisionRevision,
  eventId
}
~~~

客户端处理流程：

1. 按完整 configKey 比较本地 decisionRevision；
2. 忽略重复或更旧通知；
3. 携带完整客户端身份向当前活动 Broker 拉取适用版本；
4. 原子更新本地配置与 revision；
5. 重连后按每配置键 decisionRevision 批量校验，或携带服务端签发的配置快照 token 进行补偿。

发布与事件必须可靠关联：

~~~mermaid
sequenceDiagram
    participant Admin as 管理端
    participant DB as 配置库
    participant Relay as Outbox Relay
    participant Bus as 持久事件流
    participant Broker as 当前 Broker
    participant Client as 客户端

    Admin->>DB: 事务写入内容、规则、decisionRevision、Outbox
    DB-->>Admin: 发布或回滚成功
    Relay->>DB: 读取未投递 Outbox
    Relay->>Bus: 至少一次发布 configKey + decisionRevision + eventId
    Bus-->>Broker: 按 Broker 独立 cursor 投递
    Broker-->>Client: invalidate(configKey, decisionRevision, eventId)
    Client->>Broker: fetch(clientIdentity, configKey)
    Broker->>DB: findApplicableRelease(...)
    DB-->>Broker: 适用内容与 decisionRevision
    Broker-->>Client: 更新结果
~~~

每个 Broker 都必须收到相关事件。若事件产品使用 consumer group 竞争消费，必须为每个 Broker 设置独立消费身份或使用真正的广播语义，不能让一个事件只被集群中的某一个 Broker 消费。

Outbox 和事件流采用至少一次投递，并要求同一 configKey 内保序；Relay 与 Broker 消费端都必须按 eventId 幂等。仅使用不可恢复的 Pub/Sub 时，必须依靠每配置键 decisionRevision、快照 token 和定期补偿保证最终收敛；更推荐持久事件流。

## 9. 服务发现设计

### 9.1 注册表数据

注册表分为权威租约状态和可复制查询视图。租约所有权、epoch 分配、续租和条件删除必须由线性一致或单写权威存储执行；服务列表查询和 Watch 视图可以异步复制。

权威租约记录至少包含：

- serviceName；
- instanceId；
- endpoint 和 metadata；
- leaseId；
- epoch；
- expiresAt；
- registryRevision。

幂等记录单独保存 <code>operationId → result</code>，并设置覆盖最大网络重试和迟到消息窗口的保留期，不能只在实例记录中保存一个 lastOperationId。

### 9.2 注册与续租

- 首次 register 由权威存储原子创建 leaseId 和 epoch；
- 同一 operationId 的 register 重试返回原 leaseId 和 epoch，不得再次提升 epoch；
- Broker 切换优先续租原 lease，只有租约已失效或执行显式接管时才原子生成更高 epoch；
- heartbeat 只能续租匹配 leaseId 和 epoch 的记录；
- deregister 只能删除匹配 leaseId 和 epoch 的记录；
- 租约到期使用权威存储的服务端时间判定；
- Broker 切换不改变逻辑 instanceId；
- 跨 Broker 重试复用 operationId。

### 9.3 查询与 Watch

客户端从当前活动 Broker 获取：

- 带 registryRevision 的完整快照；
- 或从 last-seen registryRevision 开始的增量事件。

当增量游标过旧或发生缺口时，Broker 明确要求客户端重新获取快照。客户端不再维护 Broker A、B、C 三份局部视图，也不再自行推断某个断开 Broker 中的实例何时应该删除。

### 9.4 隔离与背压

配置读取与注册心跳可以部署在同一 Broker 进程，但至少需要逻辑隔离：

- 独立线程池或并发额度；
- 独立超时和队列；
- 独立限流；
- 独立指标；
- 注册心跳风暴不能拖垮配置读取和配置推送。

## 10. 灰度与回滚

### 10.1 两套独立状态机

| 类型 | 决策内容 | 典型身份 | 回滚动作 |
|---|---|---|---|
| 基础设施灰度 | 客户端连接旧 Broker 池还是新 Broker 池 | 客户端版本、实例、区域、cohort | 将 cohort 路由回兼容 Broker 池并重建连接 |
| 配置灰度 | 客户端读取哪个配置内容 | clientInstanceId、IP/CIDR、tags、百分比桶 | 关闭规则或指向稳定内容，并递增 decisionRevision |

回滚 Broker 二进制不会自动回滚配置决策；回滚配置也不会修复半死连接。两套状态机必须分别监控和验收。

基础设施 cohort 必须先选定一个协议兼容 Broker 池，普通请求 failover 只能在该池内发生。跨旧/新 Broker 池或 native/bridge Transport 的切换只能由显式基础设施发布或回滚改变，并且必须重建连接，不能由自动 failover 越界选择。

### 10.2 灰度一致性不变量

对于相同的客户端身份、完整 configKey 和同一已提交 decisionRevision 或决策快照：

~~~text
push 引发的重新拉取结果
    =
主动 fetch 结果
    =
定期修复结果
    =
任意健康 Broker 返回结果
~~~

当前 <code>GRAY_IP</code> 和 <code>GRAY_PERCENTAGE</code> 在缺少 selector、seed 和 applicable-release 查询前应保持隐藏或禁用。

## 11. 关闭与重连

Broker 二进制或基础设施发布、回滚、停机摘流时：

1. 入口停止接收新的连接或从基础设施流量池摘除；
2. 对现有 Session 发送 preclose；
3. Broker 停止向该 Session 分配新工作，并对竞态到达的新请求返回可识别的 retryable draining 错误；
4. 在有限 drain 窗口内等待 in-flight 请求结束；
5. 发送 final close 并关闭物理连接；
6. 客户端切换到其他健康 Broker。

客户端收到 preclose 后进入 DRAINING。若 final close 丢失，closing deadline 到期必须强制关闭和重连，不能依赖 heartbeat 自行恢复。

## 12. 可观测性

建议至少采集：

| 指标 | 维度或说明 |
|---|---|
| Session 状态 | broker、endpoint、ACTIVE/SUSPECT/DRAINING/CLOSED |
| RPC 超时计数 | broker、event、错误类型 |
| 连续请求失败数 | broker |
| 当前选中 Broker | client、plane |
| failover 次数与耗时 | source、target、reason |
| in-flight Stream 数 | broker、request/subscribe |
| closing 持续时间 | broker、close code |
| reconnect 次数 | broker、reason |
| 配置 decisionRevision | client、本地、Broker |
| 事件消费延迟 | Broker event cursor 与最新 eventSequence 差值 |
| 注册表 revision | Broker 和客户端 last-seen registryRevision |
| 租约到期与 fencing 拒绝数 | service、Broker |
| 每分钟 timeout 日志数 | Broker、调用类型 |

超时日志应按 Broker、事件和错误原因聚合限速，同时保留计数指标，避免故障期间日志本身成为额外压力。

## 13. 迁移计划

### 阶段 0：冻结风险能力并补充观测

- 隐藏或禁用未真正实现的灰度类型；
- 增加 Session 状态、RPC timeout、选中 Broker、Stream 数和日志速率指标；
- 建立生产 SmartHTTP bridge 的故障复现测试。

退出条件：

- 能区分 active-but-RPC-dead 与 valid-but-closing；
- 能复现灰度发布或回滚后的持续超时；
- 告警可以定位到具体 Broker 和事件。

### 阶段 1：客户端安全改造

- 只保存 <code>Client.open()</code> 返回的稳定 Session Shell；
- 配置读取由 fan-out 改为单 Broker 路由；
- 增加请求健康状态、SUSPECT、closing deadline 和有界故障切换；
- 合并重连恢复和周期修复；
- 为写请求增加 operationId、leaseId 和 epoch；在服务端幂等或 fencing 完成前，不自动跨 Broker 重试非幂等写操作。

退出条件：

- 一个 Broker 故障时请求数量不再乘以 N；
- 故障 Session 能退出流量并被替换；
- 配置读取不再 fan-out；缺少幂等保障的写操作不会被自动跨 Broker 重试。

### 阶段 2：原生 Socket.D 双栈

- 增加原生 Socket.D 独立端口；
- 保留 SmartHTTP bridge 作为明确的旧端点；
- 客户端增加端点能力配置；
- 小批 cohort 选择原生 Broker 池，自动 failover 只在该池内发生；
- 一个请求尝试只走一种 Transport，切换 native/bridge 必须重建连接；
- 在阶段 3 完成前，配置推送可暂时保留旧订阅覆盖方式，但必须按 configKey decisionRevision 去重；不得恢复请求 fan-out。

退出条件：

- 原生链路请求、推送、preclose、close 和重连测试通过；
- 新旧客户端与新旧 Broker 兼容结果明确；
- 原生链路 timeout 和 Stream 泄漏指标满足目标。

### 阶段 3：配置共享事件

- 发布事务写入 ConfigOutbox；
- 建立可恢复事件流；
- 每个 Broker 独立消费配置事件；
- 推送改为 invalidation + decisionRevision；
- 客户端支持每配置键 decisionRevision 或快照 token 补偿；
- 完成后将配置推送收敛为一个活动订阅。

退出条件：

- 客户端只连接一个 Broker 也不会漏掉其他 Broker 发起的发布；
- Broker 重启后能够恢复事件；
- 重复事件不会重复触发业务配置更新。

### 阶段 4：权威租约注册表与复制视图

- 引入权威 lease、服务端原子 epoch、TTL、幂等记录和 registryRevision；
- Broker 改为注册表无状态网关；
- 客户端停止向所有 Broker 多写；
- 客户端停止合并多份 Broker 本地视图。

退出条件：

- 切换 Broker 后服务视图 revision 连续或能够明确重建快照；
- stale heartbeat/deregister 被 fencing 拒绝；
- register 跨 Broker 重放返回原 leaseId 和 epoch；
- 心跳风暴不会影响配置读取。

### 阶段 5：真正的灰度与回滚

- 配置内容、ReleaseDecision、RolloutRule 分离；
- 实现稳定身份、selector 和确定性哈希；
- 所有 Broker 统一执行适用版本查询；
- 回滚递增 decisionRevision；
- push、pull、定时修复结果一致。

退出条件：

- 灰度 cohort 稳定且可解释；
- 非目标客户端从未读取灰度内容；
- 回滚后所有目标客户端在规定时间内收敛。

### 阶段 6：下架旧链路

- 完成全部支持客户端迁移；
- 关闭桥接端点的回滚窗口；
- 删除 <code>ToSocketdWebSocketListener</code> 生产端点；
- 删除补发 ping workaround；
- 删除配置请求 fan-out 和客户端发现联邦代码。

## 14. 验收矩阵

| 场景 | 必须满足 |
|---|---|
| 旧客户端 → 旧端点 | 迁移期基线保持正常 |
| 新客户端 → 原生端点 | 请求、推送、关闭和重连正常 |
| 新旧 Broker 混合 | 每次尝试只到一个 Broker，自动 failover 不越过 cohort Broker 池 |
| 一个 Broker 丢回复但连接仍 open | 单次调用可顺序重试；达到阈值后 Session 进入 SUSPECT |
| preclose 后 final close 丢失 | closing deadline 强制替换连接 |
| 超时后迟到回复 | 迟到回复被丢弃，Stream 数保持有界 |
| Broker 切换时 register 重放 | 同一 operationId 返回原 leaseId 和 epoch |
| 旧 heartbeat/deregister 迟到 | 旧 epoch 被权威存储条件更新拒绝 |
| 配置事件丢失或 Broker 重启 | 每配置键 revision 或快照 token 补偿成功 |
| 重复配置事件 | decisionRevision 去重 |
| Relay 发布成功但回写状态前崩溃 | eventId 幂等，不产生错误业务更新 |
| Broker 处理事件后、提交 cursor 前崩溃 | 重放事件被幂等去重 |
| 配置灰度 | push 与 pull 选中同一内容 |
| 配置回滚 | decisionRevision 增加并收敛到稳定内容 |
| 基础设施灰度回滚 | 连接回到兼容 Broker 池，不产生永久半死 Session |
| 重连风暴 | 恢复任务合并、退避并带 jitter |
| 单 Broker 故障 | timeout 日志速率有界，请求量不乘以 N |

端到端测试必须启动与生产相同的 Transport。原生 Socket.D 测试不能证明 SmartHTTP bridge 正确，SmartHTTP bridge 测试也不能替代原生端口验收。

## 15. 待确认的技术选型

以下项目不影响目标架构方向，但在实施前需要形成独立决策：

1. 原生 Socket.D WebSocket Transport 使用 Java-WebSocket 还是 Netty。
2. 独立 Socket.D 端口、TLS 终止和基础设施入口方式。
3. 配置持久事件流使用数据库 Outbox 表、Redis Streams、NATS JetStream、Kafka 或其他方案。
4. 服务注册表使用专用一致性系统、共享存储加租约层，还是事件日志加物化视图。
5. 客户端维护一个活动连接还是一个活动连接加一个热备连接。
6. 每类请求的总 deadline、SUSPECT 阈值、冷却时间和 closing deadline。

这些选型必须通过容量、延迟、故障恢复和运维复杂度验证，不能只根据正常路径吞吐决定。

## 16. 最终不变量

完成迁移后，系统必须始终满足：

1. 每次请求尝试只发送给一个 Broker，禁止并发 fan-out；顺序重试通过 operationId、幂等或 fencing 保证单一业务效果。
2. 连接存活和请求健康分别判定。
3. 所有 Broker 对同一客户端身份返回相同适用配置。
4. 所有 Broker 能按独立 event cursor 恢复并传播完整配置事件，decisionRevision 与 eventSequence 严格分离。
5. 服务实例租约由线性一致或单写权威存储管理，并具有服务端分配的 epoch 和单调 revision。
6. 跨 Broker 重试具备幂等或 fencing。
7. preclose 必须在有限时间内进入 final close 或客户端强制关闭。
8. 回滚产生新的决策版本，而不是让客户端依赖旧缓存猜测。
9. 基础设施灰度与配置灰度分别观测、分别回滚。
10. 单 Broker 故障不会造成请求和日志按 Broker 数量放大。
