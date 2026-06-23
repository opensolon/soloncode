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
package org.noear.solon.codecli.command.builtin;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.scheduling.ScheduledAnno;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.simple.JobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int MAX_TASKS_PER_SESSION = 50;
    private static final String TASKS_FILE = "loop-tasks.json";

    private static volatile boolean interruptHandlerInstalled = false;

    // D1: 续行保护常量
    private static final int MAX_CONTINUATION_DEPTH = 3;            // 最大连续事件驱动续行深度
    private static final long MIN_CONTINUATION_INTERVAL_MS = 2000;  // 最小续行间隔 2 秒

    private final HarnessEngine engine;

    // Solon 原生调度管理器
    private final IJobManager jobManager;

    // 会话级任务列表：sessionId -> list of LoopTask
    private final ConcurrentHashMap<String, List<LoopTask>> sessionTasks = new ConcurrentHashMap<>();

    // CLI 端任务执行回调：sessionId, prompt, agentName -> void（同步阻塞）
    private volatile List<TaskExecutor> taskExecutors = new ArrayList<>();

    // 会话繁忙检查器
    private volatile List<BusyChecker> busyCheckers = new ArrayList<>();

    // Worktree 管理器（lazy init）
    private volatile WorktreeManager worktreeManager;

    // Worktree 目录名
    private final String worktreeDir;

    //（Goal 评估逻辑已内联为 [GOAL_ACHIEVED] 标记检测）

    /**
     * CLI 端任务执行器（同步阻塞）
     *
     * <p>支持指定 agent 名称。
     * 若 agentName 为 null，则使用默认主 agent。
     *
     * <p>返回 AI 的响应文本摘要，用于 goal 条件检查。
     * 若无法获取响应（如会话不匹配），返回 null。
     */
    @FunctionalInterface
    public interface TaskExecutor {
        /**
         * @param sessionId 会话 ID
         * @param prompt    提示词
         * @param agentName 代理名称（可为 null，表示主 agent）
         * @return AI 响应文本摘要，无法获取时返回 null
         */
        String execute(String sessionId, String prompt, String agentName);
    }

    /**
     * 会话繁忙检查器
     *
     * <p>用于在 loop 定时触发时判断目标会话是否有任务正在执行。
     * 若会话繁忙，则跳过本次触发，避免与前台任务并发冲突、向前端推送多余消息。
     */
    @FunctionalInterface
    public interface BusyChecker {
        /**
         * @param sessionId 会话 ID
         * @return true 表示会话正在执行任务
         */
        boolean isBusy(String sessionId);
    }


    /**
     * @param worktreeDir worktree 目录名（如 ".soloncode/loop-worktrees"），null 时使用默认值
     */
    public LoopScheduler(HarnessEngine engine, String worktreeDir) {
        this.engine = engine;
        this.jobManager = JobManager.getInstance();
        this.worktreeDir = worktreeDir;
    }

    public void addTaskExecutor(TaskExecutor executor) {
        this.taskExecutors.add(executor);
    }

    /**
     * 注册会话繁忙检查器（由 WebController / CliShell 各自注入）。
     *
     * <p>采用追加语义而非覆盖：多个端口的 checker 共存，任一报告繁忙即视为繁忙。</p>
     */
    public void addBusyChecker(BusyChecker busyChecker) {
        if (busyChecker != null) {
            this.busyCheckers.add(busyChecker);
        }
    }



    /**
     * 获取第一个可用的会话 ID（用于 GoalTool 自动推断当前会话）
     */
    public String getFirstSessionId() {
        if (sessionTasks.isEmpty()) return null;
        return sessionTasks.keySet().iterator().next();
    }

    /**
     * 跨会话查找活跃 goal（PURSUING 优先，PAUSED 次之）
     */
    public LoopTask findActiveGoalAcrossSessions() {
        // 先找 PURSUING
        for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
            for (LoopTask t : entry.getValue()) {
                if (t.isGoalMode() && t.getGoalState().getStatus().isActive()) {
                    return t;
                }
            }
        }
        // 再找 PAUSED
        for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
            for (LoopTask t : entry.getValue()) {
                if (t.isGoalMode() && t.getGoalState().getStatus() == GoalState.Status.PAUSED) {
                    return t;
                }
            }
        }
        return null;
    }

    // ==================== G3: ShutdownHook 自动暂停 ====================

    /**
     * ★ G3: 安装 ShutdownHook — JVM 退出前自动暂停所有活跃 goal
     */
    private void installInterruptHandler() {
        if (interruptHandlerInstalled) {
            return;
        }
        interruptHandlerInstalled = true;

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("G3: JVM shutting down, pausing active goals");
                pauseAllGoals();
            }, "goal-shutdown-hook"));
            LOG.info("G3: ShutdownHook installed for auto-pausing active goals");
        } catch (Throwable e) {
            LOG.warn("G3: Cannot install ShutdownHook: {}", e.getMessage());
        }
    }

    /**
     * ★ G3: 暂停所有活跃 goal
     */
    private void pauseAllGoals() {
        for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
            for (LoopTask task : entry.getValue()) {
                if (task.isGoalMode()) {
                    GoalState gs = task.getGoalState();
                    if (gs.getStatus() == GoalState.Status.PURSUING) {
                        gs.pause();
                        disableGoalScheduling(entry.getKey(), task);
                        LOG.info("G3: goal '{}' paused due to JVM shutdown", task.getId());
                    }
                }
            }
        }
    }

    /**
     * 获取或创建 WorktreeManager（lazy init）
     */
    private WorktreeManager getWorktreeManager() {
        if (worktreeManager == null) {
            synchronized (this) {
                if (worktreeManager == null) {
                    worktreeManager = worktreeDir != null
                            ? new WorktreeManager(worktreeDir)
                            : new WorktreeManager();
                }
            }
        }
        return worktreeManager;
    }

    // ==================== 任务注册 ====================

    /**
     * 注册循环任务
     *
     * <p>流程：创建 LoopTask -> 注册到 IJobManager（cron / fixedDelay）-> 加入内存列表 -> 持久化到 JSON
     *
     * @param sessionId      会话 ID
     * @param task           待注册的任务
     * @return 已注册的任务
     */
    public LoopTask schedule(String sessionId, LoopTask task) {
        // 1. 检查最大任务数
        List<LoopTask> tasks = sessionTasks.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        if (tasks.size() >= MAX_TASKS_PER_SESSION) {
            throw new IllegalStateException("Max tasks reached: " + MAX_TASKS_PER_SESSION);
        }

        // 2. 清理过期任务
        cleanExpired(sessionId, tasks);

        // 3. 注册到 IJobManager（cron 模式用 cron 表达式，否则 fixedDelay 串行）
        //    firstRegistration=true，使 runNow 生效
        registerJob(sessionId, task, true);

        // 4. 加入内存列表
        tasks.add(task);

        // 5. 持久化到 JSON
        saveToFile(sessionId, tasks);

        return task;
    }

    // ==================== 任务移除 ====================

    /**
     * 停止指定任务
     */
    public void remove(String sessionId, LoopTask task) {
        LOG.info("Removing loop task '{}' from session '{}'", task.getId(), sessionId);

        task.cancel();
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }

        // P1-fix: 清理 worktree
        if (task.isWorktreeEnabled()) {
            try {
                getWorktreeManager().cleanup(engine.getWorkspace());
                LOG.info("Loop task '{}' worktree cleaned up on remove", task.getId());
            } catch (Exception e) {
                LOG.warn("Loop task '{}' worktree cleanup failed on remove: {}", task.getId(), e.getMessage());
            }
        }

        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) {
            LOG.warn("Loop task '{}' remove failed: no tasks found for session '{}'", task.getId(), sessionId);
            return;
        }

        tasks.removeIf(t -> t.getId().equals(task.getId()));

        saveToFile(sessionId, tasks);
    }

    // ==================== Goal 生命周期管理 (P0) ====================

    /**
     * 暂停 goal（PURSUING → PAUSED），移除调度但保留任务
     */
    public void pauseGoal(String sessionId, String taskId) {
        LoopTask task = getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            LOG.warn("pauseGoal: task '{}' not found or not goal mode", taskId);
            return;
        }

        GoalState gs = task.getGoalState();
        if (!gs.pause()) {
            LOG.warn("pauseGoal: task '{}' cannot be paused (status={})", taskId, gs.getStatus());
            return;
        }

        // 移除调度
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }

        saveToFile(sessionId, sessionTasks.get(sessionId));
        LOG.info("Goal paused for task '{}'", taskId);
    }

    /**
     * 恢复 goal（PAUSED → PURSUING），重新注册调度
     */
    public void resumeGoal(String sessionId, String taskId) {
        LoopTask task = getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            LOG.warn("resumeGoal: task '{}' not found or not goal mode", taskId);
            return;
        }

        GoalState gs = task.getGoalState();

        // ★ P0: 电路熔断 — blocked 循环耗尽后禁止恢复
        if (gs.isBlockedCycleExhausted()) {
            LOG.warn("resumeGoal: task '{}' blocked cycle exhausted ({}), cannot resume",
                    taskId, gs.getBlockedCycleCount());
            return;
        }

        if (!gs.resume()) {
            LOG.warn("resumeGoal: task '{}' cannot be resumed (status={})", taskId, gs.getStatus());
            return;
        }

        // 重新注册调度
        registerJob(sessionId, task);

        saveToFile(sessionId, sessionTasks.get(sessionId));
        LOG.info("Goal resumed for task '{}'", taskId);
    }

    /**
     * 清除 goal（任务保留，调度停止）
     */
    public void clearGoal(String sessionId, String taskId) {
        LoopTask task = getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            LOG.warn("clearGoal: task '{}' not found or not goal mode", taskId);
            return;
        }

        // 移除调度
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }

        saveToFile(sessionId, sessionTasks.get(sessionId));
        LOG.info("Goal cleared for task '{}' (scheduling removed, task preserved)", taskId);
    }

    /**
     * 禁用 goal 调度（goal 达成/预算耗尽后使用，保留任务和 goal 状态）
     */
    private void disableGoalScheduling(String sessionId, LoopTask task) {
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }
        saveToFile(sessionId, sessionTasks.get(sessionId));
    }

    /**
     * 启用/停用任务（toggle enabled 字段）
     */
    public void toggle(String sessionId, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                boolean newEnabled = !t.isEnabled();
                t.setEnabled(newEnabled);

                if (newEnabled) {
                    // 恢复：重新注册 Job（即时模式会被 registerJob 内部跳过）
                    registerJob(sessionId, t);
                } else {
                    // 暂停：移除 Job，但不 cancel
                    String jobName = t.getJobName();
                    if (jobManager.jobExists(jobName)) {
                        jobManager.jobRemove(jobName);
                    }
                }

                saveToFile(sessionId, tasks);
                return;
            }
        }
    }

    /**
     * 更新任务定义（重建 Job）
     */
    public void update(String sessionId, String taskId, LoopTask newTask) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (int i = 0; i < tasks.size(); i++) {
            LoopTask t = tasks.get(i);
            if (t.getId().equals(taskId)) {
                // 移除旧 Job
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }

                // 替换为新任务
                tasks.set(i, newTask);

                // 如果 enabled 且未取消，注册新 Job
                if (newTask.isEnabled() && !newTask.isCancelled()) {
                    registerJob(sessionId, newTask);
                }

                saveToFile(sessionId, tasks);
                return;
            }
        }
    }

    /**
     * 手动触发一次执行（不走定时）
     */
    public void trigger(String sessionId, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                // 异步执行，避免阻塞 HTTP 请求
                Thread thread = new Thread(() -> onTrigger(sessionId, t), "loop-trigger-" + taskId);
                thread.setDaemon(true);
                thread.start();
                return;
            }
        }
    }

    /**
     * 根据 ID 获取任务
     */
    public LoopTask getTaskById(String sessionId, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return null;
        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) return t;
        }
        return null;
    }

    // ==================== 任务列表 ====================

    /**
     * 列出活跃任务（自动清理过期）
     */
    public List<LoopTask> listActive(String sessionId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();

        // 清理过期任务
        cleanExpired(sessionId, tasks);

        return new ArrayList<>(tasks);
    }

    /**
     * 列出所有任务（含已停用的），自动清理过期
     */
    public List<LoopTask> listAll(String sessionId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();

        // 清理过期任务
        cleanExpired(sessionId, tasks);

        return new ArrayList<>(tasks);
    }

    // ==================== 批量停止 ====================

    /**
     * 停止会话的所有任务
     */
    public void stopAll(String sessionId) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            tasks.forEach(t -> {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
                // F6: 清理 worktree
                if (t.isWorktreeEnabled() ) {
                    getWorktreeManager().cleanup(engine.getWorkspace());
                }
            });
        }
        // 删除 JSON 文件
        deleteFile(sessionId);
    }

    // ==================== 会话恢复 ====================

    /**
     * 从 JSON 恢复任务 — 过滤过期任务，重新注册到 IJobManager
     *
     * <p>在 CliShell.prepare() 或 ResumeCommand 中调用
     */
    public void restore(String sessionId) {
        List<LoopTask> tasks = loadFromFile(sessionId);
        if (tasks == null || tasks.isEmpty()) return;

        // 移除过期/已取消任务
        List<LoopTask> alive = new ArrayList<>();
        for (LoopTask t : tasks) {
            if (t.isExpired() || t.isCancelled()) {
                continue;
            }
            alive.add(t);
        }

        if (alive.isEmpty()) {
            deleteFile(sessionId);
            return;
        }

        sessionTasks.put(sessionId, Collections.synchronizedList(alive));

        // 重新注册到 IJobManager
        for (LoopTask t : alive) {
            registerJob(sessionId, t);
        }

        // ★ A3: 自动恢复因 SIGINT 中断而暂停的 goal
        for (LoopTask t : alive) {
            if (t.isGoalMode()) {
        GoalState gs = t.getGoalState();
        if (gs.getStatus() == GoalState.Status.PAUSED) {
            LOG.info("A3: auto-resuming paused goal '{}'", t.getId());
            gs.resume();
            t.resetGoalBlockedAudit();
                    registerJob(sessionId, t);
                }
            }
        }

        // 回写（去掉过期任务）
        saveToFile(sessionId, alive);
        LOG.info("Restored {} loop tasks for session {}", alive.size(), sessionId);
    }

    // ==================== IJobManager 注册 ====================

    /**
     * 注册任务到 IJobManager（cron 模式使用 cron 表达式，否则使用 fixedDelay 串行策略）
     */
    private void registerJob(String sessionId, LoopTask task) {
        registerJob(sessionId, task, false);
    }

    /**
     * 注册任务到 IJobManager
     *
     * @param firstRegistration 是否为首次注册（首次注册时，runNow 才生效）
     */
    private void registerJob(String sessionId, LoopTask task, boolean firstRegistration) {
        String jobName = task.getJobName();

        ScheduledAnno scheduled;
        if (task.isCronMode()) {
            scheduled = new ScheduledAnno().cron(task.getCron());
        } else {
            long intervalMs = (long) task.getIntervalMinutes() * 60_000L;
            // isRunNow() 只对首次注册生效：重启恢复、切换启用、更新定义时均不应用
            long initialDelay = (firstRegistration && task.isRunNow()) ? 0 : intervalMs;
            scheduled = new ScheduledAnno()
                    .fixedDelay(intervalMs)
                    .initialDelay(initialDelay);
        }

        jobManager.jobAdd(jobName, scheduled, ctx -> {
            if(task.isEnabled() == false) {
                return;
            }

            onTrigger(sessionId, task);
        });
    }

    // ==================== 定时触发回调 ====================

    /**
     * 定时触发 — 执行任务
     */
    private void onTrigger(String sessionId, LoopTask task) {
        // D1: 每次触发重置续行深度（后续事件驱动续行会重新递增）
        task.setContinuationCount(0);

        // ★ A1: 重置抑制状态（每次执行都重新评估）
        task.setSuppressed(false);

        // 已禁用/过期/已取消则移除
        if (!task.isEnabled() || task.isExpired() || task.isCancelled()) {
            String jobName = task.getJobName();
            if (jobManager.jobExists(jobName)) {
                jobManager.jobRemove(jobName);
            }
            return;
        }

        // 会话正在执行任务时跳过本次触发：不消耗迭代、不创建 worktree、不向前端推送消息。
        // 任一端口的 checker 报告繁忙即跳过（OR 合并）。
        for (BusyChecker checker : busyCheckers) {
            if (checker.isBusy(sessionId)) {
                LOG.info("Loop task '{}' skipped: session '{}' is busy", task.getId(), sessionId);
                return;
            }
        }

        // Goal 模式下的预算检查（状态机处理）
        if (task.isGoalMode()) {
            GoalState gs = task.getGoalState();

            // ★ P0: 时间预算强制执行
            Long maxDurationMs = task.getMaxDurationMs();
            if (maxDurationMs != null && maxDurationMs > 0) {
                long elapsed = System.currentTimeMillis() - gs.getStartEpochMs();
                if (elapsed >= maxDurationMs) {
                    LOG.info("Loop task '{}' goal duration exceeded ({}ms >= {}ms)",
                            task.getId(), elapsed, maxDurationMs);
                    gs.markBudgetLimited();
                    LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                            (String) null, task.getCurrentIteration(), "BUDGET_LIMITED");
                    disableGoalScheduling(sessionId, task);
                    return;
                }
            }

            if (gs.isBudgetExceeded()) {
                LOG.info("Loop task '{}' goal budget exceeded at iteration {}",
                        task.getId(), task.getCurrentIteration());
                gs.markBudgetLimited();
                LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                        (String) null, task.getCurrentIteration(), "BUDGET_LIMITED");
                disableGoalScheduling(sessionId, task);
                return;
            }
            // 非活跃状态（ACHIEVED/PAUSED 等）跳过
            if (!gs.getStatus().isActive()) {
                return;
            }
        } else {
            // 非 Goal 模式：原有最大迭代次数检查
            if (task.isMaxIterationsReached()) {
                LOG.info("Loop task '{}' reached max iterations ({})", task.getId(), task.getMaxIterations());
                remove(sessionId, task);
                return;
            }
        }

        // 防重入：上一个还没执行完则跳过
        if (!task.tryStart()) {
            return;
        }

        try {
            // Phase 4: Worktree 隔离
            String worktreePath = null;
            if (task.isWorktreeEnabled()) {
                worktreePath = getWorktreeManager().create(engine.getWorkspace(), task.getId());
                if (worktreePath != null) {
                    LOG.info("Loop task '{}' executing in worktree: {}", task.getId(), worktreePath);
                } else {
                    LOG.warn("Loop task '{}' worktree creation failed, falling back to main workspace", task.getId());
                }
            }

            try {
                // 构建完整 prompt（注入 skill + state 上下文）
                String effectivePrompt = buildEffectivePrompt(sessionId, task);

                LoopExecutionResult executionResult;

                // 单一 agent 执行
                executionResult = executeSingle(sessionId, effectivePrompt, null);

                String finalResult = executionResult != null ? executionResult.getFinalResult() : null;

                // 更新执行记录
                task.updateLastExecution(finalResult != null ? finalResult : "ok");

                // 仅在执行完成时递增迭代计数，避免 session busy 等场景下空转消耗迭代
                int iteration;
                if (executionResult != null && executionResult.isCompleted()) {
                    iteration = task.incrementIteration();
                } else {
                    iteration = task.getCurrentIteration();
                }

                // ★ P0/P1: Goal 状态机评估（Codex 对齐：仅检测 [GOAL_ACHIEVED] 标记）
                if (task.isGoalMode()) {
                    GoalState gs = task.getGoalState();
                    String transcript = executionResult != null ? executionResult.getFinalResult() : "";

                    // ★ G2: 累计 token 消耗
                    if (executionResult != null && executionResult.getTokensUsed() > 0) {
                        gs.addTokens(executionResult.getTokensUsed());
                    }

                    // 提取评估原因（取 transcript 末尾 200 字符作为评估文本）
                    String evalReason = extractTail(transcript, 200);
                    task.recordGoalEvaluation(evalReason);

                    // 检测 [GOAL_ACHIEVED] 标记
                    boolean achieved = transcript != null && transcript.contains(LoopExecutionResult.GOAL_ACHIEVED);
                    if (achieved) {
                        LOG.info("Loop task '{}' goal ACHIEVED at iteration {}", task.getId(), iteration);
                        gs.achieve();
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "GOAL_ACHIEVED");
                        disableGoalScheduling(sessionId, task);
                        return;
                    }

                    // Codex 自动 blocked 检测：连续 3 轮相同评估原因 → 暂停
                    if (task.isGoalBlocked()) {
                        gs.incrementBlockedCycleCount();
                        if (gs.isBlockedCycleExhausted()) {
                            LOG.warn("Loop task '{}' blocked cycle exhausted ({}), permanently disabling",
                                    task.getId(), gs.getBlockedCycleCount());
                            gs.markBudgetLimited();
                            LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                    executionResult, iteration, "BLOCKED_CYCLE_EXHAUSTED");
                            disableGoalScheduling(sessionId, task);
                            return;
                        }
                        LOG.info("Loop task '{}' goal blocked at iteration {}: {}",
                                task.getId(), iteration, task.getGoalLastEvalReason());
                        gs.pause();
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "GOAL_BLOCKED");
                        disableGoalScheduling(sessionId, task);
                        return;
                    }

                    // 预算检查
                    if (gs.isBudgetExceeded()) {
                        LOG.info("Loop task '{}' budget exceeded at iteration {}", task.getId(), iteration);
                        gs.markBudgetLimited();
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "BUDGET_EXCEEDED");
                        disableGoalScheduling(sessionId, task);
                        return;
                    }

                    // ★ A1: L5 - No-tool-call suppression
                    boolean curHasToolCalls = executionResult != null && executionResult.isHasToolCalls();
                    if (curHasToolCalls == false && !task.isLastHadToolCalls() && iteration > 1) {
                        LOG.info("Loop task '{}' suppressing: no tool calls in iteration {}", task.getId(), iteration);
                        task.setSuppressed(true);
                        task.setLastHadToolCalls(false);
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "NO_TOOL_CALLS");
                        scheduleSuppressionRetry(sessionId, task);
                    } else {
                        task.setLastHadToolCalls(curHasToolCalls);
                    }
                } else {
                    // 非 goal 模式：原有逻辑
                    if (task.isMaxIterationsReached()) {
                        LOG.info("Loop task '{}' reached max iterations ({})", task.getId(), task.getMaxIterations());
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "MAX_ITERATIONS_REACHED");
                        remove(sessionId, task);
                        return;
                    }
                }

                // 写入执行历史
                String stopReason = task.isGoalMode() ? "NONE" : "NONE";
                LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(), executionResult, iteration, stopReason);

                // ★ 实时持久化：每次迭代后立即保存全部状态到 loop-tasks.json
                //     确保 token 消耗、评估结果、迭代计数、抑制状态等不因进程崩溃丢失
                saveToFile(sessionId, sessionTasks.get(sessionId));

            } finally {
                // Phase 4: 清理 worktree（执行完毕后）
                if (worktreePath != null) {
                    getWorktreeManager().remove(worktreePath);
                    LOG.debug("Loop task '{}' worktree cleaned up", task.getId());
                }
            }

            // ★ D1: L2 - 事件驱动续行（未被抑制且 goal 活跃 → 立即触发下一轮）
            //     保护：最大续行深度 + 最小间隔 + 待定用户输入检查
            if (task.isGoalMode() && !task.isSuppressed()) {
                GoalState gs = task.getGoalState();
                if (gs.getStatus().isActive() && !gs.isBudgetExceeded()) {
                    boolean busy = false;
                    for (BusyChecker checker : busyCheckers) {
                        if (checker.isBusy(sessionId)) {
                            busy = true;
                            break;
                        }
                    }
                    if (!busy) {
                        // D1: 检查续行深度
                        int depth = task.getContinuationCount();
                        if (depth >= MAX_CONTINUATION_DEPTH) {
                            LOG.info("Loop task '{}' max continuation depth ({}) reached, waiting for next interval",
                                    task.getId(), MAX_CONTINUATION_DEPTH);
                        }
                        // D1: 检查最小间隔
                        else if (System.currentTimeMillis() - task.getLastContinuationTime() < MIN_CONTINUATION_INTERVAL_MS) {
                            LOG.debug("Loop task '{}' continuation too soon, scheduling retry in {}ms",
                                    task.getId(), MIN_CONTINUATION_INTERVAL_MS);
                            // 延迟重试
                            long delay = MIN_CONTINUATION_INTERVAL_MS;
                            Thread retryThread = new Thread(() -> {
                                try {
                                    Thread.sleep(delay);
                                } catch (InterruptedException e) {
                                    return;
                                }
                                if (!task.isCancelled() && !task.isExpired()) {
                                    onTrigger(sessionId, task);
                                }
                            }, "loop-continuation-retry-" + task.getId());
                            retryThread.setDaemon(true);
                            retryThread.start();
                        } else {
                            LOG.debug("Loop task '{}' continuing (event-driven, depth={})", task.getId(), depth);
                            task.incrementContinuationCount();
                            task.setLastContinuationTime(System.currentTimeMillis());
                            Thread thread = new Thread(
                                    () -> onTrigger(sessionId, task),
                                    "loop-continue-" + task.getId());
                            thread.setDaemon(true);
                            thread.start();
                        }
                    }
                }
            }

        } catch (Exception e) {
            LOG.error("Loop task '{}' failed: {}", task.getId(), e.getMessage());
            task.updateLastExecution("error: " + e.getMessage());
            // ★ P1: 异常时持久化，防止 token 状态丢失
            List<LoopTask> tasks = sessionTasks.get(sessionId);
            if (tasks != null) {
                saveToFile(sessionId, tasks);
            }
        } finally {
            task.finish();
        }
    }

    /**
     * 构建完整的 effective prompt（skill 解析 + goal 条件注入）
     *
     * Codex 对齐：内联 continuation 提示，无独立模板文件。
     * 始终展示进度和目标条件，预算超限时追加告警。
     */
    private String buildEffectivePrompt(String sessionId, LoopTask task) {
        String prompt = task.getPrompt();

        if (!task.isGoalMode()) {
            return prompt;
        }

        GoalState gs = task.getGoalState();
        int iter = task.getCurrentIteration();
        boolean isFirstIter = iter == 0;
        boolean isCritical = gs.isBudgetCritical();

        // 构建预算信息
        String budgetInfo = buildBudgetInfo(gs);

        // Codex 对齐的内联 continuation 提示
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append("你正在朝着这个目标努力：<untrusted_objective>")
          .append(gs.getCondition()).append("</untrusted_objective>");
        sb.append("\n");
        sb.append("你的目标是完成这个目标。\n");
        sb.append("\n");
        sb.append("在继续之前，请完成以下步骤：\n");
        sb.append("1. 回顾：目标是什么？检查已有的进展。\n");
        sb.append("2. 核查：针对目标中的每一项，验证其是否已完成。\n");
        sb.append("3. 如果你已完成所有项，说明你是如何实现每一项的。\n");
        sb.append("   — 然后调用 update_goal(complete) 标记完成。\n");
        sb.append("4. 如果你遇到阻碍（同一困境尝试了3次），请继续执行 —\n");
        sb.append("   系统会自动检测阻塞并暂停。\n");

        if (isCritical) {
            sb.append("\n[紧急] 你的 Token 预算即将耗尽。")
              .append("请专注于高效完成目标。\n");
        }

        sb.append(budgetInfo);

        return prompt + sb.toString();
    }

    /**
     * D3: 构建预算信息字符串（始终显示，Codex 行为）
     */
    private String buildBudgetInfo(GoalState gs) {
        StringBuilder sb = new StringBuilder();

        // Token 预算
        if (gs.getMaxTokens() > 0) {
            long remainToken = gs.getMaxTokens() - gs.getConsumedTokens();
            sb.append("\n已消耗 ").append(formatTokens(gs.getConsumedTokens()))
              .append(" / ").append(formatTokens(gs.getMaxTokens()))
              .append(" (").append(budgetPercent(gs.getConsumedTokens(), gs.getMaxTokens())).append("%)");
            if (remainToken > 0 && gs.isBudgetCritical()) {
                sb.append(" (剩余: ").append(formatTokens(remainToken)).append(")");
            }
        } else if (gs.getConsumedTokens() > 0) {
            sb.append("\n已消耗 Token: ").append(formatTokens(gs.getConsumedTokens()));
        }

        // 时间预算（从 startEpochMs 计算）
        if (gs.getStartEpochMs() > 0) {
            long elapsed = System.currentTimeMillis() - gs.getStartEpochMs();
            if (elapsed > 1000) {
                sb.append("\n耗时: ").append(formatDuration(elapsed));
            }
        }

        return sb.toString();
    }

    /**
     * D3: 百分比计算
     */
    private String budgetPercent(long value, long total) {
        if (total <= 0) return "0";
        return String.valueOf((int) (value * 100 / total));
    }

    // ===== L4: token/时间格式化辅助方法 =====

    private String formatTokens(long tokens) {
        if (tokens < 1000) return tokens + " tokens";
        if (tokens < 1_000_000) return String.format("%.1fk", tokens / 1000.0);
        return String.format("%.1fM", tokens / 1_000_000.0);
    }

    private String formatDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        if (ms < 3_600_000) return (ms / 60_000) + "m " + ((ms % 60_000) / 1000) + "s";
        return (ms / 3_600_000) + "h " + ((ms % 3_600_000) / 60_000) + "m";
    }

    /**
     * 取字符串末尾最多 maxLen 字符（用于提取评估原因摘要）
     */
    private static String extractTail(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        int len = Math.min(text.length(), maxLen);
        return text.substring(text.length() - len).replace('\n', ' ').trim();
    }

    /**
     * ★ A1: 调度抑制状态下的延迟复检（30 秒后自动尝试恢复）
     */
    private void scheduleSuppressionRetry(String sessionId, LoopTask task) {
        LOG.info("Loop task '{}' scheduling suppression retry in 30s", task.getId());
        Thread retryThread = new Thread(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                return;
            }
            // 检查 goal 是否仍活跃且被抑制
            if (task.isCancelled() || task.isExpired()) return;
            if (task.isSuppressed()) {
                LOG.info("Loop task '{}' suppression retry: resuming", task.getId());
                task.setSuppressed(false);
                onTrigger(sessionId, task);
            }
        }, "loop-suppression-retry-" + task.getId());
        retryThread.setDaemon(true);
        retryThread.start();
    }

    private LoopExecutionResult executeSingle(String sessionId, String effectivePrompt, String agentName) {
        for (TaskExecutor taskExecutor : taskExecutors) {
            String result = taskExecutor.execute(sessionId, effectivePrompt, agentName);
            if (result != null) {
                return LoopExecutionResult.fromText(result);
            }
        }
        return LoopExecutionResult.submittedOnly();
    }

    // ==================== 清理过期任务 ====================

    /**
     * 清理内存列表中的过期/已取消任务，并同步 IJobManager 和 JSON
     */
    private void cleanExpired(String sessionId, List<LoopTask> tasks) {
        boolean changed = tasks.removeIf(t -> {
            if (t.isExpired() || t.isCancelled()) {
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
                return true;
            }
            return false;
        });

        if (changed) {
            saveToFile(sessionId, tasks);
        }
    }

    // ==================== JSON 持久化 ====================

    /**
     * 获取任务 JSON 文件路径
     * 位于会话目录下：&lt;workspace&gt;/&lt;harnessSessions&gt;/&lt;sessionId&gt;/loop_tasks.json
     */
    private Path getTasksFilePath(String sessionId) {
        return Paths.get(engine.getWorkspace(), engine.getHarnessSessions(), sessionId, TASKS_FILE);
    }
    /**
     * 将任务列表保存到 JSON 文件（原子写入：先写临时文件，再 rename）
     */
    private void saveToFile(String sessionId, List<LoopTask> tasks) {
        try {
            Path filePath = getTasksFilePath(sessionId);
            Files.createDirectories(filePath.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            for (LoopTask t : tasks) {
                root.add(t.toONode());
            }
            String json = root.toJson();

            // 原子写入：先写临时文件，再 rename
            Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                    StandardCharsets.UTF_8)) {
                w.write(json);
            }
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.error("Failed to save loop tasks: {}", e.getMessage());
        }
    }

    /**
     * 从 JSON 文件加载任务列表
     */
    private List<LoopTask> loadFromFile(String sessionId) {
        try {
            Path filePath = getTasksFilePath(sessionId);
            if (!Files.exists(filePath)) return null;

            String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            ONode root = ONode.ofJson(json);

            List<LoopTask> tasks = new ArrayList<>();
            for (ONode node : root.getArray()) {
                tasks.add(LoopTask.fromONode(node));
            }

            LOG.info("Succeeded load loop tasks[{}]: {}项", sessionId, tasks.size());

            return tasks;
        } catch (Exception e) {
            LOG.error("Failed to load loop tasks[{}]: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 删除 JSON 文件
     */
    private void deleteFile(String sessionId) {
        try {
            Path filePath = getTasksFilePath(sessionId);
            Files.deleteIfExists(filePath);
        } catch (Exception ignored) {
            // ignored
        }
    }
}