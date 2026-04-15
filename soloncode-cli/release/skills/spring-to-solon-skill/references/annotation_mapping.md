# Spring → Solon 注解对照表

> 基于 Solon 3.10.x / Spring Boot 2.7 / 3.x | 官方来源: https://solon.noear.org/article/compare-springboot

---

## 一、IoC / 依赖注入 (DI)

### 1.1 Bean 注入（按类型）

```java
// [Spring] @Autowired 按类型注入
@Service
public class OrderService {
    @Autowired
    private UserService userService;
}
```

```java
// [Solon] @Inject 按类型注入
@Component
public class OrderService {
    @Inject
    private UserService userService;
}
```

> **注意**: Solon 不支持 setter 方法注入。仅支持字段注入、构造器参数注入、`@Bean` 方法参数注入。

---

### 1.2 Bean 注入（按名称）

```java
// [Spring] @Qualifier + @Autowired
@Service
public class OrderService {
    @Qualifier("orderDataSource")
    @Autowired
    private DataSource dataSource;
}
```

```java
// [Solon] @Inject("name")
@Component
public class OrderService {
    @Inject("orderDataSource")
    private DataSource dataSource;
}
```

> **注意**: Solon 容器注册时需要配置 name 才会按名字注册。Spring 会自动以类名作为 name 注册，行为不同。

---

### 1.3 配置值注入

```java
// [Spring] @Value 注入配置
@Service
public class UserService {
    @Value("${app.name}")
    private String appName;

    @Value("${app.timeout:3000}")
    private int timeout;
}
```

```java
// [Solon] @Inject("${name}") 注入配置
@Component
public class UserService {
    @Inject("${app.name}")
    private String appName;

    @Inject("${app.timeout:3000}")
    private int timeout;

    // 也可手动获取
    // private String appName = Solon.cfg().get("app.name");
}
```

> **注意**: Solon 用同一个 `@Inject` 注解，通过 `${}` 前缀区分配置注入与 Bean 注入。

---

### 1.4 配置属性集绑定

```java
// [Spring] @ConfigurationProperties(prefix="name")
@ConfigurationProperties(prefix = "datasource")
public class DataSourceProps {
    private String url;
    private String username;
    // getter/setter ...
}
```

```java
// [Solon] 方式一: @Inject("${prefix}") 注入到配置类
@Inject("${datasource}")
@Configuration
public class DataSourceProps {
    public String url;
    public String username;
    // 字段为 public，无需 getter/setter
}

// 别处可直接注入复用
@Component
public class SomeService {
    @Inject
    DataSourceProps dataSourceProps;
}
```

```java
// [Solon] 方式二: @BindProps(prefix="name") 绑定到组件字段
@Component
public class SomeService {
    @BindProps(prefix = "datasource")
    private DataSourceProps props;
}
```

---

## 二、组件注册与作用域

### 2.1 组件注册

```java
// [Spring] 多种语义注解: @Component, @Service, @Repository, @Controller
@Service
public class UserService { }

@Repository
public class UserDao { }
```

```java
// [Solon] 统一使用 @Component
@Component
public class UserService { }

@Component
public class UserDao { }
```

> **注意**: Solon 没有 `@Service` / `@Repository` / `@Dao` 等细分注解，统一使用 `@Component`。对于 Web 控制器使用 `@Controller`。

---

### 2.2 作用域

```java
// [Spring] @Scope 控制作用域
@Service
@Scope("singleton")    // 默认，单例
public class ConfigService { }

@Service
@Scope("prototype")    // 原型，每次获取新实例
public class RequestContext { }
```

```java
// [Solon] @Singleton 控制作用域（Solon 默认即为单例）
@Component
@Singleton             // 默认，可省略
public class ConfigService { }

@Component
@Singleton(false)      // 多例（每次获取新实例）
public class RequestContext { }
```

> **关键差异**:
> - Solon **没有** `@Scope("prototype")` 的完全等价物。`@Singleton(false)` 是"多例"，每次获取新实例。
> - Solon **没有** `@Scope("request")` 和 `@Scope("session")`，需要通过 `Context` 或 `SessionState` 替代。

