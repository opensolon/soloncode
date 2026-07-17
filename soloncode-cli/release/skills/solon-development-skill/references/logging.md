# Logging — 日志体系

> 适用场景：日志框架选择、日志配置、自定义日志添加器、日志持久化。
>
> 目标版本：4.0.3。

## 日志框架适配

使用 slf4j 作为统一接口，提供相对统一的增配风格。

| 插件 | 添加器支持 | 备注 |
|---|---|---|
| `solon-logging-simple` | console, cloud | 轻量版 |
| `solon-logging-logback` | console, file, cloud | **推荐**（Java 8+），高级定制可用 xml |
| `solon-logging-logback-jakarta` | console, file, cloud | **推荐**（Java 11+） |
| `solon-logging-log4j2` | console, file, cloud | 高级定制可用 xml |

## 日志配置

日志配置分为两部分：`solon.logging.appender`（添加器）和 `solon.logging.logger`（记录器）。

### 添加器配置

框架默认三个添加器：`console`、`file`、`cloud`。

```yaml
solon.logging.appender:
  console:
    enable: true        # 是否启用（默认 true）
    level: TRACE        # 日志等级
    pattern: "..."      # 打印样式
  file:
    enable: true
    level: INFO
    name: "logs/${solon.app.name}"
    extension: ".log"         # 文件后缀名
    maxFileSize: "10MB"       # 文件最大尺寸
    maxHistory: 30            # 最大保留历史（天）
    rolling: "logs/${solon.app.name}-%d{yyyy-MM-dd}.%i.log"  # 滚动路径
  cloud:
    enable: true
    level: ERROR              # ERROR / WARN / INFO / DEBUG / TRACE
```

### 记录器配置

```yaml
solon.logging.logger:
  "root":
    level: DEBUG
  "com.demo.order":
    level: INFO
  "com.demo.payment":
    level: WARN
```

## 自定义日志添加器

### 简单自定义添加器

```java
public class JsonAppender extends AppenderBase {
    @Override
    public void append(LogEvent logEvent) {
        System.out.println("[Json] " + ONode.stringify(logEvent));
    }
}
```

配置注册：

```yaml
solon.logging.appender:
  json:
    level: INFO
    class: demo.log.JsonAppender
```

### 高性能持久化添加器

基于 `PersistentAppenderBase` 实现流转批 + 持久化：

```java
public class PersistentAppender extends PersistentAppenderBase {
    LogService logService;

    public PersistentAppender() {
        Solon.context().getBeanAsync(LogService.class, bean -> {
            logService = bean;
        });
    }

    @Override
    public void onEvents(List<LogEvent> list) {
        if (logService != null) {
            logService.insertList(list);  // 批量入库
        }
    }
}
```

配置注册：

```yaml
solon.logging.appender:
  persistent:
    level: TRACE
    class: demo.dso.PersistentAppender
```

## Slf4j MDC

支持标准的 slf4j MDC（Mapped Diagnostic Context），用于在日志中嵌入上下文信息。

## XML 高级定制

如需不同业务的日志写到不同文件，可使用日志框架原生 xml 配置：

- **logback**: 参考 `solon-logging-logback` 插件文档
- **log4j2**: 参考 `solon-logging-log4j2` 插件文档

## Slf4j 版本冲突处理

Solon v2.3.0 起切到 slf4j v2.x。若第三方包引用 v1.x 导致冲突：

**方式一：** pom.xml 开头强制引入 v2.x
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.9</version>
</dependency>
```

**方式二：** 排除掉 v1.x 的包（Maven 依赖顺序原则：声明优先、路径最短优先）
