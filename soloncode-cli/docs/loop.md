# SolonCode CLI 定时任务功能方案（/loop）

> 对标 Claude Code 的 Scheduled Tasks 功能

## 一、功能目标

在会话内提供定时执行提示词的能力，支持四种用法：

```
/loop 5m check if the deployment finished     # 固定间隔循环
/loop check CI status and address comments    # 自动间隔（AI 智能调整）
/loop                                         # 执行默认维护提示词（或列出活跃任务）
/loop ls                                      # 列出所有活跃任务
/loop stop <id>                               # 停止指定任务
/loop stop-all                                # 停止所有任务
```

## 二、架构分析（基于现有代码）

### 2.1 现有命令体系

```
Command 接口 → XxxCommand 实现 → CommandRegistry.register() → Configurator 注册
                                    ↓
                    CliShell.isCommand() → 查找 Command → 构建 CliCommandContext → command.execute(ctx)
                    WsGate.onMessage()   → 查找 Command → 构建 WebCommandContext → command.execute(ctx)
```

- `CommandContext.runAgentTask(prompt, model)` 是回调委托，CLI 端为同步执行，Web 端为 reactive 流
- `CommandContext.println(text)` 用于向终端输出信息
- `CommandType` 枚举：`SYSTEM`（系统命令）、`CONFIG`（配置命令）、`AGENT`（会触发 AI 任务）

### 2.2 CLI 主循环结构（关键约束）

```java
// CliShell.run() — 当前是阻塞式
while (true) {
    input = reader.readLine("> ");          // ← 阻塞！定时器无法插入
    if (!isCommand(session, input)) {
        performAgentTask(session, input, null);  // ← 同步等待 AI 完成
    }
}
```

**核心问题**：`reader.readLine()` 会无限阻塞，定时器触发的任务无法在主循环中被拾起执行。

### 2.3 Web 端结构（WsGate）

```java
// WsGate.onMessage() — 纯响应式
kernel.prompt(prompt).session(session).stream()
      .doOnNext(chunk -> socket.send(...))
      .subscribe();
```

Web 端无主循环，每次请求独立处理，定时任务需要独立提交 reactive 流。

### 2.4 会话状态

- `AgentSession.attrs()` — 已有机制，可存储临时数据（当前用于存 `disposable`、`cwd` 等）
- `AgentSession` 通过 `FileAgentSession` 持久化到 `<workspace>/.soloncode/sessions/<sessionId>/`
- 进程退出通过 `ExitCommand.execute()` → `System.exit(0)` 触发

## 三、方案设计

### 3.1 核心思路 — IJobManager 动态调度 + JSON 持久化

**两层架构**：

```
┌─────────────────────────────────────────────────────────────────┐
│                       LoopScheduler（管理器）                      │
│                                                                 │
│  ┌──────────────────┐    ┌──────────────────────────────────┐  │
│  │  JSON 持久化层    │    │  IJobManager 动态调度层            │  │
│  │                  │    │                                  │  │
│  │  tasks.json      │◄──►│  jobAdd / jobRemove / jobExists   │  │
│  │  (任务注册表)     │    │  (运行时调度)                     │  │
│  │                  │    │                                  │  │
│  │  启动时加载       │    │  触发时 → pendingQueue            │  │
│  │  变更时写入       │    │  CLI 主循环消费执行                │  │
│  └──────────────────┘    └──────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**关键决策**：

1. **IJobManager 只管调度触发，不管任务元数据** — 任务注册信息（prompt、间隔、过期时间等）存在 JSON 文件中
2. **不使用 Solon yaml 配置覆盖** — 所有任务的 CRUD 由 `LoopScheduler` 自己通过 `IJobManager` 的 `jobAdd`/`jobRemove` 管理
3. **JSON 是唯一的数据源** — 启动时从 JSON 加载任务列表 → 逐一调用 `jobAdd` 注册到 IJobManager；变更时先写 JSON 再操作 IJobManager
4. **进程重启可恢复** — `/resume` 时读取 JSON，过滤未过期任务，重新注册调度

**为什么需要 JSON 持久化（而不只是 session.attrs 内存）**：

- Claude Code 的 `/loop` 任务可通过 `--resume` 恢复，说明任务在进程退出后仍然存在
- `session.attrs()` 是内存态，进程退出即丢失
- JSON 文件跟随会话目录（`~/.soloncode/sessions/<sessionId>/loop_tasks.json`），天然与会话绑定

### 3.2 依赖新增（pom.xml）

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-scheduling-simple</artifactId>
</dependency>
```

**启动类增加**：

```java
@EnableScheduling  // 启用调度框架
public class App { ... }
```

### 3.3 IJobManager 关键 API 映射

