# Web Advanced — SSE / Reactive / I18n

> 适用场景：服务端推送 (SSE)、响应式 Web、国际化。

## SSE — Server-Sent Events

Dependency: `solon-web-sse`

### 推送模式

```java
@Controller
public class SseController {
    static Map<String, SseEmitter> emitterMap = new HashMap<>();

    @Mapping("/sse/{id}")
    public SseEmitter sse(String id) {
        return new SseEmitter(3000L)  // 异步超时 3s
                .onCompletion(() -> emitterMap.remove(id))
                .onError(e -> e.printStackTrace())
                .onInited(s -> emitterMap.put(id, s));
    }

    @Mapping("/sse/put/{id}")
    public String ssePut(String id) {
        SseEmitter emitter = emitterMap.get(id);
        if (emitter != null) {
            emitter.send("test msg");
            emitter.send(new SseEvent().id(Utils.guid()).data("msg").reconnectTime(1000L));
        }
        return "Ok";
    }

    @Mapping("/sse/del/{id}")
    public String sseDel(String id) {
        SseEmitter emitter = emitterMap.get(id);
        if (emitter != null) {
            emitter.complete();
        }
        return "Ok";
    }
}
```

### 流式输出模式（常用于 AI 应用）

```java
@Controller
public class DemoController {
    @Inject
    ChatModel chatModel;

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("case1")
    public Flux<SseEvent> case1() {
        return Flux.just(new SseEvent().data("test"));
    }

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("case2")
    public Flux<ChatMessage> case2(String prompt) {
        return Flux.from(chatModel.prompt(prompt).stream())
                .filter(resp -> resp.haContent())
                .map(resp -> resp.getMessage());
    }
}
```

### SSE 客户端

Dependency: `solon-net-httputils`

```java
HttpUtils.http("http://127.0.0.1:8080/sse/")
        .execAsSseStream("GET")
        .subscribe(new SimpleSubscriber<ServerSentEvent>()
                .doOnNext(sse -> System.out.println(sse)));
```

### 线程与超时配置

```yaml
# 服务 http 最小线程数（默认 0 表示自动）
server.http.coreThreads: 0
# 服务 http 最大线程数
server.http.maxThreads: 0
```

超时说明：0L 代表默认，-1L 代表不超时（仍有闲置超时限制）。

---

## Web Reactive（响应式开发）

Dependency: `solon-web-rx`

类似 WebFlux，将 Solon Web 的异步能力转换为响应式体验。经典体验与响应式可共存。

### 基础使用

```java
@Controller
public class DemoController {
    // 经典的
    @Mapping("/hi")
    public String hi(String name) {
        return "Hello " + name;
    }

    // 响应式的
    @Mapping("/hello")
    public Mono<String> hello(String name) {
        return Mono.fromSupplier(() -> "Hello " + name);
    }
}
```

### 流式输出 — SSE 格式

```java
@Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
@Mapping("case1")
public Flux<SseEvent> case1(String prompt) {
    return Flux.from(chatModel.prompt(prompt).stream())
            .map(resp -> resp.getMessage())
            .map(msg -> new SseEvent().data(msg.getContent()));
}
```

### 流式输出 — ndjson 格式

```java
@Produces(MimeType.APPLICATION_X_NDJSON_UTF8_VALUE)
@Mapping("case2")
public Flux<AssistantMessage> case2(String prompt) {
    return Flux.from(chatModel.prompt(prompt).stream())
            .map(resp -> resp.getMessage());
}
```

### 对接 Vert.X 响应式组件

```xml
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-web-client</artifactId>
</dependency>
```

```java
@Configuration
public class VertxConfig {
    @Bean public Vertx vertx() { return Vertx.vertx(); }
    @Bean public WebClient webClient(Vertx vertx) { return WebClient.create(vertx); }
}

@Controller
public class DemoController {
    @Inject WebClient webClient;

    @Mapping("/hello")
    public Mono<String> hello() {
        return Mono.create(sink -> {
            webClient.getAbs("https://example.com")
                    .send()
                    .onSuccess(resp -> sink.success(resp.bodyAsString()))
                    .onFailure(err -> sink.error(err));
        });
    }
}
```

### 流式转发（不需要积累数据，省内存）

Dependency: `solon-net-httputils`

```java
@Controller
public class DemoController {
    @Mapping("/hello")
    public Flux<String> hello() throws Exception {
        return HttpUtils.http("https://solon.noear.org/").execAsLineStream("GET");
    }
}
```

---

## I18n — 国际化

Dependency: `solon-i18n`

### 配置文件

`resources/i18n/messages.properties`（默认）：
```properties
login.title=登录
login.name=世界
```

`resources/i18n/messages_en_US.properties`：
```properties
login.title=Login
login.name=world
```

### 方式一：工具类

```java
@Controller
public class DemoController {
    @Mapping("/demo/")
    public String demo(Locale locale) {
        return I18nUtil.getMessage(locale, "login.title");
    }
}
```

### 方式二：国际化服务类

```java
@Controller
public class LoginController {
    I18nService i18nService = new I18nService("i18n.login");

    @Mapping("/demo/")
    public String demo(Locale locale) {
        return i18nService.get(locale, "login.title");
    }
}
```

### 方式三：注解（视图模板）

```java
@I18n("i18n.login")
@Controller
public class LoginController {
    @Mapping("/login/")
    public ModelAndView login() {
        return new ModelAndView("login.ftl");
    }
}
```

### 模板中使用

**beetl:** `${i18n["login.title"]}` / `${@i18n.get("login.title")}`
**enjoy:** `#(i18n.get("login.title"))`
**freemarker:** `${i18n["login.title"]}` / `${i18n.get("login.title")}`
**thymeleaf:** `<span th:text='${i18n.get("login.title")}'></span>`

### 语言分析器

| 分析器 | 说明 |
|---|---|
| `LocaleResolverHeader`（默认） | 从 `Content-Language` 或 `Accept-Language` 获取 |
| `LocaleResolverCookie` | 从 `SOLON.LOCALE` Cookie 获取 |
| `LocaleResolverSession` | 从 `SOLON.LOCALE` Session 属性获取 |

切换分析器：

```java
@Configuration
public class DemoConfig {
    @Bean
    public LocaleResolver localeResolver() {
        return new LocaleResolverCookie();
    }
}
```

### 分布式国际化配置

适合对接企业内部的国际化配置中台：

```java
public class I18nBundleFactoryImpl implements I18nBundleFactory {
    @Override
    public I18nBundle create(String bundleName, Locale locale) {
        if (I18nUtil.getMessageBundleName().equals(bundleName)) {
            bundleName = Solon.cfg().appName();
        }
        return new I18nBundleImpl(I18nContextManager.getMessageContext(bundleName), locale);
    }
}

// 注册到 Bean 容器
Solon.context().wrapAndPut(I18nBundleFactory.class, new I18nBundleFactoryImpl());
```
