# Cloud 网关与 RPC 迁移参考

> Spring Cloud → Solon Cloud 迁移指南（目标版本：Solon 4.0.x）
> 参考文档：[solon.noear.org/article/compare-springcloud](https://solon.noear.org/article/compare-springcloud)

## 1. RPC 迁移

### 1.1 依赖迁移

**Spring Cloud OpenFeign：**
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

**Solon（推荐 Nami 原生）：**
```xml
<!-- 快捷：solon-rpc 或按需 nami + 通道 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>nami</artifactId>
</dependency>
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>nami-channel-http</artifactId>
</dependency>
<!-- 序列化按需，如 solon-serialization-json / nami-coder-* -->
```

> - **优先** `@NamiClient` 原生迁移（与 `dependency_mapping.md` 的 openfeign → `nami` 一致）。
> - 若需尽量保留 Feign 编程习惯，可评估官方 **`feign-solon-plugin`**（存在于 solon-parent BOM）；**不要**使用不存在的 `solon-cloud-feign-compatible` 坐标。
> - 服务发现场景配合 Cloud discovery 插件，`@NamiClient(name="...")` 走注册中心。

### 1.2 客户端声明迁移

**Spring Cloud (OpenFeign)：**
```java
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
@NamiClient(name = "user-service", path = "/api/users")
public interface UserServiceClient {

    @NamiMapping("GET /{id}")
    User getUser(@Param("id") Long id);

    @NamiMapping("POST /")
    User createUser(@NamiBody User user);

    @NamiMapping("GET /")
    List<User> listUsers();
}
```

**关键差异：**
- `@FeignClient` → `@NamiClient`
- `@GetMapping` / `@PostMapping` → `@NamiMapping("METHOD /path")`
- `@PathVariable` → `@Param`
- `@RequestBody` → `@NamiBody`

> 补充：Solon v3.3.0+ 也支持使用 Solon 原生注解（`@Get`、`@Post`、`@Mapping`、`@Body`、`@Param` 等），效果与 `@NamiMapping` + `@NamiBody` 等价。

### 1.3 客户端使用迁移

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

### 1.4 直连模式 vs 负载均衡模式

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

## 2. 网关迁移

### 2.1 依赖迁移

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

### 2.2 路由配置迁移

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

**Solon Cloud Gateway (app.yml)：**
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

### 2.3 完整网关配置示例

```yaml
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

### 2.4 网关启动类

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

## 3. 事件/消息迁移

### 3.1 依赖迁移

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

### 3.2 消息发送迁移

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
        CloudClient.event().publish(
            new Event("order.topic", event)
        );
    }
}
```

### 3.3 消息监听迁移

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

    @CloudEvent("order.topic")
    public void handleOrder(Event event) throws Throwable {
        OrderEvent order = Solon.json().toBean(event.data(), OrderEvent.class);
        System.out.println("收到订单事件: " + order.getOrderId());
    }
}
```

**关键差异：**
- `@StreamListener` → `@CloudEvent`
- 事件对象统一为 `Event`，通过 `event.data()` 获取载荷。
- Spring Cloud Bus 的功能由 `CloudEventService` 统一替代，无需独立组件。

### 3.4 完整消息配置

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

**Solon Cloud Event (app.yml)：**
```yaml
solon:
  cloud:
    event:
      topic:
        - "order.topic"
      kafka:
        bootstrapServers: "127.0.0.1:9092"
        groupId: "order-group"
```

---

## 4. 陷阱与差异

| 编号 | 陷阱 | 说明 |
|---|---|---|
| 1 | **Feign → Nami** | 接口声明与客户端注解体系不同；兼容包与原生 Nami 二选一策略要明确。 |
| 2 | **服务名 / 发现** | 依赖注册中心插件（如 Nacos）与 `CloudClient`，不是 Eureka + Ribbon 原样。 |
| 3 | **网关模型** | Spring Cloud Gateway Predicate/Filter 链不能逐条 1:1 粘贴；按 Solon `CloudGateway` 能力重写路由。 |
| 4 | **事件载荷** | `@CloudEvent` 侧统一 `Event`，自行反序列化业务对象。 |
| 5 | **序列化** | RPC/HTTP 通道需配套 `solon-serialization-json` 等，勿假设 Boot 默认 Jackson 自动齐套。 |
| 6 | **可观测性另文** | 断路器 / 链路 / 分布式任务见 `cloud_observability_migration.md`。 |

## 5. 迁移检查清单

- [ ] 移除 `spring-cloud-starter-openfeign` / Gateway / Stream 等对应 starter
- [ ] Feign 客户端改为 Nami（或官方兼容方案），接口与配置对齐
- [ ] 网关路由在 Solon Cloud Gateway 中重写并做联调
- [ ] `@StreamListener` / Bus → `@CloudEvent` + 通道配置
- [ ] 注册发现与配置中心与 `cloud_discovery_config_migration.md` 一致
- [ ] 需要熔断、链路、分布式 Job 时继续 `cloud_observability_migration.md`
- [ ] 端到端验证：服务发现 → RPC → 网关 → 事件至少一条主路径
