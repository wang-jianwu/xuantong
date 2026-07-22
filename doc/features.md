# 玄同 2.0 功能说明

这份文档讲用户能做什么，以及每个按钮点下去以后会发生什么。

## 1. 配置放在哪里

一条配置由 namespace、group、dataId 唯一确定。

常见用法：

- namespace：区分 dev、test、prod，或者不同团队。
- group：在一个 namespace 里继续分组，默认是 DEFAULT_GROUP。
- dataId：配置名称，例如 application.yml 或 order-service.json。

## 2. 编辑配置

玄同支持：

- Text
- String
- Number
- Boolean
- Properties
- YAML
- JSON
- XML

Number 和 Boolean 会检查值是否合法。JSON、YAML、XML、Properties 可以格式化和压缩。

页面上的校验是为了尽快提示，服务端保存时还会再检查一次。错误内容不能保存，也不能发布。

单条配置默认最多 1 MiB。

## 3. 草稿为什么不会立刻生效

编辑时先保存草稿，确认后再发布，这样写到一半的内容不会被客户端读到。

每次保存都会带上 draftRevision。如果你打开页面以后，别人已经保存了新内容，服务端会返回冲突，不会直接覆盖。页面需要同时显示你的内容和服务器上的新内容，让你自己决定怎么合并。

## 4. 发布、历史和回滚

点击发布后，玄同会：

1. 检查内容和权限。
2. 把新版本写入 Config State。
3. 更新后台页面使用的查询数据。
4. 通知客户端。
5. 记录审计日志。

每次发布都会留下历史。

回滚不会修改旧记录，而是拿旧内容再发布一个新版本。所以回滚以后版本号仍然向前走。

## 5. 灰度发布

灰度可以按三种方式选客户端：

- 精确实例：直接选择 clientInstanceId。
- IP：按 Gateway 看到的客户端地址匹配。
- 百分比：按 clientInstanceId 稳定分桶。

开始灰度前可以先看预览。预览会告诉你哪些实例能看到、哪些会命中，以及为什么命中。

多 Gateway 部署时，同一个 clientInstanceId 只算一次。如果某个 Gateway 的连接数据不完整，玄同会拒绝生成假装完整的预览。

### 为什么一台客户端做 10% 灰度可能不生效

因为 10% 不是“至少选一台”，而是每个实例都有固定的分桶结果。

只有一台客户端时想保证命中，就选“精确实例”。

### 转全量

把灰度内容变成所有客户端的新版本。

### 终止

撤销灰度规则，已经命中的客户端回到原来的稳定版本。

转全量和终止都会产生新事件，客户端不需要重启。

## 6. 下线和恢复

下线表示明确告诉客户端“这条配置已经没有了”。

客户端会删掉内存值和本地快照，监听器收到 null。以后读取这条配置时返回调用方给的默认值。

断网、超时或 Gateway 暂时不可用不会触发下线，客户端继续使用最后一次成功值。

下线后可以重新编辑并发布，也可以从历史版本恢复。

## 7. 客户端启动和刷新

客户端启动时：

1. 先读本地缓存。
2. 连接 Gateway。
3. 拉取服务端快照。
4. 建立 Watch。

如果服务端暂时连不上，只要本地有缓存，应用仍然可以先启动。连接恢复后再追上最新版本。

客户端支持：

- 获取字符串和默认值；
- JSON 转对象、List 和 Map；
- 监听 dataId 变化；
- Spring Boot 和 Solon 字段自动刷新。

监听器注册会返回 `ListenerRegistration`。业务对象销毁时关闭这个句柄，就不会被客户端长期引用。Spring 管理的普通 Bean 会自动释放监听器；prototype Bean 没有可靠的销毁回调，因此不会自动开启字段刷新。

本地快照目录可以配置。未配置时仍使用当前工作目录下的 `.xuantong-cache`。

重复事件会被忽略，Watch 位置太旧时会重新拉完整快照。

