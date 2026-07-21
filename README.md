<h1 align="center">玄同 Xuantong</h1>

<p align="center">一个给 Java 项目用的配置中心和注册中心</p>

<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-2.0.0-6f42c1">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-e76f00">
  <img alt="Solon" src="https://img.shields.io/badge/Solon-4.0.3-1677ff">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
</p>

玄同 2.0.0 已经可以管理配置、做灰度发布，也可以注册和发现服务。它有 Java、Spring Boot、Spring Cloud、Solon 和 Solon Cloud 的接入包。

2.0 是重新设计的新版本，不兼容 1.x，也不会自动迁移 1.x 数据。

## 能做什么

- 管理 namespace、group、dataId 三层配置。
- 编辑 Text、String、Number、Boolean、Properties、YAML、JSON 和 XML。
- 保存草稿、发布、查看历史、回滚、下线和恢复。
- 按实例、IP 或百分比做灰度发布。
- 客户端断线后继续使用本地最后一次成功配置，连接恢复后自动追上新版本。
- Java 监听器有可关闭句柄，Spring Bean 销毁时自动解除监听。
- Spring Cloud 同时支持阻塞式和 Reactive 服务发现。
- 注册服务实例、续租、自动摘除过期实例，并监听服务变化；第一次注册会自动创建服务。
- 管理用户、应用 Token、权限和审计日志。
- 使用 TLS 或 mTLS 保护客户端连接。

玄同只管配置和服务信息，不转发你的业务请求，所以不会在业务调用链中多加一跳。

## 先跑起来

需要 JDK 21 和 Maven 3.8.7+。

~~~bash
git clone https://github.com/wang-jianwu/xuantong.git
cd xuantong
mvn clean package -DskipTests
java -jar xuantong-server/target/xuantong-server.jar
~~~

然后打开：

~~~text
http://localhost:8088
~~~

默认账号：

~~~text
admin / admin123
~~~

应用连接的端口是 8090，不是后台页面使用的 8088。

本地启动不需要 Docker、MySQL、Redis、Nacos 或消息队列。玄同会自动使用 H2，并启动一个本地 State 节点。数据放在当前目录的 data 文件夹。

## 发一条配置

1. 登录后台，打开“配置管理”。
2. 使用默认的 public / DEFAULT_GROUP。
3. 新建 demo.message，类型选 String。
4. 内容填 hello-xuantong。
5. 先保存，再点发布。

只保存是草稿，客户端不会收到。点了发布才会生效。

## Spring Boot 接入

~~~xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
~~~

~~~yaml
spring:
  application:
    name: demo-service

xuantong:
  config:
    server-addresses: [127.0.0.1:8090]
    namespace: public
    group: DEFAULT_GROUP
    application-name: ${spring.application.name}
    access-token: ${XUANTONG_ACCESS_TOKEN:}
~~~

~~~java
@Component
public class DemoConfig {
    @ConfigValue(value = "demo.message",
            defaultValue = "default",
            autoRefresh = true)
    private String message;
}
~~~

其他接入方式和完整示例见[快速入门](doc/quick-start.md)和 [examples](examples)。

## 单机生产

单机可以直接用于生产。它的功能和集群版一样，只是机器挂了以后没有其他节点接管。

XUANTONG_PRODUCTION=true 只会打开生产安全检查，和你部署一台、三台还是五台没有关系。

单机生产通常只需要配置数据库和安全项：

~~~bash
export XUANTONG_PRODUCTION=true
export XUANTONG_DB_URL='jdbc:mysql://mysql.example.com:3306/xuantong'
export XUANTONG_DB_USER='xuantong'
export XUANTONG_DB_PASSWORD='change-me'
export XUANTONG_DB_DRIVER='com.mysql.cj.jdbc.Driver'
export XUANTONG_DB_DIALECT='mysql'
export XUANTONG_ADMIN_SESSION_SECRET='replace-with-at-least-32-random-bytes'
export XUANTONG_ADMIN_COOKIE_SECURE=true
export XUANTONG_CLUSTER_ID='xuantong-prod'
export XUANTONG_GATEWAY_ID='gateway-1'

java -jar xuantong-server.jar
~~~

State 节点继续使用默认的 state-1@127.0.0.1:9101，不需要再配一串 Raft 参数。

如果以后需要机器故障自动接管，再改成 3 个或 5 个 State 节点。具体配置见[快速入门](doc/quick-start.md#8-生产部署)。

## 项目结构

| 模块 | 用来做什么 |
|---|---|
| xuantong-server | 后台、API、Gateway 和可执行 JAR |
| xuantong-client-core | 原生 Java 客户端 |
| xuantong-spring-boot-starter | Spring Boot 配置接入 |
| xuantong-spring-cloud-starter | Spring Cloud 配置、阻塞/Reactive 注册发现和负载均衡 |
| xuantong-solon-plugin | Solon 配置接入 |
| xuantong-solon-cloud-plugin | Solon Cloud 配置和注册发现 |
| xuantong-probe | 从外部检查控制面是否真的可用 |

其他模块是 Server 内部代码，普通应用不需要依赖。

## 文档

- [快速入门](doc/quick-start.md)：第一次安装和接入。
- [架构设计](doc/architecture.md)：代码为什么这样拆。
- [功能说明](doc/features.md)：每个功能具体怎么表现。
- [开发计划](PLAN_2.0.md)：2.0 做完了什么，接下来做什么。
- [贡献指南](CONTRIBUTING.md)
- [安全策略](SECURITY.md)
- [变更记录](CHANGELOG.md)

## 后面准备做什么

2.0 先把配置中心和注册中心做好。后续版本再增加服务拓扑、标签路由、权重路由、限流、熔断和降级。

## License

[Apache License 2.0](LICENSE)
