# Modules Reference — 模块与依赖参考

> 适用场景：选择服务器实现、序列化方式、视图引擎、数据访问、ORM 集成。
>
> Solon 官方坐标 groupId 为 `org.noear`，版本号 `3.10.4`。部分第三方适配插件的 groupId 不同，见各表标注。

## Shortcut Dependencies（快捷组合包）

快捷组合包本身不含代码，由多个插件包组合而成。开发者也可按需单独引入各子插件。

| Artifact | Description | Includes |
|---|---|---|
| `solon-web` | 完整 Web 应用开发（推荐 Web 项目使用）。包含 Web 服务器 + 序列化 + 会话 + 静态文件 + 跨域 + 验证 | solon-lib + solon-server-smarthttp + solon-serialization-snack4 + solon-sessionstate-local + solon-web-staticfiles + solon-web-cors + solon-security-validation |
| `solon-lib` | 基础开发组合包（不含 Web 服务器）。适用于 CLI 工具、后台任务、非 Web 微服务等场景 | solon + solon-data + solon-proxy + solon-config-yaml + solon-config-plus |

> **solon-web vs solon-lib 选择指南：** 需要启动 HTTP 服务、处理 Web 请求 → 用 `solon-web`；仅做数据处理、定时任务、消息消费等无需 HTTP 的场景 → 用 `solon-lib`。

## Server Implementations

| Artifact | Type | Size |
|---|---|---|
| `solon-server-smarthttp` | AIO (default in solon-web) | ~0.7MB |
| `solon-server-jdkhttp` | BIO (JDK built-in) | ~0.2MB |
| `solon-server-jetty` | NIO (Servlet API) | ~2.2MB |
| `solon-server-undertow` | NIO (Servlet API) | ~4.5MB |
| `solon-server-tomcat` | NIO (Servlet API) | varies |
| `solon-server-vertx` | Event-driven | varies |

## Serialization Options

| Artifact | Format |
|---|---|
| `solon-serialization-snack3` | JSON (default before v3.7.0, based on Snack3) |
| `solon-serialization-snack4` | JSON (default in solon-web since v3.7.0, based on Snack4) |
| `solon-serialization-jackson` | JSON (Jackson) |
| `solon-serialization-jackson3` | JSON (Jackson 3.x) |
| `solon-serialization-fastjson2` | JSON (Fastjson2) |
| `solon-serialization-gson` | JSON (Gson) |
| `solon-serialization-jackson-xml` | XML |
| `solon-serialization-hessian` | Binary (Hessian) |
| `solon-serialization-fury` | Binary (Fury) |
| `solon-serialization-protostuff` | Binary (Protobuf) |
| `solon-serialization-kryo` | Binary (Kryo) |
| `solon-serialization-abc` | Binary (ABC) |

## View Templates

| Artifact | Engine |
|---|---|
| `solon-view-freemarker` | FreeMarker |
| `solon-view-thymeleaf` | Thymeleaf |
| `solon-view-enjoy` | Enjoy |
| `solon-view-velocity` | Velocity |
| `solon-view-beetl` | Beetl |
| `solon-view-jsp` | JSP |

## Data Access

Solon Data 系列涵盖事务管理、数据源构建、缓存服务和 ORM 框架适配，以"多数据源"和"多数据源种类"为基础场景设定。

### 核心数据组件

| Artifact | Description |
|---|---|
| `solon-data` | 核心数据支持（事务管理、数据源构建） |
| `solon-data-sqlutils` | 轻量 SQL 工具（代码仅 ~20 KB），提供基础 SQL 调用与 Bean 映射 |
| `solon-data-rx-sqlutils` | 响应式 SQL 工具，基于 r2dbc 封装（代码仅 ~20 KB） |
| `solon-data-dynamicds` | 动态多数据源支持（按需切换数据源） |
| `solon-data-shardingds` | 分片数据源支持（适用于分库分表场景） |

### 缓存适配

| Artifact | Description |
|---|---|
| `solon-cache-jedis` | Redis 缓存（基于 Jedis 适配） |
| `solon-cache-redisson` | Redis 缓存（基于 Redisson 适配） |
| `solon-cache-spymemcached` | Memcached 缓存（基于 spymemcached 适配） |

### DataSource 配置示例

