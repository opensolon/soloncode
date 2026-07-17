# Core Concepts — 核心概念

> 适用场景：理解 Solon 的 IoC 容器、配置系统、表达式语言，以及与 Spring 的区别。
>
> 目标版本：4.0.3。注解速查表仅作精简对照；完整 Spring 迁移请用 `spring-to-solon-skill`。
> EventBus 完整 API 见 `api_reference.md`；生态总览见 `quick_start.md`。
> 插件 / E-SPI / H-SPI / 配置元数据见 **`plugin_spi.md`**（业务开发不必加载）。
>
> **阅读建议**：业务开发优先看注解对照、IoC/配置、`@Inject`。

## 注解对照（Solon vs Spring）

> 详细迁移与工程改造见 **`spring-to-solon-skill`**。下表仅作速查；**右侧注解禁止出现在 Solon 代码中**。
> 注解完整说明见 `api_reference.md`。

| Solon | 用途 | Spring 等价（禁止使用） |
|---|---|---|
| `@SolonMain` | 入口类标识 | `@SpringBootApplication` |
| `@Controller` | Web 控制器 | `@RestController` / `@Controller` |
| `@Remoting` | RPC 远程控制器 | / |
| `@Mapping("/path")` | URL 映射 | `@RequestMapping` |
| `@Get` / `@Post` / `@Put` / `@Delete` | HTTP 方法限定 | `@GetMapping` / `@PostMapping` 等 |
| `@Inject` | 按类型注入 | `@Autowired` |
| `@Inject("name")` | 按名称注入 | `@Qualifier` + `@Autowired` |
| `@Inject("${key}")` | 注入配置值 | `@Value("${key}")` |
| `@BindProps(prefix="xxx")` | 绑定配置组 | `@ConfigurationProperties(prefix="xxx")` |
| `@Component` | 托管组件 | `@Component` / `@Service` / `@Dao` / `@Repository` |
| `@Configuration` | 配置类 | `@Configuration` |
| `@Bean` | 声明 Bean（在 @Configuration 内） | `@Bean` |
| `@Condition` | 条件注册 | `@ConditionalOn*` |
| `@Import` | 导入类 / 扫描包 / 导入属性 | `@ComponentScan` + `@Import` + `@PropertySource` |
| `@Singleton` | 单例（默认） | `@Scope("singleton")` |
| `@Singleton(false)` | 多例 | / |
| `@Param` / `@Body` / `@Header` / `@Cookie` / `@Path` | 请求绑定 | `@RequestParam` / `@RequestBody` 等 |
| `@Produces` / `@Consumes` | 声明内容类型 | / |
| `@Init` / `@Destroy` | 初始化 / 销毁 | `@PostConstruct` / `@PreDestroy` |
| `@Valid` | 参数校验（类级） | `@Validated` |
| `@Transaction` | 事务 | `@Transactional` |
| `@NamiClient` | RPC 客户端 | `@FeignClient` |
| `@Cache` / `@CacheRemove` | 缓存（支持 tag） | `@Cacheable` / `@CacheEvict` |
| `@Rollback` | 测试回滚 | `@TestRollback` |

### 注解约束

- `@Bean` 方法只在 `@Configuration` 类内有效，且只执行一次
- 参数上的 `@Inject` 只在 `@Bean` 方法与构造器中有效
- 类上的 `@Inject` 只在 `@Configuration` 类上有效
- `@Import` 只在入口类或 `@Configuration` 类上有效
- **不支持 setter 注入**；请用字段注入、构造器参数或 `@Bean` 方法参数

## IoC 容器

- 访问容器：`Solon.context()`
- 获取 Bean：`Solon.context().getBean(UserService.class)`
- 注册 Bean：`Solon.context().wrapAndPut(DemoService.class)`

### ScopeLocal（作用域变量）

用于在调用链内传递上下文（类似增强版 ThreadLocal，支持结构化作用域）：

```java
static ScopeLocal<User> LOCAL = ScopeLocal.newInstance();

LOCAL.with(user, () -> {
    String name = LOCAL.get().getName();
});
```

### IoC / AOP 要点

**IoC（控制反转 / 依赖注入）**：对象通过容器获取，而非直接 `new`。容器扫描 `@Component` 等注解，完成注册与 `@Inject` 注入。

**AOP**：通过对组件建立代理实现。仅 `public` 方法可被代理，且**仅在注册了拦截器时**才代理（按需代理，这是 Solon 更快的原因之一）。切点模型以注解为中心。

### IoC/AOP 扩展点（插件常用）

