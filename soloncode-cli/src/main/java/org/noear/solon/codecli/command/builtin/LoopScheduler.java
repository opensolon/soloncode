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

import static org.noear.solon.codecli.command.builtin.GoalStatus.*;

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

    // ★ P1: Goal 评估器（默认为字符串匹配，可注入替换）
    private GoalEvaluator goalEvaluator = new StringMatchEvaluator();

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
     * 设置 Goal 评估器（可注入替换，默认为 StringMatchEvaluator）
     */
    public void setGoalEvaluator(GoalEvaluator goalEvaluator) {
        if (goalEvaluator != null) {
            this.goalEvaluator = goalEvaluator;
        }
    }

    /**
     * 获取当前 Goal 评估器
     */
    public GoalEvaluator getGoalEvaluator() {
        return goalEvaluator;
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
     * 清除 goal（状态 → TERMINATED），任务保留，调度停止
     */
    public void clearGoal(String sessionId, String taskId) {
        LoopTask task = getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            LOG.warn("clearGoal: task '{}' not found or not goal mode", taskId);
            return;
        }

        GoalState gs = task.getGoalState();
        gs.terminate();

        // 移除调度
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }

        saveToFile(sessionId, sessionTasks.get(sessionId));
        LOG.info("Goal cleared for task '{}'", taskId);
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
            if (gs.isBudgetExceeded()) {
                LOG.info("Loop task '{}' goal budget exceeded at iteration {}/{}",
                        task.getId(), gs.getCurrentIteration(), gs.getMaxIterations());
                gs.markBudgetLimited();
                LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                        (String) null, gs.getCurrentIteration(), "BUDGET_LIMITED");
                disableGoalScheduling(sessionId, task);
                return;
            }
            // 非活跃状态（ACHIEVED/UNMET/PAUSED 等）跳过
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

                // ★ P0/P1: Goal 状态机评估（使用 GoalEvaluator 获得结构化结果）
                if (task.isGoalMode()) {
                    GoalState gs = task.getGoalState();
                    gs.setCurrentIteration(iteration);

                    // 使用评估器获得结构化评估结果
                    String transcript = executionResult != null ? executionResult.getFinalResult() : "";
                    GoalEvaluation eval = goalEvaluator.evaluate(
                            gs.getCondition(),
                            transcript != null ? transcript : "",
                            gs.getHistory());

                    // 记录评估结果
                    gs.addEvaluation(eval);

                    if (eval.isAchieved()) {
                        LOG.info("Loop task '{}' goal ACHIEVED at iteration {}", task.getId(), iteration);
                        gs.achieve();
                        gs.setLastEvaluationReason(eval.getReason());
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "GOAL_ACHIEVED");
                        disableGoalScheduling(sessionId, task);
                        return;
                    }

                    // ★ P1: 检查 BLOCKED 状态（模型声明阻塞）
                    String evalReason = eval.getReason();
                    if (evalReason != null && evalReason.startsWith("[GOAL_BLOCKED]")) {
                        LOG.info("Loop task '{}' goal BLOCKED at iteration {}: {}",
                                task.getId(), iteration, evalReason);
                        gs.block(evalReason);
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "GOAL_BLOCKED");
                        disableGoalScheduling(sessionId, task);
                        return;
                    }

                    // 评估 reason 注入（buildEffectivePrompt 中已通过 GoalState.lastEvaluationReason 读取）
                    gs.setLastEvaluationReason(evalReason);

                    // Goal 模式下的迭代边界检查
                    if (task.isMaxIterationsReached()) {
                        LOG.info("Loop task '{}' reached max iterations ({})", task.getId(), task.getMaxIterations());
                        LoopStateManager.appendHistory(engine.getWorkspace(), task.getId(),
                                executionResult, iteration, "MAX_ITERATIONS_REACHED");
                        disableGoalScheduling(sessionId, task);
                        return;
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


            } finally {
                // Phase 4: 清理 worktree（执行完毕后）
                if (worktreePath != null) {
                    getWorktreeManager().remove(worktreePath);
                    LOG.debug("Loop task '{}' worktree cleaned up", task.getId());
                }
            }

        } catch (Exception e) {
            LOG.error("Loop task '{}' failed: {}", task.getId(), e.getMessage());
            task.updateLastExecution("error: " + e.getMessage());
        } finally {
            task.finish();
        }
    }

    /**
     * 构建完整的有效 prompt（skill 解析 + goal 条件注入）
     *
     * 三阶段自适应模板：
     *   Warmup (iter==0)  — 首次注入协议指令
     *   Steady (1~n)      — 精简反馈，模型已学会协议
     *   Critical (<20%)   — 注入紧迫感 + 具体剩余次数
     */
    private String buildEffectivePrompt(String sessionId, LoopTask task) {
        String prompt = task.getPrompt();

        if (!task.isGoalMode()) {
            return prompt;
        }

        GoalState gs = task.getGoalState();
        int iter = gs.getCurrentIteration();
        int maxIter = gs.getMaxIterations();
        boolean isFirstIter = iter == 0;
        boolean isCritical = gs.isBudgetCritical();

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【目标】").append(gs.getCondition())
          .append("  [").append(iter).append("/").append(maxIter)
          .append("]");

        // 协议指令：仅 Warmup 阶段（首轮）注入
        if (isFirstIter) {
            sb.append("\n【协议】每次回复末尾标注状态 → ")
              .append("[GOAL_ACHIEVED] / [GOAL_BLOCKED] / [GOAL_CONTINUE]");
        }

        // 预算临界提示：具体化，不笼统
        if (isCritical && maxIter > 0) {
            int remaining = maxIter - iter;
            sb.append("\n【注意】仅剩 ").append(remaining)
              .append(" 次迭代，优先完成核心目标，可放弃非关键细节。");
        }

        return prompt + sb;
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