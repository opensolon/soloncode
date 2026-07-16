---
name: solon-development-skill
description: "Solon Java framework expert (NOT Spring). Use for Solon apps, Solon AI (ChatModel/RAG/MCP/Agent/Harness/Talent), Solon Flow, Solon Cloud, Nami RPC, SqlUtils/MyBatis, and Solon annotations (@Mapping, @Inject, @SolonMain, @Component). Independent IoC/AOP and plugins — never use Spring annotations or spring-boot dependencies."
---

# Solon Development Skill

为使用 **Solon 框架** 构建 Java 应用提供专家级指导。Solon 是独立的全场景 Java 企业应用框架 — **与 Spring 不兼容**，拥有自研架构、注解体系与生态。

**官网**: https://solon.noear.org  
**GitHub**: https://github.com/opensolon/solon  
**License**: Apache 2.0  
**JDK**: Java 8 ~ 26，GraalVM Native Image  
**目标版本**: **4.0.3**（升级时全局替换此标记 + 复核 AI/Nami 变更段）

## Critical Rules

1. **Solon 不是 Spring。** 禁止混用 Spring 注解（`@Autowired`、`@SpringBootApplication`、`@RestController`、`@RequestMapping`、`@Service`、`@Repository`、`@Value`、`@ComponentScan` 等）。
2. **禁止 Spring 依赖。** 不要引入 `spring-boot-starter-*`、`spring-*`。Solon 坐标 groupId 为 `org.noear`。
3. **配置文件是 `app.yml`**（或 `app.properties`），**不是** `application.yml`。
4. **入口**是 `Solon.start(App.class, args)`，不是 `SpringApplication.run()`。
5. **组件注解用 `@Component`**，不要用 `@Service` / `@Repository`。
6. **示例默认目标版本 4.0.3**（除非用户指定其它版本）。
7. **Parent POM** 为 `solon-parent`（`groupId=org.noear`）。
8. **中文支持。** 用户使用中文时，回复与代码注释使用中文。
9. **不确定的 API 不要臆造。** 优先查本 skill 的 reference；仍不确定时查官网/源码，禁止用 Spring 习惯补全。

## 执行流程

1. **判定场景** → 只 `read` 下表中对应的 1～2 个 reference（**禁止一次加载全部**）。
2. **生成代码前**核对 Critical Rules（尤其：`app.yml`、`@Component`、`@Inject`、无 Spring 依赖）。
3. **数据访问**优先读 `references/data_access.md`。
4. **AI 场景分流**：
   - Chat/Tool/MCP/基础 RAG → `references/ai_chat_rag_mcp.md`
   - Loader/向量库/搜索插件表 → `references/ai_rag_plugins.md`
   - Agent/Talent/Loop → `references/ai_agent.md`
   - HarnessEngine/工具权限 → `references/ai_harness.md`
   - AI UI/ACP/A2A → `references/ai_protocol_ui.md`
5. **Cloud 分流**：Config/Discovery/Event/Job → `references/cloud_core.md`；File/Breaker/Gateway/Trace/Metric/Id/Lock → `references/cloud_ops.md`。
6. **Remoting 分流**：Nami RPC/HttpClient → `references/remoting.md`；过滤器/发现/LB → `references/remoting_filter_lb.md`；Socket.D → `references/socketd.md`。
7. **Flow 分流**：YAML/Graph/节点 → `references/flow_orchestration.md`；中断恢复/Workflow/拦截器 → `references/flow_workflow.md`。
8. **Security 分流**：Auth/CORS/Vault → `references/security.md`；参数校验 → `references/validation.md`。
9. **从 Spring 迁移** → 使用 `spring-to-solon-skill`，本 skill 仅保留精简对照。
10. 用户中文提问 → 中文回复与注释；默认版本 **4.0.3**。
11. **API 不确定时**查 reference 或源码；禁止用 Spring 习惯或臆造坐标补全（例如不存在 `solon-ai-rag` / `solon-ai-a2a`）。

## Scene Navigation（含阅读优先级）

> 根据场景只读 1～2 个文件。路径统一写完整相对路径（如 `references/quick_start.md`）。

### 基础与核心