| loop 操作 | IJobManager API | 说明 |
|-----------|-----------------|------|
| 注册循环任务 | `jobAdd(name, scheduled, handler)` | `ScheduledAnno.fixedDelay(intervalMs)` |
| 停止任务 | `jobRemove(name)` | 移除并自动停止 |
| 暂停任务 | `jobStop(name)` | 暂不删除，可恢复 |
| 恢复任务 | `jobStart(name, data)` | 恢复已暂停的任务 |
| 判断任务存在 | `jobExists(name)` | 防重复注册 |

**关键选择**：使用 `fixedDelay`（串行）而非 `fixedRate`（并行）。原因：loop 任务必须等上一轮 AI 执行完成后才开始计算下一次间隔，避免并发问题。

### 3.4 新增文件清单

```
soloncode-cli/src/main/java/org/noear/solon/codecli/
├── core/
│   ├── LoopTask.java          // 定时任务模型（含 JSON 序列化支持）（~100行）
│   └── LoopScheduler.java     // 调度管理器（IJobManager + JSON 持久化）（~200行）
├── command/builtin/
│   └── LoopCommand.java       // /loop 命令（~120行）
```

改动文件：
- `pom.xml` — 新增 `solon-scheduling-simple` 依赖（+5行）
- `App.java` — 启动类增加 `@EnableScheduling`（+1行注解）
- `Configurator.java` — 注册 LoopCommand + 创建 LoopScheduler（+5行）
- `CliShell.java` — 主循环适配（改动 run() 方法，约20行）

运行时生成文件：
- `<workspace>/.soloncode/sessions/<sessionId>/loop_tasks.json` — 任务注册表

### 3.5 LoopTask 模型

```java
package org.noear.solon.codecli.core;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务模型 — 支持 JSON 序列化/反序列化
 *
 * 注意：running 和 cancelled 是运行时状态，不参与 JSON 序列化
 */
public class LoopTask {
    private String id;               // 唯一标识（8位），同时作为 IJobManager 的 job name 前缀
    private String prompt;           // 要执行的提示词
    private int intervalMinutes;     // 间隔分钟数，0=一次性
    private boolean autoInterval;    // 是否自动间隔
    private String createdAt;        // 创建时间（ISO-8601 字符串，方便 JSON）
    private String expireAt;         // 过期时间（createdAt + 7天）
    private String lastResult;       // 最后执行结果摘要（可持久化，便于 /loop ls 展示）
    private String lastExecutedAt;   // 最后执行时间

    // ====== 运行时状态（不序列化） ======
    private transient AtomicBoolean running = new AtomicBoolean(false);
    private transient AtomicBoolean cancelled = new AtomicBoolean(false);

    // JSON 反序列化需要无参构造器
    public LoopTask() {}

    // 工厂方法
    public static LoopTask ofFixed(String prompt, int intervalMinutes) {
        LoopTask t = new LoopTask();
        t.id = generateId();
        t.prompt = prompt;
        t.intervalMinutes = intervalMinutes;
        t.autoInterval = false;
        t.createdAt = Instant.now().toString();
        t.expireAt = Instant.now().plusSeconds(7 * 24 * 3600).toString();
        return t;
    }

    public static LoopTask ofAuto(String prompt) {
        LoopTask t = new LoopTask();
        t.id = generateId();
        t.prompt = prompt;
        t.intervalMinutes = 5; // 初始5分钟
        t.autoInterval = true;
        t.createdAt = Instant.now().toString();
        t.expireAt = Instant.now().plusSeconds(7 * 24 * 3600).toString();
        return t;
    }

    public static LoopTask ofDefault(String defaultPrompt) {
        return ofAuto(defaultPrompt); // 默认维护提示词，也用自动间隔
    }

    public static LoopTask ofOnce(String prompt, long delayMinutes) {
        LoopTask t = new LoopTask();
        t.id = generateId();
        t.prompt = prompt;
        t.intervalMinutes = 0; // 0 标记一次性
        t.autoInterval = false;
        t.createdAt = Instant.now().toString();
        t.expireAt = Instant.now().plusSeconds(delayMinutes * 60 + 60).toString(); // 过期时间=触发时间+1分钟
        return t;
    }

    /** 生成 8 位 ID */
    private static String generateId() {
        return Long.toHexString(System.currentTimeMillis()).substring(Math.max(0, Long.toHexString(System.currentTimeMillis()).length() - 8));
    }

    /** 生成 IJobManager 使用的 job name */
    public String getJobName() { return "loop_" + id; }

    public boolean isExpired() {
        return Instant.now().isAfter(Instant.parse(expireAt));
    }

    public boolean isRunning() { return running.get(); }

    /** CAS 防重入：只有非运行态才能置为运行 */
    public boolean tryStart() { return running.compareAndSet(false, true); }
    public void finish() { running.set(false); }
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    /** 反序列化后重建 transient 字段 */
    public void initTransient() {
        this.running = new AtomicBoolean(false);
        this.cancelled = new AtomicBoolean(false);
    }

    // getter/setter 省略（使用 Lombok 或手写）
    public String getId() { return id; }
    public String getPrompt() { return prompt; }
    public int getIntervalMinutes() { return intervalMinutes; }
    public boolean isAutoInterval() { return autoInterval; }
    public String getCreatedAt() { return createdAt; }
    public String getExpireAt() { return expireAt; }
    public String getLastResult() { return lastResult; }
    public void setLastResult(String lastResult) { this.lastResult = lastResult; }
    public String getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(String lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }
    public void setIntervalMinutes(int intervalMinutes) { this.intervalMinutes = intervalMinutes; }
}
```

