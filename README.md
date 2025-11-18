# Nimbus 配置中心客户端

## 产品介绍
Nimbus-Config是一款高性能的分布式配置管理客户端，具有以下核心特性：

- ⚡ **实时推送**：基于WebSocket的配置变更通知
- 🛡️ **多级容灾**：内存 → 本地文件 → Redis → 服务端
- 📊 **完善监控**：内置指标采集和健康检查
- 🔒 **安全可靠**：支持配置加密和权限控制 
------------------------------------------------
- 对实时性要求极高（毫秒级推送）
- 追求极简、轻量
- 需要更强的容灾和熔断能力
- 注重性能优化和监控指标
- 愿意使用相对较新的技术，选TA没错！
## 快速开始

### 1. 添加依赖
```xml
<dependency>
    <groupId>com.nimbus</groupId>
    <artifactId>nimbus-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 初始化客户端
```java
// 生产环境初始化
NimBusClient client = ConfigClientFactory.getClient(
    "sd:ws://config-center:8080",
    "your-app-name",
    "prod"
);
```

### 3. 获取配置
```java
String timeout = NimBusClient.get("payment.timeout", "5000");
```

### 4. 监听变更
```java
client.addListener("payment.timeout", event -> {
    System.out.println("配置变更: " + event.getNewValue());
});
```

## 开发进度

### 已实现功能
✅ 核心客户端 (nimbus-client)  
✅ 多级缓存架构  
✅ 实时推送机制  
✅ 熔断保护  
✅ 本地快照  

### 正在开发
🔄 Spring Boot Starter (nimbus-spring-boot-starter)  
🔄 Solon Cloud Plugin (nimbus-config-solon-cloud-plugin)  

### 计划功能
🔲 配置加密支持  
🔲 权限控制体系  
🔲 灰度发布功能  

## 最佳实践

### 推荐配置
| 环境 | 建议配置 |
|------|---------|
| 开发环境 | 单节点连接 |
| 生产环境 | 多节点集群 + Redis缓存 |

### 性能调优
- 适当增大连接池大小
- 合理设置心跳间隔
- 启用压缩传输

## 技术支持
- 文档：https://nimbus-config.io/docs
- 社区：forum.nimbus-config.io
- 商业支持：support@nimbus-config.io