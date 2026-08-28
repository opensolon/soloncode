# Remoting — Nami RPC / 声明式 HttpClient

> 适用场景：基于 Nami 的服务间 RPC、声明式 HttpClient。
>
> 目标版本：4.0.3。默认 JSON 序列化栈为 **snack4**（`nami-coder-snack4` / `solon-serialization-snack4`）。
> 过滤器 / 服务发现 / 负载均衡见 **`remoting_filter_lb.md`**；Socket.D 见 **`socketd.md`**。

---

## 5 分钟最小 RPC 闭环

服务端（`solon-web`）与客户端（`solon-rpc`）共用同一接口定义：

```java
// 1) 接口（双方依赖）
public interface UserService {
    User getById(long userId);
}

// 2) 服务端实现
@Mapping("/rpc/v1/user")
@Remoting
public class UserServiceImpl implements UserService {
    @Override
    public User getById(long userId) {
        return new User(userId, "demo");
    }
}

// 3) 客户端消费
@NamiClient(url = "http://localhost:9001/rpc/v1/user", headers = ContentTypes.JSON)
UserService userService;
```

> 更完整的接口实体、配置见下文；发现与负载均衡见 `remoting_filter_lb.md`。

---

## 通道与序列化组件

RPC 开发由三部分组成：服务接口声明（独立项目）、服务端实现、客户端消费。

**通道组件：**

| 通道 | 客户端组件 | 服务端支持组件 |
|---|---|---|
| Http 通道 | nami-channel-http | solon-server-jdkhttp / solon-server-smarthttp / solon-server-jetty / solon-server-undertow |
| Socket.D 通道 | nami-channel-socketd + socket.d | solon-server-socketd + socket.d |

**序列化方案组件：**

| 序列化方案 | 客户端组件 | 服务端组件 |
|---|---|---|
| Json | nami-coder-snack4 / nami-coder-fastjson / nami-coder-fastjson2 / nami-coder-jackson | solon-serialization-snack4 / solon-serialization-fastjson / solon-serialization-fastjson2 / solon-serialization-jackson |
| Hessian | nami-coder-hessian | solon-serialization-hessian |
| Fury | nami-coder-fury | solon-serialization-fury |
| Kryo | nami-coder-kryo | solon-serialization-kryo |
| Protostuff | nami-coder-protostuff | solon-serialization-protostuff |

> 选择序列化方案时，客户端与服务端的框架应一一对应。客户端需一个 channel + 一个 coder；服务端需一个 server + 一个 serialization。
> `nami-channel-http` 在 v3.3.0 后支持文件上传（配合 `UploadedFile` 参数）。

---

## RPC — 基于 Nami 的远程调用

### 依赖说明

- 服务端：引入 `solon-web`（已包含 smarthttp + snack4 序列化）
- 客户端：引入 `solon-rpc`（已集成 RPC 客户端所需的 Nami 组件）

### 服务端：接口定义与实现

**接口定义**（独立项目，可被双方引用）：

```java
// 注意：函数名不能相同
public interface UserService {
    void add(User user);
    User getById(long userId);
}
```

**数据实体**（实现 Serializable，以适应任何序列化方案）：

```java
@Data
public class User implements Serializable {
    long userId;
    String name;
    int level;
}
```

**服务端实现**（引入依赖：接口项目 + `solon-web`）：

```java
public class ServerApp {
    public static void main(String[] args) {
        Solon.start(ServerApp.class, args);
    }
}

@Mapping("/rpc/v1/user") // 必须有 @Mapping
@Remoting
public class UserServiceImpl implements UserService {
    @Inject
    UserMapper userMapper;

    @Override
    public void add(User user) {
        userMapper.add(user);
    }

    @Override
    public User getById(long userId) {
        return userMapper.getById(userId);
    }
}
```

配置：

```yaml
server.port: 9001

solon.app:
  group: "demo"
  name: "userapi"
```

### 客户端：服务消费

```java
public class ClientApp {
    public static void main(String[] args) {
        Solon.start(ClientApp.class, args);
    }
}

@Mapping("user")
@Controller
public class UserController {
    // 直接指定地址和序列化方案
    @NamiClient(url = "http://localhost:9001/rpc/v1/user", headers = ContentTypes.JSON)
    UserService userService;

    @Post
    @Mapping("register")
    public Result register(User user) {
        userService.add(user);
        return Result.succeed();
    }
}
```

配置：

```yaml
server.port: 8081

solon.app:
  group: "demo"
  name: "userdemo"
```

### 序列化定制

通过配置器全局定制编码/解码：

