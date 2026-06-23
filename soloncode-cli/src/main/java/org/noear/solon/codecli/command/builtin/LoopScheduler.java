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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    private final LoopConfig loopConfig;
    private final HarnessEngine engine;
    private final IJobManager jobManager;
    private final ConcurrentHashMap<String, List<LoopTask>> sessionTasks = new ConcurrentHashMap<>();

    // 共享线程池：续行调度 + 异常重试
    private final ScheduledExecutorService asyncExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "loop-async");
        t.setDaemon(true);
        return t;
    });

    private volatile List<TaskExecutor> taskExecutors = new ArrayList<>();
    private volatile List<BusyChecker> busyCheckers = new ArrayList<>();
    private volatile WorktreeManager worktreeManager;
    private final String worktreeDir;

    @FunctionalInterface
    public interface TaskExecutor {
        String execute(String sessionId, String prompt, String agentName);
    }

    @FunctionalInterface
    public interface BusyChecker {
        boolean isBusy(String sessionId);
    }

    public LoopScheduler(HarnessEngine engine, String worktreeDir) {
        this.engine = engine;
        this.jobManager = JobManager.getInstance();
        this.worktreeDir = worktreeDir;
        this.loopConfig = new LoopConfig();
    }

    public void addTaskExecutor(TaskExecutor executor) {
        this.taskExecutors.add(executor);
    }

    public void addBusyChecker(BusyChecker busyChecker) {
        if (busyChecker != null) {
            this.busyCheckers.add(busyChecker);
        }
    }

    /**
     * 跨会话查找活跃 goal（PURSUING 优先，PAUSED/BLOCKED 次之）
     */
    public LoopTask findActiveGoalAcrossSessions(String sessionId) {
        List<LoopTask> taskList = sessionTasks.get(sessionId);

        for (LoopTask t : taskList) {
            if (t.isGoalMode() && t.getGoalState().getStatus().isActive()) {
                return t;
            }
        }

        for (LoopTask t : taskList) {
            if (t.isGoalMode() && t.getGoalState().getStatus().isResumable()) {
                return t;
            }
        }

        return null;
    }

    // ==================== ShutdownHook ====================

    private void installInterruptHandler() {
        if (interruptHandlerInstalled) {
            return;
        }
        interruptHandlerInstalled = true;

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("JVM shutting down, pausing active goals");
                pauseAllGoals();
                asyncExecutor.shutdown();
            }, "goal-shutdown-hook"));
            LOG.info("ShutdownHook installed for auto-pausing active goals");
        } catch (Throwable e) {
            LOG.warn("Cannot install ShutdownHook: {}", e.getMessage());
        }
    }

    private void pauseAllGoals() {
        for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
            for (LoopTask task : entry.getValue()) {
                if (task.isGoalMode()) {
                    GoalState gs = task.getGoalState();
                    if (gs.getStatus() == GoalState.Status.PURSUING) {
                        gs.pause();
                        disableGoalScheduling(entry.getKey(), task);
                        LOG.info("goal '{}' paused due to JVM shutdown", task.getId());
                    }
                }
            }
        }
    }

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

    public LoopTask schedule(String sessionId, LoopTask task) {
        List<LoopTask> tasks = sessionTasks.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        if (tasks.size() >= MAX_TASKS_PER_SESSION) {
            throw new IllegalStateException("Max tasks reached: " + MAX_TASKS_PER_SESSION);
        }

        cleanExpired(sessionId, tasks);
        registerJob(sessionId, task, true);
        tasks.add(task);
        saveToFile(sessionId, tasks);

        if (task.isGoalMode()) {
            installInterruptHandler();
        }

        return task;
    }

    // ==================== 任务移除 ====================

    public void remove(String sessionId, LoopTask task) {
        LOG.info("Removing loop task '{}' from session '{}'", task.getId(), sessionId);

        task.cancel();
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }

        if (task.isWorktreeEnabled()) {
            try {
                getWorktreeManager().cleanup(engine.getWorkspace());
            } catch (Exception e) {
                LOG.warn("Loop task '{}' worktree cleanup failed: {}", task.getId(), e.getMessage());
            }
        }

        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        tasks.removeIf(t -> t.getId().equals(task.getId()));
        saveToFile(sessionId, tasks);
    }

    // ==================== Goal 生命周期管理 ====================

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

        disableGoalScheduling(sessionId, task);
        LOG.info("Goal paused for task '{}'", taskId);
    }

    /**
     * 恢复 goal（PAUSED/BLOCKED → PURSUING），重新注册调度
     */
    public void resumeGoal(String sessionId, String taskId) {
        LoopTask task = getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            LOG.warn("resumeGoal: task '{}' not found or not goal mode", taskId);
            return;
        }

        GoalState gs = task.getGoalState();
        if (!gs.resume()) {
            LOG.warn("resumeGoal: task '{}' cannot be resumed (status={})", taskId, gs.getStatus());
            return;
        }

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

        disableGoalScheduling(sessionId, task);
        LOG.info("Goal cleared for task '{}'", taskId);
    }

    private void disableGoalScheduling(String sessionId, LoopTask task) {
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }
        saveToFile(sessionId, sessionTasks.get(sessionId));
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
                    if (jobManager.jobExists(jobName)) {
                        jobManager.jobRemove(jobName);
                    }
                }

                saveToFile(sessionId, tasks);
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
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }

                tasks.set(i, newTask);

                if (newTask.isEnabled() && !newTask.isCancelled()) {
                    registerJob(sessionId, newTask);
                }

                saveToFile(sessionId, tasks);
                return;
            }
        }
    }

    public void trigger(String sessionId, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                asyncExecutor.submit(() -> onTrigger(sessionId, t));
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

    // ==================== 批量停止 ====================

    public void stopAll(String sessionId) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            tasks.forEach(t -> {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
                if (t.isWorktreeEnabled()) {
                    getWorktreeManager().cleanup(engine.getWorkspace());
                }
            });
        }
        deleteFile(sessionId);
    }

    // ==================== 会话恢复 ====================

    public void restore(String sessionId) {
        List<LoopTask> tasks = loadFromFile(sessionId);
        if (tasks == null || tasks.isEmpty()) return;

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

        for (LoopTask t : alive) {
            registerJob(sessionId, t);
        }

        // 自动恢复因 SIGINT 中断而暂停的 goal
        for (LoopTask t : alive) {
            if (t.isGoalMode()) {
                GoalState gs = t.getGoalState();
                if (gs.getStatus().isResumable()) {
                    LOG.info("Auto-resuming paused/blocked goal '{}'", t.getId());
                    gs.resume();
                    registerJob(sessionId, t);
                }
            }
        }

        saveToFile(sessionId, alive);
        LOG.info("Restored {} loop tasks for session {}", alive.size(), sessionId);
    }

    // ==================== IJobManager 注册 ====================

    private void registerJob(String sessionId, LoopTask task) {
        registerJob(sessionId, task, false);
    }

    private void registerJob(String sessionId, LoopTask task, boolean firstRegistration) {
        String jobName = task.getJobName();

        ScheduledAnno scheduled;
        if (task.isCronMode()) {
            scheduled = new ScheduledAnno().cron(task.getCron());
        } else {
            long intervalMs = (long) task.getIntervalMinutes() * 60_000L;
            // Goal 模式 intervalMinutes=0 时，设为 5 秒保底间隔
            if (intervalMs == 0) {
                intervalMs = 5_000L;
            }
            
            long initialDelay = (firstRegistration && task.isRunNow()) ? 0 : intervalMs;
            scheduled = new ScheduledAnno()
                    .fixedDelay(intervalMs)
                    .initialDelay(initialDelay);
        }

        jobManager.jobAdd(jobName, scheduled, ctx -> {
            if (!task.isEnabled()) {
                return;
            }
            onTrigger(sessionId, task);
        });
    }

    // ==================== 定时触发回调 ====================

    /**
     * 定时触发 — 执行任务
     *
     * <p>Goal 模式下，执行完成后若 goal 仍活跃则事件驱动续行（submit 下一轮）。
     * 续行无深度限制、无冷却期 — 靠 tryStart() CAS 防重叠 + BusyChecker 防冲突。
     */
    private void onTrigger(String sessionId, LoopTask task) {
        // 已禁用/过期/已取消则移除
        if (!task.isEnabled() || task.isExpired() || task.isCancelled()) {
            String jobName = task.getJobName();
            if (jobManager.jobExists(jobName)) {
                jobManager.jobRemove(jobName);
            }
            return;
        }

        // 会话繁忙时跳过
        for (BusyChecker checker : busyCheckers) {
            if (checker.isBusy(sessionId)) {
                LOG.info("Loop task '{}' skipped: session '{}' is busy", task.getId(), sessionId);
                return;
            }
        }

        // Goal 模式预算检查
        if (task.isGoalMode()) {
            GoalState gs = task.getGoalState();

            // 时间预算
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

            // Token 预算
            if (gs.isBudgetExceeded()) {
                LOG.info("Loop task '{}' goal budget exceeded at iteration {}",
                        task.getId(), task.getCurrentIteration());
                gs.markBudgetLimited();
                LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                        (String) null, task.getCurrentIteration(), "BUDGET_LIMITED");
                disableGoalScheduling(sessionId, task);
                return;
            }

            // 非活跃状态跳过
            if (!gs.getStatus().isActive()) {
                return;
            }
        } else {
            // 非 Goal 模式
            if (task.isMaxIterationsReached()) {
                LOG.info("Loop task '{}' reached max iterations ({})", task.getId(), task.getMaxIterations());
                remove(sessionId, task);
                return;
            }
        }

        // 防重入
        if (!task.tryStart()) {
            return;
        }

        try {
            // Worktree 隔离
            String worktreePath = null;
            if (task.isWorktreeEnabled()) {
                worktreePath = getWorktreeManager().create(engine.getWorkspace(), task.getId());
                if (worktreePath != null) {
                    LOG.info("Loop task '{}' executing in worktree: {}", task.getId(), worktreePath);
                }
            }

            try {
                // 构建 prompt（注入 goal 引导词）
                String effectivePrompt = buildEffectivePrompt(sessionId, task);

                LoopExecutionResult executionResult = executeSingle(sessionId, effectivePrompt, null);

                String finalResult = executionResult != null ? executionResult.getFinalResult() : null;
                task.updateLastExecution(finalResult != null ? finalResult : "ok");

                int iteration;
                if (executionResult != null && executionResult.isCompleted()) {
                    iteration = task.incrementIteration();
                } else {
                    iteration = task.getCurrentIteration();
                }

                // Goal 状态评估（Codex 对齐：仅检测 [GOAL_ACHIEVED] 标记）
                if (task.isGoalMode()) {
                    GoalState gs = task.getGoalState();

                    // 累计 token
                    if (executionResult != null && executionResult.getTokensUsed() > 0) {
                        gs.addTokens(executionResult.getTokensUsed());
                    }

                    // 完成检测
                    boolean achieved = executionResult != null && executionResult.isGoalAchieved();
                    if (achieved) {
                        LOG.info("Loop task '{}' goal ACHIEVED at iteration {}", task.getId(), iteration);
                        gs.achieve();
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "GOAL_ACHIEVED");
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
                } else {
                    // 非 goal 模式
                    if (task.isMaxIterationsReached()) {
                        LOG.info("Loop task '{}' reached max iterations ({})", task.getId(), task.getMaxIterations());
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "MAX_ITERATIONS_REACHED");
                        remove(sessionId, task);
                        return;
                    }
                }

                // 写入执行历史
                LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                        executionResult, iteration, "NONE");

                // 实时持久化
                saveToFile(sessionId, sessionTasks.get(sessionId));

            } finally {
                if (worktreePath != null) {
                    getWorktreeManager().remove(worktreePath);
                }
            }

            // 事件驱动续行：goal 活跃 → submit 下一轮
            if (task.isGoalMode()) {
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
                        LOG.debug("Loop task '{}' continuing (event-driven)", task.getId());
                        asyncExecutor.submit(() -> onTrigger(sessionId, task));
                    }
                }
            }

        } catch (Exception e) {
            LOG.error("Loop task '{}' failed: {}", task.getId(), e.getMessage());
            task.updateLastExecution("error: " + e.getMessage());
            List<LoopTask> tasks = sessionTasks.get(sessionId);
            if (tasks != null) {
                saveToFile(sessionId, tasks);
            }
            // 异常后延迟重试
            if (task.isGoalMode() && !task.isCancelled() && !task.isExpired()) {
                GoalState gs = task.getGoalState();
                if (gs.getStatus().isActive() && !gs.isBudgetExceeded()) {
                    LOG.info("Loop task '{}' scheduling error retry in 5s", task.getId());
                    asyncExecutor.schedule(() -> {
                        if (!task.isCancelled() && !task.isExpired()) {
                            onTrigger(sessionId, task);
                        }
                    }, 5, TimeUnit.SECONDS);
                }
            }
        } finally {
            task.finish();
        }
    }

    // ==================== Prompt 构建（Codex 对齐：7 章节） ====================

    /**
     * 构建完整的 effective prompt（goal 引导词注入）
     *
     * <p>对齐 Codex continuation.md 的 7 章节结构：
     * <ol>
     *   <li>Continuation behavior — 跨 turn 持久性</li>
     *   <li>Budget — token 用量 / 预算 / 剩余</li>
     *   <li>Work from evidence — 强制验证当前状态</li>
     *   <li>Progress visibility — 上一轮摘要</li>
     *   <li>Fidelity — 禁止缩小目标范围</li>
     *   <li>Completion audit — 逐条需求审计</li>
     *   <li>Blocked audit — 3 轮规则</li>
     * </ol>
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

        String budgetInfo = buildBudgetInfo(gs);

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append("--- Goal Continuation ---\n");
        sb.append("你正在朝向以下目标工作: ").append(gs.getCondition()).append("\n");
        sb.append("你的目标是完成此任务。这是持续性的工作 — 每一轮执行都是同一个目标的延续。\n");
        sb.append("\n");

        // Chapter 3: Work from evidence
        sb.append("--- 基于证据工作 ---\n");
        sb.append("不要依赖记忆或假设来判断当前状态。在采取行动前，先检查实际的文件内容、\n");
        sb.append("测试结果、构建输出等客观证据。你的判断必须基于最新的事实，而非上轮的记忆。\n");
        sb.append("\n");

        // Chapter 5: Fidelity
        sb.append("--- 目标忠实度 ---\n");
        sb.append("不要缩小目标范围或降低完成标准。目标中的每一项都必须完成。\n");
        sb.append("不要留占位符、TODO 或 stub。如果某个部分很难，要投入精力解决，而非跳过。\n");
        sb.append("\n");

        // Chapter 6: Completion audit
        sb.append("--- 完成审计 ---\n");
        sb.append("在继续之前，请完成以下步骤：\n");
        sb.append("1. 回顾：目标是什么？检查已有的进展。\n");
        sb.append("2. 核查：针对目标中的每一项，通过运行测试、检查文件等客观手段验证其是否已完成。\n");
        sb.append("   不要仅凭推理 — 必须有权威证据（测试通过、构建成功、文件存在且内容正确）。\n");
        sb.append("3. 如果你已完成所有项，说明你是如何实现每一项的，\n");
        sb.append("   然后在回复末尾输出 [GOAL_ACHIEVED] 并调用 update_goal(complete) 标记完成。\n");
        sb.append("\n");

        // Chapter 7: Blocked audit
        sb.append("--- 阻塞审计 ---\n");
        sb.append("如果你遇到阻碍（同一困境尝试了 3 次），调用 update_goal(blocked) 声明阻塞。\n");
        sb.append("不要因为工作困难、进展慢或不确定就声明阻塞 — 仅当同一问题反复尝试仍无法解决时才使用。\n");
        sb.append("resume 后阻塞计数重置为 0。\n");
        sb.append("\n");

        // Chapter 2: Budget
        if (isCritical) {
            sb.append("[紧急] 你的 Token 预算即将耗尽。请专注于高效完成目标。\n");
        }

        // Chapter 4: Progress visibility — 上一轮摘要
        if (!isFirstIter && task.getLastResult() != null) {
            String lastSummary = truncateForPrompt(task.getLastResult(), 300);
            sb.append("\n--- 上一轮执行摘要（第 ").append(iter).append(" 轮）---\n");
            sb.append(lastSummary).append("\n");
            sb.append("请基于以上进展继续推进，避免重复已尝试过的方案。\n");
        }

        sb.append(budgetInfo);

        return prompt + sb.toString();
    }

    // ==================== 格式化辅助 ====================

    private static String truncateForPrompt(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLen) return text;
        int half = maxLen / 2;
        return text.substring(0, half) + "\n...(省略)...\n" + text.substring(text.length() - half);
    }

    private String buildBudgetInfo(GoalState gs) {
        StringBuilder sb = new StringBuilder();

        if (gs.getMaxTokens() > 0) {
            long remainToken = gs.getMaxTokens() - gs.getConsumedTokens();
            sb.append("\n已消耗 ").append(formatTokens(gs.getConsumedTokens()))
              .append(" / ").append(formatTokens(gs.getMaxTokens()))
              .append(" (").append(budgetPercent(gs.getConsumedTokens(), gs.getMaxTokens())).append("%)");
            if (remainToken > 0 && gs.isBudgetCritical()) {
                sb.append(" (剩余: ").append(formatTokens(remainToken)).append(")");
            }
            if (gs.isBudgetWarning()) {
                sb.append("\n[预算提示] 已使用 ").append(budgetPercent(gs.getConsumedTokens(), gs.getMaxTokens()))
                  .append("%，请评估是否需要调整策略或申请扩容");
            }
        } else if (gs.getConsumedTokens() > 0) {
            sb.append("\n已消耗 Token: ").append(formatTokens(gs.getConsumedTokens()));
        }

        if (gs.getStartEpochMs() > 0) {
            long elapsed = System.currentTimeMillis() - gs.getStartEpochMs();
            if (elapsed > 1000) {
                sb.append("\n耗时: ").append(formatDuration(elapsed));
            }
        }

        return sb.toString();
    }

    private String budgetPercent(long value, long total) {
        if (total <= 0) return "0";
        return String.valueOf((int) (value * 100 / total));
    }

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

    // ==================== 执行 ====================

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

    private Path getTasksFilePath(String sessionId) {
        return Paths.get(engine.getWorkspace(), engine.getHarnessSessions(), sessionId, TASKS_FILE);
    }

    private void saveToFile(String sessionId, List<LoopTask> tasks) {
        try {
            Path filePath = getTasksFilePath(sessionId);
            Files.createDirectories(filePath.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            for (LoopTask t : tasks) {
                root.add(t.toONode());
            }
            String json = root.toJson();

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

    private void deleteFile(String sessionId) {
        try {
            Path filePath = getTasksFilePath(sessionId);
            Files.deleteIfExists(filePath);
        } catch (Exception ignored) {
        }
    }
}