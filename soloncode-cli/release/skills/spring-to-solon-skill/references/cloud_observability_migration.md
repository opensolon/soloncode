# Cloud 可观测性与分布式组件迁移参考

> Spring Cloud → Solon Cloud 迁移指南（目标版本：Solon 3.10.x）
> 参考文档：[solon.noear.org/article/compare-springcloud](https://solon.noear.org/article/compare-springcloud)

## 1. 断路器迁移

### 1.1 依赖迁移

**Spring Cloud Resilience4j：**
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
</dependency>
```

**Solon Cloud Breaker：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-breaker</artifactId>
</dependency>
```

### 1.2 注解迁移

**Spring Cloud (Hystrix)：**
```java
@Service
public class UserService {
    @HystrixCommand(fallbackMethod = "getUserFallback")
    public User getUser(Long id) {
        return userServiceClient.getUser(id);
    }

    public User getUserFallback(Long id) {
        return new User(-1L, "降级用户");
    }
}
```

**Spring Cloud (Resilience4j)：**
```java
@Service
public class UserService {
    @CircuitBreaker(name = "userService", fallbackMethod = "getUserFallback")
    public User getUser(Long id) {
        return userServiceClient.getUser(id);
    }

    public User getUserFallback(Long id, Exception e) {
        return new User(-1L, "降级用户");
    }
}
```

**Solon Cloud Breaker：**
```java
@Component
public class UserService {
    @CloudBreaker("userService")
    public User getUser(Long id) {
        return userServiceClient.getUser(id);
    }

    // 降级逻辑通过 fallback 属性指定
    @CloudBreaker(value = "userService", fallback = "getUserFallback")
    public User getUserSafe(Long id) {
        return userServiceClient.getUser(id);
    }

    public User getUserFallback(Long id) {
        return new User(-1L, "降级用户");
    }
}
```

### 1.3 配置迁移

**Spring Cloud Resilience4j (application.yml)：**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      userService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 60000
```

**Solon Cloud Breaker (application.yml)：**
```yaml
solon:
  cloud:
    local:
      breaker:
        userService:
          slidingWindowSize: 10
          failureRateThreshold: 50
          waitDurationInOpenState: 60s
```

**关键差异：**
- `@HystrixCommand` / `@CircuitBreaker` → `@CloudBreaker`
- 降级方法通过 `fallback` 属性指定方法名，而非 Spring 的约定方法签名。
- Solon 的断路器配置统一在 `solon.cloud.local.breaker` 下。

## 2. 任务调度迁移

### 2.1 依赖迁移

**Spring Boot 内置调度：**
```xml
<!-- Spring Boot 自带，无需额外依赖 -->
```

**Solon Cloud Job：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-job</artifactId>
</dependency>
```

### 2.2 启用注解迁移

**Spring Boot：**
```java
@SpringBootApplication
@EnableScheduling  // 显式启用调度
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

**Solon Cloud：**
```java
@SolonMain
public class Application {
    public static void main(String[] args) {
        Solon.start(Application.class, args);
    }
}
```

### 2.3 任务声明迁移

**Spring Boot (@Scheduled)：**
```java
@Component
public class DataSyncTask {

    @Scheduled(fixedRate = 5000)  // 每5秒执行一次
    public void syncData() {
        System.out.println("开始同步数据...");
    }

    @Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨2点
    public void cleanupData() {
        System.out.println("开始清理数据...");
    }
}
```

**Solon Cloud (@CloudJob)：**
```java
@Component
public class DataSyncTask {

    // 分布式任务，同一时刻只有一个实例执行
    @CloudJob("syncDataJob")
    public void syncData(Context ctx) {
        System.out.println("开始同步数据...");
    }

    @CloudJob("cleanupDataJob")
    public void cleanupData(Context ctx) {
        System.out.println("开始清理数据...");
    }
}
```

### 2.4 任务调度配置

```yaml
solon:
  cloud:
    job:
      local:
        syncDataJob:
          cron: "0/5 * * * * ?"     # 每5秒
        cleanupDataJob:
          cron: "0 0 2 * * ?"       # 每天凌晨2点
```

**关键差异：**
- `@Scheduled` → `@CloudJob`
- `@CloudJob` 是分布式任务，天然具备 **防重复执行** 能力（多实例部署时只有一个实例会执行）。
- 任务方法签名可接收 `Context` 参数，获取执行上下文。
- cron 表达式格式与 Spring 一致，无需调整。

**陷阱提醒：**
- 如果只需要单机调度（不需要分布式协调），可继续使用 Solon 的 `@Scheduled`（由 `solon-scheduling-simple` 提供）。
- `@CloudJob` 的任务名称是必须的，用于在注册中心标识任务。

## 3. 链路追踪迁移

### 3.1 依赖迁移

**Spring Cloud Sleuth：**
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-sleuth</artifactId>
</dependency>
```

**Solon Cloud Trace：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-trace</artifactId>
</dependency>
```

### 3.2 获取 TraceId 迁移

**Spring Cloud Sleuth：**
```java
@Service
public class OrderService {
    @Autowired
    private Tracer tracer;

    public void processOrder() {
        String traceId = tracer.currentSpan().context().traceId();
        System.out.println("当前 TraceId: " + traceId);
    }
}
```

**Solon Cloud Trace：**
```java
@Component
public class OrderService {
    public void processOrder() {
        String traceId = CloudClient.trace().getTraceId();
        System.out.println("当前 TraceId: " + traceId);
    }
}
```

### 3.3 自定义 Span 迁移

**Spring Cloud Sleuth：**
```java
@Service
public class OrderService {
    @Autowired
    private Tracer tracer;

    @NewSpan("processPayment")
    public void processPayment() {
        // 自动创建新 Span
    }
}
```

**Solon Cloud Trace：**
```java
@Component
public class OrderService {
    public void processPayment() {
        CloudClient.trace().newSpan("processPayment");
        try {
            // 业务逻辑...
        } finally {
            CloudClient.trace().stopSpan();
        }
    }
}
```

**关键差异：**
- Sleuth 通过 `Tracer` 注入使用；Solon 通过 `CloudClient.trace()` 静态入口。
- Sleuth 的 `@NewSpan` 注解自动创建 Span；Solon 需要手动管理 Span 生命周期。

## 4. 分布式锁迁移

### 4.1 依赖迁移

**Spring Integration (Zookeeper Lock)：**
```xml
<dependency>
    <groupId>org.springframework.integration</groupId>
    <artifactId>spring-integration-zookeeper</artifactId>
</dependency>
```

**Solon Cloud Lock：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-lock</artifactId>
</dependency>
```

### 4.2 锁使用迁移

**Spring Integration：**
```java
@Service
public class OrderService {
    @Autowired
    private ZookeeperLockRegistry lockRegistry;

    public void processOrder(String orderId) {
        Lock lock = lockRegistry.obtain(orderId);
        try {
            if (lock.tryLock(10, TimeUnit.SECONDS)) {
                try {
                    // 处理订单逻辑
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

**Solon Cloud Lock：**
```java
@Component
public class OrderService {
    public void processOrder(String orderId) {
        if (CloudClient.lock().tryLock(orderId, 10)) {
            try {
                // 处理订单逻辑
            } finally {
                CloudClient.lock().unlock(orderId);
            }
        }
    }
}
```

**关键差异：**
- Solon 使用 `tryLock(key, seconds)` 返回 boolean，需要手动 `unlock()`。
- 无需注入特定的 LockRegistry，统一通过 `CloudClient.lock()` 访问。

## 5. 分布式ID迁移

> Spring Cloud 无原生分布式ID组件，通常需要自建或引入第三方（如雪花算法）。

**Solon Cloud Id：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-id</artifactId>
</dependency>
```

**使用示例：**
```java
@Component
public class OrderService {
    public void createOrder() {
        long id = CloudClient.id().generate();
        System.out.println("生成订单ID: " + id);
    }
}
```

## 6. 分布式文件迁移

> Spring Cloud 无原生分布式文件组件。

**Solon Cloud File：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-file</artifactId>
</dependency>
```

**使用示例：**
```java
@Component
public class FileService {
    public String upload(byte[] data, String fileName) {
        Media media = new Media(data, "text/plain");
        CloudClient.file().put(fileName, media);
        return fileName;
    }

    public byte[] download(String key) {
        Media result = CloudClient.file().get(key);
        return result.bodyAsBytes();
    }
}
```

## 7. 分布式名单迁移

> Spring Cloud 无原生对应组件。

**Solon Cloud List：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-list</artifactId>
</dependency>
```

**使用示例：**
```java
@Component
public class IpBlacklistService {
    public void addToBlacklist(String ip) {
        CloudClient.list().add("ip-blacklist", ip);
    }

    public boolean isBlacklisted(String ip) {
        return CloudClient.list().inListOfIp("ip-blacklist", ip);
    }

    public void removeFromBlacklist(String ip) {
        CloudClient.list().remove("ip-blacklist", ip);
    }
}
```

## 8. 分布式监控迁移

> Spring Cloud 使用 Micrometer + Prometheus 方案。

**Solon Cloud Metric：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-metric</artifactId>
</dependency>
```

**使用示例：**
```java
@Component
public class OrderMetric {
    public void recordOrderCount() {
        CloudClient.metric().addCount("order", "order.count", 1);
    }
}
```

## 9. 分布式日志迁移

> Spring Cloud 通常集成 ELK (Logstash)。

**Solon Cloud Log：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-log</artifactId>
</dependency>
```

**使用示例：**
```java
@Component
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public void processOrder(Long orderId) {
        // 日志自动上报到分布式日志系统，traceId 自动关联
        log.info("处理订单: orderId={}", orderId);
    }
}
```

## 10. 可观测性迁移检查清单

- [ ] 将 `@HystrixCommand` / `@CircuitBreaker` 改为 `@CloudBreaker`
- [ ] 将 `@Scheduled` 改为 `@CloudJob`（如需分布式调度）
- [ ] 将 `Tracer` 注入改为 `CloudClient.trace()` 静态调用
- [ ] 确认分布式锁使用 `CloudClient.lock()` 替代第三方锁库
- [ ] 确认所有可观测性插件（trace、metric、log）配置正确
- [ ] 验证链路追踪 traceId 在各服务间正确传递
- [ ] 验证断路器降级逻辑正常触发
