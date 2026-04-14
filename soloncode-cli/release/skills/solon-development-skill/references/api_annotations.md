# API & Annotations Reference — 注解与配置参考

> 适用场景：查找具体注解用法、配置文件属性、WebSocket/EventBus/Filter API。

## 1. Core Annotations

### Entry & Configuration

| Annotation | Target | Description |
|---|---|---|
| `@SolonMain` | Class | Mark the application entry class |
| `@Configuration` | Class | Configuration class for building beans via `@Bean` methods |
| `@Bean` | Method | Declare a bean in `@Configuration` class (runs once) |
| `@Component` | Class | General managed component (supports proxy) |
| `@Controller` | Class | Web MVC controller (use with `@Mapping`) |
| `@Remoting` | Class | Remote service endpoint |
| `@Import` | Class | Import classes, scan packages, or load config profiles |
| `@Condition` | Class/Method | Conditional bean registration |

### Dependency Injection

| Annotation | Target | Description |
|---|---|---|
| `@Inject` | Field/Param | Inject bean by type |
| `@Inject("name")` | Field/Param | Inject bean by name |
| `@Inject("${key}")` | Field/Param/Type | Inject configuration value |
| `@BindProps(prefix="xx")` | Type | Bind property set to class |
| `@Singleton` | Class | Singleton scope (default) |
| `@Singleton(false)` | Class | Non-singleton (new instance each injection) |

### Web MVC

| Annotation | Target | Description |
|---|---|---|
| `@Mapping("/path")` | Class/Method | URL path mapping |
| `@Get` | Method/Type | Restrict to GET (use with `@Mapping`) |
| `@Post` | Method/Type | Restrict to POST |
| `@Put` | Method/Type | Restrict to PUT |
| `@Delete` | Method/Type | Restrict to DELETE |
| `@Patch` | Method/Type | Restrict to PATCH |
| `@Param` | Parameter | Request parameter binding with options |
| `@Header` | Parameter | Bind request header |
| `@Cookie` | Parameter | Bind cookie value |
| `@Body` | Parameter | Bind request body |
| `@Path` | Parameter | Bind path variable |
| `@Consumes` | Method | Specify consumed content type |
| `@Produces` | Method | Specify produced content type |
| `@Multipart` | Method | Declare multipart request |

### Lifecycle

| Annotation/Interface | Description |
|---|---|
| `@Init` | Component init method (like `@PostConstruct`) |
| `@Destroy` | Component destroy method (like `@PreDestroy`) |
| `LifecycleBean` | Interface with `start()` and `stop()` methods |
| `AppLoadEndEvent` | Event fired after all loading completes |

### AOP & Interceptors

| Annotation | Description |
|---|---|
| `@Around` | Method interceptor (AOP around advice) |

## 2. Configuration File Reference

Solon uses `app.yml` (or `app.properties`) as the main configuration file located in `src/main/resources/`.

### Core Properties

```yaml
# Server configuration
server.port: 8080

# Application name
solon.app.name: "my-app"
solon.app.group: "my-group"

# Environment profiles
solon.env: dev  # loads app-dev.yml additionally

# Debug mode
solon.debug: true

# Logging
solon.logging.logger.root.level: INFO

# Virtual threads (Java 21+)
solon.threads.virtual.enable: true
```

### Multi-Environment Configuration

- `app.yml` — base config (always loaded)
- `app-dev.yml` — loaded when `solon.env=dev`
- `app-test.yml` — loaded when `solon.env=test`
- `app-pro.yml` — loaded when `solon.env=pro`

## 3. WebSocket API Reference

### 核心接口

| Interface/Class | Description |
|---|---|
| `WebSocket` | WebSocket 会话，提供 send/onClose 等操作 |
| `WebSocketListener` | 监听器接口（onOpen/onMessage/onClose） |
| `SimpleWebSocketListener` | 简单监听器（适配器模式） |
| `PipelineWebSocketListener` | 管道监听器（支持链式处理） |
| `PathWebSocketListener` | 路径监听器（按路径分发） |

### WebSocket 注解

| Annotation | Target | Description |
|---|---|---|
| `@ServerEndpoint` | Class | 声明 WebSocket 端点路径 |

### WebSocket 使用示例

```java
@ServerEndpoint("/ws/chat")
public class ChatWebSocket extends SimpleWebSocketListener {
    @Override
    public void onOpen(WebSocket socket) {
        // 连接打开时
    }

    @Override
    public void onMessage(WebSocket socket, String message) throws IOException {
        // 收到文本消息时
    }

    @Override
    public void onClose(WebSocket socket) {
        // 连接关闭时
    }
}
```

## 4. EventBus API Reference

### 核心方法

| Method | Description |
|---|---|
| `EventBus.publish(event)` | 同步发布事件（可传导异常） |
| `EventBus.publishTry(event)` | 同步发布（不抛异常，内部处理错误） |
| `EventBus.publishAsync(event)` | 异步发布 |
| `EventBus.subscribe(Class, listener)` | 按类型订阅 |
| `EventBus.subscribe(Class, priority, listener)` | 带优先级订阅 |

### 核心接口

| Interface | Description |
|---|---|
| `EventListener<T>` | 事件监听器接口，实现 `onEvent(T event)` |

### EventBus 使用示例

```java
// 定义事件
public class UserCreatedEvent {
    public final String username;
    public UserCreatedEvent(String username) { this.username = username; }
}

// 发布事件
EventBus.publish(new UserCreatedEvent("solon"));

// 订阅事件
EventBus.subscribe(UserCreatedEvent.class, event -> {
    System.out.println("User created: " + event.username);
});
```

## 5. Filter & RouterInterceptor

### 核心接口

| Interface | Description |
|---|---|
| `Filter` | 全局过滤器（doFilter），最外层拦截，处理所有请求 |
| `RouterInterceptor` | 路由拦截器，仅限动态路由，在 Filter 之后执行 |
| `@Component(index=N)` | 控制过滤器/拦截器执行顺序，index 越小越先执行 |

### Filter 使用示例

```java
@Component(index = 1)
public class AuthFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String token = ctx.header("Authorization");
        if (token == null) {
            ctx.outputAsJson(Result.failure("Unauthorized"));
            return;
        }
        chain.doFilter(ctx);
    }
}
```

### RouterInterceptor 使用示例

```java
@Component(index = 1)
public class LogInterceptor implements RouterInterceptor {
    @Override
    public void doIntercept(Context ctx, RouterInterceptorChain chain) throws Throwable {
        long start = System.currentTimeMillis();
        chain.doIntercept(ctx);
        System.out.println(ctx.path() + " => " + (System.currentTimeMillis() - start) + "ms");
    }
}
```
