# Controller 与请求上下文迁移参考

> Spring Boot → Solon Web 层 Controller 与请求上下文迁移指南（目标版本：Solon 3.10.x）

## 1. Controller 迁移

### 1.1 基本控制器结构

#### Before — Spring

```java
package com.example.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping
    public List<User> list() {
        return userService.findAll();
    }

    @GetMapping("/{id}")
    public User get(@PathVariable Long id) {
        return userService.findById(id);
    }

    @PostMapping
    public User create(@RequestBody User user) {
        return userService.save(user);
    }

    @PutMapping("/{id}")
    public User update(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        return userService.update(user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        userService.deleteById(id);
    }
}
```

#### After — Solon

```java
package com.example.controller;

import org.noear.solon.annotation.*;
import org.noear.solon.annotation.Mapping;
import java.util.List;

@Controller
@Mapping("/api/users")
public class UserController {

    @Inject
    private UserService userService;

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
    public User create(@Body User user) {
        return userService.save(user);
    }

    @Put
    @Mapping("/{id}")
    public User update(@Path long id, @Body User user) {
        user.setId(id);
        return userService.update(user);
    }

    @Delete
    @Mapping("/{id}")
    public void delete(@Path long id) {
        userService.deleteById(id);
    }
}
```

> **关键差异**：
> - `@RestController` → `@Controller`（Solon 默认 JSON 输出，不存在 `@RestController`）。
> - `@RequestMapping` → `@Mapping`。
> - `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping` → `@Get`+`@Mapping` / `@Post`+`@Mapping` 等（HTTP 方法与路径分离为两个注解）。
> - `@Autowired` → `@Inject`。
> - `@PathVariable` → `@Path`；`@RequestBody` → `@Body`。
> - **`@Mapping` 不支持多路径映射**（如 `@RequestMapping({"/a", "/b"})` 在 Solon 中不可用）。
> - 控制器继承时，Solon 支持基类的 `@Mapping` public 函数。

### 1.2 HTTP 方法映射

#### Before — Spring

```java
@GetMapping("/list")
public List<User> list() { ... }

@PostMapping("/add")
public User add(@RequestBody User user) { ... }

@PutMapping("/update")
public User update(@RequestBody User user) { ... }

@DeleteMapping("/delete/{id}")
public void delete(@PathVariable Long id) { ... }

// 也可以用 @RequestMapping 的 method 属性
@RequestMapping(value = "/search", method = RequestMethod.GET)
public List<User> search(@RequestParam String keyword) { ... }
```

#### After — Solon

```java
@Get
@Mapping("/list")
public List<User> list() { ... }

@Post
@Mapping("/add")
public User add(@Body User user) { ... }

@Put
@Mapping("/update")
public User update(@Body User user) { ... }

@Delete
@Mapping("/delete/{id}")
public void delete(@Path long id) { ... }

// 等价写法：@Mapping 的 method 属性
@Mapping(value = "/search", method = MethodType.GET)
public List<User> search(@Param String keyword) { ... }
```

> **陷阱**：
> - Spring 的 `@GetMapping`、`@PostMapping` 等是路径+方法的复合注解；Solon 拆分为 `@Get` + `@Mapping`。
> - 推荐使用 `@Get`/`@Post` + `@Mapping` 的组合写法，可读性更好。
> - Solon 也支持 `@Mapping(method = MethodType.POST)` 的单注解写法。

### 1.3 请求参数注解对照

| Spring 注解 | Solon 注解 | 说明 |
|---|---|---|
| `@RequestParam` | `@Param` | URL 查询参数 / 表单参数 |
| `@RequestHeader` | `@Header` | 请求头 |
| `@RequestBody` | `@Body` | 请求体（JSON 绑定） |
| `@PathVariable` | `@Path` | 路径变量 |
| `@CookieValue` | `@Cookie` | Cookie 值 |

#### Before — Spring

```java
@PostMapping("/order/{storeId}")
public Order createOrder(
        @RequestParam String product,
        @RequestParam(defaultValue = "1") int quantity,
        @RequestHeader("X-Token") String token,
        @RequestBody OrderRequest body,
        @CookieValue("sessionId") String sessionId,
        @PathVariable("storeId") Long storeId) {
    // ...
}
```

#### After — Solon

```java
@Post
@Mapping("/order/{storeId}")
public Order createOrder(
        @Param String product,
        @Param(defaultValue = "1") int quantity,
        @Header("X-Token") String token,
        @Body OrderRequest body,
        @Cookie("sessionId") String sessionId,
        @Path long storeId) {
    // ...
}
```

