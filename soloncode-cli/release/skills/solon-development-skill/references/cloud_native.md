# Cloud Native — 微服务与分布式

> 适用场景：服务注册与发现、配置中心、消息队列、文件存储、分布式任务调度、熔断限流、网关。

## 核心用法（统一 API）

Solon Cloud 提供统一的 API 接口，不同中间件只需更换插件依赖即可切换。

### 注解清单

| 注解 | 适用范围 | 说明 |
|---|---|---|
| `@CloudConfig` | 类、字段、参数 | 配置注入 |
| `@CloudEvent` | 类 | 事件订阅 |
| `@CloudJob` | 类、函数 | 分布式定时任务 |
| `@CloudBreaker` | 类、函数 | 熔断/限流 |

### Cloud Client 统一入口

```java
// 配置服务
CloudClient.config().pull(group, key);

// 注册与发现（一般自动完成，无需手动调用）
CloudClient.discovery().register(instance);
CloudClient.discovery().find(group, service);

// 事件总线
CloudClient.event().publish(new Event("topic.order", "content"));

// 分布式 ID
long id = CloudClient.id().generate();

// 分布式锁
if (CloudClient.lock().tryLock("demo.lock.key", 3)) {
    // 业务处理
    CloudClient.lock().unlock("demo.lock.key");
}

// 文件服务
CloudClient.file().put(key, new Media(stream));
Media media = CloudClient.file().get(key);
CloudClient.file().delete(key);

// 分布式计数（指标监控）
CloudClient.metric().addCount("demo", "demo.api.user.add", 1);

// 链路追踪
String traceId = CloudClient.trace().getTraceId();

// 熔断（手动模式）
try (AutoCloseable entry = CloudClient.breaker().entry("main")) {
    // 业务处理
} catch (BreakerException ex) {
    // 超出限制
}
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

## Cloud File — 分布式文件服务

### 适配插件

| 插件 | 本地文件 | 云端文件 | 支持服务商 |
|---|---|---|---|
| `local-solon-cloud-plugin` | 支持 | / | / |
| `aliyun-oss-solon-cloud-plugin` | / | 支持 | 阿里云 |
| `aws-s3-solon-cloud-plugin` | / | 支持 | S3 协议 |
| `file-s3-solon-cloud-plugin` | 支持 | 支持 | S3 + 本地 |
| `qiniu-kodo-solon-cloud-plugin` | / | 支持 | 七牛云 |
| `minio-solon-cloud-plugin` | / | 支持 | MinIO |
| `minio7-solon-cloud-plugin` | / | 支持 | MinIO |
| `fastdfs-solon-cloud-plugin` | / | 支持 | FastDFS |

### 文件操作

```java
import org.noear.solon.cloud.model.Media;

// 上传（put）
CloudClient.file().put("test.txt", new Media(inputStream));

// 下载（get），返回 Media 对象
Media media = CloudClient.file().get("test.txt");
InputStream stream = media.body();

// 删除
CloudClient.file().delete("test.txt");
```

---

## Cloud Breaker — 熔断/限流

### 适配插件

| 插件 | Backend |
|---|---|
| `semaphore-solon-cloud-plugin` | 信号量 |
| `guava-solon-cloud-plugin` | Guava RateLimiter |
| `sentinel-solon-cloud-plugin` | Alibaba Sentinel |
| `resilience4j-solon-cloud-plugin` | Resilience4j |

### 配置示例（此配置可通过配置中心动态更新）

```yaml
solon.cloud.local:
  breaker:
    root: 100  # 默认 100（Qps100 或信号量 100，视插件而定）
    main: 150  # 名为 main 的断路器阈值为 150

# 可放到配置中心，例如：
# solon.cloud.water:
#   config.load: "breaker.yml"
```

### @CloudBreaker 注解属性

| 属性 | 描述 | 备注 |
|---|---|---|
| `value` | 断路器名字 | |
| `name` | 断路器名字 | 与 `value` 互为别名，用一个即可 |
| `fallback` | 降级方法名 | 被限流时执行的后备方法 |

> 阈值不支持代码里写死，需要通过上述配置实现。

### 通过注解埋点

```java
@CloudBreaker("test")  // test 使用 root 的阈值配置
@Controller
public class BreakerController {
    @Mapping("/breaker")
    public void breaker() {
        // 业务逻辑
    }
}
```

### 手动模式埋点

```java
public class BreakerFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        if (CloudClient.breaker() == null) {
            chain.doFilter(ctx);
        } else {
            try (AutoCloseable entry = CloudClient.breaker().entry("main")) {
                chain.doFilter(ctx);
            } catch (BreakerException ex) {
                throw new IllegalStateException("Request capacity exceeds limit");
            }
        }
    }
}
```

---

## Cloud Gateway — 分布式网关

Solon Cloud Gateway 是基于 Solon Cloud、Vert.X 和 Solon-Rx(reactive-streams) 实现的响应式接口网关。采用流式转发策略，性能好、内存少。内置 solon-server-vertx，同时支持常规 Web 开发。

> 提醒：不要再引入其它 http 的 solon-server-xxx 插件（已内置 solon-server-vertx，避免冲突）。

### 建议

- **推荐**使用专业网关产品（nginx、apisix、kong 等）
- Solon Cloud Gateway 可用于 Java 技术栈内的网关场景

### 核心能力

- 服务路由（基于 LoadBalance）
- 全局过滤器（`CloudGatewayFilter`）
- 路由过滤器定制
- 签权/跨域处理
- 基于 Cloud Config 动态更新路由
- 响应式支持

### Maven 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-gateway</artifactId>
</dependency>
```

