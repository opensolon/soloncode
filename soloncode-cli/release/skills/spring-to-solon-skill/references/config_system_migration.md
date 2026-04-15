# 配置体系迁移参考

> Spring Boot → Solon 核心概念迁移指南（目标版本：Solon 3.10.x）
>
> 本文档聚焦配置文件、配置类与 Bean 定义的迁移细节，提供完整的配置对照、代码对照及差异陷阱标注。

## 目录

- [5. 配置文件迁移](#5-配置文件迁移)
  - [5.1 文件命名与加载机制](#51-文件命名与加载机制)
  - [5.2 环境切换](#52-环境切换)
  - [5.3 配置文件完整对照](#53-配置文件完整对照)
  - [5.4 属性源导入](#54-属性源导入)
  - [5.5 变量引用与多片段](#55-变量引用与多片段)
  - [5.6 编程式读取配置](#56-编程式读取配置)
- [6. 配置类与 Bean 定义迁移](#6-配置类与-bean-定义迁移)
  - [6.1 基本配置类](#61-基本配置类)
  - [6.2 带 Condition 的 Bean 定义](#62-带-condition-的-bean-定义)
  - [6.3 多数据源场景](#63-多数据源场景)
- [10. 核心陷阱与差异清单（配置相关）](#10-核心陷阱与差异清单配置相关)

---

## 5. 配置文件迁移

### 5.1 文件命名与加载机制

| 项目 | Spring Boot | Solon |
|---|---|---|
| 默认配置文件 | `application.yml` / `application.properties` | `app.yml` / `app.properties` |
| 环境配置文件 | `application-{profile}.yml` | `app-{env}.yml` |
| 环境切换配置 | `spring.profiles.active=dev` | `solon.env=dev` |
| 文件位置 | `src/main/resources/` | `src/main/resources/`（相同） |
| 支持格式 | `.yml`、`.properties` | `.yml`、`.properties`（相同） |

### 5.2 环境切换

#### Before — Spring（application.yml）

```yaml
spring:
  profiles:
    active: dev    # 激活 dev 环境
```

#### After — Solon（app.yml）

```yaml
solon:
  env: dev        # 激活 dev 环境
```

> **差异说明**：
> - 环境切换的关键字从 `spring.profiles.active` 变为 `solon.env`。
> - 环境文件命名从 `application-{profile}.yml` 变为 `app-{env}.yml`。

### 5.3 配置文件完整对照

#### Before — Spring（application.yml）

```yaml
# 服务配置
server:
  port: 8080
  servlet:
    context-path: /api

# Spring 激活环境
spring:
  profiles:
    active: dev
  # 数据源配置
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

  # Redis 配置
  redis:
    host: localhost
    port: 6379
    password: ""
    database: 0

# 日志级别
logging:
  level:
    root: INFO
    com.example: DEBUG

# 自定义业务配置
app:
  name: demo-app
  version: 1.0.0
  features:
    - feature-a
    - feature-b
  security:
    jwt-secret: my-secret-key
    token-expiration: 3600
```

#### Before — Spring（application-dev.yml）

```yaml
# 开发环境覆盖
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb_dev
    username: dev_user
    password: dev_pass

logging:
  level:
    root: DEBUG
```

#### After — Solon（app.yml）

```yaml
# 服务配置（Solon 的 server 配置结构不同于 Spring）
server:
  port: 8080
  contextPath: /api    # 注意：不是 servlet.context-path

# Solon 激活环境
solon:
  env: dev
  # 数据源配置（使用 Solon 的数据源约定）
  datasource:          # 默认数据源
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: 123456
    driverClassName: com.mysql.cj.jdbc.Driver
    maxPoolSize: 20
    minIdle: 5

  # Redis 配置（使用 Solon 的 Redis 约定）
  redis:
    host: localhost
    port: 6379
    password: ""
    database: 0

# 日志级别
solon.logging:
  level:
    root: INFO
    com.example: DEBUG

# 自定义业务配置（保持不变）
app:
  name: demo-app
  version: 1.0.0
  features:
    - feature-a
    - feature-b
  security:
    jwt-secret: my-secret-key
    token-expiration: 3600
```

#### After — Solon（app-dev.yml）

```yaml
# 开发环境覆盖
server:
  port: 8081

solon:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb_dev
    username: dev_user
    password: dev_pass

solon.logging:
  level:
    root: DEBUG
```

> **陷阱**：
> - `server.servlet.context-path` → `server.contextPath`（路径不同，驼峰命名）。
> - `spring.datasource.hikari.maximum-pool-size` → `solon.datasource.maxPoolSize`（层级更扁平，驼峰命名）。
> - `logging.level` → `solon.logging.level`（前缀不同）。
> - 自定义业务配置（如 `app.*`）的 key 路径不变，迁移时保持原样即可。

### 5.4 属性源导入

#### Before — Spring

```java
// 方式一：@PropertySource 注解
@Configuration
@PropertySource("classpath:custom.properties")
@PropertySource("classpath:db.properties")
public class CustomConfig { }

// 方式二：@PropertySources 复合注解
@Configuration
@PropertySources({
    @PropertySource("classpath:custom.properties"),
    @PropertySource("classpath:db.properties")
})
public class CustomConfig { }
```

#### After — Solon

```java
// @Import 统一导入属性源文件（使用 profiles 属性指定配置文件路径）
@SolonMain
@Import(profiles = {"classpath:custom.properties","classpath:db.properties"})
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
```

> **差异说明**：
> - Solon 的 `@Import` 同时承担 `@ComponentScan`、`@Import`（Spring 的类导入）和 `@PropertySource` 三者的职责。
> - `@Import` 只在启动类或 `@Configuration` 类上有效。

### 5.5 变量引用与多片段

#### 变量引用

Spring 和 Solon 都支持在配置文件中使用 `${...}` 引用其他配置值：

```yaml
# app.yml（Solon）
app:
  name: demo-app
  version: 1.0.0
  description: "${app.name} v${app.version}"   # 变量引用，解析为 "demo-app v1.0.0"
```

#### YAML 多片段（solon.env.on）

Solon 支持 YAML 多片段特性，可以在同一个文件中为不同环境定义配置：

```yaml
# app.yml —— 通过 --- 分隔不同环境片段

server:
  port: 8080

app:
  name: demo-app

---
# 当 solon.env=dev 时激活此片段
solon.env.on: dev

server:
  port: 8081

app:
  debug: true

---
# 当 solon.env=prod 时激活此片段
solon.env.on: prod

server:
  port: 80

app:
  debug: false
```

> **差异说明**：Spring 需要通过独立的 `application-{profile}.yml` 文件实现环境隔离；Solon 支持两种方式——既支持独立的 `app-{env}.yml` 文件，也支持在同一文件中用 `---` + `solon.env.on` 分隔多环境片段。

### 5.6 编程式读取配置

#### Before — Spring

```java
@Service
public class ConfigReader {

    @Autowired
    private Environment env;

    public void read() {
        String name = env.getProperty("app.name");
        int port = env.getProperty("server.port", Integer.class, 8080);
        // 使用 @Value 更加常见
    }
}
```

#### After — Solon

```java
@Component
public class ConfigReader {

    public void read() {
        // 方式一：通过 Solon.cfg() 获取配置
        String name = Solon.cfg().get("app.name");
        int port = Solon.cfg().getInt("server.port", 8080);

        // 方式二：通过 @Inject 注入（推荐）
        // @Inject("${app.name}") private String appName;
    }
}
```

## 6. 配置类与 Bean 定义迁移

### 6.1 基本配置类

#### Before — Spring

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        return new HikariDataSource();
    }

    @Bean("secondaryDataSource")
    public DataSource secondaryDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://localhost:3306/secondary");
        return ds;
    }
}
```

#### After — Solon

```java
@Configuration
public class DataSourceConfig {

    // typed=true 表示该类型的默认 Bean
    // @Inject 在 @Bean 方法参数上注入配置
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${solon.datasource}") HikariDataSource ds) {
        return ds;
    }

    @Bean("secondaryDataSource")
    public DataSource secondaryDataSource(@Inject("${solon.datasource.secondary}") HikariDataSource ds) {
        return ds;
    }
}
```

> **差异说明**：
> - Solon 的 `@Bean` 方法参数通过 `@Inject` 注入配置或 Bean，Spring 则依赖自动装配。
> - `@Bean(typed=true)` 用于声明某类型的默认 Bean，在 `@Inject` 按类型注入时优先匹配此 Bean。
> - Solon 的 `@Bean` 可以返回 `void`（仅用于执行初始化逻辑，不注册任何 Bean）。

### 6.2 带 Condition 的 Bean 定义

#### Before — Spring

```java
@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    @ConditionalOnProperty(name = "cache.type", havingValue = "redis")
    public CacheManager redisCacheManager() {
        return new RedisCacheManager();
    }
}
```

#### After — Solon

```java
@Configuration
public class CacheConfig {

    @Bean
    @Condition(onClass = "org.redisson.api.RedissonClient", onProperty = "cache.type=redis")
    public CacheManager redisCacheManager() {
        return new RedisCacheManager();
    }
}
```

> **注意**：`@Condition` 可同时声明多个条件（`onClass` + `onProperty`），而 Spring 需要叠加多个 `@ConditionalOnXxx` 注解。

### 6.3 多数据源场景

#### Before — Spring

```java
@Configuration
public class MultiDataSourceConfig {

    @Bean
    @Primary                          // 标记为主数据源
    @ConfigurationProperties(prefix = "spring.datasource.primary")
    public DataSource primaryDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.secondary")
    public DataSource secondaryDataSource() {
        return new HikariDataSource();
    }
}
```

#### After — Solon

```java
@Configuration
public class MultiDataSourceConfig {

    // typed=true 等价于 @Primary，表示该类型的默认 Bean
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${solon.datasource.primary}") HikariDataSource ds) {
        return ds;
    }

    @Bean("db2")
    public DataSource db2(@Inject("${solon.datasource.secondary}") HikariDataSource ds) {
        return ds;
    }
}
```

> **差异说明**：
> - Spring 用 `@Primary` 标记默认 Bean；Solon 用 `@Bean(typed=true)` 实现相同效果。
> - 注入时：Spring 需 `@Qualifier` 区分，Solon 直接 `@Inject("db1")` 或 `@Inject("db2")`。

## 10. 核心陷阱与差异清单（配置相关）

### 陷阱速查表

| 编号 | 陷阱描述 | 严重程度 | 详细说明 |
|---|---|---|---|
| 1 | **配置文件名不同** | 中 | `application.yml` → `app.yml`，`application-{profile}.yml` → `app-{env}.yml`。 |
| 2 | **server 配置结构扁平化** | 中 | `server.servlet.context-path` → `server.contextPath`，注意驼峰命名。 |
| 3 | **数据源配置路径变更** | 中 | `spring.datasource.hikari.maximum-pool-size` → `solon.datasource.maxPoolSize`，层级更扁平，驼峰命名。 |
| 4 | **日志配置前缀变更** | 中 | `logging.level` → `solon.logging.level`，前缀不同。 |
| 5 | **环境切换关键字变更** | 中 | `spring.profiles.active` → `solon.env`，环境文件命名规则也相应变更。 |
| 6 | **@Import 多义性** | 低 | `@Import` 同时承担包扫描、类导入、属性源导入三种职责，注意区分使用场景。 |
| 7 | **自定义配置 key 不变** | 低 | 自定义业务配置（如 `app.*`）的 key 路径不变，迁移时保持原样即可。 |

### 迁移检查清单

- [ ] 配置文件名：`application.yml` → `app.yml`，`application-{profile}.yml` → `app-{env}.yml`
- [ ] 环境切换：`spring.profiles.active` → `solon.env`
- [ ] 服务配置：`server.servlet.context-path` → `server.contextPath`
- [ ] 数据源配置：`spring.datasource.*` → `solon.datasource.*`，注意驼峰命名和层级扁平化
- [ ] 日志配置：`logging.level` → `solon.logging.level`
- [ ] 属性源导入：`@PropertySource` → `@Import(profiles=...)`
- [ ] 配置类：`@ConfigurationProperties` → `@Inject("${prefix}")` + `@Configuration` 或 `@BindProps`
- [ ] 条件装配：`@ConditionalOnXxx` → `@Condition(onXxx=...)`
- [ ] 默认 Bean：`@Primary` → `@Bean(typed=true)`
- [ ] 环境隔离：可使用独立 `app-{env}.yml` 或同文件 `---` + `solon.env.on` 多片段
