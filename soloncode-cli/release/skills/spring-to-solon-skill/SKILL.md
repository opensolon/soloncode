---
name: spring-to-solon-skill
description: "Expert guidance for migrating Java projects from Spring Boot / Spring Cloud to the Solon framework. Provides comprehensive annotation mapping, dependency replacement, architecture differences, and step-by-step migration strategies for each layer (IoC, Web, Data, Cloud, Testing)."
---

# Spring to Solon Migration Skill

Provide expert guidance for migrating Java projects from **Spring Boot** / **Spring Cloud** to the **Solon framework**. Solon is an independent Java enterprise framework (NOT based on Spring) that offers similar developer experience but with different annotations, architecture, and ecosystem.

**Official comparison**: https://solon.noear.org/article/compare-springboot
**Official cloud comparison**: https://solon.noear.org/article/compare-springcloud
**Solon website**: https://solon.noear.org
**Current version**: 3.10.x

## Critical Migration Rules

1. **Solon is NOT Spring.** Never mix Spring annotations with Solon annotations. Replace ALL Spring imports.
2. **No Spring dependencies.** Remove all `spring-boot-starter-*`, `spring-*` artifacts. Solon uses `org.noear` group ID.
3. **Configuration file** is `app.yml` (or `app.properties`), NOT `application.yml`.
4. **Entry point** is `Solon.start(App.class, args)`, NOT `SpringApplication.run()`.
5. **Parent POM** is `solon-parent` with `groupId=org.noear`.
6. **Package scan** uses `@Import` on main class, NOT `@ComponentScan`.
7. **No setter injection.** Solon only supports field injection and constructor injection.
8. **All examples target Solon 3.10.x** unless specified otherwise.
9. **中文支持.** When the user communicates in Chinese, all responses and code comments must be in Chinese.

## Migration Scene Navigation

> 根据迁移场景，读取对应的 reference 文件获取详细信息。

### 快速对照

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 注解替换对照表 / 完整的 Spring→Solon 注解映射 | `references/annotation_mapping.md` | `@Autowired`, `@Inject`, `@RequestMapping`, `@Mapping`, `@Service`, `@Component`, `@Value` |
| Maven 依赖替换 / starter → solon 插件 / POM 改造 | `references/dependency_mapping.md` | `pom.xml`, `spring-boot-starter`, `solon-web`, `solon-lib`, `solon-parent` |

### 分层迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| IoC 容器 / Bean 生命周期 / AOP / 配置系统 / 作用域 | `references/core_concepts_migration.md` | `@Configuration`, `@Bean`, `LifecycleBean`, `@Init`, `@Destroy`, `@Condition`, `app.yml` |
| Web 层 / Controller / Filter / Interceptor / CORS / SSE / WebSocket | `references/web_layer_migration.md` | `@Controller`, `@RestController`, `@Mapping`, `Filter`, `RouterInterceptor`, `Context`, `SessionState` |
| 数据访问 / ORM / MyBatis / 事务 / 缓存 / 多数据源 | `references/data_access_migration.md` | `@Transaction`, `@Cache`, `@Db`, `SqlUtils`, `MyBatis`, `HikariDataSource` |

### 微服务迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| Spring Cloud → Solon Cloud / 注册发现 / 配置中心 / 网关 / 熔断 / 事件 / 任务 / 链路追踪 | `references/cloud_migration.md` | `Nacos`, `Eureka`, `Feign`, `NamiClient`, `CloudClient`, `@CloudEvent`, `@CloudJob`, `CloudGateway` |

### 测试迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 单元测试 / 集成测试 / Mock / HTTP 测试 | `references/testing_migration.md` | `@SpringBootTest`, `@SolonTest`, `HttpTester`, `MockMvc`, `@Rollback` |

## Quick Migration Checklist

### Step 1: POM 改造
- Replace `spring-boot-starter-parent` → `solon-parent`
- Replace Spring starters → Solon plugins
- Add `solon-maven-plugin`
- See: `references/dependency_mapping.md`

### Step 2: 配置文件
- Rename `application.yml` → `app.yml`
- Adjust configuration key names
- See: `references/core_concepts_migration.md`

### Step 3: 启动类
- Replace `@SpringBootApplication` → `@SolonMain`
- Replace `SpringApplication.run()` → `Solon.start()`
- See: `references/annotation_mapping.md`

### Step 4: 注解替换
- Replace all Spring annotations with Solon equivalents
- See: `references/annotation_mapping.md`

### Step 5: Web 层改造
- Replace `@RestController` → `@Controller`
- Replace `@RequestMapping` → `@Mapping`
- Handle `HttpServletRequest/Response` → `Context`
- See: `references/web_layer_migration.md`

### Step 6: 数据层改造
- Replace Spring transaction/cache annotations
- See: `references/data_access_migration.md`

### Step 7: 微服务改造 (如适用)
- Replace Spring Cloud components with Solon Cloud equivalents
- See: `references/cloud_migration.md`

### Step 8: 测试改造
- Replace `@SpringBootTest` → `@SolonTest`
- See: `references/testing_migration.md`