---

### 2.3 条件注册

```java
// [Spring] 多种条件注解
@Configuration
public class MyConfig {
    @Bean
    @ConditionalOnClass(name = "redis.clients.jedis.Jedis")
    @ConditionalOnExpression("${feature.redis.enabled}")
    public RedisService redisService() {
        return new RedisService();
    }
}
```

```java
// [Solon] @Condition 统一处理
@Configuration
public class MyConfig {
    @Bean
    @Condition(onClass = "redis.clients.jedis.Jedis")
    public RedisService redisService() {
        return new RedisService();
    }
}
```

---

## 三、配置类与 Bean 定义

### 3.1 配置类

```java
// [Spring] @Configuration + @Bean
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        return new HikariDataSource();
    }

    @Bean("secondaryDataSource")
    public DataSource secondaryDataSource() {
        return new HikariDataSource();
    }
}
```

```java
// [Solon] @Configuration + @Bean（结构相似）
@Configuration
public class DataSourceConfig {

    // typed=true 表示该类型的默认 Bean
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${test.db1}") HikariDataSource ds) {
        return ds;
    }

    @Bean("db2")
    public DataSource db2(@Inject("${test.db2}") HikariDataSource ds) {
        return ds;
    }
}
```

> **注意**:
> - Solon 的 `@Bean` 可以返回 `void`（用于执行初始化逻辑）。
> - `@Bean(typed=true)` 用于声明某类型的默认 Bean，在其他地方直接 `@Inject` 该类型时优先注入此 Bean。
> - `@Inject` 的参数注入只在 `@Bean` 方法和构造器上有效。

---

## 四、生命周期

### 4.1 初始化与销毁

```java
// [Spring] @PostConstruct + @PreDestroy，或实现 InitializingBean + DisposableBean
@Service
public class UserService implements InitializingBean, DisposableBean {

    @PostConstruct
    public void init() {
        // 初始化逻辑
    }

    @PreDestroy
    public void cleanup() {
        // 销毁逻辑
    }

    @Override
    public void afterPropertiesSet() { /* InitializingBean */ }

    @Override
    public void destroy() { /* DisposableBean */ }
}
```

```java
// [Solon] 方式一: @Init + @Destroy 注解
@Component
public class UserService {

    @Init
    public void init() {
        // 初始化逻辑
    }

    @Destroy
    public void cleanup() {
        // 销毁逻辑
    }
}
```

```java
// [Solon] 方式二: LifecycleBean（推荐，支持 start/stop 顺序控制）
@Component
public class UserService extends LifecycleBean {

    @Override
    protected void start() throws Throwable {
        // 初始化逻辑（按优先级顺序执行）
    }

    @Override
    protected void stop() throws Throwable {
        // 销毁逻辑
    }
}
```

---

### 4.2 应用启动完成后执行

```java
// [Spring] ApplicationRunner 或 CommandLineRunner
@Component
public class StartupRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 应用启动完成后执行
    }
}
```

```java
// [Solon] 监听 AppLoadEndEvent 事件
@Component
public class StartupRunner {
    public StartupRunner() {
        Solon.app().onEvent(AppLoadEndEvent.class, e -> {
            // 应用加载完成后执行
        });
    }
}
```

---

## 五、Web / MVC

### 5.1 控制器

```java
// [Spring] @RestController = @Controller + @ResponseBody
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.getById(id);
    }

    @PostMapping
    public User createUser(@RequestBody User user) {
        return userService.create(user);
    }
}
```

```java
// [Solon] @Controller（默认即返回 JSON，无需 @ResponseBody）
@Controller
@Mapping("/api/users")
public class UserController {

    @Mapping("/{id}")
    public User getUser(@Path long id) {
        return userService.getById(id);
    }

    @Mapping(method = MethodType.POST)
    public User createUser(@Body User user) {
        return userService.create(user);
    }
}
```

