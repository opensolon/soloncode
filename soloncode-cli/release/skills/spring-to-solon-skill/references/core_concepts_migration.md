# IoC / AOP / 配置 迁移参考手册

> Spring Boot → Solon 核心概念迁移指南（目标版本：Solon 3.10.x）
>
> 本文档聚焦 IoC 容器、AOP 机制与配置体系三大核心模块的迁移细节，提供完整的代码对照、配置对照及差异陷阱标注。

---

## 目录

- [1. 启动类迁移](#1-启动类迁移)
- [2. IoC / 依赖注入迁移](#2-ioc--依赖注入迁移)
  - [2.1 按类型注入](#21-按类型注入)
  - [2.2 按名称注入](#22-按名称注入)
  - [2.3 配置值注入](#23-配置值注入)
  - [2.4 配置属性集绑定](#24-配置属性集绑定)
  - [2.5 构造器注入](#25-构造器注入)
- [3. 组件注册与作用域](#3-组件注册与作用域)
  - [3.1 组件注解统一](#31-组件注解统一)
  - [3.2 作用域控制](#32-作用域控制)
  - [3.3 条件装配](#33-条件装配)
- [4. 生命周期迁移](#4-生命周期迁移)
  - [4.1 初始化与销毁](#41-初始化与销毁)
  - [4.2 应用启动完成后执行](#42-应用启动完成后执行)
- [5. 配置文件迁移](#5-配置文件迁移)
  - [5.1 文件命名与加载机制](#51-文件命名与加载机制)
  - [5.2 环境切换](#52-环境切换)
  - [5.3 配置文件完整对照](#53-配置文件完整对照)
  - [5.4 属性源导入](#54-属性源导入)
  - [5.5 变量引用与多片段](#55-变量引用与多片段)
  - [5.6 编程式读取配置](#56-编程式读取配置)
- [6. 配置类与 Bean 定义迁移](#6-配置类与-bean-定义迁移)
  - [6.1 基本配置类](#61-基本配置类)
  - [6.2 带 Condition 的 Bean 定义](#62-带-condition-的-bean-定义)
  - [6.3 多数据源场景](#63-多数据源场景)
- [7. AOP 迁移](#7-aop-迁移)
  - [7.1 代理机制差异](#71-代理机制差异)
  - [7.2 环绕通知迁移](#72-环绕通知迁移)
  - [7.3 前置/后置/异常通知迁移](#73-前置后置异常通知迁移)
  - [7.4 自定义拦截器注解](#74-自定义拦截器注解)
- [8. 事件机制迁移](#8-事件机制迁移)
  - [8.1 ApplicationEvent → EventBus](#81-applicationevent--eventbus)
  - [8.2 事件发布与监听完整对照](#82-事件发布与监听完整对照)
- [9. 包扫描迁移](#9-包扫描迁移)
- [10. 核心陷阱与差异清单](#10-核心陷阱与差异清单)

---

## 1. 启动类迁移

### Before — Spring Boot

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication 是一个复合注解，包含：
// @SpringBootConfiguration + @EnableAutoConfiguration + @ComponentScan
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

// @SolonMain 标记启动类，Solon 会自动扫描同包及子包下的组件
@SolonMain
public class App {
    public static void main(String[] args) {
        // 方式一：最简启动
        Solon.start(App.class, args);

        // 方式二：带启动前定制（等效于 Spring 的 ApplicationBuilder 定制）
        // Solon.start(App.class, args, app -> {
        //     app.enableEncoding(true);       // 启用编码过滤
        //     app.converterAdd(...);          // 添加类型转换器
        // });
    }
}
```

> **差异说明**：
> - Spring 的 `@SpringBootApplication` 包含自动配置（Auto-Configuration）能力；Solon 采用"插件即配置"模式，引入的 Maven 依赖自动激活。
> - Spring Boot 启动类默认扫描同包及子包（`@ComponentScan`）；Solon 同样默认扫描启动类同包及子包。
> - Solon 启动类名可自定义，习惯上使用 `App`，但不是强制要求。

---

## 2. IoC / 依赖注入迁移

### 2.1 按类型注入

#### Before — Spring

```java
@Service
public class OrderService {

    @Autowired                    // 按类型注入（字段注入）
    private UserService userService;

    @Autowired(required = false)  // 可选注入（允许容器中不存在该 Bean）
    private PaymentService paymentService;
}
```

#### After — Solon

```java
@Component
public class OrderService {

    @Inject                       // 按类型注入（字段注入）
    private UserService userService;

    @Inject(required = false)     // 可选注入（允许容器中不存在该 Bean）
    private PaymentService paymentService;
}
```

> **陷阱**：Solon **不支持 setter 方法注入**。Spring 支持字段注入、构造器注入和 setter 注入三种方式，Solon 仅支持：
> 1. 字段注入（`@Inject` 标注在字段上）
> 2. 构造器参数注入（`@Inject` 标注在构造器参数上）
> 3. `@Bean` 方法参数注入（`@Inject` 标注在 `@Bean` 方法参数上）
>
> 如果原项目中有大量 setter 注入，迁移时需改为字段注入或构造器注入。

---

### 2.2 按名称注入

#### Before — Spring

```java
@Service
public class OrderService {

    @Qualifier("orderDataSource")  // 通过 @Qualifier 指定 Bean 名称
    @Autowired
    private DataSource dataSource;
}
```

#### After — Solon

```java
@Component
public class OrderService {

    @Inject("orderDataSource")     // 直接在 @Inject 中指定名称
    private DataSource dataSource;
}
```

> **陷阱**：Spring 默认以类名首字母小写（如 `orderDataSource`）作为 Bean 名称注册；**Solon 不会自动以类名注册**，必须在 `@Component` 或 `@Bean` 上显式声明 name：
>
> ```java
> // Spring: 自动以 "orderDataSource" 注册
> @Service
> public class OrderDataSource implements DataSource { ... }
>
> // Solon: 需要显式指定 name
> @Component("orderDataSource")
> public class OrderDataSource implements DataSource { ... }
> ```
>
> 如果迁移后按名称注入失败，首先检查 Bean 定义处是否声明了 name。

---

### 2.3 配置值注入

#### Before — Spring

```java
@Service
public class UserService {

    @Value("${app.name}")              // 注入配置值，无默认值
    private String appName;

    @Value("${app.timeout:3000}")      // 注入配置值，带默认值 3000
    private int timeout;

    @Value("${app.features:feature1,feature2}")  // 注入列表值
    private String[] features;
}
```

#### After — Solon

```java
@Component
public class UserService {

    @Inject("${app.name}")             // 注入配置值，无默认值
    private String appName;

    @Inject("${app.timeout:3000}")     // 注入配置值，带默认值 3000
    private int timeout;

    @Inject("${app.features:feature1,feature2}") // 注入列表值
    private String[] features;
}
```

> **差异说明**：
> - Solon 用同一个 `@Inject` 注解，通过 `${}` 前缀区分"配置值注入"和"Bean 注入"。
> - 默认值语法完全一致：`${key:defaultValue}`。
> - 也支持编程式获取：`Solon.cfg().get("app.name")`、`Solon.cfg().getInt("app.timeout", 3000)`。

---

### 2.4 配置属性集绑定

#### Before — Spring

```java
// 方式：@ConfigurationProperties + @Component
@Component
@ConfigurationProperties(prefix = "datasource.primary")
public class DataSourceProperties {
    private String url;
    private String username;
    private String password;
    private int maxPoolSize = 10;
    // 必须有 getter/setter
    // getter/setter ...
}
```

#### After — Solon

```java
// 方式一：@Inject("${prefix}") + @Configuration（配置类风格，字段为 public，无需 getter/setter）
@Inject("${datasource.primary}")
@Configuration
public class DataSourceProperties {
    public String url;
    public String username;
    public String password;
    public int maxPoolSize = 10;
}

// 方式二：@BindProps(prefix="name")（绑定到组件字段）
@Component
public class SomeService {

    @BindProps(prefix = "datasource.primary")
    private DataSourceProperties props;
}
```

> **差异说明**：
> - Spring 的 `@ConfigurationProperties` 要求类有 getter/setter；Solon 的 `@Inject("${prefix}")` + `@Configuration` 方式要求字段为 **public**，不需要 getter/setter。
> - `@BindProps` 适用于将配置属性集绑定到已有组件的某个字段上，更灵活。

---

### 2.5 构造器注入

#### Before — Spring

```java
@Service
public class OrderService {
    private final UserService userService;
    private final OrderRepository orderRepository;

    // Spring 4.3+ 可省略 @Autowired（单构造器时自动注入）
    public OrderService(UserService userService,
                        OrderRepository orderRepository) {
        this.userService = userService;
        this.orderRepository = orderRepository;
    }
}
```

#### After — Solon

```java
@Component
public class OrderService {
    private final UserService userService;
    private final OrderRepository orderRepository;

    // Solon 构造器参数需使用 @Inject 注入
    public OrderService(@Inject UserService userService,
                        @Inject OrderRepository orderRepository) {
        this.userService = userService;
        this.orderRepository = orderRepository;
    }
}
```

> **陷阱**：Spring 在单构造器情况下可省略 `@Autowired`；**Solon 的构造器参数必须显式标注 `@Inject`**，否则参数不会被自动注入。

---

## 3. 组件注册与作用域

### 3.1 组件注解统一

#### Before — Spring

```java
@Service          // 业务逻辑层
public class UserService { }

@Repository       // 数据访问层
public class UserDao { }

@Controller       // Web 控制器（返回视图）
public class PageController { }

@RestController   // REST 控制器（返回 JSON）
public class ApiController { }

@Component        // 通用组件
public class Utils { }
```

#### After — Solon

```java
@Component        // 业务逻辑层（统一使用 @Component）
public class UserService { }

@Component        // 数据访问层（统一使用 @Component）
public class UserDao { }

@Controller       // Web 控制器（Solon 默认返回 JSON，无需 @ResponseBody）
public class PageController { }

@Controller       // REST 控制器（同样是 @Controller，默认即 JSON）
public class ApiController { }

@Component        // 通用组件
public class Utils { }
```

> **差异说明**：
> - Solon **没有** `@Service`、`@Repository`、`@Dao`、`@RestController` 等细分注解。
> - 所有非 Web 组件统一使用 `@Component`。
> - Web 控制器统一使用 `@Controller`，默认返回 JSON（无需 `@ResponseBody`）。

---

### 3.2 作用域控制

#### Before — Spring

```java
@Service
@Scope("singleton")    // 单例（Spring 默认值，可省略）
public class ConfigService { }

@Service
@Scope("prototype")    // 每次获取都创建新实例
public class RequestContext { }

@Service
@Scope("request")      // 每个 HTTP 请求一个实例
public class RequestBean { }

@Service
@Scope("session")      // 每个 HTTP 会话一个实例
public class SessionBean { }
```

#### After — Solon

```java
@Component
@Singleton             // 单例（Solon 默认值，可省略）
public class ConfigService { }

@Component
@Singleton(false)      // 多例（每次获取都创建新实例）
public class RequestContext { }

// 注意：Solon 没有 @Scope("request") 和 @Scope("session") 的直接等价物
// 需要通过 Context 或 SessionState 手动管理生命周期
```

> **陷阱**：
> - Solon **没有** `@Scope("request")` 和 `@Scope("session")` 的直接等价物。
> - 如需请求/会话级别的实例管理，需要通过 `Context`（请求级）或 `SessionState`（会话级）手动实现。
> - `@Singleton(false)` 是 `@Scope("prototype")` 的替代，但语义名称不同，注意不要遗漏。

---

### 3.3 条件装配

#### Before — Spring

```java
@Configuration
public class MyConfig {

    @Bean
    @ConditionalOnClass(name = "redis.clients.jedis.Jedis")
    public RedisService redisService() {
        return new RedisService();
    }

    @Bean
    @ConditionalOnProperty(name = "feature.cache.enabled", havingValue = "true")
    public CacheService cacheService() {
        return new CacheService();
    }

    @Bean
    @ConditionalOnExpression("${feature.redis.enabled:false}")
    public RedissonService redissonService() {
        return new RedissonService();
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public TransactionService transactionService() {
        return new TransactionService();
    }
}
```

#### After — Solon

```java
@Configuration
public class MyConfig {

    // @Condition(onClass=...) 等价于 @ConditionalOnClass
    @Bean
    @Condition(onClass = "redis.clients.jedis.Jedis")
    public RedisService redisService() {
        return new RedisService();
    }

    // @Condition(onProperty=...) 等价于 @ConditionalOnProperty
    @Bean
    @Condition(onProperty = "feature.cache.enabled=true")
    public CacheService cacheService() {
        return new CacheService();
    }

    // @Condition(onExpr=...) 等价于 @ConditionalOnExpression
    @Bean
    @Condition(onExpr = "${feature.redis.enabled:false}")
    public RedissonService redissonService() {
        return new RedissonService();
    }

    // @Condition(onBean=...) 等价于 @ConditionalOnBean
    @Bean
    @Condition(onBean = DataSource.class)
    public TransactionService transactionService() {
        return new TransactionService();
    }
}
```

> **差异说明**：
> - Spring 的四种条件注解（`@ConditionalOnClass`、`@ConditionalOnProperty`、`@ConditionalOnExpression`、`@ConditionalOnBean`）在 Solon 中统一用 `@Condition` 注解，通过不同参数区分。
> - `onProperty` 的值格式为 `"key=value"`，不同于 Spring 的 `name` + `havingValue` 分开声明。
> - 可同时指定多个条件：`@Condition(onClass = "...", onProperty = "...")`。

---

## 4. 生命周期迁移

### 4.1 初始化与销毁

#### Before — Spring

```java
@Service
public class UserService implements InitializingBean, DisposableBean {

    @PostConstruct
    public void init() {
        // @PostConstruct：依赖注入完成后执行
        System.out.println("UserService 初始化（@PostConstruct）");
    }

    @PreDestroy
    public void cleanup() {
        // @PreDestroy：容器关闭前执行
        System.out.println("UserService 销毁（@PreDestroy）");
    }

    @Override
    public void afterPropertiesSet() {
        // InitializingBean 接口方法
        System.out.println("UserService afterPropertiesSet");
    }

    @Override
    public void destroy() {
        // DisposableBean 接口方法
        System.out.println("UserService destroy");
    }
}
```

#### After — Solon（方式一：@Init + @Destroy 注解）

```java
@Component
public class UserService {

    @Init
    public void init() {
        // @Init：依赖注入完成后执行（等价于 @PostConstruct）
        System.out.println("UserService 初始化（@Init）");
    }

    @Destroy
    public void cleanup() {
        // @Destroy：容器关闭前执行（等价于 @PreDestroy）
        System.out.println("UserService 销毁（@Destroy）");
    }
}
```

#### After — Solon（方式二：LifecycleBean 接口，推荐）

```java
@Component
public class UserService extends LifecycleBean {

    @Override
    protected void start() throws Throwable {
        // 等价于 @PostConstruct + InitializingBean
        // 支持优先级顺序控制（多个组件间可排序）
        System.out.println("UserService start（LifecycleBean）");
    }

    @Override
    protected void stop() throws Throwable {
        // 等价于 @PreDestroy + DisposableBean
        System.out.println("UserService stop（LifecycleBean）");
    }
}
```

> **差异说明**：
> - `@Init` 等价于 Spring 的 `@PostConstruct`。
> - `@Destroy` 等价于 Spring 的 `@PreDestroy`。
> - `LifecycleBean` 是推荐的统一方案，替代 Spring 的 `InitializingBean` + `DisposableBean` 组合。
> - `LifecycleBean` 支持优先级排序，可控制多个组件的启动/停止顺序。

---

### 4.2 应用启动完成后执行

#### Before — Spring

```java
// 方式一：ApplicationRunner（推荐，支持获取应用参数）
@Component
public class StartupRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        System.out.println("应用启动完成（ApplicationRunner）");
        System.out.println("参数: " + args.getOptionNames());
    }
}

// 方式二：CommandLineRunner
@Component
public class StartupRunner2 implements CommandLineRunner {
    @Override
    public void run(String... args) {
        System.out.println("应用启动完成（CommandLineRunner）");
    }
}
```

#### After — Solon

```java
// 监听 AppLoadEndEvent 事件（统一替代 ApplicationRunner 和 CommandLineRunner）
@Component
public class StartupRunner {

    public StartupRunner() {
        // 方式一：构造器中监听事件
        Solon.app().onEvent(AppLoadEndEvent.class, e -> {
            System.out.println("应用加载完成（AppLoadEndEvent）");
        });
    }

    // 方式二：通过 @Init 方法监听（等价）
    @Init
    public void init() {
        Solon.context().onEvent(AppLoadEndEvent.class, e -> {
            System.out.println("应用加载完成（@Init 中监听）");
        });
    }
}
```

> **差异说明**：
> - Solon 没有 `ApplicationRunner` 和 `CommandLineRunner` 接口，统一通过 `AppLoadEndEvent` 事件实现。
> - `AppLoadEndEvent` 在所有 Bean 初始化完成后触发，与 `ApplicationRunner.run()` 的触发时机等价。

---

## 5. 配置文件迁移

### 5.1 文件命名与加载机制

| 项目 | Spring Boot | Solon |
|---|---|---|
| 默认配置文件 | `application.yml` / `application.properties` | `app.yml` / `app.properties` |
| 环境配置文件 | `application-{profile}.yml` | `app-{env}.yml` |
| 环境切换配置 | `spring.profiles.active=dev` | `solon.env=dev` |
| 文件位置 | `src/main/resources/` | `src/main/resources/`（相同） |
| 支持格式 | `.yml`、`.properties` | `.yml`、`.properties`（相同） |

---

### 5.2 环境切换

#### Before — Spring（application.yml）

```yaml
spring:
  profiles:
    active: dev    # 激活 dev 环境
```

#### After — Solon（app.yml）

```yaml
solon:
  env: dev        # 激活 dev 环境
```

> **差异说明**：
> - 环境切换的关键字从 `spring.profiles.active` 变为 `solon.env`。
> - 环境文件命名从 `application-{profile}.yml` 变为 `app-{env}.yml`。

---

### 5.3 配置文件完整对照

#### Before — Spring（application.yml）

```yaml
# 服务配置
server:
  port: 8080
  servlet:
    context-path: /api

# Spring 激活环境
spring:
  profiles:
    active: dev
  # 数据源配置
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

  # Redis 配置
  redis:
    host: localhost
    port: 6379
    password: ""
    database: 0

# 日志级别
logging:
  level:
    root: INFO
    com.example: DEBUG

# 自定义业务配置
app:
  name: demo-app
  version: 1.0.0
  features:
    - feature-a
    - feature-b
  security:
    jwt-secret: my-secret-key
    token-expiration: 3600
```

#### Before — Spring（application-dev.yml）

```yaml
# 开发环境覆盖
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb_dev
    username: dev_user
    password: dev_pass

logging:
  level:
    root: DEBUG
```

#### After — Solon（app.yml）

```yaml
# 服务配置（Solon 的 server 配置结构不同于 Spring）
server:
  port: 8080
  contextPath: /api    # 注意：不是 servlet.context-path

# Solon 激活环境
solon:
  env: dev
  # 数据源配置（使用 Solon 的数据源约定）
  datasource:          # 默认数据源
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: 123456
    driverClassName: com.mysql.cj.jdbc.Driver
    maxPoolSize: 20
    minIdle: 5

  # Redis 配置（使用 Solon 的 Redis 约定）
  redis:
    host: localhost
    port: 6379
    password: ""
    database: 0

# 日志级别
solon.logging:
  level:
    root: INFO
    com.example: DEBUG

# 自定义业务配置（保持不变）
app:
  name: demo-app
  version: 1.0.0
  features:
    - feature-a
    - feature-b
  security:
    jwt-secret: my-secret-key
    token-expiration: 3600
```

#### After — Solon（app-dev.yml）

```yaml
# 开发环境覆盖
server:
  port: 8081

solon:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb_dev
    username: dev_user
    password: dev_pass

solon.logging:
  level:
    root: DEBUG
```

> **陷阱**：
> - `server.servlet.context-path` → `server.contextPath`（路径不同，驼峰命名）。
> - `spring.datasource.hikari.maximum-pool-size` → `solon.datasource.maxPoolSize`（层级更扁平，驼峰命名）。
> - `logging.level` → `solon.logging.level`（前缀不同）。
> - 自定义业务配置（如 `app.*`）的 key 路径不变，迁移时保持原样即可。

---

### 5.4 属性源导入

#### Before — Spring

```java
// 方式一：@PropertySource 注解
@Configuration
@PropertySource("classpath:custom.properties")
@PropertySource("classpath:db.properties")
public class CustomConfig { }

// 方式二：@PropertySources 复合注解
@Configuration
@PropertySources({
    @PropertySource("classpath:custom.properties"),
    @PropertySource("classpath:db.properties")
})
public class CustomConfig { }
```

#### After — Solon

```java
// @Import 统一导入属性源文件
@SolonMain
@Import("classpath:custom.properties")
@Import("classpath:db.properties")
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
```

> **差异说明**：
> - Solon 的 `@Import` 同时承担 `@ComponentScan`、`@Import`（Spring 的类导入）和 `@PropertySource` 三者的职责。
> - `@Import` 只在启动类或 `@Configuration` 类上有效。

---

### 5.5 变量引用与多片段

#### 变量引用

Spring 和 Solon 都支持在配置文件中使用 `${...}` 引用其他配置值：

```yaml
# app.yml（Solon）
app:
  name: demo-app
  version: 1.0.0
  description: "${app.name} v${app.version}"   # 变量引用，解析为 "demo-app v1.0.0"
```

#### YAML 多片段（solon.env.on）

Solon 支持 YAML 多片段特性，可以在同一个文件中为不同环境定义配置：

```yaml
# app.yml —— 通过 --- 分隔不同环境片段

server:
  port: 8080

app:
  name: demo-app

---
# 当 solon.env=dev 时激活此片段
solon.env.on: dev

server:
  port: 8081

app:
  debug: true

---
# 当 solon.env=prod 时激活此片段
solon.env.on: prod

server:
  port: 80

app:
  debug: false
```

> **差异说明**：Spring 需要通过独立的 `application-{profile}.yml` 文件实现环境隔离；Solon 支持两种方式——既支持独立的 `app-{env}.yml` 文件，也支持在同一文件中用 `---` + `solon.env.on` 分隔多环境片段。

---

### 5.6 编程式读取配置

#### Before — Spring

```java
@Service
public class ConfigReader {

    @Autowired
    private Environment env;

    public void read() {
        String name = env.getProperty("app.name");
        int port = env.getProperty("server.port", Integer.class, 8080);
        // 使用 @Value 更加常见
    }
}
```

#### After — Solon

```java
@Component
public class ConfigReader {

    public void read() {
        // 方式一：通过 Solon.cfg() 获取配置
        String name = Solon.cfg().get("app.name");
        int port = Solon.cfg().getInt("server.port", 8080);

        // 方式二：通过 @Inject 注入（推荐）
        // @Inject("${app.name}") private String appName;
    }
}
```

---

## 6. 配置类与 Bean 定义迁移

### 6.1 基本配置类

#### Before — Spring

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        return new HikariDataSource();
    }

    @Bean("secondaryDataSource")
    public DataSource secondaryDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://localhost:3306/secondary");
        return ds;
    }
}
```

#### After — Solon

```java
@Configuration
public class DataSourceConfig {

    // typed=true 表示该类型的默认 Bean
    // @Inject 在 @Bean 方法参数上注入配置
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${solon.datasource}") HikariDataSource ds) {
        return ds;
    }

    @Bean("secondaryDataSource")
    public DataSource secondaryDataSource(@Inject("${solon.datasource.secondary}") HikariDataSource ds) {
        return ds;
    }
}
```

> **差异说明**：
> - Solon 的 `@Bean` 方法参数通过 `@Inject` 注入配置或 Bean，Spring 则依赖自动装配。
> - `@Bean(typed=true)` 用于声明某类型的默认 Bean，在 `@Inject` 按类型注入时优先匹配此 Bean。
> - Solon 的 `@Bean` 可以返回 `void`（仅用于执行初始化逻辑，不注册任何 Bean）。

---

### 6.2 带 Condition 的 Bean 定义

#### Before — Spring

```java
@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    @ConditionalOnProperty(name = "cache.type", havingValue = "redis")
    public CacheManager redisCacheManager() {
        return new RedisCacheManager();
    }
}
```

#### After — Solon

```java
@Configuration
public class CacheConfig {

    @Bean
    @Condition(onClass = "org.redisson.api.RedissonClient", onProperty = "cache.type=redis")
    public CacheManager redisCacheManager() {
        return new RedisCacheManager();
    }
}
```

> **注意**：`@Condition` 可同时声明多个条件（`onClass` + `onProperty`），而 Spring 需要叠加多个 `@ConditionalOnXxx` 注解。

---

### 6.3 多数据源场景

#### Before — Spring

```java
@Configuration
public class MultiDataSourceConfig {

    @Bean
    @Primary                          // 标记为主数据源
    @ConfigurationProperties(prefix = "spring.datasource.primary")
    public DataSource primaryDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.secondary")
    public DataSource secondaryDataSource() {
        return new HikariDataSource();
    }
}
```

#### After — Solon

```java
@Configuration
public class MultiDataSourceConfig {

    // typed=true 等价于 @Primary，表示该类型的默认 Bean
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${solon.datasource.primary}") HikariDataSource ds) {
        return ds;
    }

    @Bean("db2")
    public DataSource db2(@Inject("${solon.datasource.secondary}") HikariDataSource ds) {
        return ds;
    }
}
```

> **差异说明**：
> - Spring 用 `@Primary` 标记默认 Bean；Solon 用 `@Bean(typed=true)` 实现相同效果。
> - 注入时：Spring 需 `@Qualifier` 区分，Solon 直接 `@Inject("db1")` 或 `@Inject("db2")`。

---

## 7. AOP 迁移

### 7.1 代理机制差异

| 特性 | Spring | Solon |
|---|---|---|
| 代理范围 | public 和 protected 方法 | **仅 public 方法** |
| 代理策略 | 默认对所有组件创建代理 | **按需代理**（仅当存在拦截器注册时才代理） |
| 代理创建时机 | 容器启动时统一创建 | 首次使用时按需创建 |
| 性能影响 | 较重（CGLIB/JDK 动态代理） | 较轻（这是 Solon 启动快的原因之一） |
| 内部方法调用 | `@Transactional` 等注解在同 Bean 内部调用时失效 | 同样失效（代理机制决定） |

> **陷阱**：
> - 如果原项目中有 **protected 方法** 上的 AOP 注解（如 `@Transactional`），迁移后不会生效。需将方法改为 public。
> - Spring 的 `@Transactional` 在同类内部调用时失效；Solon 的 `@Transaction` 同样如此，这不是迁移引入的新问题，但需要注意。

---

### 7.2 环绕通知迁移

#### Before — Spring

```java
// 定义切面
@Aspect
@Component
public class LoggingAspect {

    // @Around 环绕通知
    @Around("execution(* com.example.service.*.*(..))")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        System.out.println("方法调用前: " + methodName);
        try {
            Object result = joinPoint.proceed();  // 执行目标方法
            System.out.println("方法返回后: " + methodName);
            return result;
        } catch (Throwable e) {
            System.out.println("方法异常: " + methodName + ", 异常: " + e.getMessage());
            throw e;
        } finally {
            System.out.println("方法结束后: " + methodName);
        }
    }
}
```

#### After — Solon

```java
// Solon 使用 @Around 注解（名称相同，但使用方式不同）
// 通常结合自定义注解使用，而不是基于 execution 表达式

// 1. 定义标记注解
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Loggable {
}

// 2. 定义拦截器（实现 MethodInterceptor 或使用函数式接口）
public class LoggableInterceptor implements MethodInterceptor {
    @Override
    public Object intercept(Invocation inv) throws Throwable {
        String methodName = inv.method().getName();
        System.out.println("方法调用前: " + methodName);
        try {
            Object result = inv.invoke();  // 执行目标方法
            System.out.println("方法返回后: " + methodName);
            return result;
        } catch (Throwable e) {
            System.out.println("方法异常: " + methodName + ", 异常: " + e.getMessage());
            throw e;
        } finally {
            System.out.println("方法结束后: " + methodName);
        }
    }
}

// 3. 注册拦截器（通常在 Plugin 或启动类中）
Solon.context().beanInterceptorAdd(Loggable.class, new LoggableInterceptor());

// 4. 使用标记注解
@Component
public class OrderService {

    @Loggable   // 标记需要拦截的方法
    public Order createOrder(OrderRequest request) {
        // 业务逻辑
    }
}
```

> **关键差异**：
> - Spring 使用 AspectJ 风格的 `execution()` 表达式匹配拦截目标；**Solon 不支持 execution 表达式**，需要通过自定义注解 + `beanInterceptorAdd` 注册拦截器。
> - Spring 的 `ProceedingJoinPoint.proceed()` → Solon 的 `Invocation.invoke()`。
> - Spring 在 `@Aspect` 类中集中定义所有通知；Solon 通过 `beanInterceptorAdd` 分散注册。

---

### 7.3 前置/后置/异常通知迁移

#### Before — Spring

```java
@Aspect
@Component
public class MultiAdviceAspect {

    @Before("execution(* com.example.service.*.*(..))")
    public void before(JoinPoint jp) {
        System.out.println("前置通知: " + jp.getSignature().getName());
    }

    @AfterReturning(pointcut = "execution(* com.example.service.*.*(..))", returning = "result")
    public void afterReturning(JoinPoint jp, Object result) {
        System.out.println("返回通知: " + jp.getSignature().getName() + ", 结果: " + result);
    }

    @AfterThrowing(pointcut = "execution(* com.example.service.*.*(..))", throwing = "ex")
    public void afterThrowing(JoinPoint jp, Exception ex) {
        System.out.println("异常通知: " + jp.getSignature().getName() + ", 异常: " + ex.getMessage());
    }
}
```

#### After — Solon

```java
// Solon 统一使用 @Around 或 @Addition 处理所有通知类型
// 没有 @Before / @AfterReturning / @AfterThrowing 的独立注解

// 方式：用 MethodInterceptor 实现全部通知逻辑
public class MultiAdviceInterceptor implements MethodInterceptor {
    @Override
    public Object intercept(Invocation inv) throws Throwable {
        String methodName = inv.method().getName();

        // 前置通知（@Before）
        System.out.println("前置通知: " + methodName);

        try {
            Object result = inv.invoke();

            // 返回通知（@AfterReturning）
            System.out.println("返回通知: " + methodName + ", 结果: " + result);
            return result;
        } catch (Exception ex) {
            // 异常通知（@AfterThrowing）
            System.out.println("异常通知: " + methodName + ", 异常: " + ex.getMessage());
            throw ex;
        }
    }
}

// 或者使用 @Addition 注解（Solon 提供的另一种拦截方式）
// @Addition 用于标注额外的拦截处理
```

> **差异说明**：
> - Spring 有 `@Before`、`@After`、`@AfterReturning`、`@AfterThrowing`、`@Around` 五种通知类型。
> - Solon 只有 `@Around`（通过 `MethodInterceptor`）和 `@Addition` 两种方式，但可以在拦截器内部自由实现前置、后置、异常等逻辑。

---

### 7.4 自定义拦截器注解

#### Before — Spring

```java
// 1. 定义注解
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int value() default 100;
}

// 2. 定义切面
@Aspect
@Component
public class RateLimitAspect {

    @Around("@annotation(rateLimit)")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        int maxRequests = rateLimit.value();
        // 限流逻辑
        if (isOverLimit(maxRequests)) {
            throw new RuntimeException("请求过于频繁");
        }
        return joinPoint.proceed();
    }
}

// 3. 使用
@Service
public class ApiService {
    @RateLimit(50)
    public void callExternalApi() { ... }
}
```

#### After — Solon

```java
// 1. 定义注解（相同）
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int value() default 100;
}

// 2. 定义拦截器
public class RateLimitInterceptor implements MethodInterceptor, MethodInterceptor<RateLimit> {

    @Override
    public Object intercept(Invocation inv, RateLimit anno, Object... args) throws Throwable {
        int maxRequests = anno.value();
        // 限流逻辑
        if (isOverLimit(maxRequests)) {
            throw new RuntimeException("请求过于频繁");
        }
        return inv.invoke();
    }
}

// 3. 注册拦截器（在 Plugin 或启动类中）
Solon.context().beanInterceptorAdd(RateLimit.class, new RateLimitInterceptor());

// 4. 使用（相同）
@Component
public class ApiService {
    @RateLimit(50)
    public void callExternalApi() { ... }
}
```

---

## 8. 事件机制迁移

### 8.1 ApplicationEvent → EventBus

| 概念 | Spring | Solon |
|---|---|---|
| 事件基类 | `ApplicationEvent` | 无需继承（任意对象可作为事件） |
| 事件发布 | `ApplicationEventPublisher.publishEvent()` | `EventBus.publish()` |
| 事件监听 | `@EventListener` 注解 | `EventListener` 接口实现 |
| 异步事件 | `@Async` + `@EventListener` | `EventBus.publishAsync()` |
| 事件顺序 | `@Order` 注解 | `@Order` 注解（相同） |

---

### 8.2 事件发布与监听完整对照

#### Before — Spring

```java
// 1. 定义事件
public class OrderCreatedEvent extends ApplicationEvent {
    private final Long orderId;
    private final String customerName;

    public OrderCreatedEvent(Object source, Long orderId, String customerName) {
        super(source);
        this.orderId = orderId;
        this.customerName = customerName;
    }

    public Long getOrderId() { return orderId; }
    public String getCustomerName() { return customerName; }
}

// 2. 发布事件
@Service
public class OrderService {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public void createOrder(Order order) {
        // 业务逻辑
        eventPublisher.publishEvent(
            new OrderCreatedEvent(this, order.getId(), order.getCustomerName())
        );
    }
}

// 3. 监听事件（同步）
@Component
public class EmailNotifier {

    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        System.out.println("发送邮件: 订单 " + event.getOrderId() + " 已创建");
    }
}

// 4. 监听事件（异步）
@Component
public class StatisticsService {

    @Async
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        System.out.println("异步统计: 订单 " + event.getOrderId());
    }
}
```

#### After — Solon

```java
// 1. 定义事件（普通 POJO，无需继承 ApplicationEvent）
public class OrderCreatedEvent {
    private final Long orderId;
    private final String customerName;

    public OrderCreatedEvent(Long orderId, String customerName) {
        this.orderId = orderId;
        this.customerName = customerName;
    }

    public Long getOrderId() { return orderId; }
    public String getCustomerName() { return customerName; }
}

// 2. 发布事件
@Component
public class OrderService {

    public void createOrder(Order order) {
        // 业务逻辑
        // 使用 EventBus.publish() 发布事件
        EventBus.publish(
            new OrderCreatedEvent(order.getId(), order.getCustomerName())
        );
    }
}

// 3. 监听事件（同步 —— 实现 EventListener 接口）
@Component
public class EmailNotifier implements EventListener<OrderCreatedEvent> {

    @Override
    public void onEvent(OrderCreatedEvent event) throws Throwable {
        System.out.println("发送邮件: 订单 " + event.getOrderId() + " 已创建");
    }
}

// 4. 监听事件（异步 —— 使用 publishAsync 或 subscribeAsync）
@Component
public class StatisticsService {

    public StatisticsService() {
        // 异步订阅方式
        EventBus.subscribe(OrderCreatedEvent.class, event -> {
            System.out.println("异步统计: 订单 " + event.getOrderId());
        });
    }
}
```

> **差异说明**：
> - Spring 事件必须继承 `ApplicationEvent`；Solon 事件是**任意 Java 对象**（POJO），无需继承任何基类。
> - Spring 通过 `@EventListener` 注解标记监听方法；Solon 通过实现 `EventListener<T>` 接口或 `EventBus.subscribe()` 注册监听。
> - 异步事件：Spring 用 `@Async` + `@EventListener`；Solon 通过 `EventBus.publishAsync()` 或 `EventBus.subscribe()` 处理。

---

## 9. 包扫描迁移

#### Before — Spring

```java
// @ComponentScan 指定扫描包路径
@SpringBootApplication
@ComponentScan(basePackages = "com.example")
public class Application { }

// @Import 导入特定配置类
@SpringBootApplication
@Import({DataSourceConfig.class, RedisConfig.class})
public class Application { }
```

#### After — Solon

```java
// @Import 统一处理（替代 @ComponentScan 和 @Import）
@SolonMain
@Import(scanPackages = "com.example")                  // 包扫描
@Import({DataSourceConfig.class, RedisConfig.class})   // 类导入
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
```

> **差异说明**：
> - Solon 的 `@Import` 同时替代 Spring 的 `@ComponentScan` 和 `@Import`。
> - `@Import(scanPackages = "...")` 等价于 `@ComponentScan(basePackages = "...")`。
> - `@Import({A.class, B.class})` 等价于 Spring 的 `@Import({A.class, B.class})`。
> - `@Import` 只在启动类或 `@Configuration` 类上有效，在其他类上无效。

---

## 10. 核心陷阱与差异清单

### 陷阱速查表

| 编号 | 陷阱描述 | 严重程度 | 详细说明 |
|---|---|---|---|
| 1 | **不支持 setter 注入** | 高 | Solon 仅支持字段注入、构造器参数注入、`@Bean` 方法参数注入。原项目中的 setter 注入必须改写。 |
| 2 | **Bean 默认不按名字注册** | 高 | Spring 自动以类名注册 Bean name；Solon 需在 `@Component("name")` 或 `@Bean("name")` 上显式声明 name。 |
| 3 | **构造器参数必须标注 @Inject** | 中 | Spring 单构造器可省略 `@Autowired`；Solon 构造器参数必须显式标注 `@Inject`。 |
| 4 | **AOP 只代理 public 方法** | 高 | Spring 对 public/protected 方法都代理；Solon 只代理 public 方法。protected 方法上的 `@Transaction` 等注解将失效。 |
| 5 | **AOP 不支持 execution 表达式** | 高 | Spring 的 `@Around("execution(...)")` 在 Solon 中不可用，需改用自定义注解 + `beanInterceptorAdd`。 |
| 6 | **@Mapping 不支持多路径** | 中 | 一个 `@Mapping` 只能映射一个路径模式，不支持 `@RequestMapping({"/a", "/b"})` 形式。 |
| 7 | **配置文件名不同** | 中 | `application.yml` → `app.yml`，`application-{profile}.yml` → `app-{env}.yml`。 |
| 8 | **server 配置结构扁平化** | 中 | `server.servlet.context-path` → `server.contextPath`，注意驼峰命名。 |
| 9 | **@Controller 默认返回 JSON** | 低 | 不需要 `@ResponseBody`，也不存在 `@RestController`。如需返回视图需显式指定。 |
| 10 | **没有 @Scope("request")/@Scope("session")** | 中 | Solon 无请求/会话作用域，需通过 `Context` 或 `SessionState` 手动管理。 |
| 11 | **@Import 多义性** | 低 | `@Import` 同时承担包扫描、类导入、属性源导入三种职责，注意区分使用场景。 |
| 12 | **事件无需继承基类** | 低 | Solon 事件是普通 POJO，不需要继承 `ApplicationEvent`。 |

### 迁移检查清单

- [ ] 启动类：`@SpringBootApplication` → `@SolonMain`，`SpringApplication.run()` → `Solon.start()`
- [ ] IoC 注入：全局替换 `@Autowired` → `@Inject`，`@Qualifier` → `@Inject("name")`
- [ ] setter 注入：所有 setter 注入改为字段注入或构造器注入
- [ ] 构造器注入：确认所有构造器参数已添加 `@Inject` 注解
- [ ] 组件注解：`@Service`/`@Repository`/`@Dao` → `@Component`
- [ ] Bean 名称：检查按名称注入的 Bean 是否在定义处显式声明了 name
- [ ] 配置值注入：`@Value("${x}")` → `@Inject("${x}")`
- [ ] 配置属性集：`@ConfigurationProperties` → `@Inject("${prefix}")` + `@Configuration` 或 `@BindProps`
- [ ] 生命周期：`@PostConstruct` → `@Init`，`@PreDestroy` → `@Destroy`
- [ ] 条件装配：`@ConditionalOnXxx` → `@Condition(onXxx=...)`
- [ ] 包扫描：`@ComponentScan` → `@Import(scanPackages=...)`
- [ ] 配置文件名：`application.yml` → `app.yml`，环境变量前缀统一修改
- [ ] AOP 切面：AspectJ execution 表达式 → 自定义注解 + `beanInterceptorAdd`
- [ ] AOP 方法可见性：确认所有需要拦截的方法都是 public
- [ ] 事件机制：`ApplicationEvent` → 普通 POJO，`@EventListener` → `EventListener` 接口
- [ ] 启动回调：`ApplicationRunner`/`CommandLineRunner` → `AppLoadEndEvent` 事件监听
