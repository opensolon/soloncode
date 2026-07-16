# Cloud Core — 配置 / 发现 / 事件 / 任务

> 适用场景：服务注册与发现、配置中心、消息队列、分布式任务调度。
>
> 目标版本：4.0.3。优先用 `CloudClient` 统一 API；中间件只换插件依赖。
> File / Breaker / Gateway / Trace / Metric / Id / Lock 见 **`cloud_ops.md`**。

## 核心用法（统一 API）

Solon Cloud 提供统一的 API 接口，不同中间件只需更换插件依赖即可切换。

### 注解清单

| 注解 | 适用范围 | 说明 |
|---|---|---|
| `@CloudConfig` | 类、字段、参数 | 配置注入 |
| `@CloudEvent` | 类 | 事件订阅 |
| `@CloudJob` | 类、函数 | 分布式定时任务 |
| `@CloudBreaker` | 类、函数 | 熔断/限流 → 详见 `cloud_ops.md` |

### Cloud Client 统一入口

```java
// 配置服务
CloudClient.config().pull(group, key);

// 注册与发现（一般自动完成，无需手动调用）
CloudClient.discovery().register(instance);
CloudClient.discovery().find(group, service);

// 事件总线
CloudClient.event().publish(new Event("topic.order", "content"));

// id / lock / file / metric / trace / breaker → cloud_ops.md
```

---

## Cloud Config — 分布式配置

### 适配插件

| 插件 | 刷新方式 | 协议 | namespace | group |
|---|---|---|---|---|
| `local-solon-cloud-plugin` | 不支持 | / | 不支持 | 支持 |
| `nacos-solon-cloud-plugin` | tcp 实时 | tcp | 支持 | 支持 |
| `nacos2-solon-cloud-plugin` | tcp 实时 | tcp | 支持 | 支持 |
| `nacos3-solon-cloud-plugin` | tcp 实时 | tcp | 支持 | 支持 |
| `consul-solon-cloud-plugin` | 定时拉取 | http | 不支持 | 支持 |
| `zookeeper-solon-cloud-plugin` | 支持 | tcp | 不支持 | 支持 |
| `polaris-solon-cloud-plugin` | 实时 | grpc | 支持 | 支持 |
| `etcd-solon-cloud-plugin` | 事件通知 | http | 不支持 | 支持 |
| `water-solon-cloud-plugin` | 事件通知 | http | 不支持 | 支持 |

### 配置示例（Nacos）

```yaml
solon.cloud.nacos:
  server: "127.0.0.1:8848"
  namespace: "dev"
  config:
    group: "DEFAULT_GROUP"
```

### Nacos 最小端到端（配置 + 注册发现）

```xml
<!-- 按 Nacos 大版本选其一：nacos / nacos2 / nacos3 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>nacos2-solon-cloud-plugin</artifactId>
</dependency>
```

```yaml
solon.app:
  group: "demo"
  name: "user-service"

solon.cloud.nacos:
  server: "127.0.0.1:8848"
  namespace: "dev"
  config:
    group: "DEFAULT_GROUP"
  discovery:
    group: "DEFAULT_GROUP"
```

```java
// 配置注入（Nacos 中存在 demo.db.url 时自动拉取/刷新）
@Component
public class DbProps {
    @CloudConfig("demo.db.url")
    String url;
}

// 或手动拉取
String yaml = CloudClient.config().pull("DEFAULT_GROUP", "demo.db.url");
```

> 注册发现在引入 cloud discovery 插件并配置 `solon.app.name/group` 后通常自动完成；消费者用 `@NamiClient(name="user-service")` 或 `CloudClient.discovery().find(...)`。

---

## Cloud Discovery — 服务注册与发现

### 适配插件

