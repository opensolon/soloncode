# 数据访问迁移参考手册

> Spring Boot → Solon 数据访问层迁移指南（目标版本：Solon 3.10.x）
>
> 本文档聚焦数据源配置、MyBatis、MyBatis Plus、JPA、事务管理、缓存、Redis、多数据源共八大主题，提供完整的代码对照、配置对照及差异陷阱标注。

---

## 目录

- [1. 数据源配置迁移](#1-数据源配置迁移)
  - [1.1 YAML 配置对照](#11-yaml-配置对照)
  - [1.2 数据源 Bean 注册](#12-数据源-bean-注册)
  - [1.3 连接池选型](#13-连接池选型)
- [2. MyBatis 迁移](#2-mybatis-迁移)
  - [2.1 依赖替换](#21-依赖替换)
  - [2.2 Mapper 扫描与注册](#22-mapper-扫描与注册)
  - [2.3 Mapper 注入方式](#23-mapper-注入方式)
  - [2.4 XML 映射文件](#24-xml-映射文件)
  - [2.5 配置项迁移](#25-配置项迁移)
- [3. MyBatis Plus 迁移](#3-mybatis-plus-迁移)
  - [3.1 依赖替换](#31-依赖替换)
  - [3.2 代码迁移](#32-代码迁移)
- [4. JPA 迁移](#4-jpa-迁移)
  - [4.1 依赖替换](#41-依赖替换)
  - [4.2 实体类迁移](#42-实体类迁移)
  - [4.3 Repository 迁移](#43-repository-迁移)
  - [4.4 配置项迁移](#44-配置项迁移)
- [5. 事务管理迁移](#5-事务管理迁移)
  - [5.1 注解对照](#51-注解对照)
  - [5.2 传播机制与隔离级别](#52-传播机制与隔离级别)
  - [5.3 回滚策略差异](#53-回滚策略差异)
  - [5.4 编程式事务](#54-编程式事务)
- [6. 缓存迁移](#6-缓存迁移)
  - [6.1 注解对照](#61-注解对照)
  - [6.2 缓存标签机制](#62-缓存标签机制)
  - [6.3 缓存服务接口](#63-缓存服务接口)
- [7. Redis 迁移](#7-redis-迁移)
  - [7.1 依赖替换](71-依赖替换)
  - [7.2 配置迁移](#72-配置迁移)
  - [7.3 RedisTemplate → RedisService](#73-redistemplate--redisservice)
  - [7.4 高性能替代方案：RedisX](#74-高性能替代方案redisx)
- [8. 多数据源迁移](#8-多数据源迁移)
  - [8.1 多数据源配置](#81-多数据源配置)
  - [8.2 多数据源 Bean 注册](#82-多数据源-bean-注册)
  - [8.3 数据源指定](#83-数据源指定)
- [9. 数据访问层陷阱与差异清单](#9-数据访问层陷阱与差异清单)

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
    # 连接池配置（HikariCP）
    hikari:
      minimum-idle: 5
      maximum-pool-size: 20
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-timeout: 30000
      pool-name: DemoHikariCP
```

**Solon After：**

```yaml
# 自定义数据源名称（如 db1），不再有 spring.datasource 前缀
db1:
  url: jdbc:mysql://localhost:3306/demo
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
  # 连接池配置直接写在数据源节点下
  minimumIdle: 5
  maximumPoolSize: 20
  idleTimeout: 600000
  maxLifetime: 1800000
  connectionTimeout: 30000
  poolName: DemoHikariCP
```

> **关键差异**：
> - Spring 使用 `spring.datasource` 固定前缀；Solon 使用自定义名称（如 `db1`、`db2`）作为前缀，更灵活。
> - `driver-class-name`（Spring 驼峰短横线风格）→ `driverClassName`（Solon 驼峰风格）。
> - 连接池参数从 `spring.datasource.hikari.*` 子节点移至数据源顶层节点。

### 1.2 数据源 Bean 注册

**Spring Before：**

```java
// Spring Boot 自动配置，一般无需手动注册
// 仅在多数据源或特殊定制时才手动配置
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
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

    /**
     * 注册数据源 Bean。
     * name = "db1" 与 YAML 中的 db1 前缀对应。
     * typed = true 标记为默认（主）数据源，可被 @Db 无参注入时自动选用。
     */
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        // @Inject("${db1}") 自动将 db1 节点下的配置绑定到 HikariDataSource
        return ds;
    }
}
```

> **关键差异**：
> - Solon 需要显式通过 `@Bean` 注册数据源，不像 Spring Boot 有自动配置。
> - `typed = true` 等价于 Spring 的 `@Primary`，表示默认数据源。
> - `@Inject("${db1}")` 直接将配置段绑定到连接池对象，无需 `@ConfigurationProperties`。

### 1.3 连接池选型

| 连接池 | Spring 依赖 | Solon 依赖 |
|--------|------------|------------|
| HikariCP | spring-boot-starter-jdbc（内含） | `org.noear:solon-data-dynamicds`（内含）或单独引入 HikariCP |
| Druid | druid-spring-boot-starter | `com.alibaba:druid` + 手动配置 Bean |
| Tomcat JDBC | spring-boot-starter-jdbc（可选） | 不常用，建议使用 HikariCP |

---

## 2. MyBatis 迁移

### 2.1 依赖替换

**Spring Before：**

```xml
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>3.0.3</version>
</dependency>
```

**Solon After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>mybatis-solon-plugin</artifactId>
</dependency>
```

### 2.2 Mapper 扫描与注册

**Spring Before：**

```java
// 需要在启动类或配置类上声明扫描路径
@SpringBootApplication
@MapperScan("com.example.demo.mapper")
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

// 每个 Mapper 接口需要加 @Mapper 注解
@Mapper
public interface UserMapper {
    User selectById(Long id);
    List<User> selectAll();
    int insert(User user);
    int updateById(User user);
    int deleteById(Long id);
}
```

**Solon After：**

```java
// 无需 @MapperScan，Solon 自动扫描 Mapper 接口
// 启动类保持简洁
public class DemoApplication {
    public static void main(String[] args) {
        Solon.start(DemoApplication.class, args);
    }
}

// Mapper 接口无需任何注解
public interface UserMapper {
    User selectById(Long id);
    List<User> selectAll();
    int insert(User user);
    int updateById(User user);
    int deleteById(Long id);
}
```

> **关键差异**：
> - Solon 不需要 `@MapperScan` 注解，插件会自动发现 Mapper 接口。
> - Mapper 接口不需要 `@Mapper` 注解标记。
> - 扫描范围由 Solon 的应用主类包路径决定（与 `@ComponentScan` 一致）。

### 2.3 Mapper 注入方式

Solon 提供两种 Mapper 注入方式，对应不同的使用模式：

**模式一：BaseMapper 泛型（SqlUtils 模式）**

```java
@Controller
public class DemoController {

    /**
     * 直接注入 BaseMapper 泛型，无需定义 Mapper 接口。
     * 适用于简单的 CRUD 操作，类似 MyBatis Plus 的 BaseMapper。
     * 默认关联 typed=true 的数据源。
     */
    @Db
    BaseMapper<UserModel> userMapper;

    @Mapping("/user/{id}")
    public UserModel getUser(long id) {
        return userMapper.selectById(id);
    }

    @Mapping("/user/add")
    public String addUser(UserModel user) {
        userMapper.insert(user, true); // true 表示使用字段名作为列名
        return "ok";
    }
}
```

**模式二：Mapper 接口注入**

```java
@Controller
public class DemoController {

    /**
     * 注入自定义 Mapper 接口，适用于复杂 SQL 场景。
     * 可通过 @Db("db1") 指定数据源名称。
     */
    @Db("db1")
    UserMapper userMapper;

    @Mapping("/user/{id}")
    public User getUser(long id) {
        return userMapper.selectById(id);
    }
}
```

> **关键差异**：
> - Spring 使用 `@Autowired` 或 `@Resource` 注入 Mapper；Solon 使用 `@Db` 注解。
> - `@Db` 无参时注入默认数据源（`typed=true`）。
> - `@Db("db1")` 指定名为 `db1` 的数据源。
> - BaseMapper 泛型模式无需编写 Mapper 接口即可完成基本 CRUD。

### 2.4 XML 映射文件

XML 映射文件无需修改，保持原样即可：

```xml
<!-- resources/mapper/UserMapper.xml -->
<!-- Spring 和 Solon 通用 -->
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.demo.mapper.UserMapper">

    <select id="selectById" resultType="com.example.demo.entity.User">
        SELECT * FROM user WHERE id = #{id}
    </select>

    <select id="selectAll" resultType="com.example.demo.entity.User">
        SELECT * FROM user ORDER BY id DESC
    </select>

    <insert id="insert" parameterType="com.example.demo.entity.User"
            useGeneratedKeys="true" keyProperty="id">
        INSERT INTO user(name, age, email)
        VALUES(#{name}, #{age}, #{email})
    </insert>

    <update id="updateById" parameterType="com.example.demo.entity.User">
        UPDATE user SET name=#{name}, age=#{age}, email=#{email}
        WHERE id=#{id}
    </update>

    <delete id="deleteById">
        DELETE FROM user WHERE id = #{id}
    </delete>
</mapper>
```

> **配置项迁移**：XML 映射文件路径配置方式不同，见 [2.5 配置项迁移](#25-配置项迁移)。

### 2.5 配置项迁移

**Spring Before：**

```yaml
mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.example.demo.entity
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

**Solon After：**

```yaml
# Solon 的 MyBatis 配置位于 mybatis 节点下
mybatis:
  mappers: mapper/*.xml                    # 注意：不是 mapper-locations
  typeAliases: com.example.demo.entity     # 注意：不是 type-aliases-package
  configuration:
    mapUnderscoreToCamelCase: true         # 使用驼峰风格，非短横线
    logImpl: org.apache.ibatis.logging.stdout.StdOutImpl
```

> **常见陷阱**：
> - `mapper-locations` → `mappers`：键名完全不同，且路径前缀 `classpath:` 可以省略。
> - `type-aliases-package` → `typeAliases`：键名与风格均不同。
> - `map-underscore-to-camel-case` → `mapUnderscoreToCamelCase`：Solon 使用标准驼峰命名。
> - 迁移时务必逐项核对，不要假设键名兼容。

---

## 3. MyBatis Plus 迁移

### 3.1 依赖替换

**Spring Before：**

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot-starter</artifactId>
    <version>3.5.5</version>
</dependency>
```

**Solon After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>mybatis-plus-solon-plugin</artifactId>
</dependency>
```

### 3.2 代码迁移

**Spring Before：**

```java
// 实体类 - Spring 和 Solon 通用
@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer age;
    private String email;
}

// Mapper 接口
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 继承 BaseMapper 即可获得 CRUD 方法
}

// Service 层
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    @Override
    public List<User> getUsersByAge(Integer age) {
        return lambdaQuery().eq(User::getAge, age).list();
    }
}
```

**Solon After：**

```java
// 实体类保持不变
@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer age;
    private String email;
}

// Mapper 接口 - 去掉 @Mapper 注解
public interface UserMapper extends BaseMapper<User> {
    // 继承 BaseMapper 即可获得 CRUD 方法
}

// Service 层 - 使用 Solon 的注解
@Component
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    @Override
    public List<User> getUsersByAge(Integer age) {
        return lambdaQuery().eq(User::getAge, age).list();
    }
}
```

> **关键差异**：
> - 用法基本相同，核心差异在依赖包替换。
> - `@Mapper` 注解移除（Solon 自动扫描）。
> - `@Service` → `@Component`（Solon 不区分 Service/Repository/Controller 注解语义，统一使用 `@Component`；但 `@Controller` 在 Web 层仍保留）。
> - `ServiceImpl` 基类保持兼容。

---

## 4. JPA 迁移

### 4.1 依赖替换

**Spring Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

**Solon After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-jpa</artifactId>
</dependency>
```

### 4.2 实体类迁移

**Spring Before：**

```java
@Entity
@Table(name = "user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "age")
    private Integer age;

    @Column(name = "email", length = 200)
    private String email;

    // getter / setter 省略
}
```

**Solon After：**

```java
// JPA 实体类完全兼容，无需修改
@Entity
@Table(name = "user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "age")
    private Integer age;

    @Column(name = "email", length = 200)
    private String email;

    // getter / setter 省略
}
```

> **说明**：JPA 实体类基于 JPA 标准注解，与框架无关，迁移时无需修改。

### 4.3 Repository 迁移

**Spring Before：**

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // Spring Data JPA 方法命名查询
    List<User> findByName(String name);

    List<User> findByAgeGreaterThan(Integer age);

    @Query("SELECT u FROM User u WHERE u.email LIKE %:keyword%")
    List<User> findByEmailKeyword(@Param("keyword") String keyword);
}

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public User save(User user) {
        return userRepository.save(user);
    }
}
```

**Solon After：**

```java
// Repository 接口定义保持兼容
public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findByName(String name);

    List<User> findByAgeGreaterThan(Integer age);

    @Query("SELECT u FROM User u WHERE u.email LIKE %:keyword%")
    List<User> findByEmailKeyword(@Param("keyword") String keyword);
}

@Component
public class UserService {

    @Inject  // Solon 的 @Inject 替代 @Autowired
    private UserRepository userRepository;

    public User save(User user) {
        return userRepository.save(user);
    }
}
```

> **关键差异**：
> - Repository 接口定义保持不变（基于 JPA 标准）。
> - `@Autowired` → `@Inject`（Solon 使用 `org.noear.solon.annotation.Inject`）。
> - `@Service` → `@Component`。

### 4.4 配置项迁移

**Spring Before：**

```yaml
spring:
  jpa:
    database: MYSQL
    show-sql: true
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
        format_sql: true
```

**Solon After：**

```yaml
jpa:
  database: MYSQL
  show-sql: true
  properties:
    hibernate:
      dialect: org.hibernate.dialect.MySQL8Dialect
      hbm2ddl:
        auto: update        # DDL 策略
      format_sql: true
```

> **关键差异**：
> - 前缀从 `spring.jpa` 变为 `jpa`。
> - `spring.jpa.hibernate.ddl-auto` 的路径映射到 `jpa.properties.hibernate.hbm2ddl.auto`。
> - 其余 JPA 标准属性基本兼容。

---

## 5. 事务管理迁移

### 5.1 注解对照

| 特性 | Spring | Solon |
|------|--------|-------|
| 注解 | `@Transactional` | `@Transaction` |
| 所属包 | `org.springframework.transaction.annotation` | `org.noear.solon.annotation` |
| 类级别 | 支持 | 支持 |
| 方法级别 | 支持 | 支持 |
| 默认回滚 | 仅 `RuntimeException` | 所有异常（`Exception`） |

### 5.2 传播机制与隔离级别

**Spring Before：**

```java
@Service
public class OrderService {

    // 默认传播：REQUIRED
    @Transactional
    public void createOrder(Order order) {
        orderMapper.insert(order);
        inventoryService.deduct(order.getProductId(), order.getQuantity());
    }

    // 指定传播行为和隔离级别
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED,
        rollbackFor = Exception.class
    )
    public void updateStock(Long productId, int quantity) {
        productMapper.updateStock(productId, quantity);
    }

    // 不开启事务，以无事务方式执行
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Product getProduct(Long id) {
        return productMapper.selectById(id);
    }
}
```

**Solon After：**

```java
@Component
public class OrderService {

    // 默认传播：REQUIRED（与 Spring 一致）
    @Transaction
    public void createOrder(Order order) {
        orderMapper.insert(order);
        inventoryService.deduct(order.getProductId(), order.getQuantity());
    }

    // 指定传播行为和隔离级别
    @Transaction(
        propagation = Propagation.REQUIRES_NEW,    // 同样支持全部传播类型
        isolation = Isolation.READ_COMMITTED
        // 注意：不需要 rollbackFor，Solon 默认所有异常都回滚
    )
    public void updateStock(Long productId, int quantity) {
        productMapper.updateStock(productId, quantity);
    }

    // 不开启事务
    @Transaction(propagation = Propagation.NOT_SUPPORTED)
    public Product getProduct(Long id) {
        return productMapper.selectById(id);
    }
}
```

> **关键差异**：
> - 传播机制（`Propagation`）和隔离级别（`Isolation`）枚举值完全兼容，可直接迁移。
> - 注解名从 `@Transactional` → `@Transaction`（少了 `al` 后缀）。
> - `Propagation` 和 `Isolation` 来自 Solon 的包路径。

### 5.3 回滚策略差异

**Spring Before：**

```java
// Spring 默认仅回滚 RuntimeException，必须显式指定 rollbackFor
@Transactional(rollbackFor = Exception.class)
public void transferMoney(Long fromId, Long toId, BigDecimal amount) {
    accountMapper.deduct(fromId, amount);
    accountMapper.add(toId, amount);
    // 如果此处抛出 checked Exception，默认不会回滚
}

// 指定不回滚的异常
@Transactional(noRollbackFor = BusinessException.class)
public void processOrder(Order order) {
    orderMapper.update(order);
}
```

**Solon After：**

```java
// Solon 默认回滚所有 Exception，无需指定 rollbackFor
@Transaction
public void transferMoney(Long fromId, Long toId, BigDecimal amount) {
    accountMapper.deduct(fromId, amount);
    accountMapper.add(toId, amount);
    // 任何异常都会回滚，包括 checked Exception
}

// Solon 不支持 noRollbackFor，如需跳过回滚需编程式处理
@Transaction
public void processOrder(Order order) {
    orderMapper.update(order);
}
```

> **常见陷阱**：
> - Spring 中 `@Transactional` 默认仅回滚 `RuntimeException` 和 `Error`，checked Exception 不回滚。很多开发者习惯加 `rollbackFor = Exception.class`。
> - Solon 中 `@Transaction` 默认回滚所有 `Exception`，行为更安全，但也意味着迁移时可以安全移除 `rollbackFor`。
> - Solon 当前版本不支持 `noRollbackFor` 属性。

### 5.4 编程式事务

**Spring Before：**

```java
@Service
public class PaymentService {

    @Autowired
    private TransactionTemplate transactionTemplate;

    public void processPayment(Payment payment) {
        // 编程式事务
        transactionTemplate.execute(status -> {
            paymentMapper.insert(payment);
            accountMapper.deduct(payment.getAccountId(), payment.getAmount());
            return null;
        });
    }
}
```

**Solon After：**

```java
@Component
public class PaymentService {

    @Inject
    private DataSource dataSource;  // 注入数据源

    public void processPayment(Payment payment) {
        // 使用 Solon 的编程式事务工具
        DataSourceManager.execute(dataSource, () -> {
            paymentMapper.insert(payment);
            accountMapper.deduct(payment.getAccountId(), payment.getAmount());
        });
    }
}
```

---

## 6. 缓存迁移

### 6.1 注解对照

| Spring | Solon | 说明 |
|--------|-------|------|
| `@Cacheable` | `@Cache` | 缓存读取/写入 |
| `@CacheEvict` | `@CacheRemove` | 缓存移除 |
| `@CachePut` | `@Cache`（配合 `@CachePut` 语义） | 缓存更新 |
| `@Caching` | 不支持 | 多缓存组合操作 |
| `@CacheConfig` | 不需要 | 类级别缓存配置 |

### 6.2 缓存标签机制

**Spring Before：**

```java
@Service
public class UserService {

    // Spring 基于缓存名称 + Key 管理缓存
    @Cacheable(value = "users", key = "#id")
    public User getUserById(Long id) {
        return userMapper.selectById(id);
    }

    // 条件缓存
    @Cacheable(value = "users", key = "#id", unless = "#result == null")
    public User getUserIfExists(Long id) {
        return userMapper.selectById(id);
    }

    // 更新缓存
    @CachePut(value = "users", key = "#user.id")
    public User updateUser(User user) {
        userMapper.updateById(user);
        return user;
    }

    // 清除缓存
    @CacheEvict(value = "users", key = "#id")
    public void deleteUser(Long id) {
        userMapper.deleteById(id);
    }

    // 清除整个缓存区域
    @CacheEvict(value = "users", allEntries = true)
    public void clearAllUsers() {
        // 清除 users 缓存下的所有条目
    }
}
```

**Solon After：**

```java
@Component
public class UserService {

    // Solon 基于标签（tag）管理缓存，支持 SpEL 表达式
    @Cache(tags = "user_${id}")
    public User getUserById(long id) {
        return userMapper.selectById(id);
    }

    // 条件缓存 - 使用 condition 属性
    @Cache(tags = "user_${id}", condition = "result != null")
    public User getUserIfExists(long id) {
        return userMapper.selectById(id);
    }

    // 更新时移除旧缓存（@CacheRemove 负责清除，之后调用 @Cache 写入）
    @CacheRemove(tags = "user_${user.id}")
    public User updateUser(User user) {
        userMapper.updateById(user);
        return user;
    }

    // 清除指定标签的缓存
    @CacheRemove(tags = "user_${id}")
    public void deleteUser(long id) {
        userMapper.deleteById(id);
    }

    // 通过标签模式批量清除
    @CacheRemove(tags = "user_*")
    public void clearAllUsers() {
        // 清除所有 user_ 前缀的缓存
    }
}
```

> **关键差异**：
> - Spring 使用 `value`（缓存区域名）+ `key` 双维度；Solon 使用 `tags`（标签）单维度，更简洁。
> - Solon 的 `tags` 支持 `${参数名}` 占位符语法，类似 SpEL 但更轻量。
> - Solon 的 `@CacheRemove` 通过 `tags` 模式匹配批量清除缓存（如 `user_*`），比 Spring 的 `allEntries` 更灵活。
> - Spring 的 `@CachePut`（只更新缓存不执行逻辑）在 Solon 中通过 `@Cache` + `@CacheRemove` 组合实现。

### 6.3 缓存服务接口

**Spring Before：**

```java
@Service
public class CacheService {

    @Autowired
    private CacheManager cacheManager;

    // 编程式操作缓存
    public void manualCacheOps() {
        Cache cache = cacheManager.getCache("users");
        cache.put("user_1", new User(1L, "Alice"));
        User user = cache.get("user_1", User.class);
        cache.evict("user_1");
    }
}
```

**Solon After：**

```java
@Component
public class CacheService {

    @Inject
    private CacheService cacheService;  // Solon 内置的缓存服务接口

    // 编程式操作缓存
    public void manualCacheOps() {
        // 通过标签存储和获取
        cacheService.store("user_1", new User(1L, "Alice"), 3600);  // 3600 秒过期
        User user = cacheService.get("user_1", User.class);
        cacheService.remove("user_1");
    }
}
```

> **常见陷阱**：
> - Solon 的缓存抽象与 Spring Cache 不兼容，需要全量替换注解和编程式调用。
> - 迁移时先确定缓存实现（如基于 Redis 的 `solon-data-redis` 作为缓存后端），再替换注解。

---

## 7. Redis 迁移

### 7.1 依赖替换

**Spring Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**Solon After：**

```xml
<!-- 方案一：solon-data-redis（基于 RedisService 的轻量封装） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-redis</artifactId>
</dependency>

<!-- 方案二：solon-data-redisx（基于 RedisX 的高性能方案） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-redisx</artifactId>
</dependency>
```

### 7.2 配置迁移

**Spring Before：**

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: your_password
      database: 0
      timeout: 5000
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5
          max-wait: 3000
```

**Solon After：**

```yaml
# solon-data-redis 配置
redis:
  host: localhost
  port: 6379
  password: your_password
  database: 0
  timeout: 5000
  maxTotal: 20        # 最大连接数（基于连接池参数）
  maxIdle: 10
  minIdle: 5
  maxWait: 3000
```

### 7.3 RedisTemplate → RedisService

**Spring Before：**

```java
@Service
public class RedisDemoService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 字符串操作
    public void setString(String key, String value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public String getString(String key) {
        return (String) redisTemplate.opsForValue().get(key);
    }

    // Hash 操作
    public void setHash(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    public Object getHash(String key, String field) {
        return redisTemplate.opsForHash().get(key, field);
    }

    // Key 操作
    public Boolean deleteKey(String key) {
        return redisTemplate.delete(key);
    }

    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    // 过期时间
    public Boolean expire(String key, long timeout, TimeUnit unit) {
        return redisTemplate.expire(key, timeout, unit);
    }
}
```

**Solon After：**

```java
@Component
public class RedisDemoService {

    @Inject
    private RedisService redisService;

    // 字符串操作 - API 更简洁
    public void setString(String key, String value, long seconds) {
        redisService.set(key, value, seconds);  // 直接传秒数
    }

    public String getString(String key) {
        return redisService.get(key);           // 直接返回 String
    }

    // Hash 操作
    public void setHash(String key, String field, Object value) {
        redisService.hashSet(key, field, value);
    }

    public Object getHash(String key, String field) {
        return redisService.hashGet(key, field);
    }

    // Key 操作
    public Boolean deleteKey(String key) {
        return redisService.delete(key);
    }

    public Boolean hasKey(String key) {
        return redisService.exists(key);
    }

    // 过期时间
    public Boolean expire(String key, long seconds) {
        return redisService.expire(key, seconds);
    }
}
```

> **关键差异**：
> - `RedisTemplate` → `RedisService`：API 风格从 `opsForValue()`, `opsForHash()` 链式调用变为直接方法调用。
> - 序列化策略：Spring 的 `RedisTemplate` 默认使用 JDK 序列化；Solon 的 `RedisService` 默认使用 String 序列化，存取更直观。
> - 无需手动配置 `RedisTemplate` Bean，`RedisService` 由插件自动注册。

### 7.4 高性能替代方案：RedisX

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-redisx</artifactId>
</dependency>
```

```java
@Component
public class RedisXDemoService {

    // RedisX 提供更高性能的 Redis 操作封装
    // 支持连接池管理、自动重连、集群模式等
    @Inject
    private RedisX redisX;

    public void demo() {
        // 使用 RedisX 的 API
        try (RedisSession session = redisX.openSession()) {
            session.set("key", "value", 3600);
            String val = session.get("key");
            session.delete("key");
        }
    }
}
```

> **说明**：`solon-data-redisx` 适用于高并发场景，提供更精细的连接管理和更丰富的分布式特性（如分布式锁）。

---

## 8. 多数据源迁移

### 8.1 多数据源配置

**Spring Before：**

```yaml
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
```

**Solon After：**

```yaml
# 主数据源
db1:
  url: jdbc:mysql://localhost:3306/main_db
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver

# 第二个数据源
db2:
  url: jdbc:mysql://localhost:3306/second_db
  username: root
  password: 123456
  driverClassName: com.mysql.cj.jdbc.Driver
```

### 8.2 多数据源 Bean 注册

**Spring Before：**

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary  // 标记为默认数据源
    @ConfigurationProperties(prefix = "spring.datasource.primary")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.secondary")
    public DataSource secondaryDataSource() {
        return DataSourceBuilder.create().build();
    }
}
```

**Solon After：**

```java
@Configuration
public class DataSourceConfig {

    /**
     * 主数据源（默认）。
     * typed = true 标记为默认数据源，@Db 无参时自动注入此数据源。
     */
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        return ds;
    }

    /**
     * 第二个数据源。
     * 不设置 typed，需通过 @Db("db2") 显式指定。
     */
    @Bean(name = "db2")
    public DataSource db2(@Inject("${db2}") HikariDataSource ds) {
        return ds;
    }
}
```

> **关键差异**：
> - Spring 使用 `@Primary` 标记默认数据源；Solon 使用 `typed = true`。
> - Solon 通过 `@Bean(name = "xxx")` 的 name 与 YAML 前缀对应，关系更直观。
> - `@Inject("${db1}")` 实现配置到连接池的自动绑定，无需 `@ConfigurationProperties`。

### 8.3 数据源指定

**Spring Before：**

```java
@Service
public class OrderService {

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primaryDs;

    @Autowired
    @Qualifier("secondaryDataSource")
    private DataSource secondaryDs;

    // 或配合 MyBatis 使用 @MapperScan 指定不同 SqlSessionFactory
}
```

**Solon After：**

```java
@Component
public class OrderService {

    /** 注入默认数据源的 BaseMapper（typed=true 的 db1） */
    @Db
    BaseMapper<Order> orderMapper;

    /** 注入 db2 数据源的 BaseMapper */
    @Db("db2")
    BaseMapper<Log> logMapper;

    /** 注入 db2 数据源的 Mapper 接口 */
    @Db("db2")
    LogMapper logMapper2;
}
```

> **关键差异**：
> - Spring 多数据源 + MyBatis 需要配置多个 `SqlSessionFactory` 和 `@MapperScan`，极其繁琐。
> - Solon 通过 `@Db` 注解的名称直接指定数据源，省去了大量的配置类代码。
> - 同一个类中可以轻松注入不同数据源的 Mapper。

---

## 9. 数据访问层陷阱与差异清单

| 编号 | 类别 | Spring | Solon | 风险等级 | 说明 |
|------|------|--------|-------|---------|------|
| 1 | 数据源 | 自动配置 | 需手动注册 `@Bean` | 中 | Solon 不提供数据源自动配置，必须显式声明 |
| 2 | 数据源 | `@Primary` | `typed = true` | 低 | 标记默认数据源的机制不同 |
| 3 | 数据源 | `driver-class-name` | `driverClassName` | 低 | 配置键命名风格不同 |
| 4 | MyBatis | `@MapperScan` | 自动扫描 | 低 | Solon 无需扫描注解，但需确保 Mapper 在主类包路径下 |
| 5 | MyBatis | `@Mapper` | 无需注解 | 低 | Mapper 接口不需要任何框架注解 |
| 6 | MyBatis | `mapper-locations` | `mappers` | 中 | 配置键名完全不同，容易遗漏 |
| 7 | MyBatis | `@Autowired` 注入 Mapper | `@Db` 注入 | 高 | 注解完全不同，必须全量替换 |
| 8 | 事务 | `@Transactional` | `@Transaction` | 高 | 注解名不同，全局搜索替换时注意不要误伤 |
| 9 | 事务 | 默认仅回滚 RuntimeException | 默认回滚所有 Exception | 中 | 行为更安全，但可能与原有逻辑预期不同 |
| 10 | 事务 | `rollbackFor` | 不需要 | 低 | 可安全移除，Solon 默认即回滚所有异常 |
| 11 | 事务 | `noRollbackFor` | 不支持 | 中 | 如有使用需通过编程式事务替代 |
| 12 | 缓存 | `@Cacheable` + `key` | `@Cache` + `tags` | 高 | 缓存机制完全不同，需全量重写缓存逻辑 |
| 13 | 缓存 | `@CacheEvict(allEntries=true)` | `@CacheRemove(tags="xxx_*")` | 中 | 批量清除使用通配符标签模式 |
| 14 | 缓存 | `@CachePut` | `@CacheRemove` + `@Cache` 组合 | 中 | 缓存更新策略需调整 |
| 15 | Redis | `RedisTemplate` | `RedisService` / `RedisX` | 高 | API 完全不同，需全量重写 Redis 操作代码 |
| 16 | 多数据源 | `@Qualifier` | `@Db("name")` | 中 | 数据源指定方式不同 |
| 17 | JPA | `spring.jpa.*` | `jpa.*` | 低 | 前缀变更，子项基本兼容 |
| 18 | MyBatis Plus | `@Mapper` | 无需注解 | 低 | 与原生 MyBatis 规则一致 |
