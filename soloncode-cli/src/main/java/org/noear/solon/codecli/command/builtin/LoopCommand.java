/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.entity.LoopGroupDo;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * /loop 命令 - 循环任务与 Goal 统一管理（Loop Engineering 运行时）
 *
 * <pre>
 * 基础用法:
 *   /loop 5m check if deployment finished    → fixed interval (5m)
 *   /loop check ci status                   → auto interval (5m default)
 *   /loop cron:"0 *&#47;5 * * * ?" check status  → cron expression
 *   /loop ls                                → list active tasks
 *   /loop stop <id>                         → stop a task
 *   /loop stop-all                          → stop all tasks
 *   /loop pause <id>                        → pause a goal task
 *   /loop resume <id>                       → resume a paused goal task
 *
 * Loop Engineering 扩展:
 *   /loop goal fix auth                     → goal 模式（简化）
 *   /loop 30m --notify:feishu check CI      → 通道通知
 * </pre>
 *
 * @author noear
 * @since 2026.4.28
 */
public class LoopCommand implements Command {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    private final LoopScheduler scheduler;

    public LoopCommand(LoopScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public String name() {
        return "loop";
    }

    @Override
    public String description() {
        return "循环任务 (ls, stop, stop-all, pause, resume, goal, <interval> <prompt>, cron:<expr> <prompt>)";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/loop ls",
                "/loop stop <id>",
                "/loop stop-all",
                "/loop pause <id>",
                "/loop resume <id>",
                "/loop goal <objective>",
                "/loop 5m <prompt>",
                "/loop cron:\"<expr>\" <prompt> ",
        };
    }

    @Override
    public void execute(CommandContext ctx) throws Exception {
        String sessionId = ctx.getSession().getSessionId();
        HarnessEngine engine = ctx.getEngine();
        String workspace = engine.getWorkspace();
        String harnessSessions = engine.getHarnessSessions();

        String sub = ctx.argAt(0);

        if (sub == null || sub.isEmpty() || "ls".equals(sub)) {
            doList(ctx, sessionId);
        } else if ("stop".equals(sub)) {
            String taskId = ctx.argAt(1);
            if (taskId == null || taskId.isEmpty()) {
                ctx.println(ctx.color(RED + "Usage: /loop stop <id>" + RESET));
            } else {
                LoopTask task = scheduler.getTaskById(sessionId, taskId);
                if (task != null) {
                    scheduler.remove(sessionId, task);
                }

                ctx.println(ctx.color(GREEN + "Loop task '" + taskId + "' stopped." + RESET));
            }
        } else if ("stop-all".equals(sub)) {
            scheduler.stopAll(sessionId);
            ctx.println(ctx.color(GREEN + "All loop tasks stopped." + RESET));
        } else if ("pause".equals(sub)) {
            doPause(ctx, sessionId, ctx.argAt(1));
        } else if ("resume".equals(sub)) {
            doResume(ctx, sessionId, ctx.argAt(1));
        } else if ("goal".equals(sub)) {
            // /loop goal <objective> — 简化 Goal 模式（一个参数，立即执行）
            doScheduleGoal(ctx, sessionId, workspace, harnessSessions);
        } else {
            // Schedule a new task
            doSchedule(ctx, sessionId, workspace, harnessSessions);
        }
    }

