# 数据源与 ORM 迁移参考

> Spring Boot → Solon 数据访问层迁移指南（目标版本：跟随 SKILL，默认 4.0.3）
>
> **本文职责（权威）**：`solon.dataSources`、SqlUtils、MyBatis / Plus、JPA、多数据源。
>
> **边界**：`@Transaction` / `@Cache` / Redis 客户端 → **`transaction_cache_migration.md`**。
>
> Solon 原生细节也可对照 `solon-development-skill` 的 `references/data_access.md`。

---

## 目录

- [1. 数据源配置迁移](#1-数据源配置迁移)
- [2. JdbcTemplate → SqlUtils](#2-jdbctemplate--sqlutils)
- [3. MyBatis 迁移](#3-mybatis-迁移)
- [4. MyBatis Plus 迁移](#4-mybatis-plus-迁移)
- [5. JPA 迁移](#5-jpa-迁移)
- [6. 多数据源迁移](#6-多数据源迁移)
- [7. 数据源与 ORM 陷阱速查](#7-数据源与-orm-陷阱速查)
- [8. 迁移检查清单](#8-迁移检查清单)

---

## 1. 数据源配置迁移

配置文件必须是 **`app.yml`**（不是 `application.yml`）。

### 1.1 推荐：`solon.dataSources` 自动装配

**Spring Before：**

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/demo
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      minimum-idle: 5
      maximum-pool-size: 20
      pool-name: DemoHikariCP
```

**Solon After（推荐）：**

```yaml
# app.yml
solon.dataSources:
  db1!: # 名称以 ! 结尾表示默认数据源
    class: "com.zaxxer.hikari.HikariDataSource"
    jdbcUrl: jdbc:mysql://localhost:3306/demo?useUnicode=true&characterEncoding=utf8
    driverClassName: com.mysql.cj.jdbc.Driver
    username: root
    password: 123456
    minimumIdle: 5
    maximumPoolSize: 20
    poolName: DemoHikariCP
```

> **关键差异**：
> - Spring 使用 `spring.datasource` 固定前缀；Solon 推荐 `solon.dataSources.<name>`。
> - Hikari 连接串键是 **`jdbcUrl`**，不要写 `url`（部分场景能兜底，但不稳）。
> - `driver-class-name`（短横线）→ `driverClassName`（驼峰）。
> - 连接池参数从 `hikari.*` 子节点移至数据源节点顶层。
> - 使用 `solon.dataSources` 后，一般 **无需** 手写 `@Bean` 注册 DataSource。

### 1.2 可选：属性前缀 + 手动 `@Bean`

```yaml
# app.yml
db1:
  jdbcUrl: jdbc:mysql://localhost:3306/demo
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
```

```java
@Configuration
public class DataSourceConfig {
    @Bean(name = "db1", typed = true) // typed=true 等价于 @Primary
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        return ds; // @Inject("${db1}") 自动绑定配置
    }
}
```

### 1.3 连接池与依赖

| 连接池 | Spring 依赖 | Solon 依赖 |
|--------|------------|------------|
| HikariCP | spring-boot-starter-jdbc（内含） | `com.zaxxer:HikariCP`（显式引入）+ `solon-data`（通常由 `solon-web`/`solon-lib` 传递） |
| Druid | druid-spring-boot-starter | `com.alibaba:druid` + 在 `solon.dataSources` 中指定 `class` |

轻量 SQL（替代 JdbcTemplate）额外引入：

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-sqlutils</artifactId>
</dependency>
```

---

## 2. JdbcTemplate → SqlUtils

`JdbcTemplate` / `NamedParameterJdbcTemplate` 在 Solon 中用 **`solon-data-sqlutils`** 的 `SqlUtils` 替代。

### 2.1 依赖

```xml
<!-- Spring Before：通常随 spring-boot-starter-jdbc -->
<!-- Solon After -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-sqlutils</artifactId>
</dependency>
```

### 2.2 注入方式

```java
// Spring Before
@Service
public class UserService {
    @Autowired
    private JdbcTemplate jdbcTemplate;
}

// Solon After
@Component
public class UserService {
    @Inject // 默认数据源
    SqlUtils sqlUtils;

    @Inject("db2") // 指定数据源名
    SqlUtils sqlUtils2;

    // 也可：SqlUtils.of(dataSource) / SqlUtils.ofName("db1")
}
```

### 2.3 API 对照（最小可运行）

```java
// Spring Before
User user = jdbcTemplate.queryForObject(
    "SELECT * FROM users WHERE id=?",
    new BeanPropertyRowMapper<>(User.class), id);

List<User> list = jdbcTemplate.query(
    "SELECT * FROM users",
    new BeanPropertyRowMapper<>(User.class));

jdbcTemplate.update("UPDATE users SET name=? WHERE id=?", name, id);

// Solon After
User user = sqlUtils.sql("SELECT * FROM users WHERE id=?", id)
        .queryRow(User.class);

List<User> list = sqlUtils.sql("SELECT * FROM users")
        .queryRowList(User.class);

sqlUtils.sql("UPDATE users SET name=? WHERE id=?", name, id).update();

Long newId = sqlUtils.sql("INSERT INTO users(name) VALUES(?)", name)
        .updateReturnKey();
```

> **禁止**编造不存在的 API（如 `findById`、`queryRowList(...).toBeanList(...)`、`new SqlUtils(ds)`）。正确入口是注入 / `SqlUtils.of(ds)`，以及 `sql(...).queryRow / queryRowList / update`。

### 2.4 注入注解选择

| 场景 | 注解 |
|------|------|
| 普通 Bean / SqlUtils / DataSource | `@Inject` 或 `@Inject("name")` |
| MyBatis Mapper / BaseMapper | **`@Db`** 或 `@Db("name")` |
| 不要把 `@Db` 当成通用 `@Autowired` 替代 | — |

---

## 3. MyBatis 迁移

### 3.1 依赖替换

```xml
<!-- Spring Before -->
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>3.0.3</version>
</dependency>

<!-- Solon After -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>mybatis-solon-plugin</artifactId>
</dependency>
```

### 3.2 Mapper 扫描与注册

**Spring Before：**

```java
@SpringBootApplication
@MapperScan("com.example.demo.mapper")
public class DemoApplication { ... }

@Mapper
public interface UserMapper {
    User selectById(Long id);
    int insert(User user);
}
```

**Solon After：**

```java
// 无需 @MapperScan，自动扫描（范围由主类包路径决定）
@SolonMain
public class DemoApplication {
    public static void main(String[] args) {
        Solon.start(DemoApplication.class, args);
    }
}

// Mapper 接口无需 @Mapper
public interface UserMapper {
    User selectById(Long id);
    int insert(User user);
}
```

### 3.3 Mapper 注入

```java
@Controller
public class DemoController {
    @Db // 默认数据源
    UserMapper userMapper;

    @Db("db2") // 指定数据源
    UserMapper userMapper2;

    // 也可使用 BaseMapper 泛型（视插件能力）
    @Db
    BaseMapper<User> userBaseMapper;
}
```

> **关键差异**：Mapper 注入用 **`@Db`**，不是 `@Autowired` / 普通 `@Inject`。

### 3.4 XML 与配置项

XML 映射文件通常无需修改。配置键名不同：

```yaml
# Spring Before
mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.example.demo.entity
  configuration:
    map-underscore-to-camel-case: true

# Solon After（app.yml）
mybatis:
  mappers: mapper/*.xml                    # 不是 mapper-locations
  typeAliases: com.example.demo.entity     # 不是 type-aliases-package
  configuration:
    mapUnderscoreToCamelCase: true         # 驼峰，非短横线
```

---

## 4. MyBatis Plus 迁移

### 4.1 依赖替换

```xml
<!-- Spring Before -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot-starter</artifactId>
    <version>3.5.5</version>
</dependency>

<!-- Solon After：自 3.5.9 起 baomidou 官方发布 Solon 适配 -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-solon-plugin</artifactId>
    <version>3.5.12</version>
</dependency>
```

### 4.2 代码迁移

```java
// Spring Before
@Mapper
public interface UserMapper extends BaseMapper<User> { }

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {
    public List<User> getUsersByAge(Integer age) {
        return lambdaQuery().eq(User::getAge, age).list();
    }
}

// Solon After：去掉 @Mapper，@Service → @Component
public interface UserMapper extends BaseMapper<User> { }

@Component
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {
    public List<User> getUsersByAge(Integer age) {
        return lambdaQuery().eq(User::getAge, age).list();
    }
}
```

> **关键差异**：
> - 依赖坐标以 baomidou 官方 Solon 插件为准（3.5.9+）。
> - `@Mapper` 移除；`@Service` → `@Component`。
> - 实体与 `ServiceImpl` 基类通常可保持兼容。

---

## 5. JPA 迁移

### 5.1 依赖替换

```xml
<!-- Spring Before -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- Solon After -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-jpa</artifactId>
</dependency>
```

### 5.2 实体与 Repository

JPA 标准注解与 Repository 接口定义通常可保留；使用端改注入：

```java
// Spring Before
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
}

// Solon After
@Component
public class UserService {
    @Inject
    private UserRepository userRepository;
}
```

### 5.3 配置项迁移

```yaml
# Spring Before
spring:
  jpa:
    database: MYSQL
    show-sql: true
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect

# Solon After（app.yml）
jpa:
  database: MYSQL
  show-sql: true
  properties:
    hibernate:
      dialect: org.hibernate.dialect.MySQL8Dialect
      hbm2ddl:
        auto: update  # 注意路径不同
```

---

## 6. 多数据源迁移

### 6.1 推荐配置

```yaml
# Spring Before
spring:
  datasource:
    primary:
      url: jdbc:mysql://localhost:3306/main_db
      username: root
      password: 123456
      driver-class-name: com.mysql.cj.jdbc.Driver
    secondary:
      url: jdbc:mysql://localhost:3306/second_db
      username: root
      password: 123456
      driver-class-name: com.mysql.cj.jdbc.Driver

# Solon After（app.yml）
solon.dataSources:
  db1!:
    class: "com.zaxxer.hikari.HikariDataSource"
    jdbcUrl: jdbc:mysql://localhost:3306/main_db
    driverClassName: com.mysql.cj.jdbc.Driver
    username: root
    password: 123456
  db2:
    class: "com.zaxxer.hikari.HikariDataSource"
    jdbcUrl: jdbc:mysql://localhost:3306/second_db
    driverClassName: com.mysql.cj.jdbc.Driver
    username: root
    password: 123456
```

### 6.2 数据源指定

```java
// Spring Before
@Autowired @Qualifier("primaryDataSource")
private DataSource primaryDs;

// Solon After — MyBatis
@Db
UserMapper userMapper;

@Db("db2")
LogMapper logMapper;

// Solon After — SqlUtils
@Inject
SqlUtils sqlUtils;

@Inject("db2")
SqlUtils sqlUtils2;
```

> **关键差异**：
> - Spring 多数据源 + MyBatis 常需多个 `SqlSessionFactory`，配置繁琐。
> - Solon 通过 `@Db("name")` / `@Inject("name")` 直接指定，省去大量样板。

---

## 7. 数据源与 ORM 陷阱速查

| 编号 | 类别 | Spring | Solon | 风险 | 说明 |
|------|------|--------|-------|------|------|
| 1 | 数据源 | `spring.datasource` 自动 | **`solon.dataSources` 自动装配（推荐）** | 高 | **一般无需**手写 `@Bean`；可选属性前缀 + `@Bean` 见 §1.2 |
| 2 | 数据源 | `@Primary` | 名称 `!` 后缀或 `@Bean(..., typed=true)` | 中 | 默认源标记方式不同 |
| 3 | 数据源 | `url` / `driver-class-name` | **`jdbcUrl`** / `driverClassName` | 高 | Hikari 用 `jdbcUrl`，勿只写 `url` |
| 4 | SQL | `JdbcTemplate` | `SqlUtils`（`solon-data-sqlutils`） | 高 | API 链式 `sql(...).queryRow/update`，勿臆造 |
| 5 | MyBatis | `@Mapper` + `@MapperScan` | 插件扫描；接口通常无需 `@Mapper` | 中 | 确保在扫描包路径下 |
| 6 | MyBatis | `mapper-locations` | `mappers` 等 | 中 | 配置键名不同 |
| 7 | MyBatis | `@Autowired` Mapper | **`@Db` / `@Db("name")`** | 高 | 不要用 `@Inject` 当 Mapper 注入的唯一习惯替代而不核对 |
| 8 | 多源 | `@Qualifier` | `@Db("name")` / `@Inject("name")` | 中 | SqlUtils 用 `@Inject`；Mapper 用 `@Db` |
| 9 | JPA | `spring.jpa.*` | `jpa.*`（以插件文档为准） | 低 | 前缀变更 |

---

## 8. 迁移检查清单

- [ ] `application.yml` → `app.yml`
- [ ] `spring.datasource.*` → **`solon.dataSources.*`（推荐）**；仅在有充分理由时用自定义前缀 + `@Bean`
- [ ] 连接串键使用 **`jdbcUrl`**（Hikari）；驱动键 `driverClassName`
- [ ] 默认数据源：`name!` 或 `typed=true`
- [ ] `JdbcTemplate` → `solon-data-sqlutils` / `SqlUtils`
- [ ] MyBatis：`mybatis-spring-boot-starter` → `mybatis-solon-plugin`
- [ ] Mapper 注入：`@Autowired` → **`@Db`**
- [ ] 去掉 `@Mapper` / `@MapperScan`（按插件扫描规则）
- [ ] MyBatis 配置键：`mapper-locations` → `mappers` 等
- [ ] `@Service` → `@Component`，禁止残留 Spring 数据注解混用
- [ ] 事务 / 缓存 / Redis 见 `transaction_cache_migration.md`
