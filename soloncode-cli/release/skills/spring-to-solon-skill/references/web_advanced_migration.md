# Web 进阶特性迁移参考

> Spring Boot → Solon Web 文件上传下载、SSE/WebSocket、参数校验、会话管理迁移指南（目标版本：Solon 3.10.x）

## 6. 文件上传 / 下载

### 6.1 MultipartFile → UploadedFile

#### Before — Spring

```java
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        String originalName = file.getOriginalFilename();
        long size = file.getSize();
        try {
            file.transferTo(new File("/uploads/" + originalName));
            return Map.of("success", true, "name", originalName, "size", size);
        } catch (IOException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.UploadedFile;

@Controller
@Mapping("/api/files")
public class FileController {
    @Post
    @Mapping("/upload")
    public Map<String, Object> upload(UploadedFile file) {
        if (file == null) {
            return Map.of("success", false, "message", "未选择文件");
        }
        String originalName = file.getName();
        long size = file.getContentSize();
        try {
            file.transferTo(new File("/uploads/" + originalName));
            return Map.of("success", true, "name", originalName, "size", size);
        } catch (IOException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }
}
```

> **关键差异**：
> - `MultipartFile` → `UploadedFile`。
> - `getOriginalFilename()` → `getName()`；`getSize()` → `getContentSize()`。
> - `transferTo()` 方法名相同，行为一致。
> - Solon 的 `UploadedFile` 不需要 `@RequestParam` 注解，框架会自动绑定。

### 6.2 文件下载（DownloadedFile）

#### Before — Spring

```java
@GetMapping("/download/{filename}")
public ResponseEntity<Resource> download(@PathVariable String filename) throws IOException {
    File file = new File("/uploads/" + filename);
    if (!file.exists()) return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new FileSystemResource(file));
}
```

#### After — Solon

```java
// 方式一：DownloadedFile 封装（推荐）
@Get
@Mapping("/download/{filename}")
public DownloadedFile download(@Path String filename) {
    File file = new File("/uploads/" + filename);
    if (!file.exists()) return null;  // 框架会返回 404
    return new DownloadedFile("application/octet-stream", file, filename);
}

// 方式二：Context 直接输出
@Get
@Mapping("/download2/{filename}")
public void download2(@Path String filename, Context ctx) {
    File file = new File("/uploads/" + filename);
    if (!file.exists()) { ctx.status(404); return; }
    ctx.outputAsFile(file);
}
```

> **注意**：`DownloadedFile` 是 Solon 独有的文件下载封装类，Spring 中没有等价物。

### 6.3 多文件上传

#### Before — Spring

```java
@PostMapping("/batch-upload")
public Map<String, Object> batchUpload(@RequestParam("files") MultipartFile[] files) {
    return Map.of("count", files.length);
}
```

#### After — Solon

```java
@Post
@Mapping("/batch-upload")
public Map<String, Object> batchUpload(Context ctx) {
    UploadedFile[] files = ctx.fileValues("files");
    return Map.of("count", files.length);
}
```

> **注意**：多文件上传时，Solon 通过 `ctx.fileValues("fieldName")` 获取 `UploadedFile[]` 数组，而非通过方法参数绑定。

## 7. SSE / WebSocket

### 7.1 SseEmitter → SseEmitter (solon-web-sse)

#### Before — Spring

```java
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/sse")
public class SseController {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping("/connect")
    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        return emitter;
    }

    @PostMapping("/send")
    public void send(@RequestBody String message) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(message).name("message"));
            } catch (IOException e) { emitters.remove(emitter); }
        }
    }
}
```

#### After — Solon

需引入 `solon-web-sse` 插件：

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-web-sse</artifactId>
</dependency>
```

```java
import org.noear.solon.web.sse.SseEmitter;
import org.noear.solon.web.sse.SseEvent;

@Controller
@Mapping("/api/sse")
public class SseController {
    static Map<String, SseEmitter> emitterMap = new HashMap<>();

    @Get
    @Mapping("/connect/{id}")
    public SseEmitter connect(String id) {
        return new SseEmitter(3000L)
                .onCompletion(() -> emitterMap.remove(id))
                .onInited(s -> emitterMap.put(id, s));
    }

