# Xuantong Socket.D / SmartHTTP / Multi-Broker 架构评审

> 评审日期：2026-07-16  
> 项目版本：2.0.0-SNAPSHOT  
> 关键依赖：Solon 4.0.3、Socket.D 2.6.0、SmartHTTP 2.5.19、Java 21（服务端）/ Java 8（客户端）

## 1. 结论摘要

当前架构的问题不是单一的 Socket.D 超时参数，而是以下机制串联形成的系统性故障：

目标架构和分阶段实施方案见：[Socket.D 目标架构设计](socketd-target-architecture-design.md)。

1. 生产端点通过 `ToSocketdWebSocketListener` 复用 SmartHTTP WebSocket，而不是使用原生 Socket.D Server。
2. Socket.D 业务监听器异步执行，桥接层却在没有等待 WebSocket 写 Future 的情况下向 Socket.D 报告发送完成。
3. 物理 WebSocket 可以保持 open，但请求/回复链路已经失效；Socket.D 的 `isActive()` 无法表达这种 RPC 半死状态。
4. 配置客户端把每次读取同时发送给所有 Broker，使一次故障扩大为 N 次请求、N 个流和多条日志。
5. RPC 超时不会主动废弃连接，连续 ping 还可能维持“物理存活、RPC 失效”的连接。
6. 配置定时核对、发现心跳和重连恢复会不断重新制造请求，因此灰度发布或回滚触发故障后，客户端会持续打印超时。

最终判断：

- “配置多个 Broker 地址并支持故障切换”是合理的。
- “每个客户端连接全部 Broker，并将每个普通操作 fan-out 到全部 Broker”不合理。
- 配置中心的当前 Multi-Broker 实现应下架。
- 服务发现的客户端侧多 Broker 联邦只能作为过渡方案，不宜成为长期默认架构。
- 正确目标是：控制面使用原生 Socket.D Transport、一次请求选择一个健康 Broker、Broker 层共享事件和状态。

## 2. 官方文档阅读范围

本次对 `https://socketd.noear.org` 做了站内链接闭包抓取：

- 从首页、协议、学习和案例入口开始。
- 对同域全部 `/article/*` 链接做广度优先遍历。
- 处理相对链接，去除 query、fragment 和重复 URL。
- 每篇使用 `?format=md` 获取正文。
- 共访问 120 个 HTML 页面，发现并读取 119 篇文章。
- 下载成功 119 篇，失败 0 篇。
- 空白页、图片页、导航页和重复页均未跳过。

完整索引和逐页摘要已随 Codex Skill 保存：

- [119 篇完整索引](codex-skills/socketd-architecture-review/references/socketd-docs-index.md)
- [119 篇逐页摘要](codex-skills/socketd-architecture-review/references/socketd-docs-digest.md)

重点官方文档：

