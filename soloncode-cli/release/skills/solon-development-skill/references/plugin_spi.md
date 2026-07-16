# Plugin SPI — 插件与扩展机制

> 适用场景：开发 Solon 插件、E-SPI 外部扩展、H-SPI 热插拔、配置元数据。
>
> 目标版本：4.0.3。业务开发（注解/IoC/配置）见 `core_concepts.md`。
>
> **阅读建议**：普通业务代码生成**不必**阅读本文件；仅在做插件/扩展包时加载。

## 插件系统（SPI）

Solon 基于 SPI 的插件体系。插件参与应用生命周期并提供扩展能力；加入 Maven 依赖后会自动激活对应插件。

### Plugin 接口

```java
public interface Plugin {
    void start(AppContext context) throws Throwable;  // 应用初始化后调用
    default void preStop() throws Throwable {}         // ::stop 之前
    default void stop() throws Throwable {}            // Solon::stop 时
}
```

### 插件发现

1. 实现插件类（约定：`XxxSolonPlugin`，放在 `integration` 包，**不允许注入**）：

```java
public class DemoSolonPlugin implements Plugin {
    @Override
    public void start(AppContext context) {
        context.beanInterceptorAdd(AuthLogined.class, new LoginedInterceptor());
    }
}
```

2. 在 `META-INF/solon/{packname}.properties` 声明（文件名须全局唯一）：

```properties
solon.plugin=org.example.DemoSolonPlugin
solon.plugin.priority=1    # 越大越先执行，默认 0
```

3. 启动时扫描 `META-INF/solon/` 下所有 `.properties`，发现并排序插件。

### 排除插件

```yaml
solon.plugin.exclude:
  - "{PluginImpl}"
```

```java
Solon.start(App.class, args, app -> {
    app.pluginExclude(PluginImpl.class);
});
```

### 命名约定

| 模式 | 含义 |
|---|---|
| `solon-*` | 框架内部插件 |
| `*-solon-plugin` | 外部适配插件 |
| `*-solon-ai-plugin` | AI 适配插件 |
| `*-solon-cloud-plugin` | Cloud 适配插件 |

### 插件可扩展点

插件可在启动时程序化扩展框架：

- 注册注解拦截器（如 `@Transaction`、`@Cache`）
- 注册 Bean 构建器（如 `@Controller` 路由加载）
- 注册字段注入器
- 注册方法提取器（如 `@CloudJob`）

## E-SPI（外部 SPI）

解决 fatjar 部署时的外部扩展：从**应用 classpath 之外**的目录加载插件 jar 与配置。`.properties` / `.yml` 作为扩展配置；`.jar` / `.zip` 作为插件包。

### 特性

- 所有插件**共享** ClassLoader、AppContext 与配置
- 插件可独立打包（外部加载）或并入主应用
- 更新外部插件/配置**需要重启**主服务
- 由 Solon 核心提供，**无需额外依赖**

### ClassLoader 共享

通过 `AppClassLoader:addJar(URL | File)` 实现。启动时自动加载扩展目录中的 jar/zip 与配置。也可编程加载：

```java
@SolonMain
public class Application {
    public static void main(String[] args) throws Exception {
        Solon.start(Application.class, args, app -> {
            app.classLoader().addJar(new File("/demo.jar"));
            app.cfg().loadAdd(new File("/demo.yml"));
        });
    }
}
```

### 配置

```yaml
# 扩展目录（需手动创建）
solon.extend: "demo_ext"

# 前缀 "!" 表示自动创建目录
solon.extend: "!demo_ext"
```

### 目录布局示例

```
demo.jar
demo_ext/_db.properties
demo_ext/demo_user.jar
demo_ext/demo_order.jar
```

### 打包建议

- 插件可打成 fatjar（`maven-assembly-plugin`）
- 或将公共依赖放入主应用（推荐）
- 最佳实践：公共依赖在主应用打包；插件 `pom.xml` 中标记 `<optional>`

## H-SPI（热插拔 SPI）

面向生产的隔离、热更新与管理。每个业务模块作为独立插件开发打包。

> 依赖：`solon-hotplug`

### 特性

- 每个插件拥有**独立** ClassLoader、AppContext、配置
  - 仍可通过 `Solon.app()` / `Solon.cfg()` / `Solon.context()` 访问主程序资源
