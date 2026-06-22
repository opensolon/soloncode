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

import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * /goal 命令 — Goal 生命周期管理（独立命令入口）
 *
 * <pre>
 *   /goal                 → 查看当前活跃 goal
 *   /goal status          → 查看当前活跃 goal 详情
 *   /goal status &lt;taskId&gt; → 查看指定任务的 goal
 *   /goal &lt;condition&gt;     → 在最近的活跃 loop 任务上设置 goal
 *   /goal pause           → 暂停当前 goal
 *   /goal resume          → 恢复暂停的 goal
 *   /goal clear           → 清除当前 goal（任务保留）
 *   /goal --help          → 帮助
 * </pre>
 *
 * @author noear
 * @since 3.9.1
 */
public class GoalCommand implements Command {

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
        return "Goal 生命周期管理 (status, pause, resume, clear, <condition>)";
    }

    @Override
    public boolean execute(CommandContext ctx) throws Exception {
        String sessionId = ctx.getSession().getSessionId();
        String sub = ctx.argAt(0);

        if (sub == null || sub.isEmpty()) {
            handleStatus(ctx, sessionId, null);
        } else if ("status".equals(sub) || "ls".equals(sub)) {
            String taskId = ctx.argAt(1);
            handleStatus(ctx, sessionId, taskId);
        } else if ("pause".equals(sub)) {
            handlePause(ctx, sessionId);
        } else if ("resume".equals(sub)) {
            handleResume(ctx, sessionId);
        } else if ("clear".equals(sub)) {
            String taskId = ctx.argAt(1);
            handleClear(ctx, sessionId, taskId);
        } else if ("--help".equals(sub) || "-h".equals(sub)) {
            printUsage(ctx);
        } else {
            // /goal <condition> → 在最近的活跃 loop 任务上设置 goal
            // 将剩余参数拼接为条件（argAt(0) 已经是第一个参数）
            String condition = sub;
            for (int i = 1; ; i++) {
                String arg = ctx.argAt(i);
                if (arg == null) break;
                condition += " " + arg;
            }
            handleSetGoal(ctx, sessionId, condition);
        }

        return true;
    }

    /**
     * /goal 或 /goal status [taskId] — 查看 goal 状态
     */
    private void handleStatus(CommandContext ctx, String sessionId, String taskId) {
        List<LoopTask> tasks = scheduler.listActive(sessionId);
        if (tasks.isEmpty()) {
            ctx.println(ctx.color(DIM + "No active loop tasks." + RESET));
            return;
        }

        // 如果指定了 taskId，只显示该任务
        if (taskId != null && !taskId.isEmpty()) {
            LoopTask target = scheduler.getTaskById(sessionId, taskId);
            if (target == null) {
                ctx.println(ctx.color(RED + "Task not found: " + taskId + RESET));
                return;
            }
            printGoalStatus(ctx, target);
            return;
        }

        // 显示所有活跃 goal
        List<LoopTask> goalTasks = tasks.stream()
                .filter(LoopTask::isGoalMode)
                .collect(Collectors.toList());

        if (goalTasks.isEmpty()) {
            ctx.println(ctx.color(DIM + "No active goals." + RESET));
            ctx.println(ctx.color(DIM + "  Use /loop goal:\"<condition>\" <prompt> to create a goal task." + RESET));
            ctx.println(ctx.color(DIM + "  Or /goal <condition> to set goal on active task." + RESET));
            return;
        }

        ctx.println(ctx.color(BOLD + "Active Goals:" + RESET));
        for (LoopTask t : goalTasks) {
            printGoalSummary(ctx, t);
        }
    }

    /**
     * 打印单个 goal 的摘要（多任务列表时使用）
     */
    private void printGoalSummary(CommandContext ctx, LoopTask task) {
        GoalState gs = task.getGoalState();
        String statusIcon = statusIcon(gs.getStatus());
        String statusColor = statusColor(gs.getStatus());

        StringBuilder line = new StringBuilder();
        line.append("  ").append(CYAN).append(task.getId()).append(RESET);
        line.append(" ").append(statusIcon);
        line.append(" ").append(statusColor).append(gs.getStatus().name().toLowerCase()).append(RESET);
        line.append(" ").append(DIM).append("iter:").append(gs.getCurrentIteration())
                .append("/").append(gs.getMaxIterations()).append(RESET);

        // 运行时长
        if (gs.getStartedAt() != null) {
            line.append(" ").append(DIM).append("(").append(formatElapsed(gs.getStartedAt())).append(")").append(RESET);
        }

        line.append("\n");
        line.append("    ").append(DIM).append("Goal: ").append(RESET).append(gs.getCondition());

        // 最近评估原因
        if (gs.getLastEvaluationReason() != null && !gs.getStatus().isTerminal()) {
            line.append("\n    ").append(DIM)
                    .append("eval: ").append(abbreviate(gs.getLastEvaluationReason(), 100))
                    .append(RESET);
        }

        ctx.println(ctx.color(line.toString()));
        ctx.println("");
    }

    /**
     * 打印单个 goal 的详细信息（/goal status <taskId>）
     */
    private void printGoalStatus(CommandContext ctx, LoopTask task) {
        GoalState gs = task.getGoalState();
        if (gs == null) {
            ctx.println(ctx.color(YELLOW + "Task '" + task.getId() + "' has no goal." + RESET));
            return;
        }

        String statusIcon = statusIcon(gs.getStatus());
        String statusColor = statusColor(gs.getStatus());

        ctx.println(ctx.color(BOLD + "Goal Status — " + task.getId() + RESET));

        // ★ BLOCKED 状态额外提示
        if (gs.getStatus() == GoalStatus.BLOCKED) {
            ctx.println(ctx.color("  " + YELLOW + "ⓘ 模型声明阻塞，等待用户干预后通过 /goal resume 恢复" + RESET));
        }
        ctx.println("");
        ctx.println(ctx.color("  " + statusIcon + " " + statusColor + gs.getStatus().name() + RESET));
        ctx.println(ctx.color("  " + BOLD + "Condition:" + RESET + " " + gs.getCondition()));
        ctx.println(ctx.color("  " + BOLD + "Progress:" + RESET + " " + gs.getCurrentIteration()
                + "/" + gs.getMaxIterations() + " iterations"));

        if (gs.getStartedAt() != null) {
            ctx.println(ctx.color("  " + BOLD + "Elapsed:" + RESET + " " + formatElapsed(gs.getStartedAt())));
        }

        if (gs.getLastEvaluationReason() != null) {
            ctx.println("");
            ctx.println(ctx.color("  " + BOLD + "Recent Evaluation:" + RESET));
            ctx.println(ctx.color("  " + DIM + gs.getLastEvaluationReason() + RESET));
        }

        // 评估历史
        List<GoalEvaluation> history = gs.getHistory();
        if (!history.isEmpty()) {
            ctx.println("");
            ctx.println(ctx.color("  " + BOLD + "Evaluation History (" + history.size() + "):" + RESET));
            // 显示最近 5 条
            int start = Math.max(0, history.size() - 5);
            for (int i = start; i < history.size(); i++) {
                GoalEvaluation eval = history.get(i);
                String icon = eval.isAchieved() ? GREEN + "✓" : RED + "✗";
                ctx.println(ctx.color("    " + icon + RESET + " iter " + eval.getIteration()
                        + " " + DIM + abbreviate(eval.getReason(), 80) + RESET));
            }
        }

        // 操作提示
        ctx.println("");
        ctx.println(ctx.color(DIM + "Commands:" + RESET));
        if (gs.getStatus().isPausable()) {
            ctx.println(ctx.color(DIM + "  /goal pause            — Pause this goal" + RESET));
        }
        if (gs.getStatus().isResumable()) {
            ctx.println(ctx.color(DIM + "  /goal resume           — Resume this goal" + RESET));
        }
        if (!gs.getStatus().isTerminal()) {
            ctx.println(ctx.color(DIM + "  /goal clear " + task.getId() + "   — Clear this goal" + RESET));
        }
    }

    /**
     * /goal <condition> — 设置 goal
     */
    private void handleSetGoal(CommandContext ctx, String sessionId, String condition) {
        // 清理引号
        if ((condition.startsWith("\"") && condition.endsWith("\"")) ||
                (condition.startsWith("'") && condition.endsWith("'"))) {
            condition = condition.substring(1, condition.length() - 1);
        }

        if (condition == null || condition.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /goal <condition>" + RESET));
            return;
        }

        List<LoopTask> tasks = scheduler.listActive(sessionId);
        // 在最近的 running 或 idle 任务上设置 goal
        Optional<LoopTask> target = tasks.stream()
                .filter(t -> !t.isGoalMode())
                .findFirst();

        if (!target.isPresent()) {
            // 看是否有活跃的 goal 任务可以替换
            Optional<LoopTask> existingGoal = tasks.stream()
                    .filter(LoopTask::isGoalMode)
                    .filter(t -> !t.getGoalState().getStatus().isTerminal())
                    .findFirst();

            if (existingGoal.isPresent()) {
                // 更新已有 goal 的条件
                LoopTask task = existingGoal.get();
                GoalState gs = task.getGoalState();
                scheduler.clearGoal(sessionId, task.getId());
                ctx.println(ctx.color(YELLOW + "Replaced existing goal on task " + task.getId() + RESET));
            } else {
                ctx.println(ctx.color(RED + "No active loop task found. Use /loop goal:\"...\" <prompt> to create one." + RESET));
                ctx.println(ctx.color(DIM + "  Or /loop 5m <prompt> to create a task first, then /goal <condition>" + RESET));
                return;
            }
        }

        LoopTask task = target.orElseGet(() -> {
            // 如果 target 为空（上面已经 return 了），这里不会执行
            return null;
        });

        if (task == null) return;

        // 通过 copyWithUpdate 设置 goalCondition
        LoopTask updated = task.copyWithUpdate(
                task.getPrompt(),
                task.getIntervalMinutes(),
                task.getCron(),
                condition,
                task.isWorktreeEnabled(),
                task.getMaxIterations(),
                task.isRunNow(),
                task.getMaxTokens(),
                task.getMaxDurationMs()
        );
        scheduler.update(sessionId, task.getId(), updated);

        ctx.println(ctx.color(GREEN + "Goal set on task " + task.getId() + ":" + RESET));
        ctx.println(ctx.color("  " + DIM + condition + RESET));
    }

    /**
     * /goal pause — 暂停当前 goal
     */
    private void handlePause(CommandContext ctx, String sessionId) {
        LoopTask active = findActiveGoal(sessionId);
        if (active == null) {
            ctx.println(ctx.color(YELLOW + "No active goal to pause." + RESET));
            return;
        }

        GoalState gs = active.getGoalState();
        if (!gs.getStatus().isPausable()) {
            ctx.println(ctx.color(YELLOW + "Goal is not in a pausable state: " + gs.getStatus() + RESET));
            return;
        }

        scheduler.pauseGoal(sessionId, active.getId());
        ctx.println(ctx.color(GREEN + "Goal paused." + RESET));
        ctx.println(ctx.color("  " + DIM + "Use /goal resume to resume." + RESET));
    }

    /**
     * /goal resume — 恢复暂停的 goal
     */
    private void handleResume(CommandContext ctx, String sessionId) {
        List<LoopTask> tasks = scheduler.listActive(sessionId);
        LoopTask paused = null;
        for (LoopTask t : tasks) {
            if (t.isGoalMode() && t.getGoalState().getStatus() == GoalStatus.PAUSED) {
                paused = t;
                break;
            }
        }

        if (paused == null) {
            ctx.println(ctx.color(YELLOW + "No paused goal found." + RESET));
            return;
        }

        scheduler.resumeGoal(sessionId, paused.getId());
        ctx.println(ctx.color(GREEN + "Goal resumed." + RESET));
    }

    /**
     * /goal clear [taskId] — 清除 goal
     */
    private void handleClear(CommandContext ctx, String sessionId, String taskId) {
        LoopTask target = null;

        if (taskId != null && !taskId.isEmpty()) {
            target = scheduler.getTaskById(sessionId, taskId);
            if (target == null) {
                ctx.println(ctx.color(RED + "Task not found: " + taskId + RESET));
                return;
            }
        } else {
            target = findActiveGoal(sessionId);
            if (target == null) {
                ctx.println(ctx.color(YELLOW + "No active goal to clear." + RESET));
                return;
            }
        }

        if (!target.isGoalMode()) {
            ctx.println(ctx.color(YELLOW + "Task '" + target.getId() + "' has no goal." + RESET));
            return;
        }

        scheduler.clearGoal(sessionId, target.getId());
        ctx.println(ctx.color(GREEN + "Goal cleared for task " + target.getId() + RESET));
        ctx.println(ctx.color("  " + DIM + "Task is preserved. Use /loop stop " + target.getId() + " to remove the task." + RESET));
    }

    // ===== 工具方法 =====

    /**
     * 查找当前活跃的 goal 任务（PURSUING 或 PAUSED）
     */
    private LoopTask findActiveGoal(String sessionId) {
        List<LoopTask> tasks = scheduler.listActive(sessionId);
        for (LoopTask t : tasks) {
            if (t.isGoalMode()) {
                GoalStatus status = t.getGoalState().getStatus();
                if (status == GoalStatus.PURSUING || status == GoalStatus.PAUSED) {
                    return t;
                }
            }
        }
        return null;
    }

    private String statusIcon(GoalStatus status) {
        switch (status) {
            case PURSUING: return "●";
            case PAUSED: return "◌";
            case ACHIEVED: return "✓";
            case UNMET: return "✗";
            case BUDGET_LIMITED: return "⚠";
            case BLOCKED: return "⊘";
            case TERMINATED: return "○";
            case CREATING: return "○";
            default: return "?";
        }
    }

    private String statusColor(GoalStatus status) {
        switch (status) {
            case PURSUING: return CYAN;
            case PAUSED: return YELLOW;
            case ACHIEVED: return GREEN;
            case UNMET: return RED;
            case BUDGET_LIMITED: return YELLOW;
            case BLOCKED: return YELLOW;
            case TERMINATED: return DIM;
            case CREATING: return DIM;
            default: return RESET;
        }
    }

    private String formatElapsed(Instant start) {
        if (start == null) return "-";
        long seconds = Duration.between(start, Instant.now()).getSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private void printUsage(CommandContext ctx) {
        ctx.println(ctx.color(BOLD + "/goal — Goal 生命周期管理" + RESET));
        ctx.println("");
        ctx.println(ctx.color(DIM + "Usage:" + RESET));
        ctx.println(ctx.color(DIM + "  /goal                      — 查看所有活跃 goal" + RESET));
        ctx.println(ctx.color(DIM + "  /goal status <taskId>      — 查看指定任务 goal 详情" + RESET));
        ctx.println(ctx.color(DIM + "  /goal <condition>          — 在最近活跃的任务上设置 goal" + RESET));
        ctx.println(ctx.color(DIM + "  /goal pause                — 暂停当前 goal" + RESET));
        ctx.println(ctx.color(DIM + "  /goal resume               — 恢复暂停的 goal" + RESET));
        ctx.println(ctx.color(DIM + "  /goal clear [taskId]       — 清除 goal（任务保留）" + RESET));
    }
}
