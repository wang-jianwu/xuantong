# 玄同 2.0 架构设计

这份文档解释玄同为什么这样设计。第一次使用不用先读它，照着[快速入门](quick-start.md)启动就行。

## 1. 整体怎么工作

~~~mermaid
flowchart LR
    APP["Java 应用"]
    GW["Gateway<br/>Socket.D 8090"]
    CONFIG["Config State"]
    REGISTRY["Registry State"]
    ADMIN["后台和 HTTP API<br/>8088"]
    DB["H2 / MySQL"]

    APP --> GW
    GW --> CONFIG
    GW --> REGISTRY
    ADMIN --> CONFIG
    ADMIN --> REGISTRY
    ADMIN --> DB
    GW --> DB
~~~

可以把玄同理解成三块：

1. **Gateway**：负责和客户端保持连接，收请求，推送变化。
2. **State**：保存客户端真正应该看到的配置和服务实例。
3. **后台**：给人使用，负责编辑配置、管理服务、用户、Token 和审计。

客户端连接 8090。8088 只给后台页面、HTTP API、健康检查和指标使用。

玄同不会转发业务请求。你的订单服务调用库存服务时，流量不会经过玄同。

## 2. 为什么同时有 Raft 和数据库

两者保存的东西不一样。

Raft 保存客户端必须看到的最终结果：

- 当前生效的配置；
- 灰度规则；
- 配置是否已经下线；
- 当前服务实例；
- 实例 Lease 和过期状态。

H2 或 MySQL 保存后台管理需要的数据：

- 草稿；
- 用户和角色；
- 应用 Token；
- 审计日志；
- 方便页面查询的索引数据。

如果 Raft 和数据库短时间不一致，以 Raft 为准。数据库查询数据可以补，已经提交给客户端的结果不能被数据库旧数据覆盖。

## 3. 为什么 Config 和 Registry 分开

配置发布和实例续租不是一类工作。

Registry 会不停处理心跳和过期实例；Config 的写入少一些，但更关心发布、灰度和历史。把它们放进两个 Raft Group 后，某个服务大量续租不会堵住配置发布。

两个 Group 可以跑在同一批进程里，只是日志和状态各自独立。

## 4. 客户端怎么连接

客户端可以配置多个 Gateway 地址，但同时只连一个。

正常过程：

1. 连接一个 Gateway。
2. 发送 Hello，告诉服务端自己的版本、服务名、实例 ID 和需要的功能。
3. 拉一次完整快照。
4. 建立 Watch，接收后续变化。
5. 当前 Gateway 断开后，再顺序尝试下一个地址。

客户端不会同时向几台 Gateway 广播同一个请求，也不会因为切换 Gateway 重复注册一个实例。

## 5. clientId 到底是什么

2.0 使用两个身份：

- applicationName：服务名。例如 order-service，同一个服务部署十台也一样。
- clientInstanceId：某一个具体进程的 ID。每台机器、每个 Pod、每个 JAR 进程都不一样。

clientInstanceId 默认自动生成。普通用户不需要在配置文件里写它。

灰度选择具体实例时用 clientInstanceId，而不是 applicationName。

## 6. 一条配置怎么发布

配置地址由三部分组成：

~~~text
namespace + group + dataId
~~~

例如：

~~~text
public + DEFAULT_GROUP + application.yml
~~~

保存和发布是两件事：

- 保存：只更新草稿。
- 发布：把新内容写进 Config State，并通知客户端。

发布大致经过：

~~~text
检查内容和权限
  -> 提交 Raft
  -> 得到新版本号
  -> 更新后台查询数据
  -> 推送给客户端
~~~

只有 Raft 提交成功，才算真的发布成功。

## 7. 为什么有几种 Revision

同一个配置有几种不同变化，不能混用一个版本号：

- draftRevision：防止两个人同时编辑时互相覆盖。
- contentRevision：配置内容版本。
- decisionRevision：当前选择了哪个全量或灰度版本。
- eventRevision：客户端 Watch 看到的位置。

比如终止灰度时，配置内容可能没变，但生效规则变了，所以 decisionRevision 和 eventRevision 仍然要增加。

## 8. 灰度怎么选客户端

2.0 支持三种方式：