- [Solon 原生 solon-server-socketd](https://solon.noear.org/article/1144)
- [复用 Solon WebSocket 端口的桥接方案](https://socketd.noear.org/article/783)
- [Socket.D 单连与多连](https://socketd.noear.org/article/786)
- [Broker 集群高可用](https://socketd.noear.org/article/802)
- [超时概念](https://socketd.noear.org/article/775)
- [安全关闭](https://socketd.noear.org/article/807)
- [自动重连](https://socketd.noear.org/article/808)

## 3. 当前实际运行路径

### 3.1 服务端

项目引入了：

- `solon-web`，间接使用 `solon-server-smarthttp`；
- `solon-net`；
- `socketd-transport-java-websocket`；
- 没有引入 `solon-server-socketd`。

生产端点：

- [`ConfigBrokerV2Endpoint`](../xuantong-server/src/main/java/cloud/xuantong/core/v2/listener/ConfigBrokerV2Endpoint.java)
- [`DiscoveryBrokerV2Endpoint`](../xuantong-server/src/main/java/cloud/xuantong/core/v2/listener/DiscoveryBrokerV2Endpoint.java)

两个类均继承 `ToSocketdWebSocketListener`，所以真实链路是：

```text
Socket.D Java Client
  → WebSocket
  → SmartHTTP
  → Solon WebSocketRouter
  → ToSocketdWebSocketListener
  → Socket.D ProcessorDefault
  → Config/Discovery Broker Listener
```

`app.enableSocketD(true)` 并不会让这两个端点自动切换为原生 Socket.D Server，因为 `ToSocketdWebSocketListener` 首先是一个 `WebSocketListener`，Solon 会把它注册到 WebSocketRouter。

### 3.2 配置客户端

[`SocketDTransport`](../xuantong-client-core/src/main/java/cloud/xuantong/client/transport/impl/SocketDTransport.java) 的行为：

- 为每个 Broker 创建独立、自动重连的 Socket.D Client。
- 每个 `fetch(dataId)` 同时向所有 active Broker 发送 `/get`。
- 第一个没有抛异常的结果获胜。
- 取消其他 Java Executor Future。
- 每个连接 open/close 都可能触发配置全量核对。

[`ConfigCore`](../xuantong-client-core/src/main/java/cloud/xuantong/client/core/ConfigCore.java) 还会每 30 秒核对所有已缓存 dataId。

### 3.3 服务发现客户端

[`SocketDDiscoveryTransport`](../xuantong-client-core/src/main/java/cloud/xuantong/client/transport/impl/SocketDDiscoveryTransport.java) 会：

- 向每个 active Broker 注册同一个实例。
- 向每个 Broker 发送心跳、注销和查询。
- 分别保存每个 Broker 的服务视图。
- 通过 [`MultiBrokerServiceState`](../xuantong-client-core/src/main/java/cloud/xuantong/client/transport/impl/MultiBrokerServiceState.java) 对实例做并集合并。

服务端实例状态位于 [`ServiceInstanceRegistry`](../xuantong-discovery-core/src/main/java/cloud/xuantong/core/v2/service/ServiceInstanceRegistry.java) 的进程内 `ConcurrentHashMap`。这意味着当前发现 Multi-Broker 本质上是“客户端侧联邦”，不是简单的连接故障切换。

## 4. SmartHTTP 桥接风险

### 4.1 异步边界

Socket.D 的 `ProcessorDefault` 会把业务 Listener 提交到 Socket.D 工作线程。SmartHTTP 的当前二进制 WebSocket 回调可以在 Listener 真正生成回复前返回。

回复发生在另一个线程和稍后的时间点：

```text
SmartHTTP 收到请求帧
  → 调用 ToSocketdWebSocketListener
  → Socket.D 提交异步业务任务
  → SmartHTTP 当前回调结束
  → 业务线程调用 replyEnd
  → 桥接层写 WebSocket 回复
```

### 4.2 写完成语义不足

Solon 4.0.3 的桥接层调用 `target.send(buffer)` 后没有等待 `Future<Void>`，立即向 Socket.D 报告 completion success。

SmartHTTP 的 `WebSocketImpl.send(ByteBuffer)` 自身也是在执行 `sendBinaryMessage + flush` 后立即完成 Future。这个 Future 不代表对端已经收到，也不能为 Socket.D 提供可靠的请求回复完成语义。

### 4.3 项目已有显式旁证

[`SocketDRpcSupport`](../xuantong-client-core/src/main/java/cloud/xuantong/client/transport/impl/SocketDRpcSupport.java) 的注释已经记录：SmartHTTP 2.5.x 可能让孤立的二进制回复停留到下一次 WebSocket I/O。

当前实现会在一次 5 秒 RPC 内的多个检查点发送 Socket.D ping。它可以偶然推动输出，但存在问题：

- ping 不是原请求回复的确认机制；
- 每个请求都会额外制造多次流量；
- Multi-Broker 会把 ping 数量再乘以 Broker 数；
- 它可能让半死连接继续表现为物理存活；
- 无法修复关闭帧丢失、回复丢失或异步写失败。

因此它只能作为临时诊断开关，不能作为长期架构。

## 5. 持续超时的完整故障链

```text
基础设施灰度/滚动发布或回滚
  → 新旧服务实例和连接生命周期交错
  → 桥接回复、推送或最终 close 未被客户端正确收到
  → WebSocket 仍保持 open
  → session.isActive() 仍为 true
  → 下一次请求继续选择该 Session
  → RequestStream 5 秒超时
  → 超时只清理该流，不会废弃连接
  → 30 秒配置核对 / 10 秒发现心跳 / reconnect recovery 再次发请求
  → Multi-Broker 将请求和日志放大 N 倍
  → 客户端持续打印超时
```

需要区分两种连接故障：

### 5.1 ACTIVE 但 RPC 已失效

- 底层连接仍 open。
- `isActive()` 返回 true。
- ping/pong 可能仍正常。
- 业务请求没有回复。
- 这是持续超时日志最可能的直接来源。

### 5.2 收到 preclose，但最终 close 丢失

- Session 进入 `isClosing=true`。
- Socket.D 心跳处理会跳过 closing Session。
- 服务端已经尝试 `preclose → 等待 → close`，但桥接无法保证最终 close 送达。
- 客户端没有 closing deadline，可能长期停留在 draining/closing 状态。

两种状态必须分别监控和恢复。

## 6. Session Shell 保存错误

Socket.D Client 内部存在两层 Session：

- 真实 Channel 的临时 Session；
- `.open()` 返回的稳定 Session Shell，负责将发送转到当前真实 Channel，并提供发送时自动连接检查。

首次连接时，Socket.D 会先触发 `onOpen`，随后才把真实 Channel 的 Session 替换为稳定 Shell。

当前配置和发现 Transport 都在 `doOnOpen(session -> handleOpened(...))` 中将回调参数保存到 `sessions` Map。之后 `openAllSessions()` 使用 `putIfAbsent` 保存 `.open()` 返回的稳定 Shell，因为 Map 已有值，所以稳定 Shell 无法替换临时 Session。

后果：

- 请求可能直接使用真实 Channel Session；
- 绕过 `ClientChannel.send()` 的自动连接检查；
- 发送失败时的自动重连能力变弱；
- 每次 reconnect 又可能保存新的临时 Session。

修复原则：

- Map 中只保存 `.open()` 返回的稳定 Session。
- `onOpen` 回调只负责更新 Broker 状态和执行恢复，不保存回调 Session。

## 7. Multi-Broker 评估

### 7.1 Socket.D 官方语义

官方 `ClusterClient` 会连接多个地址，但普通 `send`、`sendAndRequest` 和 `sendAndSubscribe` 每次只选择一个 active Session。只有显式遍历全部 Session 时才是广发。

所以应区分：

- 多连接：连接和地址冗余；
- 单请求选择：负载均衡或故障切换；
- fan-out：明确的业务广播或多写操作。

### 7.2 配置中心

当前模式不合适：

- 配置是单一事实源，不需要每次并发读全部 Broker。
- 第一个 `found=false` 也会作为成功结果返回；如果 Broker 数据库不一致，会出现随机“配置不存在”。
- Java Future 取消不会取消底层 Socket.D RequestStream。
- Broker 数越多，流、ping、线程和告警越多。
- 当前多连接主要是在补偿 Broker 之间没有传播配置事件。

应改为：

- 每次只选择一个 RPC 健康的 Broker。
- 失败时在总 deadline 内切换一次。
- Broker 层通过共享事件日志/Outbox 传播发布事件。
- 客户端接收 revision invalidation，再按身份拉取适用版本。

### 7.3 服务发现

当前多写有业务原因：实例状态是 Broker 本地内存。如果只连接一个 Broker，客户端看不到其他 Broker 的实例。

但代价是：

- 连接数和心跳数约为 `客户端数量 × Broker 数量`；
- 客户端承担一致性、合并、去重、断连移除和 stale view 处理；
- Broker 没有统一 revision；
- 运维和故障模型复杂。

长期应将实例状态放到共享或复制的注册表，由任意 Broker 返回同一服务视图。完成后，发现客户端也改为一个 active Broker 加故障切换。

如果短期保留客户端联邦，必须明确：

- 每个 Broker 的成功状态；
- 最小成功数或 quorum；
- stale view 时限；
- Broker 断开后的实例移除语义；
- 重注册与心跳重试策略；
- 容量上限和告警指标。

## 8. 当前灰度发布并未实现

[`ReleaseType`](../xuantong-config-core/src/main/java/cloud/xuantong/core/v2/model/ReleaseType.java) 声明：

- `FULL`
- `GRAY_IP`
- `GRAY_PERCENTAGE`
- `ROLLBACK`

但是 [`ConfigRelease`](../xuantong-config-core/src/main/java/cloud/xuantong/core/v2/model/ConfigRelease.java) 只有 `releaseType` 字段，没有：

- 目标 IP/CIDR；
- 百分比；
- client tag 或 application cohort；
- 稳定 hash seed；
- rollout rule；
- stable fallback release。

服务端 `/get` 使用 `findLatest(namespace, group, dataId)`，没有客户端身份；发布和回滚都向 `subscriber*` 广播。

因此：

```text
GRAY_IP / GRAY_PERCENTAGE = 发布历史标签
                         ≠ 灰度选择规则
```

而且配置灰度与基础设施金丝雀是两个不同状态机：

- 基础设施金丝雀决定客户端连到哪个版本的 Broker。
- 配置灰度决定某个客户端应该读取哪个配置 revision。

回滚 Broker 二进制不会自动回滚配置，回滚配置也不会修复半死连接。

正确灰度模型至少需要：

1. 稳定 `clientInstanceId`。
2. selector 类型和数据。
3. 确定性百分比函数，例如 `hash(rolloutKey, clientInstanceId, seed) % 100`；rolloutKey 在同一次灰度策略期间保持稳定，不能因新建内容版本而重洗人群。
4. 稳定版本 fallback。
5. Broker 一致的 `findApplicableLatest(clientIdentity, dataId)`。
6. push 与 pull 对同一客户端得到相同版本。
7. 回滚时原子停用灰度规则并生成新的可审计 revision。

在这些能力完成前，应禁用或隐藏 `GRAY_IP`、`GRAY_PERCENTAGE` API。

## 9. 推荐目标架构

```text
                       ┌─────────────────────┐
Admin / HTTP API ─────▶│ SmartHTTP :8088     │
                       └─────────────────────┘

                       ┌─────────────────────┐
Client ─ native SD:WS ─▶│ Broker A            │──┐
       one attempt      └─────────────────────┘  │
       one Broker       ┌─────────────────────┐  ├─ Shared DB / Outbox / Registry
       sequential retry ▶│ Broker B            │──┘
                       └─────────────────────┘
```

关键设计：

- HTTP 管理面继续使用 SmartHTTP。
- `/config-v2`、`/discovery-v2` 使用独立端口的原生 Socket.D WebSocket Transport。
- Client 保存多个地址，每次尝试只选择一个 Broker；失败后只能在总 deadline 内顺序重试。
- 使用 `ACTIVE / SUSPECT / DRAINING / CLOSED` 请求健康状态。
- 连续请求超时后将 Broker 标为 suspect，并强制关闭/重建连接。
- preclose 后设置客户端 closing deadline；超过 deadline 未收到 final close 就强制重连。
- 配置事件通过 Broker 层共享 Outbox/事件流传播。
- 服务发现使用权威租约注册表；查询和 Watch 视图可以带 revision 异步复制。

## 10. 迁移顺序

### P0：止损

1. 禁用灰度类型入口。
2. 修复稳定 Session Shell 保存问题。
3. 增加请求级健康计数和 timeout 后的 Broker 隔离。
4. 增加 closing deadline。
5. 合并 reconnect 触发的重复配置核对。
6. 对重复 timeout 日志限速并增加计数指标。

### P1：客户端请求链路

1. 配置读取取消 fan-out。
2. 每次尝试选择一个 Broker，失败后在总 deadline 内顺序重试一次。
3. 将单次请求重试与连续失败阈值分离；达到阈值后才将 Broker 标记为 suspect。
4. 写操作引入 operationId；在服务端幂等或 fencing 完成前，不自动跨 Broker 重试非幂等写操作。

### P2：原生 Socket.D 迁移

1. 新增原生 Socket.D WebSocket 端口，暂时保留旧桥接端点。
2. 客户端增加 native/bridge endpoint 能力选择。
3. 先让小比例客户端使用 native endpoint。
4. 比较成功率、timeout、stream 清理、close/reconnect 和 revision 一致性。
5. 分批扩大 native endpoint 使用范围。
6. 保留一个有明确期限的 bridge 回滚窗口。
7. 全部支持客户端完成迁移后，下架桥接和 ping workaround。

同一个逻辑请求不能同时发送到 native 和 bridge 两条链路，否则会重新引入 fan-out、副作用重复和响应竞速。

### P3：配置共享事件

1. 引入配置 Outbox 和持久事件流。
2. decisionRevision 按 configKey 单调递增，Outbox 使用独立 eventId/eventSequence。
3. 每个 Broker 使用独立 cursor 消费事件，并按 eventId 幂等。
4. 推送 configKey、decisionRevision 和 eventId，不推送完整配置内容。
5. 客户端按每配置键 revision 或服务端快照 token 补偿，完成后收敛为一个活动订阅。

### P4：服务发现

1. 将租约所有权、epoch 分配和条件更新迁移到线性一致或单写权威注册表。
2. 查询和 Watch 视图可以异步复制，但 stale heartbeat/deregister 必须由权威存储 fencing。
3. Broker 订阅统一实例事件。
4. 客户端由多 Broker 联邦改为单 active Broker 加故障切换。

### P5：真正的灰度发布

完成 selector、匹配规则、适用版本查询、回滚语义和一致性测试后再重新开放灰度类型。

## 11. 测试矩阵

必须启动与生产一致的 SmartHTTP 桥接或原生 Socket.D Endpoint，不能只测试 `SocketD.createServer("sd:ws")`。

| 场景 | 验收条件 |
|---|---|
| 旧客户端 → 旧服务端 | 请求、推送、关闭、重连基线正常 |
| 旧客户端 → 新服务端 | 明确兼容或明确失败，不产生半死连接 |
| 新客户端 → 旧服务端 | 同上 |
| 新旧 Broker 混合 | 每次尝试只到一个兼容 Broker，失败后在同一 Broker 池内有界切换 |
| 配置灰度 | push 与 pull 对同一身份选择相同 revision |
| 灰度回滚 | 目标客户端收敛到 rollback revision，非目标客户端从未看到灰度版本 |
| preclose + final close | drain 后建立新连接 |
| preclose 但 final close 丢失 | closing deadline 强制替换 Session |
| open Socket 丢回复 | 单次调用可顺序重试；达到连续失败阈值后 Session 进入 suspect |
| 超时后迟到回复 | 丢弃迟到回复，stream map 保持有界 |
| reconnect storm | 配置核对合并、退避并带 jitter |
| 一个 Broker 故障 | 请求数量不乘以 N，日志速率有上限 |
| 重复 push | revision 去重，不重复触发业务更新 |

需要采集：

- Broker 选择结果；
- Session 状态和 close code；
- 连续 RPC timeout 数；
- Stream 数量；
- reconnect 次数；
- push revision 和 applicable release；
- 每分钟 timeout 日志数量。

## 12. 当前测试结论

本次运行：

- 客户端相关测试 9 个通过。
- 服务端生命周期和网络测试 4 个通过。

但现有真实网络测试 [`SocketDBrokerNetworkTest`](../xuantong-server/src/test/java/cloud/xuantong/core/v2/listener/SocketDBrokerNetworkTest.java) 使用的是原生 `SocketD.createServer("sd:ws")`，未覆盖生产 SmartHTTP 桥接。

其余 Multi-Broker 和 RPC ping 测试主要使用代理、Fake Session 或覆盖网络方法，无法验证：

- 异步回复发生在 SmartHTTP callback 返回之后；
- bridge 回复滞留；
- Future cancel 后的真实 Stream 生命周期；
- preclose 没有 final close；
- 混合版本滚动发布与回滚；
- timeout 日志是否有界。

因此当前诊断属于源码级高置信结论；最终修复必须以生产 Transport 的端到端复现测试作为验收依据。

## 13. 额外一致性风险

默认配置使用节点本地 H2 文件。如果直接以默认配置启动多个 Broker，它们并不共享配置数据。

Config fan-out 中，某个 Broker 返回 `found=false` 也会作为成功结果获胜，因此本地 H2 多节点可能随机返回“配置不存在”。生产 Multi-Broker 必须显式使用同一个外部数据库，并在健康检查中验证数据源身份。

## 14. Codex Skill

本次评审知识已沉淀为可安装 Skill：

- [socketd-architecture-review](codex-skills/socketd-architecture-review/SKILL.md)
- [安装说明](codex-skills/INSTALL.md)

Skill 包含：

- Socket.D 架构评审工作流；
- 119 篇官方文档索引和逐页摘要；
- Socket.D stream、timeout、close、reconnect 和 ClusterClient 语义；
- Solon 原生服务与 SmartHTTP 桥接的源码级区别；
- Xuantong Multi-Broker、灰度和回滚案例；
- 双栈迁移与测试矩阵；
- 可重新抓取官网文档的脚本。