> **关键差异**:
> - Solon 没有 `@RestController`，统一使用 `@Controller`，默认输出 JSON。
> - `@Mapping` 同时替代 `@RequestMapping`、`@GetMapping`、`@PostMapping` 等，通过 `method` 参数指定 HTTP 方法。
> - Solon 的 `@Mapping` **不支持多路径映射**（如 `@RequestMapping({"/a", "/b"})`）。
> - 控制器继承时，Solon 支持基类的 `@Mapping` public 函数。
> - 可通过"本地网关"为一批 action 添加不同地址前缀。

---

### 5.2 请求参数绑定对照

| Spring 注解 | Solon 注解 | 说明 |
|---|---|---|
| `@RequestParam` | `@Param` | 请求参数 |
| `@RequestHeader` | `@Header` | 请求头 |
| `@RequestBody` | `@Body` | 请求体（JSON 绑定） |
| `@CookieValue` | `@Cookie` | Cookie |
| `@PathVariable` | `@Path` | 路径变量 |

```java
// [Spring] 各类参数绑定
@PostMapping("/order")
public Order createOrder(
        @RequestParam String product,
        @RequestHeader("X-Token") String token,
        @RequestBody OrderRequest body,
        @CookieValue("sessionId") String sessionId,
        @PathVariable("storeId") Long storeId) {
    // ...
}
```

```java
// [Solon] 各类参数绑定
@Mapping(method = MethodType.POST, value = "/order/{storeId}")
public Order createOrder(
        @Param String product,
        @Header("X-Token") String token,
        @Body OrderRequest body,
        @Cookie("sessionId") String sessionId,
        @Path long storeId) {
    // ...
}
```

> **注意**: Solon 的 `@Param`、`@Body` 等与 Spring 的对应注解行为"并不完全对等"，迁移时需逐一验证。

---

### 5.3 内容类型声明

```java
// [Spring] 通过 produces/consumes 属性声明
@GetMapping(value = "/data", produces = "application/json", consumes = "application/json")
public Data getData() { }
```

```java
// [Solon] @Produces / @Consumes 独立注解
@Mapping("/data")
@Produces("application/json")
@Consumes("application/json")
public Data getData() { }
```

---

### 5.4 请求上下文对照

| Spring (Servlet) 类 | Solon 类 | 说明 |
|---|---|---|
| `HttpServletRequest` + `HttpServletResponse` | `Context` | 统一请求上下文 |
| `HttpSession` | `SessionState` | 会话状态 |
| `MultipartFile` | `UploadedFile` | 文件上传 |
| 无 | `DownloadedFile` | 文件下载（Solon 独有） |
| `ModelAndView` | `ModelAndView` | 模型视图（名称相同，包不同） |

```java
// [Spring] 使用 Servlet API
@GetMapping("/upload")
public String upload(@RequestParam("file") MultipartFile file,
                     HttpServletRequest request) {
    String clientIp = request.getRemoteAddr();
    String originalName = file.getOriginalFilename();
    return "ok";
}
```

```java
// [Solon] 使用 Context（非 Servlet 架构，但兼容 Servlet）
@Mapping("/upload")
public String upload(UploadedFile file, Context ctx) {
    String clientIp = ctx.realIp();
    String originalName = file.getName();
    return "ok";
}
```

> **注意**: Solon 不基于 Servlet，但支持 Servlet API 兼容。当使用 Jetty/Undertow 等 Servlet 容器时，可直接注入 `HttpServletRequest`。

---

### 5.5 远程控制器 (RPC 服务端)

```java
// [Spring] 无直接等价物，通常用 Spring MVC 暴露 REST API
```

```java
// [Solon] @Remoting 标注为 RPC 服务端
@Mapping("/user")
@Remoting
public class UserServiceImpl implements UserService {
    @Override
    public UserModel getUser(Integer userId) {
        return ...;
    }
}
```

> **注意**: `@Remoting` 是 Solon 独有的 RPC 服务端注解，配合 `@NamiClient` 消费端使用。

---

## 六、AOP / 拦截

### 6.1 动态代理差异

