# Spring → Solon 注解对照表

> 目标版本：跟随 SKILL（默认 **4.0.3**）| 官方：https://solon.noear.org/article/compare-springboot
>
> **本文件是速查权威表**（表 + 关键差异一行即可）。完整 Before/After、陷阱与 Checklist 见各 `*_migration.md`，避免两套长示例漂移。
>
> **加载策略**：只读下方目录对应小节或 §12 总表，**勿整文件灌入上下文**。

## 目录（按需读）

| 节 | 内容 | 长示例见 |
|----|------|----------|
| §1 | DI / 配置注入 | `ioc_aop_migration.md`, `config_system_migration.md` |
| §2 | 组件 / 作用域 / 条件 | `ioc_aop_migration.md` |
| §3 | 配置类 / Bean | `config_system_migration.md` |
| §4 | 生命周期 | `ioc_aop_migration.md` |
| §5 | Web / MVC | `web_controller_migration.md` |
| §6 | AOP | `ioc_aop_migration.md` |
| §7 | 事务 / 缓存 / 校验 | `transaction_cache_migration.md`, `validation_migration.md` |
| §8 | 扫描 / 导入 | `ioc_aop_migration.md` |
| §9 | RPC 客户端 | `cloud_gateway_rpc_migration.md` |
| §10 | 定时任务 | `scheduling_migration.md` / observability |
| §11 | 测试 / 启动 | `test_basics_migration.md` |
| §12 | **完整速查总表** | （优先读这里） |
| §13 | 高频注意事项 | — |

---

## 1. IoC / 依赖注入

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@Autowired` | `@Inject` | 无 setter 注入；仅字段 / 构造器参数（参数须显式 `@Inject`）/ `@Bean` 方法参数 |
| `@Qualifier` + `@Autowired` | `@Inject("name")` | Solon 需 Bean 有 name 才能按名取；与 Spring 默认类名注册行为不同 |
| `@Value("${x}")` / 默认值 | `@Inject("${x}")` / `@Inject("${x:默认}")` | 同一注解兼类型注入与配置注入 |
| `@ConfigurationProperties` | `@BindProps` 或 `@Inject("${prefix}")` 到类型 | 细节见 `config_system_migration.md` |

---

## 2. 组件注册与作用域

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@Component` / `@Service` / `@Repository` | **`@Component`** | 统一组件注解，不要保留 `@Service`/`@Repository` 当托管语义 |
| `@Scope("singleton")` | 默认单例 / `@Singleton` | 默认即单例 |
| `@Scope("prototype")` | `@Singleton(false)` | 无 Spring 式 `@Scope` 字符串一套 |
| `@ConditionalOnXxx` | `@Condition(...)` | API 不同，按条件属性迁移 |

---

## 3. 配置类与 Bean

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@Configuration` | `@Configuration` | 名称相同，包不同 |
| `@Bean` | `@Bean` | 方法参数注入用 `@Inject` |
| `@Primary` | `@Bean(..., typed = true)` 或数据源名 `!` 后缀 | 默认 Bean 标记方式不同；数据源见 `datasource_orm_migration.md` |
| `@PropertySource` | `@Import`（属性/资源） | Solon `@Import` 职责更广 |

---

## 4. 生命周期

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@PostConstruct` | `@Init` | 或实现 `LifecycleBean` |
| `@PreDestroy` | `@Destroy` | 同上 |
| `InitializingBean` / `DisposableBean` | `LifecycleBean` | 统一生命周期接口 |
| `ApplicationRunner` / `CommandLineRunner` | 监听 `AppLoadEndEvent` 等 | 事件模型，见 ioc 文 |

---

