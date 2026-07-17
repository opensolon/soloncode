# Maven 依赖替换对照表

> 目标版本：跟随 SKILL（默认 **Solon 4.0.3**）
>
> **本文件是依赖速查权威表**。完整场景迁移（配置键、注解、陷阱）见各 `*_migration.md`。
>
> **加载策略**：优先读 §1–§2 与 §3 速查表；仅在需要抄完整 POM 时读 §5。**勿把每个 starter 的重复 XML 整段加载**。

## 目录

- [1. Parent POM](#1-parent-pom)
- [2. 构建插件](#2-构建插件)
- [3. Starter → Solon 速查表](#3-starter--solon-速查表)
- [4. GroupId 与版本规则](#4-groupid-与版本规则)
- [5. 最小完整 POM 示例](#5-最小完整-pom-示例)
- [6. 注意事项](#6-注意事项)

---

## 1. Parent POM

```xml
<!-- Before -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.x.x</version>
    <relativePath/>
</parent>

<!-- After -->
<parent>
    <groupId>org.noear</groupId>
    <artifactId>solon-parent</artifactId>
    <version>4.0.3</version>
    <relativePath/>
</parent>
```

使用 `solon-parent` 后，多数 `org.noear` 依赖**不必写 version**。

---

## 2. 构建插件

```xml
<!-- Before: spring-boot-maven-plugin -->
<!-- After -->
<plugin>
    <groupId>org.noear</groupId>
    <artifactId>solon-maven-plugin</artifactId>
</plugin>
```

> `solon-maven-plugin` 含打包与热重载（`mvn solon:run`），一般无需 `devtools`。

---

## 3. Starter → Solon 速查表

> 表中 Solon 坐标默认 `groupId=org.noear`，除非另注。

### 3.1 Web 与容器

| Spring | Solon | 备注 |
|--------|-------|------|
| `spring-boot-starter-web` | `solon-web` | 主 Web 集成包 |
| `spring-boot-starter-webflux` | `solon-web` | **非**对等 WebFlux；需评估响应式差异 |
| `spring-boot-starter-websocket` | `solon-server-websocket` | 见 `web_advanced_migration.md` |
| `spring-boot-starter-servlet` | `solon-web-servlet` | Servlet 兼容 |
| `spring-boot-starter-tomcat` | `solon-server-tomcat`（或 jetty/undertow） | 可按容器切换 |
| `spring-boot-starter-jetty` | `solon-server-jetty` | |
| `spring-boot-starter-undertow` | `solon-server-undertow` | |
| （SSE） | `solon-web-sse` | Spring 无单独 starter 时按需加 |
| （CORS） | `solon-web-cors` | 见 filter 文 |

### 3.2 数据访问

| Spring / 生态 | Solon | 备注 |
|---------------|-------|------|
| `spring-boot-starter-jdbc` | `solon-data` + **`solon-data-sqlutils`** | SqlUtils 替代 JdbcTemplate；连接池常显式 `HikariCP` |
| `spring-boot-starter-data-jpa` | `solon-data-jpa` | |
| `mybatis-spring-boot-starter` | `mybatis-solon-plugin` | Mapper 用 `@Db` |
| `mybatis-plus-spring-boot-starter` | `mybatis-plus-solon-plugin` | |
| `spring-boot-starter-data-mongodb` | `solon-data-mongodb` | |
| `spring-boot-starter-data-elasticsearch` | `solon-data-es` | |

数据源配置权威：`datasource_orm_migration.md`（**`solon.dataSources` 自动装配，一般无需手写 `@Bean`**）。

### 3.3 缓存与 Redis

| Spring | Solon | 备注 |
|--------|-------|------|
| `spring-boot-starter-cache` | `solon-data-cache` | 注解缓存抽象 |
| Redis 作 **Cache 后端** | `solon-cache-jedis` / `solon-cache-redisson` 等 | 配合 `@Cache` |
| `spring-boot-starter-data-redis`（客户端） | `solon-data-redis` 或 **`solon-data-redisx`** | 与 Cache 插件场景区分；见 transaction 文 |

### 3.4 消息

| Spring | Solon | 备注 |
|--------|-------|------|
| `spring-boot-starter-amqp` | `rabbitmq-solon-cloud-plugin` | Cloud 插件体系 |
| `spring-kafka` | `kafka-solon-cloud-plugin` | |

### 3.5 模板

| Spring | Solon |
|--------|-------|
| `spring-boot-starter-thymeleaf` | `solon-view-thymeleaf` |
| `spring-boot-starter-freemarker` | `solon-view-freemarker` |

### 3.6 安全 / 校验 / 调度

| Spring | Solon | 备注 |
|--------|-------|------|
| `spring-boot-starter-security` | **`solon-security-auth`** | 见 `security_migration.md`；勿复刻完整过滤器链 |
| `spring-boot-starter-validation` | **`solon-security-validation`** | **不是** `solon-validation` |
| `spring-boot-starter-quartz` / `@Scheduled` | `solon-scheduling-simple` 或 `solon-scheduling-quartz` | 单机；分布式见 `@CloudJob` 与 cloud 插件 |

### 3.7 测试 / 日志 / 运维 / 其它

| Spring | Solon | 备注 |
|--------|-------|------|
| `spring-boot-starter-test` | **`solon-test-junit5`** | 见 test 文 |
| `spring-boot-starter-logging` | `solon-logging` | |
| `spring-boot-starter-actuator` | `solon-health` | 能力非一一对等 |
| `spring-boot-starter-mail` | `solon-mail` | |
| `spring-boot-starter-json` / Jackson 栈 | `solon-serialization-json` 等 | 按序列化需求选 |
| `spring-boot-starter-aop` | （**内置**，无需依赖） | |
| `spring-boot-devtools` | （**插件内置热重载**） | |
| `spring-boot-starter-hateoas` | （无直接对应） | 勿硬编 |

### 3.8 Cloud

| Spring | Solon | 备注 |
|--------|-------|------|
| `spring-cloud-starter-openfeign` | **`nami`** + `nami-channel-http`（等） | 可选 `feign-solon-plugin`；**禁止** `solon-cloud-feign-compatible` |
| `spring-cloud-starter-gateway` | `solon-cloud-gateway` | |
| Eureka / Config 等 | 如 **`nacos-solon-cloud-plugin`** | 注册+配置常同一插件；也有 consul/zk 等 |
| 断路器 / 分布式 Job 等 | 见 `cloud_observability_migration.md` | 按能力选 cloud 插件 |

---

## 4. GroupId 与版本规则

| Spring GroupId | Solon |
|----------------|-------|
| `org.springframework.boot` | `org.noear` |
| `org.springframework` | `org.noear` |
| `org.springframework.cloud` | `org.noear` |

- 第三方驱动（MySQL、PostgreSQL、HikariCP 等）**坐标可保留**，不必改成 `org.noear`。
- MyBatis-Plus 等生态：Solon 插件侧多为 `org.noear` 发布，以插件名为准。
- 版本与 SKILL 声明的 **4.0.3** 对齐；高版本通常只改 `solon-parent`。

---

## 5. 最小完整 POM 示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.noear</groupId>
        <artifactId>solon-parent</artifactId>
        <version>4.0.3</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo-app</artifactId>
    <version>1.0.0</version>

    <dependencies>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-security-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-data-sqlutils</artifactId>
        </dependency>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>mybatis-solon-plugin</artifactId>
        </dependency>
        <dependency>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-test-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.noear</groupId>
                <artifactId>solon-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

按项目再叠加：安全 `solon-security-auth`、缓存/Redis、调度、Nacos/Nami 等（查 §3）。

---

## 6. 注意事项

1. **删除全部** `spring-boot-starter-*` / `spring-cloud-starter-*` 与无用的 `org.springframework.*` 依赖。
2. **校验**只用 **`solon-security-validation`**；**测试**优先 **`solon-test-junit5`**。
3. **Feign** → Nami 通道组合；不要使用文档中已否定的假 artifact。
4. **AOP / devtools** 无需对等 starter。
5. 无直接对应（Batch 全量、HATEOAS、GraphQL 等）→ 见 SKILL「明确不覆盖」，查官网 / `solon-development-skill`，禁止臆造坐标。
6. 依赖替换后配置与注解仍须按 Checklist 改：`app.yml`、`@Component`/`@Inject` 等。