### 3.6 LoopScheduler 调度管理器（核心）

```java
package org.noear.solon.codecli.core;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.scheduling.scheduled.IJobManager;
import org.noear.solon.scheduling.scheduled.JobManager;
import org.noear.solon.scheduling.ScheduledAnno;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 定时任务调度管理器
 *
 * 职责：
 * 1. 管理任务元数据的 JSON 持久化（load / save）
 * 2. 通过 IJobManager 动态注册/移除调度
 * 3. 管理会话级待执行队列（pendingQueue）
 * 4. 支持进程重启后恢复未过期任务
 */
public class LoopScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LoopScheduler.class);
    private static final int MAX_TASKS = 50;
    private static final String TASKS_FILE = "loop_tasks.json";

    // Solon 原生调度管理器
    private final IJobManager jobManager;

    // 会话级待执行队列（挂在 session.attrs 上）
    private static final String ATTR_PENDING_QUEUE = "loop_pending_queue";

    public LoopScheduler() {
        this.jobManager = JobManager.getInstance();
    }

    // ==================== JSON 持久化 ====================

    /**
     * 获取任务 JSON 文件路径
     * 位于会话目录下：<workspace>/.soloncode/sessions/<sessionId>/loop_tasks.json
     */
    private Path getTasksFilePath(AgentSession session) {
        // FileAgentSession 的数据目录
        return Paths.get(session.getSessionDir(), TASKS_FILE);
    }

    /**
     * 从 JSON 文件加载任务列表
     */
    private List<LoopTask> loadFromJson(AgentSession session) {
        Path file = getTasksFilePath(session);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file);
            // 使用 Solon 内置的 OGNL/JSON 解析，或 SnackJson
            // List<LoopTask> tasks = ONode.deserialize(json, new TypeRef<List<LoopTask>>(){});
            List<LoopTask> tasks = deserialize(json);
            // 重建 transient 字段
            tasks.forEach(LoopTask::initTransient);
            return tasks;
        } catch (IOException e) {
            LOG.warn("Failed to load loop tasks from {}: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 将任务列表保存到 JSON 文件
     */
    private void saveToJson(AgentSession session, List<LoopTask> tasks) {
        Path file = getTasksFilePath(session);
        try {
            // 确保目录存在
            Files.createDirectories(file.getParent());
            String json = serialize(tasks);
            Files.writeString(file, json);
        } catch (IOException e) {
            LOG.warn("Failed to save loop tasks to {}: {}", file, e.getMessage());
        }
    }

    // ==================== 会话恢复（/resume 时调用） ====================

    /**
     * 恢复会话的任务 — 从 JSON 加载，过滤过期任务，重新注册到 IJobManager
     *
     * 在 CliShell.prepare() 或 ResumeCommand 中调用
     */
    public void restore(AgentSession session) {
        List<LoopTask> tasks = loadFromJson(session);
        List<LoopTask> alive = new ArrayList<>();

        for (LoopTask task : tasks) {
            if (task.isExpired() || task.isCancelled()) {
                continue; // 跳过过期和已取消
            }
            // 重新注册到 IJobManager
            if (!jobManager.jobExists(task.getJobName())) {
                registerToJobManager(session, task);
            }
            alive.add(task);
        }

        // 回写 JSON（去掉过期任务）
        if (alive.size() != tasks.size()) {
            saveToJson(session, alive);
        }

        if (!alive.isEmpty()) {
            LOG.info("Restored {} loop tasks for session {}", alive.size(), session.getSessionId());
        }
    }

    // ==================== 任务注册（/loop 时调用） ====================

    /**
     * 注册循环任务
     *
     * 流程：创建 LoopTask → 注册到 IJobManager → 加入内存列表 → 持久化到 JSON
     */
    public LoopTask schedule(AgentSession session, LoopTask task) {
        List<LoopTask> tasks = loadFromJson(session);
        if (tasks.size() >= MAX_TASKS) {
            throw new IllegalStateException("已达到最大任务数限制 (" + MAX_TASKS + ")");
        }

        // 清理过期任务
        tasks = cleanExpired(session, tasks);

        // 注册到 IJobManager
        registerToJobManager(session, task);

        // 持久化
        tasks.add(task);
        saveToJson(session, tasks);

        return task;
    }

    /**
     * 将任务注册到 IJobManager（核心调度动作）
     */
    private void registerToJobManager(AgentSession session, LoopTask task) {
        long intervalMs = task.getIntervalMinutes() * 60 * 1000L;

        // 一次性任务：用极大的 fixedDelay + 精确的 initialDelay
        // 循环任务：fixedDelay（串行）
        ScheduledAnno scheduled = new ScheduledAnno()
                .fixedDelay(intervalMs > 0 ? intervalMs : Long.MAX_VALUE)
                .initialDelay(intervalMs > 0 ? intervalMs : intervalMs);

        jobManager.jobAdd(task.getJobName(), scheduled, (ctx) -> {
            onTrigger(session, task);
        });
    }

    /**
     * 重新注册任务（用于动态调整间隔）
     */
    private void reRegisterToJobManager(AgentSession session, LoopTask task) {
        if (jobManager.jobExists(task.getJobName())) {
            jobManager.jobRemove(task.getJobName());
        }
        registerToJobManager(session, task);
    }

    // ==================== 定时触发回调 ====================

    /**
     * 定时触发 — 将任务放入待执行队列
     */
    private void onTrigger(AgentSession session, LoopTask task) {
        if (task.isExpired() || task.isCancelled()) {
            removeTask(session, task);
            return;
        }
        // 防重入：上一个还没执行完则跳过
        if (!task.tryStart()) {
            return;
        }
        // 投入待执行队列，由主循环（CLI）或 reactive 流（Web）消费
        getPendingQueue(session).add(task);
    }

    // ==================== 任务管理 ====================

    /**
     * 停止指定任务
     */
    public void stop(AgentSession session, String taskId) {
        List<LoopTask> tasks = loadFromJson(session);
        tasks.stream().filter(t -> t.getId().equals(taskId)).findFirst().ifPresent(t -> {
            t.cancel();
            if (jobManager.jobExists(t.getJobName())) {
                jobManager.jobRemove(t.getJobName());
            }
            tasks.remove(t);
            saveToJson(session, tasks);
        });
    }

    /**
     * 停止所有任务
     */
    public void stopAll(AgentSession session) {
        List<LoopTask> tasks = loadFromJson(session);
        tasks.forEach(t -> {
            t.cancel();
            if (jobManager.jobExists(t.getJobName())) {
                jobManager.jobRemove(t.getJobName());
            }
        });
        tasks.clear();
        saveToJson(session, tasks);
    }

    /**
     * 列出活跃任务
     */
    public List<LoopTask> listActive(AgentSession session) {
        List<LoopTask> tasks = loadFromJson(session);
        List<LoopTask> alive = tasks.stream()
                .filter(t -> !t.isCancelled() && !t.isExpired())
                .collect(Collectors.toList());

        // 如果有变化，回写（清理过期）
        if (alive.size() != tasks.size()) {
            saveToJson(session, alive);
            // 同时清理 IJobManager 中过期的
            tasks.stream()
                .filter(t -> t.isCancelled() || t.isExpired())
                .forEach(t -> {
                    if (jobManager.jobExists(t.getJobName())) {
                        jobManager.jobRemove(t.getJobName());
                    }
                });
        }

        return alive;
    }

    /**
     * 更新任务（用于动态调整间隔后保存）
     */
    public void updateTask(AgentSession session, LoopTask updated) {
        List<LoopTask> tasks = loadFromJson(session);
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(updated.getId())) {
                tasks.set(i, updated);
                break;
            }
        }
        saveToJson(session, tasks);
        reRegisterToJobManager(session, updated);
    }

    /**
     * 清理过期任务
     */
    private List<LoopTask> cleanExpired(AgentSession session, List<LoopTask> tasks) {
        List<LoopTask> alive = tasks.stream()
                .filter(t -> {
                    if (t.isExpired() || t.isCancelled()) {
                        if (jobManager.jobExists(t.getJobName())) {
                            jobManager.jobRemove(t.getJobName());
                        }
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (alive.size() != tasks.size()) {
            saveToJson(session, alive);
        }
        return alive;
    }

    private void removeTask(AgentSession session, LoopTask task) {
        task.cancel();
        if (jobManager.jobExists(task.getJobName())) {
            jobManager.jobRemove(task.getJobName());
        }
        List<LoopTask> tasks = loadFromJson(session);
        tasks.removeIf(t -> t.getId().equals(task.getId()));
        saveToJson(session, tasks);
    }

    // ==================== 待执行队列 ====================

    /**
     * 获取/初始化待执行队列（CLI 端使用）
     */
    @SuppressWarnings("unchecked")
    public Queue<LoopTask> getPendingQueue(AgentSession session) {
        return (Queue<LoopTask>) session.attrs()
                .computeIfAbsent(ATTR_PENDING_QUEUE, k -> new ConcurrentLinkedQueue<>());
    }

    // ==================== 会话关闭 ====================

    /**
     * 关闭会话的所有 IJobManager 调度（不删除 JSON，以便 /resume 恢复）
     */
    public void shutdown(AgentSession session) {
        List<LoopTask> tasks = loadFromJson(session);
        tasks.forEach(t -> {
            if (jobManager.jobExists(t.getJobName())) {
                jobManager.jobRemove(t.getJobName());
            }
        });
        // 注意：不删除 JSON 文件，保留用于 /resume
    }

    // ==================== JSON 序列化（简化版） ====================
    // 实际实现可用 SnackJson 或 Solon 内置的 ONode

    private List<LoopTask> deserialize(String json) {
        // 用 SnackJson: ONode.deserialize(json, new TypeRef<List<LoopTask>>(){});
        // 或用 Solon 的 PropsConverter
        // ... 具体实现取决于项目已引入的 JSON 库
        return new ArrayList<>();
    }

    private String serialize(List<LoopTask> tasks) {
        // 用 SnackJson: new ONode(tasks).toJson();
        // ... 具体实现取决于项目已引入的 JSON 库
        return "[]";
    }
}
```