```java
@Configuration
public class Config {
    @Bean
    public NamiConfiguration initNami() {
        return new NamiConfiguration() {
            @Override
            public void config(NamiClient client, NamiBuilder builder) {
                builder.decoder(Snack4Decoder.instance);
                builder.encoder(Snack4Encoder.instance);
            }
        };
    }
}
```

或在接口声明时指定内容类型：

```java
@NamiClient(name = "userapi", path = "/rpc/v1/user", headers = ContentTypes.JSON)
UserService userService;
```

### 构建器模式（手动创建）

不依赖 Solon 的最小用法（Hello World 风格，可运行在任何 Java 环境）：

```java
GitHub github = Nami.builder()
        .decoder(Snack4Decoder.instance)   // 编码组件（序列化）
        .encoder(Snack4Encoder.instance)
        .channel(HttpChannel.instance)     // 通道组件（http）
        .upstream(() -> "https://api.github.com") // 服务地址提供者
        .create(GitHub.class);
```

在 Solon 中结合服务发现 / 本地配置路由：

```java
UserService userService = Nami.builder()
        .name("userapi")
        .path("/rpc/v1/user")
        .decoder(Snack4Decoder.instance)
        .encoder(Snack4Encoder.instance)
        .create(UserService.class);
```

其他常用构建方法：`url(String)`（直接指定地址）、`group(String)`（服务组）、`timeout(int)` / `heartbeat(int)`（超时/心跳，秒）、`headerSet(name, val)`（设置头）、`filterAdd(Filter)`（添加过滤器）。

### 超时设置

**全局性设置**（不宜设太大）：

```java
NamiGlobal.setConnectTimeout(10);     // 连接超时（秒）
NamiGlobal.setReadTimeout(10);        // 读超时（秒）
NamiGlobal.setWriteTimeout(10);       // 写超时（秒）
NamiGlobal.setMaxConnections(10000);  // 最大连接数
```

或全局配置器风格（同样全局生效，还可配置其它内容）：

```java
@Component
public class DemoNamiConfiguration implements NamiConfiguration {
    @Override
    public void config(NamiClient client, NamiBuilder builder) {
        builder.timeout(10); // 秒
    }
}
```

**接口专用设置**（按需设长/短）：

```java
// 注解方式
@NamiClient(name = "userapi", path = "/rpc/v1/user", timeout = 300, heartbeat = 30)
UserService userService;

// 构建器方式（也可直接 .url(...)）
UserService userService = Nami.builder()
        .name("userapi").path("/rpc/v1/user")
        .timeout(300).heartbeat(30)
        .create(UserService.class);
```

- `timeout`（秒）：对 http、socket、websocket 通道都有效
- `heartbeat`（秒）：仅对 socket、websocket 通道有效

---

## Nami 注解说明

### @NamiClient 注解

| 字段 | 说明 | 示例 |
|---|---|---|
| url | 完整的 url 地址 | `http://api.water.org/cfg/get/` |
| group | 服务组 | `water` |
| name | 服务名或负载均衡组件名（配合发现服务使用） | `waterapi` |
| path | 路径 | `/cfg/get/` |
| headers | 添加头信息 | `{"head1=a","head2=b"}` |
| configuration | 配置器 | |
| localFirst | 本地优先（如果本地有接口实现，则优先用） | `false` |
| timeout | 超时（秒） | `300` |
| heartbeat | 心跳间隔（秒），仅对 socket、websocket 通道有效 | `30` |

### @NamiMapping 注解（注在函数上，默认不需要）

| 字段 | 说明 |
|---|---|
| value | 映射值，支持三种情况 |
| headers | 声明时添加头信息，如 `{"a=1", "b=2"}` |

映射值的三种情况：

- 没有注解：没有参数时执行 GET，有参数时执行 POST；path 为函数名（默认行为）
- `@NamiMapping("GET")`：执行 GET 请求，path 为函数名
- `@NamiMapping("PUT user/a.0.1")`：执行 PUT 请求，path 为 `user/a.0.1`

### @NamiBody 注解（注在参数上）

| 字段 | 说明 |
|---|---|
| contentType | 内容类型 |

注在参数上，表示以此参数做为内容主体进行提交。

### @NamiParam 注解（注在参数上）

| 字段 | 说明 |
|---|---|
| value | 参数名 |

注在参数上，主要为参数标注参数名字（v3.2.0 后支持）。

---

## Nami 声明式 HttpClient

Nami 除做 RPC 客户端外，还提供声明式 HttpClient 的体验能力。

### 接口声明与使用

```java
@NamiClient(url = "http://localhost:8080/ComplexModelService/")
public interface IComplexModelService {
    // POST http://localhost:8080/ComplexModelService/save
    void save(@NamiBody ComplexModel model);

    // POST http://localhost:8080/ComplexModelService/read
    ComplexModel read(Integer modelId);
}
```

