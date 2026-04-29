# API & Annotations Reference — 注解与配置参考

> 适用场景：查找具体注解用法、配置文件属性、WebSocket/EventBus/Filter API。
> 目标版本：3.10.4

## 1. Core Annotations

### Entry & Configuration

| Annotation | Target | Description |
|---|---|---|
| `@SolonMain` | Class | Solon 主类标识（即 main 函数所在类） |
| `@Configuration` | Class | 托管配置组件类（与 `@Inject`、`@Bean` 共同完成初始化配置、构建托管对象等） |
| `@Bean` | Method | 配置托管对象（作用在 `@Configuration` 类的函数上才有效）。属性：value/name, typed, index, priority, delivered, injected, initMethod, destroyMethod, tag |
| `@Component` | Class | 通用托管组件（支持自动代理，v2.5.2 开始支持自动代理）。属性：value/name, tag, typed, index, delivered |
| `@Controller` | Class | Web MVC 控制器组件类（支持函数拦截） |
| `@Remoting` | Class | 远程控制器类（有类代理，即 RPC 服务端） |
| `@Import` | Class | 导入组件或属性源（作用在启动主类上或 `@Configuration` 类上才有效） |
| `@Condition` | Class/Method | 配置条件（v2.0 支持） |
| `@SolonTest` | Class | Solon 测试标识（一般在测试时使用） |
| `@Rollback` | Method/Class | 执行回滚（一般在测试时使用） |

### Dependency Injection

| Annotation | Target | Description |
|---|---|---|
| `@Inject` | Field/Param | 注入托管对象（by type）。属性：value, required, autoRefreshed |
| `@Inject("name")` | Field/Param | 注入托管对象（by name） |
| `@Inject("${key}")` | Field/Param/Type | 注入应用属性（可由基础类型或结构体接收） |
| `@BindProps(prefix="xx")` | Type | 绑定应用属性（绑定配置类或方法结果） |
| `@Singleton` | Class | 单例声明（Solon 默认是单例） |
| `@Singleton(false)` | Class | 非单例（每次注入生成新实例） |

### Web MVC

| Annotation | Target | Description |
|---|---|---|
| `@Mapping("/path")` | Class/Method | URL 路径映射（可附加 `@Get`、`@Post`、`@Socket` 等限定注解）。属性：value/path, method, consumes, produces, multipart, name, description, headers |
| `@Get` | Method/Type | 限定为 GET 请求（配合 `@Mapping` 使用） |
| `@Post` | Method/Type | 限定为 POST 请求 |
| `@Put` | Method/Type | 限定为 PUT 请求 |
| `@Delete` | Method/Type | 限定为 DELETE 请求 |
| `@Patch` | Method/Type | 限定为 PATCH 请求 |
| `@Options` | Method/Type | 限定为 OPTIONS 请求 |
| `@Head` | Method/Type | 限定为 HEAD 请求 |
| `@Socket` | Method/Type | 限定为 Socket 请求 |
| `@Http` | Method/Type | 限定为 HTTP 协议 |
| `@Message` | Method/Type | 限定为 Message 协议 |
| `@To` | Method | 发送到（指定目标） |
| `@Param` | Parameter | 请求参数（需要默认值或名字不同时使用）。属性：value/name, required, defaultValue |
| `@Header` | Parameter | 绑定请求 Header |
| `@Cookie` | Parameter | 绑定 Cookie 值 |
| `@Body` | Parameter | 绑定请求体（仅在主体的 String、InputStream、Map 时才需要） |
| `@Path` | Parameter | 绑定路径变量（框架会自动处理，主要用于标识方便文档生成） |
| `@Consumes` | Method | 指定消费的内容类型 |
| `@Produces` | Method | 指定生成的内容类型 |
| `@Multipart` | Method | 声明 multipart 请求 |

### Lifecycle

| Annotation/Interface | Description |
|---|---|
| `@Init` | 组件初始化方法（类似 `@PostConstruct`） |
| `@Destroy` | 组件销毁方法（类似 `@PreDestroy`） |
| `LifecycleBean` | 接口，提供 `start()` 和 `stop()` 方法 |
| `AppLoadEndEvent` | 所有加载完成后触发的事件 |

### AOP & Interceptors

| Annotation | Description |
|---|---|
| `@Around` | 方法拦截器（AOP 环绕通知） |
| `@Addition` | 附加拦截器（AOP 增强通知，与 `@Around` 的区别在于不包装返回值） |