    @Get
    @Mapping("/send/{id}")
    public String send(String id) {
        SseEmitter emitter = emitterMap.get(id);
        if (emitter != null) {
            emitter.send(new SseEvent().data("message content"));
        }
        return "Ok";
    }
}
```

> **关键差异**：
> - Spring 使用 `SseEmitter`（`spring-webmvc`）；Solon 同样提供 `SseEmitter`（`solon-web-sse` 插件），API 风格相似但包名不同。
> - Solon 的 `SseEmitter` 支持 `onCompletion`、`onInited` 等回调，通过 `SseEvent` 构建事件数据。
> - 需要单独引入 `solon-web-sse` 依赖，不是 `solon-web` 内置功能。

### 7.2 Spring WebSocket → Solon WebSocket

#### Before — Spring

```java
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) { sessions.add(session); }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        for (WebSocketSession s : sessions) {
            s.sendMessage(new TextMessage(message.getPayload()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }
}

// 需要额外的配置类注册端点
@Configuration @EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat");
    }
}
```

#### After — Solon

```java
@ServerEndpoint("/ws/chat")
@Component
public class ChatWebSocketEndpoint implements WebSocketListener {
    private final List<WebSocket> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void onOpen(WebSocket socket) { sessions.add(socket); }

    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        for (WebSocket s : sessions) { s.send(text); }
    }

    @Override
    public void onClose(WebSocket socket) { sessions.remove(socket); }

    @Override
    public void onError(WebSocket socket, Throwable error) { /* 处理异常 */ }
}
```

> **关键差异**：
> - Spring 使用 `TextWebSocketHandler` + `WebSocketConfigurer` 注册；Solon 使用 `@ServerEndpoint` + `WebSocketListener`。
> - Spring 需要额外的 `@Configuration` 类；Solon 的 `@ServerEndpoint` + `@Component` 一步完成。
> - Solon **不使用** `@OnOpen`/`@OnMessage` 等注解，而是实现 `WebSocketListener` 接口。
> - 会话类型是 `WebSocket`（不是 `WebSocketSession`）。需引入 `solon-websocket` 依赖。

## 8. 请求参数校验

### 8.1 @Valid + @Validated → @Valid

#### Before — Spring

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody UserDTO user,
                                      BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return Map.of("code", 400, "message", bindingResult.getFieldError().getDefaultMessage());
        }
        return Map.of("code", 200, "data", user);
    }
}

public class UserDTO {
    @NotBlank(message = "用户名不能为空") private String username;
    @Email(message = "邮箱格式不正确") private String email;
    @Size(min = 6, max = 20, message = "密码长度 6-20 位") private String password;
}
```

#### After — Solon

```java
@Valid    // 类级 @Valid 开启参数校验功能
@Controller
@Mapping("/api/users")
public class UserController {
    @Post
    @Mapping("")
    public Map<String, Object> create(@Body UserDTO user) {
        // 校验不通过时，Solon 自动返回 400 错误
        return Map.of("code", 200, "data", user);
    }
}

// DTO 使用 Solon 内置校验注解（org.noear.solon.validation.annotation.*）
// 注意：Solon 不兼容 javax.validation 注解
public class UserDTO {
    @NotBlank(message = "用户名不能为空") private String username;
    @Email(message = "邮箱格式不正确") private String email;
    @Size(min = 6, max = 20, message = "密码长度 6-20 位") private String password;
}
```

> **关键差异**：
> - Spring 使用 `@Validated`/`@Valid` + `BindingResult` 手动处理；Solon 类上标注 `@Valid` 即自动触发校验。
> - Solon **没有 `BindingResult`**，校验失败由框架自动处理。需引入 `solon-validation` 依赖。

### 8.2 批量参数校验

Solon 支持在方法参数上直接使用校验注解：

```java
// Spring 不支持在方法参数上直接加校验注解，需封装 DTO

// Solon 写法：
@Valid
@Controller
@Mapping("/api")
public class RegisterController {
    @Post
    @Mapping("/register")
    public String register(
            @NotBlank(message = "姓名不能为空") String name,
            @Email(message = "邮箱格式不正确") String email,
            @Pattern("13\\d{9}") String mobile) {
        return "ok";
    }
}
```

> **注意**：控制器类上必须标注 `@Valid` 才能激活校验。参数约束直接在方法签名上声明，比 Spring 的 DTO 封装更直观。

### 8.3 实体校验

```java
// Spring: @Validated @RequestBody UserDTO user
// Solon:  @Validated UserDTO user（@Validated 触发实体内部校验注解）
```

## 9. 会话管理

### 9.1 HttpSession → Context

#### Before — Spring

```java
@RestController
@RequestMapping("/api/session")
public class SessionController {
    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                     @RequestParam String password,
                                     HttpSession session) {
        if ("admin".equals(username) && "123456".equals(password)) {
            session.setAttribute("userId", 1001L);
            session.setAttribute("username", username);
            return Map.of("code", 200, "message", "登录成功");
        }
        return Map.of("code", 401, "message", "用户名或密码错误");
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Map.of("code", 200, "message", "已退出");
    }
}
```

#### After — Solon