| 扩展方法 | 用途 | 示例 |
|---|---|---|
| `beanBuilderAdd(anno, handler)` | 注册 Bean 构建器 | `@Controller` 构建时注册路由 |
| `beanInjectorAdd(anno, handler)` | 注册字段注入器 | `@Inject` 解析 Bean/配置 |
| `beanInterceptorAdd(anno, interceptor, index)` | 注册方法拦截器 | `@Transaction` 包装调用 |
| `beanExtractorAdd(anno, extractor)` | 注册方法提取器 | `@CloudJob` 收集任务方法 |

```java
Solon.context().beanInterceptorAdd(AuthLogined.class, new LoginedInterceptor());
```

> 完整插件开发流程见 `plugin_spi.md`。

## 应用生命周期

从 `start()` 到 `stop()` 的主要阶段：

1. **一次初始化回调** — `Solon.start()` 的 lambda
2. **六个应用事件** — `AppInitEndEvent`、`AppPluginLoadEndEvent`、`AppBeanLoadEndEvent`、`AppLoadEndEvent`、`AppPrestopEndEvent`、`AppStopEndEvent`
3. **插件生命周期** — `Plugin.start()` / `prestop()` / `stop()`
4. **容器生命周期** — `AppContext.start()` / `stop()`

### 事件顺序

```
[Init lambda] -> AppInitEndEvent -> [Plugin.start] -> AppPluginLoadEndEvent
-> [Bean 扫描 + 注入] -> AppBeanLoadEndEvent -> [AppContext.start / @Init]
-> AppLoadEndEvent -> ::运行中::
-> AppPrestopEndEvent -> [Plugin.prestop] -> [AppContext.stop / @Destroy]
-> [Plugin.stop] -> AppStopEndEvent
```

注意：

- 启动完成前不要阻塞线程，否则无法对外服务
- `AppBeanLoadEndEvent` **之前**的事件须在启动前手动订阅（如 `Solon.start()` lambda），否则会错过时机

### 事件订阅

```java
// 早期事件：手动订阅
Solon.start(App.class, args, app -> {
    app.onEvent(AppInitEndEvent.class, e -> { /* ... */ });
});

// 晚期事件：注解组件订阅
@Component
public class AppLoadEndListener implements EventListener<AppLoadEndEvent> {
    @Override
    public void onEvent(AppLoadEndEvent event) throws Throwable {
        // ...
    }
}
```

## Bean 生命周期

| 阶段 | 说明 | 备注 |
|---|---|---|
| `::new()` | 扫描时构造 | 尚未注册进容器 |
| `@Inject` | 字段注入 | 注入后注册进容器 |
| `start()` 或 `@Init` | `AppContext::start()` | 扫描完成，Bean 可用；v2.2.8+ 按依赖自动排序 |
| `postStart()` | `AppContext::start()` 后半段 | v2.9+；启动网络监听等 |
| `preStop()` | `AppContext::preStop()` | v2.9+；注销远程服务 |
| `stop()` 或 `@Destroy` | `AppContext::stop()` | 清理资源 |

### LifecycleBean

需要完整生命周期控制时实现 `LifecycleBean`（**仅单例有效**）：

```java
@Component
public class DemoCom implements LifecycleBean {
    @Override
    public void start() {
        // AppContext:start()，扫描与注入已完成
    }

    @Override
    public void postStart() {
        // start() 之后；此处不要再创建新的托管 Bean
    }

    @Override
    public void preStop() {
        // 如从注册中心下线
    }

    @Override
    public void stop() {
        // 清理本地资源
    }
}
```

### @Init / @Destroy

简单场景用注解即可：

```java
@Component
public class Demo {
    @Init
    public void init() { /* 初始化 */ }

    @Destroy
    public void destroy() { /* 清理 */ }
}
```

### 自动排序与循环依赖

`LifecycleBean` 按 `@Inject` 依赖自动排序（v2.2.8+）。若 Bean2 依赖 Bean1，则 Bean1 的 `start()` 先执行。循环依赖出问题时用 `@Component(index = N)` 手动指定顺序。

## 本地事件总线

Solon 内置事件总线：**强类型**、发布/订阅、默认同步派发（可传导异常，便于事务回滚）。

