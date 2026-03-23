---
name: solon-development-skill
description: "Specialized knowledge for developing Java applications with the Solon framework (v3.9.6). This skill should be used when users want to create, configure, or troubleshoot Solon-based Java projects, including web applications, microservices, AI integrations, flow orchestration, and cloud-native services. Solon is an independent Java enterprise framework (NOT based on Spring) with its own annotation system, IoC/AOP container, and plugin ecosystem."
---

# Solon Development Skill

Provide expert guidance for building Java applications with the **Solon framework** (v3.9.6). Solon is an independent, full-scenario Java enterprise application development framework — it is **NOT compatible with Spring** and has its own architecture, annotations, and ecosystem built from scratch.

**Official website**: https://solon.noear.org  
**GitHub**: https://github.com/opensolon/solon  
**License**: Apache 2.0  
**JDK support**: Java 8 ~ 25, GraalVM Native Image

## Critical Rules

1. **Solon is NOT Spring.** Never mix Spring annotations (`@Autowired`, `@SpringBootApplication`, `@RestController`, `@RequestMapping`, `@Service`, `@Repository`, `@Value`, `@ComponentScan`, etc.) into Solon code. Solon has its own complete annotation set.
2. **No Spring dependencies.** Never include `spring-boot-starter-*`, `spring-*`, or any Spring artifact in Solon projects. Solon uses `org.noear` group ID.
3. **Configuration file is `app.yml`** (or `app.properties`), NOT `application.yml`.
4. **Entry point** is `Solon.start(App.class, args)`, NOT `SpringApplication.run()`.
5. **All examples must target version 3.9.6** unless the user specifies otherwise.
6. **Parent POM** is `solon-parent` with `groupId=org.noear`.

## Quick Start Template

### Maven pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.noear</groupId>
        <artifactId>solon-parent</artifactId>
        <version>3.9.6</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-web</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.noear</groupId>
                <artifactId>solon-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Application Entry

```java
package com.example.demo;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
```

### Controller Example

```java
package com.example.demo.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;

@Controller
public class HelloController {

    @Get
    @Mapping("/hello")
    public String hello(@Param(defaultValue = "world") String name) {
        return String.format("Hello %s!", name);
    }
}
```

### Configuration (app.yml)

```yaml
server.port: 8080
solon.app.name: "demo"
```

## Core Concepts

### Annotations Mapping (Solon vs Spring equivalents)

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

### IoC Container

- Access the container: `Solon.context()`
- Get a bean: `Solon.context().getBean(UserService.class)`
- Register a bean: `Solon.context().wrapAndPut(DemoService.class)`
- `@Bean` methods only work inside `@Configuration` classes and execute only once
- `@Inject` on parameters only works in `@Bean` methods and constructors
- `@Import` only works on the entry class or `@Configuration` classes

### Configuration System

- Main file: `src/main/resources/app.yml` (or `app.properties`)
- Environment profiles: `app-{env}.yml` loaded via `solon.env` property
- Programmatic access: `Solon.cfg().get("key")`, `Solon.cfg().getProp("prefix")`
- Config injection to class: use `@Inject("${prefix}")` on a `@Configuration` class

### Plugin System (SPI)

Solon uses an SPI-based plugin system. Plugins are auto-discovered via `META-INF/solon/` service files. Adding a dependency automatically activates its plugin.

## Shortcut Dependencies

| Artifact | Use Case |
|---|---|
| `solon-web` | **Full web development** (HTTP server + JSON + session + static files + cors + validation) |
| `solon-lib` | **Library/non-web** (IoC + AOP + data + cache + yaml config, no HTTP server) |

When building a web application, use `solon-web`. When building a non-web service or library, use `solon-lib`.

## Common Patterns

### REST API with JSON

```java
@Controller
@Mapping("/api/users")
public class UserController {
    @Inject
    UserService userService;

    @Get
    @Mapping("")
    public List<User> list() {
        return userService.findAll();
    }

    @Get
    @Mapping("/{id}")
    public User get(@Path long id) {
        return userService.findById(id);
    }

    @Post
    @Mapping("")
    public long create(@Body User user) {
        return userService.insert(user);
    }

    @Put
    @Mapping("/{id}")
    public void update(@Path long id, @Body User user) {
        user.setId(id);
        userService.update(user);
    }

    @Delete
    @Mapping("/{id}")
    public void delete(@Path long id) {
        userService.deleteById(id);
    }
}
```