    /**
     * 解析并创建新的 loop 任务
     */
    private void doSchedule(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        int intervalMinutes = 5; // default
        String cronExpr = null;
        boolean runNow = false;
        Integer maxIterations = null;
        LoopTask.TaskType taskType = LoopTask.TaskType.HEARTBEAT;

        int promptStartIndex = 0;
        String first = ctx.argAt(0);

        // 1. 检查是否为 cron 模式
        if (first != null && first.startsWith("cron:")) {
            cronExpr = first.substring(5).trim();
            // 去除可能被引号包裹的 cron 表达式
            if ((cronExpr.startsWith("\"") && cronExpr.endsWith("\"")) ||
                    (cronExpr.startsWith("'") && cronExpr.endsWith("'"))) {
                cronExpr = cronExpr.substring(1, cronExpr.length() - 1);
            }
            if (cronExpr.isEmpty()) {
                ctx.println(ctx.color(RED + "Usage: /loop cron:<expr> <prompt>" + RESET));
                ctx.println(ctx.color(DIM + "  /loop cron:\"0 */5 * * * ?\" check status" + RESET));
                return;
            }
            promptStartIndex = 1;
        } else {
            // 3. 检查时间间隔
            Integer parsed = parseInterval(first);
            if (parsed != null) {
                intervalMinutes = parsed;
                promptStartIndex = 1;
            }
        }

        // Build prompt from remaining args, 同时解析 --flag
        StringBuilder promptBuilder = new StringBuilder();

        for (int i = promptStartIndex; ; i++) {
            String arg = ctx.argAt(i);
            if (arg == null) break;

            // 解析 --flag:value 或 --flag
            if (arg.startsWith("--")) {
                String flag = arg.substring(2);
                int colonIdx = flag.indexOf(':');
                if (colonIdx > 0) {
                    String key = flag.substring(0, colonIdx);
                    String val = flag.substring(colonIdx + 1);
                    switch (key) {
                        case "max-iter":
                            try {
                                maxIterations = Integer.parseInt(val);
                            } catch (NumberFormatException e) {
                                ctx.println(ctx.color(RED + "Invalid --max-iter value: " + val + RESET));
                                return;
                            }
                            break;
                        default:
                            ctx.println(ctx.color(YELLOW + "Unknown flag: --" + key + RESET));
                            break;
                    }
                } else {
                    if ("now".equals(flag)) {
                        runNow = true;
                    } else {
                        ctx.println(ctx.color(YELLOW + "Unknown flag: --" + flag + RESET));
                    }
                }
            } else {
                // 普通参数拼入 prompt
                if (promptBuilder.length() > 0) promptBuilder.append(" ");
                promptBuilder.append(arg);
            }
        }

        String prompt = promptBuilder.toString().trim();

        if (prompt.isEmpty()) {
            printUsage(ctx);
            return;
        }

        // Create task
        LoopTask task = new LoopTask(
                prompt, intervalMinutes, cronExpr,
                taskType, maxIterations, runNow
        );

        // 初始化状态目录（用 task 生成的 ID）
        LoopStateManager.init(workspace, task.getId(), prompt);

        try {
            scheduler.schedule(sessionId, task);
        } catch (IllegalStateException e) {
            ctx.println(ctx.color(RED + "Failed: " + e.getMessage() + RESET));
            LoopStateManager.cleanup(workspace, task.getId());
            return;
        }

        // 打印注册信息
        ctx.println(ctx.color(GREEN + "Loop task registered:" + RESET));
        ctx.println(ctx.color("  " + BOLD + "ID:" + RESET + " " + task.getId()));
        if (task.isCronMode()) {
            ctx.println(ctx.color("  " + BOLD + "Cron:" + RESET + " " + task.getCron()));
        } else {
            ctx.println(ctx.color("  " + BOLD + "Interval:" + RESET + " " + formatInterval(intervalMinutes)));
        }
        ctx.println(ctx.color("  " + BOLD + "Prompt:" + RESET + " " + prompt));

        // 打印 Loop Engineering 扩展信息
        if (runNow) {
            ctx.println(ctx.color("  " + MAGENTA + "Run Now:" + RESET + " first execution immediately"));
        }
        if (maxIterations != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Max Iterations:" + RESET + " " + maxIterations));
        }

        ctx.println(ctx.color("  " + DIM + "State:" + RESET + " " + Paths.get(AgentFlags.getHarnessLoops(), task.getId())));
        ctx.println(ctx.color(DIM + "  Expires: " + task.getExpireAt() + RESET));
    }

