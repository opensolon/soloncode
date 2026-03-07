# Solon API Reference & Ecosystem Details

> Version: 3.9.3 | Official: https://solon.noear.org | GitHub: https://github.com/opensolon

## 1. Core Annotations Reference

### Entry & Configuration

| Annotation | Target | Description |
|---|---|---|
| `@SolonMain` | Class | Mark the application entry class |
| `@Configuration` | Class | Configuration class for building beans via `@Bean` methods |
| `@Bean` | Method | Declare a bean in `@Configuration` class (runs once) |
| `@Component` | Class | General managed component (supports proxy) |
| `@Controller` | Class | Web MVC controller (use with `@Mapping`) |
| `@Remoting` | Class | Remote service endpoint |
| `@Import` | Class | Import classes, scan packages, or load config profiles |
| `@Condition` | Class/Method | Conditional bean registration |

### Dependency Injection

| Annotation | Target | Description |
|---|---|---|
| `@Inject` | Field/Param | Inject bean by type |
| `@Inject("name")` | Field/Param | Inject bean by name |
| `@Inject("${key}")` | Field/Param/Type | Inject configuration value |
| `@BindProps(prefix="xx")` | Type | Bind property set to class |
| `@Singleton` | Class | Singleton scope (default) |
| `@Singleton(false)` | Class | Non-singleton (new instance each injection) |

### Web MVC

| Annotation | Target | Description |
|---|---|---|
| `@Mapping("/path")` | Class/Method | URL path mapping |
| `@Get` | Method/Type | Restrict to GET (use with `@Mapping`) |
| `@Post` | Method/Type | Restrict to POST |
| `@Put` | Method/Type | Restrict to PUT |
| `@Delete` | Method/Type | Restrict to DELETE |
| `@Patch` | Method/Type | Restrict to PATCH |
| `@Param` | Parameter | Request parameter binding with options |
| `@Header` | Parameter | Bind request header |
| `@Cookie` | Parameter | Bind cookie value |
| `@Body` | Parameter | Bind request body |
| `@Path` | Parameter | Bind path variable |
| `@Consumes` | Method | Specify consumed content type |
| `@Produces` | Method | Specify produced content type |
| `@Multipart` | Method | Declare multipart request |

### Lifecycle

| Annotation/Interface | Description |
|---|---|
| `@Init` | Component init method (like `@PostConstruct`) |
| `@Destroy` | Component destroy method (like `@PreDestroy`) |
| `LifecycleBean` | Interface with `start()` and `stop()` methods |
| `AppLoadEndEvent` | Event fired after all loading completes |

### AOP & Interceptors

| Annotation | Description |
|---|---|
| `@Around` | Method interceptor (AOP around advice) |

## 2. Configuration File Reference

Solon uses `app.yml` (or `app.properties`) as the main configuration file located in `src/main/resources/`.

### Core Properties

```yaml
# Server configuration
server.port: 8080

# Application name
solon.app.name: "my-app"
solon.app.group: "my-group"

# Environment profiles
solon.env: dev  # loads app-dev.yml additionally

# Debug mode
solon.debug: true

# Logging
solon.logging.logger.root.level: INFO

# Virtual threads (Java 21+)
solon.threads.virtual.enable: true
```

### Multi-Environment Configuration

- `app.yml` — base config (always loaded)
- `app-dev.yml` — loaded when `solon.env=dev`
- `app-test.yml` — loaded when `solon.env=test`
- `app-pro.yml` — loaded when `solon.env=pro`

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

## 3. Project Module Reference

### Shortcut Dependencies

| Artifact | Description | Includes |
|---|---|---|
| `solon-web` | Full web development (recommended for web apps) | solon-lib + smarthttp + snack4-json + session + staticfiles + cors + validation |
| `solon-lib` | Core library without web server | solon + handle + data + cache + proxy + yaml + config |

### Server Implementations

| Artifact | Type | Size |
|---|---|---|
| `solon-server-smarthttp` | AIO (default in solon-web) | ~0.7MB |
| `solon-server-jdkhttp` | BIO (JDK built-in) | ~0.2MB |
| `solon-server-jetty` | NIO (Servlet API) | ~2.2MB |
| `solon-server-undertow` | NIO (Servlet API) | ~4.5MB |
| `solon-server-tomcat` | NIO (Servlet API) | varies |
| `solon-server-vertx` | Event-driven | varies |

### Serialization Options

| Artifact | Format |
|---|---|
| `solon-serialization-snack4` | JSON (default in solon-web) |
| `solon-serialization-jackson` | JSON (Jackson) |
| `solon-serialization-jackson3` | JSON (Jackson 3.x) |
| `solon-serialization-fastjson2` | JSON (Fastjson2) |
| `solon-serialization-gson` | JSON (Gson) |
| `solon-serialization-jackson-xml` | XML |
| `solon-serialization-hessian` | Binary (Hessian) |
| `solon-serialization-fury` | Binary (Fury) |
| `solon-serialization-protostuff` | Binary (Protobuf) |

### View Templates

| Artifact | Engine |
|---|---|
| `solon-view-freemarker` | FreeMarker |
| `solon-view-thymeleaf` | Thymeleaf |
| `solon-view-enjoy` | Enjoy |
| `solon-view-velocity` | Velocity |
| `solon-view-beetl` | Beetl |

### Data Access

