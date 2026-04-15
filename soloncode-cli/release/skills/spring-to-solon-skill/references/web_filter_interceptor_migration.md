# Filter / Interceptor / 异常处理迁移参考

> Spring Boot → Solon Web Filter、拦截器、全局异常处理及 CORS 迁移指南（目标版本：Solon 3.10.x）

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
        String token = request.getHeader("Authorization");

        if (token == null || token.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未授权\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
```

#### After — Solon

```java
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

@Component
public class AuthFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String token = ctx.header("Authorization");

        if (token == null || token.isEmpty()) {
            ctx.status(401);
            ctx.contentType("application/json");
            ctx.output("{\"code\":401,\"message\":\"未授权\"}");
            return;  // 不调用 chain.doFilter()，终止请求链
        }

        chain.doFilter(ctx);
    }
}
```

> **关键差异**：
> - Spring 继承 `OncePerRequestFilter`；Solon 实现 `Filter` 接口。
> - Spring 使用 `HttpServletRequest` + `HttpServletResponse`；Solon 使用统一的 `Context`。
> - Spring 调用 `chain.doFilter(req, res)` 继续执行；Solon 调用 `chain.doFilter(ctx)`。
> - 终止请求的方式相同：不调用 chain 的 `doFilter` 方法即终止。

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
        long startTime = System.currentTimeMillis();
        request.setAttribute("startTime", startTime);
        System.out.println("请求开始: " + request.getRequestURI());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request,
                           HttpServletResponse response,
                           Object handler,
                           ModelAndView modelAndView) {
        System.out.println("请求处理完成");
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
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

### 3.3 执行顺序控制

#### Before — Spring

```java
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
> - `@Component(index=N)` 中 **index 越小越先执行**。
> - 如果不指定 `index`，默认值为 0，多个 Filter 的执行顺序不确定。
> - 建议为所有 Filter 和 RouterInterceptor 明确指定 `index` 值。

## 4. 全局异常处理

### 4.1 @ControllerAdvice → Filter

#### Before — Spring

```java
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
@Component(index = 0)  // 优先级最高，包裹所有后续处理
public class GlobalExceptionFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);

            // 处理控制器抛出但已被框架捕获的异常
            if (ctx.errors != null) {
                ctx.contentType("application/json");
                ctx.output(buildError(500, "服务器内部错误"));
            }
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.contentType("application/json");
            ctx.output(buildError(400, e.getMessage()));
        } catch (NullPointerException e) {
            ctx.status(500);
            ctx.contentType("application/json");
            ctx.output(buildError(500, "空指针异常"));
        } catch (RuntimeException e) {
            ctx.status(500);
            ctx.contentType("application/json");
            ctx.output(buildError(500, e.getMessage()));
        } catch (Throwable e) {
            ctx.status(500);
            ctx.contentType("application/json");
            ctx.output(buildError(500, "服务器内部错误"));
        }
    }

    private String buildError(int code, String message) {
        return "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
    }
}
```

> **关键差异**：
> - Spring 使用 `@ControllerAdvice` + `@ExceptionHandler` 声明式异常处理。
> - Solon 通过 Filter 的 try-catch 包裹实现全局异常捕获，**没有 `@ControllerAdvice` 的等价物**。
> - 将 `GlobalExceptionFilter` 的 `index` 设为最小值（如 0），确保它包裹所有后续处理逻辑。
> - 可以在 Filter 内部使用统一的响应格式类（如 `Result<T>`）保证输出一致性。

## 5. CORS 迁移

### 5.1 @CrossOrigin / WebMvcConfigurer → @CrossOrigin

#### Before — Spring（注解方式）

```java
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000",
             methods = {RequestMethod.GET, RequestMethod.POST},
             maxAge = 3600)
public class ApiController {
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

### 5.2 注解式 CORS（推荐）

引入 `solon-web-cors` 插件后，可直接使用 `@CrossOrigin` 注解：

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-web-cors</artifactId>
</dependency>
```

```java
@Controller
@Mapping("/api")
@CrossOrigin(origins = "http://localhost:3000",
             methods = {"GET", "POST", "PUT", "DELETE"},
             headers = "*",
             credentials = true,
             maxAge = 3600)
public class ApiController {
}
```

> **说明**：`solon-web-cors` **不提供 YAML 配置式支持**。CORS 配置方式有三种：
> 1. `@CrossOrigin` 注解（推荐）
> 2. `CrossFilter` 编程式（见 5.3 节）
> 3. `CrossInterceptor` 路由拦截器

### 5.3 编程式 CORS（CrossFilter）

适合动态场景，需手动处理预检请求：

```java
@Component(index = -1)  // 最高优先级，确保 CORS 在最外层
public class CorsFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        ctx.headerSet("Access-Control-Allow-Origin", "http://localhost:3000");
        ctx.headerSet("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ctx.headerSet("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ctx.headerSet("Access-Control-Allow-Credentials", "true");
        ctx.headerSet("Access-Control-Max-Age", "3600");

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
> - 推荐使用 `@CrossOrigin` 注解方式管理 CORS，避免硬编码。
