# Spring Cloud → Solon Cloud 迁移指南

> 目标版本：Solon 3.10.x
> 参考文档：[solon.noear.org/article/compare-springcloud](https://solon.noear.org/article/compare-springcloud)

---

## 目录

- [1. 组件对照总览](#1-组件对照总览)
- [2. 注册发现迁移](#2-注册发现迁移)
- [3. 配置中心迁移](#3-配置中心迁移)
- [4. RPC 迁移](#4-rpc-迁移)
- [5. 网关迁移](#5-网关迁移)
- [6. 事件/消息迁移](#6-事件消息迁移)
- [7. 断路器迁移](#7-断路器迁移)
- [8. 任务调度迁移](#8-任务调度迁移)
- [9. 链路追踪迁移](#9-链路追踪迁移)
- [10. 分布式锁迁移](#10-分布式锁迁移)
- [11. 分布式ID迁移](#11-分布式id迁移)
- [12. 分布式文件迁移](#12-分布式文件迁移)
- [13. 分布式名单迁移](#13-分布式名单迁移)
- [14. 分布式监控迁移](#14-分布式监控迁移)
- [15. 分布式日志迁移](#15-分布式日志迁移)
- [16. 常见陷阱与注意事项](#16-常见陷阱与注意事项)

---

## 1. 组件对照总览

| Spring Cloud | Solon Cloud | 接口定义 | 说明 |
|---|---|---|---|
| Spring Cloud Config | Solon Cloud Config | `CloudConfigService` | 分布式配置 |
| Eureka / Nacos Discovery | Solon Cloud Discovery | `CloudDiscoveryService` | 注册与发现 |
| Spring Cloud Gateway | Solon Cloud Gateway | - | 分布式网关 |
| Resilience4j / Hystrix | Solon Cloud Breaker | `CloudBreakerService` | 断路器/限流 |
| Spring Cloud Sleuth | Solon Cloud Trace | `CloudTraceService` | 分布式跟踪 |
| Spring Cloud Stream | Solon Cloud Event | `CloudEventService` | 分布式事件总线 |
| Spring Cloud Task | Solon Cloud Job | `CloudJobService` | 分布式任务 |
| Spring Cloud Zookeeper | Solon Cloud Lock | `CloudLockService` | 分布式锁 |
| Spring Cloud Bus | *(由 Cloud Event 替代)* | `CloudEventService` | 事件广播 |
| / | Solon Cloud Id | `CloudIdService` | 分布式ID |
| / | Solon Cloud File | `CloudFileService` | 分布式文件 |
| / | Solon Cloud List | `CloudListService` | 分布式名单 |
| / | Solon Cloud Metric | `CloudMetricService` | 分布式监控 |
| / | Solon Cloud Log | `CloudLogService` | 分布式日志 |

**关键差异说明：**

- Solon Cloud 采用 **插件化设计**，每个组件对应一个独立的 `solon.cloud.xxx` 插件，按需引入。
- 所有 Cloud 服务统一通过 `CloudClient` 静态入口访问，接口命名遵循 `CloudXxxService` 规范。
- Solon Cloud **不强制绑定** 注册中心实现，同一套代码可无缝切换 Nacos / Consul / Zookeeper 等后端。
- Spring Cloud Bus 的功能在 Solon 中由 `CloudEventService` 统一承载，无需单独组件。

---

## 2. 注册发现迁移

### 2.1 依赖迁移

**Spring Cloud (Eureka)：**

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

**Solon Cloud (Nacos)：**

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-discovery-nacos</artifactId>
</dependency>
```

> 也可使用 `solon-cloud-discovery-consul` 或 `solon-cloud-discovery-zookeeper`，切换时只需更换依赖，代码无需修改。

### 2.2 启用注解迁移

**Spring Cloud：**

```java
// Spring Boot 启动类
@SpringBootApplication
@EnableDiscoveryClient  // 显式启用注册发现
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

**Solon Cloud：**

```java
// Solon 启动类 — 引入 discovery 插件后自动注册，无需额外注解
@SolonMain
public class Application {
    public static void main(String[] args) {
        Solon.start(Application.class, args);
    }
}
```

**关键差异：** Solon Cloud 不需要 `@EnableDiscoveryClient` 等注解，引入插件即自动生效。

### 2.3 配置迁移

**Spring Cloud (application.yml)：**

```yaml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: dev
        group: DEFAULT_GROUP
```

**Solon Cloud (application.yml)：**

```yaml
solon:
  app:
    name: user-service          # 对应 spring.application.name
  cloud:
    nacos:
      discovery:
        serverAddr: 127.0.0.1:8848   # 注意：驼峰命名（Solon 风格）
        namespace: dev
        group: DEFAULT_GROUP
```

**陷阱提醒：**
- Solon Cloud 配置键名使用 **驼峰命名** (`serverAddr`)，不是 Spring 的 **短横线命名** (`server-addr`)。
- 服务名称由 `solon.app.name` 指定，而非 `spring.application.name`。

### 2.4 服务发现使用迁移

**Spring Cloud (DiscoveryClient)：**

```java
@Service
public class OrderService {
    @Autowired
    private DiscoveryClient discoveryClient;

    public List<ServiceInstance> getUserInstances() {
        return discoveryClient.getInstances("user-service");
    }
}
```

**Solon Cloud (CloudClient)：**

```java
@Component
public class OrderService {
    public List<Discovery> getUserInstances() {
        // 通过 CloudClient 获取发现服务，查找实例列表
        return CloudClient.discovery().findInstances("user-service");
    }
}
```

---

## 3. 配置中心迁移

### 3.1 依赖迁移

**Spring Cloud Config：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

**Solon Cloud (Nacos Config)：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-config-nacos</artifactId>
</dependency>
```

> 也可使用 `solon-cloud-config-polaris` 等其他实现。

### 3.2 配置迁移

**Spring Cloud (bootstrap.yml)：**

```yaml
spring:
  cloud:
    config:
      uri: http://config-server:8888
      name: user-service
      profile: dev
      label: main
```

**Solon Cloud (application.yml)：**

```yaml
solon:
  cloud:
    nacos:
      config:
        serverAddr: 127.0.0.1:8848
        namespace: dev
        group: DEFAULT_GROUP
        # 配置加载规则
        files:
          - dataId: "user-service.yml"
          - dataId: "user-service-dev.yml"
```

**陷阱提醒：** Spring Cloud Config 需要独立的 Config Server，而 Solon Cloud 通常直连 Nacos/Polaris，架构更简洁。

### 3.3 配置读取迁移

**Spring Cloud：**

```java
@RefreshScope  // 支持配置热更新
@RestController
public class UserController {
    @Value("${user.max-count:100}")
    private int maxCount;

    @GetMapping("/config")
    public int getConfig() {
        return maxCount;
    }
}
```

**Solon Cloud：**

```java
// Solon 通过 @Inject 注入配置，支持热更新（无需额外注解）
@RestController
public class UserController {
    @Inject("${user.max-count:100}")
    private int maxCount;

    @GetMapping("/config")
    public int getConfig() {
        return maxCount;
    }
}
```

**关键差异：**
- `@Value` → `@Inject`（Solon 的统一注入注解）。
- Solon **不需要** `@RefreshScope`，配置变更自动感知。
- 配置前缀绑定使用 `@Inject("${prefix}")` 配合 `@Configuration` 注解类。

### 3.4 配置监听迁移

**Spring Cloud：**

```java
@RestController
@RefreshScope
public class ConfigController {
    @Value("${dynamic.value}")
    private String dynamicValue;
}
```

**Solon Cloud：**

```java
// 方式1：自动注入（推荐）
@RestController
public class ConfigController {
    @Inject("${dynamic.value}")
    private String dynamicValue;  // 配置变更时自动更新
}

// 方式2：手动监听配置变更
@Component
public class ConfigListener {
    public void init() {
        CloudClient.config().listen((cfgGroup, cfgKey, event) -> {
            System.out.println("配置变更: " + cfgKey + " = " + event.newValue());
        });
    }
}
```

---

## 4. RPC 迁移

### 4.1 依赖迁移

**Spring Cloud OpenFeign：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

**Solon Cloud Nami：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-feign-compatible</artifactId>
</dependency>
```

> 注意：如果仅使用 Nami 原生 API，只需 `solon-serialization-json` + `nami-channel-http`。

### 4.2 客户端声明迁移

**Spring Cloud (OpenFeign)：**

```java
// 声明 Feign 客户端接口
@FeignClient(name = "user-service", path = "/api/users")
public interface UserServiceClient {

    @GetMapping("/{id}")
    User getUser(@PathVariable("id") Long id);

    @PostMapping
    User createUser(@RequestBody User user);

    @GetMapping
    List<User> listUsers();
}
```

**Solon Cloud (Nami)：**

```java
// 声明 Nami 客户端接口
@NamiClient(name = "user-service", path = "/api/users")
public interface UserServiceClient {

    @NamiClientMapping("GET /{id}")
    User getUser(@Param("id") Long id);

    @NamiClientMapping("POST /")
    User createUser(@Body User user);

    @NamiClientMapping("GET /")
    List<User> listUsers();
}
```

**关键差异：**
- `@FeignClient` → `@NamiClient`
- `@GetMapping` / `@PostMapping` → `@NamiClientMapping("METHOD /path")`
- `@PathVariable` → `@Param`
- `@RequestBody` → `@Body`

### 4.3 客户端使用迁移

**Spring Cloud：**

```java
@Service
public class OrderService {
    @Autowired
    private UserServiceClient userServiceClient;

    public User getUser(Long id) {
        return userServiceClient.getUser(id);
    }
}
```

**Solon Cloud：**

```java
@Component
public class OrderService {
    @Inject
    private UserServiceClient userServiceClient;

    public User getUser(Long id) {
        return userServiceClient.getUser(id);
    }
}
```

### 4.4 直连模式 vs 负载均衡模式

**Solon Nami 支持两种调用模式：**

```java
// 模式1：通过注册中心负载均衡（推荐）
@NamiClient(name = "user-service", path = "/api/users")
public interface UserServiceClient {
    // ...
}

// 模式2：直连地址模式（调试或特殊场景）
@NamiClient(url = "http://127.0.0.1:8081", path = "/api/users")
public interface UserServiceClient {
    // ...
}
```

**陷阱提醒：**
- `name` 模式依赖注册中心发现，需要先配置好 Discovery。
- `url` 模式绕过注册中心，适合本地调试或固定地址场景。
- 二者可同时存在于同一项目，不同接口使用不同模式。

---

## 5. 网关迁移

### 5.1 依赖迁移

**Spring Cloud Gateway：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
```

**Solon Cloud Gateway：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-gateway</artifactId>
</dependency>
```

### 5.2 路由配置迁移

**Spring Cloud Gateway (application.yml)：**

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service          # lb:// 表示负载均衡
          predicates:
            - Path=/api/users/**
          filters:
            - StripPrefix=1
            - name: CircuitBreaker
              args:
                name: userCircuitBreaker
```

**Solon Cloud Gateway (application.yml)：**

```yaml
solon:
  cloud:
    gateway:
      routes:
        - id: user-service
          target: "lb://user-service"     # 负载均衡目标
          predicates:
            - "Path=/api/users/**"
          filters:
            - "StripPrefix=1"
```

**关键差异：**
- `uri` → `target`
- `lb://` 前缀在 Solon 中同样支持，表示通过注册中心负载均衡。
- `predicates` 和 `filters` 的配置格式从 YAML 对象数组变为 **字符串数组**。
- 断路器通过独立注解 `@CloudBreaker` 实现，不内嵌在网关配置中。

### 5.3 完整网关配置示例

```yaml
# Solon Cloud Gateway 完整配置
solon:
  app:
    name: gateway-service
  cloud:
    gateway:
      routes:
        - id: demo
          target: "https://www.baidu.com"
          predicates:
            - "Path=/**"
        - id: user-service
          target: "lb://user-service"
          predicates:
            - "Path=/api/users/**"
          filters:
            - "StripPrefix=1"
        - id: order-service
          target: "lb://order-service"
          predicates:
            - "Path=/api/orders/**"
          filters:
            - "StripPrefix=1"
```

### 5.4 网关启动类

**Spring Cloud Gateway：**

```java
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

**Solon Cloud Gateway：**

```java
@SolonMain
public class GatewayApplication {
    public static void main(String[] args) {
        Solon.start(GatewayApplication.class, args);
    }
}
```

**陷阱提醒：** Spring Cloud Gateway 基于 WebFlux，与 Spring MVC 互斥；Solon Cloud Gateway 无此限制，可灵活搭配。

---

## 6. 事件/消息迁移

### 6.1 依赖迁移

**Spring Cloud Stream：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-stream-rabbit</artifactId>
</dependency>
```

**Solon Cloud Event：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-event-plus</artifactId>
</dependency>
```

> 也可使用具体实现插件如 `solon-cloud-event-kafka`、`solon-cloud-event-rabbit`。

### 6.2 消息发送迁移

**Spring Cloud Stream：**

```java
@Service
public class OrderService {
    @Autowired
    private StreamBridge streamBridge;

    public void publishOrder(OrderEvent event) {
        streamBridge.send("orderOutput", event);
    }
}
```

**Solon Cloud Event：**

```java
@Component
public class OrderService {
    public void publishOrder(OrderEvent event) {
        // 手动发布事件到 Cloud Event 总线
        CloudClient.event().publish(
            new Event("order.topic", event)
        );
    }
}
```

### 6.3 消息监听迁移

**Spring Cloud Stream：**

```java
@Component
public class OrderEventListener {

    @StreamListener("orderInput")
    public void handleOrder(OrderEvent event) {
        System.out.println("收到订单事件: " + event.getOrderId());
    }
}
```

**Solon Cloud Event：**

```java
@Component
public class OrderEventListener {

    // 通过 @CloudEvent 注解声明订阅
    @CloudEvent("order.topic")
    public void handleOrder(Event event) throws Throwable {
        OrderEvent order = event.data().toBean(OrderEvent.class);
        System.out.println("收到订单事件: " + order.getOrderId());
    }
}
```

**关键差异：**
- `@StreamListener` → `@CloudEvent`
- 事件对象统一为 `Event`，通过 `event.data()` 获取载荷。
- Spring Cloud Bus 的功能由 `CloudEventService` 统一替代，无需独立组件。

### 6.4 完整消息配置

**Spring Cloud Stream (application.yml)：**

```yaml
spring:
  cloud:
    stream:
      bindings:
        orderOutput:
          destination: order.topic
        orderInput:
          destination: order.topic
          group: order-group
```

**Solon Cloud Event (application.yml)：**

```yaml
solon:
  cloud:
    event:
      topic:
        - "order.topic"
      # 具体实现插件配置（如 Kafka）
      kafka:
        bootstrapServers: "127.0.0.1:9092"
        groupId: "order-group"
```

---

## 7. 断路器迁移

### 7.1 依赖迁移

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

### 7.2 注解迁移

**Spring Cloud (Hystrix)：**

```java
@Service
public class UserService {
    @HystrixCommand(fallbackMethod = "getUserFallback")
    public User getUser(Long id) {
        // 调用远程服务
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
    // 通过 @CloudBreaker 注解实现断路保护
    @CloudBreaker("userService")
    public User getUser(Long id) {
        return userServiceClient.getUser(id);
    }

    // 降级逻辑通过 @CloudBreaker 的 fallback 属性指定
    @CloudBreaker(value = "userService", fallback = "getUserFallback")
    public User getUserSafe(Long id) {
        return userServiceClient.getUser(id);
    }

    public User getUserFallback(Long id) {
        return new User(-1L, "降级用户");
    }
}
```

### 7.3 配置迁移

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

---

## 8. 任务调度迁移

### 8.1 依赖迁移

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

### 8.2 启用注解迁移

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

### 8.3 任务声明迁移

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

### 8.4 任务调度配置

**Solon Cloud Job (application.yml)：**

```yaml
solon:
  cloud:
    job:
      # 任务调度配置（根据具体实现插件）
      # 以本地模式为例：
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

---

## 9. 链路追踪迁移

### 9.1 依赖迁移

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

### 9.2 获取 TraceId 迁移

**Spring Cloud Sleuth：**

```java
import org.springframework.cloud.sleuth.Tracer;

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
        // 通过 CloudClient 获取当前 TraceId
        String traceId = CloudClient.trace().getTraceId();
        System.out.println("当前 TraceId: " + traceId);
    }
}
```

### 9.3 自定义 Span 迁移

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
        // 手动创建 Span
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

---

## 10. 分布式锁迁移

### 10.1 依赖迁移

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

### 10.2 锁使用迁移

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
        // 通过 CloudClient 获取分布式锁服务
        CloudClient.lock().tryLock(orderId, 10, () -> {
            // 处理订单逻辑（在锁的保护下执行）
        });
    }
}
```

**关键差异：**
- Solon 使用回调式 API `tryLock(key, seconds, runnable)`，更简洁且避免忘记释放锁。
- 无需注入特定的 LockRegistry，统一通过 `CloudClient.lock()` 访问。

---

## 11. 分布式ID迁移

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
        // 生成分布式唯一ID
        long id = CloudClient.id().generate();
        System.out.println("生成订单ID: " + id);
    }
}
```

---

## 12. 分布式文件迁移

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
        // 上传文件到分布式文件存储
        CloudFile file = new CloudFile(fileName, "text/plain", data);
        String url = CloudClient.file().upload(file);
        return url;
    }

    public byte[] download(String url) {
        CloudFile file = CloudClient.file().download(url);
        return file.data();
    }
}
```

---

## 13. 分布式名单迁移

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
    // 添加IP到黑名单
    public void addToBlacklist(String ip) {
        CloudClient.list().add("ip-blacklist", ip);
    }

    // 检查IP是否在黑名单中
    public boolean isBlacklisted(String ip) {
        return CloudClient.list().inOf("ip-blacklist", ip);
    }

    // 移除IP
    public void removeFromBlacklist(String ip) {
        CloudClient.list().remove("ip-blacklist", ip);
    }
}
```

---

## 14. 分布式监控迁移

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
        // 记录自定义指标
        CloudClient.metric().add("order.count", 1);
    }
}
```

---

## 15. 分布式日志迁移

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

---

## 16. 常见陷阱与注意事项

### 16.1 配置键命名差异

| 维度 | Spring Cloud | Solon Cloud |
|---|---|---|
| 配置前缀 | `spring.cloud.*` | `solon.cloud.*` |
| 键名风格 | 短横线 (`server-addr`) | 驼峰 (`serverAddr`) |
| 应用名称 | `spring.application.name` | `solon.app.name` |
| 配置文件 | `bootstrap.yml` + `application.yml` | `application.yml` (单文件) |

### 16.2 依赖冲突排查

- Solon Cloud 插件之间互不冲突，可按需组合。
- **不要**同时引入 `solon-cloud-discovery-nacos` 和 `solon-cloud-discovery-consul`，同一类型 Discovery 只能有一个实现。
- Config 和 Discovery 可以使用不同的后端（如 Config 用 Nacos，Discovery 用 Consul），但通常建议统一。

### 16.3 版本兼容性

- Solon 3.10.x 要求 Java 17+。
- 各 Cloud 插件版本与 Solon 框架版本保持一致。
- 引入插件时使用 BOM 管理版本，避免版本不一致。

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-parent</artifactId>
            <version>3.10.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 16.4 CloudClient 使用模式

Solon Cloud 所有服务通过 `CloudClient` 静态入口统一访问：

```java
CloudClient.config()      // 配置服务
CloudClient.discovery()   // 注册发现服务
CloudClient.event()       // 事件服务
CloudClient.lock()        // 分布式锁服务
CloudClient.id()          // 分布式ID服务
CloudClient.file()        // 分布式文件服务
CloudClient.list()        // 分布式名单服务
CloudClient.metric()      // 分布式监控服务
CloudClient.log()         // 分布式日志服务
CloudClient.trace()       // 链路追踪服务
```

### 16.5 Spring Cloud Bus 替代方案

Spring Cloud Bus 在 Solon Cloud 中由 `CloudEventService` 统一替代：

```java
// 广播配置变更事件
CloudClient.event().publish(new Event("config-change", configData));

// 监听配置变更
@CloudEvent("config-change")
public void onConfigChange(Event event) {
    // 处理配置变更
}
```

### 16.6 迁移检查清单

- [ ] 替换所有 `spring-cloud-*` 依赖为 `solon-cloud-*` 对应插件
- [ ] 将 `@EnableDiscoveryClient` / `@EnableCircuitBreaker` 等注解删除（Solon 自动生效）
- [ ] 将 `@FeignClient` 改为 `@NamiClient`，调整方法映射注解
- [ ] 将 `spring.cloud.*` 配置键改为 `solon.cloud.*` 格式（注意驼峰命名）
- [ ] 将 `spring.application.name` 改为 `solon.app.name`
- [ ] 将 `@Value` 改为 `@Inject`
- [ ] 删除 `bootstrap.yml`，合并到 `application.yml`
- [ ] 将 `@StreamListener` 改为 `@CloudEvent`
- [ ] 将 `@HystrixCommand` / `@CircuitBreaker` 改为 `@CloudBreaker`
- [ ] 将 `@Scheduled` 改为 `@CloudJob`（如需分布式调度）
- [ ] 将 `Tracer` 注入改为 `CloudClient.trace()` 静态调用
- [ ] 验证所有 Cloud 插件配置正确，启动无报错
