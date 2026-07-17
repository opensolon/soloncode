# Data Access — 数据访问

> 适用场景：数据源、SqlUtils、声明式事务、MyBatis 及常见 ORM 接入。
>
> 目标版本：4.0.3。模块/依赖索引见 `modules_reference.md`。

## 依赖

```xml
<!-- 轻量 SQL（推荐 SQL 少或需手写复杂 SQL 时） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-sqlutils</artifactId>
</dependency>

<!-- MyBatis 适配（需要时） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>mybatis-solon-plugin</artifactId>
</dependency>

<!-- 连接池（示例用 HikariCP） -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
</dependency>
```

> `solon-lib` / `solon-web` 已传递引入 `solon-data`（事务与数据源构建）。`solon-data-sqlutils`、ORM 插件需按需追加。

---

## 数据源配置（推荐：`solon.dataSources`）

配置文件必须是 **`app.yml`**（不是 `application.yml`）。

```yaml
# app.yml
solon.dataSources:
  db1!: # 名称以 ! 结尾表示默认数据源
    class: "com.zaxxer.hikari.HikariDataSource"
    jdbcUrl: jdbc:mysql://localhost:3306/demo?useUnicode=true&characterEncoding=utf8&autoReconnect=true
    driverClassName: com.mysql.cj.jdbc.Driver
    username: root
    password: 123456
  db2:
    class: "com.zaxxer.hikari.HikariDataSource"
    jdbcUrl: jdbc:mysql://localhost:3306/demo2?useUnicode=true&characterEncoding=utf8
    driverClassName: com.mysql.cj.jdbc.Driver
    username: root
    password: 123456
```

配置后可直接按名注入 `DataSource` / `SqlUtils`，一般无需手写 `@Bean`。

### 可选：手动 `@Bean` 构建

```java
@Configuration
public class DataSourceConfig {
    @Bean(name = "db1", typed = true) // typed=true 同时按类型注册为默认
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        return ds;
    }
}
```

对应属性前缀写法（与上面 `solon.dataSources` 二选一即可）：

```yaml
# app.yml
db1:
  jdbcUrl: jdbc:mysql://localhost:3306/demo
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
```

---

## SqlUtils（solon-data-sqlutils）

`SqlUtils` 是线程安全的 JDBC 轻量封装：`sql(...).query*/update*` 链式 API。适合 SQL 少、复杂 SQL、或不想上 ORM 的场景。

### 获取实例

```java
@Component
public class UserService {
    @Inject // 默认数据源
    SqlUtils sqlUtils;

    @Inject("db2") // 指定数据源名
    SqlUtils sqlUtils2;

    // 手动获取
    // SqlUtils sql = SqlUtils.of(dataSource);
    // SqlUtils sql = SqlUtils.ofName("db1");
}
```

### 查询 / 更新

```java
@Component
public class UserService {
    @Inject
    SqlUtils sqlUtils;

    public User getUser(long id) throws SQLException {
        return sqlUtils.sql("SELECT * FROM users WHERE id=?", id)
                .queryRow(User.class);
    }

    public List<User> listUsers() throws SQLException {
        return sqlUtils.sql("SELECT * FROM users")
                .queryRowList(User.class);
    }

    public Long countUsers() throws SQLException {
        return sqlUtils.sql("SELECT COUNT(*) FROM users")
                .queryValue();
    }

    public int updateUser(User user) throws SQLException {
        return sqlUtils.sql(
                "UPDATE users SET name=? WHERE id=?",
                user.getName(), user.getId()
        ).update();
    }

    public Long insertUser(User user) throws SQLException {
        return sqlUtils.sql(
                "INSERT INTO users(name) VALUES(?)",
                user.getName()
        ).updateReturnKey();
    }
}
```

### 动态 SQL（SqlBuilder）

```java
public List<User> search(String name, Integer status) throws SQLException {
    SqlBuilder sb = new SqlBuilder()
            .append("SELECT * FROM users WHERE 1=1 ")
            .appendIf(name != null, "AND name LIKE ? ", "%" + name + "%")
            .appendIf(status != null, "AND status = ? ", status);

    return sqlUtils.sql(sb).queryRowList(User.class);
}
```

集合占位：`in (?...)`：

```java
sqlUtils.sql(
        new SqlBuilder().append(
                "SELECT * FROM users WHERE id IN (?...)",
                Arrays.asList(1L, 2L, 3L)
        )
).queryRowList(User.class);
```

### 批量与流式

```java
// 批量插入
List<Object[]> args = Arrays.asList(
        new Object[]{"a"}, new Object[]{"b"}
);
sqlUtils.sql("INSERT INTO users(name) VALUES(?)")
        .params(args)
        .updateBatch();

// 流式读取（用完关闭）
try (RowIterator<User> it = sqlUtils.sql("SELECT * FROM users")
        .queryRowIterator(100, User.class)) {
    while (it.hasNext()) {
        User u = it.next();
    }
}
```