## 5. Web / MVC

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@RestController` | `@Controller` | **默认 JSON**，无 `@ResponseBody` / 无 `@RestController` |
| `@RequestMapping` / `@GetMapping`… | `@Mapping` + `@Get`/`@Post`/… | **一个 `@Mapping` 一个路径**，不支持多 path 数组 |
| `@RequestParam` | `@Param` | 行为近似但不完全对等，复杂场景实测 |
| `@RequestHeader` | `@Header` | |
| `@RequestBody` | `@Body` | |
| `@CookieValue` | `@Cookie` | |
| `@PathVariable` | `@Path` | |
| `produces` / `consumes` | `@Produces` / `@Consumes` | 独立注解 |
| `HttpServletRequest` + `Response` | `Context` | 非 Servlet 内核；Servlet 容器下可兼容注入 Servlet API |
| `HttpSession` | `Context` session API / `SessionState` | 见 `web_advanced_migration.md` |
| `MultipartFile` | `UploadedFile` | 下载可用 `DownloadedFile` |
| （REST 服务端） | `@Remoting` | Solon RPC 服务端能力 |

---

## 6. AOP

| 项 | Spring | Solon |
|----|--------|-------|
| 代理 | 常全局代理 | **按需代理**（有拦截才代理） |
| 可见性 | 因代理机制而异 | **主要代理 public 方法** |
| 切面 | `@Aspect` + AspectJ 点切 | 拦截器 / 注解拦截等（见 `ioc_aop_migration.md`） |

---

## 7. 数据访问 / 事务 / 缓存 / 校验

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@Transactional` | `@Transaction` | 默认**所有 Exception 回滚**；常可去 `rollbackFor`；`noRollbackFor` 弱支持 |
| `@Cacheable` | `@Cache(tags=...)` | **标签模型**，非 value+key |
| `@CacheEvict` | `@CacheRemove` | 可用 `user_*` 类通配 |
| `@CachePut` | 常拆成 Remove + 再缓存 | 无一对一 Put |
| 类级启用校验 | 类级 `@Valid` | 依赖 **`solon-security-validation`** |
| 实体参数校验 | 参数/字段 **`@Validated`** | 包名 `org.noear.solon.validation.annotation`，与 javax/jakarta **不兼容** |
| Mapper 注入 | **`@Db`** | 勿与普通 `@Inject` 混用习惯不核对 |

---

## 8. 组件导入与扫描

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@ComponentScan` | 主类包扫描 + `@Import` | `@Import` 只在启动类 / `@Configuration` 上有效 |
| `@Import` | `@Import` | 兼扫描、导入类、属性源 |
| `@SpringBootApplication` | `@SolonMain` + `Solon.start` | 见 §11 |

---

## 9. RPC 客户端

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@FeignClient` | `@NamiClient` | 依赖 `nami` + 通道；可选 `feign-solon-plugin` 保 Feign 习惯 |
| 假坐标 | — | **禁止** `solon-cloud-feign-compatible` / 臆造 `solon-rpc-nami` |

详见 `cloud_gateway_rpc_migration.md`。

---

## 10. 定时任务

| 场景 | Spring | Solon | 文档 |
|------|--------|-------|------|
| **单机** | `@EnableScheduling` + `@Scheduled` | `@Scheduled`（`org.noear.solon.scheduling.annotation`）+ `solon-scheduling-simple` / quartz | `scheduling_migration.md` |
| **分布式防重** | 集群 Job / XXL 等 | `@CloudJob` | `cloud_observability_migration.md` |

**勿**把所有 Spring `@Scheduled` 一律改成 `@CloudJob`。

---

## 11. 测试与启动类

| Spring | Solon | 关键差异 |
|--------|-------|----------|
| `@SpringBootApplication` | `@SolonMain` | 入口 `Solon.start(App.class, args)` |
| `@SpringBootTest` | `@SolonTest` | **禁止**测试里混用 Spring 注解 |
| `@TestPropertySource` | `@Import` 等 | 见 test 文 |
| `@Rollback` / 测试回滚 | `@Rollback` / `@TestRollback`（以 test 插件为准） | 见 `test_basics_migration.md` |
| `spring-boot-starter-test` | **`solon-test-junit5`** | 坐标见 `dependency_mapping.md` |

---

## 12. 完整注解速查表