### Service Component

```java
@Component
public class UserService {
    @Inject
    UserMapper userMapper;

    public List<User> findAll() {
        return userMapper.selectAll();
    }
}
```

### Configuration Bean

```java
@Configuration
public class DataSourceConfig {
    @Bean
    public DataSource dataSource(@Inject("${datasource}") HikariDataSource ds) {
        return ds;
    }
}
```

### Filter (Middleware)

```java
@Component
public class LogFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        long start = System.currentTimeMillis();
        chain.doFilter(ctx);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println(ctx.path() + " took " + elapsed + "ms");
    }
}
```

### Scheduled Task

With `solon-scheduling-simple`:

```java
@Component
public class MyJob {
    @Scheduled(cron = "0 0/5 * * * ?")
    public void run() {
        // Execute every 5 minutes
    }
}
```

### Unit Testing

With `solon-test-junit5`:

```java
@SolonTest(App.class)
public class UserControllerTest {
    @Inject
    UserService userService;

    @Test
    public void testFindAll() {
        List<User> users = userService.findAll();
        assert users != null;
    }
}
```

## Ecosystem Overview

### Sub-Projects

| Project | Repository | Description |
|---|---|---|
| **Solon** (core) | `opensolon/solon` | Core framework, IoC/AOP, Web MVC, data, security, scheduling, native |
| **Solon AI** | `opensolon/solon-ai` | LLM, RAG, MCP protocol, Agent (ReAct/Team), AI Skills |
| **Solon Flow** | `opensolon/solon-flow` | General flow orchestration (YAML/JSON), workflow, rule engine |
| **Solon Cloud** | `opensolon/solon-cloud` | Distributed: config, discovery, event, file, job, trace, breaker |
| **Solon Expression** | `opensolon/solon-expression` | SnEL — evaluation expression language |
| **Solon Admin** | `opensolon/solon-admin` | Admin monitoring server + client |
| **Solon Integration** | `opensolon/solon-integration` | Third-party ORM/RPC integrations (MyBatis, Dubbo, etc.) |
| **Solon Java17** | `opensolon/solon-java17` | Java 17+ specific modules |
| **Solon Java25** | `opensolon/solon-java25` | Java 25+ specific modules |

### AI Development (solon-ai)

Solon AI provides a full-scenario Java AI framework similar to LangChain/LlamaIndex:

- **ChatModel**: Unified LLM interface with dialect adapters (OpenAI, Ollama, Gemini, Claude, DashScope)
- **RAG**: DocumentLoader → DocumentSplitter → EmbeddingModel → Repository → RerankingModel
- **MCP**: Model Context Protocol support (server/client, streamable channel)
- **Agent**: ReActAgent (think→act→observe loop), TeamAgent (multi-agent collaboration)
- **Skills**: Pluggable AI skill system

### Flow Orchestration (solon-flow)

YAML/JSON-based flat flow orchestration for:
- Computation/task orchestration
- Business rules and decision processing
- Interruptible/resumable workflows (snapshot persistence)
- AI agent systems (ReAct, Team, Multi-Agent)

### Cloud Native (solon-cloud)

Distributed capabilities:
- **Config**: Nacos, Consul, Etcd, ZooKeeper
- **Discovery**: Nacos, Consul, Etcd, ZooKeeper
- **Event/Messaging**: Kafka, RabbitMQ, RocketMQ, FolkMQ, MQTT, Pulsar
- **File Storage**: MinIO, Aliyun OSS, AWS S3, Tencent COS
- **Distributed Job**: XXL-Job, PowerJob, Quartz

## Build & Deploy

### Package

```bash
mvn clean package -DskipTests
```

The `solon-maven-plugin` produces a fat JAR.

### Run

```bash
java -jar target/demo.jar
```

### Run with environment

```bash
java -jar demo.jar --solon.env=pro
```

### Native Image (GraalVM)

Solon supports AOT and GraalVM native image compilation via `solon-native` module.

## Detailed Reference

For detailed API reference, module lists, and annotation specifications, consult `references/api_reference.md`. Search patterns:
- `grep "Annotation"` — find annotation documentation
- `grep "Plugin"` — find plugin/module listings
- `grep "Solon Cloud"` — find cloud module details
- `grep "Solon AI"` — find AI module details
- `grep "Differences"` — find Spring vs Solon comparison