**JSON 文件示例（loop_tasks.json）**：

```json
[
  {
    "id": "a1b2c3d4",
    "prompt": "check if the deployment finished",
    "intervalMinutes": 5,
    "autoInterval": false,
    "createdAt": "2026-04-27T10:30:00Z",
    "expireAt": "2026-05-04T10:30:00Z",
    "lastResult": "deployment still in progress",
    "lastExecutedAt": "2026-04-27T10:35:00Z"
  },
  {
    "id": "e5f6g7h8",
    "prompt": "check CI status and address comments",
    "intervalMinutes": 5,
    "autoInterval": true,
    "createdAt": "2026-04-27T11:00:00Z",
    "expireAt": "2026-05-04T11:00:00Z",
    "lastResult": null,
    "lastExecutedAt": null
  }
]
```

### 3.7 对比原方案的改进

| 对比项 | 原方案（纯内存） | 新方案（JSON 持久化 + IJobManager） |
|--------|----------------|-----------------------------------|
| 任务存储 | `session.attrs()` 内存 | JSON 文件 + 内存双写 |
| 进程重启恢复 | 不支持，任务丢失 | `/resume` 自动从 JSON 恢复未过期任务 |
| IJobManager 用途 | 同时管调度和任务元数据 | 只管调度触发，任务元数据在 JSON |
| Solon yaml 配置覆盖 | 依赖 `solon.scheduling.job.{name}` | 不依赖，由 LoopScheduler 自行管理 |
| 并发安全 | attrs 的 ConcurrentHashMap | JSON 文件单写 + IJobManager 线程安全 |
| 数据一致性 | 无持久化，无需一致 | JSON 是 source of truth，IJobManager 是运行时投影 |
| 会话关闭 | stopAll 清除一切 | 只移除 IJobManager 调度，保留 JSON 供恢复 |

