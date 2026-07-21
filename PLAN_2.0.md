# 玄同 2.0 开发计划

## 现在是什么版本

当前版本是 2.0.0。

2.0 和 1.x 没有兼容关系：

- 不兼容旧协议；
- 不读取旧数据库；
- 不保留 Redis Broker；
- 不为旧 clientId 和旧灰度规则写兼容分支。

## 2.0 已经做完

### 配置中心

- [x] namespace、group、dataId
- [x] 八种配置类型
- [x] 校验、格式化和大小限制
- [x] 草稿和多人编辑冲突保护
- [x] 发布、批量发布、历史和回滚
- [x] 精确实例、IP、百分比灰度
- [x] 灰度预览、转全量和终止
- [x] 下线和恢复
- [x] 本地缓存、Watch 和断线恢复

### 注册中心

- [x] 服务定义
- [x] 实例注册和注销
- [x] Lease 续租和过期摘除
- [x] 旧进程 fencing
- [x] 服务查询和变化监听
- [x] Spring Cloud 和 Solon Cloud 接入

### Server

- [x] Socket.D TCP 连接
- [x] Protobuf 协议
- [x] Config 和 Registry 两个 Raft Group
- [x] 多 Gateway 顺序切换
- [x] Snapshot、WAL 和成员变更
- [x] H2 和 MySQL
- [x] 单机零配置启动
- [x] 单机、三节点和五节点部署

### 客户端

- [x] 原生 Java Client
- [x] Spring Boot Starter
- [x] Spring Cloud Starter
- [x] Solon Plugin
- [x] Solon Cloud Plugin
- [x] 自动生成 clientInstanceId
- [x] TLS 和 mTLS
- [x] 显式默认客户端，不在构造器里修改全局状态
- [x] 可关闭监听器和 Spring Bean 生命周期清理
- [x] ConfigData 启动连接复用和可配置快照目录
- [x] Spring Cloud ReactiveDiscoveryClient
- [x] Spring Cloud、Solon Cloud 服务发现连接复用
- [x] 五个示例项目

### 后台和安全

- [x] 配置、服务、连接、Token、用户和审计页面
- [x] 服务端分页
- [x] 登录、Session、CSRF 和失败退避
- [x] 默认密码检查
- [x] Token 权限和吊销
- [x] 敏感信息脱敏

### 发布

- [x] 版本改为 2.0.0
- [x] 可直接 java -jar 运行的 Server
- [x] Sources 和 Javadocs
- [x] Maven Central 配置
- [x] GitHub Release Workflow
- [x] SBOM 和 SHA-256
- [x] SECURITY、CONTRIBUTING 和 CHANGELOG

## 发布时怎么做

1. 在干净代码上运行 Maven 测试。
2. 运行 scripts/build-release-candidate.sh。
3. 检查 Server JAR、Probe JAR、SBOM 和 SHA256SUMS。
4. 创建 v2.0.0 标签。
5. 由 GitHub Workflow 发布 Maven Central 和 GitHub Release。

这几步是发布操作，不再往 2.0 里塞新功能。

## 2.0.x 以后只改什么

2.0.x 只做：

- Bug 修复；
- 安全修复；
- 性能优化；
- 不破坏现有行为的监控增强；
- 文档和示例修正。

需要改协议、状态机或资源模型的功能放到后续版本。

## 后面准备做什么

### 2.1

- 服务拓扑；
- 服务版本和标签；
- 地域、机房信息；
- 权重路由和标签路由。

### 2.2

- 限流；
- 熔断；
- 降级；
- 并发隔离；
- 治理规则的命中说明和效果指标。

### 更远的方向

- 配置、服务和流量统一管理；
- 把发布变更和故障关联起来；
- 自动止损和回滚；
- 多语言客户端。

这些内容现在只是方向，不会写成 2.0 已经完成。