- 指定 clientInstanceId；
- 指定 IP；
- 指定百分比。

百分比不是“100 台里随便抽 10 台”，而是根据 rolloutKey、clientInstanceId 和 seed 算出固定结果。这样客户端重连、Gateway 切换或重新点开页面后，命中结果不会乱跳。

只有一台客户端时，10% 可能命中，也可能不命中。如果你就是想让这一台生效，用“精确实例”。

## 9. 配置下线后发生什么

下线不会简单删除数据库记录，而是写入一个 Tombstone，明确告诉客户端“这条配置已经不存在”。

客户端收到后会：

- 删除内存里的值；
- 删除本地快照；
- 通知 Listener，新值为 null；
- 调用 get(dataId, defaultValue) 时返回默认值。

普通超时、断网或临时请求失败不会触发删除。客户端继续使用最后一次成功配置。

## 10. 服务实例怎么过期

实例注册后会拿到 Lease，并定期续租。

如果续租一直失败，Registry Leader 会提交一条过期记录，把实例摘除。Follower 不会各自判断时间然后删除实例，否则几台机器可能得出不同结果。

同一个实例被新进程接管时会增加 Generation。旧进程后到的续租或注销请求会被拒绝，避免旧进程把新实例删掉。

## 11. Watch 断了怎么办

客户端保存一个 eventRevision。

- Revision 还在服务端保留范围内：补发缺少的事件。
- Revision 太旧：重新拉完整快照。
- 服务端暂时不可用：继续使用本地最后一次成功数据，后台重连。

所以“连接断了”和“配置被下线”是两件完全不同的事。

## 12. 单机和集群

### 单机

默认就是单机：

~~~text
1 个 Server
1 个 Gateway
1 个 State voter
H2
~~~

直接 java -jar 就能用。也可以换成 MySQL，并开启 XUANTONG_PRODUCTION=true 跑单机生产。

单机没有节点接管能力。这台机器停了，玄同也会停，但功能没有被删减。

### 三节点或五节点

需要机器故障自动接管时，再使用 3 个或 5 个 State voter。

所有节点使用相同的：

- Cluster ID；
- MySQL；
- Session Secret；
- State peers。

每个节点使用不同的：

- Gateway ID；
- State Node ID。

State peers 里的地址必须让节点之间直接访问，不能写负载均衡 VIP。

XUANTONG_PRODUCTION=true 只检查安全设置，不限制节点数量。

## 13. 安全

- 后台使用用户、角色和签名 Session。
- 生产模式要求 Secure Cookie 和至少 32 字节的 Session Secret。
- 客户端可以使用按 namespace、group 和权限限制的 Token。
- TLS 负责加密连接，mTLS 可以再验证客户端证书。
- Token、密码和私钥不能写进 URL、指标或审计明文。
- YAML、JSON 和 XML 在服务端重新校验，XML 禁止外部实体。

## 14. 模块为什么这样拆

| 模块 | 职责 |
|---|---|
| xuantong-protocol | Protobuf 消息 |
| xuantong-client-core | 连接、缓存、Watch、重连和 Config/Discovery API |
| xuantong-gateway | Socket.D 服务端 |
| xuantong-config-state | 配置状态 |
| xuantong-registry-state | 注册中心状态 |
| xuantong-raft-core | Raft 节点、Snapshot 和成员变更 |
| xuantong-config-core | 草稿、发布、灰度和历史 |
| xuantong-discovery-core | 服务管理 |
| xuantong-security | 用户、Token 和审计 |
| xuantong-server | 把这些模块装成一个可运行的 Server |

Spring 和 Solon 插件只负责适配框架，底层连接和缓存都复用 client-core，不各写一套。

## 15. 2.0 不能破坏的规则

以后修改 2.0 时，下面这些规则不能变：

1. 客户端看到的配置和服务实例以 Raft 为准。
2. Gateway 不转发业务流量。
3. 一个客户端同时只使用一个 Gateway Session。
4. Config 和 Registry 使用不同的 Raft Group。
5. 断网不会删除客户端最后一次成功数据。
6. 百分比灰度不会强行保证至少命中一台。
7. 单机和集群都能开启生产安全模式。
8. 2.0 不再增加 1.x 兼容代码。
