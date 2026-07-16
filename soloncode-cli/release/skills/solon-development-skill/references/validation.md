# Validation — 请求参数校验

> 适用场景：参数校验、实体校验、校验异常处理、自定义校验器。
>
> 目标版本：4.0.3。认证/CORS/Vault 见 `security.md`。

Dependency: `solon-security-validation`

提供请求参数校验能力，支持 Context 参数校验（注入前校验）和实体字段校验（注入后校验）两种模式。

## 基本用法

```java
@Valid  // 启用校验（加在控制器类上或基类上）
@Controller
public class UserController {
    @NoRepeatSubmit  // 重复提交验证（方法级，注入前校验）
    @Whitelist       // 白名单验证（方法级，注入前校验）
    @Mapping("/user/add")
    public void addUser(
            @NotNull String name,
            @Pattern("^http") String icon,
            @Validated User user) {  // 实体校验需加 @Validated
        // ...
    }

    // 分组校验
    @Mapping("/user/update")
    public void updateUser(@Validated(UpdateLabel.class) User user) {
        // ...
    }
}
```

## 实体字段校验

```java
@Data
public class User {
    @NotNull(groups = UpdateLabel.class)  // 分组校验
    private Long id;

    @NotNull
    private String nickname;

    @Email
    private String email;

    @Validated          // 验证列表里的实体
    @NotNull
    @Size(min = 1)      // 最少要有1个
    private List<Order> orderList;
}
```

## 工具手动校验

```java
User user = new User();
ValidUtils.validateEntity(user);
```

## 全量校验配置

默认策略：有校验不通过时马上返回。如需校验所有字段，添加配置：

```yaml
solon.validation.validateAll: true
```

## 校验注解一览

| 注解 | 作用范围 | 说明 |
|------|---------|------|
| `@Valid` | 控制器类 | 启用校验能力 |
| `@Validated` | 参数 或 字段 | 校验实体（或实体集合）上的字段 |
| `@Date` | 参数 或 字段 | 校验值为日期格式 |
| `@DecimalMax(value)` | 参数 或 字段 | 校验值 <= 指定值 |
| `@DecimalMin(value)` | 参数 或 字段 | 校验值 >= 指定值 |
| `@Email` | 参数 或 字段 | 校验值为电子邮箱格式 |
| `@Length(min, max)` | 参数 或 字段 | 校验值长度在区间内（对字符串有效） |
| `@Logined` | 控制器 或 动作 | 校验请求主体已登录 |
| `@Max(value)` | 参数 或 字段 | 校验值 <= 指定值 |
| `@Min(value)` | 参数 或 字段 | 校验值 >= 指定值 |
| `@NoRepeatSubmit` | 控制器 或 动作 | 校验请求未重复提交 |
| `@NotBlacklist` | 控制器 或 动作 | 校验请求主体不在黑名单 |
| `@NotBlank` | 动作/参数/字段 | 校验值不是空白（String） |
| `@NotEmpty` | 动作/参数/字段 | 校验值不是空（String） |
| `@NotNull` | 动作/参数/字段 | 校验值不是 null |
| `@NotZero` | 动作/参数/字段 | 校验值不是 0 |
| `@Null` | 动作/参数/字段 | 校验值是 null |
| `@Numeric` | 动作/参数/字段 | 校验值为数字格式 |
| `@Pattern(value)` | 参数 或 字段 | 校验值匹配指定正则 |
| `@Size(min, max)` | 参数 或 字段 | 校验集合大小在区间内 |
| `@Whitelist` | 控制器 或 动作 | 校验请求主体在白名单内 |

> 注：可作用在 [动作 或 参数] 上的注解，加在动作上时可支持多个参数的校验。

## 校验异常处理

通过过滤器捕捉校验异常：

```java
@Component
public class ValidatorFailureFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);
        } catch (ValidatorException e) {
            ctx.render(Result.failure(e.getCode(), e.getMessage()));
        }
    }
}
```

## 定制校验

### @NoRepeatSubmit 改为分布式锁

```java
@Component
public class NoRepeatSubmitCheckerNew implements NoRepeatSubmitChecker {
    @Override
    public boolean check(NoRepeatSubmit anno, Context ctx, String submitHash, int limitSeconds) {
        return LockUtils.tryLock(Solon.cfg().appName(), submitHash, limitSeconds);
    }
}
```

### @Whitelist 实现白名单验证

```java
@Component
public class WhitelistCheckerNew implements WhitelistChecker {
    @Override
    public boolean check(Whitelist anno, Context ctx) {
        String ip = ctx.realIp();
        // 实现白名单逻辑
        return true;
    }
}
```

## 扩展自定义校验注解（指引）

1. 定义注解（含 `message` / `groups` 等约定属性）
2. 实现 `Validator<YourAnno>`（`validateOfValue` + `validateOfContext`）
3. 注册：`ValidatorManager.register(YourAnno.class, new YourValidator())`

```java
@Configuration
public class Config {
    @Bean
    public void adapter() {
        ValidatorManager.register(Date.class, new DateValidator());
    }
}
```

> 完整自定义校验器实现较长，按需查官网「验证器」或源码 `solon-validation`；Agent 生成业务代码时优先用内置注解（`@NotNull` / `@NotEmpty` / `@Email` / `@Pattern` 等）。