## 2. Configuration File Reference

Solon 使用 `app.yml`（或 `app.properties`）作为主配置文件，位于 `src/main/resources/`。

### 启动参数

启动参数在应用启动后会被静态化（启动后不可再修改）。

| 启动参数 | 对应应用配置 | 描述 |
|---|---|---|
| `--env` | `solon.env` | 环境切换（可用于内部配置切换） |
| `--debug` | `solon.debug` | 调试模式（0 或 1） |
| `--setup` | `solon.setup` | 安装模式（0 或 1） |
| `--white` | `solon.white` | 白名单模式（0 或 1） |
| `--drift` | `solon.drift` | 漂移模式，部署到 K8s 的服务要设为 1（0 或 1） |
| `--alone` | `solon.alone` | 单体模式（0 或 1） |
| `--extend` | `solon.extend` | 扩展目录 |
| `--locale` | `solon.locale` | 默认地区 |
| `--config.add` | `solon.config.add` | 增加外部配置 |
| `--app.name` | `solon.app.name` | 应用名 |
| `--app.group` | `solon.app.group` | 应用分组 |
| `--app.title` | `solon.app.title` | 应用标题 |
| `--stop.safe` | `solon.stop.safe` | 安全停止（0 或 1，v2.1.0 后支持） |
| `--stop.delay` | `solon.stop.delay` | 安全停止的延时秒数（默认 10 秒） |

启动参数扩展特性：所有带 `.` 的启动参数同时会成为应用配置。以下效果相同：

```bash
java -Dsolon.env=dev -jar demo.jar
java -jar demo.jar --solon.env=dev
java -jar demo.jar --env=dev
```

### Core Properties

```yaml
# ==================== 服务端基本属性 ====================
server.port: 8080                       # 服务端口（默认 8080）
server.host: "0.0.0.0"                  # 服务主机（ip）
server.contextPath: "/test-service/"    # 服务上下文路径（v1.11.2 后支持）

# ==================== 应用基本属性 ====================
solon.app.name: "my-app"               # 应用名称
solon.app.group: "my-group"            # 应用分组
solon.app.namespace: "demo"            # 应用命名空间（一般用不到，只有支持的组件才用）
solon.app.title: "My App"              # 应用标题
solon.app.enabled: true                # 应用是否启用

# ==================== 环境配置 ====================
solon.env: dev                         # 环境配置切换，加载 app-dev.yml

# ==================== 调试模式 ====================
solon.debug: true

# ==================== 日志 ====================
solon.logging.logger.root.level: INFO

# ==================== 虚拟线程（Java 21+）====================
solon.threads.virtual.enabled: true    # 启用虚拟线程池（默认 false，v2.7.3 后支持）

# ==================== 安全停止 ====================
solon.stop.safe: 0                     # 安全停止（0 或 1，v2.1.0 后支持）
solon.stop.delay: 10                   # 安全停止的延时秒数（默认 10 秒）

# ==================== 元信息输出 ====================
solon.output.meta: 1                   # 输出每个插件的信息

# ==================== 体外扩展目录 ====================
solon.extend: "ext"
```

### Server 线程配置

```yaml
# HTTP 信号配置
server.http.name: "myapp"              # HTTP 信号名称（默认 ${solon.app.name}）
server.http.port: 8080                 # HTTP 信号端口（默认 ${server.port}）
server.http.host: "0.0.0.0"           # HTTP 信号主机
server.http.coreThreads: 0            # HTTP 最小线程数（0 表示自动，支持固定值或内核倍数 x2）
server.http.maxThreads: 0             # HTTP 最大线程数（0 表示自动，支持固定值或内核倍数 x32）
server.http.idleTimeout: 0            # HTTP 闲置线程或连接超时（0 表示自动，默认 5 分钟，单位 ms）
server.http.ioBound: true             # HTTP 是否为 IO 密集型

# Socket 信号配置
server.socket.name: "myapp.tcp"       # Socket 信号名称
server.socket.port: 28080             # Socket 信号端口（默认 20000+${server.port}）
server.socket.host: "0.0.0.0"        # Socket 信号主机
server.socket.coreThreads: 0          # Socket 最小线程数
server.socket.maxThreads: 0           # Socket 最大线程数
server.socket.idleTimeout: 0          # Socket 闲置超时

# WebSocket 信号配置
server.websocket.name: "myapp.ws"     # WebSocket 信号名称
server.websocket.port: 18080          # WebSocket 信号端口（默认 10000+${server.port}）
server.websocket.host: "0.0.0.0"     # WebSocket 信号主机
server.websocket.coreThreads: 0       # WebSocket 最小线程数
server.websocket.maxThreads: 0        # WebSocket 最大线程数
```

