# 安全鉴权迁移参考

> Spring Security → Solon Security 迁移指南（目标版本：Solon 4.0.x）
>
> Solon 安全模型与 Spring Security **不是一一对应**。不要试图复刻完整 FilterChainProxy / SecurityFilterChain；按「登录态 + 路径规则 + 权限/角色注解」重写。
>
> 更完整的 Solon 原生用法见 `solon-development-skill` 的 `references/security.md`。

---

## 1. 依赖替换

```xml
<!-- Spring Before -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- Solon After：认证鉴权 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-security-auth</artifactId>
</dependency>

<!-- 可选：参数校验（对应 spring-boot-starter-validation；与鉴权分开） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-security-validation</artifactId>
</dependency>

<!-- 可选：配置加密 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-security-vault</artifactId>
</dependency>
```

| Spring | Solon | 说明 |
|--------|-------|------|
| spring-boot-starter-security | solon-security-auth | 路径鉴权 / 登录态 / 权限角色 |
| SecurityFilterChain / WebSecurityConfigurerAdapter | `AuthAdapter` + `AuthProcessor` | 配置模型完全不同 |
| `@PreAuthorize` / `@Secured` | `@AuthPermissions` / `@AuthRoles` | 方法级控制 |
| CORS via Security | `solon-web-cors` / `@CrossOrigin` / `CrossFilter` | 常与鉴权分开配置 |
| jasypt 等配置加密 | solon-security-vault | `ENC(...)` + `VaultUtils` |

---

## 2. 总体思路对照

| 维度 | Spring Security | Solon |
|------|-----------------|-------|
| 配置入口 | `SecurityFilterChain` Bean | `@Bean AuthAdapter` |
| 业务适配 | `UserDetailsService` / `AuthenticationProvider` | 实现 `AuthProcessor` |
| 路径放行 | `requestMatchers(...).permitAll()` | `AuthAdapter` 规则 `exclude(...)` |
| 登录态 | SecurityContext | 自定义 Session / Token（在 Processor 中判断） |
| 方法鉴权 | `@PreAuthorize("hasRole('ADMIN')")` | `@AuthRoles("admin")` 等 |
| 失败处理 | `AuthenticationEntryPoint` / `AccessDeniedHandler` | `AuthAdapter.failure(...)` + 可选 `Filter` 捕 `AuthException` |

---

## 3. 最小迁移闭环

### 3.1 Spring Before（示意）

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/public/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login"));
        return http.build();
    }
}
```

### 3.2 Solon After：AuthAdapter + AuthProcessor

```java
@Configuration
public class AuthConfig {
    @Bean(index = 0)
    public AuthAdapter authAdapter() {
        return new AuthAdapter()
                .loginUrl("/login")
                // 除登录与公开路径外，校验是否已登录 / 路径是否允许
                .addRule(r -> r.exclude("/login**")
                        .exclude("/public/**")
                        .verifyPath())
                .processor(new AuthProcessorImpl())
                .failure((ctx, rst) -> ctx.render(rst));
    }
}

public class AuthProcessorImpl implements AuthProcessor {
    @Override
    public boolean verifyIp(String ip) {
        return true; // 按需实现 IP 白名单
    }

    @Override
    public boolean verifyLogined() {
        // 从 Session / Token 判断是否登录（替换 SecurityContext）
        return Context.current().session("userId") != null;
    }

    @Override
    public boolean verifyPath(String path, String method) {
        // 已登录用户的路径访问策略；也可直接 return true 后交给注解控制
        return verifyLogined();
    }

    @Override
    public boolean verifyPermissions(String[] permissions, Logical logical) {
        // 读取当前用户权限集后比对
        return true;
    }

    @Override
    public boolean verifyRoles(String[] roles, Logical logical) {
        return true;
    }
}
```

### 3.3 方法级权限

```java
// Spring Before
@PreAuthorize("hasAuthority('user:edit')")
@PreAuthorize("hasRole('ADMIN')")

// Solon After
@AuthPermissions("user:edit")
@AuthRoles("admin")
@Controller
@Mapping("/demo")
public class DemoController {
    @AuthPermissions("agroup:edit")
    @Mapping("edit/{id}")
    public void edit(int id) { }

    @AuthRoles("admin")
    @Mapping("admin/save")
    public void save() { }
}
```

### 3.4 鉴权异常处理

```java
@Component
public class AuthExceptionFilter implements Filter {
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

---

## 4. 会话 / JWT 常见替换

| Spring 做法 | Solon 建议 |
|-------------|------------|
| HttpSession + SecurityContext | `Context.session(...)` 存 userId/角色；在 `verifyLogined` 读取 |
| JWT Filter 写入 Authentication | 自建 Filter / RouterInterceptor 解析 Token，写入 session 或 Context 属性，再由 `AuthProcessor` 校验 |
| Remember-Me / OAuth2 Client | 无开箱一对一组件；按业务用 Filter + 第三方 SDK 实现，再接入 `AuthProcessor` |

> JWT 解析库（jjwt 等）可继续使用；**不要**再引入 `spring-security-oauth2-*`。

---

## 5. CORS（常与 Security 一起迁）

Spring Security 里配的 CORS，迁到 Solon 后建议独立处理：

```java
// 全局
Solon.start(App.class, args, app -> {
    app.filter(-1, new CrossFilter().allowedOrigins("*"));
});

// 或控制器
@CrossOrigin(origins = "*")
@Controller
public class ApiController { }
```

依赖：`solon-web-cors`（通常已随 `solon-web` 可用）。

---

## 6. 配置加密（可选）

对应 Spring 侧 jasypt 等：

```yaml
# app.yml
solon.vault:
  password: "Your16BytePass!!"

db.password: "ENC(...)"
```

使用 `VaultUtils.encrypt/decrypt` 或 `@VaultInject`，详见开发 skill 的 security 文档。

---

## 7. 陷阱

1. **不要混用** `spring-security-*` 与 Solon 鉴权。
2. **没有** `@EnableWebSecurity` / `SecurityFilterChain` 等价物，统一走 `AuthAdapter`。
3. **路径放行**写在规则 `exclude`，不要假设「没配 Security 就全放行」——引入 auth 插件后按规则生效。
4. **方法注解**不能替代登录态规则：宏观路径用 Adapter，微观权限用 `@AuthPermissions` / `@AuthRoles`。
5. CSRF、默认生成的 login 页、UserDetails 密码编码器等 Spring 默认行为 **不会**自动出现，需按业务显式实现。

---

## 8. 迁移检查清单

- [ ] 移除 `spring-boot-starter-security` 及所有 `spring-security-*`
- [ ] 引入 `solon-security-auth`
- [ ] 用 `AuthAdapter` 配置 loginUrl / include / exclude / failure
- [ ] 实现 `AuthProcessor`（至少 `verifyLogined` / `verifyPath`）
- [ ] `@PreAuthorize` / `@Secured` → `@AuthPermissions` / `@AuthRoles`
- [ ] 登录成功后写入 Session 或 Token 上下文，供 Processor 读取
- [ ] 配置 `AuthException` 统一输出
- [ ] CORS 从 Security 配置中拆出，改用 CrossFilter / `@CrossOrigin`
- [ ] 如有配置加密，评估 `solon-security-vault`
