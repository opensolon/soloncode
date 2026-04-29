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
        <version>3.10.4</version>
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

### AOT & Native Image (GraalVM)

Solon 支持 AOT (Ahead-of-Time Processing) 编译和 GraalVM Native Image 打包。

Dependency:
```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-aot</artifactId>
</dependency>
```

#### AOT 编译

单模块项目：
```bash
mvn clean -DskipTests=true -P aot package
```

多模块项目：
1. 所有模块先 `mvn install`（不勾选 aot）
2. 主模块 `mvn -P aot package`

#### Native Image 编译

要求：`graalvm 17+` + `native-image`

单模块项目：
```bash
mvn clean -DskipTests=true -P native native:compile
```

多模块项目：
1. 所有模块先 `mvn install`（不勾选 native）
2. 主模块 `mvn -P native package`

运行：
```bash
./target/demo
```

#### Native 定制（RuntimeNativeRegistrar）

Solon AOT 自动处理托管部分的反射和资源登记。第三方框架需要手动补充：

```java
@Component
public class RuntimeNativeRegistrarImpl implements RuntimeNativeRegistrar {
    @Override
    public void register(AppContext context, RuntimeNativeMetadata metadata) {
        // 登记资源
        metadata.registerResourceInclude("com.mysql.jdbc.LocalizedErrorMessages.properties");
        // 登记序列化
        metadata.registerSerialization(MyDto.class);
        // 登记反射
        metadata.registerReflection(MyClass.class, MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
```

#### Native 兼容工具

| 工具 | 描述 |
|---|---|
| `ScanUtil` | 兼容原生编译的资源或文件扫描 |
| `ResourceUtil` | 兼容原生编译的资源获取或查找 |
| `ReflectUtil` | 兼容原生编译的基础反射工具 |
| `NativeDetector` | 环境探测：`isAotRuntime()`, `inNativeImage()` |

#### Native 注意事项

- 所有反射必须提前登记（Solon AOT 自动处理托管部分）
- 所有资源文件必须提前登记
- 不能扫描资源文件（使用 `ResourceUtil.scanResources`）
- 不能用动态编译（可换脚本或表达式工具）
- 不能用字节码构建类（Solon AOT 自动处理托管部分）

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
