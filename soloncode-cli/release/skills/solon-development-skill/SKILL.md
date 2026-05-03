---
name: solon-development-skill
description: "Specialized knowledge for developing Java applications with the Solon framework. Covers core concepts, web, data, security, remoting, AI, flow orchestration, cloud-native, testing, and more. Solon is an independent Java enterprise framework (NOT based on Spring) with its own annotation system, IoC/AOP container, and plugin ecosystem."
---

# Solon Development Skill

Provide expert guidance for building Java applications with the **Solon framework**. Solon is an independent, full-scenario Java enterprise application development framework — it is **NOT compatible with Spring** and has its own architecture, annotations, and ecosystem built from scratch.

**Official website**: https://solon.noear.org
**GitHub**: https://github.com/opensolon/solon
**License**: Apache 2.0
**JDK support**: Java 8 ~ 25, GraalVM Native Image
**Current version**: 3.10.4

## Critical Rules

1. **Solon is NOT Spring.** Never mix Spring annotations (`@Autowired`, `@SpringBootApplication`, `@RestController`, `@RequestMapping`, `@Service`, `@Repository`, `@Value`, `@ComponentScan`, etc.) into Solon code. Solon has its own complete annotation set.
2. **No Spring dependencies.** Never include `spring-boot-starter-*`, `spring-*`, or any Spring artifact in Solon projects. Solon uses `org.noear` group ID.
3. **Configuration file is `app.yml`** (or `app.properties`), NOT `application.yml`.
4. **Entry point** is `Solon.start(App.class, args)`, NOT `SpringApplication.run()`.
5. **All examples must target version 3.10.4** unless the user specifies otherwise.
6. **Parent POM** is `solon-parent` with `groupId=org.noear`.
7. **中文支持.** When the user communicates in Chinese, all responses and code comments must be in Chinese.

## Scene Navigation

> 根据用户场景，读取对应的 reference 文件获取详细信息。

### 基础与核心

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 项目初始化 / Maven 配置 / 构建 / 部署 / AOT / Native Image | `references/quick_start.md` | `pom.xml`, `Solon.start`, `solon-maven-plugin`, `solon-aot`, `native-image` |
| 注解对照 / IoC 容器 / 配置系统 / 插件SPI / SnEL表达式 / Spring对比 | `references/core_concepts.md` | `@Inject`, `@Configuration`, `app.yml`, `SnEL`, `SpiLoader`, `Spring` |
| 依赖选择 (web/lib) / 模块列表 / 序列化 / 视图 / 数据访问 / ORM | `references/modules_reference.md` | `solon-web`, `solon-lib`, `SqlUtils`, `MyBatis` |
| 注解完整参考 / 配置文件属性参考 | `references/api_annotations.md` | `@Mapping`, `@Bean`, `@Param`, `server.port` |

### Web 开发

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| REST API / MVC / WebSocket / EventBus / Filter / 拦截器 / 定时任务 | `references/common_patterns.md` | `@Controller`, `@Component`, `Filter`, `WebSocket`, `EventBus`, `@Mapping`, `@Scheduled` |
| SSE 服务端推送 / Reactive 响应式 / I18n 国际化 | `references/web_advanced.md` | `SseEmitter`, `Flux`, `Mono`, `solon-web-sse`, `solon-web-rx`, `I18nUtil` |

### 安全

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 认证 / 鉴权 / CORS 跨域 / 角色权限 / 参数校验 / 配置加密 | `references/security.md` | `AuthAdapter`, `AuthProcessor`, `@CrossOrigin`, `@AuthPermissions`, `@AuthRoles`, `solon-security` |

### 数据与通信

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| RPC 调用 / Nami 声明式客户端 / Socket.D 双向通信 / 负载均衡 | `references/remoting.md` | `@NamiClient`, `@Remoting`, `Socket.D`, `LoadBalance`, `ClientSession` |

### 运维与可观测

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 日志配置 / 自定义添加器 / 日志持久化 / Slf4j | `references/logging.md` | `solon-logging`, `AppenderBase`, `PersistentAppenderBase`, `logback` |

### 测试

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 单元测试 / 集成测试 / HTTP 接口测试 / Mock | `references/testing.md` | `@SolonTest`, `HttpTester`, `@Rollback`, `@Import`, `mockito` |

### 云原生 / 微服务

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 配置中心 / 注册发现 / 事件总线 / 分布式定时任务 / 文件存储 / 熔断 / 网关 / 链路追踪 / 分布式锁 | `references/cloud_native.md` | `nacos`, `kafka`, `minio`, `xxl-job`, `CloudClient`, `@CloudJob`, `@CloudEvent`, `CloudGateway` |

### AI 开发

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| ChatModel / Tool Call / RAG / MCP / Agent / AI UI / Harness / ACP / A2A | `references/ai_development.md` | `ChatModel`, `RAG`, `MCP`, `ReActAgent`, `AiSdkStreamWrapper`, `HarnessEngine`, `ACP`, `A2A` |

### 流程编排

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| Flow 流程编排 (规则引擎 / 工作流 / 状态机 / 图编排) | `references/flow_orchestration.md` | `FlowEngine`, `FlowContext`, `Graph`, `YAML`, `StateMachine` |