### 3.8 LoopCommand 命令

```java
package org.noear.solon.codecli.command.builtin;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.CommandType;
import org.noear.solon.codecli.core.LoopScheduler;
import org.noear.solon.codecli.core.LoopTask;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoopCommand implements Command {
    private final LoopScheduler scheduler;

    public LoopCommand(LoopScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public String name() { return "loop"; }

    @Override
    public String description() { return "定时任务管理（循环执行提示词）"; }

    @Override
    public CommandType type() { return CommandType.SYSTEM; }

    @Override
    public boolean cliOnly() { return true; } // 第一版仅 CLI

    @Override
    public boolean execute(CommandContext ctx) throws Exception {
        List<String> args = ctx.getArgs();

        // /loop ls — 列出任务
        if (args.size() == 1 && "ls".equals(args.get(0))) {
            return printTaskList(ctx);
        }

        // /loop stop <id> — 停止任务
        if (args.size() == 2 && "stop".equals(args.get(0))) {
            AgentSession session = ctx.getSession();
            scheduler.stop(session, args.get(1));
            ctx.println("Stopped: " + args.get(1));
            return true;
        }

        // /loop stop-all — 停止所有
        if (args.size() == 1 && "stop-all".equals(args.get(0))) {
            scheduler.stopAll(ctx.getSession());
            ctx.println("All loop tasks stopped.");
            return true;
        }

        // /loop 5m <prompt> — 固定间隔
        if (args.size() >= 2) {
            Matcher m = Pattern.compile("^(\\d+)(m|min|minutes?|h|hours?|s|seconds?)$").matcher(args.get(0));
            if (m.matches()) {
                int minutes = parseMinutes(m);
                String prompt = String.join(" ", args.subList(1, args.size()));
                LoopTask task = LoopTask.ofFixed(prompt, minutes);
                scheduler.schedule(ctx.getSession(), task);
                ctx.println("Loop registered [" + task.getId() + "]: every " + minutes + "m — " + prompt);
                return true;
            }
        }

        // /loop <prompt> — 自动间隔
        if (args.size() >= 1 && !"stop".equals(args.get(0))) {
            String prompt = String.join(" ", args);
            LoopTask task = LoopTask.ofAuto(prompt);
            scheduler.schedule(ctx.getSession(), task);
            ctx.println("Loop registered [" + task.getId() + "]: auto interval — " + prompt);
            return true;
        }

        // /loop（无参数）— 有任务则列出，无任务则执行默认维护
        List<LoopTask> active = scheduler.listActive(ctx.getSession());
        if (!active.isEmpty()) {
            return printTaskList(ctx);
        } else {
            String defaultPrompt = loadDefaultPrompt(ctx);
            LoopTask task = LoopTask.ofDefault(defaultPrompt);
            scheduler.schedule(ctx.getSession(), task);
            ctx.println("Loop registered [" + task.getId() + "]: default maintenance — " + defaultPrompt);
            return true;
        }
    }

    private int parseMinutes(Matcher m) {
        int value = Integer.parseInt(m.group(1));
        String unit = m.group(2);
        if (unit.startsWith("h")) return value * 60;
        if (unit.startsWith("s")) return Math.max(1, value / 60 + (value % 60 > 0 ? 1 : 0)); // 秒向上取整到分钟
        return value; // m/min/minutes
    }

    private boolean printTaskList(CommandContext ctx) {
        List<LoopTask> tasks = scheduler.listActive(ctx.getSession());
        if (tasks.isEmpty()) {
            ctx.println("No active loop tasks.");
        } else {
            ctx.println("Active loop tasks:");
            for (LoopTask t : tasks) {
                String interval = t.getIntervalMinutes() > 0
                        ? "every " + t.getIntervalMinutes() + "m"
                        : "once";
                if (t.isAutoInterval()) interval = "auto (" + t.getIntervalMinutes() + "m)";
                String last = t.getLastExecutedAt() != null
                        ? " (last: " + t.getLastExecutedAt() + ")" : "";
                String result = t.getLastResult() != null
                        ? " → " + t.getLastResult() : "";
                ctx.println("  [" + t.getId() + "] " + interval + " — " + t.getPrompt() + result + last);
            }
        }
        return true;
    }

    /**
     * 加载默认维护提示词
     * 优先级：.soloncode/loop.md（项目级） > ~/.soloncode/loop.md（用户级） > 内置默认
     */
    private String loadDefaultPrompt(CommandContext ctx) {
        // ... 尝试从文件加载，降级到内置默认
        return "Check for any unfinished tasks, unresolved review comments, CI failures, or code cleanup opportunities.";
    }
}
```

