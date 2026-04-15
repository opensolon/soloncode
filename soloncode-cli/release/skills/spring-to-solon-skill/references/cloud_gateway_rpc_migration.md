# Cloud 网关与 RPC 迁移参考

> Spring Cloud → Solon Cloud 迁移指南（目标版本：Solon 3.10.x）
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

**Solon Cloud Nami：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-feign-compatible</artifactId>
</dependency>
```

> 注意：如果仅使用 Nami 原生 API，只需 `solon-serialization-json` + `nami-channel-http`。

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

**Solon Cloud Event (application.yml)：**
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