    /**
     * /loop goal &lt;objective&gt; — 简化 Goal 模式
     *
     * <p>与 Codex /goal &lt;objective&gt; 对齐：一个参数，立即执行，AI 自主推进。
     * prompt 即目标任务描述，type=GOAL，runNow=true，interval=0（调度器自动转 5 秒安全网）。</p>
     */
    private void doScheduleGoal(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        Integer maxIterations = null;
        Long maxTokens = null;
        Long maxDurationMs = null;
        StringBuilder promptBuilder = new StringBuilder();

        // 从 index=1 开始解析（index=0 是 "goal"）
        for (int i = 1; ; i++) {
            String arg = ctx.argAt(i);
            if (arg == null) break;

            // 解析 --flag:value 或 --flag
            if (arg.startsWith("--")) {
                String flag = arg.substring(2);
                int colonIdx = flag.indexOf(':');
                if (colonIdx > 0) {
                    String key = flag.substring(0, colonIdx);
                    String val = flag.substring(colonIdx + 1);
                    switch (key) {
                        case "max-iter":
                            try {
                                maxIterations = Integer.parseInt(val);
                            } catch (NumberFormatException e) {
                                ctx.println(ctx.color(RED + "Invalid --max-iter value: " + val + RESET));
                                return;
                            }
                            break;
                        case "max-tokens":
                            try {
                                maxTokens = Long.parseLong(val);
                            } catch (NumberFormatException e) {
                                ctx.println(ctx.color(RED + "Invalid --max-tokens value: " + val + RESET));
                                return;
                            }
                            break;
                        case "max-duration":
                            try {
                                maxDurationMs = Long.parseLong(val) * 60000;
                            } catch (NumberFormatException e) {
                                ctx.println(ctx.color(RED + "Invalid --max-duration value: " + val + RESET));
                                return;
                            }
                            break;
                        default:
                            ctx.println(ctx.color(YELLOW + "Unknown flag: --" + key + RESET));
                            break;
                    }
                } else {
                    ctx.println(ctx.color(YELLOW + "Unknown flag: --" + flag + RESET));
                }
            } else {
                // 普通参数拼入 prompt
                if (promptBuilder.length() > 0) promptBuilder.append(" ");
                promptBuilder.append(arg);
            }
        }

        String prompt = promptBuilder.toString().trim();
        if (prompt.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /loop goal <objective>" + RESET));
            ctx.println(ctx.color(DIM + "  /loop goal fix auth module" + RESET));
            ctx.println(ctx.color(DIM + "  /loop goal --max-iter:10 fix lint" + RESET));
            return;
        }

        // ★ 单会话单活跃 Goal 校验
        List<LoopTask> existing = scheduler.listActive(sessionId);
        for (LoopTask t : existing) {
            if (t.isGoalMode() && t.getGoalState().getStatus().isActive()) {
                ctx.println(ctx.color(YELLOW + "WARN: A goal is already active in this session (" + t.getId() + ")." + RESET));
                ctx.println(ctx.color(DIM + "  Active: " + t.getGoalState().getCondition() + RESET));
                ctx.println(ctx.color(DIM + "  Use /loop stop " + t.getId() + " first to replace the goal." + RESET));
                return;
            }
            if (t.isGoalMode()
                    && t.getGoalState().getStatus() == GoalState.Status.PAUSED
                    && t.getGoalState().isAbandoned()) {
                ctx.println(ctx.color(YELLOW + "Abandoned goal '" + t.getId() + "' (" + t.getGoalState().getCondition() + ") auto-cleaned." + RESET));
                scheduler.remove(sessionId, t);
            }
        }

        // 创建任务：★ 显式使用 LoopTask.TaskType.GOAL
        LoopTask task = new LoopTask(
                prompt,       // prompt
                0,            // intervalMinutes = 0（调度器自动转 5 秒安全网）
                null,         // cron
                LoopTask.TaskType.GOAL,  // type = GOAL
                maxIterations,
                true          // runNow = true（立即执行首次）
        );

        // 设置预算（仅在配置了 >0 的默认值时才应用；未配则不限制）
        LoopGroupDo loopCfg = scheduler.getLoopConfig();
        if (maxTokens != null) {
            task.setMaxTokens(maxTokens);
        } else if (loopCfg.getDefaultMaxTokensOrDefault() > 0) {
            task.setMaxTokens(loopCfg.getDefaultMaxTokensOrDefault());
        }
        if (maxDurationMs != null) {
            task.setMaxDurationMs(maxDurationMs);
        } else if (loopCfg.getDefaultMaxDurationMsOrDefault() > 0) {
            task.setMaxDurationMs(loopCfg.getDefaultMaxDurationMsOrDefault());
        }

        // 初始化状态目录
        LoopStateManager.init(workspace, task.getId(), prompt);

        try {
            scheduler.schedule(sessionId, task);
        } catch (IllegalStateException e) {
            ctx.println(ctx.color(RED + "Failed: " + e.getMessage() + RESET));
            LoopStateManager.cleanup(workspace, task.getId());
            return;
        }

        // 打印注册信息（简洁版）
        ctx.println(ctx.color(GREEN + "Goal task registered:" + RESET));
        ctx.println(ctx.color("  " + BOLD + "ID:" + RESET + " " + task.getId()));
        ctx.println(ctx.color("  " + BOLD + "Objective:" + RESET + " " + prompt));
        if (maxIterations != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Max Iterations:" + RESET + " " + maxIterations));
        }
        ctx.println(ctx.color("  " + DIM + "State:" + RESET + " " + Paths.get(AgentFlags.getHarnessLoops(), task.getId())));
        ctx.println(ctx.color(DIM + "  Expires: " + task.getExpireAt() + RESET));
    }