| 优先级 | Scenario | Reference | Grep Keywords |
|---|---|---|---|
| 必读 | 项目初始化 / Maven / 构建 / 部署 / AOT / Native | `references/quick_start.md` | `pom.xml`, `Solon.start`, `solon-maven-plugin`, `solon-aot` |
| 必读 | REST / MVC / Filter / 定时任务 / 模式速查 | `references/common_patterns.md` | `@Controller`, `Filter`, `@Mapping`, `@Scheduled` |
| 按需 | 注解对照 / IoC / 配置 / SnEL / 与 Spring 差异 | `references/core_concepts.md` | `@Inject`, `@Configuration`, `app.yml`, `SnEL` |
| 进阶 | 插件 SPI / E-SPI / H-SPI / 配置元数据 | `references/plugin_spi.md` | `Plugin`, `solon-hotplug`, `SpiLoader`, `META-INF/solon` |
| 查阅 | 依赖选择 / 模块列表 / 序列化 / 视图 / ORM 索引 | `references/modules_reference.md` | `solon-web`, `solon-lib`, `solon-rpc`, `solon-job` |
| 按需 | 数据源 / SqlUtils / 事务 / MyBatis 用法 | `references/data_access.md` | `SqlUtils`, `@Transaction`, `@Db`, `DataSource` |
| 查阅 | 注解 / 配置属性 / WebSocket·EventBus·Filter | `references/api_reference.md` | `@Mapping`, `@Bean`, `EventBus`, `WebSocket` |

### Web / 安全 / 通信

| 优先级 | Scenario | Reference | Grep Keywords |
|---|---|---|---|
| 按需 | SSE / Reactive | `references/web_sse_reactive.md` | `SseEmitter`, `Flux`, `solon-web-sse`, `Mono` |
| 按需 | 国际化 / Locale / 模板 i18n | `references/i18n.md` | `I18nUtil`, `I18nService`, `solon-i18n`, `LocaleResolver` |
| 按需 | 认证 / 鉴权 / CORS / 配置加密 / 请求头安全 | `references/security.md` | `AuthAdapter`, `@CrossOrigin`, `VaultUtils` |
| 按需 | 参数校验 / 实体校验 / 校验器扩展 | `references/validation.md` | `@Valid`, `@NotNull`, `ValidatorManager` |
| 按需 | Nami RPC / 声明式 HttpClient | `references/remoting.md` | `@NamiClient`, `@Remoting`, `solon-rpc` |
| 按需 | Nami 过滤器 / 服务发现 / 负载均衡 | `references/remoting_filter_lb.md` | `NamiManager`, `LoadBalance`, `CloudLoadStrategy` |
| 按需 | Socket.D 双向通信 | `references/socketd.md` | `Socket.D`, `ClientSession`, `ServerEndpoint` |

### 运维 / 测试 / 云 / 流程

| 优先级 | Scenario | Reference | Grep Keywords |
|---|---|---|---|
| 按需 | 日志 | `references/logging.md` | `solon-logging`, `AppenderBase`, `logback` |
| 按需 | 单元/集成/HTTP 测试 / Mock | `references/testing.md` | `@SolonTest`, `HttpTester`, `@Rollback` |
| 按需 | 配置中心 / 注册发现 / 事件 / 任务 | `references/cloud_core.md` | `nacos`, `CloudClient`, `@CloudJob`, `@CloudEvent` |
| 按需 | 文件 / 熔断 / 网关 / 链路 / 指标 / ID / 锁 | `references/cloud_ops.md` | `CloudClient.file`, `@CloudBreaker`, `solon-cloud-gateway` |
| 按需 | Flow YAML / Graph / 节点类型 | `references/flow_orchestration.md` | `FlowEngine`, `FlowContext`, `Graph`, `NodeType` |
| 按需 | Flow 中断恢复 / Workflow / 拦截器 | `references/flow_workflow.md` | `toJson`, `WorkflowExecutor`, `FlowInterceptor` |

### AI

| 优先级 | Scenario | Reference | Grep Keywords |
|---|---|---|---|
| 按需 | ChatModel / Tool / 基础 RAG / MCP / GenerateModel | `references/ai_chat_rag_mcp.md` | `ChatModel`, `ToolMapping`, `MCP`, `EmbeddingModel` |
| 按需 | RAG Loader / 向量库 / 联网搜索插件 | `references/ai_rag_plugins.md` | `solon-ai-load`, `solon-ai-repo`, `PdfLoader` |
| 按需 | Agent / Talent / Loop | `references/ai_agent.md` | `ReActAgent`, `Talent`, `solon-ai-loop` |
| 按需 | HarnessEngine / 工具权限 / 子代理 / 命令 | `references/ai_harness.md` | `HarnessEngine`, `ToolPermission`, `AgentDefinition` |
| 按需 | AI UI / ACP / A2A | `references/ai_protocol_ui.md` | `AiSdkStreamWrapper`, `ACP`, `TeamProtocols.A2A` |

## 版本与变更

- 默认稳定版：**4.0.3**；JDK **8 ~ 26**
- AI / Nami 等增量细节以对应 reference 为准（AI 见 `references/ai_agent.md` 末尾「4.0.3 AI 增量要点」；Nami 默认 snack4）
- 升级版本时：全局替换 **4.0.3** 标记，并复核 AI、Nami、快捷依赖章节

## 最小模板

从零建 Web 项目可参考 `assets/minimal-web/`（`pom.xml` + `App` + `HelloController` + `app.yml`）。
