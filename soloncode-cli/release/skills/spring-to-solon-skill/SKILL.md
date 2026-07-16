---
name: spring-to-solon-skill
description: "Expert guidance for migrating Java projects from Spring Boot / Spring Cloud to the Solon framework. Provides annotation mapping, dependency replacement, architecture differences, and step-by-step migration for IoC, Web, Data, Security, Scheduling, Validation, Cloud, and Testing. Use when migrating Spring Boot/Cloud projects to Solon, replacing Spring annotations/dependencies, rewriting application.yml → app.yml, Feign→Nami, @SpringBootTest→@SolonTest, Spring Security→solon-security-auth, validation→solon-security-validation, or when the user says 迁移/Spring转Solon/替换starter/去Spring依赖. Not for greenfield Solon apps (use solon-development-skill)."
---

# Spring to Solon Migration Skill

为将 **Spring Boot** / **Spring Cloud** 项目迁移到 **Solon 框架** 提供专家指导。Solon 是独立的 Java 企业框架（**不基于 Spring**），开发体验相近，但注解、架构与生态不同。

**官方对照**: https://solon.noear.org/article/compare-springboot  
**Cloud 对照**: https://solon.noear.org/article/compare-springcloud  
**官网**: https://solon.noear.org  
**当前版本**: **4.0.3**

> **版本声明**：本文档所有示例基于 Solon **4.0.3**（最后一次确认: 2026-07）。如需迁移到其他版本，请调整对应版本号。Solon 高版本保持向后兼容，通常仅需修改 `solon-parent` 版本号即可。reference 内写「跟随 SKILL 目标版本」时，以本节为准。

## Critical Migration Rules

1. **Solon 不是 Spring。** 禁止混用 Spring 注解与 Solon 注解。替换全部 Spring import。
2. **禁止 Spring 依赖。** 移除所有 `spring-boot-starter-*`、`spring-*`。Solon 使用 `org.noear` groupId。
3. **配置文件**是 `app.yml`（或 `app.properties`），**不是** `application.yml`。
4. **入口**是 `Solon.start(App.class, args)`，不是 `SpringApplication.run()`。
5. **Parent POM** 是 `solon-parent`（`groupId=org.noear`）。
6. **包扫描**在主类用 `@Import` / 主类包路径，不是 `@ComponentScan`。
7. **无 setter 注入。** Solon 只支持字段注入与构造器注入；构造器参数必须显式 `@Inject`。
8. **组件用 `@Component`**，不要用 `@Service` / `@Repository`。
9. **所有示例目标 Solon 4.0.x**（默认 **4.0.3**），除非用户指定其它版本。
10. **中文支持。** 用户使用中文时，回复与代码注释使用中文。
11. **不确定的 API 不要臆造。** 对照本 skill reference；Solon 原生细节查 `solon-development-skill`。
12. **校验坐标** 为 **`solon-security-validation`**（不是 `solon-validation`）。类级启用 `@Valid`，实体参数/字段用 `@Validated`。
13. **调度选型**：单机 → `@Scheduled`（`scheduling_migration.md`）；分布式防重 → `@CloudJob`（`cloud_observability_migration.md`）。勿一律替换。

## 与 solon-development-skill 的边界

| 场景 | 使用 skill |
|------|------------|
| Spring → Solon 对照、替换、迁移步骤 | **本 skill** |
| 纯 Solon 新功能 / AI / Flow / 最佳实践 | **solon-development-skill** |
| 两边都提及时 | 先按本 skill 完成迁移对照，再链开发 skill 补原生写法 |

## 执行流程

1. **扫项目**：父 POM、starter 列表、配置文件名、启动类、测试入口、是否有 Security / Validation / Scheduling / Cloud。
2. **按 Checklist Step 1→12** 推进；每步只 `read` 1～2 个 reference，避免一次加载全部。
3. **生成代码前**核对 Critical Migration Rules（尤其：`app.yml`、无 Spring 混用、`@Component`/`@Inject`、校验坐标）。
4. **数据层**优先 `datasource_orm_migration.md`（含 `solon.dataSources`、SqlUtils、`@Db`）。
5. **安全**优先 `security_migration.md`（不要复刻完整 Spring Security 过滤器链）。
6. **校验**优先 `validation_migration.md`（与 `web_advanced` 摘要冲突时以 validation 文为准）。
7. **迁移完成后**的 Solon 深化（AI/Flow/进阶 API）交给 `solon-development-skill`。