    private void doList(CommandContext ctx, String sessionId) {
        List<LoopTask> tasks = scheduler.listActive(sessionId);
        if (tasks.isEmpty()) {
            ctx.println(ctx.color(DIM + "No active loop tasks." + RESET));
            ctx.println(ctx.color(DIM + "Usage: /loop [interval] <prompt>" + RESET));
            return;
        }
        ctx.println(ctx.color(BOLD + "Active Loop Tasks:" + RESET));
        for (LoopTask t : tasks) {
            String status = t.isRunning() ? YELLOW + "running" + RESET : GREEN + "idle" + RESET;
            String scheduleInfo = t.isCronMode()
                    ? CYAN + "cron:" + t.getCron() + RESET
                    : formatInterval(t.getIntervalMinutes());
            String lastInfo = t.getLastExecutedAt() != null
                    ? DIM + " (last: " + formatAgo(t.getLastExecutedAt()) + ": " + (t.getLastResult() != null ? t.getLastResult() : "-") + ")" + RESET
                    : "";

            // 基础行
            StringBuilder line = new StringBuilder();
            line.append("  ").append(CYAN).append(t.getId()).append(RESET);

            // ★ P2: 任务类型标签
            if (t.getType() == LoopTask.TaskType.GOAL) {
                line.append(" ").append(MAGENTA).append("[goal]").append(RESET);
            } else {
                line.append(" ").append(CYAN).append("[heartbeat]").append(RESET);
            }

            line.append(" ").append(scheduleInfo);
            line.append(" ").append(status);

            // ★ P0: Goal 增强显示
            if (t.isGoalMode()) {
                GoalState gs = t.getGoalState();
                String goalIcon = goalStatusIcon(gs.getStatus());
                String goalColor = goalStatusColor(gs.getStatus());

                line.append(" ").append(goalColor).append(goalIcon).append("goal").append(RESET);
                line.append(" ").append(DIM)
                        .append("iter:").append(t.getCurrentIteration())
                        .append(RESET);

                // 状态标签
                if (gs.getStatus() == GoalState.Status.PAUSED) {
                    line.append(" ").append(YELLOW).append("[paused]").append(RESET);
                } else if (gs.getStatus() == GoalState.Status.ACHIEVED) {
                    line.append(" ").append(GREEN).append("[achieved]").append(RESET);
                } else if (gs.getStatus() == GoalState.Status.BUDGET_LIMITED) {
                    line.append(" ").append(YELLOW).append("[budget]").append(RESET);
                }

                // 运行时长
                if (gs.getStartEpochMs() > 0 && gs.getStatus().isActive()) {
                    line.append(" ").append(DIM)
                            .append("(").append(formatElapsed(gs.getStartEpochMs())).append(")")
                            .append(RESET);
                }
            } else {
                if (t.isRunNow()) {
                    line.append(" ").append(MAGENTA).append("[now]").append(RESET);
                }
                line.append(" ").append(DIM).append("iter:").append(t.getCurrentIteration()).append("/").append(t.getMaxIterations()).append(RESET);
            }

            line.append(" ").append(DIM).append(t.getPrompt()).append(RESET);
            line.append(lastInfo);

            // Goal 任务展示状态信息
            if (t.isGoalMode()) {
                GoalState gs = t.getGoalState();
                if (gs.getStatus().isActive()) {
                    line.append("\n    ").append(DIM)
                            .append("  status: ").append(gs.getStatus().name().toLowerCase())
                            .append(" | iter: ").append(t.getCurrentIteration())
                            .append(RESET);
                }
            }

            ctx.println(ctx.color(line.toString()));
        }
        ctx.println(ctx.color(DIM + "\nUsage: /loop stop <id> | /loop stop-all | /loop pause <id> | /loop resume <id>" + RESET));
    }

    // ===== Goal pause / resume =====

    private void doPause(CommandContext ctx, String sessionId, String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /loop pause <id>" + RESET));
            return;
        }