- 可独立打包或并入主应用
- 更新插件**无需重启**主服务
- 资源须自管理：`start()` 中添加的资源必须在 `stop()` 中移除
- 插件间通信建议用 EventBus 弱类型数据（Map、JsonString）；也可用 [DamiBus](https://gitee.com/noear/dami)

### ClassLoader 隔离规则

| 关系 | 访问规则 |
|---|---|
| 父 ClassLoader（公共资源） | 子可访问；若注册了资源，须在插件 `stop()` 注销 |
| 兄弟 ClassLoader | 互不可见；用 EventBus 或父 ClassLoader 实体类交互 |

### 插件开发示例

```java
public class Plugin1Impl implements Plugin {
    AppContext context;
    StaticRepository staticRepository;

    @Override
    public void start(AppContext context) {
        this.context = context;
        context.cfg().loadAdd("demo1011.plugin1.yml");
        context.beanScan(Plugin1Impl.class);

        staticRepository = new ClassPathStaticRepository(context.getClassLoader(), "plugin1_static");
        StaticMappings.add("/html/", staticRepository);
    }

    @Override
    public void stop() throws Throwable {
        Solon.app().router().remove("/user");
        JobManager.getInstance().jobRemove("job1");

        context.beanForeach(bw -> {
            if (bw.raw() instanceof EventListener) {
                EventBus.unsubscribe(bw.raw());
            }
        });

        StaticMappings.remove(staticRepository);
    }
}
```

模板渲染时注意 ClassLoader：

```java
public class BaseController implements Render {
    static final FreemarkerRender viewRender =
            new FreemarkerRender(BaseController.class.getClassLoader());

    @Override
    public void render(Object data, Context ctx) throws Throwable {
        if (data instanceof Throwable) {
            throw (Throwable) data;
        }
        if (data instanceof ModelAndView) {
            viewRender.render(data, ctx);
        } else {
            ctx.render(data);
        }
    }
}
```

### 插件管理

引入 `solon-hotplug` 后可在运行时安装/卸载/更新插件，并可进一步仓库化、平台化。

## E-SPI vs H-SPI

| 维度 | E-SPI | H-SPI |
|---|---|---|
| ClassLoader | 共享 | 隔离（每插件独立） |
| AppContext | 共享 | 隔离 |
| 热更新 | 否（需重启） | 是 |
| 额外依赖 | 无（核心支持） | `solon-hotplug` |
| 适用 | 简单外部扩展 | 生产热插拔、模块隔离 |

## 配置元数据提示

插件可为 IDE 提供配置提示元数据，路径：

```
resource/META-INF/solon/solon-configuration-metadata.json
```

### 文件格式

顶层两个数组：`properties`、`hints`。

**properties**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 完整属性名（小写点分，如 `server.port`） |
| `type` | string | 是 | 数据类型 |
| `defaultValue` | object | 否 | 默认值 |
| `description` | string | 否 | 简短描述 |

**hints**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 属性名（须与 property 对应） |
| `values` | array | 否 | 可选值列表 |
| `values[].value` | object | 是 | 值 |
| `values[].description` | string | 否 | 值说明 |

### 完整示例

```json
{
  "properties": [
    {
      "name": "server.port",
      "type": "java.lang.Integer",
      "defaultValue": "8080",
      "description": "服务端口"
    },
    {
      "name": "cache.driverType",
      "type": "java.lang.String",
      "defaultValue": "local",
      "description": "缓存驱动类型"
    },
    {
      "name": "beetlsql.inters",
      "type": "java.lang.String[]",
      "description": "数据管理插件列表"
    }
  ],
  "hints": [
    {
      "name": "cache.driverType",
      "values": [
        { "value": "local", "description": "本地缓存" },
        { "value": "redis", "description": "Redis缓存" },
        { "value": "memcached", "description": "Memcached缓存" }
      ]
    }
  ]
}
```

## 配置元数据自动生成

手写 `solon-configuration-metadata.json` 较繁琐。可用 `@BindProps` + `solon-configuration-processor` 在编译期**自动生成**。

### 依赖

Maven：

```xml
<dependencies>
    <dependency>
        <groupId>org.noear</groupId>
        <artifactId>solon-configuration-processor</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>

<!-- JDK 25 之后还需 annotationProcessorPaths -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.noear</groupId>
                <artifactId>solon-configuration-processor</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Gradle：

```gradle
compileOnly("org.noear:solon-configuration-processor")
annotationProcessor("org.noear:solon-configuration-processor")
```

### 用法

类绑定：

```java
@BindProps(prefix = "server")
@Configuration
public class ServerProps {
    private Integer port;
    private String host;
}
```

方法绑定：

```java
public class ServerProps {
    private Integer port;
    private String host;
}

@Configuration
public class ServerConfig {
    @BindProps(prefix = "server")
    @Bean
    public ServerProps serverProps() {
        return new ServerProps();
    }
}
```
