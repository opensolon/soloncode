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

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;

import java.time.Duration;
import java.time.Instant;

/**
 * /goal 命令 — 即时目标模式，让 AI 持续工作直到目标达成
 *
 * <p>本质是一个特殊的 /loop 任务：id 固定为 "goal"，intervalMinutes=0（即时模式），
 * 执行完一轮后立即 re-trigger，直到 [GOAL_ACHIEVED] 或迭代耗尽。
 *
 * <pre>
 * /goal fix all failing tests              → 设置目标并立即开始工作
 * /goal                                    → 查看当前目标状态
 * /goal edit <new description>             → 修改目标文本（保留进度）
 * /goal pause                              → 暂停目标
 * /goal resume                             → 恢复目标
 * /goal clear                              → 清除目标
 * </pre>
 *
 * @author noear
 * @since 2026.6.13
 */
public class GoalCommand implements Command {
    private static final String GOAL_TASK_ID = "goal";
    private static final int DEFAULT_MAX_ITERATIONS = 30;

    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    private final LoopScheduler scheduler;

    public GoalCommand(LoopScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return "设定自主目标并循环执行 (<description>, edit <description>, pause, resume, clear)";
    }

    @Override
    public boolean cliOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandContext ctx) throws Exception {
        String sessionId = ctx.getSession().getSessionId();
        HarnessEngine engine = ctx.getEngine();
        String workspace = engine.getWorkspace();
        String harnessSessions = engine.getHarnessSessions();

        String sub = ctx.argAt(0);

        if (sub == null || sub.isEmpty()) {
            // /goal — 查看当前目标状态
            doStatus(ctx, sessionId, workspace, harnessSessions);
        } else if ("pause".equals(sub)) {
            doPause(ctx, sessionId, workspace, harnessSessions);
        } else if ("resume".equals(sub)) {
            doResume(ctx, sessionId, workspace, harnessSessions);
        } else if ("clear".equals(sub)) {
            doClear(ctx, sessionId, workspace, harnessSessions);
        } else if ("edit".equals(sub)) {
            doEdit(ctx, sessionId, workspace, harnessSessions);
        } else {
            // /goal <prompt> — 设置新目标并立即执行
            doSet(ctx, sessionId, workspace, harnessSessions);
        }

        return true;
    }

    /**
     * 查看当前目标状态
     */
    private void doStatus(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        LoopTask goalTask = scheduler.getTaskById(sessionId, GOAL_TASK_ID);

        if (goalTask == null || goalTask.isCancelled()) {
            ctx.println(ctx.color(DIM + "No active goal." + RESET));
            ctx.println(ctx.color(DIM + "Usage: /goal <description>" + RESET));
            return;
        }

        StringBuilder line = new StringBuilder();
        line.append(BOLD).append("Goal: ").append(RESET).append(goalTask.getGoalCondition());

        // 状态
        if (goalTask.isRunning()) {
            line.append(" ").append(YELLOW).append("[running]").append(RESET);
        } else if (!goalTask.isEnabled()) {
            line.append(" ").append(RED).append("[paused]").append(RESET);
        } else {
            line.append(" ").append(GREEN).append("[ready]").append(RESET);
        }

        // 进度
        line.append(" ").append(CYAN).append(goalTask.getCurrentIteration())
            .append("/").append(goalTask.getMaxIterations()).append(RESET);

        ctx.println(ctx.color(line.toString()));

        // Prompt
        ctx.println(ctx.color(DIM + "  " + goalTask.getPrompt() + RESET));

        // 耗时
        if (goalTask.getCreatedAt() != null) {
            ctx.println(ctx.color(DIM + "  Elapsed: " + formatElapsed(goalTask.getCreatedAt()) + RESET));
        }

        // 上次执行
        if (goalTask.getLastExecutedAt() != null) {
            String lastResult = goalTask.getLastResult() != null ? goalTask.getLastResult() : "-";
            ctx.println(ctx.color(DIM + "  Last: " + formatAgo(goalTask.getLastExecutedAt()) + ": " + lastResult + RESET));
        }

        // 可用命令提示
        ctx.println(ctx.color(DIM + "  Commands: /goal edit, /goal pause, /goal clear" + RESET));
    }

    /**
     * 设置新目标并立即执行
     *
     * <p>如果已有活跃 goal，会输出警告提示，需加 --force 强制替换。
     * 这防止用户误触 /goal 清掉长时间运行的 goal 进度。
     */
    private void doSet(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        // 解析参数：--max-iter:N, --force 和剩余文本作为 prompt + goalCondition
        int maxIterations = DEFAULT_MAX_ITERATIONS;
        boolean force = false;
        StringBuilder promptBuilder = new StringBuilder();

        for (int i = 0; ; i++) {
            String arg = ctx.argAt(i);
            if (arg == null) break;

            if (arg.startsWith("--max-iter:")) {
                try {
                    maxIterations = Integer.parseInt(arg.substring("--max-iter:".length()));
                } catch (NumberFormatException e) {
                    ctx.println(ctx.color(RED + "Invalid --max-iter value" + RESET));
                    return;
                }
            } else if ("--force".equals(arg) || "-f".equals(arg)) {
                force = true;
            } else {
                if (promptBuilder.length() > 0) promptBuilder.append(" ");
                promptBuilder.append(arg);
            }
        }

        String prompt = promptBuilder.toString().trim();
        if (prompt.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /goal <description>" + RESET));
            ctx.println(ctx.color(DIM + "  /goal fix all failing tests" + RESET));
            ctx.println(ctx.color(DIM + "  /goal --max-iter:50 refactor the auth module" + RESET));
            return;
        }

        // 基本合理性检查：过短的目标大概率不是有效目标
        if (prompt.length() < 5) {
            ctx.println(ctx.color(YELLOW + "Goal description is too short. A goal should describe a concrete, verifiable outcome." + RESET));
            ctx.println(ctx.color(DIM + "  Example: /goal fix all failing tests" + RESET));
            ctx.println(ctx.color(DIM + "  Example: /goal refactor the auth module to use JWT" + RESET));
            return;
        }

        // 替换确认：如果已有活跃 goal 且未加 --force，提示用户
        LoopTask existing = scheduler.getTaskById(sessionId, GOAL_TASK_ID);
        if (existing != null && !existing.isCancelled() && !force) {
            ctx.println(ctx.color(YELLOW + "A goal is already active:" + RESET));
            ctx.println(ctx.color(DIM + "  " + existing.getGoalCondition()
                      + " (" + existing.getCurrentIteration()
                      + "/" + existing.getMaxIterations() + ")" + RESET));
            ctx.println(ctx.color(YELLOW + "Use --force to replace it: /goal --force " + prompt + RESET));
            return;
        }

        // goalCondition 就是 prompt 本身（用户描述的就是目标）
        String goalCondition = prompt;

        // 创建即时模式任务：id=goal, intervalMinutes=0
        LoopTask goalTask = GoalCommand.createGoalTask(prompt, goalCondition, maxIterations, workspace);

        // 初始化状态目录
        LoopStateManager.init(workspace, goalTask.getId(), prompt);

        // 注册并立即执行（scheduleNow 内部会先移除旧的 id=goal 任务）
        try {
            scheduler.scheduleNow(sessionId, workspace, harnessSessions, goalTask);
        } catch (IllegalStateException e) {
            ctx.println(ctx.color(RED + "Failed: " + e.getMessage() + RESET));
            LoopStateManager.cleanup(workspace, goalTask.getId());
            return;
        }

        // 打印确认
        ctx.println(ctx.color(GREEN + "Goal set and started:" + RESET));
        ctx.println(ctx.color("  " + MAGENTA + "Goal:" + RESET + " " + goalCondition));
        ctx.println(ctx.color("  " + BOLD + "Prompt:" + RESET + " " + prompt));
        ctx.println(ctx.color("  " + DIM + "Max Iterations: " + maxIterations + RESET));
        ctx.println(ctx.color(DIM + "  Use /goal to check status, /goal pause to pause, /goal edit to adjust." + RESET));
    }

    /**
     * 编辑目标文本（保留当前进度和迭代计数）
     *
     * <p>对标 Codex 的 /goal edit：修改目标文本但不清除进度。
     * 通过 LoopTask.copyWithUpdate 创建新任务实例替换旧的。
     */
    private void doEdit(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        LoopTask goalTask = scheduler.getTaskById(sessionId, GOAL_TASK_ID);
        if (goalTask == null || goalTask.isCancelled()) {
            ctx.println(ctx.color(YELLOW + "No active goal to edit. Use /goal <description> to set one." + RESET));
            return;
        }

        // 收集 edit 后面的所有文本作为新的目标描述
        StringBuilder newDescBuilder = new StringBuilder();
        for (int i = 1; ; i++) {
            String arg = ctx.argAt(i);
            if (arg == null) break;
            if (newDescBuilder.length() > 0) newDescBuilder.append(" ");
            newDescBuilder.append(arg);
        }

        String newDesc = newDescBuilder.toString().trim();
        if (newDesc.isEmpty()) {
            ctx.println(ctx.color(YELLOW + "Usage: /goal edit <new description>" + RESET));
            ctx.println(ctx.color(DIM + "  Current: " + goalTask.getGoalCondition() + RESET));
            return;
        }

        // 使用 copyWithUpdate 创建新任务实例（保留 id、迭代计数、状态等）
        LoopTask updated = goalTask.copyWithUpdate(
                newDesc, goalTask.getIntervalMinutes(), goalTask.getCron(),
                newDesc, goalTask.getMakerAgent(), goalTask.getCheckerAgent(),
                goalTask.isWorktreeEnabled(), goalTask.getChannelNotify(),
                goalTask.getMaxIterations(), goalTask.getWorkspace()
        );

        // 更新调度器中的任务（保留 enabled 状态，不重置迭代）
        scheduler.update(sessionId, workspace, harnessSessions, GOAL_TASK_ID, updated);

        // 如果任务正在运行，注入 objective_updated steering 让模型知道目标已变更
        if (updated.isRunning()) {
            String steering = LoopScheduler.buildObjectiveUpdatedSteering(newDesc);
            boolean injected = scheduler.injectSteering(sessionId, workspace, harnessSessions, GOAL_TASK_ID, steering);
            if (injected) {
                ctx.println(ctx.color(CYAN + "  Steering injected: AI will adjust to the new objective." + RESET));
            }
        }

        ctx.println(ctx.color(GREEN + "Goal updated:" + RESET));
        ctx.println(ctx.color("  " + DIM + "Old: " + goalTask.getGoalCondition() + RESET));
        ctx.println(ctx.color("  " + MAGENTA + "New: " + newDesc + RESET));
        ctx.println(ctx.color("  " + DIM + "Progress: " + updated.getCurrentIteration()
                  + "/" + updated.getMaxIterations() + " (preserved)" + RESET));
    }

    /**
     * 暂停目标
     */
    private void doPause(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        LoopTask goalTask = scheduler.getTaskById(sessionId, GOAL_TASK_ID);
        if (goalTask == null || goalTask.isCancelled()) {
            ctx.println(ctx.color(YELLOW + "No active goal to pause." + RESET));
            return;
        }
        if (!goalTask.isEnabled()) {
            ctx.println(ctx.color(YELLOW + "Goal is already paused." + RESET));
            return;
        }
        scheduler.toggle(sessionId, workspace, harnessSessions, GOAL_TASK_ID);
        ctx.println(ctx.color(YELLOW + "Goal paused. Use /goal resume to continue." + RESET));
    }

    /**
     * 恢复目标
     */
    private void doResume(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        LoopTask goalTask = scheduler.getTaskById(sessionId, GOAL_TASK_ID);
        if (goalTask == null || goalTask.isCancelled()) {
            ctx.println(ctx.color(YELLOW + "No goal to resume. Use /goal <description> to set one." + RESET));
            return;
        }
        if (goalTask.isEnabled()) {
            ctx.println(ctx.color(GREEN + "Goal is already active." + RESET));
            return;
        }
        scheduler.toggle(sessionId, workspace, harnessSessions, GOAL_TASK_ID);
        ctx.println(ctx.color(GREEN + "Goal resumed." + RESET));
    }

    /**
     * 清除目标
     */
    private void doClear(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        LoopTask goalTask = scheduler.getTaskById(sessionId, GOAL_TASK_ID);
        if (goalTask == null || goalTask.isCancelled()) {
            ctx.println(ctx.color(YELLOW + "No active goal to clear." + RESET));
            return;
        }
        scheduler.remove(sessionId, workspace, harnessSessions, GOAL_TASK_ID);
        ctx.println(ctx.color(GREEN + "Goal cleared." + RESET));
    }

    /**
     * 创建 id="goal" 的即时模式 LoopTask（公开，供 ManagerTalent 复用）
     *
     * <p>绕过便捷构造器（自动生成 UUID），使用全参数构造器强制 id="goal"。
     */
    public static LoopTask createGoalTask(String prompt, String goalCondition, int maxIterations, String workspace) {
        Instant now = Instant.now();
        return new LoopTask(
                GOAL_TASK_ID,                         // id = "goal"
                prompt,                                // prompt
                0,                                     // intervalMinutes = 0（即时模式）
                null,                                  // cron
                now,                                   // createdAt
                now.plus(7, java.time.temporal.ChronoUnit.DAYS),  // expireAt
                false,                                 // autoInterval
                true,                                  // enabled
                goalCondition,                         // goalCondition
                null,                                  // makerAgent
                null,                                  // checkerAgent
                false,                                 // worktreeEnabled
                null,                                  // worktreeBranch
                null,                                  // channelNotify
                workspace,                             // workspace
                maxIterations,                         // maxIterations
                false,                                 // cancelled
                null,                                  // lastResult
                null,                                  // lastExecutedAt
                0                                      // currentIteration
        );
    }

    /**
     * 目标任务的固定 ID
     */
    public static String getGoalTaskId() {
        return GOAL_TASK_ID;
    }

    /**
     * 格式化时间间隔（"3s ago", "5m ago", "2h ago"）
     */
    static String formatAgo(Instant instant) {
        long seconds = Duration.between(instant, Instant.now()).getSeconds();
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        return (seconds / 3600) + "h ago";
    }

    /**
     * 格式化已耗时（"3s", "5m", "2h 3m"）
     */
    static String formatElapsed(Instant since) {
        long seconds = Duration.between(since, Instant.now()).getSeconds();
        if (seconds < 60) return seconds + "s";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h > 0) {
            return m > 0 ? h + "h " + m + "m" : h + "h";
        }
        return m + "m";
    }
}
