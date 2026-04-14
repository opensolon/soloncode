---
name: solon-development-skill
description: "Specialized knowledge for developing Java applications with the Solon framework This skill should be used when users want to create, configure, or troubleshoot Solon-based Java projects, including web applications, microservices, AI integrations, flow orchestration, and cloud-native services. Solon is an independent Java enterprise framework (NOT based on Spring) with its own annotation system, IoC/AOP container, and plugin ecosystem."
---

# Solon Development Skill

Provide expert guidance for building Java applications with the **Solon framework**. Solon is an independent, full-scenario Java enterprise application development framework — it is **NOT compatible with Spring** and has its own architecture, annotations, and ecosystem built from scratch.

**Official website**: https://solon.noear.org
**GitHub**: https://github.com/opensolon/solon
**License**: Apache 2.0
**JDK support**: Java 8 ~ 25, GraalVM Native Image

## Critical Rules

1. **Solon is NOT Spring.** Never mix Spring annotations (`@Autowired`, `@SpringBootApplication`, `@RestController`, `@RequestMapping`, `@Service`, `@Repository`, `@Value`, `@ComponentScan`, etc.) into Solon code. Solon has its own complete annotation set.
2. **No Spring dependencies.** Never include `spring-boot-starter-*`, `spring-*`, or any Spring artifact in Solon projects. Solon uses `org.noear` group ID.
3. **Configuration file is `app.yml`** (or `app.properties`), NOT `application.yml`.
4. **Entry point** is `Solon.start(App.class, args)`, NOT `SpringApplication.run()`.
5. **All examples must target version 3.10.0** unless the user specifies otherwise.
6. **Parent POM** is `solon-parent` with `groupId=org.noear`.
7. **中文支持.** When the user communicates in Chinese, all responses and code comments must be in Chinese.

## Scene Navigation

> 根据用户场景，读取对应的 reference 文件获取详细信息。

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 项目初始化 / Maven 配置 / 构建 / 部署 | `references/quick_start.md` | `pom.xml`, `Solon.start`, `solon-maven-plugin` |
| REST API / Service / WebSocket / EventBus / Filter / 定时任务 / 测试 | `references/common_patterns.md` | `@Controller`, `@Component`, `Filter`, `WebSocket`, `EventBus` |
| 注解对照、IoC、配置系统、插件、SnEL、Spring 对比 | `references/core_concepts.md` | `@Inject`, `@Configuration`, `app.yml`, `SnEL`, `Spring` |
| 依赖选择 (web/lib)、模块列表、序列化、视图、数据访问、ORM | `references/modules_reference.md` | `solon-web`, `solon-lib`, `SqlUtils`, `MyBatis` |
| 微服务、配置中心、消息队列、分布式任务 | `references/cloud_native.md` | `nacos`, `kafka`, `minio`, `xxl-job` |
| AI (ChatModel / Tool Call / RAG / MCP / Agent) | `references/ai_development.md` | `ChatModel`, `RAG`, `MCP`, `ReActAgent` |
| Flow 流程编排 (规则引擎 / 工作流) | `references/flow_orchestration.md` | `FlowEngine`, `FlowContext`, `Graph`, `YAML` |
| 注解完整参考、配置文件属性参考 | `references/api_annotations.md` | `@Mapping`, `@Bean`, `@Param`, `server.port` |
