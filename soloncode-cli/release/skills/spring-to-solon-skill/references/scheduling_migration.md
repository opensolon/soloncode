# 定时任务迁移参考

> Spring Boot → Solon **单机**定时任务迁移指南（目标版本：跟随 SKILL.md，当前 4.0.3）
>
> | 场景 | 文档 |
> |---|---|
> | 单机 cron / fixedRate / fixedDelay | **本文** `scheduling_migration.md` |
> | 分布式调度（多实例只跑一次） | `cloud_observability_migration.md` 的 `@CloudJob` |
>
> **不要**把所有 Spring `@Scheduled` 默认改成 `@CloudJob`。仅在需要分布式协调时使用 CloudJob。

## 1. 依赖对比

| Spring Boot | Solon | 说明 |
|---|---|---|
| `spring-boot-starter-quartz` | `solon-scheduling-simple` | 轻量调度（推荐，无需外部依赖） |
| `spring-boot-starter-quartz` | `solon-scheduling-quartz` | Quartz 高级调度（需要 Quartz 高级功能时） |

**选择建议**：
- 简单 cron 或固定间隔任务 → `solon-scheduling-simple`（推荐，启动更快）
- 需要 Quartz 高级特性（持久化、集群、 misfire 策略等）→ `solon-scheduling-quartz`

```xml
<!-- 轻量调度（推荐） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-scheduling-simple</artifactId>
</dependency>

<!-- Quartz 调度（按需） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-scheduling-quartz</artifactId>
</dependency>
```

## 2. 基本定时任务

### 2.1 @Scheduled 注解

Solon 的 `@Scheduled` 注解名称与 Spring 相同，用法基本一致。

#### Before — Spring

```java
@Component
@EnableScheduling   // Spring 需要显式启用
public class ScheduledTasks {

    @Scheduled(fixedRate = 5000)
    public void reportCurrentTime() {
        System.out.println("现在时间: " + LocalDateTime.now());
    }

    @Scheduled(cron = "0 0/1 * * * ?")
    public void reportEveryMinute() {
        System.out.println("每分钟执行一次");
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 3000)
    public void delayedTask() {
        System.out.println("首次延迟3秒，之后每次执行完等待10秒");
    }
}
```

#### After — Solon

```java
@Component
public class ScheduledTasks {
    // 无需 @EnableScheduling，引入依赖后自动生效

    @Scheduled(fixedRate = 5000)
    public void reportCurrentTime() {
        System.out.println("现在时间: " + LocalDateTime.now());
    }

    @Scheduled(cron = "0 0/1 * * * ?")
    public void reportEveryMinute() {
        System.out.println("每分钟执行一次");
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 3000)
    public void delayedTask() {
        System.out.println("首次延迟3秒，之后每次执行完等待10秒");
    }
}
```

> **关键差异**：
> - Solon **不需要** `@EnableScheduling`，引入 `solon-scheduling-simple` 或 `solon-scheduling-quartz` 依赖后自动启用。
> - `@Scheduled` 注解的 `fixedRate`、`fixedDelay`、`cron`、`initialDelay` 等属性用法与 Spring 完全一致。
> - 任务方法必须是 **public void** 且无参数（与 Spring 要求相同）。

### 2.2 定时任务配置

#### 编程式配置

Solon 支持通过 `Scheduled` 注解属性和 `app.yml` 配置两种方式：

```yaml
# app.yml — 定时任务全局配置
solon.scheduling:
  poolSize: 4           # 调度线程池大小（默认 2）
  enabled: true         # 是否启用定时任务（默认 true）
```

## 3. cron 表达式

Solon 使用标准 cron 表达式（6 字段或 7 字段），与 Spring 一致：

| 字段 | 范围 | 特殊字符 |
|---|---|---|
| 秒 | 0-59 | , - * / |
| 分 | 0-59 | , - * / |
| 时 | 0-23 | , - * / |
| 日 | 1-31 | , - * ? / L W |
| 月 | 1-12 或 JAN-DEC | , - * / |
| 周 | 1-7 或 SUN-SAT | , - * ? / L # |
| 年（可选） | 1970-2099 | , - * / |

```java
@Scheduled(cron = "0 0/5 * * * ?")   // 每5分钟执行
@Scheduled(cron = "0 0 9-18 * * ?")  // 每天9点到18点每小时正点执行
@Scheduled(cron = "0 0 0 * * ?")     // 每天凌晨0点执行
```

## 4. 高级调度（solon-scheduling-quartz）

当使用 `solon-scheduling-quartz` 时，支持 Quartz 的所有高级特性：

```java
@Component
public class AdvancedScheduledTasks {

    // 使用 Quartz 的 misfire 策略
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Shanghai")
    public void dailyReport() {
        // 支持时区设置
    }

    // 持久化 Job 配置（需配置 Quartz 数据源）
    // 参见 solon-scheduling-quartz 文档
}
```

## 5. 动态定时任务（编程式注册）

Solon 支持通过 API 动态注册和取消定时任务：

```java
@Component
public class DynamicScheduler {

    @Inject
    ScheduledExecutorService executor;  // 注入调度器

    public void registerTask(String taskId, Runnable task, long intervalMs) {
        executor.scheduleAtFixedRate(task, 0, intervalMs, TimeUnit.MILLISECONDS);
    }
}
```

## 6. 陷阱与差异

| 编号 | 陷阱描述 | 说明 |
|---|---|---|
| 1 | **无需 @EnableScheduling** | Solon 引入依赖后自动启用，多写 `@EnableScheduling` 会报编译错误（不存在此注解）。 |
| 2 | **依赖选择** | `solon-scheduling-simple` 和 `solon-scheduling-quartz` 二选一，不要同时引入。 |
| 3 | **方法签名** | 任务方法必须是 `public void` 无参数，与 Spring 要求一致。 |
| 4 | **@Scheduled 注解冲突** | Solon 的 `@Scheduled` 包路径为 **`org.noear.solon.scheduling.annotation.Scheduled`**，不要混用 Spring 的 `org.springframework.scheduling.annotation.Scheduled`。 |
| 5 | **线程池** | Solon 默认使用 2 个线程的调度池，可通过 `solon.scheduling.poolSize` 配置调整。 |

## 7. 迁移检查清单

- [ ] POM：移除 `spring-boot-starter-quartz`，添加 `solon-scheduling-simple`（推荐）或 `solon-scheduling-quartz`
- [ ] 移除 `@EnableScheduling` 注解（Solon 自动启用）
- [ ] 替换 `@Scheduled` 的 import 为 **`org.noear.solon.scheduling.annotation.Scheduled`**
- [ ] 确认任务方法为 `public void` 无参数
- [ ] 按需在 `app.yml` 中配置 `solon.scheduling.poolSize`
- [ ] 若需多实例防重 / 分布式调度，改读 `cloud_observability_migration.md`（`@CloudJob`），勿与单机 `@Scheduled` 混为一谈