| 特性 | Spring | Solon |
|---|---|---|
| 代理范围 | public、protected 方法 | 仅 public 方法 |
| 代理策略 | 默认对所有组件代理 | 按需代理（有拦截器注册时才代理） |
| 性能影响 | 较重 | 较轻（启动快的原因之一） |

```java
// [Solon] 自定义拦截器注解示例
// 1. 定义注解
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthLogined { }

// 2. 注册拦截器（通常在 Plugin 中）
Solon.context().beanInterceptorAdd(AuthLogined.class, new LoginedInterceptor());
```

---

## 七、数据访问 / 事务 / 缓存

### 7.1 事务

```java
// [Spring] @Transactional（需指定回滚异常类型）
@Service
public class OrderService {
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(Order order) {
        orderDao.insert(order);
    }
}
```

```java
// [Solon] @Transaction（回滚不需要指定异常类型，默认所有异常回滚）
@Component
public class OrderService {
    @Transaction
    public void createOrder(Order order) {
        orderDao.insert(order);
    }
}
```

> **注意**: Solon 采用与 Spring 相同的事务传播机制和隔离级别，但回滚策略更简洁，无需指定 `rollbackFor`。

---

### 7.2 缓存

```java
// [Spring] @Cacheable + @CacheEvict（基于 key 管理）
@Service
public class UserService {
    @Cacheable(value = "users", key = "#userId")
    public User getUser(Long userId) {
        return userRepo.findById(userId);
    }

    @CacheEvict(value = "users", key = "#userId")
    public void updateUser(Long userId, User user) {
        userRepo.save(user);
    }
}
```

```java
// [Solon] @Cache + @CacheRemove（基于标签管理，避免 key 冲突）
@Component
public class UserService {
    @Cache(tags = "user_${userId}")
    public User getUser(long userId) {
        return userRepo.findById(userId);
    }

    @CacheRemove(tags = "user_${userId}")
    public void updateUser(long userId, User user) {
        userRepo.save(user);
    }
}
```

> **关键差异**: Solon 缓存基于"标签"管理而非纯 key。标签支持模糊匹配清理，更适合关联数据的缓存失效。

---

### 7.3 参数校验

```java
// [Spring] @Validated（基于 JSR 380 / Hibernate Validator）
@RestController
public class UserController {
    @PostMapping("/user")
    public String create(@Validated @RequestBody UserDTO user) {
        return "ok";
    }
}
```

```java
// [Solon] @Valid + 函数级参数校验（可见性更强，支持批量校验）
@Valid
@Controller
public class UserController {

    @NotNull({"name", "mobile"})
    @Mapping("/user/add")
    public String add(String name,
                      @Pattern("13\\d{9}") String mobile) {
        // 参数校验注解直接在函数声明处，可见性更好
        return "ok";
    }

    @Mapping("/user/create")
    public String create(@Validated UserDTO user) {
        // 也支持实体校验
        return "ok";
    }
}
```

---

## 八、组件导入与扫描

### 8.1 包扫描与导入

```java
// [Spring] @ComponentScan + @Import
@SpringBootApplication
@ComponentScan(basePackages = "com.example")
@Import({DataSourceConfig.class, RedisConfig.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

```java
// [Solon] @Import（统一替代 @ComponentScan 和 @Import）
@SolonMain
@Import({DataSourceConfig.class, RedisConfig.class})
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
```

> **注意**: Solon 使用 `@Import` 统一处理组件导入和属性源导入，不需要单独的 `@ComponentScan`。`@Import` 只在启动类或 `@Configuration` 类上有效。

---

### 8.2 属性源导入

```java
// [Spring] @PropertySource
@Configuration
@PropertySource("classpath:custom.properties")
public class CustomConfig { }
```

```java
// [Solon] @Import（同样用于导入属性源）
@SolonMain
@Import("classpath:custom.properties")
public class App { }
```

---

## 九、RPC 客户端 (Nami)

```java
// [Spring] @FeignClient
@FeignClient(name = "user-service", url = "http://localhost:8080")
public interface UserServiceClient {
    @GetMapping("/user/{id}")
    User getUser(@PathVariable("id") Long id);
}
```

```java
// [Solon] @NamiClient（更简洁，支持 HTTP 和 Socket 通道）
// 接口定义（通常不需要注解）
public interface UserService {
    User getUser(Integer userId);
}

