# Security — 认证 / 鉴权 / CORS / 加密

> 适用场景：跨域处理、用户认证、路径鉴权、角色权限、配置加密、请求头安全。
>
> 目标版本：4.0.3。参数校验见 **`validation.md`**。

## CORS — 跨域处理

Dependency: `solon-web-cors`（已包含在 `solon-web` 中）

### 方式一：注解在控制器或方法上

```java
@CrossOrigin(origins = "*")
@Controller
public class DemoController {
    @Mapping("/hello")
    public String hello() { return "hello"; }
}
```

### 方式二：注解在基类

```java
@CrossOrigin(origins = "*")
public class BaseController {}

@Controller
public class DemoController extends BaseController {
    @Mapping("/hello")
    public String hello() { return "hello"; }
}
```

### 方式三：全局配置

```java
Solon.start(App.class, args, app -> {
    // 全局处理（过滤器模式，-1 优先级更高）
    app.filter(-1, new CrossFilter().allowedOrigins("*"));

    // 某段路径
    app.filter(new CrossFilter().pathPatterns("/user/**").allowedOrigins("*"));

    // 路由拦截器模式
    app.routerInterceptor(-1, new CrossInterceptor().allowedOrigins("*"));
});
```

---

## Auth — 用户认证与鉴权

Dependency: `solon-security-auth`

核心概念：通过 `AuthAdapter` 统一配置认证规则，通过 `AuthProcessor` 接口适配具体业务逻辑。

### 第 1 步：构建认证适配器

```java
@Configuration
public class Config {
    @Bean(index = 0)
    public AuthAdapter init() {
        return new AuthAdapter()
                .loginUrl("/login")
                .addRule(r -> r.include("**").verifyIp()
                        .failure((c, t) -> c.output("你的IP不在白名单")))
                .addRule(b -> b.exclude("/login**").exclude("/run/**").verifyPath())
                .processor(new AuthProcessorImpl())
                .failure((ctx, rst) -> ctx.render(rst));
    }
}
```

规则配置说明：
- `include(path)` — 规则包含的路径范围
- `exclude(path)` — 规则排除的路径范围
- `failure(..)` — 规则失败后的处理
- `verifyIp()` / `verifyPath()` / `verifyLogined()` — 验证方案

### 第 2 步：认证异常处理

```java
@Component
public class DemoFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);
        } catch (AuthException e) {
            AuthStatus status = e.getStatus();
            ctx.render(Result.failure(status.code, status.message));
        }
    }
}
```

### 第 3 步：实现认证处理器

```java
public class AuthProcessorImpl implements AuthProcessor {
    @Override
    public boolean verifyIp(String ip) {
        // 验证 IP 是否有权访问
        return true;
    }

    @Override
    public boolean verifyLogined() {
        // 验证用户是否已登录
        return getSubjectId() > 0;
    }

    @Override
    public boolean verifyPath(String path, String method) {
        // 验证路径，用户是否可访问
        return true;
    }

    @Override
    public boolean verifyPermissions(String[] permissions, Logical logical) {
        // 验证特定权限（verifyLogined 为 true 时触发）
        return true;
    }

    @Override
    public boolean verifyRoles(String[] roles, Logical logical) {
        // 验证特定角色（verifyLogined 为 true 时触发）
        return true;
    }
}
```

### 注解控制（特定权限/角色）

```java
@Controller
@Mapping("/demo/agroup")
public class AgroupController {
    @Mapping("")
    public void home() { /* 首页 */ }

    @AuthPermissions("agroup:edit")
    @Mapping("edit/{id}")
    public void edit(int id) { /* 需要编辑权限 */ }

    @AuthRoles("admin")
    @Mapping("edit/{id}/ajax/save")
    public void save(int id) { /* 需要管理员角色 */ }
}
```

### 模板中使用

```html
<@authPermissions name="user:del">我有 user:del 权限</@authPermissions>
<@authRoles name="admin">我有 admin 角色</@authRoles>
```

### 组合使用建议

- **规则控制**：在 AuthAdapter 中配置所有需要登录的地址（宏观控制）
- **注解控制**：在特定方法上控制权限和角色（细节把握）

---

## Vault — 配置加密

Dependency: `solon-security-vault`

用于敏感配置项的加密存储（如数据库连接信息），让敏感信息不直接暴露在配置文件中。

### 配置密码

```yaml
solon.vault:
  password: "liylU9PhDq63tk1C"  # 默认算法要求 16 位，建议包含大小写和数字
```

密码也可通过启动参数传入（更安全）：

```bash
java -Dsolon.vault.password=xxx -jar demo.jar
```

### 生成密文

```java
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);
        System.out.println(VaultUtils.encrypt("root"));
    }
}
```

### 使用密文配置

```yaml
solon.vault:
  password: "liylU9PhDq63tk1C"

test.db1:
  url: "jdbc:mysql://localhost:3306/test"
  username: "ENC(xo1zJjGXUouQ/CZac55HZA==)"
  password: "ENC(XgRqh3C00JmkjsPi4mPySA==)"
```

### 注解注入（@VaultInject）

```java
@Configuration
public class TestConfig {
    @Bean("db2")
    private DataSource db2(@VaultInject("${test.db1}") HikariDataSource ds) {
        return ds;
    }
}
```

### 手动解密

```java
// 解密一块配置
Props props = Solon.cfg().getProp("test.db1");
VaultUtils.guard(props);
HikariDataSource ds = props.getBean(HikariDataSource.class);

// 解密单个配置
String name = VaultUtils.guard(Solon.cfg().get("test.demo.name"));
```

### 定制加密算法

```java
@Component
public class VaultCoderImpl implements VaultCoder {
    private final String password;

    public VaultCoderImpl() {
        this.password = Solon.cfg().get("solon.vault.password");
    }

    @Override
    public String encrypt(String str) throws Exception {
        // 自定义加密实现
        return null;
    }

    @Override
    public String decrypt(String str) throws Exception {
        // 自定义解密实现
        return null;
    }
}
```

---

## Web 安全 — 请求头安全

Dependency: `solon-security-web`（v3.1.1 后支持）

提供 HTTP 请求头安全防护能力。`SecurityFilter` 是一个 web 过滤器，可组织多种 Handler 进行安全处理。

### 安全处理器列表

| Handler | 说明 |
|---------|------|
| `CacheControlHeadersHandler` | `Cache-Control` 头处理器 |
| `HstsHeaderHandler` | `Strict-Transport-Security` 头处理器 |
| `XContentTypeOptionsHeaderHandler` | `X-Content-Type-Options` 头处理器 |
| `XFrameOptionsHeaderHandler` | `X-Frame-Options` 头处理器 |
| `XXssProtectionHeaderHandler` | `X-XSS-Protection` 头处理器 |

### 使用示例

```java
@Configuration
public class DemoFilter {
    @Bean(index = -99)
    public SecurityFilter securityFilter() {
        return new SecurityFilter(
                new XContentTypeOptionsHeaderHandler(),
                new XXssProtectionHeaderHandler()
        );
    }
}
```

---

## 相关索引

| 主题 | Reference |
|---|---|
| 参数 / 实体校验 | `validation.md` |
| Cloud 熔断 / 锁 | `cloud_ops.md` |
