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

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final LoopGroupDo loop;
    private final HarnessEngine engine;
    private final IJobManager jobManager;
    private final ConcurrentHashMap<String, List<LoopTask>> sessionTasks = new ConcurrentHashMap<>();
    private final LoopPromptBuilder promptBuilder;

    private volatile List<TaskHandler> taskHandlers = new ArrayList<>();
    private volatile List<BusyChecker> busyCheckers = new ArrayList<>();
    private final List<GoalListener> goalListeners = new CopyOnWriteArrayList<>();

    /**
     * 任务处理者
     */
    @FunctionalInterface
    public interface TaskHandler {
        String handle(String sessionId, String prompt, String agentName);
    }

    /**
     * 繁忙检测者
     */
    @FunctionalInterface
    public interface BusyChecker {
        boolean isBusy(String sessionId);
    }

    /**
     * Goal 生命周期观察者。桌面端用它把后台多轮执行持续推回同一个对话流。
     */
    @FunctionalInterface
    public interface GoalListener {
        void onChanged(String sessionId, LoopTask task, boolean removed);
    }

    public LoopScheduler(HarnessEngine engine, AgentSettings agentSettings) {
        this.engine = engine;
        this.jobManager = JobManager.getInstance();
        this.loop = agentSettings.getLoop();
        // 同步预算阈值到 GoalState 静态配置
        GoalState.configure(
                loop.getBudgetWarningPercentOrDefault(),
                loop.getBudgetCriticalPercentOrDefault()
        );
        this.promptBuilder = new LoopPromptBuilder(loop.getStagnationThresholdOrDefault());
    }

    public LoopGroupDo getLoopConfig() {
        return loop;
    }

    public void addTaskExecutor(TaskHandler executor) {
        this.taskHandlers.add(executor);
    }

    public void addBusyChecker(BusyChecker busyChecker) {
        if (busyChecker != null) {
            this.busyCheckers.add(busyChecker);
        }
    }

    public void addGoalListener(GoalListener listener) {
        if (listener != null) {
            this.goalListeners.add(listener);
        }
    }

    private void notifyGoalChanged(String sessionId, LoopTask task, boolean removed) {
        if (task == null || !task.isGoalMode()) {
            return;
        }
        for (GoalListener listener : goalListeners) {
            try {
                listener.onChanged(sessionId, task, removed);
            } catch (Throwable error) {
                LOG.debug("Goal listener failed for task '{}': {}", task.getId(), error.getMessage());
            }
        }
    }

    /**
     * 查找指定会话中的 goal（优先返回活跃(PURSUING)，其次返回可恢复态(PAUSED/BLOCKED)）
     */
    public LoopTask findActiveGoalInSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }

        List<LoopTask> taskList = sessionTasks.get(sessionId);
        if (taskList == null) {
            return null;
        }

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

    private synchronized void installInterruptHandler() {
        if (interruptHandlerInstalled) {
            return;
        }
        interruptHandlerInstalled = true;

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("JVM shutting down, pausing active goals");
                pauseAllGoals();
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
            notifyGoalChanged(sessionId, task, false);
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

        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        tasks.removeIf(t -> t.getId().equals(task.getId()));
        saveToFile(sessionId, tasks);
        notifyGoalChanged(sessionId, task, true);
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
            if (gs.getStatus() == GoalState.Status.BLOCKED) {
                disableGoalScheduling(sessionId, task);
                notifyGoalChanged(sessionId, task, false);
                return;
            }
            LOG.warn("pauseGoal: task '{}' cannot be paused (status={})", taskId, gs.getStatus());
            return;
        }

        disableGoalScheduling(sessionId, task);
        LOG.info("Goal paused for task '{}'", taskId);
        notifyGoalChanged(sessionId, task, false);
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
        notifyGoalChanged(sessionId, task, false);
    }

    /** 更新 Goal 目标和预算；调用方应先中断正在执行的旧目标轮次。 */
    public void updateGoalConfiguration(String sessionId, String taskId, String objective,
                                        long maxTokens, int maxIterations) {
        LoopTask task = getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            throw new IllegalArgumentException("Goal not found");
        }
        synchronized (task) {
            task.setPrompt(objective);
            task.setMaxTokens(maxTokens);
            GoalState state = task.getGoalState();
            state.setCondition(objective);
            state.setMaxIterations(maxIterations);
        }
        saveToFile(sessionId, sessionTasks.get(sessionId));
        notifyGoalChanged(sessionId, task, false);
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

    public void stopAll(String sessionId) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            tasks.forEach(t -> {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
            });
        }
        deleteFile(sessionId);
    }

    // ==================== 生命周期 ====================

    /**
     * 是否存在活跃（未停止）的循环/goal 任务。
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
     * 关闭调度器：停止本工作区全部会话的任务并注销调度。
     * 注意：jobManager 为进程级单例（跨工作区共享），此处只按本工作区会话逐个 stopAll，
     * 不 shutdown 全局 JobManager。
     */
    public void shutdown() {
        for (String sessionId : new ArrayList<>(sessionTasks.keySet())) {
            try {
                stopAll(sessionId);
            } catch (Exception e) {
                LOG.warn("[Loop] shutdown session {} failed: {}", sessionId, e.getMessage());
            }
        }
    }

    // ==================== 会话恢复 ====================

    public synchronized void restore(String sessionId) {
        if (sessionTasks.containsKey(sessionId)) {
            return;
        }

        List<LoopTask> tasks = loadFromFile(sessionId);
        if (tasks == null || tasks.isEmpty()) return;

        List<LoopTask> alive = new ArrayList<>();
        for (LoopTask t : tasks) {
            if (t.isCancelled()) {
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
            // kill -9 兜底：running 锁未持久化，但显式释放确保无残留
            t.finish();
            registerJob(sessionId, t);
        }

        // 自动恢复因 SIGINT 中断而暂停的 goal
        for (LoopTask t : alive) {
            if (t.isGoalMode()) {
                GoalState gs = t.getGoalState();
                if (gs.getStatus().isResumable()) {
                    LOG.info("Auto-resuming paused/blocked goal '{}'", t.getId());
                    gs.resume();
                }
            }
        }

        saveToFile(sessionId, alive);
        LOG.info("Restored {} loop tasks for session {}", alive.size(), sessionId);
    }

    /**
     * 恢复会话目录中持久化的全部循环任务。
     */
    public void restoreAll() {
        Path wsSessionsRoot = WorkspaceDataUtil.sessionsPath(engine.getWorkspace());
        if (!Files.isDirectory(wsSessionsRoot)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(wsSessionsRoot)) {
            for (Path sessionPath : stream) {
                if (Files.isDirectory(sessionPath) && Files.exists(sessionPath.resolve(TASKS_FILE))) {
                    restore(sessionPath.getFileName().toString());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to restore all loop tasks: {}", e.getMessage());
        }
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

    /**
     * 单轮 Goal 执行结果（用于 executeGoalRound 返回值）
     */
    enum GoalRoundOutcome {
        /** 正常完成一轮，可继续调度下一轮 */
        CONTINUE,
        /** 目标已达成 */
        ACHIEVED,
        /** 预算耗尽（wrap-up 未达成） */
        BUDGET_EXCEEDED,
        /** 已执行到用户设置的最大轮次 */
        ITERATION_EXCEEDED
    }

    private void onTrigger(String sessionId, LoopTask task) {
        // 调度线程（定时/手动触发/续行/重试）无工作区标记，统一在此打标：
        // 本方法内所有日志（守卫、预算、轮次、错误）随工作区分流，不落到启动工作区文件
        Object logScope = WorkspaceLogRouter.beginScope(engine.getWorkspace());
        try {
            doTrigger(sessionId, task);
        } finally {
            WorkspaceLogRouter.endScope(logScope);
        }
    }

    private void doTrigger(String sessionId, LoopTask task) {
        // ① 前置守卫（禁用/过期/取消 → 繁忙 → 预算/状态/最大迭代）
        if (!checkGuardConditions(sessionId, task)) {
            notifyGoalChanged(sessionId, task, false);
            return;
        }

        // ② CAS 防重入
        if (!task.tryStart()) {
            return;
        }

        notifyGoalChanged(sessionId, task, false);

        try {
            // ③ 执行一轮（含 prompt 构建、AI 调用、状态评估、持久化）
            GoalRoundOutcome outcome = executeGoalRound(sessionId, task);

            // ④ 事件驱动续行：仅 CONTINUE 且 goal 仍活跃时 submit 下一轮
            if (outcome == GoalRoundOutcome.CONTINUE) {
                scheduleContinuation(sessionId, task);
            }
            // ACHIEVED / BUDGET_EXCEEDED / MAX_ITERATIONS 已在 executeGoalRound 内部处理完毕
        } catch (Exception e) {
            handleExecutionError(sessionId, task, e);
        } finally {
            task.finish();
            notifyGoalChanged(sessionId, task, false);
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
            if (jobManager.jobExists(jobName)) {
                jobManager.jobRemove(jobName);
            }
            return false;
        }

        // 会话繁忙时跳过
        for (BusyChecker checker : busyCheckers) {
            if (checker.isBusy(sessionId)) {
                LOG.info("Loop task '{}' skipped: session '{}' is busy", task.getId(), sessionId);
                return false;
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
                    LOG.info("Loop task '{}' goal duration exceeded ({}ms >= {}ms), executing wrap-up turn",
                            task.getId(), elapsed, maxDurationMs);
                    executeBudgetLimitWrapUp(sessionId, task, gs);

                    if (gs.getStatus() != GoalState.Status.ACHIEVED) {
                        gs.markBudgetLimited();
                    }

                    disableGoalScheduling(sessionId, task);
                    return false;
                }
            }

            // Token 预算
            if (gs.isBudgetExceeded()) {
                LOG.info("Loop task '{}' goal budget exceeded at iteration {}, executing wrap-up turn",
                        task.getId(), task.getCurrentIteration());
                executeBudgetLimitWrapUp(sessionId, task, gs);

                if (gs.getStatus() != GoalState.Status.ACHIEVED) {
                    gs.markBudgetLimited();
                }

                disableGoalScheduling(sessionId, task);
                return false;
            }

            // 轮次预算。该检查也覆盖服务重启后已达到上限的 Goal。
            if (gs.isIterationExceeded(task.getCurrentIteration())) {
                LOG.info("Loop task '{}' goal iteration limit reached ({} >= {})",
                        task.getId(), task.getCurrentIteration(), gs.getMaxIterations());
                gs.markIterationLimited();
                disableGoalScheduling(sessionId, task);
                return false;
            }

            // 非活跃状态跳过
            if (!gs.getStatus().isActive()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 执行单轮 Goal 调用（含 prompt 构建、AI 执行、状态评估、持久化）
     *
     * <p>返回 GoalRoundOutcome 枚举，供调用方决定是否续行。
     */
    private GoalRoundOutcome executeGoalRound(String sessionId, LoopTask task) {
        // 构建 prompt（注入 goal 引导词）
        String effectivePrompt = promptBuilder.buildEffectivePrompt(task);

        LoopExecutionResult executionResult = executeSingle(sessionId, effectivePrompt, null);

        String finalResult = executionResult != null ? executionResult.getFinalResult() : null;
        task.updateLastExecution(finalResult != null ? finalResult : "ok");
        task.resetConsecutiveErrors(); // 成功执行后重置连续异常计数

        int iteration;
        if (executionResult != null && executionResult.isCompleted()) {
            iteration = task.incrementIteration();
        } else {
            iteration = task.getCurrentIteration();
        }

        // Goal 状态评估（由 goal_update 工具调用驱动）
        if (task.isGoalMode()) {
            GoalState gs = task.getGoalState();

            // 累计 token
            if (executionResult != null && executionResult.getTokensUsed() > 0) {
                gs.addTokens(executionResult.getTokensUsed());
            }

            // 无进展检测（运行时兜底）
            String currentFingerprint = computeFingerprint(executionResult);
            if (currentFingerprint != null && currentFingerprint.equals(task.getLastFingerprint())) {
                task.recordStagnation();
                LOG.warn("Goal '{}' stagnation: {} consecutive no-progress turns",
                        task.getId(), task.getStagnationCount());
            } else {
                task.resetStagnation();
                task.setLastFingerprint(currentFingerprint);
            }

            // 完成检测：仅通过 GoalState 状态（由 goal_update(complete) 工具调用设置）
            boolean achieved = gs.getStatus() == GoalState.Status.ACHIEVED;

            // 创建、修改、运行等执行型目标必须至少产生一次成功的非 Goal 工具调用。
            // 桌面端会记录本轮真实工具证据，防止模型只回复“已完成”便提前结束。
            if (achieved && requiresActionEvidence(gs.getCondition())
                    && executionResult != null && !executionResult.isHasToolCalls()) {
                gs.rejectAchievement();
                task.updateLastExecution("完成声明被拒绝：本轮没有实际工具执行证据，请继续完成并验证目标。");
                achieved = false;
                LOG.warn("Goal '{}' completion rejected: no action evidence", task.getId());
            }

            if (achieved) {
                LOG.info("Loop task '{}' goal ACHIEVED at iteration {}", task.getId(), iteration);
                disableGoalScheduling(sessionId, task);
                return GoalRoundOutcome.ACHIEVED;
            }

            // 预算检查
            if (gs.isBudgetExceeded()) {
                LOG.info("Loop task '{}' budget exceeded at iteration {}, executing wrap-up turn",
                        task.getId(), iteration);
                executeBudgetLimitWrapUp(sessionId, task, gs);

                // wrap-up 回合若 LLM 认为目标已达成，则标记 ACHIEVED 而非 BUDGET_EXCEEDED
                if (gs.getStatus() == GoalState.Status.ACHIEVED) {
                    disableGoalScheduling(sessionId, task);
                    return GoalRoundOutcome.ACHIEVED;
                } else {
                    gs.markBudgetLimited();
                    disableGoalScheduling(sessionId, task);
                    return GoalRoundOutcome.BUDGET_EXCEEDED;
                }
            }


            if (gs.isIterationExceeded(iteration)) {
                LOG.info("Loop task '{}' iteration limit reached at iteration {}",
                        task.getId(), iteration);
                gs.markIterationLimited();
                disableGoalScheduling(sessionId, task);
                return GoalRoundOutcome.ITERATION_EXCEEDED;
            }
        }



        // 实时持久化
        saveToFile(sessionId, sessionTasks.get(sessionId));

        return GoalRoundOutcome.CONTINUE;
    }

    /**
     * 事件驱动续行：goal 仍活跃且非繁忙时，submit 下一轮 onTrigger
     */
    private void scheduleContinuation(String sessionId, LoopTask task) {
        if (!task.isGoalMode()) {
            return;
        }

        // 已取消则不续行
        if (task.isCancelled()) {
            LOG.debug("Loop task '{}' cancelled, skip continuation", task.getId());
            return;
        }

        GoalState gs = task.getGoalState();
        if (!gs.getStatus().isActive() || gs.isBudgetExceeded()) {
            return;
        }

        boolean busy = false;
        for (BusyChecker checker : busyCheckers) {
            if (checker.isBusy(sessionId)) {
                busy = true;
                break;
            }
        }

        if (!busy) {
            LOG.debug("Loop task '{}' continuing (event-driven)", task.getId());
            // 最小 1s 冷却间隙，防止紧循环空转
            RunUtil.delay(() -> onTrigger(sessionId, task), 1_000L);
        }
    }

    /**
     * 异常分级处理：连续异常 ≥ 阈值时标记 BLOCKED，否则递增延迟重试
     *
     * <p>增强逻辑：
     * <ul>
     *   <li>对错误进行分类（SSL/NETWORK/HTTP_4XX/HTTP_5XX/TOOL_EXECUTION/OTHER）</li>
     *   <li>不可恢复的错误（SSL、HTTP_4XX）连续 2 次即标记 BLOCKED</li>
     *   <li>同类型错误连续 3 次标记 BLOCKED（比总错误阈值更早触发）</li>
     *   <li>记录错误类型和摘要，供 prompt 注入使用</li>
     * </ul>
     */
    private void handleExecutionError(String sessionId, LoopTask task, Exception e) {
        // 错误分类
        String errorType = LoopTask.classifyError(e);
        int sameTypeCount = task.recordError(e);

        LOG.error("Loop task '{}' failed [{}]: {}", task.getId(), errorType, e.getMessage());
        task.updateLastExecution("error [" + errorType + "]: " + e.getMessage());
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks != null) {
            saveToFile(sessionId, tasks);
        }

        // 异常后分级处理（TurnError → blocked）
        if (task.isGoalMode() && !task.isCancelled()) {
            GoalState gs = task.getGoalState();
            if (gs.getStatus().isActive() && !gs.isBudgetExceeded()) {
                int errors = task.incrementConsecutiveErrors();

                // ★ 不可恢复错误快速熔断：SSL/HTTP_4XX 连续 2 次直接 BLOCKED
                if (LoopTask.isNonRecoverable(errorType) && sameTypeCount >= 2) {
                    LOG.warn("Goal '{}' blocked by runtime: non-recoverable error [{}] repeated {} times",
                            task.getId(), errorType, sameTypeCount);
                    gs.markBlocked();
                    pauseGoal(sessionId, task.getId());
                    return;
                }

                // ★ 同类型错误熔断：连续 3 次相同类型错误直接 BLOCKED
                if (sameTypeCount >= 3) {
                    LOG.warn("Goal '{}' blocked by runtime: same error type [{}] repeated {} times",
                            task.getId(), errorType, sameTypeCount);
                    gs.markBlocked();
                    pauseGoal(sessionId, task.getId());
                    return;
                }

                if (errors >= loop.getMaxConsecutiveErrorsOrDefault()) {
                    // 连续异常 → 运行时兜底 blocked
                    LOG.warn("Goal '{}' blocked by runtime: {} consecutive errors",
                            task.getId(), errors);
                    gs.markBlocked();
                    pauseGoal(sessionId, task.getId());

                } else {
                    // 未达阈值 → 递增延迟重试
                    long delay = 5L * errors; // 5s, 10s, 15s ...
                    LOG.info("Loop task '{}' scheduling error retry in {}s (attempt {}, type={}, sameType={})",
                            task.getId(), delay, errors, errorType, sameTypeCount);
                    RunUtil.delay(() -> {
                        if (!task.isCancelled()) {
                            onTrigger(sessionId, task);
                        }
                    }, delay * 1_000L);
                }
            }
        }
    }



    // ==================== 预算耗尽收尾 ====================

    /**
     * 预算耗尽时执行一次收尾 turn（对齐 Codex budget_limit.md）
     *
     * <p>注入 budget_limit 引导词，让模型总结进展和剩余工作，而非直接终止。
     * 收尾 turn 不触发续行。
     */
    private void executeBudgetLimitWrapUp(String sessionId, LoopTask task, GoalState gs) {
        try {
            String wrapUpPrompt = promptBuilder.buildBudgetLimitPrompt(task, gs);
            executeSingle(sessionId, wrapUpPrompt, null);

            // 预算耗尽后仍给 LLM 一次总结机会：LLM 可能调用 goal_update(complete)
            // 通过 GoalState 状态检测完成
            if (gs.getStatus() == GoalState.Status.ACHIEVED) {
                LOG.info("Goal '{}' ACHIEVED during budget wrap-up turn", task.getId());
            }
        } catch (Exception e) {
            LOG.warn("Goal '{}' wrap-up turn failed: {}", task.getId(), e.getMessage());
        }
    }



    // ==================== 无进展指纹计算 ====================

    /**
     * 计算执行指纹（用于无进展检测）
     *
     * <p>基于：是否有工具调用 + 结果文本长度桶（200字/桶）+ 结果行数桶（10行/桶）
     */
    static String computeFingerprint(LoopExecutionResult result) {
        if (result == null) return "null";
        String text = result.getFinalResult();
        if (text == null || text.isEmpty()) return "empty";

        String toolDim = result.isHasToolCalls() ? "1" : "0";
        int lenBucket = text.length() / 200;      // 200 字/桶（原 500）
        int lineBucket = countLines(text) / 10;   // 新增：10 行/桶

        return toolDim + ":" + lenBucket + ":" + lineBucket;
    }

    /**
     * 统计文本行数（用于指纹计算）
     */
    static int countLines(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }



    // ==================== 执行 ====================

    private LoopExecutionResult executeSingle(String sessionId, String effectivePrompt, String agentName) {
        for (TaskHandler taskExecutor : taskHandlers) {
            String result = taskExecutor.handle(sessionId, effectivePrompt, agentName);
            if (result != null) {
                // 优先使用 LLM 返回的真实 token 消耗（Web 端通过 session attrs 传递）
                long tokensUsed = 0;
                Boolean hasToolCalls = null;
                try {
                    AgentSession session = engine.getSession(sessionId);
                    Object val = session.attrs().get("_loop_last_total_tokens");
                    if (val instanceof Number) {
                        tokensUsed = ((Number) val).longValue();
                    }
                    Object toolEvidence = session.attrs().remove("_loop_last_has_tool_calls");
                    if (toolEvidence instanceof Boolean) {
                        hasToolCalls = (Boolean) toolEvidence;
                    }
                } catch (Exception e) {
                    // fallback: 使用 fromText 估算
                }

                if (tokensUsed > 0 || hasToolCalls != null) {
                    long effectiveTokens = tokensUsed > 0
                            ? tokensUsed
                            : Math.max(1, result.length() / 4);
                    return LoopExecutionResult.fromExecution(
                            hasToolCalls != null
                                    ? hasToolCalls
                                    : result.length() > 20 && !result.startsWith("error:"),
                            effectiveTokens, result);
                }
                return LoopExecutionResult.fromText(result);
            }
        }
        return LoopExecutionResult.submittedOnly();
    }

    static boolean requiresActionEvidence(String objective) {
        if (objective == null || objective.trim().isEmpty()) {
            return false;
        }
        String normalized = objective.toLowerCase(Locale.ROOT);
        String[] keywords = {
                "create", "generate", "write", "implement", "modify", "edit", "fix",
                "add", "delete", "remove", "refactor", "build", "test", "run", "install",
                "configure", "deploy", "file",
                "生成", "创建", "新建", "编写", "写入", "修改", "编辑", "修复", "实现",
                "添加", "删除", "重构", "构建", "测试", "运行", "安装", "配置", "部署", "文件"
        };
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** null 表示当前执行入口未提供证据追踪；false/true 表示已启用追踪。 */
    Boolean currentRoundHasActionEvidence(String sessionId) {
        try {
            Object value = engine.getSession(sessionId).attrs().get("_loop_last_has_tool_calls");
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 统计当前会话 TODO 清单中的未完成项（`- [ ]` 待办 + `- [/]` 进行中）。
     *
     * <p>用于 Goal 完成判定联动：模型声明 goal_update(complete) 时，若清单尚有未完成项，
     * 应拒绝完成并退回继续执行，避免“清单未清零却宣称目标达成”的语义脱节。
     *
     * <p>识别规则与 {@link TodoTalent} 的进度页脚保持一致：仅识别形如 {@code - [x]} 的
     * checkbox 行，状态字符大小写兼容。若 TODO.md 不存在或无 checkbox 行，返回 0（不拦截）。
     *
     * @return 未完成项数量；无清单或解析失败时返回 0
     */
    int countUnfinishedTodos(String sessionId) {
        if (sessionId == null) {
            return 0;
        }

        try {
            TodoTalent todoTalent = engine.getTodoTalent();
            if (todoTalent == null) {
                return 0;
            }

            Path todoPath = todoTalent.getTodoPath(engine.getWorkspace(), sessionId);
            if (!Files.exists(todoPath)) {
                return 0;
            }
            String content = new String(Files.readAllBytes(todoPath), StandardCharsets.UTF_8);
            return countUnfinishedCheckboxes(content);
        } catch (Throwable e) {
            LOG.debug("countUnfinishedTodos failed for session '{}': {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 统计 Markdown 文本中的未完成 checkbox 行（`- [ ]` 待办 + `- [/]` 进行中）。
     *
     * <p>识别规则与 {@link TodoTalent} 的进度页脚保持一致：仅识别形如 {@code - [x]} 的
     * checkbox 行，第 4 字符须为 {@code ']'}，状态字符大小写兼容。已完成（{@code x}）
     * 及非 checkbox 行不计入。提取为静态方法以便独立单元测试。
     *
     * @param content TODO.md 文本内容；null 时返回 0
     * @return 未完成项数量
     */
    static int countUnfinishedCheckboxes(String content) {
        if (content == null) {
            return 0;
        }

        int unfinished = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            // 仅识别形如 "- [x]" 的 checkbox 行（第 4 字符须为 ']'）
            if (trimmed.length() < 5 || !trimmed.startsWith("- [") || trimmed.charAt(4) != ']') {
                continue;
            }
            char mark = Character.toLowerCase(trimmed.charAt(3));
            if (mark == ' ' || mark == '/') {
                unfinished++;
            }
        }
        return unfinished;
    }

    // ==================== 清理过期任务 ====================

    private void cleanExpired(String sessionId, List<LoopTask> tasks) {
        boolean changed = tasks.removeIf(t -> {
            if (t.isCancelled()) {
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
        Path wsSessionsRoot = WorkspaceDataUtil.sessionsPath(engine.getWorkspace());
        return wsSessionsRoot.resolve(sessionId).resolve(TASKS_FILE);
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