### 3.9 CLI 主循环适配（关键改动）

CliShell.run() 需要从阻塞式 `reader.readLine()` 改为非阻塞轮询式，以支持定时任务的拾起：

```java
@Override
public void run() {
    AgentSession session = prepare(agentProps.getSessionId());
    LoopScheduler loopScheduler = agentRuntime.getLoopScheduler();

    // 恢复上次的 loop 任务（如果有）
    loopScheduler.restore(session);

    while (true) {
        try {
            // 1. 先检查是否有定时任务待执行
            Queue<LoopTask> pendingQueue = loopScheduler.getPendingQueue(session);
            LoopTask pendingTask;
            while ((pendingTask = pendingQueue.poll()) != null) {
                if (!pendingTask.isExpired() && !pendingTask.isCancelled()) {
                    terminal.writer().println("\n" + CYAN + "[Loop " + pendingTask.getId() + "] " + RESET + pendingTask.getPrompt());
                    try {
                        performAgentTask(session, pendingTask.getPrompt(), null);
                        pendingTask.setLastResult("completed");
                        pendingTask.setLastExecutedAt(Instant.now().toString());
                        loopScheduler.updateTask(session, pendingTask);
                    } finally {
                        pendingTask.finish();
                    }
                } else {
                    pendingTask.finish();
                }
            }

            // 2. 非阻塞读取用户输入（超时 1 秒，让出主循环检查 pendingQueue）
            terminal.writer().println();
            terminal.writer().print(BOLD + CYAN + "User" + RESET);
            terminal.writer().println();
            terminal.flush();

            String input = null;
            Attributes originalAttributes = terminal.getAttributes();
            try {
                terminal.enterRawMode();
                StringBuilder buf = new StringBuilder();
                long deadline = System.currentTimeMillis() + 1000; // 1秒超时
                while (System.currentTimeMillis() < deadline) {
                    int c = terminal.reader().read(100);
                    if (c == -1) break;
                    if (c == '\r' || c == '\n') {
                        input = buf.toString().trim();
                        break;
                    }
                    if (c == 27) { // ESC
                        buf.setLength(0);
                        break;
                    }
                    buf.append((char) c);
                }
            } finally {
                terminal.setAttributes(originalAttributes);
            }

            if (input == null || input.isEmpty()) {
                continue;
            }

            if (!isCommand(session, input)) {
                performAgentTask(session, input, null);
            }
        } catch (Throwable e) {
            terminal.writer().println("\n" + RED + "! Error: " + RESET + e.getMessage());
        }
    }
}
```

