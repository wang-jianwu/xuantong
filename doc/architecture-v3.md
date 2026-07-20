# 玄同 3.0 极简架构设计

> 文档状态：目标架构草案
>
> 更新日期：2026-07-20
>
> 设计原则：**极简、轻量、零外部依赖、可解释的正确性**

---

## 1. 设计哲学

### 1.1 什么是极简

极简不是功能少，而是**用户的心智模型足够小，以至于可以完全装进脑子里**。

```
一条命令启动，一个目录持久化，理解三个概念就能用：
  1. 配置 (namespace + group + dataId)
  2. 服务 (namespace + group + serviceName)
  3. 节点 (一个进程，一个身份)
```

### 1.2 什么是轻量

轻量不是代码行数少，而是**运行时占用足够少，用户不觉得它是一个负担**。

```
打包体积 < 10MB
启动时间 < 100ms
运行时内存 < 64MB（空载）
零外部依赖（只需要 JVM 21+）
```

### 1.3 不妥协的原则

| 原则 | 说明 |
|---|---|
| **配置中心必须是 CP** | 配置变更不能丢失或分叉；读到旧配置可能导致线上事故 |
| **注册发现接受 AP** | 拿到过期地址就重试，Eureka 已验证可行 |
| **启动零参数** | `java -jar xuantong.jar` 直接可用 |
| **只有 JVM 一个外部依赖** | 不要 SQL、不要 Redis、不要 MQ、不要 ZK |
| **正确性可解释** | 不靠"经过大量测试验证"掩盖复杂性，每处设计都能说清为什么 |

---

## 2. 总览

### 2.1 部署模型

```
用户视角：
  java -jar xuantong.jar
  → 一个进程启动，监听 8090（控制面）和 8088（管理 API）
  → 在本地创建 data/ 目录存放所有持久化数据
  → 内置配置管理和服务发现的完整能力

多节点：
  java -jar xuantong.jar --peers node1:9101,node2:9101,node3:9101
  → 节点间用 Socket.D 自动组网
  → 选举出一个 Config Leader
  → 服务发现走 Gossip，不依赖选主
```

### 2.2 内部架构

```
┌──────────────────────────────────────────────┐
│                  xuantong.jar (~8MB)           │
│                                              │
│  ┌──────────────────────────────────────────┐│
│  │     Socket.D Server (:8090)              ││
│  │     客户端协议入口 / 节点间通信            ││
│  └──────────────┬───────────────────────────┘│
│                 │                            │
│  ┌──────────────▼───────────────────────────┐│
│  │          State Engine                    ││
│  │                                          ││
│  │  ┌──────────────┐ ┌────────────────────┐ ││
│  │  │ Config       │ │ Registry           │ ││
│  │  │ (自研 Raft)  │ │ (Gossip, AP)       │ ││
│  │  │ 强一致, CP   │ │ 最终一致, AP       │ ││
│  │  └──────┬───────┘ └────────┬───────────┘ ││
│  │         │                  │             ││
│  │  ┌──────▼──────────────────▼───────────┐ ││
│  │  │       Append-Only Log               │ ││
│  │  │   所有持久化的唯一存储               │ ││
│  │  └─────────────────────────────────────┘ ││
│  └──────────────┬───────────────────────────┘│
│                 │                            │
│  ┌──────────────▼───────────────────────────┐│
│  │     Admin HTTP API (:8088)               ││
│  │     可禁用，不含 UI                      ││
│  └──────────────────────────────────────────┘│
└──────────────────────────────────────────────┘
```

### 2.3 数据目录结构

```
data/
  ├── state.log           ← 所有写操作的 Append-Only 日志
  ├── state.snapshot      ← 日志的定期压缩快照
  └── peers.conf          ← 节点列表（仅集群模式需要）
```

---

## 3. 持久化：Append-Only Log

### 3.1 设计

整个系统只有**一个持久化文件**：`state.log`。所有状态变更（配置发布、服务注册、服务下线）都**追加写**入这个文件。

重启时，顺序回放日志条目重建内存状态。日志积累到一定大小时，生成新快照并截断旧日志。

### 3.2 日志格式

```
每条记录：
  [4 字节：总长度]
  [4 字节：CRC32 checksum]
  [8 字节：序列号，从 1 开始递增]
  [8 字节：时间戳，epoch 毫秒]
  [1 字节：条目类型]
  [N 字节：JSON 正文]

类型枚举：
  0x01  CONFIG_PUBLISH      配置发布
  0x02  CONFIG_ROLLBACK     配置回滚
  0x03  CONFIG_TOMBSTONE    配置下线
  0x04  SERVICE_REGISTER    服务注册
  0x05  SERVICE_DEREGISTER  服务注销
  0x06  SERVICE_HEARTBEAT   服务心跳（定期压缩可丢弃）
```

