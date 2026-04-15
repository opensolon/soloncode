# 测试进阶迁移参考

> Spring Boot Test → Solon Test 迁移指南（目标版本：Solon 3.10.x）

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

**关键差异：**
- Solon 使用过滤器/拦截器代替 Spring AOP，但集成测试的写法基本一致。
- 如需单独测试拦截器逻辑，可将其作为普通类进行单元测试。

## 8. 条件测试迁移

### 8.1 条件执行测试

**Spring Boot：**

```java
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class CiOnlyTest {
    @Test
    void testOnlyOnCi() { /* 仅在 CI 环境执行 */ }
}

@SpringBootTest
@DisabledOnOs(OS.WINDOWS)
class LinuxOnlyTest {
    @Test
    void testOnlyOnLinux() { /* 仅在非 Windows 环境执行 */ }
}
```

**Solon：**

```java
@SolonTest(App.class)
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class CiOnlyTest {
    @Test
    void testOnlyOnCi() { /* 仅在 CI 环境执行 */ }
}

@SolonTest(App.class)
@DisabledOnOs(OS.WINDOWS)
class LinuxOnlyTest {
    @Test
    void testOnlyOnLinux() { /* 仅在非 Windows 环境执行 */ }
}
```

> 条件测试注解（`@EnabledIf*`、`@DisabledOnOs` 等）属于 JUnit 5 原生功能，Solon 测试中直接可用，无需修改。

### 8.2 嵌套测试

**Spring Boot / Solon（完全一致）：**

```java
@SolonTest(App.class)  // 或 @SpringBootTest
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
@CsvSource({ "1, 张三", "2, 李四" })
void testUserMapping(Long id, String expectedName) {
    User user = userService.findById(id);
    assertEquals(expectedName, user.getName());
}
```

## 9. 测试生命周期迁移

### 9.1 初始化与清理

**Spring Boot / Solon（完全一致）：**

```java
@SolonTest(App.class)  // 或 @SpringBootTest
class UserServiceTest {
    @Inject  // 或 @Autowired
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
        assertNotNull(Solon.context());
        assertNotNull(Solon.context().getBean(UserService.class));
    }
}
```

**关键差异：**
- `ApplicationContext` → `Solon.context()` 直接访问全局容器。
- 使用 `Solon.context().getBean(Class)` 替代 `context.containsBean(String)`。

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

**关键差异：**
- `WebTestClient` → 同样使用 `HttpTester`（Solon 对响应式接口的测试方式不变）。
- 响应验证从链式 API 改为直接操作响应字符串。

## 11. 数据层测试迁移

### 11.1 Repository 测试

**Spring Boot (@DataJpaTest)：**

```java
@DataJpaTest
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
- 内存数据库配置：

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

```sql
-- src/test/resources/schema.sql（Spring Boot 和 Solon 通用）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100),
    email VARCHAR(100)
);

-- src/test/resources/data.sql（Spring Boot 和 Solon 通用）
INSERT INTO users (name, email) VALUES ('张三', 'zhangsan@example.com');
INSERT INTO users (name, email) VALUES ('李四', 'lisi@example.com');
```

> 数据初始化脚本在 Solon 中同样放在 `src/test/resources/` 下，使用方式一致。

## 12. 完整对照示例

### 12.1 控制器集成测试骨架

**Spring Boot：**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class UserControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private UserService userService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        User user = new User(1L, "张三", "zhangsan@example.com");
        when(userService.findById(1L)).thenReturn(user);
        when(userService.findAll()).thenReturn(List.of(user));
    }

    @Test
    public void testGetUser() throws Exception {
        mockMvc.perform(get("/api/users/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("张三"));
    }

    @Test
    public void testCreateUser() throws Exception {
        User newUser = new User(null, "王五", "wangwu@example.com");
        when(userService.save(any())).thenReturn(new User(3L, "王五", "wangwu@example.com"));
        mockMvc.perform(post("/api/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(newUser)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(3));
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
@SolonTest(App.class)
public class UserControllerTest extends HttpTester {
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        UserService mockService = mock(UserService.class);
        User user = new User(1L, "张三", "zhangsan@example.com");
        when(mockService.findById(1L)).thenReturn(user);
        when(mockService.findAll()).thenReturn(List.of(user));
        when(mockService.save(any())).thenReturn(new User(3L, "王五", "wangwu@example.com"));
        doNothing().when(mockService).deleteById(anyLong());
        Solon.context().wrapAndPut(UserService.class, mockService);
    }

    @Test
    public void testGetUser() throws Throwable {
        String json = path("/api/users/1").get();
        assertNotNull(json);
        assertTrue(json.contains("张三"));
    }

    @Test
    public void testCreateUser() throws Throwable {
        User newUser = new User(null, "王五", "wangwu@example.com");
        String body = objectMapper.writeValueAsString(newUser);
        String json = path("/api/users").bodyOfJson(body).post();
        assertTrue(json.contains("王五"));
    }

    @Test
    public void testGetUserNotFound() throws Throwable {
        HttpResponse resp = path("/api/users/999").exec("GET");
        assertEquals(404, resp.code());
    }
}
```

### 12.2 服务层单元测试

纯单元测试（不依赖框架容器）在 Spring 和 Solon 之间 **完全一致**，无需任何修改。
详见「测试基础迁移参考」5.3 节。

### 12.3 多环境测试配置对比

| 配置项 | Spring Boot | Solon |
|---|---|---|
| 测试配置文件 | `application-test.yml` + `@ActiveProfiles` | `app.yml` 自动加载 |
| 数据源配置 | `spring.datasource.*` | `solon.dataSources.*` |
| DDL 策略 | `spring.jpa.hibernate.ddl-auto=create-drop` | 由框架自动管理 |
| 服务发现 | `spring.cloud.discovery.enabled=false` | 无需额外配置 |

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
| Profile | `@ActiveProfiles("test")` | `@SolonTest(env="test")` 或测试资源文件 |
| 切片测试 | `@WebMvcTest` / `@DataJpaTest` | 无对应（使用 `@Import` 控制） |
| 事务回滚 | `@Transactional` + `@Rollback` | `@Rollback` |
| 条件执行 | JUnit 5 原生注解 | JUnit 5 原生注解（一致） |

### 13.2 @SolonTest 推荐指定启动类

```java
// 不推荐：不指定启动类时，当前测试类将作为启动类
@SolonTest
class MyTest { ... }

// 推荐：显式指定启动类
@SolonTest(App.class)
class MyTest { ... }
```

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