| 插件 | 发现刷新 | 协议 | namespace | group |
|---|---|---|---|---|
| `local-solon-cloud-plugin` | 不支持 | / | 不支持 | 不支持 |
| `jmdns-solon-cloud-plugin` | 支持 | dns | 不支持 | 支持 |
| `nacos-solon-cloud-plugin` | 实时 | tcp | 支持 | 支持 |
| `nacos2-solon-cloud-plugin` | 实时 | tcp | 支持 | 支持 |
| `nacos3-solon-cloud-plugin` | 实时 | tcp | 支持 | 支持 |
| `water-solon-cloud-plugin` | 实时 | tcp | 不支持 | 支持 |
| `consul-solon-cloud-plugin` | 定时拉取 | http | 不支持 | 不支持 |
| `zookeeper-solon-cloud-plugin` | 实时 | tcp | 不支持 | 不支持 |
| `polaris-solon-cloud-plugin` | 实时 | grpc | 支持 | 支持 |
| `etcd-solon-cloud-plugin` | 实时 | http | 不支持 | 支持 |

### 配置示例（Nacos）

```yaml
solon.cloud.nacos:
  server: "127.0.0.1:8848"
  namespace: "dev"
  discovery:
    group: "DEFAULT_GROUP"
    serviceName: "demo-service"
```

---

## Cloud Event — 分布式事件总线

### 适配插件

| 插件 | 确认重试 | 自动延时 | 定时事件 | 消息事务 |
|---|---|---|---|---|
| `local-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | / |
| `folkmq-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | 支持 |
| `kafka-solon-cloud-plugin` | 支持 | / | / | 支持 |
| `rabbitmq-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | 支持 |
| `rocketmq-solon-cloud-plugin` | 支持 | 支持 | 半支持 | / |
| `rocketmq5-solon-cloud-plugin` | 支持 | 支持 | 支持 | 半支持 |
| `aliyun-ons-solon-cloud-plugin` | 支持 | 支持 | 支持 | 支持 |
| `activemq-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | 支持 |
| `water-solon-cloud-plugin` | 支持 | 支持 | 支持 | 支持 |
| `mqtt-solon-cloud-plugin` | 支持 | / | / | / |
| `mqtt5-solon-cloud-plugin` | 支持 | / | / | / |
| `jedis-solon-cloud-plugin` | / | / | / | / |

### 事件发布与订阅

```java
// 发布
CloudClient.event().publish(new Event("topic.order", "order-1"));

// 订阅（@CloudEvent 标注在类上，实现 CloudEventHandler 接口）
@CloudEvent("topic.order")
public class OrderEventHandler implements CloudEventHandler {
    @Override
    public boolean handle(Event event) throws Throwable {
        System.out.println(event.content());
        return true;
    }
}
```

虚拟组配置（类似 namespace 隔离）：

```yaml
solon.cloud.water:
  event:
    group: demo  # 所有发送、订阅自动加上此组
```

---

## Cloud Job — 分布式定时任务

### 适配插件

| 插件 | cron | 自动注册 | 支持脚本 | 分布式调度 | 控制台 |
|---|---|---|---|---|---|
| `local-solon-cloud-plugin` | 支持 | 支持 | 不支持 | 不支持 | 无 |
| `quartz-solon-cloud-plugin` | 支持 | 支持 | 不支持 | 支持 | 无 |
| `water-solon-cloud-plugin` | 支持 | 支持 | 支持 | 支持 | 有 |
| `xxl-job-solon-cloud-plugin` | 支持 | 不支持 | 不支持 | 支持 | 有 |
| `powerjob-solon-cloud-plugin` | 支持 | 不支持 | 不支持 | 支持 | 有 |

### 任务声明

```java
@CloudJob("demoJob")
public class DemoJob implements CloudJobHandler {
    @Override
    public void handle(Context ctx) throws Throwable {
        // 任务逻辑
    }
}
```

```yaml
solon.cloud.local:
  job:
    demoJob:
      cron: "0 0/5 * * * ?"  # 每 5 分钟
```

---

## 其它 Cloud 能力索引

| 能力 | Reference |
|---|---|
| File / Breaker / Gateway | `cloud_ops.md` |
| Trace / Metric / Id / Lock | `cloud_ops.md` |
| Nami RPC + 服务发现消费 | `remoting.md` |