### 3.3 压缩策略

```
触发条件：state.log > 100MB 或 距上次压缩 > 1 小时

压缩过程：
  1. 顺序回放 state.log 的所有条目到内存
  2. 将内存状态序列化写入 state.snapshot
  3. 记录当前序列号
  4. 截断 state.log 到该序列号之后的条目
  5. 删除旧 state.log 段

SERVICE_HEARTBEAT 在压缩时直接丢弃（心跳状态不需要持久化到快照）
```

### 3.4 恢复

```
启动恢复：
  1. 读取 state.snapshot，加载内存状态
  2. 从快照记录的序列号 + 1 开始，回放 state.log 的剩余条目
  3. 恢复到当前序列号
  4. 通知上层系统就绪
```

---

## 4. 一致性模型

### 4.1 两种模型、两种用途

| 子系统 | 一致性 | 算法 | 节点关系 | 理由 |
|---|---|---|---|---|
| **Config** | CP，强一致 | 自研 Raft | Leader + Follower | 配置变更不能丢失或分叉 |
| **Registry** | AP，最终一致 | Gossip | 对等 | 拿到过期地址就重试，Eureka 已验证 |

### 4.2 Config：自研 Raft 内核

#### 范围

- ~2000 行纯 Raft 核心
- 只服务于 Config 一个 Group
- 不使用 gRPC，用 Socket.D 做节点间传输
- 不实现 Multi-Group、动态成员变更等工业级特性

#### 实现的功能

```
✅ Leader 选举（含 PreVote）
✅ 日志复制（AppendEntries）
✅ 快照安装（InstallSnapshot）
✅ 线性一致读（ReadIndex）
```

#### 不实现的功能

```
❌ 动态成员变更         → 变更节点需要配置变更后重启
❌ Multi-Group          → 只有 Config 一个 Group
❌ Priority 选主        → 每个节点权重相同
❌ gRPC transport       → 节点间用 Socket.D
❌ Joint consensus      → 不需要（无动态成员变更）
❌ Leadership transfer  → 不需要（无动态成员变更）
```

#### 节点通信

```
Raft 消息（4 种）：
  RequestVote          ← 选举
  AppendEntries        ← 日志复制 + 心跳
  InstallSnapshot      ← 快照安装
  ReadIndex            ← 线性一致读

全部走 Socket.D，序列化用 JSON。
```

### 4.3 Registry：Gossip 协议

#### 设计

```
每个节点持有完整的服务注册表（内存 HashMap）
每 1 秒向随机 3 个对等节点发送 Sync 消息
Sync 消息包含：自己在过去 1 秒内收到的新变更
收到 Sync 后：对比本地版本，处理冲突（最后写入胜）
```

#### Gossip 消息格式

```json
{
  "type": "SYNC",
  "from": "node-1",
  "seq": 1042,
  "changes": [
    {
      "type": "REGISTER",
      "service": "order-service",
      "instanceId": "order-pod-abc123",
      "address": "10.0.1.5:8080",
      "timestamp": 1753000000000
    }
  ]
}
```

#### 过期清理

```
心跳间隔：5 秒
过期时间：30 秒（6 个心跳窗口）
清理时间：60 秒（过期后 30 秒才物理删除）

每个节点独立判断：
  服务持续 30 秒无心跳 → 本地标记为 UNHEALTHY
  标记后 30 秒仍无心跳 → 本地物理删除
  Sync 不传播 UNHEALTHY 状态（减少抖动传播）
```

---

## 5. 子协议设计

### 5.1 Config State Machine

#### 数据模型

```
ConfigStore（内存 + 日志）：
  Map<ConfigKey, LinkedList<Revision>>  ← 每个配置的所有版本
  Map<ConfigKey, ReleaseDecision>       ← 当前生效的发布决策
  long eventRevision                     ← 全局事件序列号

ConfigKey:
  namespace + group + dataId

ReleaseDecision:
  0 → 从未发布（MISSING）
  N → 发布到 Revision N（ACTIVE）
  TOMBSTONE → 已下线

灰度：
  ReleaseDecision 包含灰度规则列表
  规则由 ConfigReleaseSelector 评估
  评估算法是纯函数：SHA-256(rolloutKey + clientId + seed) mod 10000
```

#### 操作与幂等