// 客户端注入
@Controller
public class DemoController {

    // 直接指定地址
    @NamiClient("http://localhost:8080/user/")
    UserService userService;

    // 使用负载均衡
    @NamiClient(name = "local", path = "/user/")
    UserService userService2;
}
```

> **注意**: Nami 比 Feign 更轻量，支持 Socket 通道（不只是 HTTP）。配合 Solon Cloud 可实现服务发现负载均衡。

---

## 十、定时任务

```java
// [Spring] @Scheduled（注解名称和用法基本一致）
@Component
@EnableScheduling
public class ScheduledTasks {
    @Scheduled(fixedRate = 5000)
    public void reportCurrentTime() {
        System.out.println("现在时间: " + LocalDateTime.now());
    }
}
```

```java
// [Solon] @Scheduled（注解相同，无需额外 @EnableScheduling）
@Component
public class ScheduledTasks {
    @Scheduled(fixedRate = 5000)
    public void reportCurrentTime() {
        System.out.println("现在时间: " + LocalDateTime.now());
    }
}
```

> **注意**: Solon 不需要 `@EnableScheduling`，引入 `solon-scheduling-simple` 插件即可自动生效。

---

## 十一、测试

### 11.1 测试类注解

```java
// [Spring] @SpringBootTest
@SpringBootTest(classes = Application.class)
public class UserServiceTest {
    @Autowired
    private UserService userService;

    @Test
    public void testGetUser() {
        assertNotNull(userService.getUser(1L));
    }
}
```

```java
// [Solon] @SolonTest
@SolonTest(App.class)
public class UserServiceTest {
    @Inject
    private UserService userService;

    @Test
    public void testGetUser() {
        assertNotNull(userService.getUser(1L));
    }
}
```

---

### 11.2 测试属性源

```java
// [Spring] @TestPropertySource
@SpringBootTest
@TestPropertySource(properties = {"spring.profiles.active=test"})
public class MyTest { }
```

```java
// [Solon] @Import 导入测试属性源
@SolonTest
@Import("classpath:app-test.yml")
public class MyTest { }
```

---

### 11.3 测试回滚

```java
// [Spring] @TestRollback（Spring Test 的回滚注解）
@SpringBootTest
public class MyDaoTest {
    @TestRollback
    @Test
    public void testInsert() { }
}
```

```java
// [Solon] @Rollback
@SolonTest
public class MyDaoTest {
    @Rollback
    @Test
    public void testInsert() { }
}
```

---

## 十二、启动类

```java
// [Spring] @SpringBootApplication
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

```java
// [Solon] @SolonMain
@SolonMain
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
```

> **注意**: Solon 启动类可使用 `Solon.start(App.class, args, app -> { /* 启动前配置 */ })` 进行启动前定制。

---

## 十三、完整注解速查表