> **备选简化方案**（对 CliShell 改动更小）：
> 不改主循环的 `reader.readLine()`，而是让 LoopScheduler 的 `onTrigger` 直接在调度线程中通过 JLine 的 `terminal.writer()` 输出提示，然后调用 `RunUtil.async()` 异步执行 AI 任务。但这要求 `performAgentTask` 支持并发安全，改动面更大。因此推荐上述轮询方案。

### 3.10 Configurator 注册

```java
// 新增 Bean：
@Bean
public LoopScheduler loopScheduler() {
    return new LoopScheduler();
}

// 在 agentRuntime() 方法中新增：
engine.getCommandRegistry().register(new LoopCommand(loopScheduler()));
```

### 3.11 会话生命周期

| 事件 | 处理方式 |
|------|---------|
| CLI 退出（`/exit`） | `System.exit(0)` → Solon 容器关闭 → IJobManager 自动 stop |
| CLI 退出（Ctrl+D / EOF） | CliShell.run() break → 进程退出 → JSON 文件保留 |
| 会话恢复（`/resume`） | `loopScheduler.restore(session)` 从 JSON 加载未过期任务，重新 `jobAdd` |
| 7天过期 | 每次 `onTrigger` 和 `listActive` 时检查，自动移除并更新 JSON |
| 进程崩溃 | JSON 文件仍在，下次 `/resume` 时自动恢复 |
| 新会话（非 resume） | 不会加载旧 JSON（sessionId 不同），干净启动 |

### 3.12 JobInterceptor 日志拦截（可选增强）

利用 Solon 的 `JobInterceptor` 机制，统一为所有 loop 任务增加日志和异常处理：

```java
@Component
public class LoopJobInterceptor implements JobInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LoopJobInterceptor.class);

    @Override
    public void doIntercept(Job job, JobHandler handler) throws Throwable {
        if (!job.getName().startsWith("loop_")) {
            handler.handle(job.getContext()); // 非 loop 任务，直接放行
            return;
        }

        long start = System.currentTimeMillis();
        try {
            handler.handle(job.getContext());
        } catch (Throwable e) {
            log.warn("Loop task [{}] execution failed: {}", job.getName(), e.getMessage());
            // 不抛出，避免 IJobManager 终止后续调度
        } finally {
            long timespan = System.currentTimeMillis() - start;
            log.debug("Loop task [{}] executed in {}ms", job.getName(), timespan);
        }
    }
}
```

## 四、一次性提醒（自然语言触发）

**方案**：零代码改动，通过提示词引导 AI 使用 `/loop` 命令。

在 AGENTS.md 或系统提示词中增加：

```markdown
当用户说"remind me at 3pm to push the branch"时，你应该：
1. 计算距离目标时间的分钟数 N
2. 告诉用户：已设置提醒，将在 N 分钟后触发
3. 使用 /loop Nm <提醒内容> 注册一个一次性定时任务
```

后续版本可新增 `register_reminder` 工具函数，让 AI 直接调用。

## 五、配置项

在 `AgentProperties` 中新增：

```java
private boolean loopEnabled = true;
private int loopMaxTasks = 50;
private int loopDefaultExpireDays = 7;
private int loopMinIntervalMinutes = 1;
private int loopMaxIntervalMinutes = 60;
```

对应 config.yml：

```yaml
soloncode:
  loopEnabled: true
  loopMaxTasks: 50
  loopDefaultExpireDays: 7
  loopMinIntervalMinutes: 1
  loopMaxIntervalMinutes: 60
```

**注意**：不使用 `solon.scheduling.job.{name}` 的 yaml 配置覆盖机制。所有任务的注册/移除/调整都通过 `LoopScheduler` 的 `IJobManager` API 动态完成，任务元数据保存在 `loop_tasks.json` 中。

## 六、默认维护提示词（loop.md）

支持从以下位置加载（优先级从高到低）：

1. `<workspace>/.soloncode/loop.md` — 项目级
2. `~/.soloncode/loop.md` — 用户级
3. 内置默认提示词

