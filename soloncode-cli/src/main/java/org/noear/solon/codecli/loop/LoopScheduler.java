/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.loop;
import org.noear.solon.ai.harness.runtime.ErrorClassifier;

import org.noear.java_cron.CronUtils;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.LoopGroupDo;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.scheduling.ScheduledAnno;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.simple.JobManager;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Instant;

/**
 * 定时循环任务调度管理器
 *
 * <p>职责：
 * <ol>
 *   <li>管理任务元数据的 JSON 持久化（load / save）</li>
 *   <li>通过 IJobManager 动态注册/移除调度</li>
 *   <li>支持进程重启后恢复未过期任务</li>
 * </ol>
 *
 * @author noear
 * @since 3.9.1
 */
public class LoopScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LoopScheduler.class);
    /** 工作区级任务总量上限（tasks.json 统一存储后不再按会话隔离计数）。 */
    private static final int MAX_TASKS = 200;
    /** 旧版每会话任务文件（sessions/<id>/loop-tasks.json），仅迁移时读取。 */
    private static final String LEGACY_TASKS_FILE = "loop-tasks.json";
    /** 统一任务存储：工作区数据目录下 tasks.json。 */
    private static final String STORE_FILE = "tasks.json";
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static volatile boolean interruptHandlerInstalled = false;

    private final LoopGroupDo loop;
    private final HarnessEngine engine;
    private final IJobManager jobManager;
    private final ConcurrentHashMap<String, List<LoopTask>> sessionTasks = new ConcurrentHashMap<>();
    /** 统一存储写锁：所有 mutation 后的落盘串行化，防止并发写坏 tasks.json。 */
    private final ReentrantLock storeLock = new ReentrantLock();
    /** 全量加载标记：restore/restoreAll 幂等的依据。 */
    private volatile boolean loaded = false;

    /**
     * 本调度器自有的执行器（与 goal 无任何共享）。
     *
     * <p>loop 是宿主调度能力（cli 域），执行通道必须内联在这里：谁拥有线程、连接、终端，
     * 谁定义执行通道。曾与 goal 共用的 {@code ExecutionBridge} 已删除——
     * 让 harness 定义一个同时服务两者的通道，等于让 harness 拥有 cli 的调度语义。</p>
     */
    private final List<LoopExecutor> executors = new CopyOnWriteArrayList<>();

    /**
     * 本轮的执行器：执行一轮 prompt，返回最终文本（null = 本入口不认领）。
     *
     * <p>loop 只需最终文本（轮次计数与 lastResult），不需要 token / 工具证据，
     * 因此不与 goal 共用签名：强求统一会逼迫 loop 去填它用不到的字段，
     * 而那正是旧设计把指标落进会话 attrs 旁路的起因。</p>
     *
     * <p><b>没有 agentName 参数</b>：子代理归属是<b>会话属性</b>（{@code CTX_AGENT_SELECTED}，
     * 由 {@code AutomationManager.applySelections} 在创建任务时写入会话上下文），执行器
     * 从 session 读即可。旧签名带该参数，但唯一调用点恒传 null——三个宿主里
     * 「拼 {@code @agentName} 前缀」的分支从未执行，是彻底的死代码。</p>
     */
    @FunctionalInterface
    public interface LoopExecutor {
        String handle(String sessionId, String prompt);
    }

    public LoopScheduler(HarnessEngine engine, AgentSettings agentSettings) {
        this.engine = engine;
        this.jobManager = resolveJobManager();
        this.loop = agentSettings.getLoop();
    }

    /**
     * 取进程级调度器；无 Solon 容器（纯 JUnit）时为 null，调度能力随之失效。
     *
     * <p>与 {@code GoalExtension.Builder.scheduler(null)} 同一意图：执行通道与调度装配是两件事，
     * 测试应当能单独验证前者。</p>
     */
    private static IJobManager resolveJobManager() {
        try {
            return JobManager.getInstance();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 注册执行器（按注册顺序取首个认领者）。 */
    public void addExecutor(LoopExecutor executor) {
        if (executor != null) {
            this.executors.add(executor);
        }
    }

    /** 是否已有执行器（供宿主启动断言；无执行器的调度器属配置错误）。 */
    public boolean hasExecutor() {
        return !executors.isEmpty();
    }

    /**
     * 注入型回合的额外屏隢（唯一一个会话状态表达不了的事实：终端所有权）。
     *
     * <p>CLI 注入回合在调度线程执行、直写 {@code terminal}，与主线程的 {@code readLine}
     * 输出并发。这与「会话忙不忙」是两回事：主线程跑 {@code /help}、{@code /model} 选择器时
     * 会话不忙，但终端正被占用。因此保留单一宿主谓词，缺省恒 true（非 CLI 宿主不用关心）。</p>
     */
    @FunctionalInterface
    public interface InjectionGate {
        /** @return true 表示可以注入本轮 */
        boolean canInject();
    }

    private volatile InjectionGate injectionGate = () -> true;

    public void setInjectionGate(InjectionGate gate) {
        if (gate != null) {
            this.injectionGate = gate;
        }
    }

    // ==================== 任务注册 ====================

    /**
     * 注册任务并开始调度。
     *
     * @throws IllegalArgumentException cron 无法解析，或已无未来触发时刻
     * @throws IllegalStateException    会话任务数已达上限
     */
    public LoopTask schedule(String sessionId, LoopTask task) {
        if (task.isCronMode()) {
            // 创建入口统一收口（模型侧 loop_add、/loop cron:、Web 端共用此处）：
            // 无法解析或已无未来触发时刻的 cron 一律拒绝创建
            requireNextFireTime(task.getCron());
        }

        List<LoopTask> tasks = sessionTasks.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        if (countAllTasks() >= MAX_TASKS) {
            throw new IllegalStateException("Max tasks reached: " + MAX_TASKS);
        }

        cleanExpired(sessionId, tasks);
        registerJob(sessionId, task, true);
        tasks.add(task);
        persistAll();

        return task;
    }

    // ==================== 任务移除 ====================

    public void remove(String sessionId, LoopTask task) {
        LOG.info("Removing loop task '{}' from session '{}'", task.getId(), sessionId);

        task.cancel();
        String jobName = task.getJobName();
        if (jobExists(jobName)) {
            jobRemove(jobName);
        }

        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        tasks.removeIf(t -> t.getId().equals(task.getId()));
        persistAll();
    }

    public void toggle(String sessionId, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                boolean newEnabled = !t.isEnabled();
                t.setEnabled(newEnabled);

                if (newEnabled) {
                    registerJob(sessionId, t);
                } else {
                    String jobName = t.getJobName();
                    if (jobExists(jobName)) {
                        jobRemove(jobName);
                    }
                }

                persistAll();
                return;
            }
        }
    }

    public void update(String sessionId, String taskId, LoopTask newTask) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (int i = 0; i < tasks.size(); i++) {
            LoopTask t = tasks.get(i);
            if (t.getId().equals(taskId)) {
                String jobName = t.getJobName();
                if (jobExists(jobName)) {
                    jobRemove(jobName);
                }

                tasks.set(i, newTask);

                if (newTask.isEnabled() && !newTask.isCancelled()) {
                    registerJob(sessionId, newTask);
                }

                persistAll();
                return;
            }
        }
    }

    public void trigger(String sessionId, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                RunUtil.parallel(() -> onTrigger(sessionId, t));
                return;
            }
        }
    }

    public LoopTask getTaskById(String sessionId, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return null;
        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) return t;
        }
        return null;
    }

    // ==================== 任务列表 ====================

    public List<LoopTask> listActive(String sessionId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();
        cleanExpired(sessionId, tasks);
        return new ArrayList<>(tasks);
    }

    public List<LoopTask> listAll(String sessionId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();
        cleanExpired(sessionId, tasks);
        return new ArrayList<>(tasks);
    }

    /**
     * 获取所有已加载会话的循环任务快照。
     *
     * @return sessionId 到任务列表的映射，按 sessionId 排序
     */
    public Map<String, List<LoopTask>> listAll() {
        Map<String, List<LoopTask>> result = new LinkedHashMap<>();
        List<String> sessionIds = new ArrayList<>(sessionTasks.keySet());
        Collections.sort(sessionIds);

        for (String sessionId : sessionIds) {
            List<LoopTask> tasks = sessionTasks.get(sessionId);
            if (tasks == null) {
                continue;
            }

            cleanExpired(sessionId, tasks);
            List<LoopTask> snapshot = new ArrayList<>(tasks);
            if (!snapshot.isEmpty()) {
                result.put(sessionId, snapshot);
            }
        }

        return result;
    }

    // ==================== 批量停止 ====================

    /**
     * 停止会话全部任务并从 tasks.json 删除该会话的记录。
     * 用于删除会话（deleteSession/clearSessions）与 CLI /loop stop-all。
     */
    public void stopAll(String sessionId) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            tasks.forEach(t -> {
                t.cancel();
                String jobName = t.getJobName();
                if (jobExists(jobName)) {
                    jobRemove(jobName);
                }
            });
        }
        persistAll();
    }

    /**
     * 卸载会话任务但保留 tasks.json 记录。
     * 供工作区优雅关闭（shutdown/LRU 回收）使用：任务定义跨重启存活，下次加载时恢复调度。
     */
    public void unload(String sessionId) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            tasks.forEach(t -> {
                String jobName = t.getJobName();
                if (jobExists(jobName)) {
                    jobRemove(jobName);
                }
            });
        }
        // 不落盘：磁盘上该会话条目保持原样
    }

    // ==================== 生命周期 ====================

    /**
     * 是否存在活跃（未停止）的循环任务。
     * 供工作区 LRU 回收判定使用：有活跃任务的工作区不应被回收。
     */
    public boolean hasActiveTasks() {
        for (List<LoopTask> tasks : sessionTasks.values()) {
            if (tasks != null && !tasks.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 关闭调度器：卸载本工作区全部会话的任务并注销调度，但保留 tasks.json 记录
     * （优雅退出后重启可恢复；stopAll 会删记录，仅用于用户显式删除场景）。
     * 注意：jobManager 为进程级单例（跨工作区共享），此处只按本工作区会话逐个 unload，
     * 不 shutdown 全局 JobManager。
     */
    public void shutdown() {
        for (String sessionId : new ArrayList<>(sessionTasks.keySet())) {
            try {
                unload(sessionId);
            } catch (Exception e) {
                LOG.warn("[Loop] shutdown session {} failed: {}", sessionId, e.getMessage());
            }
        }
    }

    // ==================== 会话恢复 ====================

    /**
     * 单会话恢复：统一存储后任务已在 loadAll() 全量加载，此方法退化为幂等兼容入口。
     * 历史调用点（会话打开时懒加载）无需改动。
     */
    public synchronized void restore(String sessionId) {
        loadAll();
    }

    /**
     * 全量加载统一存储 tasks.json（含旧版三路数据迁移），并注册全部启用任务。
     * 幂等：重复调用直接返回。工作区启动（WorkspaceManager）与旧入口均安全。
     */
    public synchronized void restoreAll() {
        loadAll();
    }

    private void loadAll() {
        if (loaded) return;
        loaded = true; // 先置位：即便后续异常也不再重试，避免半初始化反复执行
        try {
            Map<String, List<LoopTask>> loaded = migrateAndLoad();
            if (loaded.isEmpty()) return;

            for (Map.Entry<String, List<LoopTask>> entry : loaded.entrySet()) {
                String sessionId = entry.getKey();
                if (sessionTasks.containsKey(sessionId)) continue; // 已在内存则跳过

                List<LoopTask> alive = new ArrayList<>();
                for (LoopTask t : entry.getValue()) {
                    if (t.isCancelled()) continue;
                    alive.add(t);
                }
                if (alive.isEmpty()) continue;

                sessionTasks.put(sessionId, Collections.synchronizedList(alive));

                for (LoopTask t : alive) {
                    // kill -9 兜底：running 锁未持久化，但显式释放确保无残留
                    t.finish();
                    if (t.isEnabled()) {
                        registerJob(sessionId, t);
                    }
                }
            }
            LOG.info("Loaded {} sessions of loop tasks from tasks.json", sessionTasks.size());
        } catch (Exception e) {
            LOG.error("Failed to load tasks.json: {}", e.getMessage());
        }
    }

    /**
     * 读取统一存储；不存在时触发旧版数据迁移（各会话的 loop-tasks.json）后重读。
     * 同时清理孤儿记录（会话目录已不存在但 tasks.json 仍有条目）。自动化任务（auto- 前缀）
     * 除外——其专用会话目录可能被归档但不代表任务失效。
     */
    private Map<String, List<LoopTask>> migrateAndLoad() {
        Path store = storePath();
        if (!Files.isRegularFile(store)) {
            migrateLegacy();
        }

        Map<String, List<LoopTask>> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(store)) return result;

        try {
            String json = new String(Files.readAllBytes(store), StandardCharsets.UTF_8);
            ONode root = ONode.ofJson(json);
            if (root == null || !root.isObject()) return result;

            Path sessionsRoot = WorkspaceDataUtil.sessionsPath(engine.getWorkspace());
            boolean changed = false;
            if (root.get("tasks") != null && root.get("tasks").isArray()) {
                for (ONode item : root.get("tasks").getArray()) {
                    try {
                        String sessionId = item.getOrNull("sessionId") != null
                                ? item.get("sessionId").getString() : null;
                        if (sessionId == null || sessionId.trim().isEmpty()) continue;
                        LoopTask task = LoopTask.fromONode(item);
                        // 孤儿清理：会话目录已删的普通任务不再恢复（auto- 专用会话允许缺席）
                        if (!sessionId.startsWith("auto-") && !Files.isDirectory(sessionsRoot.resolve(sessionId))) {
                            changed = true;
                            continue;
                        }
                        result.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(task);
                    } catch (Exception e) {
                        LOG.warn("Skip invalid task entry: {}", e.getMessage());
                    }
                }
            }
            if (changed) {
                // ★ 不能在这里调 persistAll()：此时 sessionTasks 尚未填充（loadAll 的后半段才填充），
                //   persistAll 以空内存快照落盘会清空 tasks.json；改用 persistMap 直接落盘过滤后的结果
                storeLock.lock();
                try {
                    persistMap(result);
                } finally {
                    storeLock.unlock();
                }
            }
        } catch (Exception e) {
            LOG.warn("Load tasks.json failed: {}", e.getMessage());
        }
        return result;
    }

    // ==================== 旧版数据迁移 ====================

    /**
     * 各会话的 loop-tasks.json（含 auto- 会话镜像，运行时状态权威）合并写入 tasks.json
     * 后删除旧文件；任一步失败保留现场下次重试。
     */
    private void migrateLegacy() {
        try {
            Path sessionsRoot = WorkspaceDataUtil.sessionsPath(engine.getWorkspace());
            if (!Files.isDirectory(sessionsRoot)) return;

            Map<String, List<LoopTask>> merged = new LinkedHashMap<>();

            int sessionFiles = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsRoot)) {
                for (Path sessionPath : stream) {
                    Path file = sessionPath.resolve(LEGACY_TASKS_FILE);
                    if (!Files.isRegularFile(file)) continue;
                    List<LoopTask> tasks = parseLegacyTasks(file);
                    if (!tasks.isEmpty()) {
                        merged.put(sessionPath.getFileName().toString(), tasks);
                        sessionFiles++;
                    }
                }
            }

            persistMap(merged);

            // 迁移成功后删除旧文件（删除失败不影响结果，残留会被下次扫描忽略）
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsRoot)) {
                for (Path sessionPath : stream) {
                    Files.deleteIfExists(sessionPath.resolve(LEGACY_TASKS_FILE));
                }
            }
            LOG.info("Migrated {} sessions of legacy loop tasks to tasks.json", sessionFiles);
        } catch (Exception e) {
            LOG.warn("Migrate legacy tasks failed (will retry next start): {}", e.getMessage());
        }
    }

    private List<LoopTask> parseLegacyTasks(Path file) {
        List<LoopTask> tasks = new ArrayList<>();
        try {
            ONode root = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            for (ONode node : root.getArray()) {
                try {
                    tasks.add(LoopTask.fromONode(node));
                } catch (Exception e) {
                    LOG.warn("Skip invalid legacy task in {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Read legacy tasks failed {}: {}", file, e.getMessage());
        }
        return tasks;
    }

    // ==================== IJobManager 注册 ====================

    private void registerJob(String sessionId, LoopTask task) {
        registerJob(sessionId, task, false);
    }

    private void registerJob(String sessionId, LoopTask task, boolean firstRegistration) {
        String jobName = task.getJobName();

        ScheduledAnno scheduled;
        if (task.isCronMode()) {
            // 恢复(restore)/更新(update)/启用(toggle) 这些路径不像 schedule() 那样能拒绝创建：
            // 此时若 cron 已不可触发，注册出去的 job 会被底层立即判为 expired 而停掉，
            // 任务却仍是 enabled=true（永不执行且不会自动清理）。故直接停用并留痕，保持状态诚实。
            try {
                requireNextFireTime(task.getCron());
            } catch (IllegalArgumentException e) {
                task.setEnabled(false);
                LOG.warn("Loop task '{}' disabled, not schedulable: {}", task.getId(), e.getMessage());
                return;
            }
            scheduled = new ScheduledAnno().cron(task.getCron());
        } else {
            long intervalMs = (long) task.getIntervalMinutes() * 60_000L;
            long initialDelay = (firstRegistration && task.isRunNow()) ? 0 : intervalMs;
            scheduled = new ScheduledAnno()
                    .fixedDelay(intervalMs)
                    .initialDelay(initialDelay);
        }

        if (jobManager == null) {
            LOG.debug("LoopScheduler has no job manager; task '{}' kept as metadata only", task.getId());
            return;
        }
        jobManager.jobAdd(jobName, scheduled, ctx -> {
            if (!task.isEnabled()) {
                return;
            }
            onTrigger(sessionId, task, true);
        });
    }

    /** 调度器无容器时为空：守卫在唯一的调用处，避免把 null 判断分散到 7 处。 */
    private boolean jobExists(String jobName) {
        return jobManager != null && jobManager.jobExists(jobName);
    }

    private void jobRemove(String jobName) {
        if (jobManager != null) {
            jobManager.jobRemove(jobName);
        }
    }

    /**
     * 计算 cron 的下次触发时刻 —— 与 SimpleScheduler 使用同一个解析器，
     * 保证校验语义与实际调度行为一致。
     *
     * @return 下次触发时刻（不为 null）
     * @throws IllegalArgumentException 表达式无法解析，或已无未来触发时刻
     */
    static Date requireNextFireTime(String cron) {
        Date nextTime;
        try {
            nextTime = CronUtils.getNextTime(cron, new Date());
        } catch (Exception e) {
            throw new IllegalArgumentException("cron 表达式无法解析: " + cron
                    + "（需 6 或 7 位：秒 分 时 日 月 周 [年]，日与周必须有一个为 ?）");
        }
        if (nextTime == null) {
            // 带年份的 cron 整体过期，或日期永不存在（如 2 月 31 日）
            throw new IllegalArgumentException("cron 无有效触发时刻: " + cron
                    + "（时刻已过或日期不存在，服务器当前时间 " + LocalDateTime.now().format(NOW_FORMAT) + "）");
        }
        return nextTime;
    }

    // ==================== 定时触发回调 ====================

    /**
     * 定时触发 — 执行一轮任务
     */
    private void onTrigger(String sessionId, LoopTask task) {
        onTrigger(sessionId, task, false);
    }

    private void onTrigger(String sessionId, LoopTask task, boolean fromSchedule) {
        // 调度线程（定时/手动触发/重试）无工作区标记，统一在此打标：
        // 本方法内所有日志随工作区分流，不落到启动工作区文件
        Object logScope = WorkspaceLogRouter.beginScope(engine.getWorkspace());
        try {
            doTrigger(sessionId, task, fromSchedule);
        } finally {
            WorkspaceLogRouter.endScope(logScope);
        }
    }

    /** 包内可见：供测试直接驱动一轮（不经并行调度，避免测试依赖时序）。 */
    void doTrigger(String sessionId, LoopTask task, boolean fromSchedule) {
        // ① 前置守卫（禁用/过期/取消 → 繁忙）
        if (!checkGuardConditions(sessionId, task)) {
            return;
        }

        // ② CAS 防重入
        if (!task.tryStart()) {
            return;
        }

        try {
            // ③ 执行一轮
            String effectivePrompt = task.getPrompt();
            String finalResult = execute(sessionId, effectivePrompt);
            if (finalResult != null) {
                task.updateLastExecution(finalResult);
                task.incrementIteration();
                persistAll();
            } else {
                // 无执行器认领 = 配置错误（日志已在 execute 内留痕）。
                // 绝不能记 "ok"：那会让定时任务看起来一直在跑、实际什么也没做，
                // 且不计迭代、不报错、不打日志，属于最难发现的故障形态。
                LOG.warn("Loop task '{}' skipped: no executor claimed session '{}'", task.getId(), sessionId);
            }
        } catch (Exception e) {
            handleExecutionError(sessionId, task, e);
        } finally {
            // ★ oneShot 一次性任务：调度触发执行完一轮后自动注销（手动 trigger 不消耗配额）
            if (fromSchedule && task.isOneShot()) {
                unregisterOneShot(sessionId, task);
            }
            task.finish();
        }
    }

    /** 一次性任务善后：注销 job + 移除任务行 + 持久化（异常也消耗，避免失败任务长期残留） */
    private void unregisterOneShot(String sessionId, LoopTask task) {
        try {
            LOG.info("One-shot loop task '{}' executed, unregistering", task.getId());
            remove(sessionId, task);
        } catch (Exception e) {
            LOG.warn("Failed to unregister one-shot task '{}': {}", task.getId(), e.getMessage());
        }
    }

    /**
     * 前置守卫检查链。任一条件触发则执行对应处理并返回 false。
     *
     * @return true = 可以继续执行；false = 已处理完毕（调用方应 return）
     */
    private boolean checkGuardConditions(String sessionId, LoopTask task) {
        // 已禁用/已取消则移除
        if (!task.isEnabled() || task.isCancelled()) {
            String jobName = task.getJobName();
            if (jobExists(jobName)) {
                jobRemove(jobName);
            }
            return false;
        }

        // 会话占用/积压时跳过
        if (shouldSkipSession(sessionId)) {
            LOG.info("Loop task '{}' skipped: session '{}' occupied or backlogged", task.getId(), sessionId);
            return false;
        }

        // 终端所有权（仅 CLI 注入；非 CLI 宿主缺省恒 true）
        if (!injectionGate.canInject()) {
            LOG.info("Loop task '{}' skipped: injection gate closed", task.getId());
            return false;
        }

        return true;
    }

    /**
     * 异常分级处理：连续异常 ≥ 阈值时停用任务并告警，否则递增延迟重试。
     */
    /** 连续异常计数（任务级瞬态，进程重启后归零）。 */
    private int consecutiveErrors = 0;

    private void handleExecutionError(String sessionId, LoopTask task, Exception e) {
        String errorType = ErrorClassifier.classifyError(e);

        LOG.error("Loop task '{}' failed [{}]: {}", task.getId(), errorType, e.getMessage());
        task.updateLastExecution("error [" + errorType + "]: " + e.getMessage());
        persistAll();

        if (task.isCancelled()) {
            return;
        }

        int errors = ++consecutiveErrors;
        if (errors >= loop.getMaxConsecutiveErrorsOrDefault()) {
            LOG.warn("Loop task '{}' stopped after {} consecutive errors", task.getId(), errors);
            task.setEnabled(false);
            String jobName = task.getJobName();
            if (jobExists(jobName)) {
                jobRemove(jobName);
            }
            persistAll();
            consecutiveErrors = 0;
            return;
        }

        long delay = 5L * errors; // 5s, 10s, 15s ...
        LOG.info("Loop task '{}' scheduling error retry in {}s (attempt {}, type={})",
                task.getId(), delay, errors, errorType);
        RunUtil.delay(() -> {
            if (!task.isCancelled() && task.isEnabled()) {
                onTrigger(sessionId, task);
            }
        }, delay * 1_000L);
    }

    // ==================== 执行 ====================

    /**
     * 执行一轮：按注册顺序取首个认领者。
     *
     * <p><b>无不认领兜底</b>：loop 会话必然来自本进程的某个入口（CLI 主会话 / {@code web-*} /
     * {@code auto-*}），无任何执行器认领即属配置错误，必须留痕而不是假装执行成功——
     * 旧实现落到别的域的缺省 handler 并返回 {@code "ok"}，定时任务看起来一直在跑，
     * 实际什么也没做且无日志。</p>
     *
     * @return 最终文本；无认领时返回 null
     */
    String execute(String sessionId, String prompt) {
        for (LoopExecutor executor : executors) {
            String result = executor.handle(sessionId, prompt);
            if (result != null) {
                return result;
            }
        }
        LOG.warn("[Loop] no executor claimed session '{}', round skipped", sessionId);
        return null;
    }

    /**
     * 会话是否应跳过本轮注入。
     *
     * <p>口径为引擎的 {@code isIdle}（占用或有积压即不空闲）：HITL 挂起时没有活跃流但注入会顶掉
     * 未决审批，必须算占用；积压压着任务时注入等于插队，也一并跳过。</p>
     *
     * <p><b>与 goal 的差异（故意的）</b>：会话不存在时这里返回 false（不跳过），
     * 而 goal 返回 true。原因是 loop 的 {@code auto-*} 任务可能对应尚未实例化的会话，
     * 若跳过就会永不执行；goal 只跑已有会话，会话不存在即配置错误。</p>
     */
    private boolean shouldSkipSession(String sessionId) {
        try {
            AgentSession session = engine.getSession(sessionId);
            if (session == null) {
                return false;
            }
            return engine.isIdle(session) == false;
        } catch (Throwable e) {
            LOG.debug("[Loop] status check failed for session '{}': {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ==================== 清理过期任务 ====================

    private void cleanExpired(String sessionId, List<LoopTask> tasks) {
        boolean changed = tasks.removeIf(t -> {
            if (t.isCancelled()) {
                String jobName = t.getJobName();
                if (jobExists(jobName)) {
                    jobRemove(jobName);
                }
                return true;
            }
            return false;
        });

        if (changed) {
            persistAll();
        }
    }

    // ==================== JSON 持久化（统一存储 tasks.json） ====================

    private Path storePath() {
        return WorkspaceDataUtil.dataDir(engine.getWorkspace()).toPath().resolve(STORE_FILE).normalize();
    }

    /** 工作区任务总量（含未启用）。 */
    private int countAllTasks() {
        int count = 0;
        for (List<LoopTask> tasks : sessionTasks.values()) {
            if (tasks != null) count += tasks.size();
        }
        return count;
    }

    /**
     * 全量落盘：内存 sessionTasks 快照 → tasks.json（temp + ATOMIC_MOVE 原子替换）。
     * 所有 mutation 后统一走此方法；storeLock 串行化并发写。
     */
    private void persistAll() {
        storeLock.lock();
        try {
            Map<String, List<LoopTask>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
            }
            persistMap(snapshot);
        } finally {
            storeLock.unlock();
        }
    }

    /** 写入指定内容（迁移与孤儿清理复用；调用方自行保证串行）。 */
    private void persistMap(Map<String, List<LoopTask>> snapshot) {
        try {
            Path filePath = storePath();
            Files.createDirectories(filePath.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            root.set("version", 2);
            // ★ snack4 陷阱：必须 getOrNew("tasks").asArray()，
            //   对 get("tasks")（null）链式 asArray 会得到幽灵数组，写入元素全部丢失
            ONode array = root.getOrNew("tasks").asArray();
            for (Map.Entry<String, List<LoopTask>> entry : snapshot.entrySet()) {
                for (LoopTask t : entry.getValue()) {
                    if (t.isCancelled()) continue; // 取消任务不落盘，与旧版语义一致
                    ONode node = t.toONode();
                    node.set("sessionId", entry.getKey());
                    array.add(node);
                }
            }

            Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                    StandardCharsets.UTF_8)) {
                w.write(root.toJson());
            }
            try {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException fallback) {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.error("Failed to save tasks.json: {}", e.getMessage());
        }
    }
}