调整请求方式和路径：

```java
@NamiClient(url = "http://localhost:8080/ComplexModelService/", headers = "TOKEN=xxx")
public interface IComplexModelService {
    // PUT http://localhost:8080/ComplexModelService/save
    @NamiMapping("PUT")
    void save(@NamiBody ComplexModel model);

    // GET http://localhost:8080/ComplexModelService/api/1.0.1?modelId=xxx
    @NamiMapping("GET api/1.0.1")
    ComplexModel read(Integer modelId);
}
```

注入使用：

```java
@Controller
public class Demo {
    // 注入时没有配置，则使用接口声明时的注解配置
    @NamiClient
    IComplexModelService complexModelService;

    @Mapping
    public void test(ComplexModel model) {
        complexModelService.save(model);
    }
}
```

---

## Nami 使用 Solon 注解（v3.3.0 后支持）

新的特性可以直接 copy 控制器上的代码（微做调整），即可作为客户端接口。

**注解的对应关系：**

| Nami 注解 | Solon 注解 | 备注 |
|---|---|---|
| `@NamiMapping` | `@Mapping` / `@Get` / `@Put` / `@Post` / `@Delete` / `@Patch` | |
| | `@Consumes` | 声明请求的内容类型 |
| `@NamiBody` | `@Body` | 声明参数为 body（会转为独立主体发送） |
| `@NamiParam` | `@Param` | |
| | `@Header` | 声明参数为 header（会自动转到请求头） |
| | `@Cookie` | 声明参数为 cookie（会自动转到请求头） |
| | `@Path` | 声明参数为 path（会自动转到 url，v3.3.1 后支持） |

**示例（可从控制器接口拷贝后微调）：**

```java
@NamiClient
public interface HelloService {
    // 完整写法
    @Post
    @Mapping("hello")
    String hello(String name, @Header("H1") String h1, @Cookie("C1") String c1);

    // 路径段与方法同名、参数名相同时可省略 @Mapping/@Param
    @Consumes(MimeType.APPLICATION_JSON_VALUE)
    @Post
    String test01(List<String> ids);

    // 有参数默认 POST、无参数默认 GET；路径变量可用 @Path 或 {name}
    @Mapping("/test04/{name}")
    String test04(String name);

    @Mapping("/test05/?type={type}")
    String test05(int type, @Body Order order);
}
```

---

## Nami 头信息的设置方式

三种添加头信息的方式：

### 1、声明时添加（`@NamiMapping` 上）

```java
public interface GitHub {
    @NamiMapping(headers = {"a=1", "b=2"})
    List<Contributor> contributors(String owner, String repo);
}
```

> 注意声明位置是 `@NamiMapping`（方法级）；接口级头信息用 `@NamiClient(headers = ...)`。

### 2、过滤器时添加（接口自身或全局过滤器）

```java
public interface GitHub extends Filter {
    @NamiMapping(headers = {"a=1", "b=2"})
    List<Contributor> contributors(String owner, String repo);

    @Override
    default Result doFilter(Invocation inv) throws Throwable {
        inv.headers.put(CloudClient.trace().HEADER_TRACE_ID_NAME(), CloudClient.trace().getTraceId());
        return inv.invoke();
    }
}
```

### 3、运行时添加

- **NamiAttach（4.x 推荐，since 3.8.1）**：基于域上下文（ScopeLocal），只对 `apply` 内的调用链生效，可携带返回值：

```java
@Controller
public class Demo {
    @NamiClient(url = "https://api.github.com")
    GitHub gitHub;

    @Mapping
    public Object test() {
        return NamiAttach.apply(attach -> {
            attach.put("a", "1");        // 本次调用链的附加头
            attach.put("traceId", Utils.guid());
            return gitHub.contributors("OpenSolon", "solon");
        });
    }
}
```

- **NamiAttachment（3.x 旧方式，4.x 已移除）**：线程上下文静态方式：

```java
NamiAttachment.put("a", "1");
return gitHub.contributors("OpenSolon", "solon");
```

> `NamiAttach` 支持 `put/get/remove/clear` 及 `current()`；`apply` 有两种重载：`apply(consumer)`（无返回值）与 `apply(function)`（返回函数结果，推荐）。

---

## 进阶索引

| 主题 | Reference |
|---|---|
| Nami 过滤器 | `remoting_filter_lb.md` |
| 本地/分布式发现 | `remoting_filter_lb.md` |
| LoadBalance 定制 | `remoting_filter_lb.md` |
| Socket.D | `socketd.md` |
| Cloud Discovery 插件表 | `cloud_core.md` |