```markdown
<!-- 示例：.soloncode/loop.md -->
Check the `release/next` PR. If CI is red, pull the failing job log,
diagnose, and push a minimal fix. If new review comments have arrived,
address each one and resolve the thread. If everything is green and
quiet, say so in one line.
```

文件内容不超过 25,000 字节，超出部分截断。修改后下次迭代立即生效（不需要重启 loop）。

## 七、自动间隔模式的实现

`LoopTask.ofAuto(prompt)` 创建时 `autoInterval=true`，初始间隔设为 5 分钟。

每次执行完成后，在 AI 的 prompt 末尾追加一段指令：

```
请根据当前执行结果，建议下次检查的间隔（1-60分钟）。格式：[SUGGEST_INTERVAL:10m]
```

LoopCommand 解析 AI 响应中的 `[SUGGEST_INTERVAL:Nm]`，动态调整下次间隔：

```java
// 在 CliShell 主循环中，loop 任务执行完成后
if (task.isAutoInterval()) {
    int newInterval = parseSuggestedInterval(aiResponse);
    if (newInterval > 0 && newInterval != task.getIntervalMinutes()) {
        task.setIntervalMinutes(newInterval);
        loopScheduler.updateTask(session, task); // 更新 JSON + 重新注册 IJobManager
    }
}
```

`updateTask` 内部流程：
1. 更新 JSON 文件中的任务数据
2. `jobRemove` 旧的 IJobManager 调度
3. `jobAdd` 以新间隔重新注册

## 八、Web 端扩展（第二期）

第一版标记 `cliOnly() = true`，Web 端不注册。

第二期 Web 端支持方案：

1. **执行**：LoopScheduler 的 `onTrigger` 中，检测到 Web 会话时，直接提交 reactive 流并通过 WebSocket 推送结果
2. **API**：新增 `GET /cli/loop/tasks?sessionId=xxx` 查询活跃任务
3. **取消**：复用现有的 interrupt 机制（`[(sec)interrupt]` 消息）
4. **持久化**：JSON 文件同样有效，Web 端重启后从 JSON 恢复

## 九、工作量评估

| 模块 | 文件 | 类型 | 复杂度 |
|------|------|------|--------|
| pom.xml | pom.xml | 改动 | 低（+5行依赖） |
| App.java | App.java | 改动 | 低（+1行注解） |
| LoopTask | core/LoopTask.java | 新增 | 低（含 JSON 序列化字段） |
| LoopScheduler | core/LoopScheduler.java | 新增 | 中（IJobManager + JSON 双写） |
| LoopCommand | command/builtin/LoopCommand.java | 新增 | 中 |
| CliShell | portal/CliShell.java | 改动 | 中（run 方法重构 + restore 调用） |
| Configurator | Configurator.java | 改动 | 低（+5行） |
| AgentProperties | core/AgentProperties.java | 改动 | 低（+5属性） |
| LoopJobInterceptor | core/LoopJobInterceptor.java | 新增（可选） | 低 |

总计：**3~4 个新文件 + 4 个文件改动**，约 500 行新增代码。

## 十、风险与注意事项

1. **CLI 主循环改动风险**：将 `reader.readLine()` 改为非阻塞轮询可能影响输入体验（如光标、历史记录）。建议将非阻塞逻辑封装为独立方法，保留 `readLine` 作为 fallback
2. **JSON 并发写入**：LoopScheduler 的 JSON 读写发生在主循环（任务执行完成时）和调度线程（onTrigger 清理过期时），需要注意并发写入。建议所有 JSON 写入操作通过主线程执行，调度线程只做 jobRemove 不写 JSON
3. **线程安全**：`performAgentTask` 内部使用 `CountDownLatch` 等待，必须确保不会被并发调用。通过 `LoopTask.tryStart()` 的 CAS 机制 + `fixedDelay`（串行策略）双重保障
4. **JSON 文件损坏**：如果进程在写入 JSON 时崩溃，文件可能损坏。建议采用"写临时文件 → 原子重命名"策略
5. **7天过期**：过期时间在 `LoopTask` 创建时写入 `expireAt`，每次 `onTrigger` 前检查并自动 `jobRemove` + 更新 JSON
6. **Web 端安全**：第一版 `cliOnly=true`，避免 Web 端的无主循环问题
7. **JobInterceptor 异常吞没**：拦截器中 catch 异常后不应吞掉（否则 IJobManager 以为正常），但 loop 场景下需要"静默失败继续调度"，所以要特别处理

## 十一、开发顺序建议

1. **Phase 1**：pom.xml 依赖 + `@EnableScheduling` + LoopTask + LoopScheduler（JSON 持久化 + IJobManager）+ LoopCommand（固定间隔），CliShell 适配 + restore
2. **Phase 2**：自动间隔模式 + loop.md 默认提示词加载 + JobInterceptor 日志
3. **Phase 3**：Web 端支持 + 一次性提醒工具