```java
@Controller
@Mapping("/api/session")
public class SessionController {
    @Post
    @Mapping("/login")
    public Map<String, Object> login(@Param String username,
                                     @Param String password, Context ctx) {
        if ("admin".equals(username) && "123456".equals(password)) {
            ctx.sessionSet("userId", 1001L);
            ctx.sessionSet("username", username);
            return Map.of("code", 200, "message", "登录成功");
        }
        return Map.of("code", 401, "message", "用户名或密码错误");
    }

    @Post
    @Mapping("/logout")
    public Map<String, Object> logout(Context ctx) {
        ctx.sessionClear();
        return Map.of("code", 200, "message", "已退出");
    }
}
```

> **关键差异**：
> - `HttpSession` → `Context` 的 `sessionSet`/`session`/`sessionClear` 方法。
> - `sessionSet(key, val)` ≡ `setAttribute`；`session(key)` ≡ `getAttribute`（返回 Object）；`sessionClear()` ≡ `invalidate()`。

### 9.2 会话超时配置

```yaml
# Spring: timeout: 30m
# Solon:  timeout: 1800  （单位：秒）
```

> **注意**：Solon 的会话超时单位是**秒**，Spring Boot 支持 `30m` 字符串格式。迁移时注意换算。

## 10. Web 层陷阱与差异清单

### 陷阱速查表

| 编号 | 陷阱描述 | 严重程度 | 详细说明 |
|---|---|---|---|
| 1 | **@Controller 默认返回 JSON** | 中 | Solon 不存在 `@RestController`，`@Controller` 默认即 JSON 输出。如需返回视图需使用 `ModelAndView`。 |
| 2 | **@Mapping 不支持多路径** | 中 | 一个 `@Mapping` 只能映射一个路径模式。需要多路径时须定义多个方法。 |
| 3 | **Context 不支持字段注入** | 低 | `Context` 是请求作用域对象，只能在方法参数中使用。 |
| 4 | **Filter 顺序需显式指定** | 高 | 通过 `@Component(index=N)` 控制。不指定时执行顺序不确定，可能导致逻辑错乱。 |
| 5 | **没有 @ControllerAdvice** | 高 | 全局异常处理需通过 Filter 的 try-catch 实现。 |
| 6 | **没有 BindingResult** | 中 | 参数校验失败由框架自动返回 400。自定义错误响应需通过 Filter 拦截。 |
| 7 | **CORS 需引入独立插件** | 低 | 需引入 `solon-web-cors`。支持注解/编程式/拦截器三种方式。**不提供 YAML 配置**。 |
| 8 | **文件上传参数无需注解** | 低 | `UploadedFile` 自动绑定。多文件通过 `ctx.fileValues()` 获取。 |
| 9 | **会话超时单位不同** | 中 | Solon 单位是**秒**，Spring Boot 支持 `30m` 字符串格式。 |
| 10 | **控制器继承行为** | 低 | 支持基类 `@Mapping` public 函数继承，protected 方法不会被路由。 |
| 11 | **SSE 需引入专用插件** | 低 | `SseEmitter` 在 `solon-web-sse` 插件中。 |
| 12 | **WebSocket 端点声明方式不同** | 中 | 使用 `@ServerEndpoint` + `implements WebSocketListener`。 |

### Web 层迁移检查清单

- [ ] `@RestController` → `@Controller`（全局替换）
- [ ] `@RequestMapping` → `@Mapping`
- [ ] `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping` → `@Get`/`@Post`/`@Put`/`@Delete` + `@Mapping`
- [ ] `@RequestParam` → `@Param` / `@RequestBody` → `@Body` / `@PathVariable` → `@Path`
- [ ] `@RequestHeader` → `@Header` / `@CookieValue` → `@Cookie`
- [ ] `HttpServletRequest` + `HttpServletResponse` → `Context`
- [ ] `HttpSession` → `Context.sessionSet/session/sessionClear`
- [ ] `OncePerRequestFilter` → `Filter` 接口
- [ ] `HandlerInterceptor` → `RouterInterceptor`
- [ ] `@ControllerAdvice` + `@ExceptionHandler` → `Filter` + try-catch
- [ ] `@CrossOrigin` / `WebMvcConfigurer` → `@CrossOrigin`（注解）或 `CrossFilter`（编程式）
- [ ] `MultipartFile` → `UploadedFile`
- [ ] `SseEmitter`(Spring) → `SseEmitter`(Solon, solon-web-sse)
- [ ] `TextWebSocketHandler` → `@ServerEndpoint` + `WebSocketListener`
- [ ] `@Valid` + `BindingResult` → `@Valid`（类级）+ 框架自动处理
- [ ] `produces`/`consumes` → `@Produces`/`@Consumes`
- [ ] 多路径映射 → 拆分为多个方法
- [ ] 所有 Filter 显式指定 `@Component(index=N)`
