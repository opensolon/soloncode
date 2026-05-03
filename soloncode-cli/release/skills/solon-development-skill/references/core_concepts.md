# Core Concepts — 核心概念

> 适用场景：理解 Solon 的 IoC 容器、配置系统、插件机制、表达式语言，以及与 Spring 的区别。
>
> 基于官方文档整理，目标版本 3.10.4。

## Annotations Mapping (Solon vs Spring equivalents)

| Solon | Purpose | Spring Equivalent (DO NOT USE) |
|---|---|---|
| `@SolonMain` | Entry class marker | `@SpringBootApplication` |
| `@Controller` | Web controller | `@RestController` / `@Controller` |
| `@Remoting` | Rpc remote controller | / |
| `@Mapping("/path")` | URL mapping | `@RequestMapping` |
| `@Get` / `@Post` / `@Put` / `@Delete` | HTTP method filter | `@GetMapping` / `@PostMapping` etc. |
| `@Inject` | Inject bean by type | `@Autowired` |
| `@Inject("name")` | Inject bean by name | `@Qualifier` + `@Autowired` |
| `@Inject("${key}")` | Inject config value | `@Value("${key}")` |
| `@BindProps(prefix="xxx")` | Bind properties group to bean | `@ConfigurationProperties(prefix="xxx")` |
| `@Component` | Managed component | `@Component` / `@Service` / `@Dao` / `@Repository` |
| `@Configuration` | Config class | `@Configuration` |
| `@Bean` | Declare bean (in @Configuration) | `@Bean` |
| `@Condition` | Conditional registration | `@ConditionalOn*` |
| `@Import` | Import classes / scan packages / import properties | `@ComponentScan` + `@Import` + `@PropertySource` |
| `@Singleton` | Singleton scope (default) | `@Scope("singleton")` |
| `@Singleton(false)` | Multi-instance (non-singleton) | / |
| `@Param` | Request parameter | `@RequestParam` |
| `@Body` | Request body | `@RequestBody` |
| `@Header` | Request header | `@RequestHeader` |
| `@Cookie` | Cookie value | `@CookieValue` |
| `@Path` | Path variable | `@PathVariable` |
| `@Produces` | Declare output content type | / |
| `@Consumes` | Declare input content type | / |
| `@Init` | Post-construct initialization | `@PostConstruct` |
| `@Destroy` | Pre-destroy cleanup | `@PreDestroy` |
| `@Valid` | Parameter validation (class-level) | `@Validated` |
| `@Transaction` | Transaction management | `@Transactional` |
| `@NamiClient` | Rpc client (like Feign) | `@FeignClient` |
| `@Cache` / `@CacheRemove` | Cache with tag support | `@Cacheable` / `@CacheEvict` |
| `@Rollback` | Test rollback | `@TestRollback` |

### Annotation Constraints

- `@Bean` methods only work inside `@Configuration` classes and execute only once
- `@Inject` on parameters only works in `@Bean` methods and constructors
- `@Inject` on class injection only works in `@Configuration` classes
- `@Import` only works on the entry class or `@Configuration` classes
- Solon does **not** support setter injection; use field injection, constructor parameters, or `@Bean` method parameters

## IoC Container

- Access the container: `Solon.context()`
- Get a bean: `Solon.context().getBean(UserService.class)`
- Register a bean: `Solon.context().wrapAndPut(DemoService.class)`

### IoC/AOP Core Concepts

**IOC (Inversion of Control)**, also known as DI (Dependency Injection): objects are obtained through a "container" mediator rather than direct construction. The container scans classes with `@Component`, registers them, and injects fields annotated with `@Inject`.

**AOP (Aspect-Oriented Programming)**: Solon provides AOP by building proxy layers for components. Only `public` methods are proxied, and **only when interceptors are registered** (on-demand proxy, which is one reason Solon is faster). The pointcut model is annotation-based — interceptors are registered per annotation type.

### IoC/AOP Extension Points

Solon provides four core extension mechanisms on `AppContext`:

