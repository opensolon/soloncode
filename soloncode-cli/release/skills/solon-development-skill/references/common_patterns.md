# Common Patterns — 常用开发模式

> 适用场景：REST API、Service、Filter、定时任务、全局异常处理的最短可运行片段。
>
> 目标版本：4.0.3。  
> WebSocket / EventBus 完整 API → `api_reference.md`  
> 单元测试 / HTTP 测试 → `testing.md`  
> 数据访问 / 事务 → `data_access.md`

## REST API with JSON

```java
@Controller
@Mapping("/api/users")
public class UserController {
    @Inject
    UserService userService;

    @Get
    @Mapping("")
    public List<User> list() {
        return userService.findAll();
    }

    @Get
    @Mapping("/{id}")
    public User get(@Path long id) {
        return userService.findById(id);
    }

    @Post
    @Mapping("")
    public long create(@Body User user) {
        return userService.insert(user);
    }

    @Put
    @Mapping("/{id}")
    public void update(@Path long id, @Body User user) {
        user.setId(id);
        userService.update(user);
    }

    @Delete
    @Mapping("/{id}")
    public void delete(@Path long id) {
        userService.deleteById(id);
    }
}
```

## Service Component

```java
@Component // 禁止使用 Spring 的 @Service
public class UserService {
    @Inject
    UserMapper userMapper;

    public List<User> findAll() {
        return userMapper.selectAll();
    }
}
```

## Configuration Bean

```java
@Configuration
public class DataSourceConfig {
    @Bean(name = "db1", typed = true)
    public DataSource db1(@Inject("${db1}") HikariDataSource ds) {
        return ds;
    }
}
```

> 更推荐 `solon.dataSources` 自动构建，见 `data_access.md`。

## Filter (Middleware)

```java
@Component
public class LogFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        long start = System.currentTimeMillis();
        chain.doFilter(ctx);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println(ctx.path() + " took " + elapsed + "ms");
    }
}
```

## Scheduled Task

依赖：`solon-scheduling-simple`。

> 必须在启动类上添加 `@EnableScheduling`，`@Scheduled` 才会生效。完整包名：`org.noear.solon.scheduling.annotation.Scheduled`。Solon 官方规范要求 **7 位** cron。

```java
@EnableScheduling
@SolonMain
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
    }
}

@Component
public class MyJob {
    @Scheduled(cron = "0 0/5 * * * ? *") // 每 5 分钟
    public void run() {
        // ...
    }
}
```

## WebSocket（最短示例）

依赖：`solon-server-websocket`。完整 API 见 `api_reference.md`。

```java
@ServerEndpoint("/ws/chat/{roomId}")
public class WebSocketChat extends SimpleWebSocketListener {
    @Override
    public void onOpen(WebSocket socket) {
        String roomId = socket.param("roomId");
        System.out.println("用户加入房间: " + roomId);
    }

    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        socket.send("[Echo] " + text);
    }
}
```

入口启用：`Solon.start(App.class, args, app -> app.enableWebSocket(true));`

## EventBus（最短示例）

完整方法表见 `api_reference.md`。

```java
public class UserCreatedEvent {
    public final String username;
    public UserCreatedEvent(String username) { this.username = username; }
}

@Component
public class UserCreatedListener implements EventListener<UserCreatedEvent> {
    @Override
    public void onEvent(UserCreatedEvent event) throws Throwable {
        System.out.println("新用户: " + event.username);
    }
}

// 发布
EventBus.publish(new UserCreatedEvent("张三"));       // 同步（可传导异常）
EventBus.publishAsync(new UserCreatedEvent("张三"));  // 异步
```

## Global Exception Handling

```java
@Component(index = 0)
public class GlobalExceptionFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.outputAsJson("{\"code\":400,\"msg\":\"" + e.getMessage() + "\"}");
        } catch (Throwable e) {
            ctx.status(500);
            ctx.outputAsJson("{\"code\":500,\"msg\":\"服务端运行出错\"}");
        }
    }
}
```