        LoopTask task = scheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            ctx.println(ctx.color(RED + "Task not found: " + taskId + RESET));
            return;
        }

        if (!task.isGoalMode()) {
            ctx.println(ctx.color(YELLOW + "Task '" + taskId + "' is not a goal task." + RESET));
            return;
        }

        GoalState gs = task.getGoalState();
        if (gs.getStatus() != GoalState.Status.PURSUING) {
            ctx.println(ctx.color(YELLOW + "Goal is not in a pausable state: " + gs.getStatus() + RESET));
            return;
        }

        scheduler.pauseGoal(sessionId, task.getId());
        ctx.println(ctx.color(GREEN + "Goal paused: " + taskId + RESET));
        ctx.println(ctx.color(DIM + "  Use /loop resume " + taskId + " to resume." + RESET));
    }

    private void doResume(CommandContext ctx, String sessionId, String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /loop resume <id>" + RESET));
            return;
        }

        LoopTask task = scheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            ctx.println(ctx.color(RED + "Task not found: " + taskId + RESET));
            return;
        }

        if (!task.isGoalMode()) {
            ctx.println(ctx.color(YELLOW + "Task '" + taskId + "' is not a goal task." + RESET));
            return;
        }

        GoalState gs = task.getGoalState();
        if (!gs.getStatus().isResumable()) {
            ctx.println(ctx.color(YELLOW + "Goal is not paused/blocked: " + gs.getStatus() + RESET));
            return;
        }

        scheduler.resumeGoal(sessionId, task.getId());
        ctx.println(ctx.color(GREEN + "Goal resumed: " + taskId + RESET));
    }

    // ★ P0: Goal 辅助方法

    private String goalStatusIcon(GoalState.Status status) {
        switch (status) {
            case PURSUING: return "●";
            case PAUSED: return "◌";
            case ACHIEVED: return "✓";
            case BUDGET_LIMITED: return "⚠";
            default: return "?";
        }
    }

    private String goalStatusColor(GoalState.Status status) {
        switch (status) {
            case PURSUING: return MAGENTA;
            case PAUSED: return YELLOW;
            case ACHIEVED: return GREEN;
            case BUDGET_LIMITED: return YELLOW;
            default: return RESET;
        }
    }

    private String formatElapsed(long startEpochMs) {
        if (startEpochMs <= 0) return "-";
        long seconds = (System.currentTimeMillis() - startEpochMs) / 1000;
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 解析时间间隔字符串，支持: s/sec/seconds, m/min/minutes, h/hour/hours
     *
     * @return 间隔分钟数（秒数向上取整为分钟）；若格式不匹配返回 null
     */
    private Integer parseInterval(String s) {
        if (s == null || s.isEmpty()) return null;

        // 提取数字部分和单位部分
        int splitIdx = 0;
        while (splitIdx < s.length() && Character.isDigit(s.charAt(splitIdx))) {
            splitIdx++;
        }
        if (splitIdx == 0) return null; // 不以数字开头

        String numPart = s.substring(0, splitIdx);
        String unitPart = s.substring(splitIdx).toLowerCase();

        int value;
        try {
            value = Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return null;
        }

        if (value <= 0) return null;

        int minutes;
        switch (unitPart) {
            case "s":
            case "sec":
            case "seconds":
                // 秒 -> 分钟（向上取整，最少 1 分钟）
                minutes = Math.max(1, (value + 59) / 60);
                break;
            case "m":
            case "min":
            case "minutes":
                minutes = value;
                break;
            case "h":
            case "hour":
            case "hours":
                minutes = value * 60;
                break;
            default:
                return null; // 不识别的单位
        }

        // 限制在 1~1440 分钟（即 1 分钟 ~ 24 小时）
        return Math.max(1, Math.min(1440, minutes));
    }

    private void printUsage(CommandContext ctx) {
        ctx.println(ctx.color(RED + "Usage: /loop [options] [interval|cron:<expr>] <prompt>" + RESET));
        ctx.println(ctx.color(DIM + "Basic:" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 5m check deployment" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 30s check CI status" + RESET));
        ctx.println(ctx.color(DIM + "  /loop check CI status   (auto 5m)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop cron:\"0 */5 * * * ?\" check status" + RESET));
        ctx.println(ctx.color(DIM + "" + RESET));
        ctx.println(ctx.color(DIM + "Goal:" + RESET));
        ctx.println(ctx.color(DIM + "  /loop goal fix auth module                (goal mode, runs immediately)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop goal write a readme.md              (goal mode, runs immediately)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop goal --max-iter:10 fix lint         (goal with max iterations)" + RESET));
        ctx.println(ctx.color(DIM + "" + RESET));
        ctx.println(ctx.color(DIM + "Goal lifecycle:" + RESET));
        ctx.println(ctx.color(DIM + "  /loop pause <id>                          (pause a goal task)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop resume <id>                         (resume a paused goal)" + RESET));
    }

    private String formatInterval(int minutes) {
        if (minutes >= 60 && minutes % 60 == 0) {
            return "every " + (minutes / 60) + "h";
        }
        return "every " + minutes + "m";
    }

    private String formatAgo(Instant instant) {
        long seconds = Duration.between(instant, Instant.now()).getSeconds();
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        return (seconds / 3600) + "h ago";
    }
}