| Extension Method | Purpose | Example |
|---|---|---|
| `beanBuilderAdd(anno, handler)` | Register bean builder | `@Controller` builder registers route handlers |
| `beanInjectorAdd(anno, handler)` | Register field injector | `@Inject` injector resolves beans/config |
| `beanInterceptorAdd(anno, interceptor, index)` | Register method interceptor | `@Transaction` interceptor wraps method calls |
| `beanExtractorAdd(anno, extractor)` | Register method extractor | `@CloudJob` extractor collects job methods |

```java
// Example: register an interceptor for a custom annotation
Solon.context().beanInterceptorAdd(AuthLogined.class, new LoginedInterceptor());

// Example: register a builder for @Controller
Solon.context().beanBuilderAdd(Controller.class, (clz, bw, anno) -> {
    new HandlerLoader(bw).load(Solon.global());
});
```

## Application Lifecycle

An application goes through a defined lifecycle from `start()` to `stop()`. The lifecycle includes:

1. **One initialization function** — `Solon.start()` lambda callback
2. **Six application events** — `AppInitEndEvent`, `AppPluginLoadEndEvent`, `AppBeanLoadEndEvent`, `AppLoadEndEvent`, `AppPrestopEndEvent`, `AppStopEndEvent`
3. **Three plugin lifecycle hooks** — `Plugin.start()`, `Plugin.prestop()`, `Plugin.stop()`
4. **Two container lifecycle hooks** — `AppContext.start()`, `AppContext.stop()`

### Lifecycle Event Sequence

```
[Init lambda] -> AppInitEndEvent -> [Plugin.start] -> AppPluginLoadEndEvent
-> [Bean scan + inject] -> AppBeanLoadEndEvent -> [AppContext.start / @Init]
-> AppLoadEndEvent -> ::Running::
-> AppPrestopEndEvent -> [Plugin.prestop] -> [AppContext.stop / @Destroy]
-> [Plugin.stop] -> AppStopEndEvent
```

**Important notes:**
- The application must complete startup before it can serve requests; do not block threads during startup
- Events before `AppBeanLoadEndEvent` must be subscribed manually before startup (e.g., in the `Solon.start()` lambda), otherwise the timing will be missed

### Event Subscription

```java
// Manual subscription (for early events)
Solon.start(App.class, args, app -> {
    app.onEvent(AppInitEndEvent.class, e -> {
        // ...
    });
});

// Annotation-based subscription (for late events like AppLoadEndEvent)
@Component
public class AppLoadEndListener implements EventListener<AppLoadEndEvent> {
    @Override
    public void onEvent(AppLoadEndEvent event) throws Throwable {
        // ...
    }
}
```

## Bean Lifecycle

Beans managed by the container follow this lifecycle:

| Phase | Description | Notes |
|---|---|---|
| `::new()` | Constructor called during bean scan | Not yet registered in container |
| `@Inject` | Field injection executed | After injection, registered in container |
| `start()` or `@Init` | `AppContext::start()` | Bean scan complete; all beans available. v2.2.8+ auto-sorts by dependency |
| `postStart()` | `AppContext::start()` (second half) | v2.9+; start network listeners etc. |
| `preStop()` | `AppContext::preStop()` | v2.9+; deregister remote services |
| `stop()` or `@Destroy` | `AppContext::stop()` | v2.2.0+; cleanup resources |

### LifecycleBean Interface

For full lifecycle control, implement `LifecycleBean`. **Only effective for singletons.**

```java
@Component
public class DemoCom implements LifecycleBean {
    @Override
    public void start() {
        // Called at AppContext:start(). All beans scanned, injection complete
    }

    @Override
    public void postStart() {
        // Called after start(). Do NOT create new managed beans here
    }

    @Override
    public void preStop() {
        // Called at AppContext:preStop(). E.g., deregister from service discovery
    }

    @Override
    public void stop() {
        // Called at AppContext:stop(). Cleanup local resources
    }
}
```

### Using @Init / @Destroy Annotations

For simple cases, use annotations instead of the interface:

```java
@Component
public class Demo {
    @Init
    public void init() { // no-arg method, name is arbitrary
        // initialization logic
    }

    @Destroy
    public void destroy() { // no-arg method, name is arbitrary
        // cleanup logic
    }
}
```

### Auto-ordering and Circular Dependencies

`LifecycleBean` beans are auto-ordered by injection dependency (v2.2.8+). When Bean2 depends on Bean1 via `@Inject`, Bean1's `start()` executes first. If circular dependency causes issues, use `@Component(index = N)` to manually specify order.

