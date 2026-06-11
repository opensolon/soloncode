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
import org.noear.solon.codecli.config.AgentProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * /loop 命令 - 循环任务管理（Loop Engineering 运行时）
 *
 * <pre>
 * 基础用法:
 *   /loop 5m check if deployment finished    → fixed interval (5m)
 *   /loop check ci status                   → auto interval (5m default)
 *   /loop cron:"0 *&#47;5 * * * ?" check status  → cron expression
 *   /loop ls                                → list active tasks
 *   /loop stop <id>                         → stop a task
 *   /loop stop-all                          → stop all tasks
 *
 * Loop Engineering 扩展:
 *   /loop 30m $triage-ci-failures           → 引用 skill
 *   /loop 5m --maker:coder --checker:reviewer fix auth  → maker/checker
 *   /loop goal:"all tests pass" fix auth    → goal 模式
 *   /loop 5m --worktree fix bug #123        → worktree 隔离
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
        return "循环任务管理 (ls, stop, stop-all, goal, <interval> <prompt>, cron:<expr> <prompt>)";
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

        if (sub == null || sub.isEmpty() || "ls".equals(sub)) {
            doList(ctx, sessionId, workspace, harnessSessions);
        } else if ("stop".equals(sub)) {
            String taskId = ctx.argAt(1);
            if (taskId == null || taskId.isEmpty()) {
                ctx.println(ctx.color(RED + "Usage: /loop stop <id>" + RESET));
            } else {
                scheduler.remove(sessionId, workspace, harnessSessions, taskId);
                ctx.println(ctx.color(GREEN + "Loop task '" + taskId + "' stopped." + RESET));
            }
        } else if ("stop-all".equals(sub)) {
            scheduler.stopAll(sessionId, workspace, harnessSessions);
            ctx.println(ctx.color(GREEN + "All loop tasks stopped." + RESET));
        } else {
            // Schedule a new task
            doSchedule(ctx, sessionId, workspace, harnessSessions);
        }

        return true;
    }

    /**
     * 解析并创建新的 loop 任务
     */
    private void doSchedule(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        int intervalMinutes = 5; // default
        String cronExpr = null;
        String skillRef = null;
        String goalCondition = null;
        String makerAgent = null;
        String checkerAgent = null;
        boolean worktreeEnabled = false;
        String channelNotify = null;
        Integer maxIterations = null;

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
        } else if (first != null && first.startsWith("goal:")) {
            // 2. 检查是否为 goal 模式: /loop goal:"..." <prompt>
            goalCondition = first.substring(5).trim();
            if ((goalCondition.startsWith("\"") && goalCondition.endsWith("\"")) ||
                    (goalCondition.startsWith("'") && goalCondition.endsWith("'"))) {
                goalCondition = goalCondition.substring(1, goalCondition.length() - 1);
            }
            if (goalCondition.isEmpty()) {
                ctx.println(ctx.color(RED + "Usage: /loop goal:\"<condition>\" <prompt>" + RESET));
                ctx.println(ctx.color(DIM + "  /loop goal:\"all tests pass\" fix the auth module" + RESET));
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
                        case "maker":
                            makerAgent = val;
                            break;
                        case "checker":
                            checkerAgent = val;
                            break;
                        case "notify":
                            channelNotify = val;
                            break;
                        case "max-iter":
                            try {
                                maxIterations = Integer.parseInt(val);
                            } catch (NumberFormatException e) {
                                ctx.println(ctx.color(RED + "Invalid --max-iter value: " + val + RESET));
                                return;
                            }
                            break;
                        case "goal":
                            goalCondition = val;
                            break;
                        default:
                            ctx.println(ctx.color(YELLOW + "Unknown flag: --" + key + RESET));
                            break;
                    }
                } else {
                    // --worktree (boolean flag)
                    if ("worktree".equals(flag)) {
                        worktreeEnabled = true;
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

        // 检测 skill 引用（prompt 以 $ 开头）
        if (prompt.startsWith("$")) {
            skillRef = prompt;
            // skillRef 保持原样，由上层（CliShell）在执行时解析
        }

        if (prompt.isEmpty()) {
            printUsage(ctx);
            return;
        }

        // 初始化状态目录
        String stateDir = LoopStateManager.init(workspace, "pending", prompt);
        // 用生成的 ID 需要先创建 task 才知道，这里先用临时目录
        // 实际上 LoopTask 构造中会生成 ID，这里需要在 schedule 之后重命名
        // 简化方案：先生成 ID，再初始化目录
        String tempId = java.util.UUID.randomUUID().toString().substring(0, 8);
        stateDir = LoopStateManager.init(workspace, tempId, prompt);

        // Create and schedule
        LoopTask task = new LoopTask(
                prompt, intervalMinutes, cronExpr,
                skillRef, goalCondition, makerAgent, checkerAgent,
                worktreeEnabled, channelNotify, maxIterations, stateDir
        );

        // 如果生成的 ID 与 tempId 不同，重新初始化状态目录
        if (!task.getId().equals(tempId)) {
            LoopStateManager.cleanup(workspace, tempId);
            stateDir = LoopStateManager.init(workspace, task.getId(), prompt);
        }

        try {
            scheduler.schedule(sessionId, workspace, harnessSessions, task);
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
        if (skillRef != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Skill:" + RESET + " " + skillRef));
        }
        if (goalCondition != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Goal:" + RESET + " " + goalCondition));
        }
        if (makerAgent != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Maker:" + RESET + " " + makerAgent));
        }
        if (checkerAgent != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Checker:" + RESET + " " + checkerAgent));
        }
        if (worktreeEnabled) {
            ctx.println(ctx.color("  " + MAGENTA + "Worktree:" + RESET + " enabled"));
        }
        if (channelNotify != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Notify:" + RESET + " " + channelNotify));
        }
        if (maxIterations != null) {
            ctx.println(ctx.color("  " + MAGENTA + "Max Iterations:" + RESET + " " + maxIterations));
        }

        ctx.println(ctx.color("  " + DIM + "State:" + RESET + " " + stateDir));
        ctx.println(ctx.color(DIM + "  Expires: " + task.getExpireAt() + RESET));
    }

    private void doList(CommandContext ctx, String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = scheduler.listActive(sessionId, workspace, harnessSessions);
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
            line.append(" ").append(scheduleInfo);
            line.append(" ").append(status);

            // 扩展标签
            if (t.isGoalMode()) {
                line.append(" ").append(MAGENTA).append("[goal]").append(RESET);
            }
            if (t.isMakerCheckerMode()) {
                line.append(" ").append(MAGENTA).append("[m/c]").append(RESET);
            }
            if (t.getSkillRef() != null) {
                line.append(" ").append(MAGENTA).append("[skill]").append(RESET);
            }
            if (t.isWorktreeEnabled()) {
                line.append(" ").append(MAGENTA).append("[wt]").append(RESET);
            }
            if (t.getChannelNotify() != null) {
                line.append(" ").append(MAGENTA).append("[").append(t.getChannelNotify()).append("]").append(RESET);
            }
            if (t.isGoalMode()) {
                line.append(" ").append(DIM).append("iter:").append(t.getCurrentIteration()).append("/").append(t.getMaxIterations()).append(RESET);
            }

            line.append(" ").append(DIM).append(t.getPrompt()).append(RESET);
            line.append(lastInfo);

            ctx.println(ctx.color(line.toString()));
        }
        ctx.println(ctx.color(DIM + "\nUsage: /loop stop <id> | /loop stop-all" + RESET));
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
        ctx.println(ctx.color(DIM + "Loop Engineering:" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 30m $triage-ci-failures              (skill ref)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 5m --maker:coder --checker:reviewer fix auth" + RESET));
        ctx.println(ctx.color(DIM + "  /loop goal:\"all tests pass\" fix auth module" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 5m --worktree fix bug #123           (git worktree)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 30m --notify:feishu check CI         (channel notify)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop goal:\"lint clean\" --max-iter:10 fix lint" + RESET));
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
