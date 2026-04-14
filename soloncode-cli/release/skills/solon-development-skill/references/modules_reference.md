# Modules Reference — 模块与依赖参考

> 适用场景：选择服务器实现、序列化方式、视图引擎、数据访问、ORM 集成。

## Shortcut Dependencies

| Artifact | Description | Includes |
|---|---|---|
| `solon-web` | Full web development (recommended for web apps) | solon-lib + smarthttp + snack4-json + session + staticfiles + cors + validation |
| `solon-lib` | Core library without web server | solon + handle + data + cache + proxy + yaml + config |

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
| `solon-serialization-snack4` | JSON (default in solon-web) |
| `solon-serialization-jackson` | JSON (Jackson) |
| `solon-serialization-jackson3` | JSON (Jackson 3.x) |
| `solon-serialization-fastjson2` | JSON (Fastjson2) |
| `solon-serialization-gson` | JSON (Gson) |
| `solon-serialization-jackson-xml` | XML |
| `solon-serialization-hessian` | Binary (Hessian) |
| `solon-serialization-fury` | Binary (Fury) |
| `solon-serialization-protostuff` | Binary (Protobuf) |

## View Templates

| Artifact | Engine |
|---|---|
| `solon-view-freemarker` | FreeMarker |
| `solon-view-thymeleaf` | Thymeleaf |
| `solon-view-enjoy` | Enjoy |
| `solon-view-velocity` | Velocity |
| `solon-view-beetl` | Beetl |

## Data Access

| Artifact | Description |
|---|---|
| `solon-data` | Core data support (transaction, datasource) |
| `solon-data-sqlutils` | SQL utility tools |
| `solon-cache-caffeine` | Caffeine cache |
| `solon-cache-jedis` | Redis cache (Jedis) |
| `solon-cache-redisson` | Redis cache (Redisson) |

### DataSource 配置示例

```java
// DataSource 配置
@Configuration
public class DataSourceConfig {
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        return ds;
    }
}
```

### 编程式事务示例 (solon-data-sqlutils)

```java
@Component
public class UserService {
    @Inject
    private DataSource dataSource;

    public void updateUser(User user) {
        SqlUtils sqlUtils = new SqlUtils(dataSource);
        sqlUtils.update("UPDATE users SET name=? WHERE id=?", user.name, user.id);
    }

    public User getUser(long id) {
        SqlUtils sqlUtils = new SqlUtils(dataSource);
        return sqlUtils.findById(id, User.class, "users");
    }

    public List<User> listUsers() {
        SqlUtils sqlUtils = new SqlUtils(dataSource);
        return sqlUtils.queryRowList("SELECT * FROM users").toBeanList(User.class);
    }
}
```

## ORM Integration (solon-integration)

| Plugin | ORM |
|---|---|
| `mybatis-solon-plugin` | MyBatis |
| `mybatis-plus-solon-plugin` | MyBatis-Plus |
| `mybatis-flex-solon-plugin` | MyBatis-Flex |
| `hibernate-solon-plugin` | Hibernate |
| `wood-solon-plugin` | Wood |
| `sqltoy-solon-plugin` | SQLToy |
| `bean-searcher-solon-plugin` | Bean Searcher |

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