```
所有 Config 写操作携带 operationId
Raft 提交后记录 operationId → result
重放相同 operationId + 相同 requestHash → 返回原结果
重放相同 operationId + 不同 requestHash → 返回冲突错误
```

#### 读取

```
线性一致读（ReadIndex）：
  1. 向 Leader 请求当前 commitIndex
  2. 等待本地 appliedIndex >= commitIndex
  3. 在本地状态执行读操作
  4. 返回结果

Follower 可以直接提供 Stale Read（返回可能稍旧的值）
```

### 5.2 Registry Store

#### 数据模型

```
RegistryStore（仅内存）：
  Map<String, ServiceDef>               ← 服务名 → 服务定义
  Map<String, Map<String, Instance>>    ← 服务名 → (实例ID → 实例)

Instance:
  instanceId, address, metadata, heartbeatTime

不需要持久化到本地日志（Gossip 提供复制，心跳用于自愈）
```

#### 心跳与过期

```
客户端每 5 秒发送一次心跳
服务端收到心跳 → 更新 heartbeatTime
服务端每 1 秒扫描 → heartbeatTime > 30 秒前 → 标记 UNHEALTHY
服务端每 1 秒扫描 → heartbeatTime > 60 秒前 → 删除

下线是主动操作：
  客户端发送 DEREGISTER
  服务端立即删除（不等到期）
```

---

## 6. 传输协议

### 6.1 统一传输层

```
客户端 → 节点：Socket.D TCP port 8090
节点 → 节点：Socket.D TCP port 9101

同一个库，同一个连接模型，同一个序列化。
不需要 gRPC，不需要 HTTP/2。
```

### 6.2 序列化

```
默认：JSON（Jackson）
  - 可读，调试友好
  - 配置中心的包体很小（几百字节）
  - 性能完全够用

可选：Protobuf
  - 通过构建 profile 引入
  - 高性能场景（百万级客户端）
  - 默认不打包（省体积）
```

### 6.3 客户端连接模型

```
客户端连接到最近的节点（单活动连接）
每个 Agent 同时只连接一个节点
故障时在同一集群的节点列表内顺序切换
不使用 fan-out，不广播请求

Hello 握手：
  客户端 → 节点：{ applicationName, clientInstanceId, accessToken }
  节点 → 客户端：{ sessionId, clusterId }
  成功 → 后续请求用 sessionId
  失败 → 关闭连接
```

---

## 7. 协议消息

### 7.1 Config 消息

```
system/hello            ← 握手
system/probe            ← 健康探测

config/fetch            ← 获取配置的当前 applicable release
config/watch            ← 监听配置变更（长连接，仅通知有变更，不推送值）
config/snapshot         ← 获取批量配置快照

admin/config/publish    ← 管理端发布（HTTP API）
admin/config/rollback   ← 管理端回滚（HTTP API）
admin/config/tombstone  ← 管理端下线（HTTP API）
admin/config/rollout    ← 管理端灰度（HTTP API）
```

### 7.2 Registry 消息

```
discovery/register      ← 服务注册
discovery/deregister    ← 服务注销
discovery/heartbeat     ← 心跳
discovery/list          ← 获取服务实例列表
discovery/watch         ← 监听服务变更（长连接）

gossip/sync             ← 节点之间同步（仅节点间通信）
```

---

## 8. 安全性

### 8.1 Token 鉴权

```
Token 管理：
  - 创建 Token 时生成 256-bit 随机值
  - 存储 SHA-256 指纹（不存原文）
  - 原文仅在创建时返回一次
  - Token 可限制 namespace / group 范围

鉴权流程：
  1. 客户端在 system/hello 中携带 Token
  2. Server 计算 Token 的 SHA-256
  3. 与已知指纹比对
  4. 绑定 Session 到对应的权限范围
```

### 8.2 TLS/mTLS

```
Socket.D 原生支持 TLS
Server: 配置 keyStore + trustStore + clientAuth
Client: 配置 trustStore (+ keyStore for mTLS)
```

---

## 9. 模块组织

### 9.1 核心模块

```
xuantong-protocol/        ← 共享消息定义（极简 Java 类，不用 Protobuf）
xuantong-server/          ← 主模块：装配 + 启动入口
xuantong-client-core/     ← Java SDK
xuantong-spring-boot-starter/
xuantong-spring-cloud-starter/
xuantong-solon-plugin/
```

### 9.2 一个模块内的包结构

