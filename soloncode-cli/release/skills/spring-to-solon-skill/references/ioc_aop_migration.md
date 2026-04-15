# IoC / AOP / 组件 迁移参考

> Spring Boot → Solon 核心概念迁移指南（目标版本：Solon 3.10.x）
>
> 本文档聚焦 IoC 容器、AOP 机制与组件体系的迁移细节，提供完整的代码对照及差异陷阱标注。

## 目录

- [1. 启动类迁移](#1-启动类迁移)
- [2. IoC / 依赖注入迁移](#2-ioc--依赖注入迁移)
- [3. 组件注册与作用域](#3-组件注册与作用域)
- [4. 生命周期迁移](#4-生命周期迁移)
- [7. AOP 迁移](#7-aop-迁移)
- [8. 事件机制迁移](#8-事件机制迁移)
- [9. 包扫描迁移](#9-包扫描迁移)
- [10. 核心陷阱与差异清单（IoC/AOP 相关）](#10-核心陷阱与差异清单ioc-aop-相关)

## 1. 启动类迁移

### Before — Spring Boot

```java
package com.example.demo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication 包含：@SpringBootConfiguration + @EnableAutoConfiguration + @ComponentScan
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### After — Solon

```java
package com.example.demo;
import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain  // 标记启动类，自动扫描同包及子包下的组件
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
        // 带启动前定制：Solon.start(App.class, args, app -> { ... });
    }
}
```

> **差异说明**：Spring 的 `@SpringBootApplication` 包含自动配置能力；Solon 采用"插件即配置"模式，引入 Maven 依赖自动激活。启动类名可自定义，习惯用 `App`。

## 2. IoC / 依赖注入迁移

### 2.1 按类型注入

```java
// Before — Spring
@Service
public class OrderService {
    @Autowired                    // 按类型注入
    private UserService userService;
    @Autowired(required = false)  // 可选注入
    private PaymentService paymentService;
}

// After — Solon
@Component
public class OrderService {
    @Inject                       // 按类型注入
    private UserService userService;
    @Inject(required = false)     // 可选注入
    private PaymentService paymentService;
}
```

> **陷阱**：Solon **不支持 setter 方法注入**。仅支持：1）字段注入（`@Inject` 标注字段）；2）构造器参数注入；3）`@Bean` 方法参数注入。原项目中的 setter 注入必须改写。

### 2.2 按名称注入

```java
// Before — Spring
@Service
public class OrderService {
    @Qualifier("orderDataSource")
    @Autowired
    private DataSource dataSource;
}

// After — Solon
@Component
public class OrderService {
    @Inject("orderDataSource")     // 直接在 @Inject 中指定名称
    private DataSource dataSource;
}
```

> **陷阱**：Spring 默认以类名首字母小写注册 Bean name；**Solon 不会自动以类名注册**，必须在 `@Component("name")` 或 `@Bean("name")` 上显式声明。迁移后按名称注入失败时，首先检查 Bean 定义处是否声明了 name。

### 2.3 配置值注入

```java
// Before — Spring
@Service
public class UserService {
    @Value("${app.name}")              // 无默认值
    private String appName;
    @Value("${app.timeout:3000}")      // 带默认值
    private int timeout;
}

// After — Solon
@Component
public class UserService {
    @Inject("${app.name}")             // 无默认值
    private String appName;
    @Inject("${app.timeout:3000}")     // 带默认值
    private int timeout;
}
```

> **差异说明**：Solon 用同一个 `@Inject` 注解，通过 `${}` 前缀区分"配置值注入"和"Bean 注入"。默认值语法一致：`${key:defaultValue}`。也支持编程式：`Solon.cfg().get("app.name")`。

### 2.4 配置属性集绑定

```java
// Before — Spring
@Component
@ConfigurationProperties(prefix = "datasource.primary")
public class DataSourceProperties {
    private String url;
    private String username;
    private int maxPoolSize = 10;
    // 必须有 getter/setter
}

// After — Solon（方式一：@Inject + @Configuration）
@Inject("${datasource.primary}")
@Configuration
public class DataSourceProperties {
    public String url;       // 字段须为 public，无需 getter/setter
    public String username;
    public int maxPoolSize = 10;
}

// After — Solon（方式二：@BindProps 绑定到组件字段）
@Component
public class SomeService {
    @BindProps(prefix = "datasource.primary")
    private DataSourceProperties props;
}
```

> **差异说明**：Spring 的 `@ConfigurationProperties` 要求 getter/setter；Solon 的 `@Inject("${prefix}")` + `@Configuration` 要求字段为 **public**。

### 2.5 构造器注入

```java
// Before — Spring（单构造器可省略 @Autowired）
@Service
public class OrderService {
    private final UserService userService;
    public OrderService(UserService userService) {
        this.userService = userService;
    }
}

// After — Solon（构造器参数必须标注 @Inject）
@Component
public class OrderService {
    private final UserService userService;
    public OrderService(@Inject UserService userService) {
        this.userService = userService;
    }
}
```

> **陷阱**：Spring 单构造器可省略 `@Autowired`；**Solon 构造器参数必须显式标注 `@Inject`**，否则不会被自动注入。

## 3. 组件注册与作用域

### 3.1 组件注解统一

```java
// Before — Spring                // After — Solon
@Service      →  @Component       // 业务逻辑层
@Repository   →  @Component       // 数据访问层
@Controller   →  @Controller      // Web 控制器（Solon 默认返回 JSON）
@RestController → @Controller     // REST 控制器（同样是 @Controller）
@Component    →  @Component       // 通用组件
```

> **差异说明**：Solon **没有** `@Service`、`@Repository`、`@Dao`、`@RestController` 等细分注解。非 Web 组件统一 `@Component`，Web 控制器统一 `@Controller`（默认返回 JSON，无需 `@ResponseBody`）。

### 3.2 作用域控制

```java
// Before — Spring               // After — Solon
@Scope("singleton")  →  @Singleton              // 单例（默认值，可省略）
@Scope("prototype")  →  @Singleton(false)       // 多例
@Scope("request")    →  无直接等价物              // 需 Context 手动管理
@Scope("session")    →  无直接等价物              // 需 SessionState 手动管理
```

> **陷阱**：Solon **没有** `@Scope("request")` 和 `@Scope("session")` 的直接等价物，需通过 `Context`（请求级）或 `SessionState`（会话级）手动实现。

### 3.3 条件装配

```java
// Before — Spring
@Configuration
public class MyConfig {
    @Bean
    @ConditionalOnClass(name = "redis.clients.jedis.Jedis")
    @ConditionalOnProperty(name = "feature.cache.enabled", havingValue = "true")
    public CacheService cacheService() { return new CacheService(); }
}

// After — Solon（统一用 @Condition，支持多条件同时声明）
@Configuration
public class MyConfig {
    @Bean
    @Condition(onClass = "redis.clients.jedis.Jedis", onProperty = "feature.cache.enabled=true")
    public CacheService cacheService() { return new CacheService(); }
}
```

> **差异说明**：Spring 四种条件注解在 Solon 中统一用 `@Condition`。`onProperty` 格式为 `"key=value"`（不同于 Spring 的 `name` + `havingValue` 分开声明）。四种参数对照：`onClass`、`onProperty`、`onExpr`、`onBean`。

## 4. 生命周期迁移

### 4.1 初始化与销毁

```java
// Before — Spring
@Service
public class UserService implements InitializingBean, DisposableBean {
    @PostConstruct
    public void init() { /* 依赖注入完成后执行 */ }
    @PreDestroy
    public void cleanup() { /* 容器关闭前执行 */ }
}

// After — Solon（方式一：@Init + @Destroy 注解）
@Component
public class UserService {
    @Init
    public void init() { /* 等价于 @PostConstruct */ }
    @Destroy
    public void cleanup() { /* 等价于 @PreDestroy */ }
}

// After — Solon（方式二：LifecycleBean 接口，推荐）
@Component
public class UserService extends LifecycleBean {
    @Override
    protected void start() throws Throwable { /* 等价于 @PostConstruct + InitializingBean */ }
    @Override
    protected void stop() throws Throwable { /* 等价于 @PreDestroy + DisposableBean */ }
}
```

> **差异说明**：`@Init` ≈ `@PostConstruct`，`@Destroy` ≈ `@PreDestroy`。`LifecycleBean` 是推荐统一方案，支持优先级排序控制启动/停止顺序。

### 4.2 应用启动完成后执行

```java
// Before — Spring
@Component
public class StartupRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) { /* 应用启动完成 */ }
}

// After — Solon（监听 AppLoadEndEvent 事件）
@Component
public class StartupRunner {
    public StartupRunner() {
        Solon.app().onEvent(AppLoadEndEvent.class, e -> { /* 应用加载完成 */ });
    }
}
```

> **差异说明**：Solon 没有 `ApplicationRunner` 和 `CommandLineRunner`，统一通过 `AppLoadEndEvent` 事件实现，触发时机等价。

## 7. AOP 迁移

### 7.1 代理机制差异

| 特性 | Spring | Solon |
|---|---|---|
| 代理范围 | public 和 protected 方法 | **仅 public 方法** |
| 代理策略 | 默认对所有组件创建代理 | **按需代理**（仅存在拦截器时才代理） |
| 代理方式 | CGLIB/JDK 动态代理 | MethodWrap 方法包装（开销极低） |
| 内部方法调用 | `@Transactional` 同 Bean 内部调用失效 | 同样失效 |

> **陷阱**：原项目中有 **protected 方法** 上的 AOP 注解（如 `@Transactional`），迁移后不会生效，需改为 public。

### 7.2 环绕通知迁移（含自定义拦截器注解）

Spring 使用 AspectJ `execution()` 表达式；**Solon 不支持 execution 表达式**，需通过自定义注解 + 拦截器实现。

```java
// Before — Spring
@Aspect
@Component
public class LoggingAspect {
    @Around("execution(* com.example.service.*.*(..))")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        System.out.println("方法调用前: " + methodName);
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            System.out.println("方法异常: " + e.getMessage());
            throw e;
        }
    }
}

// After — Solon（自定义注解 + MethodInterceptor）
// 1. 定义标记注解（可通过 @Around 绑定拦截器类）
@Around(LoggingInterceptor.class)
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Loggable {}

// 2. 定义拦截器
@Component
public class LoggingInterceptor implements MethodInterceptor {
    @Override
    public Object intercept(Invocation inv) throws Throwable {
        String methodName = inv.method().getName();
        System.out.println("方法调用前: " + methodName);
        try {
            return inv.invoke();      // 对应 joinPoint.proceed()
        } catch (Throwable e) {
            System.out.println("方法异常: " + e.getMessage());
            throw e;
        }
    }
}

// 3. 注册拦截器（通常在 Plugin 或启动类中）
Solon.context().beanInterceptorAdd(Loggable.class, new LoggingInterceptor());

// 4. 使用
@Component
public class OrderService {
    @Loggable
    public Order createOrder(OrderRequest request) { /* 业务逻辑 */ }
}
```

> **关键差异**：
> - `ProceedingJoinPoint.proceed()` → `Invocation.invoke()`
> - Spring 在 `@Aspect` 类中集中定义通知；Solon 通过 `beanInterceptorAdd` 分散注册
> - 前置/后置/异常通知无独立注解，统一在 `MethodInterceptor.intercept()` 中用 try-catch-finally 实现

### 7.3 带参数的自定义拦截器

```java
// Before — Spring
@Aspect
@Component
public class RateLimitAspect {
    @Around("@annotation(rateLimit)")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (isOverLimit(rateLimit.value())) throw new RuntimeException("请求过于频繁");
        return joinPoint.proceed();
    }
}

// After — Solon（@Around 绑定拦截器类到注解上）
@Around(RateLimitInterceptor.class)
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int value() default 100;
}