| 分类 | Spring Boot | Solon 4.0.x | 简要说明 |
|---|---|---|---|
| **DI** | `@Autowired` | `@Inject` | 按类型注入 |
| **DI** | `@Qualifier`+`@Autowired` | `@Inject("name")` | 按名称注入 |
| **DI** | `@Value("${x}")` | `@Inject("${x}")` | 注入配置值 |
| **配置** | `@ConfigurationProperties` | `@BindProps` / `@Inject("${p}")` | 属性集绑定 |
| **配置** | `@Configuration` | `@Configuration` | 配置类（同名异包） |
| **配置** | `@Bean` | `@Bean` | 配置 Bean |
| **配置** | `@PropertySource` | `@Import` | 导入属性源 |
| **配置** | `@ConditionalOnXxx` | `@Condition` | 条件注册 |
| **组件** | `@Component`/`@Service`/`@Repository` | `@Component` | 托管组件统一 |
| **组件** | `@Import`+`@ComponentScan` | `@Import` | 导入与扫描 |
| **作用域** | `@Scope("singleton")` | 默认 / `@Singleton` | 单例默认 |
| **作用域** | `@Scope("prototype")` | `@Singleton(false)` | 多例 |
| **生命周期** | `@PostConstruct` | `@Init` / `LifecycleBean` | 初始化 |
| **生命周期** | `@PreDestroy` | `@Destroy` / `LifecycleBean` | 销毁 |
| **生命周期** | `ApplicationRunner` | `AppLoadEndEvent` 等 | 启动完成后 |
| **Web** | `@RestController` | `@Controller` | 默认 JSON |
| **Web** | `@RequestMapping`/`@GetMapping`... | `@Mapping` + 方法注解 | 单路径映射 |
| **Web** | `@RequestParam` | `@Param` | 请求参数 |
| **Web** | `@RequestHeader` | `@Header` | 请求头 |
| **Web** | `@RequestBody` | `@Body` | 请求体 |
| **Web** | `@CookieValue` | `@Cookie` | Cookie |
| **Web** | `@PathVariable` | `@Path` | 路径变量 |
| **Web** | `produces`/`consumes` | `@Produces`/`@Consumes` | 内容类型 |
| **Web** | Servlet Request/Response | `Context` | 请求上下文 |
| **Web** | `HttpSession` | session API / `SessionState` | 会话 |
| **Web** | `MultipartFile` | `UploadedFile` | 上传 |
| **Web** | — | `DownloadedFile` | 下载 |
| **Web** | — | `@Remoting` | RPC 服务端 |
| **事务** | `@Transactional` | `@Transaction` | 默认全异常回滚 |
| **缓存** | `@Cacheable` | `@Cache` | tags 标签 |
| **缓存** | `@CacheEvict` | `@CacheRemove` | 标签清除 |
| **校验** | 类级 `@Validated` | 类级 `@Valid` | `solon-security-validation` |
| **校验** | 实体 `@Valid`/`@Validated` | 参数/字段 `@Validated` | 换包名 |
| **数据** | `@Autowired` Mapper | `@Db` | Mapper 专用 |
| **定时（单机）** | `@Scheduled` | `scheduling.annotation.Scheduled` | 见 scheduling 文 |
| **定时（分布式）** | 集群 Job | `@CloudJob` | 见 observability |
| **RPC** | `@FeignClient` | `@NamiClient` | Nami |
| **启动** | `@SpringBootApplication` | `@SolonMain` | + `Solon.start` |
| **测试** | `@SpringBootTest` | `@SolonTest` | 禁混 Spring |
| **测试** | `@TestPropertySource` | `@Import` 等 | 见 test 文 |

---

## 13. 迁移高频注意事项

1. **Solon 不是 Spring**：禁止混用 `org.springframework.*` 与 Solon 注解。
2. **无 setter 注入**；构造器参数必须 `@Inject`。
3. **配置文件**：`application.yml` → **`app.yml`**。
4. **`@Import` 多义**：兼扫描 / 导入 / 属性源；位置受限。
5. **`@Controller` 默认 JSON**；无 `@RestController`。
6. **`@Mapping` 单路径**。
7. **Bean 命名**：按名注入前确认已注册 name。
8. **AOP 按需、偏 public**。
9. **校验坐标**仅 **`solon-security-validation`**。
10. **长示例与陷阱**以各 `*_migration.md` 为准；本表冲突时以 migration 文 + SKILL Critical Rules 为准。
