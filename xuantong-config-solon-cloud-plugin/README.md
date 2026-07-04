# xuantong-config-solon-cloud-plugin


```xml

<dependency>
    <groupId>cloud.xuantong</groupId>
    <artifactId>xuantong-config-solon-cloud-plugin</artifactId>
    <version>1.3.1</version>
</dependency>
```


### 1、描述

分布式配置扩展插件。基于 Xuantong Config 适配的 Solon Cloud 插件。提供云端配置服务支持。

### 2、能力支持


| 云端能力接口 | 说明 | 备注 |
| -------- | -------- | -------- |
| CloudConfigService        | 云端配置服务            | 支持 namespace     |

### 3、配置示例

```yml
# app.yml
solon:
  cloud:
    xuantong-config:
      # Broker 地址（支持多地址，自动 failover，逗号分隔）
      server: node1:8088,node2:8088
      namespace: dev:app1,appName2,appName3
      config:
        enable: true
        load: db.yml,redis.yml # 指定加载的配置key 可@Inject 注入
```

提醒：通过 "...config.load" 加载的配置，会进入 Solon.cfg() 可使用 @Inject 注入

### 4、代码应用

```java
// 自动注入配置服务
@Configuration
public class AppConfig {

    @CloudConfig("app.payment.timeout")
    private String paymentTimeout;

    @CloudConfig("app.db.url", autoRefreshed = true)
    private PaymentConfig paymentConfig;
    
}

// 配置订阅：关注配置的实时更新
@Component
public class TestConfigHandler implements CloudConfigHandler {
    @Override
    public void handler(Config config) {
        // 配置变更处理
        System.out.println("配置变更: " + config.value());
    }
}
```


### 5、演示项目

* [https://gitee.com/wjw_system/xuantong-config/tree/master/solon-cloud-demo](https://gitee.com/wjw_system/xuantong-config/tree/main/solon-cloud-demo)