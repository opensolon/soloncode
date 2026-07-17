# Cloud Ops — 文件 / 熔断 / 网关 / 可观测 / ID / 锁

> 适用场景：Cloud File、Breaker、Gateway、Trace、Metric、Id、Lock。
>
> 目标版本：4.0.3。Config / Discovery / Event / Job 见 `cloud_core.md`。统一入口仍是 `CloudClient`。

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

CloudClient.file().put("test.txt", new Media(inputStream));
Media media = CloudClient.file().get("test.txt");
InputStream stream = media.body();
CloudClient.file().delete("test.txt");
```

## Cloud Breaker — 熔断/限流

### 适配插件

| 插件 | Backend |
|---|---|
| `semaphore-solon-cloud-plugin` | 信号量 |
| `guava-solon-cloud-plugin` | Guava RateLimiter |
| `sentinel-solon-cloud-plugin` | Alibaba Sentinel |
| `resilience4j-solon-cloud-plugin` | Resilience4j |

### 配置示例（可通过配置中心动态更新）

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
        filter((c, chain) -> {
            if (c.param("t") == null) {
                c.result = Result.failure(403, "Missing authentication information");
                c.setHandled(true);
            }
            chain.doFilter(c);
        });

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
String traceId = CloudClient.trace().getTraceId();
MDC.put(CloudClient.trace().HEADER_TRACE_ID_NAME(), traceId);

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
CloudClient.metric().addMeter("path", path, milliseconds);
CloudClient.metric().addCount("path_err", path, 1);
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