| Artifact | Description |
|---|---|
| `solon-data` | Core data support (transaction, datasource) |
| `solon-data-sqlutils` | SQL utility tools |
| `solon-cache-caffeine` | Caffeine cache |
| `solon-cache-jedis` | Redis cache (Jedis) |
| `solon-cache-redisson` | Redis cache (Redisson) |

### ORM Integration (solon-integration)

| Plugin | ORM |
|---|---|
| `mybatis-solon-plugin` | MyBatis |
| `mybatis-plus-solon-plugin` | MyBatis-Plus |
| `mybatis-flex-solon-plugin` | MyBatis-Flex |
| `hibernate-solon-plugin` | Hibernate |
| `wood-solon-plugin` | Wood |
| `sqltoy-solon-plugin` | SQLToy |
| `bean-searcher-solon-plugin` | Bean Searcher |

### Scheduling

| Artifact | Description |
|---|---|
| `solon-scheduling-simple` | Simple built-in scheduler |
| `solon-scheduling-quartz` | Quartz integration |

### Security

| Artifact | Description |
|---|---|
| `solon-security-validation` | Parameter validation |
| `solon-security-auth` | Authentication & authorization |
| `solon-security-vault` | Secrets vault |

### Testing

| Artifact | Description |
|---|---|
| `solon-test-junit5` | JUnit 5 integration |
| `solon-test-junit4` | JUnit 4 integration |

## 4. Solon Cloud Modules

### Service Registration & Configuration

| Plugin | Backend |
|---|---|
| `nacos2-solon-cloud-plugin` | Nacos 2.x |
| `nacos3-solon-cloud-plugin` | Nacos 3.x |
| `consul-solon-cloud-plugin` | Consul |
| `etcd-solon-cloud-plugin` | Etcd |
| `zookeeper-solon-cloud-plugin` | ZooKeeper |

### Event / Messaging

| Plugin | Backend |
|---|---|
| `kafka-solon-cloud-plugin` | Kafka |
| `rabbitmq-solon-cloud-plugin` | RabbitMQ |
| `rocketmq-solon-cloud-plugin` | RocketMQ |
| `rocketmq5-solon-cloud-plugin` | RocketMQ 5.x |
| `folkmq-solon-cloud-plugin` | FolkMQ |
| `mqtt-solon-cloud-plugin` | MQTT |
| `pulsar-solon-cloud-plugin` | Pulsar |

### File Storage

| Plugin | Backend |
|---|---|
| `minio-solon-cloud-plugin` | MinIO |
| `aliyun-oss-solon-cloud-plugin` | Aliyun OSS |
| `aws-s3-solon-cloud-plugin` | AWS S3 |
| `qcloud-cos-solon-cloud-plugin` | Tencent COS |

### Distributed Job

| Plugin | Backend |
|---|---|
| `xxl-job-solon-cloud-plugin` | XXL-Job |
| `powerjob-solon-cloud-plugin` | PowerJob |
| `quartz-solon-cloud-plugin` | Quartz |

## 5. Solon AI Modules

### Core

| Artifact | Description |
|---|---|
| `solon-ai` | Core AI module (ChatModel, EmbeddingModel, etc.) |
| `solon-ai-mcp` | MCP protocol support |
| `solon-ai-agent` | Agent framework (ReActAgent, TeamAgent) |
| `solon-ai-flow` | AI + Flow integration |

### LLM Dialects

| Artifact | Provider |
|---|---|
| `solon-ai-dialect-openai` | OpenAI / compatible APIs (DeepSeek, etc.) |
| `solon-ai-dialect-ollama` | Ollama |
| `solon-ai-dialect-gemini` | Google Gemini |
| `solon-ai-dialect-claude` | Anthropic Claude |
| `solon-ai-dialect-dashscope` | Alibaba DashScope |

### RAG Document Loaders

| Artifact | Format |
|---|---|
| `solon-ai-load-pdf` | PDF |
| `solon-ai-load-word` | Word |
| `solon-ai-load-excel` | Excel |
| `solon-ai-load-html` | HTML |
| `solon-ai-load-markdown` | Markdown |
| `solon-ai-load-ppt` | PowerPoint |

### RAG Vector Repositories

| Artifact | Backend |
|---|---|
| `solon-ai-repo-milvus` | Milvus |
| `solon-ai-repo-pgvector` | PgVector |
| `solon-ai-repo-elasticsearch` | Elasticsearch |
| `solon-ai-repo-redis` | Redis |
| `solon-ai-repo-qdrant` | Qdrant |
| `solon-ai-repo-chroma` | Chroma |
| `solon-ai-repo-weaviate` | Weaviate |

## 6. Solon Flow

### Core Concepts

| Concept | Interface | Description |
|---|---|---|
| Graph | `Graph`, `GraphSpec` | Flow chart definition |
| Node | `Node`, `NodeSpec` | Flow node (can have task and condition) |
| Link | `Link`, `LinkSpec` | Flow connection (can have condition) |
| FlowEngine | `FlowEngine` | Execute graphs |
| FlowDriver | `FlowDriver` | Customizable driver |
| FlowContext | `FlowContext` | Execution context |

### Flow YAML Format

```yaml
id: "flow1"
layout:
  - { id: "n1", type: "start", link: "n2" }
  - { id: "n2", type: "activity", link: "n3", task: "handler.process()" }
  - { id: "n3", type: "end" }
```

## 7. Solon Expression (SnEL)

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

## 8. Key Differences from Spring

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
