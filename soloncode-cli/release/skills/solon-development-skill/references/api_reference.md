# API Reference — 注解 / 配置 / 运行时 API

> 适用场景：查找注解用法、配置文件属性、WebSocket / EventBus / Filter API。
> 目标版本：4.0.3。WebSocket / EventBus / Filter 的**权威参考**；`common_patterns.md` 仅最短示例。

## 1. Core Annotations

### Entry & Configuration

| Annotation | Target | Description |
|---|---|---|
| `@SolonMain` | Class | 主类标识（main 所在类） |
| `@Configuration` | Class | 配置组件（配合 `@Inject`、`@Bean`） |
| `@Bean` | Method | 托管对象（仅在 `@Configuration` 方法上有效）。属性：value/name, typed, index, priority, delivered, injected, initMethod, destroyMethod, tag |
| `@Component` | Class | 通用托管组件（自动代理）。属性：value/name, tag, typed, index, delivered |
| `@Controller` | Class | Web MVC 控制器 |
| `@Remoting` | Class | RPC 服务端（有类代理） |
| `@Import` | Class | 导入组件或属性源（主类或 `@Configuration`） |
| `@Condition` | Class/Method | 条件装配（v2.0+） |
| `@SolonTest` / `@Rollback` | Class / Method | 测试标识 / 回滚 |

### Dependency Injection

| Annotation | Description |
|---|---|
| `@Inject` | by type 注入。属性：value, required, autoRefreshed |
| `@Inject("name")` | by name |
| `@Inject("${key}")` | 注入应用属性（基础类型或结构体） |
| `@BindProps(prefix="xx")` | 绑定配置类或方法结果 |
| `@Singleton` / `@Singleton(false)` | 单例（默认）/ 非单例 |

### Web MVC

| Annotation | Description |
|---|---|
| `@Mapping("/path")` | URL 映射。属性：value/path, method, consumes, produces, multipart, name, description, headers |
| `@Get` `@Post` `@Put` `@Delete` `@Patch` `@Options` `@Head` | HTTP 方法限定 |
| `@Socket` `@Http` `@Message` | 协议限定 |
| `@Param` / `@Header` / `@Cookie` / `@Body` / `@Path` | 参数绑定 |
| `@Consumes` / `@Produces` / `@Multipart` | 内容类型 / multipart |
| `@To` | 发送到指定目标 |

### Lifecycle / AOP

| Annotation/Interface | Description |
|---|---|
| `@Init` / `@Destroy` | 初始化 / 销毁（类似 PostConstruct / PreDestroy） |
| `LifecycleBean` | `start()` / `stop()` |
| `AppLoadEndEvent` | 全部加载完成后事件 |
| `@Around` | AOP 环绕（包装返回值） |
| `@Addition` | AOP 增强（不包装返回值） |

## 2. Configuration File Reference

主配置：`app.yml` 或 `app.properties`（`src/main/resources/`）。**不是** `application.yml`。

### 启动参数

启动后静态化，不可再改。带 `.` 的启动参数同时成为应用配置。

| 启动参数 | 配置键 | 描述 |
|---|---|---|
| `--env` | `solon.env` | 环境切换 |
| `--debug` / `--setup` / `--white` / `--alone` | 对应 `solon.*` | 0 或 1 |
| `--drift` | `solon.drift` | K8s 部署设为 1 |
| `--extend` / `--locale` / `--config.add` | 对应键 | 扩展目录 / 地区 / 外部配置 |
| `--app.name` / `--app.group` / `--app.title` | 对应键 | 应用元信息 |
| `--stop.safe` / `--stop.delay` | 对应键 | 安全停止（默认 delay 10s） |

```bash
java -Dsolon.env=dev -jar demo.jar
java -jar demo.jar --env=dev
```

### Core Properties（精简）

```yaml
server.port: 8080
server.host: "0.0.0.0"
server.contextPath: "/test-service/"   # v1.11.2+

solon.app.name: "my-app"
solon.app.group: "my-group"
solon.app.namespace: "demo"            # 一般用不到
solon.app.title: "My App"
solon.app.enabled: true

solon.env: dev                         # 加载 app-dev.yml
solon.debug: true
solon.logging.logger.root.level: INFO
solon.threads.virtual.enabled: true    # Java 21+，v2.7.3+
solon.stop.safe: 0
solon.stop.delay: 10
solon.output.meta: 1
solon.extend: "ext"                    # E-SPI 体外扩展；"!" 前缀可自动建目录
```

