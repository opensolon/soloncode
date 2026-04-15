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

### IoC / AOP / 配置

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| IoC 容器 / DI / 组件注册 / 作用域 / 生命周期 / AOP / 事件 / 包扫描 | `references/ioc_aop_migration.md` | `@Inject`, `@Configuration`, `@Bean`, `LifecycleBean`, `@Init`, `@Destroy`, `@Aspect`, `EventBus` |
| 配置文件 / 属性注入 / 配置类 / Bean 定义 / 条件装配 | `references/config_system_migration.md` | `app.yml`, `@Inject("${")`, `@Configuration`, `@Bean`, `@Condition`, `solon.config` |

### Web 层迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| Controller / 请求参数 / 返回值 / Context 上下文 | `references/web_controller_migration.md` | `@Controller`, `@RestController`, `@Mapping`, `@Param`, `@Body`, `Context` |
| Filter / Interceptor / 全局异常 / CORS | `references/web_filter_interceptor_migration.md` | `Filter`, `RouterInterceptor`, `@ControllerAdvice`, `GlobalExceptionFilter`, `@CrossOrigin` |
| 文件上传下载 / SSE / WebSocket / 参数校验 / 会话 | `references/web_advanced_migration.md` | `UploadedFile`, `SseEmitter`, `WebSocket`, `@Valid`, `SessionState` |

### 数据访问迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 数据源 / MyBatis / MyBatis Plus / JPA / 多数据源 | `references/datasource_orm_migration.md` | `@Db`, `SqlUtils`, `MyBatis`, `JPA`, `HikariDataSource`, `@Mapper` |
| 事务 / 缓存 / Redis / 陷阱清单 | `references/transaction_cache_migration.md` | `@Transaction`, `@Cache`, `Redis`, `@CacheRemove`, `CacheService` |

### 微服务迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 注册发现 / 配置中心 / 陷阱清单 | `references/cloud_discovery_config_migration.md` | `Nacos`, `Eureka`, `CloudClient`, `@CloudConfig`, `discovery`, `config` |
| RPC (Feign→Nami) / 网关 / 事件消息 | `references/cloud_gateway_rpc_migration.md` | `Feign`, `NamiClient`, `CloudGateway`, `@CloudEvent`, `Gateway` |
| 断路器 / 任务调度 / 链路追踪 / 分布式锁/ID/文件/名单/监控/日志 | `references/cloud_observability_migration.md` | `Breaker`, `@CloudJob`, `Trace`, `CloudLock`, `CloudId`, `CloudFile`, `Metric` |

### 测试迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 依赖 / 测试类 / HTTP 测试 / 配置 / Mock / 事务回滚 | `references/test_basics_migration.md` | `@SpringBootTest`, `@SolonTest`, `HttpTester`, `Mock`, `@TestRollback` |
| 切面 / 条件 / 生命周期 / WebFlux / 数据层 / 完整示例 / 陷阱 | `references/test_advanced_migration.md` | `@Rollback`, `WebFlux`, `@SolonTest`, `@EnableAutoConfiguration`, `assert` |

## Quick Migration Checklist

### Step 1: POM 改造
- Replace `spring-boot-starter-parent` → `solon-parent`
- Replace Spring starters → Solon plugins
- Add `solon-maven-plugin`
- See: `references/dependency_mapping.md`

### Step 2: 配置文件
- Rename `application.yml` → `app.yml`
- Adjust configuration key names
- See: `references/config_system_migration.md`

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
- See: `references/web_controller_migration.md`

### Step 6: 数据层改造
- Replace Spring transaction/cache annotations
- See: `references/datasource_orm_migration.md`, `references/transaction_cache_migration.md`

### Step 7: 微服务改造 (如适用)
- Replace Spring Cloud components with Solon Cloud equivalents
- See: `references/cloud_discovery_config_migration.md`

### Step 8: 测试改造
- Replace `@SpringBootTest` → `@SolonTest`
- See: `references/test_basics_migration.md`
