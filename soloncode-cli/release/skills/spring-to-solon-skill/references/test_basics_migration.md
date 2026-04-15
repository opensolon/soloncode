# 测试基础迁移参考

> Spring Boot Test → Solon Test 迁移指南（目标版本：Solon 3.10.x）

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

**Solon：**

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-test</artifactId>
    <scope>test</scope>
</dependency>
```

**关键差异：**
- Spring Boot 提供统一的 `spring-boot-starter-test`，包含所有测试工具。
- Solon 推荐使用 `solon-test`，也可按需选择 `solon-test-junit5` 或 `solon-test-junit4`。
- `solon-test` 已内置 Mockito；如需 AssertJ 可额外添加。

### 1.2 完整测试依赖配置

**Solon (pom.xml)：**

```xml
<dependencies>
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

## 2. 测试类标识迁移

### 2.1 基础测试类

**Spring Boot：**

```java
@SpringBootTest
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
@SolonTest(App.class)
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
- `@SolonTest` **推荐指定**启动类，不指定时当前测试类将作为启动类。
- Solon 不需要 `@RunWith(SpringRunner.class)`（JUnit 5 原生支持）。

### 2.2 JUnit 4 兼容迁移

**Spring Boot (JUnit 4)：**

```java
@RunWith(SpringRunner.class)
@SpringBootTest
public class UserServiceTest {
    @Autowired
    private UserService userService;
}
```

**Solon (JUnit 4)：**

```java
@RunWith(SolonJUnit4ClassRunner.class)
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
@WebMvcTest(UserController.class)             // 测试指定层
@DataJpaTest                                  // 测试数据层
@SpringBootTest(webEnvironment = RANDOM_PORT)  // 测试完整上下文
@SpringBootTest(webEnvironment = NONE)         // 不启动 Web 容器
```

**Solon：**

```java
@SolonTest(App.class)
class UserControllerTest extends HttpTester { ... }  // 测试控制器

@SolonTest(App.class)
class UserRepositoryTest { ... }                      // 测试数据层

@SolonTest(App.class)
class IntegrationTest extends HttpTester { ... }      // 测试完整上下文

@SolonTest(value = App.class, args = "--server.port=-1")
class ServiceTest { ... }                             // 不启动 Web 容器
```

**关键差异：**
- Solon 没有 `@WebMvcTest`、`@DataJpaTest` 等切片注解，统一使用 `@SolonTest`。
- 如需限制加载范围，使用 `@Import` 指定加载的组件类。

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
                       .contentType(MediaType.APPLICATION_JSON).content(body))
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
        String json = path("/api/users/1").get();
        assertNotNull(json);
        assertTrue(json.contains("张三"));
    }

    @Test
    void testCreateUser() throws Throwable {
        String body = "{\"name\":\"李四\",\"email\":\"lisi@example.com\"}";
        String json = path("/api/users").bodyOfJson(body).post();
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
| Content-Type | `.contentType(MediaType.APPLICATION_JSON)` | `.bodyOfJson(body)` (自动设置) |
| 读取响应体 | `.andReturn().getResponse().getContentAsString()` | 直接返回 `String` |

### 3.3 响应验证对比

**Spring Boot：**

```java
mockMvc.perform(get("/api/users/1"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.name").value("张三"))
       .andExpect(header().string("X-Custom", "value"));
```

**Solon：**

```java
HttpResponse resp = path("/api/users/1").exec("GET");
assertEquals(200, resp.code());
assertTrue(resp.bodyAsString().contains("张三"));
```

### 3.4 文件上传测试

**Spring Boot：**

```java
@Test
void testFileUpload() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file", "test.txt", "text/plain", "文件内容".getBytes());
    mockMvc.perform(multipart("/api/files/upload").file(file))
           .andExpect(status().isOk());
}
```

**Solon：**

```java
@Test
void testFileUpload() throws Throwable {
    String json = path("/api/files/upload")
        .file("file", "test.txt", "text/plain", "文件内容".getBytes())
        .post();
    assertNotNull(json);
}
```

## 4. 测试配置迁移

### 4.1 测试专用属性

**Spring Boot：**

```java
// 方式1：注解属性
@TestPropertySource(properties = { "app.cache.enabled=false" })
// 方式2：指定文件
@TestPropertySource(locations = "classpath:test-application.properties")
// 方式3：profile
@ActiveProfiles("test")
```

**Solon：**

```java
// 方式1：通过 properties 传入
@SolonTest(value = App.class, properties = { "app.cache.enabled=false" })
// 方式2：使用 @Import 加载测试配置类
@SolonTest(App.class) @Import(TestConfig.class)
// 方式3：指定环境
@SolonTest(value = App.class, env = "test")
// 方式4：测试资源文件 src/test/resources/app.yml 自动加载
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
        public PaymentService paymentService() { return new MockPaymentService(); }
    }
    @Autowired
    private OrderService orderService;
}
```

**Solon：**

```java
@SolonTest(App.class)
@Import(TestConfig.class)
public class OrderServiceTest {
    @Configuration
    public static class TestConfig {
        @Bean
        public PaymentService paymentService() { return new MockPaymentService(); }
    }
    @Inject
    private OrderService orderService;
}
```

**关键差异：**
- `@TestConfiguration` → `@Configuration`（Solon 中都是普通配置类）。
- 测试配置类需通过 `@Import` 显式导入，不会自动扫描。

## 5. Mock 迁移

### 5.1 手动 Mock（Solon 推荐方式）

**Spring Boot (@MockBean)：**

```java
@SpringBootTest
class UserServiceTest {
    @Autowired
    private UserController userController;

