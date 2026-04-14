# Core Concepts — 核心概念

> 适用场景：理解 Solon 的 IoC 容器、配置系统、插件机制、表达式语言，以及与 Spring 的区别。

## Annotations Mapping (Solon vs Spring equivalents)

| Solon | Purpose | Spring Equivalent (DO NOT USE) |
|---|---|---|
| `@SolonMain` | Entry class marker | `@SpringBootApplication` |
| `@Controller` | Web controller | `@RestController` / `@Controller` |
| `@Mapping("/path")` | URL mapping | `@RequestMapping` |
| `@Get` / `@Post` / `@Put` / `@Delete` | HTTP method filter | `@GetMapping` / `@PostMapping` etc. |
| `@Inject` | Inject bean by type | `@Autowired` |
| `@Inject("name")` | Inject bean by name | `@Qualifier` + `@Autowired` |
| `@Inject("${key}")` | Inject config value | `@Value("${key}")` |
| `@Component` | Managed component | `@Component` / `@Service` |
| `@Configuration` | Config class | `@Configuration` |
| `@Bean` | Declare bean (in @Configuration) | `@Bean` |
| `@Condition` | Conditional registration | `@ConditionalOn*` |
| `@Import` | Import classes/scan packages | `@ComponentScan` + `@Import` |
| `@Param` | Request parameter | `@RequestParam` |
| `@Body` | Request body | `@RequestBody` |
| `@Header` | Request header | `@RequestHeader` |
| `@Path` | Path variable | `@PathVariable` |
| `@Init` | Post-construct | `@PostConstruct` |

## IoC Container

- Access the container: `Solon.context()`
- Get a bean: `Solon.context().getBean(UserService.class)`
- Register a bean: `Solon.context().wrapAndPut(DemoService.class)`
- `@Bean` methods only work inside `@Configuration` classes and execute only once
- `@Inject` on parameters only works in `@Bean` methods and constructors
- `@Import` only works on the entry class or `@Configuration` classes

## Configuration System

- Main file: `src/main/resources/app.yml` (or `app.properties`)
- Environment profiles: `app-{env}.yml` loaded via `solon.env` property
- Programmatic access: `Solon.cfg().get("key")`, `Solon.cfg().getProp("prefix")`
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

// Inject into config class
@Inject("${db1}")
@Configuration
public class Db1Config {
    public String jdbcUrl;
    public String username;
    public String password;
}
```

## Plugin System (SPI)

Solon uses an SPI-based plugin system. Plugins are auto-discovered via `META-INF/solon/` service files. Adding a dependency automatically activates its plugin.

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
| AOP proxy | Only proxies public methods with registered interceptors | Proxies all public/protected methods |
| Servlet API | Optional (not required) | Required in Spring MVC |