> **注意**：
> - Solon 的 `@Param`、`@Body` 等注解与 Spring 对应注解行为**不完全对等**，迁移时需逐一验证参数绑定结果。
> - `@Param` 支持 `defaultValue` 属性。
> - Solon 路径变量类型推荐使用基本类型（`long`、`int`、`String`），避免使用包装类型。

### 1.4 内容类型声明

#### Before — Spring

```java
@GetMapping(value = "/data", produces = "application/json", consumes = "application/json")
public Data getData() { ... }

@PostMapping(value = "/xml", consumes = "application/xml", produces = "application/xml")
public Data createXml(@RequestBody Data data) { ... }
```

#### After — Solon

```java
@Get
@Mapping("/data")
@Produces("application/json")
@Consumes("application/json")
public Data getData() { ... }

@Post
@Mapping("/xml")
@Produces("application/xml")
@Consumes("application/xml")
public Data createXml(@Body Data data) { ... }
```

> **差异说明**：Spring 将 `produces`/`consumes` 作为 `@RequestMapping` 的属性内联声明；Solon 使用独立的 `@Produces` 和 `@Consumes` 注解，关注点分离更清晰。

## 2. 请求上下文迁移

### 2.1 HttpServletRequest/Response → Context

#### Before — Spring

```java
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/info")
    public Map<String, Object> getInfo(HttpServletRequest request) {
        Map<String, Object> info = new HashMap<>();
        info.put("path", request.getRequestURI());
        info.put("method", request.getMethod());
        info.put("clientIp", request.getRemoteAddr());
        info.put("contentType", request.getContentType());
        info.put("userAgent", request.getHeader("User-Agent"));
        return info;
    }

    @GetMapping("/redirect")
    public void redirect(HttpServletResponse response) throws IOException {
        response.sendRedirect("/new-path");
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.Context;

@Controller
@Mapping("/api")
public class DemoController {

    @Get
    @Mapping("/info")
    public Map<String, Object> getInfo(Context ctx) {
        Map<String, Object> info = new HashMap<>();
        info.put("path", ctx.path());
        info.put("method", ctx.method());
        info.put("clientIp", ctx.remoteIp());
        info.put("contentType", ctx.contentType());
        info.put("userAgent", ctx.header("User-Agent"));
        return info;
    }

    @Get
    @Mapping("/redirect")
    public void redirect(Context ctx) {
        ctx.redirect("/new-path");
    }
}
```

> **关键差异**：
> - Spring 使用 `HttpServletRequest` + `HttpServletResponse` 两个对象；Solon 使用统一的 `Context` 对象封装所有请求/响应操作。
> - `Context` 是 Solon 的核心概念，非 Servlet 架构，但兼容 Servlet 容器。

### 2.2 Context 关键方法速查

| 分类 | 方法 | 说明 |
|---|---|---|
| **请求信息** | `ctx.path()` | 请求路径 |
| | `ctx.method()` | HTTP 方法 |
| | `ctx.remoteIp()` / `ctx.realIp()` | 客户端 IP |
| | `ctx.url()` | 完整 URL |
| **请求参数** | `ctx.param("name")` | 获取请求参数 |
| | `ctx.header("name")` | 获取请求头 |
| | `ctx.cookie("name")` | 获取 Cookie 值 |
| **请求体** | `ctx.body()` | 请求体字符串 |
| | `ctx.bodyAsBean(User.class)` | 请求体反序列化为 Bean |
| **响应输出** | `ctx.render(obj)` | 输出对象（自动 JSON 序列化） |
| | `ctx.output(str)` | 输出字符串 |
| | `ctx.outputAsFile(file)` | 输出文件 |
| **响应控制** | `ctx.redirect(url)` | 重定向 |
| | `ctx.forward(path)` | 转发 |
| | `ctx.status(code)` | 设置 HTTP 状态码 |
| | `ctx.headerSet("name", "value")` | 设置响应头 |
| **会话** | `ctx.sessionSet(key, val)` | 设置会话属性 |
| | `ctx.session(key)` | 获取会话属性 |

### 2.3 Context 注入方式

Solon 支持在控制器方法参数中直接声明 `Context`，框架会自动注入当前请求上下文：

```java
@Controller
@Mapping("/api")
public class ContextController {

    // 方式一：方法参数注入（推荐）
    @Get
    @Mapping("/demo1")
    public String demo1(Context ctx) {
        return "客户端 IP: " + ctx.remoteIp();
    }

    // 方式二：在 Filter/Interceptor 中通过回调参数获取
    // 见 Filter/Interceptor 迁移参考文档
}
```

> **注意**：`Context` 不支持通过 `@Inject` 在字段上注入（它是请求作用域对象），只能在方法参数中使用。
