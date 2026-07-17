# 参数校验迁移参考

> Spring Boot → Solon 参数校验迁移指南（目标版本：跟随 SKILL.md，当前 4.0.3）
>
> Solon 原生完整用法见 `solon-development-skill` 的 `references/validation.md`。

## 1. 依赖对比

| Spring Boot | Solon | 说明 |
|---|---|---|
| `spring-boot-starter-validation` | **`solon-security-validation`** | 参数 / 实体校验（权威坐标） |

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-security-validation</artifactId>
</dependency>
```

> **注意**：不存在可用的 `solon-validation` Maven 坐标。仓库与 `solon-parent` BOM 中的 artifact 名为 **`solon-security-validation`**（源码模块路径可含 validation 字样，勿与坐标混淆）。

## 2. 注解语义（与 Spring 不同）

| 用途 | Spring | Solon | 说明 |
|---|---|---|---|
| 启用校验 | 类上 `@Validated` 或依赖方法参数上的 `@Valid`/`@Validated` | **类上（或基类上）`@Valid`** | Solon 必须显式启用 |
| 实体 / 集合字段校验 | 参数 `@Valid` / `@Validated` | 参数或字段 **`@Validated`**（可带 groups） | 触发 Bean 字段上的约束 |
| 简单参数约束 | 多靠 DTO + JSR 注解 | 参数上直接 `@NotNull` / `@NotBlank` / `@Pattern` 等 | 控制器类须有 `@Valid` |
| 分组 | `@Validated(Group.class)` | `@Validated(Group.class)` 或 `@Validated(value=...)` | Solon 的 `value` 即 groups |

```java
@Valid  // 启用校验（加在控制器类或基类上）
@Controller
public class UserController {

    @Mapping("/user/add")
    public void addUser(
            @NotNull String name,
            @Pattern("^http") String icon,
            @Validated User user) {  // 实体校验用 @Validated
        // ...
    }

    // 分组校验
    @Mapping("/user/update")
    public void updateUser(@Validated(UpdateLabel.class) User user) {
        // ...
    }
}
```

### 注解对照（语义近似，包名不兼容）

| Spring (javax/jakarta.validation) | Solon (`org.noear.solon.validation.annotation`) | 说明 |
|---|---|---|
| 类级 `@Validated` | 类级 `@Valid` | 启用校验 |
| 参数 `@Valid` / `@Validated`（实体） | 参数 `@Validated` | 校验实体字段 |
| `@NotNull` / `@NotBlank` / `@NotEmpty` | 同名 | import 必须更换 |
| `@Size` / `@Min` / `@Max` / `@Email` | 同名 | import 必须更换 |
| `@Pattern(regexp=...)` | `@Pattern(value=...)` 或 `@Pattern("...")` | **属性名是 `value`，不是 `regexp`** |
| `@DecimalMin` / `@DecimalMax` | 同名 | import 必须更换 |
| `@Digits` 等未列出的 JSR 注解 | **无同名内置** | 用 `@Pattern` / 自定义 `Validator` 替代 |
| — | `@Null` / `@NotZero` / `@Numeric` / `@Length` / `@Date` | Solon 扩展 |
| — | `@Logined` / `@Whitelist` / `@NoRepeatSubmit` 等 | 请求主体 / 防重等（非 Bean Validation） |

> **兼容性表述（权威）**：注解**语义近似** JSR 380，但 **包名与实现不兼容**。必须改为 `org.noear.solon.validation.annotation.*`，**不能**继续使用 `javax.validation` / `jakarta.validation` 注解。

## 3. 控制器参数校验

### 3.1 类级启用 + 参数级约束

#### Before — Spring

```java
@RestController
@Validated
public class UserController {

    @PostMapping("/user")
    public String create(@Validated @RequestBody UserDTO user) {
        return "ok";
    }

    @GetMapping("/user")
    public String get(@RequestParam @NotBlank String id) {
        return "ok";
    }
}
```

#### After — Solon

```java
@Valid
@Controller
@Mapping("/user")
public class UserController {

    @Mapping("/create")
    public String create(@Body @Validated UserDTO user) {
        return "ok";
    }

    @Mapping("/get")
    public String get(@Param @NotBlank String id) {
        return "ok";
    }
}
```

### 3.2 函数参数级校验

```java
@Valid
@Controller
public class UserController {