## 8. 服务注册

服务第一次注册时，玄同会自动创建同名服务，普通项目不需要先去后台操作。
如果需要提前填写服务说明或元数据，也可以在后台手动创建。

管理员明确删除过的服务不会被客户端偷偷建回来。需要重新启用时，先在后台重新创建该服务。

一个实例会记录：

- instanceId；
- IP 和端口；
- metadata；
- weight；
- applicationName；
- clientInstanceId；
- serviceGeneration、leaseEpoch 和 recoveryEpoch。

实例需要定期续租。长时间没有续租，Registry 会把它摘除。

新进程接管同一个实例时会提高 leaseEpoch 和 recoveryEpoch，旧进程后到的续租和注销不会影响新实例。
服务被管理员删除后再重新创建时，serviceGeneration 才会增加，旧版本客户端不能直接重新注册。

## 9. 服务发现

客户端可以查询某个服务当前有哪些实例，也可以监听实例变化。

Spring Cloud Starter 提供：

- DiscoveryClient
- ReactiveDiscoveryClient
- ServiceRegistry
- Spring Cloud LoadBalancer

Spring Cloud 和 Solon Cloud 的多个服务 Agent 会复用同一条 discovery Socket.D 连接。查询服务目录不会再创建一个假的 catalog 服务。

Solon Cloud Plugin 提供对应的 Config 和 Discovery SPI。Config SPI 的读取和监听可用；写入和删除必须通过玄同后台或管理 API，调用 `push/remove` 会明确抛出只读异常，不再静默返回 false。

## 10. 客户端身份

applicationName 是服务名。同一个服务部署多份时，这个值可以完全一样。

clientInstanceId 是某一个具体进程的 ID。客户端默认自动生成，不需要在每台机器上手工写配置。

后台连接页面和精确实例灰度都使用 clientInstanceId。

## 11. 后台页面

2.0 包含这些页面：

- 运行概览；
- 配置管理；
- 服务管理；
- 客户端连接；
- 应用 Token；
- 用户；
- 审计。

列表查询都在服务端分页，不会一次把全部数据拉到浏览器。

## 12. 用户和安全

后台角色：

- ADMIN：管理所有内容。
- DEVELOPER：操作配置和服务。
- VIEWER：只读。

生产模式会检查：

- 不能继续使用 admin123；
- 必须配置至少 32 字节的 Session Secret；
- Cookie 必须开启 Secure。

这些检查和单机、三节点、五节点没有关系。

应用 Token 可以限制 namespace、group、读写和注册发现权限。Token 被吊销后，其他 Gateway 也会在短时间内拒绝它。

TLS 用来加密连接，mTLS 可以要求客户端提供证书。即使使用 mTLS，应用权限仍然由 Token 控制。

## 13. 监控和备份

Server 提供：

- /health
- /metrics
- 后台运行概览
- 审计日志

xuantong-probe 会从外部重新建立 Socket.D 连接并执行 Hello 和 Probe，用来检查“客户端实际能不能用”，而不是只看进程还在不在。

备份需要同时保存数据库和 State Snapshot。只备份 MySQL 或只复制 Raft 目录都不完整。

## 14. 用户到底需要配多少东西

本地启动：一个都不用配。

单机生产：配置 MySQL、Session Secret、Secure Cookie、Cluster ID 和 Gateway ID。

高可用生产：在单机配置的基础上，设置 `XUANTONG_DEPLOYMENT=cluster`，再增加每个节点的 State Node ID 和所有节点共用的 peers。

线程数、队列大小、超时、Watch 间隔、Snapshot 和容量限制都有默认值。没有遇到明确问题时不要改。

## 15. 2.0 暂时不做什么

2.0 不负责：

- 转发业务流量；
- 服务网格数据面；
- 限流、熔断和降级执行；
- 自动故障诊断；
- 多语言客户端；
- 1.x 兼容和数据迁移。

这些功能如果要做，会放到后续版本。