## Migration Scene Navigation

> 根据迁移场景读取对应 reference。大表（`dependency_mapping` / `annotation_mapping`）只读相关小节，勿整文件灌入上下文。

### 快速对照

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 注解替换对照表 / Spring→Solon 注解映射 | `references/annotation_mapping.md` | `@Autowired`, `@Inject`, `@RequestMapping`, `@Mapping`, `@Service`, `@Component`, `@Value` |
| Maven 依赖替换 / starter → solon 插件 / POM | `references/dependency_mapping.md` | `pom.xml`, `spring-boot-starter`, `solon-web`, `solon-lib`, `solon-parent` |

### IoC / AOP / 配置

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| IoC / DI / 组件 / 作用域 / 生命周期 / AOP / 事件 | `references/ioc_aop_migration.md` | `@Inject`, `@Configuration`, `@Bean`, `LifecycleBean`, `@Init`, `@Destroy`, `@Aspect`, `EventBus` |
| 配置文件 / 属性注入 / 配置类 / 条件装配 | `references/config_system_migration.md` | `app.yml`, `@Inject("${")`, `@Configuration`, `@Bean`, `@Condition`, `solon.config` |

### Web 层迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| Controller / 请求参数 / 返回值 / Context | `references/web_controller_migration.md` | `@Controller`, `@RestController`, `@Mapping`, `@Param`, `@Body`, `Context` |
| Filter / Interceptor / 全局异常 / CORS | `references/web_filter_interceptor_migration.md` | `Filter`, `RouterInterceptor`, `@ControllerAdvice`, `GlobalExceptionFilter`, `@CrossOrigin` |
| 上传下载 / SSE / WebSocket / 会话 | `references/web_advanced_migration.md` | `UploadedFile`, `SseEmitter`, `WebSocket`, `SessionState` |
| 参数校验 / 自定义校验 / 错误处理 | `references/validation_migration.md` | `@Valid`, `@Validated`, `@NotNull`, `ValidatorException`, `solon-security-validation` |

### 数据访问迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 数据源 / SqlUtils / MyBatis / Plus / JPA / 多数据源 | `references/datasource_orm_migration.md` | `@Db`, `SqlUtils`, `solon.dataSources`, `jdbcUrl`, `MyBatis`, `JdbcTemplate` |
| 事务 / 缓存 / Redis | `references/transaction_cache_migration.md` | `@Transaction`, `@Cache`, `Redis`, `@CacheRemove`, `CacheService` |

### 安全迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| Spring Security → AuthAdapter / 路径鉴权 / 权限角色 | `references/security_migration.md` | `SecurityFilterChain`, `AuthAdapter`, `AuthProcessor`, `@AuthPermissions`, `@PreAuthorize` |

### 定时任务迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| **单机** @Scheduled / cron / fixedRate / Quartz | `references/scheduling_migration.md` | `@Scheduled`, `cron`, `solon-scheduling-simple`, `solon-scheduling-quartz` |
| **分布式** 任务（多实例防重） | `references/cloud_observability_migration.md` | `@CloudJob`, `solon.cloud.job` |

### 微服务迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 注册发现 / 配置中心 | `references/cloud_discovery_config_migration.md` | `Nacos`, `Eureka`, `CloudClient`, `@CloudConfig`, `nacos-solon-cloud-plugin` |
| RPC (Feign→Nami) / 网关 / 事件消息 | `references/cloud_gateway_rpc_migration.md` | `Feign`, `NamiClient`, `CloudGateway`, `@CloudEvent`, `Gateway` |
| 断路器 / 分布式调度 / 链路 / 锁/ID/文件/监控 | `references/cloud_observability_migration.md` | `Breaker`, `@CloudJob`, `Trace`, `CloudLock`, `CloudId`, `Metric` |

