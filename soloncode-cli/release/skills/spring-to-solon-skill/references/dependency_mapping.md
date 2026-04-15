# Maven 依赖替换对照表

> Spring Boot → Solon 迁移参考手册（目标版本：Solon 3.10.x）

本文档提供 Spring Boot 项目迁移至 Solon 框架时所需的全部 Maven 依赖替换映射。按功能模块分类整理，每项均包含替换前后的 XML 片段。

---

## 目录

- [1. Parent POM 替换](#1-parent-pom-替换)
- [2. 构建插件替换](#2-构建插件替换)
- [3. Starter → Solon Plugin 对照表](#3-starter--solon-plugin-对照表)
  - [3.1 Web 与容器](#31-web-与容器)
  - [3.2 数据访问](#32-数据访问)
  - [3.3 缓存](#33-缓存)
  - [3.4 消息队列](#34-消息队列)
  - [3.5 模板引擎](#35-模板引擎)
  - [3.6 安全与鉴权](#36-安全与鉴权)
  - [3.7 测试](#37-测试)
  - [3.8 日志与健康检查](#38-日志与健康检查)
  - [3.9 邮件](#39-邮件)
  - [3.10 定时任务](#310-定时任务)
  - [3.11 序列化与扩展](#311-序列化与扩展)
  - [3.12 Cloud 微服务](#312-cloud-微服务)
- [4. GroupId 变更规则](#4-groupid-变更规则)
- [5. 完整 POM 迁移示例](#5-完整-pom-迁移示例)
- [6. 注意事项与已知差异](#6-注意事项与已知差异)

---

## 1. Parent POM 替换

**Before — Spring Boot：**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.x.x</version>
    <relativePath/> <!-- 从仓库查找 -->
</parent>
```

**After — Solon：**

```xml
<parent>
    <groupId>org.noear</groupId>
    <artifactId>solon-parent</artifactId>
    <version>3.10.0</version>
    <relativePath/> <!-- 从仓库查找 -->
</parent>
```

---

## 2. 构建插件替换

**Before — Spring Boot：**

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

**After — Solon：**

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.noear</groupId>
            <artifactId>solon-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

> **说明：** `solon-maven-plugin` 同时内置了打包和开发热重载能力，无需额外引入 `devtools`。

---

## 3. Starter → Solon Plugin 对照表

### 3.1 Web 与容器

#### spring-boot-starter-web → solon-web

Web 开发集成包（含路由、MVC 注解、静态资源等）。

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-web</artifactId>
</dependency>
```

#### spring-boot-starter-webflux → solon-web

Solon 天然支持响应式（基于 CompletableFuture / Reactive Streams），无需单独的 WebFlux 包。

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-web</artifactId>
</dependency>
```

#### spring-boot-starter-websocket → solon-server-websocket

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-server-websocket</artifactId>
</dependency>
```

#### spring-boot-starter-servlet → solon-web-servlet

Servlet API 扩展支持（用于兼容传统 Servlet 组件）。

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-servlet</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-web-servlet</artifactId>
</dependency>
```

#### 容器切换

| Spring 容器 | Solon 替代方案 | 说明 |
|---|---|---|
| spring-boot-starter-tomcat | **无直接对应** | **Solon 不再支持 Tomcat**，需替换为 Jetty 或 Undertow |
| spring-boot-starter-undertow | solon-boot-undertow | Undertow 容器 |
| spring-boot-starter-jetty | solon-boot-jetty | Jetty 容器 |

> **重要提示：** 如果原 Spring 项目使用 Tomcat，迁移时必须选择 Jetty 或 Undertow 作为替代容器。

**spring-boot-starter-undertow → solon-boot-undertow：**

```xml
<!-- Before -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-undertow</artifactId>
</dependency>

<!-- After -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-boot-undertow</artifactId>
</dependency>
```

**spring-boot-starter-jetty → solon-boot-jetty：**

```xml
<!-- Before -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jetty</artifactId>
</dependency>

<!-- After -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-boot-jetty</artifactId>
</dependency>
```

---

### 3.2 数据访问

#### spring-boot-starter-jdbc → solon-data

JDBC 数据源支持（含连接池自动配置）。

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data</artifactId>
</dependency>
```

#### spring-boot-starter-data-jpa → solon-data-jpa

JPA（Hibernate 实现）。

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-jpa</artifactId>
</dependency>
```

#### mybatis-spring-boot-starter → mybatis-solon-plugin

**Before：**

```xml
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>mybatis-solon-plugin</artifactId>
</dependency>
```

#### mybatis-plus-spring-boot-starter → mybatis-plus-solon-plugin

**Before：**

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot-starter</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>mybatis-plus-solon-plugin</artifactId>
</dependency>
```

#### spring-boot-starter-data-mongodb → solon-data-mongodb

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-mongodb</artifactId>
</dependency>
```

#### spring-boot-starter-data-elasticsearch → solon-data-es

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-es</artifactId>
</dependency>
```

---

### 3.3 缓存

#### spring-boot-starter-cache → solon-data-cache

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-cache</artifactId>
</dependency>
```

#### spring-boot-starter-data-redis → solon-data-redis

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-redis</artifactId>
</dependency>
```

---

### 3.4 消息队列

#### spring-boot-starter-amqp → rabbitmq-solon-cloud-plugin

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>rabbitmq-solon-cloud-plugin</artifactId>
</dependency>
```

#### spring-kafka → kafka-solon-cloud-plugin

**Before：**

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>kafka-solon-cloud-plugin</artifactId>
</dependency>
```

---

### 3.5 模板引擎

#### spring-boot-starter-thymeleaf → solon-view-thymeleaf

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-view-thymeleaf</artifactId>
</dependency>
```

#### spring-boot-starter-freemarker → solon-view-freemarker

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-freemarker</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-view-freemarker</artifactId>
</dependency>
```

---

### 3.6 安全与鉴权

#### spring-boot-starter-security → solon-security-auth

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-security-auth</artifactId>
</dependency>
```

> **说明：** Solon 的安全模型与 Spring Security 差异较大，认证鉴权的用法需要适配。`solon-security-auth` 提供基于注解和过滤器的轻量鉴权方案。

---

### 3.7 测试

#### spring-boot-starter-test → solon-test-junit5

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-test-junit5</artifactId>
    <scope>test</scope>
</dependency>
```

> **说明：** 测试类注解需从 `@SpringBootTest` 替换为 `@SolonTest`。

---

### 3.8 日志与健康检查

#### spring-boot-starter-logging → solon-logging

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-logging</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-logging</artifactId>
</dependency>
```

#### spring-boot-starter-actuator → solon-health

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-health</artifactId>
</dependency>
```

---

### 3.9 邮件

#### spring-boot-starter-mail → solon-mail

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-mail</artifactId>
</dependency>
```

---

### 3.10 定时任务

#### spring-boot-starter-quartz → solon-scheduling-simple / solon-scheduling-quartz

简单定时任务使用 `solon-scheduling-simple`，需要 Quartz 高级功能时使用 `solon-scheduling-quartz`。

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-quartz</artifactId>
</dependency>
```

**After（简单调度）：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-scheduling-simple</artifactId>
</dependency>
```

**After（Quartz 调度）：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-scheduling-quartz</artifactId>
</dependency>
```

---

### 3.11 序列化与扩展

#### spring-boot-starter-json / jackson → solon-serialization-json

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-json</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-serialization-json</artifactId>
</dependency>
```

#### spring-boot-starter-validation → solon-validation

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-validation</artifactId>
</dependency>
```

#### spring-boot-starter-aop → 无需额外依赖

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

**After：**

```xml
<!-- 无需引入，Solon 框架内置 AOP 支持 -->
<!-- 通过 @Inject、@Around、@Before 等注解即可使用切面功能 -->
```

> **重要提示：** Solon 框架天然内置 AOP 能力，基于注解拦截和函数式路由实现。不需要（也不存在）独立的 AOP 依赖包。

#### spring-boot-devtools → solon-maven-plugin（内置热重载）

**Before：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

**After：**

```xml
<!-- 无需额外依赖 -->
<!-- solon-maven-plugin 已内置热重载能力，通过 mvn solon:run 启动即可 -->
```

---

### 3.12 Cloud 微服务

#### spring-cloud-starter-openfeign → nami（或 solon-rpc-nami）

**Before：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>nami</artifactId>
</dependency>
```

#### spring-cloud-starter-gateway → solon-cloud-gateway

**Before：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
```

**After：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-cloud-gateway</artifactId>
</dependency>
```

#### 注册发现与配置中心

Spring Cloud 的注册中心和配置中心与具体实现（Eureka、Consul、Nacos 等）绑定。Solon 统一通过插件方式对接各种注册中心/配置中心，推荐使用 Nacos。

**Before — Eureka Client：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

**After — Nacos 注册发现 + 配置中心：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>nacos-solon-cloud-plugin</artifactId>
</dependency>
```

**Before — Spring Cloud Config：**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

**After — Nacos 配置中心（与注册发现同一插件）：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>nacos-solon-cloud-plugin</artifactId>
</dependency>
```

> **说明：** Solon 还支持其他注册/配置中心插件，如 `consul-solon-cloud-plugin`、`zookeeper-solon-cloud-plugin` 等，可根据实际需求选择。

---

## 4. GroupId 变更规则

Spring 生态使用多个不同的 GroupId，而 Solon 统一使用 `org.noear`。

| Spring GroupId | 用途 | Solon GroupId |
|---|---|---|
| `org.springframework.boot` | Spring Boot Starters | `org.noear` |
| `org.springframework` | Spring 核心框架 | `org.noear` |
| `org.springframework.cloud` | Spring Cloud 组件 | `org.noear` |

**全局替换规则：**

```
# 所有 Spring 依赖的 groupId 统一替换
org.springframework.boot    →  org.noear
org.springframework         →  org.noear
org.springframework.cloud   →  org.noear
```

> **注意：** 部分 MyBatis-Plus 等第三方库的 `groupId` 可能不同（如 `com.baomidou`），其 Solon 版本通常由 `org.noear` 发布。

---

## 5. 完整 POM 迁移示例

### 5.1 迁移前 — 典型 Spring Boot 项目

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Spring Boot Parent -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo-app</artifactId>
    <version>1.0.0</version>
    <name>demo-app</name>
    <description>示例项目</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- 参数校验 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- AOP -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>

        <!-- JDBC 数据源 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- MyBatis -->
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
            <version>3.0.3</version>
        </dependency>

        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- 缓存 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>

        <!-- 认证鉴权 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <!-- 日志 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </dependency>

        <!-- 健康检查 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- 开发工具（热重载） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- MySQL 驱动 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Spring Boot 打包插件 -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 5.2 迁移后 — 对应的 Solon 项目

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Solon Parent -->
    <parent>
        <groupId>org.noear</groupId>
        <artifactId>solon-parent</artifactId>
        <version>3.10.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo-app</artifactId>
    <version>1.0.0</version>
    <name>demo-app</name>
    <description>示例项目</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Web（含路由、MVC 注解、静态资源） -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-web</artifactId>
        </dependency>

        <!-- 参数校验 -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-validation</artifactId>
        </dependency>

        <!-- AOP：无需额外依赖，Solon 框架内置 -->

        <!-- JDBC 数据源 -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-data</artifactId>
        </dependency>

        <!-- MyBatis -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>mybatis-solon-plugin</artifactId>
        </dependency>

        <!-- Redis -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-data-redis</artifactId>
        </dependency>

        <!-- 缓存 -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-data-cache</artifactId>
        </dependency>

        <!-- 认证鉴权 -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-security-auth</artifactId>
        </dependency>

        <!-- 日志 -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-logging</artifactId>
        </dependency>

        <!-- 健康检查 -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-health</artifactId>
        </dependency>

        <!-- 开发工具：无需额外依赖，solon-maven-plugin 内置热重载 -->

        <!-- 测试 -->
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-test-junit5</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- MySQL 驱动（保持不变，这是第三方驱动） -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Solon 打包插件（内置热重载） -->
            <plugin>
                <groupId>org.noear</groupId>
                <artifactId>solon-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 6. 注意事项与已知差异

### 6.1 无直接对应的 Spring 组件

| Spring 组件 | 说明 |
|---|---|
| `spring-boot-starter-hateoas` | Solon 无直接对应，需手动实现或寻找第三方库 |
| `spring-boot-starter-tomcat` | **Solon 不再支持 Tomcat**，必须替换为 `solon-boot-jetty` 或 `solon-boot-undertow` |

### 6.2 不需要迁移的依赖

| Spring 组件 | 说明 |
|---|---|
| `spring-boot-starter-aop` | Solon 内置 AOP，无需额外依赖 |
| `spring-boot-devtools` | `solon-maven-plugin` 已内置热重载，通过 `mvn solon:run` 启动 |

### 6.3 第三方驱动保持不变

数据库驱动等第三方依赖无需替换，保持原有坐标即可。例如：

- MySQL：`com.mysql:mysql-connector-j`
- PostgreSQL：`org.postgresql:postgresql`
- HikariCP：`com.zaxxer:HikariCP`（如需显式引入）

### 6.4 版本管理

- 使用 `solon-parent` 作为 Parent POM 后，大部分 Solon 依赖无需指定版本号（由 Parent 统一管理）。
- 当前目标版本为 **Solon 3.10.0**，请确保所有 Solon 插件使用相同版本。

### 6.5 快速对照速查表

| Spring Boot Starter | Solon Plugin | 类别 |
|---|---|---|
| spring-boot-starter-web | solon-web | Web |
| spring-boot-starter-webflux | solon-web | Web |
| spring-boot-starter-websocket | solon-server-websocket | Web |
| spring-boot-starter-servlet | solon-web-servlet | Web |
| spring-boot-starter-undertow | solon-boot-undertow | 容器 |
| spring-boot-starter-jetty | solon-boot-jetty | 容器 |
| spring-boot-starter-tomcat | solon-boot-jetty / solon-boot-undertow | 容器（替换） |
| spring-boot-starter-jdbc | solon-data | 数据 |
| spring-boot-starter-data-jpa | solon-data-jpa | 数据 |
| mybatis-spring-boot-starter | mybatis-solon-plugin | 数据 |
| mybatis-plus-spring-boot-starter | mybatis-plus-solon-plugin | 数据 |
| spring-boot-starter-data-mongodb | solon-data-mongodb | 数据 |
| spring-boot-starter-data-elasticsearch | solon-data-es | 数据 |
| spring-boot-starter-data-redis | solon-data-redis | 缓存 |
| spring-boot-starter-cache | solon-data-cache | 缓存 |
| spring-boot-starter-amqp | rabbitmq-solon-cloud-plugin | 消息 |
| spring-kafka | kafka-solon-cloud-plugin | 消息 |
| spring-boot-starter-thymeleaf | solon-view-thymeleaf | 模板 |
| spring-boot-starter-freemarker | solon-view-freemarker | 模板 |
| spring-boot-starter-security | solon-security-auth | 安全 |
| spring-boot-starter-test | solon-test-junit5 | 测试 |
| spring-boot-starter-logging | solon-logging | 日志 |
| spring-boot-starter-actuator | solon-health | 运维 |
| spring-boot-starter-mail | solon-mail | 邮件 |
| spring-boot-starter-quartz | solon-scheduling-simple / solon-scheduling-quartz | 定时任务 |
| spring-boot-starter-validation | solon-validation | 校验 |
| spring-boot-starter-json | solon-serialization-json | 序列化 |
| spring-boot-starter-aop | （内置，无需依赖） | AOP |
| spring-boot-devtools | （内置，无需依赖） | 开发工具 |
| spring-cloud-starter-openfeign | nami | Cloud |
| spring-cloud-starter-gateway | solon-cloud-gateway | Cloud |
| spring-cloud-starter-netflix-eureka-client | nacos-solon-cloud-plugin | Cloud |
| spring-cloud-starter-config | nacos-solon-cloud-plugin | Cloud |
| spring-boot-starter-hateoas | （无直接对应） | / |
