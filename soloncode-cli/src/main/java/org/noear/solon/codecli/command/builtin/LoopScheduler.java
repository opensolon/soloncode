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
import org.noear.solon.ai.harness.channel.Channel;
import org.noear.solon.codecli.config.AgentProperties;
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

    // Solon 原生调度管理器
    private final IJobManager jobManager;

    // 会话级任务列表：sessionId -> list of LoopTask
    private final ConcurrentHashMap<String, List<LoopTask>> sessionTasks = new ConcurrentHashMap<>();

    // CLI 端任务执行回调：sessionId, prompt, agentName -> void（同步阻塞）
    private volatile List<TaskExecutor> taskExecutors = new ArrayList<>();

    // Worktree 管理器（lazy init）
    private volatile WorktreeManager worktreeManager;

    // IM 通道列表（用于通知）
    private volatile List<Channel> channels = new ArrayList<>();

    /**
     * CLI 端任务执行器（同步阻塞）
     *
     * <p>支持指定 agent 名称，用于 maker/checker 分离。
     * 若 agentName 为 null，则使用默认主 agent。
     *
     * <p>返回 AI 的响应文本摘要，用于 maker/checker 编排和 goal 条件检查。
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

    public LoopScheduler() {
        this.jobManager = JobManager.getInstance();
    }

    public void addTaskExecutor(TaskExecutor executor) {
        this.taskExecutors.add(executor);
    }

    /**
     * 注入 IM 通道列表（用于 loop 结果通知）
     */
    public void setChannels(List<Channel> channels) {
        this.channels = channels != null ? channels : new ArrayList<>();
    }

    /**
     * 获取或创建 WorktreeManager（lazy init）
     */
    private WorktreeManager getWorktreeManager() {
        if (worktreeManager == null) {
            synchronized (this) {
                if (worktreeManager == null) {
                    worktreeManager = new WorktreeManager();
                }
            }
        }
        return worktreeManager;
    }

    // ==================== 任务注册 ====================

    /**
     * 注册循环任务
     *
     * <p>流程：创建 LoopTask -> 注册到 IJobManager -> 加入内存列表 -> 持久化到 JSON
     *
     * @param sessionId      会话 ID
     * @param workspace      工作空间路径
     * @param harnessSessions 会话目录相对路径
     * @param task           待注册的任务
     * @return 已注册的任务
     */
    public LoopTask schedule(String sessionId, String workspace, String harnessSessions, LoopTask task) {
        // 1. 检查最大任务数
        List<LoopTask> tasks = sessionTasks.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        if (tasks.size() >= MAX_TASKS_PER_SESSION) {
            throw new IllegalStateException("Max tasks reached: " + MAX_TASKS_PER_SESSION);
        }

        // 2. 清理过期任务
        cleanExpired(sessionId, workspace, harnessSessions, tasks);

        // 3. 注册到 IJobManager
        registerJob(sessionId, task);

        // 4. 加入内存列表
        tasks.add(task);

        // 5. 持久化到 JSON
        saveToFile(sessionId, workspace, harnessSessions, tasks);

        return task;
    }

    // ==================== 任务移除 ====================

    /**
     * 停止指定任务
     */
    public void remove(String sessionId, String workspace, String harnessSessions, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        tasks.removeIf(t -> {
            if (t.getId().equals(taskId)) {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }

                // P1-fix: 清理 worktree
                if (t.isWorktreeEnabled() && t.getWorkspace() != null) {
                    String ws = t.getWorkspace();
                    try {
                        getWorktreeManager().cleanup(ws);
                        LOG.info("Loop task '{}' worktree cleaned up on remove", t.getId());
                    } catch (Exception e) {
                        LOG.warn("Loop task '{}' worktree cleanup failed on remove: {}", t.getId(), e.getMessage());
                    }
                }

                return true;
            }
            return false;
        });

        saveToFile(sessionId, workspace, harnessSessions, tasks);
    }

    /**
     * 启用/停用任务（toggle enabled 字段）
     */
    public void toggle(String sessionId, String workspace, String harnessSessions, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                boolean newEnabled = !t.isEnabled();
                t.setEnabled(newEnabled);

                if (newEnabled) {
                    // 恢复：重新注册 Job
                    registerJob(sessionId, t);
                } else {
                    // 暂停：移除 Job，但不 cancel
                    String jobName = t.getJobName();
                    if (jobManager.jobExists(jobName)) {
                        jobManager.jobRemove(jobName);
                    }
                }

                saveToFile(sessionId, workspace, harnessSessions, tasks);
                return;
            }
        }
    }

    /**
     * 更新任务定义（重建 Job）
     */
    public void update(String sessionId, String workspace, String harnessSessions, String taskId, LoopTask newTask) {
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

                saveToFile(sessionId, workspace, harnessSessions, tasks);
                return;
            }
        }
    }

    /**
     * 手动触发一次执行（不走定时）
     */
    public void trigger(String sessionId, String workspace, String harnessSessions, String taskId) {
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
    public List<LoopTask> listActive(String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();

        // 清理过期任务
        cleanExpired(sessionId, workspace, harnessSessions, tasks);

        return new ArrayList<>(tasks);
    }

    /**
     * 列出所有任务（含已停用的），自动清理过期
     */
    public List<LoopTask> listAll(String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();

        // 清理过期任务
        cleanExpired(sessionId, workspace, harnessSessions, tasks);

        return new ArrayList<>(tasks);
    }

    // ==================== 批量停止 ====================

    /**
     * 停止会话的所有任务
     */
    public void stopAll(String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            tasks.forEach(t -> {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
                // F6: 清理 worktree
                if (t.isWorktreeEnabled() && t.getWorkspace() != null) {
                    String wtWorkspace = t.getWorkspace();
                    getWorktreeManager().cleanup(wtWorkspace);
                }
            });
        }
        // 删除 JSON 文件
        deleteFile(sessionId, workspace, harnessSessions);
    }

    // ==================== 会话恢复 ====================

    /**
     * 从 JSON 恢复任务 — 过滤过期任务，重新注册到 IJobManager
     *
     * <p>在 CliShell.prepare() 或 ResumeCommand 中调用
     */
    public void restore(String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = loadFromFile(sessionId, workspace, harnessSessions);
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
            deleteFile(sessionId, workspace, harnessSessions);
            return;
        }

        sessionTasks.put(sessionId, Collections.synchronizedList(alive));

        // 重新注册到 IJobManager
        for (LoopTask t : alive) {
            registerJob(sessionId, t);
        }

        // 回写（去掉过期任务）
        saveToFile(sessionId, workspace, harnessSessions, alive);
        LOG.info("Restored {} loop tasks for session {}", alive.size(), sessionId);
    }

    // ==================== IJobManager 注册 ====================

    /**
     * 注册任务到 IJobManager（cron 模式使用 cron 表达式，否则使用 fixedDelay 串行策略）
     */
    private void registerJob(String sessionId, LoopTask task) {
        String jobName = task.getJobName();

        ScheduledAnno scheduled;
        if (task.isCronMode()) {
            scheduled = new ScheduledAnno().cron(task.getCron());
        } else {
            long intervalMs = (long) task.getIntervalMinutes() * 60_000L;
            scheduled = new ScheduledAnno()
                    .fixedDelay(intervalMs)
                    .initialDelay(intervalMs);
        }

        jobManager.jobAdd(jobName, scheduled, ctx -> {
            onTrigger(sessionId, task);
        });
    }

    // ==================== 定时触发回调 ====================

    /**
     * 定时触发 — 执行任务
     */
    private void onTrigger(String sessionId, LoopTask task) {
        // 过期或已取消则移除
        if (task.isExpired() || task.isCancelled()) {
            String jobName = task.getJobName();
            if (jobManager.jobExists(jobName)) {
                jobManager.jobRemove(jobName);
            }
            return;
        }

        // Goal 模式：达到最大迭代次数则自动取消
        if (task.isMaxIterationsReached()) {
            LOG.info("Loop task '{}' reached max iterations ({})", task.getId(), task.getMaxIterations());
            removeCurrentTask(sessionId, task);
            return;
        }

        // 防重入：上一个还没执行完则跳过
        if (!task.tryStart()) {
            return;
        }

        try {
            // Phase 4: Worktree 隔离
            String originalWorkspace = null;
            String worktreePath = null;
            if (task.isWorktreeEnabled()) {
                String workspace = task.getWorkspace();
                worktreePath = getWorktreeManager().create(workspace, task.getId());
                if (worktreePath != null) {
                    originalWorkspace = workspace;
                    LOG.info("Loop task '{}' executing in worktree: {}", task.getId(), worktreePath);
                } else {
                    LOG.warn("Loop task '{}' worktree creation failed, falling back to main workspace", task.getId());
                }
            }

            try {
                // 构建完整 prompt（注入 skill + state 上下文）
                String effectivePrompt = buildEffectivePrompt(sessionId, task);

                String executionResult;

                if (task.isMakerCheckerMode()) {
                    // Phase 2: maker/checker 编排
                    executionResult = executeMakerChecker(sessionId, task, effectivePrompt);
                } else {
                    // 兼容路径：单一 agent 执行
                    executionResult = null;
                    for (TaskExecutor taskExecutor : taskExecutors) {
                        String result = taskExecutor.execute(sessionId, effectivePrompt, null);
                        if (result != null) {
                            executionResult = result;
                        }
                    }
                }

                // 更新执行记录
                task.updateLastExecution(executionResult != null ? executionResult : "ok");
                int iteration = task.incrementIteration();

                // F3: Goal 条件检查 — 解析 AI 响应中的 [GOAL_ACHIEVED] 标记
                if (task.isGoalMode() && executionResult != null
                        && executionResult.contains("[GOAL_ACHIEVED]")) {
                    LOG.info("Loop task '{}' goal achieved at iteration {}", task.getId(), iteration);
                    removeCurrentTask(sessionId, task);
                    notifyChannels(sessionId, task);
                    return;
                }

                // 写入执行历史
                if (task.getWorkspace() != null) {
                    String workspace = task.getWorkspace();
                    LoopStateManager.appendHistory(workspace, task.getId(),
                            executionResult != null ? executionResult : "ok", iteration);
                }

            } finally {
                // Phase 4: 清理 worktree（执行完毕后）
                if (worktreePath != null) {
                    getWorktreeManager().remove(worktreePath);
                    LOG.debug("Loop task '{}' worktree cleaned up", task.getId());
                }
            }

            // Phase 5: 通道通知
            notifyChannels(sessionId, task);

        } catch (Exception e) {
            LOG.error("Loop task '{}' failed: {}", task.getId(), e.getMessage());
            task.updateLastExecution("error: " + e.getMessage());
        } finally {
            task.finish();
        }
    }

    /**
     * 构建完整的有效 prompt（skill 解析 + state 上下文注入）
     */
    private String buildEffectivePrompt(String sessionId, LoopTask task) {
        String prompt = task.getPrompt();

        // 1. Skill 引用解析：尝试从 workspace/.soloncode/skills/ 加载 SKILL.md
        if (task.getSkillRef() != null && !task.getSkillRef().isEmpty()
                && task.getWorkspace() != null) {
            try {
                java.nio.file.Path skillFile = java.nio.file.Paths.get(task.getWorkspace(),
                        ".soloncode", "skills", task.getSkillRef(), "SKILL.md");
                if (java.nio.file.Files.exists(skillFile)) {
                    String skillContent = new String(java.nio.file.Files.readAllBytes(skillFile),
                            java.nio.charset.StandardCharsets.UTF_8);
                    // 安全截断：最多注入 4000 字符，防止撑爆上下文
                    if (skillContent.length() > 4000) {
                        skillContent = skillContent.substring(0, 4000) + "\n... (truncated)";
                    }
                    prompt = prompt + "\n\n--- Skill: " + task.getSkillRef() + " ---\n" + skillContent;
                }
            } catch (Exception e) {
                LOG.warn("Failed to load skill '{}': {}", task.getSkillRef(), e.getMessage());
            }
        }

        // 2. 状态上下文注入
        if (task.getWorkspace() != null) {
            String workspace = task.getWorkspace();
            String stateContext = LoopStateManager.buildStateContext(workspace, task.getId());
            if (!stateContext.isEmpty()) {
                prompt = prompt + stateContext;
            }
        }

        // 3. Goal 条件注入
        if (task.isGoalMode()) {
            prompt = prompt + "\n\n--- Goal ---\nTarget condition: " + task.getGoalCondition()
                    + "\nCurrent iteration: " + task.getCurrentIteration()
                    + "/" + task.getMaxIterations()
                    + "\nIf the goal is achieved, respond with [GOAL_ACHIEVED].";
        }

        return prompt;
    }

    /**
     * maker/checker 编排执行
     *
     * <p>Phase 1: maker agent 执行任务，返回结果。
     * Phase 2: checker agent 审查 maker 的实际产出，返回 pass/fail。</p>
     *
     * @return maker 的执行结果摘要
     */
    private String executeMakerChecker(String sessionId, LoopTask task, String effectivePrompt) {
        // Phase 1: maker 执行
        String makerResult = null;
        for (TaskExecutor taskExecutor : taskExecutors) {
            String result = taskExecutor.execute(sessionId, effectivePrompt, task.getMakerAgent());
            if (result != null) {
                makerResult = result;
            }
        }

        // Phase 2: checker 审查（基于 maker 的实际执行结果）
        StringBuilder checkerPrompt = new StringBuilder();
        checkerPrompt.append("Review the results of the following task and verify quality:\n\n");
        checkerPrompt.append("Original task: ").append(task.getPrompt()).append("\n\n");

        if (makerResult != null && !makerResult.isEmpty()) {
            checkerPrompt.append("Maker's execution result:\n").append(makerResult).append("\n\n");
        } else {
            checkerPrompt.append("(Maker did not produce a capturable result)\n\n");
        }

        checkerPrompt.append("Provide a brief assessment: [PASS] or [FAIL] with reasoning.");

        if (task.isGoalMode()) {
            checkerPrompt.append("\n\nAlso evaluate if this goal condition is met: ").append(task.getGoalCondition());
            checkerPrompt.append("\nIf met, respond with [GOAL_ACHIEVED].");
        }

        String checkerResult = null;
        for (TaskExecutor taskExecutor : taskExecutors) {
            String result = taskExecutor.execute(sessionId, checkerPrompt.toString(), task.getCheckerAgent());
            if (result != null) {
                checkerResult = result;
            }
        }

        // 将 checker 的评估结果写入状态
        if (task.getWorkspace() != null && checkerResult != null) {
            String workspace = task.getWorkspace();
            LoopStateManager.appendDecision(workspace, task.getId(),
                    "Checker: " + (checkerResult.length() > 200 ? checkerResult.substring(0, 200) + "..." : checkerResult));
        }

        return makerResult;
    }


    /**
     * 移除当前任务（从 IJobManager 和内存列表中）
     */
    private void removeCurrentTask(String sessionId, LoopTask task) {
        String jobName = task.getJobName();
        if (jobManager.jobExists(jobName)) {
            jobManager.jobRemove(jobName);
        }
        task.cancel();
    }

    /**
     * 供外部移除任务（带持久化）
     */
    public void removeWithPersist(String sessionId, String workspace, String harnessSessions, LoopTask task) {
        removeCurrentTask(sessionId, task);
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks != null) {
            saveToFile(sessionId, workspace, harnessSessions, tasks);
        }
    }

    // ==================== 清理过期任务 ====================

    /**
     * 清理内存列表中的过期/已取消任务，并同步 IJobManager 和 JSON
     */
    private void cleanExpired(String sessionId, String workspace, String harnessSessions, List<LoopTask> tasks) {
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
            saveToFile(sessionId, workspace, harnessSessions, tasks);
        }
    }

    public void shutdown() {
        for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
            for (LoopTask t : entry.getValue()) {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
            }
        }

        // Phase 4: 清理所有 loop worktree
        if (worktreeManager != null) {
            for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
                for (LoopTask t : entry.getValue()) {
                    if (t.isWorktreeEnabled() && t.getWorkspace() != null) {
                        String workspace = t.getWorkspace();
                        worktreeManager.cleanup(workspace);
                        break; // 每个 workspace 只需 cleanup 一次
                    }
                }
            }
        }

        sessionTasks.clear();
        LOG.info("LoopScheduler shutdown: all tasks cancelled and removed from IJobManager");
    }

    // ==================== 通道通知 ====================

    /**
     * 通过 IM 通道通知 loop 执行结果
     */
    private void notifyChannels(String sessionId, LoopTask task) {
        if (channels.isEmpty()) {
            return;
        }

        // 构建通知消息
        StringBuilder msg = new StringBuilder();
        msg.append("[Loop #").append(task.getId()).append("] ");
        msg.append(task.getPrompt());
        if (task.getLastResult() != null) {
            msg.append(" → ").append(task.getLastResult());
        }
        if (task.isGoalMode()) {
            msg.append(" (iter: ").append(task.getCurrentIteration()).append("/").append(task.getMaxIterations()).append(")");
        }

        String message = msg.toString();
        boolean sent = false;

        // 遍历所有已绑定的 IM 通道，自动推送通知
        for (Channel ch : channels) {
            if (ch.isBound(sessionId)) {
                try {
                    ch.sendReply(sessionId, message, true);
                    sent = true;
                    LOG.debug("Loop task '{}' notified via {}", task.getId(), ch.getChannelName());
                } catch (Exception e) {
                    LOG.warn("Failed to notify via {}: {}", ch.getChannelName(), e.getMessage());
                }
            }
        }

        if (!sent) {
            LOG.debug("Loop task '{}' no bound channel found (notification skipped)", task.getId());
        }
    }

    // ==================== JSON 持久化 ====================

    /**
     * 获取任务 JSON 文件路径
     * 位于会话目录下：&lt;workspace&gt;/&lt;harnessSessions&gt;/&lt;sessionId&gt;/loop_tasks.json
     */
    private Path getFilePath(String sessionId, String workspace, String harnessSessions) {
        return Paths.get(workspace, harnessSessions, sessionId, TASKS_FILE);
    }
    /**
     * 将任务列表保存到 JSON 文件（原子写入：先写临时文件，再 rename）
     */
    private void saveToFile(String sessionId, String workspace, String harnessSessions, List<LoopTask> tasks) {
        try {
            Path filePath = getFilePath(sessionId, workspace, harnessSessions);
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
    private List<LoopTask> loadFromFile(String sessionId, String workspace, String harnessSessions) {
        try {
            Path filePath = getFilePath(sessionId, workspace, harnessSessions);
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
    private void deleteFile(String sessionId, String workspace, String harnessSessions) {
        try {
            Path filePath = getFilePath(sessionId, workspace, harnessSessions);
            Files.deleteIfExists(filePath);
        } catch (Exception ignored) {
            // ignored
        }
    }
}