@Component
public class RateLimitInterceptor implements MethodInterceptor {
    @Override
    public Object intercept(Invocation inv) throws Throwable {
        RateLimit anno = inv.method().getAnnotation(RateLimit.class);
        if (isOverLimit(anno.value())) throw new RuntimeException("请求过于频繁");
        return inv.invoke();
    }
}
```

## 8. 事件机制迁移

### 8.1 概念对照

| 概念 | Spring | Solon |
|---|---|---|
| 事件基类 | `ApplicationEvent` | 无需继承（任意 POJO） |
| 事件发布 | `ApplicationEventPublisher.publishEvent()` | `EventBus.publish()` |
| 事件监听 | `@EventListener` 注解 | `EventListener<T>` 接口或 `EventBus.subscribe()` |
| 异步事件 | `@Async` + `@EventListener` | `EventBus.publishAsync()` |

### 8.2 完整对照

```java
// Before — Spring
public class OrderCreatedEvent extends ApplicationEvent {
    private final Long orderId;
    public OrderCreatedEvent(Object source, Long orderId) {
        super(source); this.orderId = orderId;
    }
    public Long getOrderId() { return orderId; }
}

@Service
public class OrderService {
    @Autowired private ApplicationEventPublisher eventPublisher;
    public void createOrder(Order order) {
        eventPublisher.publishEvent(new OrderCreatedEvent(this, order.getId()));
    }
}