### 测试迁移

| Scenario | Reference File | Grep Keywords |
|---|---|---|
| 依赖 / 测试类 / HTTP / Mock / 回滚 | `references/test_basics_migration.md` | `@SpringBootTest`, `@SolonTest`, `HttpTester`, `Mock`, `@TestRollback` |
| 切面 / 条件 / 生命周期 / 数据层 / 陷阱（接 basics） | `references/test_advanced_migration.md` | `@Rollback`, `@SolonTest`, `@EnableAutoConfiguration` |

## Quick Migration Checklist

### Step 1: POM 改造
- Replace `spring-boot-starter-parent` → `solon-parent`（**4.0.3**）
- Replace Spring starters → Solon plugins
- Add `solon-maven-plugin`
- See: `references/dependency_mapping.md`

### Step 2: 配置文件
- Rename `application.yml` / `bootstrap.yml` → **`app.yml`**（合并为单文件）
- Adjust configuration key names
- See: `references/config_system_migration.md`

### Step 3: 启动类
- Replace `@SpringBootApplication` → `@SolonMain`
- Replace `SpringApplication.run()` → `Solon.start()`
- See: `references/annotation_mapping.md`

### Step 4: 注解与 IoC
- Replace all Spring annotations with Solon equivalents
- See: `references/annotation_mapping.md`, `references/ioc_aop_migration.md`

### Step 5: Web 层改造
- Replace `@RestController` → `@Controller`
- Replace `@RequestMapping` → `@Mapping`
- Handle `HttpServletRequest/Response` → `Context`
- Filter / 全局异常 / CORS 见 `web_filter_interceptor_migration.md`
- 上传 / SSE / WebSocket / 会话见 `web_advanced_migration.md`
- See: `references/web_controller_migration.md`

### Step 6: 参数校验（如适用）
- `spring-boot-starter-validation` → **`solon-security-validation`**
- 类级 `@Valid`；实体参数 `@Validated`；异常 `ValidatorException`
- See: `references/validation_migration.md`

### Step 7: 数据层改造
- `spring.datasource` → `solon.dataSources`（`jdbcUrl`）
- `JdbcTemplate` → `SqlUtils`；Mapper 用 `@Db`
- See: `references/datasource_orm_migration.md`, `references/transaction_cache_migration.md`

### Step 8: 安全改造（如适用）
- Remove Spring Security；用 `AuthAdapter` + `AuthProcessor`
- See: `references/security_migration.md`

### Step 9: 定时任务（如适用）
- **单机**：`@Scheduled` → `org.noear.solon.scheduling.annotation.Scheduled` + `solon-scheduling-simple`（或 quartz）
- **分布式**：才考虑 `@CloudJob`（见 Step 10 / observability）
- See: `references/scheduling_migration.md`

### Step 10: 微服务改造（如适用）
- 注册发现 / 配置：`cloud_discovery_config_migration.md`
- RPC / 网关 / 事件：`cloud_gateway_rpc_migration.md`
- 断路器 / 分布式任务 / 链路 / 锁：`cloud_observability_migration.md`
- Replace Spring Cloud components with Solon Cloud plugins（如 `nacos-solon-cloud-plugin`）

### Step 11: 测试改造
- Replace `@SpringBootTest` → `@SolonTest`（**禁止**测试里混用 Spring 注解）
- See: `references/test_basics_migration.md`；进阶见 `test_advanced_migration.md`

### Step 12: 收尾核对
- 全库无 `org.springframework` import / starter
- 配置仅为 `app.yml`（或 `app.properties`）
- 校验 / 安全 / 调度依赖坐标与 Critical Rules 一致
- 有需要时再读 `solon-development-skill` 补原生能力

## 明确不覆盖（避免硬编）

以下无完整一对一迁移手册，遇到时说明边界并查官网 / 开发 skill，**不要臆造 API**：

- Spring Batch / Spring Integration 全量编排
- Spring GraphQL / Spring AI / Spring Session 集群方案细节
- 完整 OAuth2 Authorization Server 产品级复刻
- 与业务强绑定的自研 Spring Boot Starter