最短用法见 `common_patterns.md`；完整 API 见 **`api_reference.md` → EventBus**。主题型本地总线可考虑 [DamiBus](https://gitee.com/noear/damibus)。

## 配置系统

- 主配置：`src/main/resources/app.yml`（或 `app.properties`）
- 环境配置：`app-{env}.yml`，通过 `solon.env` 加载
- 编程访问：`Solon.cfg().get("key")`、`getInt("key", default)`、`getProp("prefix")`
- 类绑定：在 `@Configuration` 类上使用 `@Inject("${prefix}")`

### 代码中读取配置

```java
String val = Solon.cfg().get("key");
int port = Solon.cfg().getInt("server.port", 8080);
Props dbProps = Solon.cfg().getProp("db1");

@Inject("${server.port}")
int port;

// 等价于 Spring 的 @ConfigurationProperties
@Inject("${db1}")
@Configuration
public class Db1Config {
    public String jdbcUrl;
    public String username;
    public String password;
}
```

### 配置注入注解

| 注解 | 说明 | 作用目标 | 差异 |
|---|---|---|---|
| `@Inject("${xxx}")` | 注入配置值 | 字段、参数、类 | 有 `required` 检查（缺配置可抛异常） |
| `@BindProps(prefix="xxx")` | 绑定配置组 | 类、方法 | 支持生成模块配置元数据 |

### 变量引用

配置值可用 `${...}` 引用其它配置：

```yaml
solon.app.name: "demo"

demo.name: "${solon.app.name}"
demo.title: "${solon.app.title:}"                    # 默认空
demo.description: "${solon.app.name}/${solon.app.title:}"
```

规则：被引用变量须在解析时已存在于 `Solon.cfg()`（或同一配置块内）。

### YAML 多文档（v2.5.5+）

用 `---` 在同一文件中按环境分段：

```yaml
solon.env: pro

---
solon.env.on: pro
demo.auth:
  user: root
  password: Ssn1LeyxpQpglre0
---
solon.env.on: dev|test
demo.auth:
  user: demo
  password: 1234
```

## 插件 SPI（进阶）

插件开发、E-SPI 外部扩展、H-SPI 热插拔、配置元数据自动生成 → 见 **`plugin_spi.md`**。

## Solon 表达式（SnEL）

内置表达式语言，零依赖，约 40KB。

能力概览：

- 常量：`1`、`'name'`、`true`、`[1,2,3]`
- 变量：`name`、`map['key']`、`list[0]`
- 对象访问：`user.name`、`user.getName()`
- 运算：`+` `-` `*` `/` `%`
- 比较：`<` `<=` `>` `>=` `==` `!=`
- 逻辑：`AND` / `OR` / `NOT`（也支持 `&&` `||` `!`）
- 三元：`condition ? trueExpr : falseExpr`
- IN/LIKE：`IN`、`NOT IN`、`LIKE`、`NOT LIKE`
- 静态方法：`Math.abs(-5)`

## 与 Spring 的关键差异

| 维度 | Solon | Spring |
|---|---|---|
| 架构 | 非 Java-EE，从零自研 | 基于 Java EE / Jakarta EE |
| 启动速度 | 约 5–10 倍更快 | 较慢 |
| 包体 | 约小 50–90% | 更大 |
| 内存 | 约少 50% | 更多 |
| 并发 | TechEmpower 可高数倍 | 较低 |
| JDK | Java 8 /Users/noear 26 + GraalVM | Spring Boot 3 起 Java 17+ |
| 配置文件 | `app.yml` / `app.properties` | `application.yml` |
| 入口 | `Solon.start(App.class, args)` | `SpringApplication.run(...)` |
| DI | `@Inject` | `@Autowired` |
| 配置注入 | `@Inject("${key}")` | `@Value("${key}")` |
| 扫描 | `@Import(scanPackages=...)` | `@ComponentScan` |
| 作用域 | `@Singleton` / `@Singleton(false)` | `@Scope` |
| AOP | 仅对注册了拦截器的 public 方法按需代理 | 更广的代理范围 |
| Servlet | 可选；Context + Handler | Spring MVC 通常依赖 |
| 按名注册 | 需配置 `name` | 常按类名自动注册 |
| Setter 注入 | **不支持** | 支持 |

## 生态与工具

子项目仓库与能力总览见 **`quick_start.md` → Ecosystem Overview**。常用周边：Nami（RPC 客户端）、DamiBus（主题事件）、Snack4（JSON）、Socket.D、Liquor（动态编译）、IDEA 插件 `21380-solon`、SolonCode CLI / SolonClaw。

## 重要约束

> 注解作用域（`@Bean` / `@Inject` / `@Import` / 无 setter 注入）见上文「注解约束」。

1. `@Mapping` **不支持多路径**；路径前缀用局部网关等方式处理
2. 控制器继承支持基类上的 `@Mapping` public 方法
3. `LifecycleBean` 自动排序依赖 `@Inject`；循环依赖用 `@Component(index = N)` 解决
4. 事务见 `data_access.md`；参数校验见 `validation.md`