@Component
public class EmailNotifier {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) { /* 处理事件 */ }
}

// After — Solon
public class OrderCreatedEvent {        // 普通 POJO，无需继承
    private final Long orderId;
    public OrderCreatedEvent(Long orderId) { this.orderId = orderId; }
    public Long getOrderId() { return orderId; }
}

@Component
public class OrderService {
    public void createOrder(Order order) {
        EventBus.publish(new OrderCreatedEvent(order.getId()));
    }
}

@Component
public class EmailNotifier implements EventListener<OrderCreatedEvent> {
    @Override
    public void onEvent(OrderCreatedEvent event) throws Throwable { /* 处理事件 */ }
}
```

> **差异说明**：Spring 事件必须继承 `ApplicationEvent`；Solon 事件是任意 POJO。监听方式从 `@EventListener` 注解改为 `EventListener<T>` 接口实现或 `EventBus.subscribe()`。

## 9. 包扫描迁移

```java
// Before — Spring
@SpringBootApplication
@ComponentScan(basePackages = "com.example")
@Import({DataSourceConfig.class, RedisConfig.class})
public class Application { }

// After — Solon（@Import 统一处理包扫描和类导入）
@SolonMain
@Import(scanPackages = "com.example")                  // 替代 @ComponentScan
@Import({DataSourceConfig.class, RedisConfig.class})   // 替代 Spring @Import
public class App {
    public static void main(String[] args) { Solon.start(App.class, args); }
}
```

> **差异说明**：Solon 的 `@Import` 同时替代 `@ComponentScan` 和 `@Import`。仅在启动类或 `@Configuration` 类上有效。

## 10. 核心陷阱与差异清单（IoC/AOP 相关）

### 陷阱速查表

| 编号 | 陷阱描述 | 严重程度 | 详细说明 |
|---|---|---|---|
| 1 | **不支持 setter 注入** | 高 | Solon 仅支持字段注入、构造器参数注入、`@Bean` 方法参数注入。 |
| 2 | **Bean 默认不按名字注册** | 高 | Spring 自动以类名注册；Solon 需在 `@Component("name")` 或 `@Bean("name")` 上显式声明。 |
| 3 | **构造器参数必须标注 @Inject** | 中 | Spring 单构造器可省略 `@Autowired`；Solon 必须显式标注。 |
| 4 | **AOP 只代理 public 方法** | 高 | Solon 只代理 public 方法。protected 方法上的 `@Transaction` 等注解将失效。 |
| 5 | **AOP 不支持 execution 表达式** | 高 | 需改用自定义注解 + `beanInterceptorAdd`。 |
| 6 | **没有 @Scope("request")/@Scope("session")** | 中 | 需通过 `Context` 或 `SessionState` 手动管理。 |
| 7 | **@Controller 默认返回 JSON** | 低 | 不需要 `@ResponseBody`，也不存在 `@RestController`。 |
| 8 | **事件无需继承基类** | 低 | Solon 事件是普通 POJO。 |

### 迁移检查清单

- [ ] 启动类：`@SpringBootApplication` → `@SolonMain`，`SpringApplication.run()` → `Solon.start()`
- [ ] IoC 注入：`@Autowired` → `@Inject`，`@Qualifier` → `@Inject("name")`
- [ ] setter 注入：所有 setter 注入改为字段注入或构造器注入
- [ ] 构造器注入：确认所有构造器参数已添加 `@Inject`
- [ ] 组件注解：`@Service`/`@Repository`/`@Dao` → `@Component`
- [ ] Bean 名称：按名称注入的 Bean 需在定义处显式声明 name
- [ ] 配置值注入：`@Value("${x}")` → `@Inject("${x}")`
- [ ] 配置属性集：`@ConfigurationProperties` → `@Inject("${prefix}")` + `@Configuration` 或 `@BindProps`
- [ ] 生命周期：`@PostConstruct` → `@Init`，`@PreDestroy` → `@Destroy`，或改用 `LifecycleBean`
- [ ] 条件装配：`@ConditionalOnXxx` → `@Condition(onXxx=...)`
- [ ] 包扫描：`@ComponentScan` → `@Import(scanPackages=...)`
- [ ] AOP 切面：AspectJ execution 表达式 → 自定义注解 + `beanInterceptorAdd`
- [ ] AOP 方法可见性：确认所有需要拦截的方法都是 public
- [ ] 事件机制：`ApplicationEvent` → 普通 POJO，`@EventListener` → `EventListener` 接口
- [ ] 启动回调：`ApplicationRunner`/`CommandLineRunner` → `AppLoadEndEvent`