### 初始化脚本

```java
@Configuration
public class DbInit {
    @Bean
    public void init(SqlUtils sqlUtils) throws Exception {
        sqlUtils.initDatabase("classpath:schema.sql");
    }
}
```

> **禁止**编造不存在的 API（如 `findById`、`queryRowList(...).toBeanList(...)`、`new SqlUtils(ds)`）。正确入口是 `SqlUtils.of(ds)` / 注入，以及 `sql(...).queryRowList(Class)`。

---

## 声明式事务

依赖：`solon-data`（已由 `solon-lib` / `solon-web` 引入）。

```java
@Component // 禁止使用 Spring 的 @Service
public class OrderService {
    @Inject
    UserService userService;

    @Transaction // 默认传播 REQUIRED；回滚无需指定异常类型
    public void createOrder(Order order) {
        userService.updateUser(order.getUser());
        // ... 其他写操作
    }
}
```

手动事务：

```java
TranUtils.execute(new TranAnno(), () -> {
    sqlUtils.sql("DELETE FROM users WHERE id=?", id).update();
});
```

---

## MyBatis 最小闭环

### 1. 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>mybatis-solon-plugin</artifactId>
</dependency>
```

### 2. app.yml

```yaml
solon.dataSources:
  db1!:
    class: "com.zaxxer.hikari.HikariDataSource"
    jdbcUrl: jdbc:mysql://localhost:3306/demo?useUnicode=true&characterEncoding=utf8
    driverClassName: com.mysql.cj.jdbc.Driver
    username: root
    password: 123456

# 配置名 mybatis.<数据源 Bean 名>
mybatis.db1:
  typeAliases:          # 包名 或 类名（.class 结尾）
    - "com.example.model"
  mappers:              # 包名 / 类名 / xml 路径
    - "com.example.mapper"
    # - "classpath:mapper/*.xml"
```

### 3. Mapper + 实体

```java
// com.example.mapper.UserMapper
public interface UserMapper {
    @Select("SELECT * FROM users WHERE id=#{id}")
    User findById(long id);

    @Select("SELECT * FROM users")
    List<User> findAll();

    @Insert("INSERT INTO users(name) VALUES(#{name})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    long insert(User user);
}
```

```java
// com.example.model.User
public class User {
    public long id;
    public String name;
}
```

### 4. 注入与使用

```java
@Component
public class UserService {
    // @Db 来自 mybatis-solon-plugin，可注入 Mapper / SqlSession / SqlSessionFactory
    @Db
    UserMapper userMapper;

    // 已被 mapperScan 托管时，也可用 @Inject
    // @Inject
    // UserMapper userMapper;

    public User get(long id) {
        return userMapper.findById(id);
    }

    @Transaction
    public long create(User user) {
        return userMapper.insert(user);
    }
}

@Controller
@Mapping("/api/users")
public class UserController {
    @Inject
    UserService userService;

    @Get
    @Mapping("/{id}")
    public User get(@Path long id) {
        return userService.get(id);
    }
}
```

### 多数据源

```java
@Db("db1")
UserMapper userMapper1;

@Db("db2")
OrderMapper orderMapper2;
```

对应配置 `mybatis.db1` / `mybatis.db2`，且数据源 Bean 名与之对齐。

---

## 其他 ORM

插件坐标见 `modules_reference.md` 的 ORM 表。通用原则：

1. 先配置好命名 `DataSource`（`solon.dataSources` 或 `@Bean(name=...)`）
2. 再按插件文档绑定 Mapper / Session
3. 事务统一用 Solon `@Transaction`（不要用 Spring `@Transactional`）

---

## 缓存（简表）

| Artifact | 说明 |
|---|---|
| `solon-cache` | 本地缓存（默认） |
| `solon-cache-jedis` | Redis（Jedis） |
| `solon-cache-redisson` | Redis（Redisson） |
| `solon-cache-spymemcached` | Memcached |

### 业务缓存注解最小例

```java
import org.noear.solon.data.annotation.Cache;
import org.noear.solon.data.annotation.CachePut;
import org.noear.solon.data.annotation.CacheRemove;

@Component
public class UserCacheService {
    // tags / key 支持 ${param} 与 ${.field} 表达式；seconds 为过期秒数
    @Cache(tags = "user_${id}", seconds = 60)
    public User getById(long id) {
        return loadFromDb(id);
    }

    @CachePut(tags = "user_${user.id}")
    public User update(User user) {
        return saveToDb(user);
    }

    @CacheRemove(tags = "user_${id}")
    public void remove(long id) {
        deleteFromDb(id);
    }
}
```

> 注解包名：`org.noear.solon.data.annotation`。完整属性与更多模式见官网「缓存」章节；依赖索引见 `modules_reference.md`。
