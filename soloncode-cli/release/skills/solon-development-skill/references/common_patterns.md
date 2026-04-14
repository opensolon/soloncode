# Common Patterns — 常用开发模式

> 适用场景：REST API、Service、Filter、定时任务、测试、WebSocket、EventBus、全局异常处理。

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
@Component
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

With `solon-scheduling-simple`:

```java
@Component
public class MyJob {
    @Scheduled(cron = "0 0/5 * * * ?")
    public void run() {
        // Execute every 5 minutes
    }
}
```

## Unit Testing

With `solon-test-junit5`:

```java
@SolonTest(App.class)
public class UserControllerTest {
    @Inject
    UserService userService;

    @Test
    public void testFindAll() {
        List<User> users = userService.findAll();
        assert users != null;
    }
}
```

## WebSocket

Dependency: `solon-server-websocket`

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

> Enable in entry class: `Solon.start(App.class, args, app -> app.enableWebSocket(true));`

## Event-Driven (EventBus)

```java
// 定义事件
public class UserCreatedEvent {
    public final String username;
    public UserCreatedEvent(String username) { this.username = username; }
}

// 订阅事件
@Component
public class UserCreatedListener implements EventListener<UserCreatedEvent> {
    @Override
    public void onEvent(UserCreatedEvent event) throws Throwable {
        System.out.println("新用户: " + event.username);
    }
}

// 发布事件
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