| 分类 | Spring Boot | Solon 3.10.x | 简要说明 |
|---|---|---|---|
| **DI** | `@Autowired` | `@Inject` | 按类型注入 |
| **DI** | `@Qualifier`+`@Autowired` | `@Inject("name")` | 按名称注入 |
| **DI** | `@Value("${x}")` | `@Inject("${x}")` | 注入配置值 |
| **配置** | `@ConfigurationProperties` | `@BindProps` / `@Inject("${p}")` | 属性集绑定 |
| **配置** | `@Configuration` | `@Configuration` | 配置类（相同） |
| **配置** | `@Bean` | `@Bean` | 配置 Bean（相同） |
| **配置** | `@PropertySource` | `@Import` | 导入属性源 |
| **配置** | `@ConditionalOnXxx` | `@Condition` | 条件注册 |
| **组件** | `@Component`/`@Service`/`@Repository` | `@Component` | 托管组件（统一） |
| **组件** | `@Import`+`@ComponentScan` | `@Import` | 导入与扫描 |
| **作用域** | `@Scope("singleton")` | `@Singleton` | 单例（Solon 默认） |
| **作用域** | `@Scope("prototype")` | 无直接等价，用 `@Singleton(false)` | 多例 |
| **生命周期** | `@PostConstruct` | `@Init` / `LifecycleBean.start()` | 初始化 |
| **生命周期** | `@PreDestroy` | `@Destroy` / `LifecycleBean.stop()` | 销毁 |
| **生命周期** | `InitializingBean`+`DisposableBean` | `LifecycleBean` | 生命周期管理 |
| **生命周期** | `ApplicationRunner` | `AppLoadEndEvent` | 应用加载完成 |
| **Web** | `@RestController` | `@Controller` | 控制器 |
| **Web** | `@RequestMapping`/`@GetMapping`... | `@Mapping` | 路径映射 |
| **Web** | `@RequestParam` | `@Param` | 请求参数 |
| **Web** | `@RequestHeader` | `@Header` | 请求头 |
| **Web** | `@RequestBody` | `@Body` | 请求体 |
| **Web** | `@CookieValue` | `@Cookie` | Cookie |
| **Web** | `@PathVariable` | `@Path` | 路径变量 |
| **Web** | `produces`/`consumes` 属性 | `@Produces`/`@Consumes` | 内容类型 |
| **Web** | `HttpServletRequest`+`HttpServletResponse` | `Context` | 请求上下文 |
| **Web** | `HttpSession` | `SessionState` | 会话 |
| **Web** | `MultipartFile` | `UploadedFile` | 文件上传 |
| **Web** | 无 | `DownloadedFile` | 文件下载（Solon 独有） |
| **Web** | 无 | `@Remoting` | RPC 服务端（Solon 独有） |
| **事务** | `@Transactional` | `@Transaction` | 事务（无需指定回滚异常） |
| **缓存** | `@Cacheable` | `@Cache` | 缓存（基于标签管理） |
| **缓存** | `@CacheEvict` | `@CacheRemove` | 缓存清除 |
| **校验** | `@Validated` | `@Valid` | 参数校验（支持批量校验） |
| **定时** | `@Scheduled` | `@Scheduled` | 定时任务（注解相同） |
| **RPC** | `@FeignClient` | `@NamiClient` | RPC 客户端 |
| **启动** | `@SpringBootApplication` | `@SolonMain` | 启动类 |
| **测试** | `@SpringBootTest` | `@SolonTest` | 测试类 |
| **测试** | `@TestPropertySource` | `@Import` | 测试属性源 |
| **测试** | `@TestRollback` | `@Rollback` | 测试回滚 |

---

## 十四、迁移高频注意事项

1. **Solon 不支持 setter 注入**。只支持字段注入、构造器参数注入和 `@Bean` 方法参数注入。
2. **配置文件名不同**: `application.yml` → `app.yml`。
3. **`@Import` 多义性**: Solon 的 `@Import` 同时承担 `@ComponentScan`、`@Import`、`@PropertySource` 三者的职责，只在启动类或 `@Configuration` 类上有效。
4. **`@Controller` 默认返回 JSON**: 不需要 `@ResponseBody`，也不存在 `@RestController`。
5. **`@Mapping` 不支持多路径**: 一个 `@Mapping` 只能映射一个路径模式。
6. **容器注册行为差异**: Spring 自动以类名注册 Bean name；Solon 需显式配置 name 才能按名称获取。
7. **AOP 代理差异**: Solon 仅代理 public 方法，且按需代理（有拦截注册时才代理）。
8. **事务更简洁**: `@Transaction` 不需要指定 `rollbackFor`，默认所有异常回滚。
9. **缓存模型不同**: Spring 基于 key，Solon 基于标签（tags），支持关联失效。
10. **`@Inject` 一注多用**: 通过 `@Inject` / `@Inject("name")` / `@Inject("${name}")` 分别实现类型注入、名称注入、配置注入。