```
cloud.xuantong.server
  ├── XuantongServer           ← 启动入口
  ├── config/
  │   ├── ConfigStore          ← 配置状态机
  │   ├── ConfigReleaseSelector ← 灰度选择
  │   └── ConfigController     ← 管理 API
  ├── registry/
  │   ├── RegistryStore        ← 注册表
  │   ├── GossipProtocol       ← Gossip 协议
  │   └── RegistryController   ← 管理 API
  ├── raft/
  │   ├── RaftNode             ← Raft 核心
  │   ├── RaftLog              ← 日志管理
  │   └── RaftTransport        ← 传输接口
  ├── store/
  │   └── AppendOnlyLog        ← Append-Only 日志
  ├── transport/
  │   └── SocketDGateway       ← 客户端 Gateway
  ├── auth/
  │   └── TokenManager         ← Token 管理
  └── admin/
      └── AdminApiController   ← 管理 API 路由
```

---

## 10. 技术栈

| 层面 | 选型 | 理由 |
|---|---|---|
| HTTP 框架 | Solon 4.x | 启动 < 100ms，体积小 |
| 传输层 | Socket.D TCP | 一个库通吃客户端和节点间通信 |
| 序列化（默认） | Jackson JSON | 可读，调试友好，够快 |
| 序列化（可选） | Protobuf | 通过 profile 引入，默认不打包 |
| 共识（Config） | 自研 Raft (~2000 行) | 极简，只需 20% 的 Raft 能力 |
| 共识（Registry） | Gossip (~500 行) | AP，无 Leader |
| 持久化 | Append-Only Log | 一个文件，极简 |
| 数据库 | 无 | 不存在的依赖 |
| 缓存 | 无（堆变量） | JVM 堆就够了 |

---

## 11. 最终打包

```
xuantong-server.jar (~8MB)
  ├── org/noear/solon/          ← Solon HTTP
  ├── org/noear/socketd/        ← Socket.D (含 Netty 适配器)
  ├── com/fasterxml/jackson/    ← JSON
  ├── SLF4J-simple              ← 日志
  └── cloud/xuantong/           ← 所有自研代码

不打包的内容：
  ❌ Protobuf（可选 profile）
  ❌ Ratis / gRPC
  ❌ MySQL / PostgreSQL / H2
  ❌ EasyQuery / Flyway / HikariCP
  ❌ Bootstrap / FontAwesome / 管理 UI（独立下载）
  ❌ Enjoy 模板引擎
```

---

## 12. 实现路线

### 第一阶段：基础设施（3-4 周）

```
[x] Append-Only Log 实现（增、读、压缩、恢复）
[x] Config Store（内存状态机 + 灰度选择器）
[x] Registry Store（内存注册表 + 心跳扫描）
[x] Token 管理（生成、验证、SHA-256 指纹）
```

### 第二阶段：Raft 和 Gossip（3-4 周）

```
[ ] 自研 Raft 内核（选举 + 日志复制 + 快照）
[ ] Gossip 协议（Sync 消息 + 增量交换）
[ ] Socket.D Gateway（客户端连接 + 节点间通信）
[ ] 健康探测（system/hello、system/probe）
```

### 第三阶段：管理 API 和客户端（2 周）

```
[ ] 管理 API（配置 CRUD、灰度、回滚、下线）
[ ] 管理 API（服务定义、实例管理）
[ ] Java Client SDK（Config + Discovery）
[ ] 客户端 Watch 机制（增量推送，不是全量刷新）
```

### 第四阶段：生态集成（2 周）

```
[ ] Spring Cloud Starter 迁移
[ ] Solon Plugin 迁移
[ ] Spring Boot Starter 迁移
[ ] 管理 UI（独立仓库，SPA）
```

---

## 13. 与 2.0 的差异总结

| 维度 | 2.0（当前） | 3.0（目标） |
|---|---|---|
| 共识引擎 | Apache Ratis 3.2.2 | 自研 Raft (~2000 行) |
| 节点传输 | gRPC + Socket.D 两套 | Socket.D 统一 |
| Registry 一致性 | Raft（CP） | Gossip（AP） |
| 持久化 | Raft WAL + SQL | Append-Only Log |
| 数据库 | H2 / MySQL / PostgreSQL | 无 |
| ORM | EasyQuery | 无 |
| Migration | Flyway ×3 方言 | 无 |
| 管理 UI | .shtm 内嵌 | 外置 SPA（独立 JAR） |
| 模块数 | 17 | 5 |
| 编译产物 | ~40MB | ~8MB |
| 外部依赖 | JVM + 可选数据库 | 只有 JVM |
| 启动配置 | 13 个环境变量 | 零配置 |