## Local Event Bus

Solon's built-in event bus is **strongly typed**, based on a **publish/subscribe** model, and uses **synchronous dispatch** (can propagate exceptions, supporting transaction rollback).

For topic-based local bus needs, consider [DamiBus](https://gitee.com/noear/damibus).

### Custom Event Usage

```java
// 1. Define event model
@Getter
public class HelloEvent {
    private String name;
    public HelloEvent(String name) {
        this.name = name;
    }
}

// 2. Subscribe (annotation mode)
@Component
public class HelloEventListener implements EventListener<HelloEvent> {
    @Override
    public void onEvent(HelloEvent event) throws Throwable {
        System.out.println(event.getName());
    }
}

// 2. Subscribe (manual mode)
EventBus.subscribe(HelloEvent.class, event -> {
    System.out.println(event.getName());
});

// 3. Publish
EventBus.publish(new HelloEvent("world"));          // sync, can propagate exceptions
EventBus.publishAsync(new HelloEvent("world"));     // async, cannot propagate exceptions
```

## Configuration System

- Main file: `src/main/resources/app.yml` (or `app.properties`)
- Environment profiles: `app-{env}.yml` loaded via `solon.env` property
- Programmatic access: `Solon.cfg().get("key")`, `Solon.cfg().getInt("key", default)`, `Solon.cfg().getProp("prefix")`
- Config injection to class: use `@Inject("${prefix}")` on a `@Configuration` class

### Configuration Access in Code

```java
// Get single value
String val = Solon.cfg().get("key");
int port = Solon.cfg().getInt("server.port", 8080);

// Get property group
Props dbProps = Solon.cfg().getProp("db1");

// Inject into field
@Inject("${server.port}")
int port;

// Inject into config class (equivalent to @ConfigurationProperties)
@Inject("${db1}")
@Configuration
public class Db1Config {
    public String jdbcUrl;
    public String username;
    public String password;
}
```

### Configuration Injection Annotations

| Annotation | Description | Target | Difference |
|---|---|---|---|
| `@Inject("${xxx}")` | Inject config value | Field, parameter, class | Has `required` check (throws exception when config missing) |
| `@BindProps(prefix="xxx")` | Bind properties group | Class, method | Supports generating module config metadata |

### Variable References

Config values can reference other config variables using `${...}` syntax:

```yaml
solon.app.name: "demo"

demo.name: "${solon.app.name}"
demo.title: "${solon.app.title:}"                    # default empty
demo.description: "${solon.app.name}/${solon.app.title:}"
```

Rule: variables can be referenced only if they already exist in `Solon.cfg()` at parse time (or within the same config block).

### YAML Multi-Document Support (v2.5.5+)

Use `---` to define multiple profile-gated sections in a single YAML file:

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

## Plugin System (SPI)

Solon uses an SPI-based plugin system. Plugins participate in the application lifecycle and provide extension capabilities. Adding a Maven dependency automatically activates its plugin.

### Plugin Interface

```java
public interface Plugin {
    void start(AppContext context) throws Throwable;  // Called after app init
    default void prestop() throws Throwable {}         // Called before ::stop
    default void stop() throws Throwable {}            // Called at Solon::stop
}
```

### Plugin Discovery

1. Create a plugin implementation class (convention: `XxxSolonPlugin`, placed in `integration` package, no injection allowed):

```java
public class DemoSolonPlugin implements Plugin {
    @Override
    public void start(AppContext context) {
        context.beanInterceptorAdd(AuthLogined.class, new LoginedInterceptor());
    }
}
```

2. Declare in properties file at `META-INF/solon/{packname}.properties` (filename must be globally unique):

```properties
solon.plugin=org.example.DemoSolonPlugin
solon.plugin.priority=1    # higher = earlier, default 0
```

3. On startup, Solon scans all `.properties` files under `META-INF/solon/`, discovers and sorts plugins.

### Plugin Exclusion

```yaml
# Via configuration
solon.plugin.exclude:
  - "{PluginImpl}"
```

```java
// Via code
Solon.start(App.class, args, app -> {
    app.pluginExclude(PluginImpl.class);
});
```

### Plugin Naming Convention

| Pattern | Meaning |
|---|---|
| `solon-*` | Internal framework plugin |
| `*-solon-plugin` | External adapter plugin |
| `*-solon-ai-plugin` | AI adapter plugin |
| `*-solon-cloud-plugin` | Cloud adapter plugin |

### Plugin Extension Mechanisms

Solon SPI goes beyond simple discovery — plugins can programmatically extend the framework at startup:

- Register annotation interceptors (e.g., `@Transaction`, `@Cache`)
- Register bean builders (e.g., `@Controller` handler loading)
- Register field injectors (e.g., custom injection logic)
- Register method extractors (e.g., `@CloudJob` job collection)

### E-SPI (External SPI) — Plugin External Extension Mechanism

E-SPI solves extension needs when deploying as fatjar. It allows loading plugin jars and config files from **outside the application classpath** (i.e., an external directory). `.properties` and `.yml` files are loaded as extension configs; `.jar` and `.zip` files are loaded as plugin packages.

#### Key Characteristics

- All plugins **share** ClassLoader, AppContext, and configuration
- Plugins can be packaged independently (loaded externally) or bundled with the main app — "split" or "merge" freely
- Updating external plugins or config files **requires restarting** the main service
- E-SPI is provided by the Solon core — **no additional dependencies needed**

#### ClassLoader Sharing

E-SPI is implemented via `AppClassLoader:addJar(URL | File)`. On startup, Solon automatically loads from the configured extension directory:
- All `.jar` and `.zip` packages
- All `.properties` and `.yml` configuration files

Programmatic API for custom loading:

```java
@SolonMain
public class Application {
    public static void main(String[] args) throws Exception {
        Solon.start(Application.class, args, app -> {
            // Load jar package
            app.classLoader().addJar(new File("/demo.jar"));

            // Load properties file
            app.cfg().loadAdd(new File("/demo.yml"));
        });
    }
}
```

#### Configuration

Declare the extension directory in `app.yml`:

```yaml
# Extension directory (directory must be manually created)
solon.extend: "demo_ext"

# Extension directory (prefix "!" auto-creates the directory)
solon.extend: "!demo_ext"
```

#### File Layout Example

```
demo.jar
demo_ext/_db.properties       # external config file
demo_ext/demo_user.jar         # external plugin package
demo_ext/demo_order.jar        # external plugin package
```

#### Packaging Notes

- Either package the plugin as a fatjar (using `maven-assembly-plugin`)
- Or include the plugin's dependencies in the main app (recommended for shared/common dependencies)
- Best practice: put common dependencies in the main app packaging; mark them as `<optional>` in the plugin's `pom.xml`

### H-SPI (Hot-SPI) — Plugin Hot-Pluggable Management

H-SPI is an advanced extension mechanism for production use. Compared to E-SPI, H-SPI focuses on **isolation**, **hot-swap**, and **management**. Each business module is developed as a unit and packaged as an independent plugin.

> Requires dependency: `solon-hotplug`

#### Key Characteristics

- Each plugin has its **own isolated** ClassLoader, AppContext, and configuration — fully isolated
  - Can still access main program resources via `Solon.app()`, `Solon.cfg()`, `Solon.context()`, etc.
- Plugins can be packaged independently or bundled with the main app
- Updating a plugin does **not require restarting** the main service — hot update!
- All resources must be self-managed; resources added in `start()` **must be removed** in `stop()`
- Inter-plugin communication should use EventBus with weak-typed data (Map, JsonString). Consider using [DamiBus](https://gitee.com/noear/dami) for decoupled communication

#### ClassLoader Isolation Rules

| Relationship | Access Rule |
|---|---|
| Parent ClassLoader (public resources) | Child can access classes/resources; if anything is registered, it must be unregistered in plugin `stop()` |
| Sibling ClassLoaders | Cannot access each other's classes/resources; use EventBus for interaction with weak-typed data or parent ClassLoader entity classes |

#### Plugin Development Example

```java
public class Plugin1Impl implements Plugin {
    AppContext context;
    StaticRepository staticRepository;

    @Override
    public void start(AppContext context) {
        this.context = context;

        // Add own config file
        context.cfg().loadAdd("demo1011.plugin1.yml");
        // Scan own beans
        context.beanScan(Plugin1Impl.class);

        // Add own static file repository (register classloader)
        staticRepository = new ClassPathStaticRepository(context.getClassLoader(), "plugin1_static");
        StaticMappings.add("/html/", staticRepository);
    }

    @Override
    public void stop() throws Throwable {
        // Remove HTTP handlers (use prefix for easy removal)
        Solon.app().router().remove("/user");

        // Remove scheduled jobs (use a solution that supports manual removal)
        JobManager.getInstance().jobRemove("job1");

        // Remove event subscriptions
        context.beanForeach(bw -> {
            if (bw.raw() instanceof EventListener) {
                EventBus.unsubscribe(bw.raw());
            }
        });

        // Remove static file repository
        StaticMappings.remove(staticRepository);
    }
}
```

When using template rendering in H-SPI plugins, be mindful of ClassLoader context:

```java
public class BaseController implements Render {
    // Must consider the ClassLoader where templates reside
    static final FreemarkerRender viewRender = new FreemarkerRender(BaseController.class.getClassLoader());

    @Override
    public void render(Object data, Context ctx) throws Throwable {
        if (data instanceof Throwable) {
            throw (Throwable) data;
        }
        if (data instanceof ModelAndView) {
            viewRender.render(data, ctx);
        } else {
            ctx.render(data);
        }
    }
}
```

#### Plugin Management

With `solon-hotplug` dependency, plugins can be managed (install/uninstall/update at runtime). Plugins can further be repository-based and platform-based.

### E-SPI vs H-SPI Comparison

| Aspect | E-SPI | H-SPI |
|---|---|---|
| ClassLoader | Shared | Isolated (each plugin has its own) |
| AppContext | Shared | Isolated |
| Hot update | No (requires restart) | Yes |
| Extra dependency | None (core support) | `solon-hotplug` |
| Use case | Simple external extension | Production hot-swap, module isolation |

### Plugin SPI Configuration Hint Metadata

Plugin packages can provide configuration hint metadata for IDE support (auto-completion, documentation). The metadata file is placed at:

```
resource/META-INF/solon/solon-configuration-metadata.json
```

#### File Format

The JSON file has two top-level arrays: `properties` and `hints`.

**properties** — describes available configuration properties:

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | Yes | Full property name in lowercase dot-separated form (e.g., `server.port`) |
| `type` | string | Yes | Data type (e.g., `java.lang.String`, `java.lang.Integer`) or full generic type |
| `defaultValue` | object | No | Default value |
| `description` | string | No | Short human-readable description |

**hints** — provides value suggestions for properties:

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | Yes | Full property name (must match a property) |
| `values` | array | No | List of possible values |
| `values[].value` | object | Yes | The value |
| `values[].description` | string | No | Description of the value |

#### Complete Example

```json
{
  "properties": [
    {
      "name": "server.port",
      "type": "java.lang.Integer",
      "defaultValue": "8080",
      "description": "服务端口"
    },
    {
      "name": "cache.driverType",
      "type": "java.lang.String",
      "defaultValue": "local",
      "description": "缓存驱动类型"
    },
    {
      "name": "beetlsql.inters",
      "type": "java.lang.String[]",
      "description": "数据管理插件列表"
    }
  ],
  "hints": [
    {
      "name": "cache.driverType",
      "values": [
        { "value": "local", "description": "本地缓存" },
        { "value": "redis", "description": "Redis缓存" },
        { "value": "memcached", "description": "Memcached缓存" }
      ]
    }
  ]
}
```

### Plugin SPI Configuration Metadata Auto-Processing

Writing `solon-configuration-metadata.json` manually is tedious. Using `@BindProps` annotation combined with the `solon-configuration-processor` compiler plugin, metadata files are **auto-generated** at compile time.

#### Dependency Setup

Maven:

```xml
<dependencies>
    <dependency>
        <groupId>org.noear</groupId>
        <artifactId>solon-configuration-processor</artifactId>
        <scope>provided</scope> <!-- Must be provided scope -->
    </dependency>
</dependencies>

<!-- After JDK 25, also add annotationProcessorPaths -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.noear</groupId>
                <artifactId>solon-configuration-processor</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Gradle:

```gradle
compileOnly("org.noear:solon-configuration-processor")
annotationProcessor("org.noear:solon-configuration-processor")
```

#### Usage Examples

Class-based property binding:

```java
@BindProps(prefix = "server")
@Configuration
public class ServerProps {
    private Integer port;
    private String host;
}
```

Method-based property binding:

```java
public class ServerProps {
    private Integer port;
    private String host;
}

@Configuration
public class ServerConfig {
    @BindProps(prefix = "server")
    @Bean
    public ServerProps serverProps() {
        return new ServerProps();
    }
}
```

## Solon Expression (SnEL)

SnEL is Solon's built-in expression language for evaluation. Zero dependency, ~40KB.

### Capabilities

- Constants: `1`, `'name'`, `true`, `[1,2,3]`
- Variables: `name`, `map['key']`, `list[0]`
- Object access: `user.name`, `user.getName()`
- Arithmetic: `+`, `-`, `*`, `/`, `%`
- Comparison: `<`, `<=`, `>`, `>=`, `==`, `!=`
- Logic: `AND`, `OR`, `NOT` (also `&&`, `||`, `!`)
- Ternary: `condition ? trueExpr : falseExpr`
- IN/LIKE: `IN`, `NOT IN`, `LIKE`, `NOT LIKE`
- Static method calls: `Math.abs(-5)`

## Key Differences from Spring

| Aspect | Solon | Spring |
|---|---|---|
| Architecture | Non-Java-EE, built from scratch | Based on Java EE / Jakarta EE |
| Startup speed | 5-10x faster | Slower |
| Package size | 50-90% smaller | Larger |
| Memory | ~50% less | More |
| Concurrency | Up to 700% higher (TechEmpower) | Lower |
| JDK support | Java 8 ~ 25 + GraalVM | Java 17+ (Spring Boot 3) |
| Config file | `app.yml` / `app.properties` | `application.yml` / `application.properties` |
| Entry point | `Solon.start(App.class, args)` | `SpringApplication.run(App.class, args)` |
| DI annotation | `@Inject` | `@Autowired` |
| Config inject | `@Inject("${key}")` | `@Value("${key}")` |
| Component scan | `@Import(scanPackages=...)` | `@ComponentScan` |
| Bean scope | `@Singleton` / `@Singleton(false)` | `@Scope("singleton"/"prototype")` |
| AOP proxy | Only proxies public methods with registered interceptors (on-demand) | Proxies all public/protected methods |
| Servlet API | Optional (not required); Context + Handler architecture | Required in Spring MVC |
| Proxy scope | Only public methods, on-demand | Public and protected methods |
| Container registration | Must configure `name` to register by name | Auto-registers by class name |
| Setter injection | Not supported | Supported |

## Ecosystem

| Component | Description |
|---|---|
| Solon | Core framework |
| Solon AI | AI/LLM development module |
| Solon Cloud | Distributed development suite |
| Nami | Rpc client (similar to Feign, supports HTTP and Socket.D) |
| Liquor | Dynamic compilation as a service |
| DamiBus | Topic-based local event bus |
| SnackJson | JSON library (snack4) |
| Socket.D | Network protocol framework |

## Development Tools

| Tool | Description |
|---|---|
| Idea Plugin (21380-solon) | IntelliJ IDEA plugin for Solon |
| SolonCode CLI | CLI code generation tool |
| SolonClaw | Enhanced development tooling |

## Important Constraints

1. `@Bean` methods only work inside `@Configuration` classes and execute only once
2. `@Inject` parameter injection only works in `@Bean` methods and constructors
3. `@Inject` class injection only works in `@Configuration` classes
4. `@Import` only works on the entry class or `@Configuration` classes
5. Solon does **not** support setter injection — use field injection, constructor parameters, or `@Bean` method parameters
6. Solon's `@Mapping` does not support multi-path mapping; use local gateway for path prefixes instead
7. Solon controller inheritance supports base class `@Mapping` public methods
8. `LifecycleBean` auto-ordering is based on `@Inject` dependency; circular dependencies will throw exceptions — resolve via `@Component(index = N)`
9. `@Transaction` uses the same propagation and isolation as Spring, but rollback does not require specifying exception types
10. `@Valid` supports batch parameter validation with annotations like `@NotNull`, `@Pattern` directly on handler method parameters
