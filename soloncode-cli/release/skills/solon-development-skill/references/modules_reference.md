# Modules Reference — 模块与依赖参考

> 适用场景：选择服务器实现、序列化方式、视图引擎、数据访问插件、调度与安全依赖。
>
> Solon 官方坐标 groupId 为 `org.noear`，**版本号 `4.0.3`**。部分第三方适配插件 groupId 不同，见各表标注。
>
> **数据源 / SqlUtils / 事务 / MyBatis 用法** → 见 `data_access.md`（本文件只做依赖索引）。

## Shortcut Dependencies（快捷组合包）

快捷组合包本身不含业务代码，由多个插件组合而成；也可按需单独引入子插件。

| Artifact | Description | Includes / 备注 |
|---|---|---|
| `solon-web` | 完整 Web 应用开发（推荐 Web 项目） | solon-lib + smarthttp + snack4 + session + staticfiles + cors + validation |
| `solon-lib` | 基础开发组合包（无 Web 服务器） | solon + solon-data + solon-proxy + yaml/config-plus；CLI / 后台 / 非 Web |
| `solon-rpc` | RPC 客户端快捷包 | Nami 客户端所需通道与默认序列化；配合 `@NamiClient` |
| `solon-job` | 任务调度快捷包 | 内置调度相关能力；细粒度见 Scheduling 表 |
| `solon-ai` | AI 基础快捷包 | ChatModel / 方言等；Agent 另引 `solon-ai-agent`，Harness 另引 `solon-ai-harness` |
| `solon-cloud-gateway` | 云网关快捷包 | 网关场景；细节见 `cloud_ops.md` |

> **选择指南：** HTTP 服务 → `solon-web`；非 Web → `solon-lib`；RPC 消费端 → `solon-rpc`；AI 对话 → `solon-ai`；Agent/Harness 按需叠加。

## Server Implementations

| Artifact | Type | Size / 备注 |
|---|---|---|
| `solon-server-smarthttp` | AIO（solon-web 默认） | ~0.7MB |
| `solon-server-jdkhttp` | BIO（JDK 内置） | ~0.2MB |
| `solon-server-jetty` | NIO（Servlet API） | ~2.2MB |
| `solon-server-undertow` | NIO（Servlet API） | ~4.5MB |
| `solon-server-tomcat` | NIO（Servlet API） | varies |
| `solon-server-vertx` | Event-driven | varies |
| `solon-server-feathttp` | Feat HTTP | 可选服务器实现 |

## Serialization Options

| Artifact | Format | 备注 |
|---|---|---|
| `solon-serialization-snack4` | JSON（Snack4） | **新项目默认推荐**；solon-web 自 v3.7.0 默认 |
| `solon-serialization-jackson` | JSON（Jackson） | |
| `solon-serialization-jackson3` | JSON（Jackson 3.x） | |
| `solon-serialization-fastjson2` | JSON（Fastjson2） | |
| `solon-serialization-gson` | JSON（Gson） | |
| `solon-serialization-jackson-xml` | XML | |
| `solon-serialization-hessian` | Binary（Hessian） | |
| `solon-serialization-fury` | Binary（Fury） | |
| `solon-serialization-protostuff` | Binary（Protobuf） | |
| `solon-serialization-kryo` | Binary（Kryo） | |
| `solon-serialization-abc` | Binary（ABC） | |
| `solon-serialization-snack3` | JSON（Snack3） | **Legacy**：v3.7.0 前默认；**新项目勿用**，请用 snack4 |

## View Templates

| Artifact | Engine |
|---|---|
| `solon-view-freemarker` | FreeMarker |
| `solon-view-thymeleaf` | Thymeleaf |
| `solon-view-enjoy` | Enjoy |
| `solon-view-velocity` | Velocity |
| `solon-view-beetl` | Beetl |
| `solon-view-jsp` | JSP |

## Web 增强 / I18n

| Artifact | Description | 详情 |
|---|---|---|
| `solon-web-sse` | Server-Sent Events | `web_sse_reactive.md` |
| `solon-web-rx` | 响应式 Web（Mono/Flux） | `web_sse_reactive.md` |
| `solon-i18n` | 国际化 / Locale | `i18n.md` |

## Data Access（索引）

用法与示例见 **`data_access.md`**。

### 核心数据组件