### Server 线程 / 请求 / 会话（按需）

| 前缀 | 常用键 | 说明 |
|---|---|---|
| `server.http.` | `name`, `port`, `host`, `coreThreads`, `maxThreads`, `idleTimeout`, `ioBound` | 0=自动；线程支持固定值或 `x2`/`x32` 内核倍数 |
| `server.socket.` | 同上结构 | 默认 port=`20000+${server.port}` |
| `server.websocket.` | 同上结构 | 默认 port=`10000+${server.port}` |
| `server.request.` | `maxBodySize`(2mb), `maxFileSize`, `maxHeaderSize`(8kb), `fileSizeThreshold`(512kb), `useRawpath`, `encoding` | 上传与编码 |
| `server.response.` | `encoding` | 响应编码 |
| `server.session.` | `timeout`(7200), `cookieName`(SOLONID), `cookieDomain` | 会话 |
| `server.ssl.` / `server.http.ssl.` | `keyStore`, `keyPassword`, `enable` | 公共或分信号 SSL（v2.3.7+） |
| `server.http.gzip.` | `enable`(false), `minSize`(4096), `mimeTypes` | Gzip（v2.5.7+） |

信号名默认 `${solon.app.name}`（socket/ws 可带后缀）。

### 配置增强与变量

```yaml
solon.config.add: "./demo.yml"         # 外部配置，"," 分隔
solon.config.load:                     # 额外内部配置 v2.2.7+
  - "app-ds-${solon.env}.yml"
  - "config/common.yml"

test.demo1: "${db1.url}"               # 引用属性
test.demo2: "jdbc:mysql:${db1.server}" # 组合
test.demo3: "${JAVA_HOME}"             # 环境变量
test.demo4: "${.demo3}"                # 本级引用 v2.9.0+
test.demo5: "${solon.app.title:}"      # ":" 后缀可为空
```

### 多环境

- `app.yml` 始终加载；`app-{env}.yml` 随 `solon.env` 加载
- 优先级（高→低）：环境变量 → `--env` → `-Dsolon.env` → `app.yml` 内 `solon.env`
- YAML 多片段（v2.5.5+）：`---` + `solon.env.on: pro|dev|test`

## 3. WebSocket API

| 类型 | Description |
|---|---|
| `WebSocket` | 会话 send/onClose |
| `WebSocketListener` / `SimpleWebSocketListener` | 监听接口 / 适配器 |
| `PipelineWebSocketListener` / `PathWebSocketListener` | 管道 / 路径分发 |
| `@ServerEndpoint` / `@ClientEndpoint` | 服务端 / 客户端端点路径 |

```java
@ServerEndpoint("/ws/chat")
public class ChatWebSocket extends SimpleWebSocketListener {
    @Override
    public void onOpen(WebSocket socket) { }
    @Override
    public void onMessage(WebSocket socket, String message) throws IOException { }
    @Override
    public void onClose(WebSocket socket) { }
}
```

## 4. EventBus API

| Method | Description |
|---|---|
| `publish(event)` | 同步发布（可传导异常） |
| `publishTry(event)` | 同步，不抛异常 |
| `publishAsync(event)` | 异步 |
| `subscribe(Class, listener)` / `subscribe(Class, priority, listener)` | 订阅 |

```java
EventBus.publish(new UserCreatedEvent("solon"));
EventBus.subscribe(UserCreatedEvent.class, event -> {
    System.out.println("User created: " + event.username);
});
```

## 5. Filter & RouterInterceptor

| 类型 | Description |
|---|---|
| `Filter` | 全局过滤器，最外层 |
| `RouterInterceptor` | 仅动态路由，在 Filter 之后 |
| `@Component(index=N)` | index 越小越先执行 |

```java
@Component(index = 1)
public class AuthFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        if (ctx.header("Authorization") == null) {
            ctx.outputAsJson(Result.failure("Unauthorized"));
            return;
        }
        chain.doFilter(ctx);
    }
}

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