# 测试迁移指南：Spring Boot Test → Solon Test

> 目标版本：Solon 3.10.x

---

## 目录

- [1. 依赖迁移](#1-依赖迁移)
- [2. 测试类标识迁移](#2-测试类标识迁移)
- [3. HTTP 测试迁移](#3-http-测试迁移)
- [4. 测试配置迁移](#4-测试配置迁移)
- [5. Mock 迁移](#5-mock-迁移)
- [6. 事务回滚迁移](#6-事务回滚迁移)
- [7. 切面测试迁移](#7-切面测试迁移)
- [8. 条件测试迁移](#8-条件测试迁移)
- [9. 测试生命周期迁移](#9-测试生命周期迁移)
- [10. WebFlux 测试迁移](#10-webflux-测试迁移)
- [11. 数据层测试迁移](#11-数据层测试迁移)
- [12. 完整对照示例](#12-完整对照示例)
- [13. 常见陷阱与注意事项](#13-常见陷阱与注意事项)

---

## 1. 依赖迁移

### 1.1 基础测试依赖

**Spring Boot：**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

> `spring-boot-starter-test` 包含 JUnit 5、Mockito、AssertJ、JsonPath、Hamcrest 等。

**Solon (推荐)：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-test</artifactId>
    <scope>test</scope>
</dependency>
```

> 也可按需选择 `solon-test-junit5` 或 `solon-test-junit4`。

**关键差异：**
- Spring Boot 提供一个统一的 `spring-boot-starter-test`，包含所有测试工具。
- Solon 推荐使用 `solon-test`，也可按需选择 `solon-test-junit5` 或 `solon-test-junit4`。
- `solon-test` 已内置 Mockito，无需单独引入。

### 1.2 完整测试依赖配置

**Spring Boot (pom.xml)：**

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <!-- 内含：JUnit5, Mockito, AssertJ, JsonPath, Hamcrest 等 -->
</dependencies>
```

**Solon (pom.xml)：**

```xml
<dependencies>
    <!-- Solon 测试核心（已内置 Mockito） -->
    <dependency>
        <groupId>org.noear</groupId>
        <artifactId>solon-test</artifactId>
        <scope>test</scope>
    </dependency>
    <!-- AssertJ（如需要） -->
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 2. 测试类标识迁移

### 2.1 基础测试类

**Spring Boot：**

```java
// JUnit 5 风格（Spring Boot 2.2+）
@SpringBootTest  // 启动完整的 Spring 应用上下文
class UserServiceTest {
    @Autowired
    private UserService userService;

    @Test
    void testFindById() {
        User user = userService.findById(1L);
        assertNotNull(user);
    }
}
```

**Solon：**

```java
// JUnit 5 原生，无需额外 Runner
@SolonTest(App.class)  // 指定启动类，初始化 Solon 容器
class UserServiceTest {
    @Inject
    private UserService userService;

    @Test
    void testFindById() {
        User user = userService.findById(1L);
        assertNotNull(user);
    }
}
```

**关键差异：**
- `@SpringBootTest` → `@SolonTest(App.class)`
- `@Autowired` → `@Inject`
- `@SolonTest` **推荐指定**启动类（App.class），不指定时当前测试类将作为启动类；`@SpringBootTest` 可自动推断。
- Solon 不需要 `@RunWith(SpringRunner.class)`（JUnit 5 原生支持）。

### 2.2 JUnit 4 兼容迁移

**Spring Boot (JUnit 4)：**

```java
@RunWith(SpringRunner.class)  // JUnit 4 需要 Spring Runner
@SpringBootTest
public class UserServiceTest {
    @Autowired
    private UserService userService;
}
```

**Solon (JUnit 4)：**

```java
@RunWith(SolonJUnit4ClassRunner.class)  // JUnit 4 使用 Solon Runner
@SolonTest(App.class)
public class UserServiceTest {
    @Inject
    private UserService userService;
}
```

> 推荐迁移到 JUnit 5，避免 JUnit 4 的 Runner 机制。

### 2.3 不同测试范围的类声明

**Spring Boot：**

```java
// 测试指定层（不启动完整上下文）
@WebMvcTest(UserController.class)
class UserControllerTest { ... }

// 测试数据层
@DataJpaTest
class UserRepositoryTest { ... }

// 测试完整上下文
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationTest { ... }

// 不启动 Web 容器
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ServiceTest { ... }
```

**Solon：**

```java
// 测试控制器（启动完整上下文 + HTTP 测试能力）
@SolonTest(App.class)
class UserControllerTest extends HttpTester { ... }

// 测试数据层（启动完整上下文）
@SolonTest(App.class)
class UserRepositoryTest { ... }

// 测试完整上下文（启动 Web 容器）
@SolonTest(App.class)
class IntegrationTest extends HttpTester { ... }

// 不启动 Web 容器（通过属性控制）
@SolonTest(value = App.class, args = "--server.port=-1")
class ServiceTest { ... }
```

**关键差异：**
- Solon 没有 `@WebMvcTest`、`@DataJpaTest` 等切片注解，统一使用 `@SolonTest`。
- 如需限制加载范围，使用 `@Import` 指定加载的组件类。

---

## 3. HTTP 测试迁移

### 3.1 MockMvc → HttpTester

**Spring Boot (MockMvc)：**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGetUser() throws Exception {
        mockMvc.perform(get("/api/users/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("张三"));
    }

    @Test
    void testCreateUser() throws Exception {
        String body = "{\"name\":\"李四\",\"email\":\"lisi@example.com\"}";
        mockMvc.perform(post("/api/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(body))
               .andExpect(status().isOk());
    }

    @Test
    void testListUsers() throws Exception {
        mockMvc.perform(get("/api/users"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/users/1"))
               .andExpect(status().isOk());
    }
}
```

**Solon (HttpTester)：**

```java
@SolonTest(App.class)
class UserControllerTest extends HttpTester {

    @Test
    void testGetUser() throws Throwable {
        // 发起 GET 请求，直接获取响应体
        String json = path("/api/users/1").get();
        assertNotNull(json);
        assertTrue(json.contains("张三"));
    }

    @Test
    void testCreateUser() throws Throwable {
        String body = "{\"name\":\"李四\",\"email\":\"lisi@example.com\"}";
        // 发起 POST 请求
        String json = path("/api/users").bodyOfJson(body).post();
        assertNotNull(json);
    }

    @Test
    void testListUsers() throws Throwable {
        String json = path("/api/users").get();
        assertNotNull(json);
        // 可配合 JSON 解析库验证
    }

    @Test
    void testDeleteUser() throws Throwable {
        String json = path("/api/users/1").delete();
        assertNotNull(json);
    }
}
```

### 3.2 请求构建对比

| 操作 | Spring MockMvc | Solon HttpTester |
|---|---|---|
| GET | `mockMvc.perform(get("/path"))` | `path("/path").get()` |
| POST | `mockMvc.perform(post("/path").content(body))` | `path("/path").bodyOfJson(body).post()` |
| PUT | `mockMvc.perform(put("/path").content(body))` | `path("/path").bodyOfJson(body).put()` |
| DELETE | `mockMvc.perform(delete("/path"))` | `path("/path").delete()` |
| 设置请求头 | `.header("key", "value")` | `path("/path").header("key", "value").get()` |
| 设置 Content-Type | `.contentType(MediaType.APPLICATION_JSON)` | `.bodyOfJson(body)` (自动设置) |
| 读取响应体 | `.andReturn().getResponse().getContentAsString()` | 直接返回 `String` |

### 3.3 响应验证对比

**Spring Boot：**

```java
mockMvc.perform(get("/api/users/1"))
       .andExpect(status().isOk())                          // 验证状态码
       .andExpect(jsonPath("$.name").value("张三"))          // 验证 JSON 字段
       .andExpect(header().string("X-Custom", "value"));    // 验证响应头
```

**Solon：**

```java
// 获取完整响应进行验证
HttpResponse resp = path("/api/users/1").exec("GET");

assertEquals(200, resp.code());                              // 验证状态码
String body = resp.bodyAsString();
assertTrue(body.contains("张三"));                            // 验证响应内容
```

### 3.4 文件上传测试

**Spring Boot：**

```java
@Test
void testFileUpload() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file", "test.txt", "text/plain", "文件内容".getBytes()
    );
    mockMvc.perform(multipart("/api/files/upload").file(file))
           .andExpect(status().isOk());
}
```

**Solon：**

```java
@Test
void testFileUpload() throws Throwable {
    // 使用 HttpTester 的文件上传支持
    String json = path("/api/files/upload")
        .file("file", "test.txt", "text/plain", "文件内容".getBytes())
        .post();
    assertNotNull(json);
}
```

---

## 4. 测试配置迁移

### 4.1 测试专用属性

**Spring Boot：**

```java
// 方式1：@TestPropertySource 注解
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "app.cache.enabled=false"
})
class UserServiceTest { ... }

// 方式2：@TestPropertySource 指定文件
@SpringBootTest
@TestPropertySource(locations = "classpath:test-application.properties")
class UserServiceTest { ... }

// 方式3：profile
@ActiveProfiles("test")
@SpringBootTest
class UserServiceTest { ... }
```

**Solon：**

```java
// 方式1：通过 @SolonTest 的 properties 传入属性
@SolonTest(value = App.class, properties = {
    "app.cache.enabled=false"
})
class UserServiceTest { ... }

// 方式2：使用 @Import 加载测试配置类
@SolonTest(App.class)
@Import(TestConfig.class)
class UserServiceTest { ... }

// 方式3：指定环境
@SolonTest(value = App.class, env = "test")
class UserServiceTest { ... }

// 方式4：测试资源文件（src/test/resources/app.yml）
// 自动加载，无需额外配置
```

**关键差异：**
- `@TestPropertySource` → `@SolonTest(properties=...)` 或测试资源文件。
- `@ActiveProfiles("test")` → `@SolonTest(env="test")`。
- Solon 自动加载 `src/test/resources/app.yml`，优先级高于主配置。

### 4.2 测试配置类

**Spring Boot：**

```java
@SpringBootTest
public class OrderServiceTest {
    @TestConfiguration  // 测试专用配置类
    static class TestConfig {
        @Bean
        public PaymentService paymentService() {
            return new MockPaymentService();
        }
    }

    @Autowired
    private OrderService orderService;
}
```

**Solon：**

```java
@SolonTest(App.class)
@Import(TestConfig.class)  // 导入测试配置
public class OrderServiceTest {

    @Configuration
    public static class TestConfig {
        @Bean
        public PaymentService paymentService() {
            return new MockPaymentService();
        }
    }

    @Inject
    private OrderService orderService;
}
```

**关键差异：**
- `@TestConfiguration` → `@Configuration`（Solon 中都是普通配置类）。
- 测试配置类需要通过 `@Import` 显式导入，不会自动扫描。

### 4.3 条件加载组件

**Spring Boot：**

```java
@SpringBootTest
@Import({UserService.class, OrderService.class})  // 仅加载指定组件
class OrderServiceTest {
    @Autowired
    private OrderService orderService;
}
```

**Solon：**

```java
@SolonTest(App.class)
@Import({UserService.class, OrderService.class})  // 仅加载指定组件
class OrderServiceTest {
    @Inject
    private OrderService orderService;
}
```

---

## 5. Mock 迁移

### 5.1 手动 Mock（Solon 推荐方式）

**Spring Boot (@MockBean)：**

```java
@SpringBootTest
class UserServiceTest {
    @Autowired
    private UserController userController;

    @MockBean  // 在 Spring 容器中替换为 Mock 对象
    private UserRepository userRepository;

    @Test
    void testListUsers() {
        // 定义 Mock 行为
        when(userRepository.findAll()).thenReturn(List.of(
            new User(1L, "张三"),
            new User(2L, "李四")
        ));

        List<User> users = userController.list();
        assertEquals(2, users.size());
    }
}
```

**Solon (手动注入)：**

```java
@SolonTest(App.class)
class UserServiceTest {
    @Inject
    private UserController userController;

    @Test
    void testListUsers() {
        // 创建 Mock 对象
        UserRepository mockRepo = mock(UserRepository.class);
        when(mockRepo.findAll()).thenReturn(List.of(
            new User(1L, "张三"),
            new User(2L, "李四")
        ));

        // 手动将 Mock 对象注入到 Solon 容器
        Solon.context().wrapAndPut(UserRepository.class, mockRepo);

        List<User> users = userController.list();
        assertEquals(2, users.size());
    }
}
```

**关键差异：**
- `@MockBean` → 手动通过 `Solon.context().wrapAndPut()` 注入 Mock 对象。
- Spring 的 `@MockBean` 会自动替换容器中的 Bean；Solon 需要手动操作，但更直观可控。
- Mock 对象需要在测试方法或 `@BeforeEach` 中手动注入。

### 5.2 使用 Mockito 的完整示例

**Spring Boot：**

```java
@SpringBootTest
class OrderServiceTest {
    @MockBean
    private UserService userService;

    @MockBean
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // Mock 行为会在每个测试方法前重置
    }

    @Test
    void testCreateOrder() {
        User mockUser = new User(1L, "张三");
        when(userService.getUser(1L)).thenReturn(mockUser);

        Order order = orderService.createOrder(1L, BigDecimal.valueOf(99.9));
        assertNotNull(order);
        assertEquals(1L, order.getUserId());

        // 验证交互
        verify(orderRepository, times(1)).save(any(Order.class));
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class OrderServiceTest {
    @Inject
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // 创建并注入 Mock 对象
        UserService mockUserService = mock(UserService.class);
        OrderRepository mockOrderRepo = mock(OrderRepository.class);

        User mockUser = new User(1L, "张三");
        when(mockUserService.getUser(1L)).thenReturn(mockUser);

        // 替换容器中的 Bean
        Solon.context().wrapAndPut(UserService.class, mockUserService);
        Solon.context().wrapAndPut(OrderRepository.class, mockOrderRepo);
    }

    @Test
    void testCreateOrder() {
        Order order = orderService.createOrder(1L, BigDecimal.valueOf(99.9));
        assertNotNull(order);
        assertEquals(1L, order.getUserId());

        // 验证交互（获取容器中的 Mock 对象）
        OrderRepository repo = Solon.context().getBean(OrderRepository.class);
        verify(repo, times(1)).save(any(Order.class));
    }
}
```

### 5.3 纯单元测试（不启动容器）

**Spring Boot：**

```java
// 纯 Mockito 单元测试，不启动 Spring 容器
@ExtendWith(MockitoExtension.class)
class UserServicePureTest {
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void testFindById() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User(1L, "张三")));
        User user = userService.findById(1L);
        assertEquals("张三", user.getName());
    }
}
```

**Solon：**

```java
// 纯 Mockito 单元测试，不启动 Solon 容器
@ExtendWith(MockitoExtension.class)
class UserServicePureTest {
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void testFindById() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User(1L, "张三")));
        User user = userService.findById(1L);
        assertEquals("张三", user.getName());
    }
}
```

> 纯单元测试（不依赖框架容器）的写法完全一致，无需修改。

---

## 6. 事务回滚迁移

### 6.1 集成测试事务回滚

**Spring Boot：**

```java
@SpringBootTest
@Transactional   // 测试方法在事务中执行
@Rollback        // 测试完成后自动回滚（默认行为）
class OrderRepositoryTest {
    @Autowired
    private OrderRepository orderRepository;

    @Test
    void testSave() {
        Order order = new Order();
        order.setUserId(1L);
        order.setAmount(BigDecimal.valueOf(100));

        Order saved = orderRepository.save(order);
        assertNotNull(saved.getId());

        // 测试结束后事务自动回滚，不会污染数据库
    }

    @Test
    @Commit  // 不回滚，实际提交
    void testSaveAndCommit() {
        Order order = new Order();
        order.setUserId(2L);
        orderRepository.save(order);
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
@Rollback  // 使用 Solon 的 @Rollback 注解
class OrderRepositoryTest {
    @Inject
    private OrderRepository orderRepository;

    @Test
    void testSave() {
        Order order = new Order();
        order.setUserId(1L);
        order.setAmount(BigDecimal.valueOf(100));

        Order saved = orderRepository.save(order);
        assertNotNull(saved.getId());
    }
}
```

**关键差异：**
- Spring Boot 使用 `@Transactional` + `@Rollback` 组合。
- Solon 使用独立的 `@Rollback` 注解。
- Solon 的 `@Rollback` 同时具备事务管理和回滚的语义。

### 6.2 手动事务控制

**Spring Boot：**

```java
@SpringBootTest
class OrderServiceTest {
    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void testWithManualTransaction() {
        TransactionStatus tx = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            // 业务操作
            orderService.createOrder(...);
        } finally {
            txManager.rollback(tx);
        }
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class OrderServiceTest {
    @Test
    void testWithManualTransaction() {
        // 使用 Solon 的事务管理工具
        TransactionUtils.execute(() -> {
            // 业务操作
            orderService.createOrder(...);
        });
    }
}
```

---

## 7. 切面测试迁移

### 7.1 AOP / 拦截器测试

**Spring Boot：**

```java
@SpringBootTest
class LoggingAspectTest {
    @Autowired
    private UserService userService;

    @Test
    void testAspectLogging() {
        // 通过调用方法验证切面是否生效
        userService.findById(1L);
        // 检查日志输出或其他切面效果
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class LoggingAspectTest {
    @Inject
    private UserService userService;

    @Test
    void testAspectLogging() {
        // Solon 使用过滤器/拦截器而非 AOP，但测试方式类似
        userService.findById(1L);
        // 检查拦截器效果
    }
}
```

---

## 8. 条件测试迁移

### 8.1 条件执行测试

**Spring Boot：**

```java
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class CiOnlyTest {
    @Test
    void testOnlyOnCi() {
        // 仅在 CI 环境执行
    }
}

@SpringBootTest
@DisabledOnOs(OS.WINDOWS)
class LinuxOnlyTest {
    @Test
    void testOnlyOnLinux() {
        // 仅在非 Windows 环境执行
    }
}
```

**Solon：**

```java
// JUnit 5 原生条件注解，与 Spring Boot 完全一致
@SolonTest(App.class)
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class CiOnlyTest {
    @Test
    void testOnlyOnCi() {
        // 仅在 CI 环境执行
    }
}

@SolonTest(App.class)
@DisabledOnOs(OS.WINDOWS)
class LinuxOnlyTest {
    @Test
    void testOnlyOnLinux() {
        // 仅在非 Windows 环境执行
    }
}
```

> 条件测试注解（`@EnabledIf*`、`@DisabledOnOs` 等）属于 JUnit 5 原生功能，Solon 测试中直接可用。

### 8.2 嵌套测试

**Spring Boot：**

```java
@SpringBootTest
class UserApiTest {

    @Nested
    @DisplayName("用户查询测试")
    class QueryTests {
        @Test
        void testFindById() { ... }

        @Test
        void testFindAll() { ... }
    }

    @Nested
    @DisplayName("用户创建测试")
    class CreateTests {
        @Test
        void testCreate() { ... }
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class UserApiTest {

    @Nested
    @DisplayName("用户查询测试")
    class QueryTests {
        @Test
        void testFindById() { ... }

        @Test
        void testFindAll() { ... }
    }

    @Nested
    @DisplayName("用户创建测试")
    class CreateTests {
        @Test
        void testCreate() { ... }
    }
}
```

> 嵌套测试同样是 JUnit 5 原生功能，无需修改。

### 8.3 参数化测试

**Spring Boot / Solon（完全一致）：**

```java
@ParameterizedTest
@ValueSource(strings = {"张三", "李四", "王五"})
void testUserName(String name) {
    assertNotNull(name);
    assertFalse(name.isEmpty());
}

@ParameterizedTest
@CsvSource({
    "1, 张三",
    "2, 李四"
})
void testUserMapping(Long id, String expectedName) {
    User user = userService.findById(id);
    assertEquals(expectedName, user.getName());
}
```

---

## 9. 测试生命周期迁移

### 9.1 初始化与清理

**Spring Boot：**

```java
@SpringBootTest
class UserServiceTest {
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(new User(1L, "张三"));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @BeforeAll
    static void initAll() {
        System.out.println("所有测试开始前执行一次");
    }

    @AfterAll
    static void destroyAll() {
        System.out.println("所有测试结束后执行一次");
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class UserServiceTest {
    @Inject
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(new User(1L, "张三"));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @BeforeAll
    static void initAll() {
        System.out.println("所有测试开始前执行一次");
    }

    @AfterAll
    static void destroyAll() {
        System.out.println("所有测试结束后执行一次");
    }
}
```

> 测试生命周期注解（`@BeforeEach`、`@AfterEach`、`@BeforeAll`、`@AfterAll`）是 JUnit 5 原生功能，Solon 测试中完全兼容。

### 9.2 Solon 容器生命周期

**Spring Boot：**

```java
@SpringBootTest
class AppLifecycleTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void testContextLoaded() {
        assertNotNull(context);
        assertTrue(context.containsBean("userService"));
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class AppLifecycleTest {
    @Test
    void testContextLoaded() {
        // 通过 Solon 全局对象验证容器状态
        assertNotNull(Solon.context());
        assertNotNull(Solon.context().getBean(UserService.class));
    }
}
```

---

## 10. WebFlux 测试迁移

### 10.1 响应式 HTTP 测试

**Spring Boot (WebFlux)：**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ReactiveUserControllerTest {
    @Autowired
    private WebTestClient webTestClient;

    @Test
    void testGetUser() {
        webTestClient.get().uri("/api/users/1")
                     .exchange()
                     .expectStatus().isOk()
                     .expectBody()
                     .jsonPath("$.name").isEqualTo("张三");
    }
}
```

**Solon：**

```java
// Solon 同样使用 HttpTester 测试响应式接口
@SolonTest(App.class)
class ReactiveUserControllerTest extends HttpTester {

    @Test
    void testGetUser() throws Throwable {
        String json = path("/api/users/1").get();
        assertNotNull(json);
        assertTrue(json.contains("张三"));
    }
}
```

---

## 11. 数据层测试迁移

### 11.1 Repository 测试

**Spring Boot (@DataJpaTest)：**

```java
@DataJpaTest  // 仅加载 JPA 相关组件
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void testFindByName() {
        User user = new User();
        user.setName("张三");
        entityManager.persistAndFlush(user);

        User found = userRepository.findByName("张三");
        assertNotNull(found);
        assertEquals("张三", found.getName());
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class UserRepositoryTest {
    @Inject
    private UserRepository userRepository;

    @Test
    void testFindByName() {
        User user = new User();
        user.setName("张三");
        userRepository.save(user);

        User found = userRepository.findByName("张三");
        assertNotNull(found);
        assertEquals("张三", found.getName());
    }
}
```

**关键差异：**
- `@DataJpaTest` → `@SolonTest(App.class)`（Solon 没有切片注解）。
- `TestEntityManager` → 直接使用 `Repository` 操作。
- 如果需要使用内存数据库，在测试配置中设置：

```yaml
# src/test/resources/application.yml
solon:
  app:
    name: test-app
  dataSources:
    url: jdbc:h2:mem:testdb
    driverClassName: org.h2.Driver
```

### 11.2 数据库初始化

**Spring Boot：**

```sql
-- src/test/resources/schema.sql
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100),
    email VARCHAR(100)
);

-- src/test/resources/data.sql
INSERT INTO users (name, email) VALUES ('张三', 'zhangsan@example.com');
INSERT INTO users (name, email) VALUES ('李四', 'lisi@example.com');
```

**Solon：**

```sql
-- src/test/resources/schema.sql（同样适用）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100),
    email VARCHAR(100)
);

-- src/test/resources/data.sql（同样适用）
INSERT INTO users (name, email) VALUES ('张三', 'zhangsan@example.com');
INSERT INTO users (name, email) VALUES ('李四', 'lisi@example.com');
```

> 数据初始化脚本在 Solon 中同样放在 `src/test/resources/` 下，使用方式一致。

---

## 12. 完整对照示例

### 12.1 控制器集成测试

**Spring Boot：**

```java
// 完整的 Spring Boot 控制器集成测试
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 准备 Mock 数据
        User user = new User(1L, "张三", "zhangsan@example.com");
        when(userService.findById(1L)).thenReturn(user);
        when(userService.findAll()).thenReturn(List.of(user));
    }

    @Test
    public void testGetUser() throws Exception {
        mockMvc.perform(get("/api/users/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("张三"))
               .andExpect(jsonPath("$.email").value("zhangsan@example.com"));
    }

    @Test
    public void testListUsers() throws Exception {
        mockMvc.perform(get("/api/users"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$[0].name").value("张三"));
    }

    @Test
    public void testCreateUser() throws Exception {
        User newUser = new User(null, "王五", "wangwu@example.com");
        when(userService.save(any(User.class))).thenReturn(new User(3L, "王五", "wangwu@example.com"));

        mockMvc.perform(post("/api/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(newUser)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(3))
               .andExpect(jsonPath("$.name").value("王五"));
    }

    @Test
    public void testDeleteUser() throws Exception {
        doNothing().when(userService).deleteById(1L);

        mockMvc.perform(delete("/api/users/1"))
               .andExpect(status().isOk());

        verify(userService, times(1)).deleteById(1L);
    }

    @Test
    public void testGetUserNotFound() throws Exception {
        when(userService.findById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/users/999"))
               .andExpect(status().isNotFound());
    }
}
```

**Solon：**

```java
// 完整的 Solon 控制器集成测试
@SolonTest(App.class)
public class UserControllerTest extends HttpTester {

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 创建并注入 Mock 对象
        UserService mockUserService = mock(UserService.class);
        User user = new User(1L, "张三", "zhangsan@example.com");
        when(mockUserService.findById(1L)).thenReturn(user);
        when(mockUserService.findAll()).thenReturn(List.of(user));
        when(mockUserService.save(any(User.class))).thenReturn(new User(3L, "王五", "wangwu@example.com"));
        doNothing().when(mockUserService).deleteById(anyLong());

        // 注入到 Solon 容器
        Solon.context().wrapAndPut(UserService.class, mockUserService);
    }

    @Test
    public void testGetUser() throws Throwable {
        String json = path("/api/users/1").get();
        assertNotNull(json);
        assertTrue(json.contains("张三"));
        assertTrue(json.contains("zhangsan@example.com"));
    }

    @Test
    public void testListUsers() throws Throwable {
        String json = path("/api/users").get();
        assertNotNull(json);
        assertTrue(json.contains("张三"));
    }

    @Test
    public void testCreateUser() throws Throwable {
        User newUser = new User(null, "王五", "wangwu@example.com");
        String body = objectMapper.writeValueAsString(newUser);

        String json = path("/api/users").bodyOfJson(body).post();
        assertNotNull(json);
        assertTrue(json.contains("王五"));
    }

    @Test
    public void testDeleteUser() throws Throwable {
        String json = path("/api/users/1").delete();
        assertNotNull(json);
    }

    @Test
    public void testGetUserNotFound() throws Throwable {
        // 对于 404 场景，需要捕获响应状态码
        HttpResponse resp = path("/api/users/999").exec("GET");
        assertEquals(404, resp.code());
    }
}
```

### 12.2 服务层单元测试

**Spring Boot：**

```java
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void testFindById_Found() {
        User user = new User(1L, "张三", "zhangsan@example.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        User result = userService.findById(1L);
        assertNotNull(result);
        assertEquals("张三", result.getName());
    }

    @Test
    void testFindById_NotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> {
            userService.findById(999L);
        });
    }

    @Test
    void testCreateUser() {
        User input = new User(null, "李四", "lisi@example.com");
        User saved = new User(2L, "李四", "lisi@example.com");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        User result = userService.create(input);
        assertNotNull(result.getId());
        assertEquals("李四", result.getName());

        // 验证邮件发送
        verify(emailService, times(1)).sendWelcomeEmail("lisi@example.com");
    }
}
```

**Solon：**

```java
// 纯单元测试：不依赖 Solon 容器，写法完全一致
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void testFindById_Found() {
        User user = new User(1L, "张三", "zhangsan@example.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        User result = userService.findById(1L);
        assertNotNull(result);
        assertEquals("张三", result.getName());
    }

    @Test
    void testFindById_NotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> {
            userService.findById(999L);
        });
    }

    @Test
    void testCreateUser() {
        User input = new User(null, "李四", "lisi@example.com");
        User saved = new User(2L, "李四", "lisi@example.com");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        User result = userService.create(input);
        assertNotNull(result.getId());
        assertEquals("李四", result.getName());

        // 验证邮件发送
        verify(emailService, times(1)).sendWelcomeEmail("lisi@example.com");
    }
}
```

> **重要提示：** 纯单元测试（不依赖框架容器）在 Spring 和 Solon 之间 **完全一致**，无需任何修改。

### 12.3 多环境测试配置

**Spring Boot：**

```yaml
# src/test/resources/application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
  cloud:
    discovery:
      enabled: false
```

**Solon：**

```yaml
# src/test/resources/app.yml（Solon 自动加载测试配置）
solon:
  app:
    name: test-app
  dataSources:
    url: jdbc:h2:mem:testdb
    driverClassName: org.h2.Driver
```

---

## 13. 常见陷阱与注意事项

### 13.1 注解速查对照表

| 场景 | Spring Boot | Solon |
|---|---|---|
| 测试类标识 | `@SpringBootTest` | `@SolonTest(App.class)` |
| JUnit4 Runner | `@RunWith(SpringRunner.class)` | 不需要（或 `SolonJUnit4ClassRunner`） |
| 依赖注入 | `@Autowired` | `@Inject` |
| Mock Bean | `@MockBean` | `Solon.context().wrapAndPut()` |
| HTTP 测试 | `@AutoConfigureMockMvc` + `MockMvc` | `extends HttpTester` |
| 测试属性 | `@TestPropertySource` | `@SolonTest(properties=...)` |
| 测试配置 | `@TestConfiguration` | `@Configuration` + `@Import` |
| Profile | `@ActiveProfiles("test")` | `@SolonTest(env="test")` 或测试资源文件自动生效 |
| 切片测试 | `@WebMvcTest` / `@DataJpaTest` | 无对应（使用 `@Import` 控制） |
| 事务回滚 | `@Transactional` + `@Rollback` | `@Rollback` |
| 条件执行 | JUnit 5 原生注解 | JUnit 5 原生注解（一致） |

### 13.2 @SolonTest 推荐指定启动类

```java
// 不指定启动类时，当前测试类将作为启动类
@SolonTest
class MyTest { ... }

// 推荐：显式指定启动类
@SolonTest(App.class)
class MyTest { ... }
```

**提示：** `@SolonTest` 不指定启动类时，当前测试类将作为启动类。推荐显式指定以明确意图。

### 13.3 Mock 注入时机

```java
@SolonTest(App.class)
class UserServiceTest {
    @Inject
    private UserService userService;

    // 错误：Mock 注入太晚，userService 中已注入了真实的 Repository
    @Test
    void testWithMock() {
        UserRepository mockRepo = mock(UserRepository.class);
        Solon.context().wrapAndPut(UserRepository.class, mockRepo);
        // userService 中的 repository 不会被替换
    }

    // 正确：在 @BeforeEach 中提前注入
    @BeforeEach
    void setUp() {
        UserRepository mockRepo = mock(UserRepository.class);
        Solon.context().wrapAndPut(UserRepository.class, mockRepo);
    }
}
```

**陷阱提醒：** Mock 对象必须在被测 Bean 初始化之前注入。建议在 `@BeforeEach` 中统一处理。

### 13.4 HttpTester 端口分配

- Solon 测试默认启动真实的 HTTP 服务器（非 Mock），使用随机端口。
- 测试结束后服务器自动关闭。
- 如果测试之间有端口冲突，可指定不同端口：

```java
@SolonTest(value = App.class, args = "--server.port=8081")
class FirstTest extends HttpTester { ... }

@SolonTest(value = App.class, args = "--server.port=8082")
class SecondTest extends HttpTester { ... }
```

### 13.5 没有 Spring Test 的切片注解

Solon 没有提供 `@WebMvcTest`、`@DataJpaTest`、`@WebFluxTest` 等切片注解。替代方案：

```java
// 替代 @WebMvcTest：使用 @Import 限制加载范围
@SolonTest(App.class)
@Import({UserController.class, UserService.class})
class UserControllerTest extends HttpTester {
    // 只加载 Controller 和 Service，不加载其他组件
}
```

### 13.6 测试资源文件优先级

```
src/test/resources/app.yml    ← 测试专用配置（优先级高）
src/main/resources/app.yml    ← 主配置（优先级低）
```

- Solon 测试自动加载 `src/test/resources/` 下的配置文件。
- 测试配置会 **覆盖** 主配置中的同名键。
- 不需要 Spring 的 `@TestPropertySource` 即可实现测试配置隔离。

### 13.7 迁移检查清单

- [ ] 替换 `spring-boot-starter-test` 为 `solon-test`
- [ ] `@SpringBootTest` → `@SolonTest(App.class)`（推荐指定启动类）
- [ ] 删除 `@RunWith(SpringRunner.class)`（JUnit 5 不需要）
- [ ] `@Autowired` → `@Inject`
- [ ] `@MockBean` → `Solon.context().wrapAndPut()` 手动注入
- [ ] `MockMvc` → `extends HttpTester` + `path("/api/...").get()`
- [ ] `@AutoConfigureMockMvc` → 删除（`HttpTester` 自带）
- [ ] `@TestPropertySource` → `@SolonTest(properties=...)` 或测试资源文件
- [ ] `@TestConfiguration` → `@Configuration` + `@Import`
- [ ] `@ActiveProfiles("test")` → `@SolonTest(env="test")` 或测试资源文件自动生效
- [ ] `@Transactional` + `@Rollback` → `@Rollback`
- [ ] `@WebMvcTest` / `@DataJpaTest` → `@SolonTest` + `@Import` 控制范围
- [ ] 纯单元测试（不依赖容器）无需修改
- [ ] 验证所有测试用例通过