```java
// DataSource 配置（HikariCP 示例）
@Configuration
public class DataSourceConfig {
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        return ds;
    }
}
```

```yaml
# application.yml
db1:
  jdbcUrl: jdbc:mysql://localhost:3306/demo
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
```

### SqlUtils 使用示例 (solon-data-sqlutils)

SqlUtils 提供轻量级 JDBC 封装，支持链式查询与 Bean 自动映射。推荐注入为字段复用：

```java
@Component
public class UserService {
    @Inject
    private DataSource dataSource;

    // 推荐复用 SqlUtils 实例
    private SqlUtils sqlUtils() {
        return new SqlUtils(dataSource);
    }

    public void updateUser(User user) {
        sqlUtils().update("UPDATE users SET name=? WHERE id=?", user.name, user.id);
    }

    public User getUser(long id) {
        return sqlUtils().findById(id, User.class, "users");
    }

    public List<User> listUsers() {
        return sqlUtils().queryRowList("SELECT * FROM users").toBeanList(User.class);
    }
}
```

### 注解式事务示例 (solon-data)

```java
@Service
public class OrderService {
    @Inject
    private UserService userService;

    @Tran  // 声明式事务（传播机制默认 REQUIRED）
    public void createOrder(Order order) {
        userService.updateUser(order.getUser());
        // ... 其他业务操作
    }
}
```

## ORM Integration (solon-integration)

已适配的 ORM 框架，均支持"多数据源"场景。以下插件主要对接数据源与事务管理。

### 自主内核 ORM

| Plugin | ORM | 说明 |
|---|---|---|
| `activerecord-solon-plugin` | ActiveRecord | 自主内核 |
| `beetlsql-solon-plugin` | BeetlSQL | 自主内核 (groupId: `com.ibeetl`, artifact: `sql-solon-plugin`) |
| `easy-query-solon-plugin` | EasyQuery | 自主内核 (groupId: `com.easy-query`, artifact: `sql-solon-plugin`) |
| `sagacity-sqltoy-solon-plugin` | SQLToy | 自主内核 (groupId: `com.sagframe`) |
| `dbvisitor-solon-plugin` | DbVisitor | 自主内核 (groupId: `net.hasor`) |
| `wood-solon-plugin` | Wood | 自主内核 |

### MyBatis 体系

| Plugin | ORM | 说明 |
|---|---|---|
| `mybatis-solon-plugin` | MyBatis | 基础 MyBatis 适配 |
| `mybatis-plus-solon-plugin` | MyBatis-Plus | MyBatis 增强工具 (groupId: `com.baomidou`) |
| `mybatis-plus-join-solon-plugin` | MyBatis-Plus-Join | MyBatis-Plus 多表关联 (groupId: `com.github.yulichang`) |
| `mybatis-flex-solon-plugin` | MyBatis-Flex | MyBatis-Flex 适配 (groupId: `com.mybatis-flex`) |
| `mapper-solon-plugin` | TkMapper | 基于 mybatis-tkMapper 适配 (groupId: `tk.mybatis`) |
| `fastmybatis-solon-plugin` | FastMyBatis | FastMyBatis 适配 (groupId: `net.oschina.durcframework`) |
| `xbatis-solon-plugin` | XBatis | XBatis 适配 (groupId: `cn.xbatis`) |

### JPA/Hibernate 体系

| Plugin | ORM | 说明 |
|---|---|---|
| `hibernate-solon-plugin` | Hibernate | 基于 javax (旧版 Jakarta) JPA 适配 |
| `hibernate-jakarta-solon-plugin` | Hibernate | 基于 jakarta (新版) JPA 适配 |

### 查询增强

| Plugin | ORM | 说明 |
|---|---|---|
| `bean-searcher-solon-plugin` | Bean Searcher | 列表检索增强 |

## Scheduling

| Artifact | Description |
|---|---|
| `solon-scheduling-simple` | Simple built-in scheduler |
| `solon-scheduling-quartz` | Quartz integration |

## Security

| Artifact | Description |
|---|---|
| `solon-security-validation` | Parameter validation |
| `solon-security-auth` | Authentication & authorization |
| `solon-security-vault` | Secrets vault |

## Testing

| Artifact | Description |
|---|---|
| `solon-test-junit5` | JUnit 5 integration |
| `solon-test-junit4` | JUnit 4 integration |
