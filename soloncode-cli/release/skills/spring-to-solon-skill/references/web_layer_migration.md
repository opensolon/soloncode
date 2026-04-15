# Web 层迁移参考手册

> Spring Boot → Solon Web 层迁移指南（目标版本：Solon 3.10.x）
>
> 本文档聚焦 Controller、请求上下文、Filter/Interceptor、全局异常处理、CORS、文件上传下载、SSE/WebSocket、参数校验、会话管理共九大主题，提供完整的代码对照与差异陷阱标注。

---

## 目录

- [1. Controller 迁移](#1-controller-迁移)
  - [1.1 基本控制器结构](#11-基本控制器结构)
  - [1.2 HTTP 方法映射](#12-http-方法映射)
  - [1.3 请求参数注解对照](#13-请求参数注解对照)
  - [1.4 内容类型声明](#14-内容类型声明)
- [2. 请求上下文迁移](#2-请求上下文迁移)
  - [2.1 HttpServletRequest/Response → Context](#21-httpservletrequestresponse--context)
  - [2.2 Context 关键方法速查](#22-context-关键方法速查)
  - [2.3 Context 注入方式](#23-context-注入方式)
- [3. Filter / Interceptor 迁移](#3-filter--interceptor-迁移)
  - [3.1 Servlet Filter → Solon Filter](#31-servlet-filter--solon-filter)
  - [3.2 HandlerInterceptor → RouterInterceptor](#32-handlerinterceptor--routerinterceptor)
  - [3.3 执行顺序控制](#33-执行顺序控制)
- [4. 全局异常处理](#4-全局异常处理)
  - [4.1 @ControllerAdvice → Filter](#41-controlleradvice--filter)
  - [4.2 GlobalExceptionFilter 完整实现](#42-globalexceptionfilter-完整实现)
- [5. CORS 迁移](#5-cors-迁移)
  - [5.1 @CrossOrigin / WebMvcConfigurer → solon.web.cors](#51-crossorigin--webmvcconfigurer--solonwebcors)
  - [5.2 配置式 CORS](#52-配置式-cors)
  - [5.3 编程式 CORS（Filter）](#53-编程式-corsfilter)
- [6. 文件上传 / 下载](#6-文件上传--下载)
  - [6.1 MultipartFile → UploadedFile](#61-multipartfile--uploadedfile)
  - [6.2 文件下载（DownloadedFile）](#62-文件下载downloadedfile)
  - [6.3 多文件上传](#63-多文件上传)
- [7. SSE / WebSocket](#7-sse--websocket)
  - [7.1 SseEmitter → Context + SSE](#71-sseemitter--context--sse)
  - [7.2 Spring WebSocket → Solon WebSocket](#72-spring-websocket--solon-websocket)
- [8. 请求参数校验](#8-请求参数校验)
  - [8.1 @Valid + @Validated → @Valid](#81-valid--validated--valid)
  - [8.2 批量参数校验](#82-批量参数校验)
  - [8.3 实体校验](#83-实体校验)
- [9. 会话管理](#9-会话管理)
  - [9.1 HttpSession → SessionState](#91-httpsession--sessionstate)
  - [9.2 会话超时配置](#92-会话超时配置)
- [10. Web 层陷阱与差异清单](#10-web-层陷阱与差异清单)

---

## 1. Controller 迁移

### 1.1 基本控制器结构

#### Before — Spring

```java
package com.example.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// @RestController = @Controller + @ResponseBody
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
import org.noear.solon.core.handle.MethodType;
import java.util.List;

// @Controller 默认返回 JSON，无需 @ResponseBody
@Controller
@Mapping("/api/users")
public class UserController {

    @Inject
    private UserService userService;

    // 列表查询
    @Get
    @Mapping("")
    public List<User> list() {
        return userService.findAll();
    }

    // 单条查询
    @Get
    @Mapping("/{id}")
    public User get(@Path long id) {
        return userService.findById(id);
    }

    // 创建
    @Post
    @Mapping("")
    public User create(@Body User user) {
        return userService.save(user);
    }

    // 更新
    @Put
    @Mapping("/{id}")
    public User update(@Path long id, @Body User user) {
        user.setId(id);
        return userService.update(user);
    }

    // 删除
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
> - `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` → `@Get` + `@Mapping` / `@Post` + `@Mapping` 等（HTTP 方法与路径分离为两个注解）。
> - `@Autowired` → `@Inject`。
> - `@PathVariable` → `@Path`。
> - `@RequestBody` → `@Body`。
> - **`@Mapping` 不支持多路径映射**（如 `@RequestMapping({"/a", "/b"})` 在 Solon 中不可用）。
> - 控制器继承时，Solon 支持基类的 `@Mapping` public 函数。

---

### 1.2 HTTP 方法映射

#### Before — Spring

```java
// Spring 的 HTTP 方法注解是独立的，每个注解自带路径
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
// Solon 将 HTTP 方法和路径分离为两个注解
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

// 也可以用 @Mapping 的 method 属性（等价写法）
@Mapping(value = "/search", method = MethodType.GET)
public List<User> search(@Param String keyword) { ... }
```

> **陷阱**：
> - Spring 的 `@GetMapping`、`@PostMapping` 等是路径 + 方法的复合注解；Solon 将它们拆分为 `@Get` + `@Mapping`（或 `@Mapping(method=MethodType.GET)`）。
> - 推荐使用 `@Get`/`@Post` + `@Mapping` 的组合写法，可读性更好。
> - Solon 也支持 `@Mapping(method = MethodType.POST)` 的单注解写法，效果相同。

---

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
        @RequestParam String product,             // 查询参数
        @RequestParam(defaultValue = "1") int quantity,  // 带默认值
        @RequestHeader("X-Token") String token,   // 请求头
        @RequestBody OrderRequest body,            // 请求体
        @CookieValue("sessionId") String sessionId, // Cookie
        @PathVariable("storeId") Long storeId) {   // 路径变量
    // ...
}
```

#### After — Solon

```java
@Post
@Mapping("/order/{storeId}")
public Order createOrder(
        @Param String product,                     // 查询参数
        @Param(defaultValue = "1") int quantity,    // 带默认值
        @Header("X-Token") String token,            // 请求头
        @Body OrderRequest body,                     // 请求体
        @Cookie("sessionId") String sessionId,       // Cookie
        @Path long storeId) {                        // 路径变量
    // ...
}
```

> **注意**：
> - Solon 的 `@Param`、`@Body` 等注解与 Spring 的对应注解行为**不完全对等**，迁移时需逐一验证参数绑定结果。
> - `@Param` 支持 `defaultValue` 属性，与 Spring `@RequestParam(defaultValue=...)` 对应。
> - Solon 的路径变量类型推荐使用基本类型（`long`、`int`、`String`），避免使用包装类型。

---

### 1.4 内容类型声明

#### Before — Spring

```java
// 通过 produces/consumes 属性声明
@GetMapping(value = "/data", produces = "application/json", consumes = "application/json")
public Data getData() { ... }

@PostMapping(value = "/xml", consumes = "application/xml", produces = "application/xml")
public Data createXml(@RequestBody Data data) { ... }
```

#### After — Solon

```java
// 通过独立的 @Produces / @Consumes 注解声明
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

> **差异说明**：
> - Spring 将 `produces`/`consumes` 作为 `@RequestMapping` 的属性内联声明。
> - Solon 使用独立的 `@Produces` 和 `@Consumes` 注解，关注点分离更清晰。

---

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
        info.put("path", ctx.path());              // 请求路径
        info.put("method", ctx.method());           // 请求方法
        info.put("clientIp", ctx.ip());             // 客户端 IP
        info.put("contentType", ctx.contentType()); // 内容类型
        info.put("userAgent", ctx.header("User-Agent")); // 请求头
        return info;
    }

    @Get
    @Mapping("/redirect")
    public void redirect(Context ctx) {
        ctx.redirect("/new-path");                  // 重定向
    }
}
```

> **关键差异**：
> - Spring 使用 `HttpServletRequest` + `HttpServletResponse` 两个对象；Solon 使用统一的 `Context` 对象封装所有请求/响应操作。
> - `Context` 是 Solon 的核心概念，非 Servlet 架构，但兼容 Servlet 容器。
> - 当使用 Jetty/Undertow 等 Servlet 容器时，`Context` 内部仍然基于 Servlet API 实现。

---

### 2.2 Context 关键方法速查

| 分类 | 方法 | 说明 |
|---|---|---|
| **请求信息** | `ctx.path()` | 请求路径 |
| | `ctx.method()` | HTTP 方法 |
| | `ctx.ip()` / `ctx.realIp()` | 客户端 IP |
| | `ctx.contentType()` | Content-Type |
| | `ctx.url()` | 完整 URL |
| **请求参数** | `ctx.param("name")` | 获取请求参数（等价 `request.getParameter()`） |
| | `ctx.params()` | 获取所有请求参数 |
| | `ctx.header("name")` | 获取请求头 |
| | `ctx.cookie("name")` | 获取 Cookie 值 |
| **请求体** | `ctx.body()` | 获取请求体字符串 |
| | `ctx.bodyAsStream()` | 获取请求体输入流 |
| | `ctx.bodyToBean(User.class)` | 请求体反序列化为 Bean |
| **响应输出** | `ctx.output(obj)` | 输出对象（自动转换） |
| | `ctx.outputAsJson(obj)` | 输出 JSON |
| | `ctx.outputAsHtml(str)` | 输出 HTML |
| | `ctx.outputAsFile(file)` | 输出文件 |
| **响应控制** | `ctx.redirect(url)` | 重定向 |
| | `ctx.forward(path)` | 转发 |
| | `ctx.status(code)` | 设置 HTTP 状态码 |
| | `ctx.contentType("text/html")` | 设置响应 Content-Type |
| | `ctx.headerSet("name", "value")` | 设置响应头 |
| **会话** | `ctx.sessionSet(key, val)` | 设置会话属性 |
| | `ctx.sessionGet(key)` | 获取会话属性 |
| | `ctx.sessionRemove(key)` | 移除会话属性 |
| **请求属性** | `ctx.attrSet(key, val)` | 设置请求属性 |
| | `ctx.attrGet(key)` | 获取请求属性 |

---

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
        return "客户端 IP: " + ctx.ip();
    }

    // 方式二：在 Filter/Interceptor 中通过回调参数获取
    // 见第 3 节 Filter/Interceptor 部分
}
```

> **注意**：`Context` 不支持通过 `@Inject` 在字段上注入（它是请求作用域对象），只能在方法参数中使用。

---

## 3. Filter / Interceptor 迁移

### 3.1 Servlet Filter → Solon Filter

#### Before — Spring

```java
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;

@Component
public class AuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        // 从请求头获取令牌
        String token = request.getHeader("Authorization");

        if (token == null || token.isEmpty()) {
            // 无令牌，返回 401
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未授权\"}");
            return;  // 终止请求链
        }

        // 验证通过，继续执行
        filterChain.doFilter(request, response);
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;
import org.noear.solon.annotation.Component;

@Component
public class AuthFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        // 从请求头获取令牌
        String token = ctx.header("Authorization");

        if (token == null || token.isEmpty()) {
            // 无令牌，返回 401
            ctx.status(401);
            ctx.contentType("application/json");
            ctx.output("{\"code\":401,\"message\":\"未授权\"}");
            return;  // 不调用 chain.doFilter()，终止请求链
        }

        // 验证通过，继续执行
        chain.doFilter(ctx);
    }
}
```

> **关键差异**：
> - Spring 继承 `OncePerRequestFilter`；Solon 实现 `Filter` 接口。
> - Spring 使用 `HttpServletRequest` + `HttpServletResponse`；Solon 使用统一的 `Context`。
> - Spring 调用 `chain.doFilter(req, res)` 继续执行；Solon 调用 `chain.doFilter(ctx)`。
> - 终止请求的方式相同：不调用 chain 的 `doFilter` 方法即终止。
> - Spring 的 `@Component` 自动注册为 Servlet Filter；Solon 的 `@Component` 同样自动注册为框架 Filter。

---

### 3.2 HandlerInterceptor → RouterInterceptor

#### Before — Spring

```java
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class LogInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // 请求处理前
        long startTime = System.currentTimeMillis();
        request.setAttribute("startTime", startTime);
        System.out.println("请求开始: " + request.getRequestURI());
        return true;  // true 继续执行，false 终止
    }

    @Override
    public void postHandle(HttpServletRequest request,
                           HttpServletResponse response,
                           Object handler,
                           ModelAndView modelAndView) {
        // 请求处理后（视图渲染前）
        System.out.println("请求处理完成");
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // 请求完全完成后（用于资源清理）
        long startTime = (long) request.getAttribute("startTime");
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("请求耗时: " + duration + "ms");
    }
}

// 注册拦截器
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private LogInterceptor logInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(logInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/public/**");
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.handle.RouterInterceptor;
import org.noear.solon.annotation.Component;

@Component
public class LogRouterInterceptor implements RouterInterceptor {

    @Override
    public void doIntercept(Context ctx, Handler mainHandler, RouterInterceptorChain chain) throws Throwable {
        // 请求处理前（等价于 preHandle）
        long startTime = System.currentTimeMillis();
        ctx.attrSet("startTime", startTime);
        System.out.println("请求开始: " + ctx.path());

        // 执行后续拦截器和目标 handler
        chain.doIntercept(ctx, mainHandler);

        // 请求处理完成后（等价于 afterCompletion）
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("请求耗时: " + duration + "ms");
    }
}
```

> **差异说明**：
> - Spring 的 `HandlerInterceptor` 有 `preHandle`、`postHandle`、`afterCompletion` 三个方法；Solon 的 `RouterInterceptor` 通过 `chain.doIntercept()` 的前后位置统一处理。
> - Solon 不需要额外的 `WebMvcConfigurer` 配置类，`@Component` 自动注册。
> - 路径匹配通过 Solon 的路由机制自动处理，或可通过编程方式限定。

---

### 3.3 执行顺序控制

#### Before — Spring

```java
// Spring 通过 WebMvcConfigurer 的注册顺序决定执行顺序
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor())    // 第 1 个
                .addPathPatterns("/api/**");
        registry.addInterceptor(new LogInterceptor())     // 第 2 个
                .addPathPatterns("/api/**");
    }
}
```

#### After — Solon

```java
// Solon 通过 @Component(index=N) 控制执行顺序，index 越小越先执行
@Component(index = 1)  // 先执行认证
public class AuthFilter implements Filter { ... }

@Component(index = 2)  // 后执行日志
public class LogFilter implements Filter { ... }
```

> **陷阱**：
> - Solon 的 `@Component(index=N)` 中，**index 越小越先执行**。
> - 如果不指定 `index`，默认值为 0，多个 Filter 的执行顺序不确定。
> - 建议为所有 Filter 和 RouterInterceptor 明确指定 `index` 值。

---

## 4. 全局异常处理

### 4.1 @ControllerAdvice → Filter

#### Before — Spring

```java
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public Map<String, Object> handleIllegalArg(IllegalArgumentException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", e.getMessage());
        return result;
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public Map<String, Object> handleException(Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", "服务器内部错误");
        return result;
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;
import org.noear.solon.annotation.Component;

@Component(index = 0)  // 优先级最高，最先执行，包裹所有后续处理
public class GlobalExceptionFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);
        } catch (IllegalArgumentException e) {
            // 参数异常 → 400
            ctx.status(400);
            ctx.outputAsJson("{\"code\":400,\"message\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            // 其他异常 → 500
            ctx.status(500);
            ctx.outputAsJson("{\"code\":500,\"message\":\"服务器内部错误\"}");
            e.printStackTrace();  // 打印异常堆栈（生产环境应使用日志框架）
        }
    }
}
```

> **关键差异**：
> - Spring 使用 `@ControllerAdvice` + `@ExceptionHandler` 声明式异常处理。
> - Solon 通过 Filter 的 try-catch 包裹实现全局异常捕获，**没有 `@ControllerAdvice` 的等价物**。
> - 将 `GlobalExceptionFilter` 的 `index` 设为最小值（如 0），确保它包裹所有后续处理逻辑。
> - 可以在 Filter 内部使用统一的响应格式类（如 `Result<T>`）保证输出一致性。

---

### 4.2 GlobalExceptionFilter 完整实现

以下是一个生产级全局异常处理 Filter 的推荐实现：

```java
@Component(index = 0)
public class GlobalExceptionFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);

            // 处理控制器抛出但已被框架捕获的异常
            if (ctx.errors > 0) {
                ctx.outputAsJson(buildError(500, "服务器内部错误"));
            }
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.outputAsJson(buildError(400, e.getMessage()));
        } catch (NullPointerException e) {
            ctx.status(500);
            ctx.outputAsJson(buildError(500, "空指针异常"));
        } catch (RuntimeException e) {
            ctx.status(500);
            ctx.outputAsJson(buildError(500, e.getMessage()));
        } catch (Throwable e) {
            ctx.status(500);
            ctx.outputAsJson(buildError(500, "服务器内部错误"));
        }
    }

    private String buildError(int code, String message) {
        return "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
    }
}
```

---

## 5. CORS 迁移

### 5.1 @CrossOrigin / WebMvcConfigurer → solon.web.cors

#### Before — Spring（注解方式）

```java
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000",
             methods = {RequestMethod.GET, RequestMethod.POST},
             maxAge = 3600)
public class ApiController {
    // 该控制器下所有接口都启用 CORS
}
```

#### Before — Spring（全局配置方式）

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

---

### 5.2 配置式 CORS

#### After — Solon（推荐：配置文件方式）

在 `app.yml` 中添加 CORS 配置：

```yaml
solon:
  app:
    name: demo-app
  cors:
    enable: true                          # 启用 CORS
    allowedOrigins: "http://localhost:3000"  # 允许的来源
    allowedMethods: "GET,POST,PUT,DELETE"  # 允许的方法
    allowedHeaders: "*"                   # 允许的请求头
    allowCredentials: true                # 允许携带凭证
    maxAge: 3600                          # 预检请求缓存时间（秒）
```

> **说明**：引入 `solon-web-cors` 插件后，通过配置文件即可启用 CORS，无需编写任何 Java 代码。

对应 Maven 依赖：

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-web-cors</artifactId>
</dependency>
```

---

### 5.3 编程式 CORS（Filter）

#### After — Solon（Filter 方式，适合动态场景）

```java
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;
import org.noear.solon.annotation.Component;

@Component(index = -1)  // 最高优先级，确保 CORS 在最外层
public class CorsFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        // 设置 CORS 响应头
        ctx.headerSet("Access-Control-Allow-Origin", "http://localhost:3000");
        ctx.headerSet("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ctx.headerSet("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ctx.headerSet("Access-Control-Allow-Credentials", "true");
        ctx.headerSet("Access-Control-Max-Age", "3600");

        // 处理预检请求（OPTIONS）
        if ("OPTIONS".equalsIgnoreCase(ctx.method())) {
            ctx.status(200);
            return;
        }

        chain.doFilter(ctx);
    }
}
```

> **陷阱**：
> - CORS Filter 必须在所有其他 Filter 之前执行，否则预检请求（OPTIONS）可能被后续 Filter 拦截。
> - 生产环境中 `allowedOrigins` 不应设置为 `*`，应明确指定允许的域名。
> - 推荐使用配置文件方式管理 CORS，避免硬编码。

---

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
        Map<String, Object> result = new HashMap<>();

        // 获取原始文件名
        String originalName = file.getOriginalFilename();

        // 获取文件大小
        long size = file.getSize();

        // 获取 Content-Type
        String contentType = file.getContentType();

        // 保存文件
        try {
            String savePath = "/uploads/" + originalName;
            file.transferTo(new File(savePath));

            result.put("success", true);
            result.put("name", originalName);
            result.put("size", size);
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return result;
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.handle.Context;
import org.noear.solon.annotation.*;

@Controller
@Mapping("/api/files")
public class FileController {

    @Post
    @Mapping("/upload")
    public Map<String, Object> upload(UploadedFile file) {
        Map<String, Object> result = new HashMap<>();

        if (file == null) {
            result.put("success", false);
            result.put("message", "未选择文件");
            return result;
        }

        // 获取原始文件名
        String originalName = file.getName();

        // 获取文件大小
        long size = file.getContentSize();

        // 获取 Content-Type
        String contentType = file.getContentType();

        // 保存文件
        try {
            String savePath = "/uploads/" + originalName;
            file.transferTo(new File(savePath));

            result.put("success", true);
            result.put("name", originalName);
            result.put("size", size);
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }

        return result;
    }
}
```

> **关键差异**：
> - `MultipartFile` → `UploadedFile`。
> - `getOriginalFilename()` → `getName()`。
> - `getSize()` → `getContentSize()`。
> - `transferTo()` 方法名相同，行为一致。
> - Solon 的 `UploadedFile` 不需要 `@RequestParam` 注解，框架会自动绑定。
> - 需要引入 `solon-web` 依赖，文件上传功能已内置。

---

### 6.2 文件下载（DownloadedFile）

Spring 没有专门的文件下载对象，通常使用 `ResponseEntity` 或直接操作 `HttpServletResponse`。

#### Before — Spring

```java
@GetMapping("/download/{filename}")
public ResponseEntity<Resource> download(@PathVariable String filename) throws IOException {
    File file = new File("/uploads/" + filename);
    if (!file.exists()) {
        return ResponseEntity.notFound().build();
    }

    Resource resource = new FileSystemResource(file);
    return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(resource);
}
```

#### After — Solon

```java
// Solon 提供 DownloadedFile 封装文件下载
@Get
@Mapping("/download/{filename}")
public DownloadedFile download(@Path String filename) {
    File file = new File("/uploads/" + filename);
    if (!file.exists()) {
        return null;  // 框架会返回 404
    }

    // DownloadedFile 会自动设置 Content-Disposition 等响应头
    return new DownloadedFile(file)
            .asAttachment(true)              // 作为附件下载
            .contentType("application/octet-stream");
}

// 或者通过 Context 直接输出文件
@Get
@Mapping("/download2/{filename}")
public void download2(@Path String filename, Context ctx) {
    File file = new File("/uploads/" + filename);
    if (!file.exists()) {
        ctx.status(404);
        return;
    }
    ctx.outputAsFile(file);
}
```

> **注意**：`DownloadedFile` 是 Solon 独有的文件下载封装类，Spring 中没有等价物。它简化了文件下载的响应头设置和流处理。

---

### 6.3 多文件上传

#### Before — Spring

```java
@PostMapping("/batch-upload")
public Map<String, Object> batchUpload(@RequestParam("files") MultipartFile[] files) {
    int count = 0;
    for (MultipartFile file : files) {
        // 逐个处理文件
        String name = file.getOriginalFilename();
        // ... 保存逻辑
        count++;
    }
    return Map.of("count", count);
}
```

#### After — Solon

```java
@Post
@Mapping("/batch-upload")
public Map<String, Object> batchUpload(Context ctx) {
    // 通过 Context 获取所有上传文件
    List<UploadedFile> files = ctx.files("files");
    int count = 0;
    for (UploadedFile file : files) {
        String name = file.getName();
        // ... 保存逻辑
        count++;
    }
    return Map.of("count", count);
}
```

> **注意**：多文件上传时，Solon 通过 `ctx.files("fieldName")` 获取文件列表，而非通过方法参数绑定。

---

## 7. SSE / WebSocket

### 7.1 SseEmitter → Context + SSE

#### Before — Spring

```java
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/sse")
public class SseController {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping("/connect")
    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);  // 无超时
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));

        return emitter;
    }

    @PostMapping("/send")
    public void sendMessage(@RequestBody String message) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .data(message)
                        .name("message"));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.Context;
import org.noear.solon.annotation.*;

@Controller
@Mapping("/api/sse")
public class SseController {

    // 用于保存活跃的 SSE 连接
    private final List<Context> clients = new CopyOnWriteArrayList<>();

    @Get
    @Mapping("/connect")
    public void connect(Context ctx) throws Throwable {
        // 设置 SSE 必需的响应头
        ctx.contentType("text/event-stream");
        ctx.headerSet("Cache-Control", "no-cache");
        ctx.headerSet("Connection", "keep-alive");

        clients.add(ctx);

        // 发送初始消息
        ctx.output("event: connected\ndata: {\"status\":\"ok\"}\n\n");
        ctx.flush();

        // 注意：SSE 连接通常是长连接，Solon 会保持连接直到客户端断开
    }

    @Post
    @Mapping("/send")
    public void sendMessage(@Body String message) {
        for (Context client : clients) {
            try {
                // SSE 格式：event: xxx\ndata: xxx\n\n
                client.output("event: message\ndata: " + message + "\n\n");
                client.flush();
            } catch (Exception e) {
                clients.remove(client);
            }
        }
    }
}
```

> **关键差异**：
> - Spring 使用 `SseEmitter` 对象管理 SSE 连接；Solon 直接使用 `Context` 输出 SSE 格式数据。
> - SSE 数据格式遵循标准规范：`event: xxx\ndata: xxx\n\n`（注意结尾是两个换行符）。
> - Solon 没有 `SseEmitter` 的等价物，但通过 `Context` 实现同样简洁。

---

### 7.2 Spring WebSocket → Solon WebSocket

#### Before — Spring

```java
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 广播消息给所有连接
        for (WebSocketSession s : sessions) {
            s.sendMessage(new TextMessage(message.getPayload()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }
}

// 需要额外的配置类注册 WebSocket 端点
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Autowired
    private ChatWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat");
    }
}
```

#### After — Solon

```java
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.WebSocketSession;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

// @ServerEndpoint 将类标记为 WebSocket 服务端点
@ServerEndpoint("/ws/chat")
@Component
public class ChatWebSocketEndpoint {

    // 保存所有活跃的 WebSocket 会话
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Inject
    private MessageService messageService;

    // 连接建立时触发
    @OnOpen
    public void onOpen(WebSocketSession session) {
        sessions.add(session);
        System.out.println("新连接: " + session.getSessionId());
    }

    // 接收到文本消息时触发
    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        // 广播消息给所有连接
        for (WebSocketSession s : sessions) {
            s.send(message);
        }
    }

    // 连接关闭时触发
    @OnClose
    public void onClose(WebSocketSession session) {
        sessions.remove(session);
        System.out.println("连接关闭: " + session.getSessionId());
    }

    // 发生异常时触发
    @OnError
    public void onError(WebSocketSession session, Throwable error) {
        System.out.println("WebSocket 异常: " + error.getMessage());
        sessions.remove(session);
    }
}
```

> **关键差异**：
> - Spring 使用 `TextWebSocketHandler` + `WebSocketConfigurer` 注册；Solon 使用 `@ServerEndpoint` 注解声明式定义。
> - Spring 需要额外的 `@Configuration` 类注册端点；Solon 的 `@ServerEndpoint` + `@Component` 一步完成。
> - Solon 使用 `@OnOpen`、`@OnMessage`、`@OnClose`、`@OnError` 生命周期注解，与 Java EE WebSocket API 风格一致。
> - 需要引入 `solon-server-websocket` 依赖。

---

## 8. 请求参数校验

### 8.1 @Valid + @Validated → @Valid

#### Before — Spring

```java
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    // 使用 @Validated 或 @Valid 触发校验
    // BindingResult 必须紧跟在校验参数之后
    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody UserDTO user,
                                      BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            // 手动处理校验错误
            String errorMessage = bindingResult.getFieldError().getDefaultMessage();
            return Map.of("code", 400, "message", errorMessage);
        }

        // 业务逻辑
        return Map.of("code", 200, "data", user);
    }
}

// DTO 类
public class UserDTO {
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(min = 6, max = 20, message = "密码长度 6-20 位")
    private String password;

    // getter/setter ...
}
```

#### After — Solon

```java
import org.noear.solon.annotation.*;
import org.noear.solon.validation.annotation.Valid;
import org.noear.solon.validation.annotation.NotNull;
import org.noear.solon.validation.annotation.Pattern;
import org.noear.solon.validation.annotation.Size;
import org.noear.solon.validation.annotation.Email;

@Valid    // 类级 @Valid 开启当前控制器的参数校验功能
@Controller
@Mapping("/api/users")
public class UserController {

    @Post
    @Mapping("")
    public Map<String, Object> create(@Body UserDTO user) {
        // 校验不通过时，Solon 会自动返回 400 错误
        // 不需要手动检查 BindingResult
        return Map.of("code", 200, "data", user);
    }
}

// DTO 类（使用 javax.validation 或 Solon 内置校验注解）
public class UserDTO {
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(min = 6, max = 20, message = "密码长度 6-20 位")
    private String password;

    // getter/setter ...
}
```

> **关键差异**：
> - Spring 使用 `@Validated` 或 `@Valid` + `BindingResult` 手动处理校验结果；Solon 在类上标注 `@Valid` 即自动触发校验，校验失败自动返回 400。
> - Solon **没有 `BindingResult`**，校验失败由框架自动处理。
> - 需要引入 `solon-validation` 依赖。

---

### 8.2 批量参数校验

Solon 支持在方法参数上直接使用校验注解，称为"批量参数校验"，强调"可见性"。

#### Before — Spring

```java
// Spring 不支持在方法参数上直接加校验注解
// 必须封装为 DTO 或使用 @Validated 在类级别
@PostMapping("/register")
public String register(@RequestParam @NotBlank String name,
                       @RequestParam @Email String email) {
    // Spring 的 @NotBlank、@Email 在 @RequestParam 上不生效
    // 需要在类上添加 @Validated 才能触发
    return "ok";
}
```

#### After — Solon

```java
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
        // 校验注解直接在方法参数上，可见性更好
        // 校验不通过时框架自动返回 400 错误
        return "ok";
    }
}
```

> **注意**：
> - Solon 的"批量参数校验"允许在方法参数上直接使用 `@NotNull`、`@NotBlank`、`@Pattern`、`@Email`、`@Size` 等注解。
> - 控制器类上必须标注 `@Valid` 才能激活校验。
> - 这种风格比 Spring 的 DTO 封装更直观，参数约束"看得见"。

---

### 8.3 实体校验

#### Before — Spring

```java
@PostMapping("/user/create")
public String createUser(@Validated @RequestBody UserDTO user) {
    return "ok";
}
```

#### After — Solon

```java
@Valid
@Controller
@Mapping("/api")
public class UserController {

    @Post
    @Mapping("/user/create")
    public String createUser(@Validated UserDTO user) {
        // @Validated 在 Solon 中用于触发实体内部的校验注解
        return "ok";
    }
}
```

---

## 9. 会话管理

### 9.1 HttpSession → SessionState

#### Before — Spring

```java
import javax.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username,
                                     @RequestParam String password,
                                     HttpSession session) {
        // 模拟认证
        if ("admin".equals(username) && "123456".equals(password)) {
            // 设置会话属性
            session.setAttribute("userId", 1001L);
            session.setAttribute("username", username);
            return Map.of("code", 200, "message", "登录成功");
        }
        return Map.of("code", 401, "message", "用户名或密码错误");
    }

    @GetMapping("/info")
    public Map<String, Object> getInfo(HttpSession session) {
        // 获取会话属性
        Long userId = (Long) session.getAttribute("userId");
        String username = (String) session.getAttribute("username");

        if (userId == null) {
            return Map.of("code", 401, "message", "未登录");
        }
        return Map.of("code", 200, "userId", userId, "username", username);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        // 使会话失效
        session.invalidate();
        return Map.of("code", 200, "message", "已退出");
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.Context;
import org.noear.solon.annotation.*;

@Controller
@Mapping("/api/session")
public class SessionController {

    @Post
    @Mapping("/login")
    public Map<String, Object> login(@Param String username,
                                     @Param String password,
                                     Context ctx) {
        // 模拟认证
        if ("admin".equals(username) && "123456".equals(password)) {
            // 通过 Context 设置会话属性
            ctx.sessionSet("userId", 1001L);
            ctx.sessionSet("username", username);
            return Map.of("code", 200, "message", "登录成功");
        }
        return Map.of("code", 401, "message", "用户名或密码错误");
    }

    @Get
    @Mapping("/info")
    public Map<String, Object> getInfo(Context ctx) {
        // 通过 Context 获取会话属性
        Long userId = ctx.sessionGet("userId", Long.class);
        String username = ctx.sessionGet("username", String.class);

        if (userId == null) {
            return Map.of("code", 401, "message", "未登录");
        }
        return Map.of("code", 200, "userId", userId, "username", username);
    }

    @Post
    @Mapping("/logout")
    public Map<String, Object> logout(Context ctx) {
        // 清除会话
        ctx.sessionClear();
        return Map.of("code", 200, "message", "已退出");
    }
}
```

> **关键差异**：
> - `HttpSession` → 通过 `Context` 的 `sessionSet` / `sessionGet` / `sessionClear` 方法管理。
> - Solon 没有独立的 `HttpSession` 对象，会话管理是 `Context` 的一部分。
> - `sessionSet(key, val)` 等价于 `session.setAttribute(key, val)`。
> - `sessionGet(key)` 等价于 `session.getAttribute(key)`，支持泛型转换：`sessionGet("userId", Long.class)`。
> - `sessionClear()` 等价于 `session.invalidate()`。
> - `sessionRemove(key)` 等价于 `session.removeAttribute(key)`。

---

### 9.2 会话超时配置

#### Before — Spring（application.yml）

```yaml
server:
  servlet:
    session:
      timeout: 30m  # 会话超时时间 30 分钟
```

#### After — Solon（app.yml）

```yaml
server:
  session:
    timeout: 1800  # 会话超时时间（秒），1800 秒 = 30 分钟
```

> **注意**：Solon 的会话超时单位是**秒**，Spring Boot 的 `timeout` 单位可以是分钟（`30m`）。迁移时注意换算。

---

## 10. Web 层陷阱与差异清单

### 陷阱速查表

| 编号 | 陷阱描述 | 严重程度 | 详细说明 |
|---|---|---|---|
| 1 | **@Controller 默认返回 JSON** | 中 | Solon 不存在 `@RestController`，`@Controller` 默认即 JSON 输出。如需返回视图需使用 `ModelAndView`。 |
| 2 | **@Mapping 不支持多路径** | 中 | 一个 `@Mapping` 只能映射一个路径模式，不支持 `@RequestMapping({"/a", "/b"})`。需要多路径时须定义多个方法。 |
| 3 | **Context 不支持字段注入** | 低 | `Context` 是请求作用域对象，只能在方法参数中使用，不能通过 `@Inject` 注入到字段。 |
| 4 | **Filter 顺序需显式指定** | 高 | 多个 Filter 的执行顺序通过 `@Component(index=N)` 控制。不指定 index 时执行顺序不确定，可能导致认证/日志等逻辑错乱。 |
| 5 | **没有 @ControllerAdvice** | 高 | 全局异常处理需通过 Filter 的 try-catch 实现，不能使用 `@ControllerAdvice` + `@ExceptionHandler`。 |
| 6 | **没有 BindingResult** | 中 | 参数校验失败由框架自动返回 400 错误，不能通过 `BindingResult` 手动处理校验结果。自定义错误响应需通过 Filter 拦截。 |
| 7 | **CORS 需引入独立插件** | 低 | 需要引入 `solon-web-cors` 或自行实现 Filter。不像 Spring 默认集成。 |
| 8 | **文件上传参数无需注解** | 低 | `UploadedFile` 参数不需要 `@RequestParam` 注解，框架自动绑定。多文件通过 `ctx.files()` 获取。 |
| 9 | **会话超时单位不同** | 中 | Solon 的会话超时单位是**秒**，Spring Boot 支持 `30m` 这样的字符串格式。迁移时注意换算。 |
| 10 | **控制器继承行为** | 低 | Solon 支持基类的 `@Mapping` public 函数继承。但注意被继承的方法必须是 public，protected 方法不会被路由。 |
| 11 | **SSE 无专用对象** | 低 | Solon 没有 `SseEmitter`，通过 `Context` 直接输出 SSE 格式数据。需手动设置 `Content-Type: text/event-stream`。 |
| 12 | **WebSocket 端点声明方式不同** | 中 | Spring 使用 `TextWebSocketHandler` + 配置类注册；Solon 使用 `@ServerEndpoint` 注解 + `@OnOpen/@OnMessage/@OnClose/@OnError` 生命周期。 |

### Web 层迁移检查清单

- [ ] `@RestController` → `@Controller`（全局替换，Solon 默认返回 JSON）
- [ ] `@RequestMapping` → `@Mapping`
- [ ] `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` → `@Get`/`@Post`/`@Put`/`@Delete` + `@Mapping`
- [ ] `@RequestParam` → `@Param`
- [ ] `@RequestBody` → `@Body`
- [ ] `@PathVariable` → `@Path`
- [ ] `@RequestHeader` → `@Header`
- [ ] `@CookieValue` → `@Cookie`
- [ ] `HttpServletRequest` + `HttpServletResponse` → `Context`
- [ ] `HttpSession` → `Context.sessionSet/sessionGet/sessionClear`
- [ ] `OncePerRequestFilter` → `Filter` 接口
- [ ] `HandlerInterceptor` → `RouterInterceptor`
- [ ] `@ControllerAdvice` + `@ExceptionHandler` → `Filter` + try-catch
- [ ] `@CrossOrigin` / `WebMvcConfigurer` → `solon-web-cors` 配置或 Filter
- [ ] `MultipartFile` → `UploadedFile`
- [ ] `SseEmitter` → `Context` + SSE 格式输出
- [ ] `TextWebSocketHandler` → `@ServerEndpoint` + `@OnOpen/@OnMessage/@OnClose/@OnError`
- [ ] `@Valid` + `BindingResult` → `@Valid`（类级）+ 框架自动处理
- [ ] `produces`/`consumes` 属性 → `@Produces`/`@Consumes` 注解
- [ ] 多路径映射 → 拆分为多个方法或多个路由
- [ ] 所有 Filter 显式指定 `@Component(index=N)`
