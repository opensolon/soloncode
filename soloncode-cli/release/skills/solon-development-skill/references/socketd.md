# Socket.D — 双向通信协议

> 适用场景：Socket.D 双向通信（tcp/ws/udp）、消息应答/订阅、与 HTTP 共享端口。
>
> 目标版本：4.0.3。Nami RPC 见 `remoting.md`；过滤器/发现/负载均衡见 `remoting_filter_lb.md`。

Solon 特色通信协议，支持 tcp、ws、udp 传输。

Dependency：`solon-server-socketd` + 传输协议包（如 `socketd-transport-netty`）

## 服务端集成

引入依赖：

```xml
<!-- socket.d 的 solon 服务启动插件 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-server-socketd</artifactId>
</dependency>

<!-- 传输协议包（按需选择），会使用独立的端口 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>socketd-transport-netty</artifactId>
    <version>${socketd.version}</version>
</dependency>
```

启用服务：

```java
public class DemoApp {
    public static void main(String[] args) {
        Solon.start(DemoApp.class, args, app -> {
            // 启用 Socket.D 服务
            app.enableSocketD(true);
        });
    }
}
```

使用 `@ServerEndpoint` 监听：

```java
@ServerEndpoint("/demo/{id}")
public class SocketDDemo extends SimpleListener {
    @Override
    public void onMessage(Session session, Message message) throws IOException {
        session.send("test", new StringEntity("我收到了：" + message));
        // session.param("id"); // 获取路径变量、queryString 变量、握手变量
    }
}
```

### 集成配置参考

```yaml
# 服务 socket 信号名称（默认为 ${solon.app.name}）
server.socket.name: "waterapi.tcp"
# 服务 socket 信号端口（默认为 20000+${server.port}）
server.socket.port: 28080
# 服务 socket 信号主机（ip）
server.socket.host: "0.0.0.0"
# 服务 socket 信号包装端口（一般用 docker + 服务注册时才可能用到）
server.socket.wrapPort: 28080
# 服务 socket 信号包装主机
server.socket.wrapHost: "0.0.0.0"
# 服务 socket 最小线程数（默认 0 表示自动，支持固定值 2 或倍数 x2）
server.socket.coreThreads: 0
# 服务 socket 最大线程数（默认 0 表示自动，支持固定值 32 或倍数 x32）
server.socket.maxThreads: 0
# 服务 socket 闲置线程或连接超时（0 表示自动，单位毫秒）
server.socket.idleTimeout: 0
# 服务 socket 是否为 IO 密集型
server.socket.ioBound: true
```

不同协议架构的独立端口自动处理：

| 协议架构 | 端口 | 示例 |
|---|---|---|
| sd:tcp | ${server.socket.port} | 28080 |
| sd:udp | ${server.socket.port} + 1 | 28081 |
| sd:ws | ${server.socket.port} + 2 | 28082 |

### 客户端连接

```java
@Configuration
public class SdConfig {
    @Bean
    public ClientSession clientSession() throws IOException {
        return SocketD.createClient("sd:tcp://127.0.0.1:18602").open();
    }
}
```

### Mono 模式（请求-应答）

```java
@Controller
public class DemoController {
    @Inject ClientSession clientSession;

    @Mapping("/hello")
    public Mono<String> hello(String name) {
        return Mono.create(sink -> {
            Entity entity = new StringEntity("hello").metaPut("name", name);
            clientSession.sendAndRequest("hello", entity)
                    .thenReply(reply -> sink.success(reply.dataAsString()))
                    .thenError(sink::error);
        });
    }
}
```

### Flux 模式（订阅-流式）

```java
@Mapping("/hello2")
public Flux<String> hello2(String name) {
    return Flux.create(sink -> {
        Entity entity = new StringEntity("hello")
                .metaPut("name", name).range(5, 5);
        clientSession.sendAndSubscribe("hello", entity)
                .thenReply(reply -> {
                    sink.next(reply.dataAsString());
                    if (reply.isEnd()) sink.complete();
                })
                .thenError(sink::error);
    });
}
```

### Socket.D 协议转 MVC 接口

Socket.D 支持将协议转为标准 MVC 风格接口（v2.6.0 后支持），可以像写 HTTP 接口一样写 Socket.D 服务。

服务端代码：

```java
// 协议转换处理
@ServerEndpoint("/mvc/")
public class SocketdAsMvc extends ToHandlerListener {
    @Override
    public void onOpen(Session s) {
        // 可选：加鉴权
        if (!"a".equals(s.param("u"))) {
            s.close();
            return;
        }
        super.onOpen();
    }
}

// 控制器
@Controller
public class HelloController {
    @Socket // 不加限定注解的话，可同时支持 http 请求
    @Mapping("/mvc/hello")
    public Result hello(long id, String name) {
        return Result.succeed();
    }
}
```

客户端以 RPC 代理模式调用（引入 `nami-channel-socketd`）：

```java
// 客户端调用服务端的 MVC
HelloService rpc = SocketdProxy.create("sd:ws://localhost:28082/mvc/?u=a", HelloService.class);
System.out.println("MVC result:: " + rpc.hello("noear"));
```

### Socket.D 主要场景

| 场景 | 说明 |
|---|---|
| 消息上报 | 单向消息发送 |
| 消息应答 | 请求-响应模式 |
| 消息订阅 | 流式数据推送 |
| RPC 调用 | 远程方法调用 |
| 双向 RPC | 单连接双向调用 |
| 消息鉴权 | 带认证的消息通信 |
| RPC 鉴权 | 带认证的远程调用 |

### 借用 HTTP Server 端口

通过 WebSocket 把 Socket.D 挂在 HTTP 端口上，避免再开独立 socket 端口：

```java
// 依赖：solon-web + solon-net + socket.d
// 启动时启用 WebSocket：Solon.start(App.class, args, app -> app.enableWebSocket(true));

@ServerEndpoint("/sd")
public class SocketdOnHttp extends ToSocketdWebSocketListener {
    public SocketdOnHttp() {
        super(new ConfigDefault(false), new EventListener()
                .doOnOpen(s -> System.out.println("open: " + s.sessionId()))
                .doOnMessage((s, m) -> System.out.println("msg: " + m)));
    }
}
```

客户端连接（走 HTTP 端口上的 WebSocket 路径）：

```java
// 假设 HTTP 端口 8080
ClientSession session = SocketD.createClient("sd:ws://127.0.0.1:8080/sd").open();
```

> 独立 Socket.D 服务（`solon-server-socketd`）仍使用 `server.socket.port` 及 tcp/udp/ws 偏移端口；与 HTTP 共享端口时优先用 `ToSocketdWebSocketListener`。
