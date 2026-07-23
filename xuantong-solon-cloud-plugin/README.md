---
title: "xuantong-solon-cloud-plugin [国产]"
---

```xml
<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-solon-cloud-plugin</artifactId>
    <version>最新版</version>
</dependency>
```

### 1、描述

分布式服务治理扩展插件。基于玄同 2.0 适配的 Solon Cloud 插件，提供配置服务、服务注册与发现。

插件使用 Socket.D TCP 长连接接入玄同控制面，支持配置动态刷新和本地快照。控制面暂时不可用时，应用可以使用本地快照启动，并在连接恢复后自动同步最新配置。

开源仓库：https://gitee.com/wjw_system/xuantong

### 2、能力支持

| 云端能力接口 | 说明 | 备注 |
| --- | --- | --- |
| `CloudConfigService` | 云端配置服务 | 支持 namespace、group、启动加载和动态刷新 |
| `CloudDiscoveryService` | 云端注册与发现服务 | 支持 namespace、group、服务注册、查询和订阅 |

配置发布和删除属于管理操作，请通过玄同控制台或管理 API 完成。

### 3、配置示例

```yml
solon.app:
  name: "demoapp"
  group: "DEFAULT_GROUP"

solon.cloud.xuantong:
  server: "127.0.0.1:8090"       # 玄同 2.0 Socket.D TCP 控制面地址
  namespace: "public"
  token: "${XUANTONG_ACCESS_TOKEN:}"
  config:
    load: "demoapp.yml"          # 加载配置到应用属性，多个使用逗号分隔
  discovery:
    enable: true                  # 不需要服务注册发现时设置为 false
```

更丰富的配置：

```yml
solon.app:
  name: "demoapp"
  group: "DEFAULT_GROUP"
  meta:                           # 应用元信息（可选）
    version: "v2.0.0"
    author: "demo"
  tags: "api,prod"               # 应用标签（可选）

solon.cloud.xuantong:
  # 多个玄同 Server 地址使用逗号分隔
  server: "10.0.0.11:8090,10.0.0.12:8090,10.0.0.13:8090"
  namespace: "prod"
  token: "${XUANTONG_ACCESS_TOKEN:}"
  config:
    enable: true
    load: "application.yml,db.yml"
  discovery:
    enable: true
    healthCheckInterval: "10s"
```

提醒：

- 通过 `config.load` 加载的配置会进入 `Solon.cfg()`，可以使用 `@Inject` 注入；
- 玄同使用 `namespace + group + dataId` 唯一确定一份配置；
- `config.load` 未指定分组时使用 `solon.app.group`；需要指定其他分组时可写成 `PAYMENT_GROUP:payment.yml`；
- `@CloudConfig` 可通过 `group` 指定分组，留空时使用 `DEFAULT_GROUP`。

### 4、代码应用

```java
import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain
public class DemoApp {
    public static void main(String[] args) {
        // 启用 discovery 后，Web 服务会在启动完成时自动注册
        Solon.start(DemoApp.class, args);
    }
}
```

```java
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.cloud.annotation.CloudConfig;

import java.util.List;

@Controller
public class DemoController {
    // 基础类型配置；单例 Bean 可以使用 autoRefreshed 自动刷新
    @CloudConfig(name = "demo.title",
            group = "DEFAULT_GROUP",
            required = false,
            autoRefreshed = true)
    private String demoTitle;

    // JSON、YAML 等结构化配置可以注入为对象或集合
    @CloudConfig(name = "demo.channels",
            group = "DEFAULT_GROUP",
            required = false,
            autoRefreshed = true)
    private List<String> channels;

    @Mapping("/")
    public String test() {
        return demoTitle + ": " + channels;
    }
}
```

配置订阅，关注配置的实时更新：

```java
import org.noear.solon.annotation.Component;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.annotation.CloudConfig;
import org.noear.solon.cloud.model.Config;

@Component
@CloudConfig(value = "demo.title", group = "DEFAULT_GROUP")
public class TestConfigHandler implements CloudConfigHandler {
    @Override
    public void handle(Config config) {
        System.out.println("配置变更: " + config.key() + " = " + config.value());
    }
}
```

### 5、演示项目

- [Gitee 演示项目](https://gitee.com/wjw_system/xuantong/tree/main/examples/solon-cloud-demo)
- [GitHub 演示项目](https://github.com/wang-jianwu/xuantong/tree/main/examples/solon-cloud-demo)
