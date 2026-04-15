# Testing — 测试体系

> 适用场景：单元测试、集成测试、HTTP 接口测试、Mock 测试。

## 测试框架选择

| 插件 | 支持能力 | 备注                                                |
|---|---|---------------------------------------------------|
| `solon-test` | JUnit 5 | **默认推荐**，仅 JUnit5（v2.8.1 之前，同时支持 JUnit4 和 JUnit5） |
| `solon-test-junit5` | JUnit 5 | 仅 JUnit5                                          |
| `solon-test-junit4` | JUnit 4 | 额外 JUnit4 支持                                      |

> 注意：Junit5 的 `@Test` 为 `org.junit.jupiter.api.Test`，Junit4 的 `@Test` 为 `org.junit.Test`，不要搞混。

## 核心扩展

| 扩展 | 说明 |
|---|---|
| `@SolonTest` | 指定 Solon 测试主类 |
| `@Rollback` | 测试时事务回滚 |
| `@Import` | 导入测试所需的配置文件或包 |
| `HttpTester` | HTTP 测试基类 |
| `SolonJUnit4ClassRunner` | JUnit 4 支持 |

## @SolonTest 注解属性

| 属性 | 说明 | 示例 |
|---|---|---|
| `value` | 启动类（默认为当前类） | |
| `classes` | 启动类 | |
| `delay` | 延迟秒数（默认 1） | |
| `env` | 环境配置 | `"dev"` |
| `args` | 启动参数 | `"--server.port=9999"` |
| `properties` | 应用属性 | `"solon.app.name=demo"` |
| `debug` | 是否调试模式（默认 true） | |
| `isAot` | 是否 AOT 运行（默认 false） |
| `scanning` | 是否扫描（默认 true） |
| `enableHttp` | 是否启用 http（默认 false） | |

---

## JUnit 5 使用

Dependency:

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-test</artifactId>
    <scope>test</scope>
</dependency>
```

### 纯 JUnit 5 测试

```java
import org.junit.jupiter.api.Test;

public class DemoTest {
    @Test
    public void hello() {
        System.out.println("Hello");
    }
}
```

### Solon 能力支持

```java
@Import(profiles = "classpath:demo/app.yml")
@SolonTest
public class DemoTest {
    @Inject("${user.name:world}")
    String userName;

    @Test
    public void hello() {
        System.out.println("Hello " + userName);
    }
}
```

### HTTP 接口测试（推荐）

```java
@SolonTest(TestApp.class)
public class DemoTest extends HttpTester {
    @Inject
    UserService userService;

    @Test
    public void hello() {
        assert userService.hello("world").equals("hello world");
    }

    @Test
    public void demo1_run0() {
        assert path("/demo1/run0/?str=").get().equals("不是null(ok)");
    }

    @Test
    public void demo2_header() throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("address", "192.168.1.1:9373");

        assert path("/demo2/header/")
                .header("X-Token", "abc")
                .data(map)
                .post()
                .equals("OK");
    }
}
```

### 指定启动参数

```java
@SolonTest(value = TestApp.class, args = "--server.port=9001")
public class DemoTest extends HttpTester {
    // ...
}
```

### 指定环境

```java
@SolonTest(value = TestApp.class, env = "dev")
public class DemoTest {
    // ...
}
```

---

## JUnit 4 使用

Dependency:

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-test-junit4</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@RunWith(SolonJUnit4ClassRunner.class)
@SolonTest(TestApp.class)
public class DemoTest extends HttpTester {
    @Inject
    UserService userService;

    @Test  // org.junit.Test
    public void hello() {
        assert userService.hello("world").equals("hello world");
    }
}
```

---

## Mock 测试

### Mockito

solon-test 已内置 mockito-core，可直接使用：

```java
public class MockTest {
    @Test
    void testStub() {
        List<Integer> l = mock(ArrayList.class);
        when(l.get(0)).thenReturn(10);
        when(l.get(1)).thenReturn(20);

        assertEquals(l.get(0), 10);
        assertEquals(l.get(1), 20);
        assertNull(l.get(4));
    }

    @Test
    void testMatchers() {
        List<Integer> l = mock(ArrayList.class);
        when(l.get(anyInt())).thenReturn(100);
        assertEquals(l.get(999), 100);
    }
}
```

### Mock Web Server

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>${okhttp.version}</version>
    <scope>test</scope>
</dependency>
```

```java
public class MockWebTest extends HttpTester {
    public static final String EXPECTED = "{\"status\": \"ok\"}";

    @Rule
    public MockWebServer server = new MockWebServer();

    @Test
    public void testSimple() throws IOException {
        server.enqueue(new MockResponse().setBody(EXPECTED));
        String rst = http(server.getPort()).get();
        assert rst != null;
        assert EXPECTED.equals(rst);
    }
}
```

---

## 批量接口测试

将所有接口测试按模块放到 apis 包下，方便批量单测：

```java
@Slf4j
@SolonTest(App.class)
public class Api_config extends HttpTester {
    @Test
    public void config_set() throws Exception {
        String json = path("/api/config.set")
                .data("tag", "demo")
                .data("key", "test")
                .data("value", "test").post();
        ONode node = ONode.load(json);
        assert node.get("code").getInt() == 200;
    }
}
```