| Artifact | Description |
|---|---|
| `solon-data` | 核心数据支持（事务、数据源构建） |
| `solon-data-sqlutils` | 轻量 SQL 工具（~20KB），`SqlUtils.sql(...).query*/update*` |
| `solon-data-rx-sqlutils` | 响应式 SQL（R2DBC） |
| `solon-data-dynamicds` | 动态多数据源 |
| `solon-data-shardingds` | 分片数据源（分库分表） |

### 缓存适配

| Artifact | Description |
|---|---|
| `solon-cache-jedis` | Redis（Jedis） |
| `solon-cache-redisson` | Redis（Redisson） |
| `solon-cache-spymemcached` | Memcached |

### 自主内核 ORM

| Plugin | ORM | 说明 |
|---|---|---|
| `activerecord-solon-plugin` | ActiveRecord | 自主内核 |
| `beetlsql-solon-plugin` | BeetlSQL | groupId: `com.ibeetl`, artifact: `sql-solon-plugin` |
| `easy-query-solon-plugin` | EasyQuery | groupId: `com.easy-query`, artifact: `sql-solon-plugin` |
| `sagacity-sqltoy-solon-plugin` | SQLToy | groupId: `com.sagframe` |
| `dbvisitor-solon-plugin` | DbVisitor | groupId: `net.hasor` |
| `wood-solon-plugin` | Wood | 自主内核 |

### MyBatis 体系

| Plugin | ORM | 说明 |
|---|---|---|
| `mybatis-solon-plugin` | MyBatis | 基础适配（`@Db` 注入 Mapper） |
| `mybatis-plus-solon-plugin` | MyBatis-Plus | groupId: `com.baomidou` |
| `mybatis-plus-join-solon-plugin` | MyBatis-Plus-Join | groupId: `com.github.yulichang` |
| `mybatis-flex-solon-plugin` | MyBatis-Flex | groupId: `com.mybatis-flex` |
| `mapper-solon-plugin` | TkMapper | groupId: `tk.mybatis` |
| `fastmybatis-solon-plugin` | FastMyBatis | groupId: `net.oschina.durcframework` |
| `xbatis-solon-plugin` | XBatis | groupId: `cn.xbatis` |

### JPA / Hibernate 与查询增强

| Plugin | ORM | 说明 |
|---|---|---|
| `hibernate-solon-plugin` | Hibernate | javax（旧 Jakarta） |
| `hibernate-jakarta-solon-plugin` | Hibernate | jakarta |
| `bean-searcher-solon-plugin` | Bean Searcher | 列表检索增强 |

## Scheduling

| Artifact | Description |
|---|---|
| `solon-scheduling-simple` | 内置简单调度（配合 `@EnableScheduling` + `@Scheduled`） |
| `solon-scheduling-quartz` | Quartz 集成 |

## Security

| Artifact | Description |
|---|---|
| `solon-security-validation` | 参数校验 |
| `solon-security-auth` | 认证鉴权 |
| `solon-security-vault` | 密钥保管 |

## Testing

| Artifact | Description |
|---|---|
| `solon-test` | JUnit 5（推荐，见 `testing.md`） |
| `solon-test-junit5` | 仅 JUnit 5 |
| `solon-test-junit4` | JUnit 4 |

## RPC / AI / Cloud（索引）

| Artifact | 说明 | 详情 |
|---|---|---|
| `solon-rpc` / `nami-*` | Nami RPC 客户端 | `remoting.md` / `remoting_filter_lb.md` |
| `solon-server-socketd` / `socket.d` | Socket.D | `socketd.md` |
| `solon-ai` / `solon-ai-core` | Chat / RAG 核心 | `ai_chat_rag_mcp.md` |
| `solon-ai-load-*` / `solon-ai-repo-*` / `solon-ai-search-*` | RAG 插件表 | `ai_rag_plugins.md` |
| `solon-ai-agent` / `solon-ai-loop` | Agent / Loop | `ai_agent.md` |
| `solon-ai-harness` | 马具框架 | `ai_harness.md` |
| `solon-ai-ui-aisdk` / `solon-ai-acp` | AI UI / ACP | `ai_protocol_ui.md` |
| `*-solon-cloud-plugin` | 注册/配置/事件等 | `cloud_core.md` |
| `solon-cloud-gateway` / File/Breaker/Trace 等 | 网关与运维能力 | `cloud_ops.md` |
| `solon-flow` | 流程编排基础 | `flow_orchestration.md` |
| `solon-flow-workflow` | 中断恢复 / Workflow | `flow_workflow.md` |
