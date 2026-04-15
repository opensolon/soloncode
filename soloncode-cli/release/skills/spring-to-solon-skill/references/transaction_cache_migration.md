# 事务与缓存迁移参考

> Spring Boot → Solon 数据访问层迁移指南（目标版本：Solon 3.10.x）
>
> 本文档聚焦事务管理、缓存、Redis 及数据访问层差异陷阱四大主题，提供代码对照与差异陷阱标注。

---

## 目录

- [1. 事务管理迁移](#1-事务管理迁移)
- [2. 缓存迁移](#2-缓存迁移)
- [3. Redis 迁移](#3-redis-迁移)
- [4. 数据访问层陷阱与差异清单](#4-数据访问层陷阱与差异清单)

---

## 1. 事务管理迁移

### 1.1 注解对照

| 特性 | Spring | Solon |
|------|--------|-------|
| 注解 | `@Transactional` | `@Transaction` |
| 所属包 | `org.springframework.transaction.annotation` | `org.noear.solon.annotation` |
| 类/方法级别 | 均支持 | 均支持 |
| 默认回滚 | 仅 `RuntimeException` | 所有异常（`Exception`） |

### 1.2 传播机制与隔离级别

**Spring Before：**

```java
@Service
public class OrderService {
    @Transactional  // 默认 REQUIRED
    public void createOrder(Order order) {
        orderMapper.insert(order);
        inventoryService.deduct(order.getProductId(), order.getQuantity());
    }

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED,
        rollbackFor = Exception.class
    )
    public void updateStock(Long productId, int quantity) {
        productMapper.updateStock(productId, quantity);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Product getProduct(Long id) {
        return productMapper.selectById(id);
    }
}
```

**Solon After：**

```java
@Component
public class OrderService {
    @Transaction  // 默认 REQUIRED，与 Spring 一致
    public void createOrder(Order order) {
        orderMapper.insert(order);
        inventoryService.deduct(order.getProductId(), order.getQuantity());
    }

    @Transaction(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED
        // 不需要 rollbackFor，Solon 默认所有异常都回滚
    )
    public void updateStock(Long productId, int quantity) {
        productMapper.updateStock(productId, quantity);
    }

    @Transaction(propagation = Propagation.NOT_SUPPORTED)
    public Product getProduct(Long id) {
        return productMapper.selectById(id);
    }
}
```

> **关键差异**：
> - 注解名 `@Transactional` → `@Transaction`（少了 `al` 后缀）。
> - `Propagation` 和 `Isolation` 枚举值完全兼容，可直接迁移（来自 Solon 包路径）。

### 1.3 回滚策略差异

```java
// Spring Before：默认仅回滚 RuntimeException，需显式指定 rollbackFor
@Transactional(rollbackFor = Exception.class)
public void transferMoney(Long fromId, Long toId, BigDecimal amount) { ... }

// Solon After：默认回滚所有 Exception，无需 rollbackFor
@Transaction
public void transferMoney(Long fromId, Long toId, BigDecimal amount) { ... }
```

> **常见陷阱**：
> - Solon 默认回滚所有 `Exception`，行为更安全，可安全移除 `rollbackFor`。
> - Solon 当前版本**不支持** `noRollbackFor` 属性，需通过编程式事务替代。

### 1.4 编程式事务

```java
// Spring Before
@Service
public class PaymentService {
    @Autowired
    private TransactionTemplate transactionTemplate;

    public void processPayment(Payment payment) {
        transactionTemplate.execute(status -> {
            paymentMapper.insert(payment);
            accountMapper.deduct(payment.getAccountId(), payment.getAmount());
            return null;
        });
    }
}

// Solon After
@Component
public class PaymentService {
    @Inject
    private DataSource dataSource;

    public void processPayment(Payment payment) {
        DataSourceManager.execute(dataSource, () -> {
            paymentMapper.insert(payment);
            accountMapper.deduct(payment.getAccountId(), payment.getAmount());
        });
    }
}
```

---

## 2. 缓存迁移

### 2.1 注解对照

| Spring | Solon | 说明 |
|--------|-------|------|
| `@Cacheable` | `@Cache` | 缓存读取/写入 |
| `@CacheEvict` | `@CacheRemove` | 缓存移除 |
| `@CachePut` | `@CacheRemove` + `@Cache` 组合 | 缓存更新 |
| `@Caching` | 不支持 | 多缓存组合操作 |
| `@CacheConfig` | 不需要 | 类级别缓存配置 |

### 2.2 缓存标签机制

**Spring Before：**

```java
@Service
public class UserService {
    @Cacheable(value = "users", key = "#id")
    public User getUserById(Long id) { return userMapper.selectById(id); }

    @Cacheable(value = "users", key = "#id", unless = "#result == null")
    public User getUserIfExists(Long id) { return userMapper.selectById(id); }

    @CachePut(value = "users", key = "#user.id")
    public User updateUser(User user) { userMapper.updateById(user); return user; }

    @CacheEvict(value = "users", key = "#id")
    public void deleteUser(Long id) { userMapper.deleteById(id); }

    @CacheEvict(value = "users", allEntries = true)
    public void clearAllUsers() { }
}
```

**Solon After：**

```java
@Component
public class UserService {
    // 基于标签（tag）管理，支持 ${参数名} 占位符
    @Cache(tags = "user_${id}")
    public User getUserById(long id) { return userMapper.selectById(id); }

    // 条件缓存使用 condition 属性
    @Cache(tags = "user_${id}", condition = "result != null")
    public User getUserIfExists(long id) { return userMapper.selectById(id); }

    @CacheRemove(tags = "user_${user.id}")
    public User updateUser(User user) { userMapper.updateById(user); return user; }

    @CacheRemove(tags = "user_${id}")
    public void deleteUser(long id) { userMapper.deleteById(id); }

    // 通过标签模式批量清除
    @CacheRemove(tags = "user_*")
    public void clearAllUsers() { }
}
```

> **关键差异**：
> - Spring：`value` + `key` 双维度；Solon：`tags` 单维度，更简洁。
> - Solon `tags` 支持 `${参数名}` 占位符，类似 SpEL 但更轻量。
> - `@CacheRemove` 通过模式匹配批量清除（如 `user_*`），比 `allEntries` 更灵活。

### 2.3 缓存服务接口

```java
// Spring Before
@Autowired private CacheManager cacheManager;
Cache cache = cacheManager.getCache("users");
cache.put("user_1", new User(1L, "Alice"));
User user = cache.get("user_1", User.class);
cache.evict("user_1");

// Solon After
@Inject private CacheService cacheService;  // 内置缓存服务接口
cacheService.store("user_1", new User(1L, "Alice"), 3600);  // 3600秒过期
User user = cacheService.get("user_1", User.class);
cacheService.remove("user_1");
```

> **常见陷阱**：Solon 缓存抽象与 Spring Cache 不兼容，需全量替换注解和编程式调用。先确定缓存后端（如 `solon-cache-jedis`），再替换注解。

---

## 3. Redis 迁移

> **重要提示：Redis 集成场景区分**
>
> **场景一：缓存服务实现（配合 `@Cache` / `@CacheRemove` 使用）**
>
> 使用缓存服务插件：`solon-cache-jedis`（Jedis）、`solon-cache-redisson`（Redisson），自动注册 `CacheService`。
>
> **场景二：Redis 客户端直接使用（编程式操作）**
>
> 使用客户端插件：`solon-data-redisx`（推荐）、`redisson-solon-plugin`、`lettuce-solon-plugin`。
>
> 本章节聚焦**场景二**。

### 3.1 依赖替换

```xml
<!-- Spring Before -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Solon After：方案一（轻量） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-redis</artifactId>
</dependency>

<!-- Solon After：方案二（高性能） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-data-redisx</artifactId>
</dependency>
```

### 3.2 配置迁移

```yaml
# Spring Before
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: your_password
      database: 0
      timeout: 5000
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5

# Solon After
redis:
  host: localhost
  port: 6379
  password: your_password
  database: 0
  timeout: 5000
  maxTotal: 20
  maxIdle: 10
  minIdle: 5
```

### 3.3 RedisTemplate → RedisService

**Spring Before：**

```java
@Service
public class RedisDemoService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public void setString(String key, String value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public String getString(String key) {
        return (String) redisTemplate.opsForValue().get(key);
    }

    public void setHash(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    public Object getHash(String key, String field) {
        return redisTemplate.opsForHash().get(key, field);
    }

    public Boolean deleteKey(String key) { return redisTemplate.delete(key); }

    public Boolean hasKey(String key) { return redisTemplate.hasKey(key); }

    public Boolean expire(String key, long timeout, TimeUnit unit) {
        return redisTemplate.expire(key, timeout, unit);
    }
}
```

**Solon After：**

```java
@Component
public class RedisDemoService {
    @Inject
    private RedisService redisService;  // API 更简洁，直接传秒数

    public void setString(String key, String value, long seconds) {
        redisService.set(key, value, seconds);
    }

    public String getString(String key) {
        return redisService.get(key);  // 直接返回 String
    }

    public void setHash(String key, String field, Object value) {
        redisService.hashSet(key, field, value);
    }

    public Object getHash(String key, String field) {
        return redisService.hashGet(key, field);
    }

    public Boolean deleteKey(String key) { return redisService.delete(key); }

    public Boolean hasKey(String key) { return redisService.exists(key); }

    public Boolean expire(String key, long seconds) {
        return redisService.expire(key, seconds);
    }
}
```

> **关键差异**：
> - `RedisTemplate` → `RedisService`：从 `opsForValue()` 链式调用变为直接方法调用。
> - 序列化：Spring 默认 JDK 序列化；Solon 默认 String 序列化。
> - `RedisService` 由插件自动注册，无需手动配置 Bean。

### 3.4 高性能替代方案：RedisX

```java
@Component
public class RedisXDemoService {
    @Inject
    private RedisX redisX;  // 适用于高并发场景

    public void demo() {
        try (RedisSession session = redisX.openSession()) {
            session.set("key", "value", 3600);
            String val = session.get("key");
            session.delete("key");
        }
    }
}
```

> **说明**：`solon-data-redisx` 提供更精细的连接管理和分布式特性（如分布式锁）。

---

## 4. 数据访问层陷阱与差异清单

| 编号 | 类别 | Spring | Solon | 风险 | 说明 |
|------|------|--------|-------|------|------|
| 1 | 数据源 | 自动配置 | 需手动 `@Bean` | 中 | Solon 不提供数据源自动配置 |
| 2 | 数据源 | `@Primary` | `typed = true` | 低 | 默认数据源标记机制不同 |
| 3 | 数据源 | `driver-class-name` | `driverClassName` | 低 | 配置键命名风格不同 |
| 4 | MyBatis | `@MapperScan` | 自动扫描 | 低 | 需确保 Mapper 在主类包路径下 |
| 5 | MyBatis | `@Mapper` | 无需注解 | 低 | Mapper 接口无需框架注解 |
| 6 | MyBatis | `mapper-locations` | `mappers` | 中 | 键名完全不同，易遗漏 |
| 7 | MyBatis | `@Autowired` | `@Db` | 高 | 注解完全不同，必须全量替换 |
| 8 | 事务 | `@Transactional` | `@Transaction` | 高 | 注解名不同，注意不要误伤 |
| 9 | 事务 | 回滚 RuntimeException | 回滚所有 Exception | 中 | 行为更安全，但可能与原预期不同 |
| 10 | 事务 | `rollbackFor` | 不需要 | 低 | 可安全移除 |
| 11 | 事务 | `noRollbackFor` | 不支持 | 中 | 需编程式事务替代 |
| 12 | 缓存 | `@Cacheable` + `key` | `@Cache` + `tags` | 高 | 缓存机制完全不同，需全量重写 |
| 13 | 缓存 | `@CacheEvict(allEntries)` | `@CacheRemove(tags="*")` | 中 | 批量清除用通配符标签 |
| 14 | 缓存 | `@CachePut` | `@CacheRemove` + `@Cache` | 中 | 更新策略需调整 |
| 15 | Redis | `RedisTemplate` | `RedisService` / `RedisX` | 高 | API 完全不同，需全量重写 |
| 16 | 多数据源 | `@Qualifier` | `@Db("name")` | 中 | 数据源指定方式不同 |
| 17 | JPA | `spring.jpa.*` | `jpa.*` | 低 | 前缀变更，子项基本兼容 |
| 18 | MyBatis Plus | `@Mapper` | 无需注解 | 低 | 与原生 MyBatis 规则一致 |