    @Mapping("/user/create")
    public String create(
            @NotBlank String name,
            @Pattern("13\\d{9}") String mobile,
            @Min(18) @Max(120) int age) {
        return "ok";
    }
}
```

### 3.3 实体字段校验

```java
// DTO：注解名可与 Spring 相似，但 import 必须是 Solon 包
public class UserDTO {
    @NotBlank(message = "姓名不能为空")
    private String name;

    @Pattern(value = "13\\d{9}", message = "手机号格式不正确")
    private String mobile;

    @Min(18)
    @Max(120)
    private int age;

    @Email
    private String email;

    @Validated          // 嵌套集合/对象字段
    @NotNull
    @Size(min = 1)
    private List<Order> orderList;

    // getter/setter
}

@Valid
@Controller
public class UserController {
    @Mapping("/user/create")
    public String create(@Validated UserDTO user) {
        // 参数上的 @Validated 触发实体字段校验
        return "ok";
    }
}
```

### 3.4 方法级批量非空（Solon 特性）

`@NotNull` 可标在**方法**上，`value` 为参数名列表（校验对应方法参数非 null）：

```java
@Valid
@Controller
public class UserController {

    @NotNull({"name", "mobile"})
    @Mapping("/user/add")
    public String add(String name, String mobile) {
        return "ok";
    }
}
```

> 实体内部字段请在字段上写 `@NotNull` / `@NotBlank`，不要把 DTO 字段名误写成方法级 `@NotNull({...})` 的「Bean 属性批量校验」。

## 4. 校验失败处理

Solon 校验失败抛出 **`org.noear.solon.validation.ValidatorException`**（不是 Spring 的 `MethodArgumentNotValidException`，也不是 JSR 的 `ConstraintViolationException`）。

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

全量校验（默认遇错即停）：

```yaml
solon.validation.validateAll: true
```

手动校验：

```java
ValidUtils.validateEntity(user);
```

## 5. 自定义校验（Solon 方式）

**不要**再写 Hibernate Validator 的 `@Constraint` + `ConstraintValidator`。Solon 扩展步骤：

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

> 完整实现见 `solon-development-skill` → `validation.md` 或源码模块 `solon-security-validation`。

## 6. 国际化消息

失败消息支持表达式 `message="{i18n.key}"`，由 Solon i18n（`I18nUtil` / `ValidatorFailureHandlerI18n`）解析。

- **不要**再依赖 `ValidationMessages.properties` 里的 `javax.validation.constraints.*` 键作为主路径。
- 将文案迁到项目 i18n 资源，并在注解 `message` 中引用 key。

## 7. 陷阱与差异

| 编号 | 陷阱描述 | 说明 |
|---|---|---|
| 1 | **坐标写错** | 必须用 `solon-security-validation`，不要写 `solon-validation`。 |
| 2 | **`@Valid` vs `@Validated`** | 类级启用用 `@Valid`；实体参数/字段用 `@Validated`。二者都存在，职责不同。 |
| 3 | **包名不兼容** | 必须从 `javax`/`jakarta.validation` 改为 `org.noear.solon.validation.annotation`。 |
| 4 | **无 BindingResult** | 校验失败由框架处理或捕获 `ValidatorException`。 |
| 5 | **`@Pattern` 属性** | 使用 `value`，不是 `regexp`。 |
| 6 | **异常类** | 使用 `ValidatorException`，不要捕获 Spring/JSR 异常类名却 import 错包。 |
| 7 | **自定义校验模型不同** | 使用 `Validator` + `ValidatorManager`，不是 `ConstraintValidator`。 |
| 8 | **部分 JSR 注解无内置** | 如 `@Digits` 等需 `@Pattern` 或自定义 Validator。 |

## 8. 迁移检查清单

- [ ] POM：移除 `spring-boot-starter-validation`，添加 **`solon-security-validation`**
- [ ] 控制器（或基类）增加类级 `@Valid` 启用校验
- [ ] 实体参数：Spring 的 `@Valid`/`@Validated` → Solon **`@Validated`**
- [ ] 替换 import：`javax.validation.*` / `jakarta.validation.*` → `org.noear.solon.validation.annotation.*`
- [ ] `@Pattern(regexp=...)` → `@Pattern(value=...)` / `@Pattern("...")`
- [ ] 校验异常处理：捕获 **`ValidatorException`**
- [ ] 移除 `BindingResult` 手动分支
- [ ] 自定义校验改为 `Validator` + `ValidatorManager`
- [ ] i18n 消息迁出 `javax.validation.constraints.*` 键体系
- [ ] 可选：`solon.validation.validateAll`、方法级 `@NotNull({"p1","p2"})`
