# 玄同 2.0 快速入门

这份文档只写第一次使用真正需要的东西。配置文件里那些线程数、队列大小、Watch 间隔和 Snapshot 参数先别管，默认值可以直接用。

## 1. 准备环境

需要：

- JDK 21
- Maven 3.8.7+

先确认 Maven 没有跑到旧 Java 上：

~~~bash
mvn -version
~~~

输出里的 Java version 应该是 21。

## 2. 构建和启动

~~~bash
git clone https://github.com/wang-jianwu/xuantong.git
cd xuantong
mvn clean package -DskipTests
java -jar xuantong-server/target/xuantong-server.jar
~~~

看到下面几个信息就说明启动成功：

- 后台：http://localhost:8088
- 客户端端口：127.0.0.1:8090
- Config State：UP
- Registry State：UP

默认账号：

~~~text
admin
admin123
~~~

本地模式已经自动配好 H2、Config State 和 Registry State，不需要再导出一串环境变量。

## 3. 发布第一条配置

1. 打开后台并登录。
2. 进入“配置管理”。
3. 使用 public / DEFAULT_GROUP。
4. 新建 demo.message。
5. 类型选 String，内容填 hello-xuantong。
6. 点“保存”。
7. 再点“发布”。

保存只是草稿。忘了点发布，是客户端没有刷新的最常见原因。

玄同支持这些内容类型：

- Text、String
- Number、Boolean
- Properties
- YAML
- JSON
- XML

JSON、YAML、XML 和 Properties 可以校验、格式化和压缩。真正保存时服务端还会再检查一次，不能靠绕过页面校验写入错误内容。

## 4. 试一次灰度发布

先启动一个客户端，让它出现在“客户端连接”页面。

然后：

1. 找到 demo.message，点“灰度”。
2. 只有一个客户端时，直接选“精确实例”。
3. 先看预览，确认选中了目标 clientInstanceId。
4. 开始灰度。
5. 验证没问题后点“转全量”；不想继续就点“终止”。

不要用 10% 去测试唯一的一台客户端。10% 的意思是这个实例可能命中，也可能不命中，不会为了演示强行选中一台。

## 5. 客户端接入

### Spring Boot

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

完整例子：[spring-boot-config-demo](../examples/spring-boot-config-demo)。

本地快照默认放在当前工作目录的 `.xuantong-cache`。只有需要改目录时才配置：

~~~yaml
xuantong.config.cache-directory: /var/lib/xuantong/cache
~~~

### Spring Cloud

~~~xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-spring-cloud-starter</artifactId>
    <version>2.0.0</version>
</dependency>
~~~

~~~yaml
spring:
  application:
    name: demo-service
  config:
    import: optional:xuantong:application.yml
  cloud:
    xuantong:
      server-addresses: [127.0.0.1:8090]
      namespace: public
      group: DEFAULT_GROUP
      application-name: ${spring.application.name}
      access-token: ${XUANTONG_ACCESS_TOKEN:}
      config:
        enabled: true
      discovery:
        enabled: true
        register: true
~~~

第一次注册会自动创建和 `spring.application.name` 同名的服务，不需要先去后台添加。
如果这个服务曾被管理员删除，需要先在后台重新创建，客户端才允许再次注册。

完整例子：[spring-cloud-demo](../examples/spring-cloud-demo)。

Spring Cloud Starter 同时提供阻塞式和 Reactive DiscoveryClient。WebFlux 在类路径中时，会自动启用 Reactive 接口。ConfigData 一次加载多个 dataId 时会复用同一个启动连接，并在启动阶段结束后关闭。

### Solon

~~~xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-solon-plugin</artifactId>
    <version>2.0.0</version>
</dependency>
~~~

~~~yaml
solon.app:
  name: demo-service

xuantong.config:
  serverAddresses: [127.0.0.1:8090]
  namespace: public
  group: DEFAULT_GROUP
  applicationName: ${solon.app.name}
  accessToken: ${XUANTONG_ACCESS_TOKEN:}
~~~

完整例子：[solon-config-demo](../examples/solon-config-demo)。

Solon Cloud 的完整例子在 [solon-cloud-demo](../examples/solon-cloud-demo)。

### 原生 Java

~~~xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-client-core</artifactId>
    <version>2.0.0</version>
</dependency>
~~~

~~~java
try (XuantongConfigClient client = new XuantongConfigClient(
        List.of("127.0.0.1:8090"),
        "public",
        "DEFAULT_GROUP",
        "",
        "demo-service");
     ListenerRegistration registration = client.listen(
             "demo.message",
             event -> System.out.println(event.getNewValue()))) {
    System.out.println(client.get("demo.message", "default"));
}
~~~

完整例子：[java-client-demo](../examples/java-client-demo)。

applicationName 是服务名，同一个服务部署多台时可以一样。clientInstanceId 是某一个具体进程的 ID，客户端会自动生成，普通 JAR 启动不用配置。

`new XuantongConfigClient(...)` 只创建当前客户端，不会偷偷注册成全局单例。需要静态门面时，显式调用 `XuantongConfig.init(...)` 或 `XuantongConfig.setDefault(client)`。

## 6. 开启应用 Token

本地体验默认不强制 Token。

需要鉴权时：

1. 在后台创建应用 Token。
2. Server 设置 XUANTONG_CLIENT_AUTH_REQUIRED=true。
3. 应用设置 XUANTONG_ACCESS_TOKEN。

Token 可以限制 namespace、group 和读写权限。

## 7. 单机生产

单机可以跑生产，不需要改 Raft 配置。

默认的 `XUANTONG_DEPLOYMENT=standalone` 不会启动多 Server 使用的 Gateway 数据库协调任务，不需要手动配置。

第一次安装时，先按普通模式启动，登录后台把 admin123 改掉。然后停掉服务，配置 MySQL 和安全项后重新启动：

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

如果你直接换成一个全新的 MySQL，里面仍然是默认密码，也需要先不开生产模式启动一次并修改密码。

单机生产功能是完整的，但这台机器停了，配置中心和注册中心也会一起停。

## 8. 三节点或五节点

只有需要机器故障自动接管时，才需要多节点。

所有节点都连接同一个 MySQL，并使用相同的：

- XUANTONG_DEPLOYMENT=cluster
- XUANTONG_CLUSTER_ID
- XUANTONG_ADMIN_SESSION_SECRET
- XUANTONG_CONFIG_STATE_PEERS

每个节点使用不同的：

- XUANTONG_GATEWAY_ID
- XUANTONG_STATE_NODE_ID

三节点 peers 示例：

~~~bash
export XUANTONG_DEPLOYMENT='cluster'
export XUANTONG_STATE_NODE_ID='state-1'
export XUANTONG_CONFIG_STATE_PEERS='state-1@10.0.0.11:9101,state-2@10.0.0.12:9101,state-3@10.0.0.13:9101'
~~~

另外两个节点只改当前节点的 STATE_NODE_ID。peers 三台必须完全一样，里面写节点之间能直接访问的地址，不要写负载均衡 VIP。

## 9. 常见问题

### Maven 说没有编译器

Maven 用到了 JRE 或旧 Java。执行 mvn -version，确认它显示 JDK 21。

### 页面能打开，客户端连不上

页面是 8088，客户端应该连 8090。

### 配置改了但没有刷新

先检查是否点了发布，再检查客户端和配置的 namespace、group、dataId 是否一致。

### 一定要用 Docker 吗

不用。Docker Compose 只是可选的 MySQL 本地环境。
