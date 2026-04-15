# 数据源与 ORM 迁移参考

> Spring Boot → Solon 数据访问层迁移指南（目标版本：Solon 3.10.x）
>
> 本文档聚焦数据源配置、MyBatis、MyBatis Plus、JPA 及多数据源五大主题，提供代码对照与差异陷阱标注。

---

## 目录

- [1. 数据源配置迁移](#1-数据源配置迁移)
- [2. MyBatis 迁移](#2-mybatis-迁移)
- [3. MyBatis Plus 迁移](#3-mybatis-plus-迁移)
- [4. JPA 迁移](#4-jpa-迁移)
- [5. 多数据源迁移](#5-多数据源迁移)

---

## 1. 数据源配置迁移

### 1.1 YAML 配置对照

**Spring Before：**

```yaml
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

**Solon After：**

```yaml
db1:  # 自定义名称，不再有 spring.datasource 前缀
  url: jdbc:mysql://localhost:3306/demo
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
  # 连接池参数直接写在顶层，非 hikari 子节点
  minimumIdle: 5
  maximumPoolSize: 20
  poolName: DemoHikariCP
```

> **关键差异**：
> - Spring 使用 `spring.datasource` 固定前缀；Solon 使用自定义名称（如 `db1`）作为前缀。
> - `driver-class-name`（短横线）→ `driverClassName`（驼峰）。
> - 连接池参数从 `hikari.*` 子节点移至数据源顶层。

### 1.2 数据源 Bean 注册

**Spring Before：**

```java
@Configuration
public class DataSourceConfig {
    @Bean @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }
}
```

**Solon After：**

```java
@Configuration
public class DataSourceConfig {
    @Bean(name = "db1", typed = true)  // typed=true 等价于 @Primary
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        return ds;  // @Inject("${db1}") 自动绑定配置，无需 @ConfigurationProperties
    }
}
```

> **关键差异**：Solon 需显式 `@Bean` 注册（无自动配置）；`typed = true` 等价 `@Primary`。

### 1.3 连接池选型

| 连接池 | Spring 依赖 | Solon 依赖 |
|--------|------------|------------|
| HikariCP | spring-boot-starter-jdbc（内含） | `solon-data-dynamicds`（内含）或单独引入 |
| Druid | druid-spring-boot-starter | `com.alibaba:druid` + 手动配置 Bean |

---

## 2. MyBatis 迁移

### 2.1 依赖替换

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

### 2.2 Mapper 扫描与注册

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
// 无需 @MapperScan，自动扫描
public class DemoApplication {
    public static void main(String[] args) {
        Solon.start(DemoApplication.class, args);
    }
}

// Mapper 接口无需任何注解
public interface UserMapper {
    User selectById(Long id);
    int insert(User user);
}
```

> **关键差异**：无需 `@MapperScan` 和 `@Mapper`；扫描范围由主类包路径决定。

### 2.3 Mapper 注入方式

**模式一：BaseMapper 泛型（无需定义 Mapper 接口）**

```java
@Controller
public class DemoController {
    @Db  // 默认关联 typed=true 的数据源
    BaseMapper<UserModel> userMapper;

    @Mapping("/user/{id}")
    public UserModel getUser(long id) {
        return userMapper.selectById(id);
    }
}
```

**模式二：Mapper 接口注入**

```java
@Controller
public class DemoController {
    @Db("db1")  // 指定数据源
    UserMapper userMapper;
}
```

> **关键差异**：`@Autowired` → `@Db`；`@Db` 无参注入默认数据源，`@Db("db1")` 指定数据源。

### 2.4 XML 映射文件

XML 映射文件无需修改，Spring 和 Solon 通用，保持原样即可。

### 2.5 配置项迁移

```yaml
# Spring Before
mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.example.demo.entity
  configuration:
    map-underscore-to-camel-case: true

# Solon After
mybatis:
  mappers: mapper/*.xml                    # 注意：不是 mapper-locations
  typeAliases: com.example.demo.entity     # 不是 type-aliases-package
  configuration:
    mapUnderscoreToCamelCase: true         # 驼峰风格，非短横线
```

> **常见陷阱**：`mapper-locations` → `mappers`、`type-aliases-package` → `typeAliases`、`map-underscore-to-camel-case` → `mapUnderscoreToCamelCase`，键名完全不同，务必逐项核对。

---

## 3. MyBatis Plus 迁移

### 3.1 依赖替换

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

### 3.2 代码迁移

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
> - 依赖从 `org.noear` 改为 `com.baomidou`（3.5.9+）。
> - `@Mapper` 移除（自动扫描）；`@Service` → `@Component`。
> - 实体类和 `ServiceImpl` 基类保持兼容，无需修改。

---

## 4. JPA 迁移

### 4.1 依赖替换

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

### 4.2 实体类与 Repository 迁移

JPA 实体类基于标准注解，无需修改。Repository 接口定义也保持兼容：

```java
// 接口定义 Spring/Solon 通用
public interface UserRepository extends JpaRepository<User, Long> {
    List<User> findByName(String name);

    @Query("SELECT u FROM User u WHERE u.email LIKE %:keyword%")
    List<User> findByEmailKeyword(@Param("keyword") String keyword);
}
```

使用端差异：

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
    @Inject  // Solon 的 @Inject
    private UserRepository userRepository;
}
```

> **关键差异**：`@Autowired` → `@Inject`；`@Service` → `@Component`。

### 4.3 配置项迁移

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

# Solon After
jpa:
  database: MYSQL
  show-sql: true
  properties:
    hibernate:
      dialect: org.hibernate.dialect.MySQL8Dialect
      hbm2ddl:
        auto: update  # 注意路径不同
```

> **关键差异**：前缀 `spring.jpa` → `jpa`；`hibernate.ddl-auto` → `hibernate.hbm2ddl.auto`。

---

## 5. 多数据源迁移

### 5.1 多数据源配置

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

# Solon After
db1:
  url: jdbc:mysql://localhost:3306/main_db
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
db2:
  url: jdbc:mysql://localhost:3306/second_db
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
```

### 5.2 多数据源 Bean 注册

```java
// Spring Before
@Bean @Primary
@ConfigurationProperties(prefix = "spring.datasource.primary")
public DataSource primaryDataSource() {
    return DataSourceBuilder.create().build();
}

// Solon After
@Bean(name = "db1", typed = true)  // typed=true = 默认数据源
public DataSource db1(@Inject("${db1}") HikariDataSource ds) { return ds; }

@Bean(name = "db2")  // 不设 typed，需 @Db("db2") 显式指定
public DataSource db2(@Inject("${db2}") HikariDataSource ds) { return ds; }
```

### 5.3 数据源指定

```java
// Spring Before
@Autowired @Qualifier("primaryDataSource")
private DataSource primaryDs;

// Solon After
@Db                     // 注入默认数据源（typed=true 的 db1）
BaseMapper<Order> orderMapper;

@Db("db2")              // 指定 db2 数据源
BaseMapper<Log> logMapper;
```

> **关键差异**：
> - Spring 多数据源 + MyBatis 需配置多个 `SqlSessionFactory`，极其繁琐。
> - Solon 通过 `@Db("name")` 直接指定数据源，省去大量配置代码。
> - 同一类中可轻松注入不同数据源的 Mapper。