### 手动路由配置

```yaml
server.port: 8080

solon.app:
  name: demo-gateway
  group: gateway

solon.cloud.gateway:
  routes:
    - id: demo
      target: "http://localhost:8081"  # 或负载均衡地址 "lb://user-service"
      predicates:
        - "Path=/demo/**"
      filters:
        - "StripPrefix=1"
  defaultFilters:
    - "AddRequestHeader=Gateway-Version,1.0"
```

### 自动发现配置（配合注册中心）

```yaml
solon.app:
  name: demo-gateway
  group: gateway

solon.cloud.nacos:
  server: "127.0.0.1:8848"

solon.cloud.gateway:
  discover:
    enabled: true
    excludedServices:
      - "self-service-name"
  defaultFilters:
    - "StripPrefix=1"
```

### 主要配置项说明

| 配置项 | 说明 |
|---|---|
| `routes[].id` | 路由标识（必选） |
| `routes[].target` | 目标地址，支持 `http://`、`https://`、`ws://`、`wss://`、`lb://` |
| `routes[].predicates` | 路由检测器 |
| `routes[].filters` | 路由过滤器 |
| `defaultFilters` | 所有路由的默认过滤器 |
| `discover.enabled` | 是否启用自动发现 |
| `discover.excludedServices` | 排除的服务 |
| `httpClient.responseTimeout` | 默认响应超时（秒） |

### Local Gateway 与 Cloud Gateway 的区别

| 类型 | 区别 | 说明 |
|---|---|---|
| Solon Local Gateway | 本地网关 | 为本地组件提供路由和控制 |
| Solon Cloud Gateway | 分布式网关 | 为分布式服务提供路由和控制 |

Local Gateway 是 Solon 框架特殊的 Handler 实现，通过注册收集后在局部范围内提供二级路由、拦截、过滤、熔断、异常处理等功能。适用于为同一批接口安排多个网关以定制不同的协议效果。

```java
// Local Gateway 示例：通过 tag 收集 Bean
@Mapping("/api/**")
@Component
public class ApiGateway extends Gateway {
    @Override
    protected void register() {
        // 前置过滤（令牌验证等）
        filter((c, chain) -> {
            if (c.param("t") == null) {
                c.result = Result.failure(403, "Missing authentication information");
                c.setHandled(true);
            }
            chain.doFilter(c);
        });

        // 收集 tag="api" 的 Bean
        addBeans(bw -> "api".equals(bw.tag()));
    }

    @Override
    public void render(Object obj, Context c) throws Throwable {
        if (obj instanceof Throwable) {
            c.render(Result.failure("unknown error"));
        } else {
            c.render(obj);
        }
    }
}
```

---

## Cloud Trace — 链路追踪

使用 opentracing（全面）和 CloudTraceService（简单）两套接口。CloudTraceService 只提供 TraceId 传播能力。

> 提示：solon-cloud 插件自带了一个默认实现。

### 适配插件

| 插件 | Backend |
|---|---|
| `water-solon-cloud-plugin` | Water |
| `jaeger-solon-cloud-plugin` | Jaeger |
| `zipkin-solon-cloud-plugin` | Zipkin |

### 基本用法

```java
// 获取当前 TraceId
String traceId = CloudClient.trace().getTraceId();

// 传递给 slf4j MDC
MDC.put(CloudClient.trace().HEADER_TRACE_ID_NAME(), traceId);

// 通过 Http Header 传给下游节点
HttpUtils.url("http://x.x.x.x")
    .headerAdd(CloudClient.trace().HEADER_TRACE_ID_NAME(), traceId)
    .get();
```

### 与 Web Filter 集成示例

```java
public class TraceIdFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String traceId = CloudClient.trace().getTraceId();
        MDC.put("X-TraceId", traceId);
        chain.doFilter(ctx);
    }
}
```

## Cloud Metric — 指标监控

使用 micrometer（全面）和 CloudMetricService（简单）两套接口。当有 micrometer 适配插件时，也会收集 CloudMetricService 接口的数据。

### 适配插件

| 插件 | Backend |
|---|---|
| `water-solon-cloud-plugin` | Water |
| `micrometer-solon-cloud-plugin` | Micrometer |

### 代码示例

```java
// 监控路径请求性能（可进一步记录超5秒、超1秒的次数及曲线图）
CloudClient.metric().addMeter("path", path, milliseconds);

// 监控路径请求出错次数
CloudClient.metric().addCount("path_err", path, 1);

// 监控运行时状态
CloudClient.metric().addGauge("service", "runtime", RuntimeStatus.now());
```

## Cloud Id — 分布式 ID

生成有序不重复 ID，一般用于日志 ID、事务 ID、自增 ID 等无逻辑性 ID 场景。

### 适配插件

| 插件 | Backend |
|---|---|
| `snowflake-solon-cloud-plugin` | Snowflake 雪花算法 |

### 代码示例

```java
long logId = CloudClient.id().generate();
```

---

## Cloud Lock — 分布式锁

### 适配插件

| 插件 | Backend |
|---|---|
| `water-solon-cloud-plugin` | Water |
| `jedis-solon-cloud-plugin` | Redis (Jedis) |

### 代码示例

```java
// 尝试获取锁，3秒超时（防重复提交）
if (CloudClient.lock().tryLock("user_" + userId, 3)) {
    // 业务处理
    CloudClient.lock().unlock("user_" + userId);
} else {
    // 请求太频繁
}
```
