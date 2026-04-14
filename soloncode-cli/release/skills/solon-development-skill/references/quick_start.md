# Quick Start — 项目初始化与构建部署

> 适用场景：从零创建 Solon 项目、配置 Maven、打包部署。

## Maven pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.noear</groupId>
        <artifactId>solon-parent</artifactId>
        <version>3.10.0</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.noear</groupId>
            <artifactId>solon-web</artifactId>
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

## Application Entry

```java
package com.example.demo;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

@SolonMain
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}
```

## Controller Example

```java
package com.example.demo.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;

@Controller
public class HelloController {

    @Get
    @Mapping("/hello")
    public String hello(@Param(defaultValue = "world") String name) {
        return String.format("Hello %s!", name);
    }
}
```

## Configuration (app.yml)

```yaml
server.port: 8080
solon.app.name: "demo"
```

## Shortcut Dependencies

| Artifact | Use Case |
|---|---|
| `solon-web` | **Full web development** (HTTP server + JSON + session + static files + cors + validation) |
| `solon-lib` | **Library/non-web** (IoC + AOP + data + cache + yaml config, no HTTP server) |

When building a web application, use `solon-web`. When building a non-web service or library, use `solon-lib`.

## Build & Deploy

### Package

```bash
mvn clean package -DskipTests
```

The `solon-maven-plugin` produces a fat JAR.

### Run

```bash
java -jar target/demo.jar
```

### Run with environment

```bash
java -jar demo.jar --solon.env=pro
```

### Native Image (GraalVM)

Solon supports AOT and GraalVM native image compilation via `solon-native` module.

## Ecosystem Overview — Sub-Projects

| Project | Repository | Description |
|---|---|---|
| **Solon** (core) | `opensolon/solon` | Core framework, IoC/AOP, Web MVC, data, security, scheduling, native |
| **Solon AI** | `opensolon/solon-ai` | LLM, RAG, MCP protocol, Agent (ReAct/Team), AI Skills |
| **Solon Flow** | `opensolon/solon-flow` | General flow orchestration (YAML/JSON), workflow, rule engine |
| **Solon Cloud** | `opensolon/solon-cloud` | Distributed: config, discovery, event, file, job, trace, breaker |
| **Solon Expression** | `opensolon/solon-expression` | SnEL — evaluation expression language |
| **Solon Admin** | `opensolon/solon-admin` | Admin monitoring server + client |
| **Solon Integration** | `opensolon/solon-integration` | Third-party ORM/RPC integrations (MyBatis, Dubbo, etc.) |
| **Solon Java17** | `opensolon/solon-java17` | Java 17+ specific modules |
| **Solon Java25** | `opensolon/solon-java25` | Java 25+ specific modules |