    @MockBean
    private UserRepository userRepository;

    @Test
    void testListUsers() {
        when(userRepository.findAll()).thenReturn(List.of(
            new User(1L, "张三"), new User(2L, "李四")));
        assertEquals(2, userController.list().size());
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
        UserRepository mockRepo = mock(UserRepository.class);
        when(mockRepo.findAll()).thenReturn(List.of(
            new User(1L, "张三"), new User(2L, "李四")));
        Solon.context().wrapAndPut(UserRepository.class, mockRepo);
        assertEquals(2, userController.list().size());
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
    @MockBean private UserService userService;
    @MockBean private OrderRepository orderRepository;
    @Autowired private OrderService orderService;

    @Test
    void testCreateOrder() {
        when(userService.getUser(1L)).thenReturn(new User(1L, "张三"));
        Order order = orderService.createOrder(1L, BigDecimal.valueOf(99.9));
        assertNotNull(order);
        verify(orderRepository, times(1)).save(any(Order.class));
    }
}
```

**Solon：**

```java
@SolonTest(App.class)
class OrderServiceTest {
    @Inject private OrderService orderService;

    @BeforeEach
    void setUp() {
        UserService mockUserService = mock(UserService.class);
        OrderRepository mockOrderRepo = mock(OrderRepository.class);
        when(mockUserService.getUser(1L)).thenReturn(new User(1L, "张三"));
        Solon.context().wrapAndPut(UserService.class, mockUserService);
        Solon.context().wrapAndPut(OrderRepository.class, mockOrderRepo);
    }

    @Test
    void testCreateOrder() {
        Order order = orderService.createOrder(1L, BigDecimal.valueOf(99.9));
        assertNotNull(order);
        OrderRepository repo = Solon.context().getBean(OrderRepository.class);
        verify(repo, times(1)).save(any(Order.class));
    }
}
```

### 5.3 纯单元测试（不启动容器）

**Spring Boot / Solon（完全一致）：**

```java
@ExtendWith(MockitoExtension.class)
class UserServicePureTest {
    @Mock private UserRepository userRepository;
    @InjectMocks private UserServiceImpl userService;

    @Test
    void testFindById() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User(1L, "张三")));
        assertEquals("张三", userService.findById(1L).getName());
    }
}
```

> 纯单元测试（不依赖框架容器）的写法完全一致，无需修改。

## 6. 事务回滚迁移

### 6.1 集成测试事务回滚

**Spring Boot：**

```java
@SpringBootTest
@Transactional
@Rollback
class OrderRepositoryTest {
    @Autowired
    private OrderRepository orderRepository;

    @Test
    void testSave() {
        Order order = new Order();
        order.setUserId(1L);
        order.setAmount(BigDecimal.valueOf(100));
        assertNotNull(orderRepository.save(order).getId());
        // 测试结束后事务自动回滚，不会污染数据库
    }

    @Test @Commit  // 不回滚，实际提交
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
@Rollback  // 同时具备事务管理和回滚语义
class OrderRepositoryTest {
    @Inject
    private OrderRepository orderRepository;

    @Test
    void testSave() {
        Order order = new Order();
        order.setUserId(1L);
        order.setAmount(BigDecimal.valueOf(100));
        assertNotNull(orderRepository.save(order).getId());
    }
}
```

**关键差异：**
- Spring Boot 使用 `@Transactional` + `@Rollback` 组合。
- Solon 使用独立的 `@Rollback` 注解，同时具备事务管理和回滚的语义。

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
        TransactionUtils.execute(() -> {
            orderService.createOrder(...);
        });
    }
}
```
