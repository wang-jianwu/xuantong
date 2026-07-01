# 玄同 · Xuantong Config

<p align="center">
  <strong>轻量级、高性能的分布式配置管理平台</strong>
</p>

<p align="center">
  <img src="img_1.png" alt="管理后台截图" width="80%">
</p>

---

## 核心特性

| | |
|---|---|
| ⚡ **实时推送** | 基于 Socket.D Broker，配置变更毫秒级到达客户端 |
| 🛡️ **多级容灾** | 内存 → 本地快照 → 远端，断网也能用 |
| 🔌 **多框架** | 原生客户端 · Solon Server · Solon Cloud · Spring Boot |
| 🏠 **零依赖启动** | 单机模式无需 Redis，`java -jar` 直接跑 |
| 📊 **管理后台** | 仪表盘、配置管理、项目管理、环境管理、用户管理、Broker 监控 |
| 🔒 **安全** | RBAC 权限控制、BCrypt 密码加密 |
| 🌐 **集群** | 多节点自动发现 + Broker 组播同步，3 节点即可高可用 |
| 📦 **轻量** | 核心 jar < 5MB，无重型依赖 |

## 快速开始

### 启动配置中心

```bash
# 1. 准备 MySQL，执行建表脚本
mysql -u root -p < xuantong-admin/src/main/resources/db/schema.sql

# 2. 启动（单机模式，默认使用本地缓存，无需 Redis）
java -jar xuantong-admin.jar

# 3. 打开管理后台
# http://localhost:8088/login
# 默认账号: admin / admin123
```

> 多机部署只需取消注释 `core.yml` 中的 Redis 配置即可启用共享缓存。

### 客户端接入

**方式一：原生客户端（推荐新项目）**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-client</artifactId>
    <version>1.3.0</version>
</dependency>
```

```java
// 初始化（配多个 Broker 地址自动 failover）
XuantongConfig.init(
    Arrays.asList("node1:8088", "node2:8088"),
    Arrays.asList("your-app-name"),
    "prod"
);

// 获取配置
String timeout = XuantongConfig.get("payment.timeout", "5000");

// 监听变更
XuantongConfig.addListener("payment.timeout", event -> {
    System.out.println("配置变更: " + event.getNewValue());
});
```

**方式二：Solon Server 插件（`@ConfigValue` 注入）**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-solon-plugin</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
# app.yml
xuantong.config:
  serverAddresses:
    - config-center:8088
  appNames:
    - your-app-name
  environment: prod
```

```java
@Component
public class AppConfig {
    @ConfigValue(value = "server.port", defaultValue = "8080")
    private int serverPort;

    @ConfigValue(value = "app.name", autoRefresh = true)
    private String appName;
}
```

**方式三：Solon Cloud 插件**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-solon-cloud-plugin</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
# app.yml
solon.cloud.xuantong:
  server: "config-center:8088"
  namespace: "prod:app1,app2"   # 格式: 环境:订阅应用列表
  config:
    enable: true
    load: "db.yml,redis.yml"     # 启动时加载指定配置键，可用 @Inject 注入
```

**方式四：Spring Boot Starter**

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-spring-boot-starter</artifactId>
    <version>1.3.0</version>
</dependency>
```

```yaml
# application.yml
xuantong.config:
  server-addresses: ["config-center:8088"]
  app-name: ["your-application-name"]
  environment: "prod"
```

```java
@Component
public class AppConfig {
    @ConfigValue("app.name")
    private String appName;

    @ConfigValue(value = "db.config", type = ValueType.JSON, autoRefresh = true)
    private DatabaseConfig dbConfig;
}
```

## 部署架构

```
┌─────────────────────────────────────────────┐
│               配置中心 (Admin)                │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐  │
│  │ 仪表盘   │  │ 配置管理  │  │ 用户管理   │  │
│  └─────────┘  └──────────┘  └────────────┘  │
│                    │                         │
│              Socket.D Broker                 │
│                    │                         │
│  ┌─────────────────────────────────────┐    │
│  │     应用 A      │     应用 B       │    │
│  │  @=appA:prod   │  @=appB:prod    │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

| 部署模式 | 配置 | 适用场景 |
|---------|------|---------|
| 单机 | 默认（本地缓存），零外部依赖 | 开发、小团队 |
| 多机 | 取消注释 `core.yml` 中 Redis 配置 | 生产环境 |

## 管理后台

| 模块 | 功能 |
|------|------|
| 仪表盘 | 配置总数、项目数、今日变更、最近操作记录 |
| 配置管理 | 按项目/环境查询、新增/编辑/删除/回滚配置、变更历史 |
| 项目管理 | 项目增删改、启用/禁用 |
| 环境管理 | 环境增删改、默认环境设置 |
| 用户管理 | 用户增删改、角色分配（admin/user） |
| Broker 监控 | 连接客户端、推送日志、集群状态（仅 admin 可见） |

## FAQ

**Q: 必须装 Redis 吗？**
A: 不需要。单机模式默认使用本地内存缓存，零外部依赖。多机集群时才需要 Redis 做共享缓存和共享 Session。

**Q: 配置变更多久生效？**
A: 毫秒级。基于 Socket.D 长连接推送，配置保存后立刻通知所有订阅客户端。

**Q: 配置中心挂了怎么办？**
A: 客户端有多级容灾：内存缓存 → 本地文件快照 → 远端。即使配置中心完全不可达，业务应用仍能使用本地缓存的配置正常启动和运行。

**Q: 支持哪些配置格式？**
A: 字符串、数字、布尔、JSON 对象、JSON 数组/列表。通过 `@ConfigValue` 注解自动类型转换。

**Q: 如何保证多节点一致性？**
A: 管理后台节点通过 Socket.D Broker 组播自动同步配置变更，无需额外消息队列。

## 版本要求

| 组件           | 最低版本 |
|--------------|------|
| Java         | 8+   |
| MySQL（可选）    | 5.7+ |
| Spring Boot（可选） | 3.x  |
| Solon（可选）    | 2.x  |

## 许可证

[Apache License 2.0](LICENSE)

---

## Docker 部署

### 先决条件
- Docker & Docker Compose

### 快速启动

```bash
# 1. 设置密码
export DB_PASSWORD=your_password

# 2. 启动全部服务（MySQL + Redis + 配置中心）
docker compose up -d

# 3. 查看日志
docker compose logs -f config-center
```

访问 `http://localhost:8088`，默认账号 `admin` / `admin123`。

### 单独启动

```bash
# 启动 MySQL 和 Redis
docker compose up -d mysql redis

# 构建并启动配置中心
docker compose up -d config-center
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DB_PASSWORD` | MySQL 密码 | (必填) |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `BROKER_SECRET_KEY` | Broker 鉴权密钥 | 空（不校验） |

### 手动构建

```bash
mvn clean package -DskipTests
docker compose build config-center
docker compose up -d config-center
```