### 请求与会话配置

```yaml
server.request.maxBodySize: 2mb       # 最大请求包大小（默认 2mb）
server.request.maxFileSize: 2mb       # 最大上传文件大小（默认使用 maxBodySize 配置值）
server.request.maxHeaderSize: 8kb     # 最大请求头大小（默认 8kb）
server.request.fileSizeThreshold: 512kb  # 上传文件大小阀值，低于走内存高于走临时文件（默认 512kb，v3.6.0 后支持）
server.request.useRawpath: false      # 路由使用原始路径（即未解码状态，v2.8.6 后支持）
server.request.encoding: "utf-8"      # 请求体编码
server.response.encoding: "utf-8"     # 响应体编码

server.session.timeout: 7200          # 会话超时秒数
server.session.cookieName: "SOLONID"  # 会话 ID 的 Cookie 名称
server.session.cookieDomain: ""       # 会话状态的 Cookie 域
```

### SSL 证书配置（HTTPS）

```yaml
# 公共 SSL 配置（属于所有信号）
server.ssl.keyStore: "/data/ca/demo.jks"    # 或 "classpath:demo.pfx"
server.ssl.keyPassword: "demo"

# 各信号独立 SSL 配置（如未设置则使用公共配置，v2.3.7 后支持）
server.http.ssl.enable: true
server.http.ssl.keyStore: "/data/ca/demo.jks"
server.http.ssl.keyPassword: "demo"
```

### Gzip 压缩配置（v2.5.7 后支持）

```yaml
server.http.gzip.enable: false        # 是否启用 gzip（默认 false）
server.http.gzip.minSize: 4096        # 最小多少大小才启用（默认 4k）
server.http.gzip.mimeTypes: 'text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json,application/xml'
```

### 配置增强

```yaml
# 添加外部扩展配置（策略：先加载内部的，再加载外部的盖上去）
solon.config.add: "./demo.yml"        # 多个用 "," 隔开

# 添加多个内部配置（在 app.yml 之外添加配置加载，v2.2.7 后支持）
solon.config.load:
  - "app-ds-${solon.env}.yml"         # 可以是环境相关的
  - "app-auth_${solon.env}.yml"
  - "config/common.yml"               # 也可以是环境无关的或带目录的
```

### 配置变量引用规则

```yaml
# 属性之间使用 ${...} 引用
test.demo1: "${db1.url}"              # 引用应用属性
test.demo2: "jdbc:mysql:${db1.server}"  # 引用应用属性并组合
test.demo3: "${JAVA_HOME}"            # 引用环境变量
test.demo4: "${.demo3}"               # 引用本级其它变量（v2.9.0 后支持）
test.demo5: "${solon.app.title:}"     # 使用 ":" 后缀表示可为空
```

### Yaml 多片段支持（v2.5.5 后支持）

```yaml
solon.env: pro

---
solon.env.on: pro
demo.auth:
  user: root
  password: Ssn1LeyxpQpglre0
---
solon.env.on: dev|test
demo.auth:
  user: demo
  password: 1234
```

### Multi-Environment Configuration

- `app.yml` — 基础配置（始终加载）
- `app-dev.yml` — 当 `solon.env=dev` 时加载
- `app-test.yml` — 当 `solon.env=test` 时加载
- `app-pro.yml` — 当 `solon.env=pro` 时加载

四种指定环境的方式（编号越大优先级越高）：

| 方式 | 示例 | 备注 |
|---|---|---|
| 主配置文件指定 `solon.env` 属性 | `solon.env: dev` | 以 yml 为例 |
| 启动时用系统属性指定 | `java -Dsolon.env=pro -jar demo.jar` | |
| 启动时用启动参数指定 | `java -jar demo.jar --env=pro` | |
| 启动时用系统环境变量指定 | `docker run -e 'solon.env=pro' demo_image` | 以 docker 为例 |

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
| `@ServerEndpoint` | Class | 声明 WebSocket 或 Socket 的服务端端点路径（作用在 Listener 接口实现类上有效） |
| `@ClientEndpoint` | Class | 声明 WebSocket 或 Socket 的客户端端点路径（作用在 Listener 接口实现类上有效） |

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
