# Cloud 注册发现与配置中心迁移参考

> Spring Cloud → Solon Cloud 迁移指南（目标版本：Solon 3.10.x）
> 参考文档：[solon.noear.org/article/compare-springcloud](https://solon.noear.org/article/compare-springcloud)

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

## 2. 注册发现迁移

### 2.1 依赖迁移

**Spring Cloud (Eureka)：**
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

**Solon Cloud (Nacos)：**
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-discovery-nacos</artifactId>
</dependency>
```

> 也可使用 `solon-cloud-discovery-consul` 或 `solon-cloud-discovery-zookeeper`，切换时只需更换依赖，代码无需修改。

### 2.2 启用注解迁移

**Spring Cloud：**
```java
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
        return CloudClient.discovery().findInstances("user-service");
    }
}
```

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

## 4. 常见陷阱与注意事项（注册/配置相关）

### 4.1 配置键命名差异

| 维度 | Spring Cloud | Solon Cloud |
|---|---|---|
| 配置前缀 | `spring.cloud.*` | `solon.cloud.*` |
| 键名风格 | 短横线 (`server-addr`) | 驼峰 (`serverAddr`) |
| 应用名称 | `spring.application.name` | `solon.app.name` |
| 配置文件 | `bootstrap.yml` + `application.yml` | `application.yml` (单文件) |

### 4.2 依赖冲突排查

- Solon Cloud 插件之间互不冲突，可按需组合。
- **不要**同时引入 `solon-cloud-discovery-nacos` 和 `solon-cloud-discovery-consul`，同一类型 Discovery 只能有一个实现。
- Config 和 Discovery 可以使用不同的后端（如 Config 用 Nacos，Discovery 用 Consul），但通常建议统一。

### 4.3 版本兼容性

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

### 4.4 CloudClient 使用模式

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

### 4.5 Spring Cloud Bus 替代方案

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

### 4.6 迁移检查清单（注册/配置部分）

- [ ] 替换所有 `spring-cloud-*` 依赖为 `solon-cloud-*` 对应插件
- [ ] 将 `@EnableDiscoveryClient` 等注解删除（Solon 自动生效）
- [ ] 将 `spring.cloud.*` 配置键改为 `solon.cloud.*` 格式（注意驼峰命名）
- [ ] 将 `spring.application.name` 改为 `solon.app.name`
- [ ] 将 `@Value` 改为 `@Inject`
- [ ] 删除 `bootstrap.yml`，合并到 `application.yml`
- [ ] 验证 Discovery 和 Config 插件配置正确，启动无报错
