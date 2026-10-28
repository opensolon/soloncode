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
import org.noear.solon.codecli.config.entity.LoopGroupDo;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.codecli.loop.LoopTask;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * /loop 命令 - 工作区级循环任务管理（Loop Engineering 运行时）；
 * 会话级目标已迁出至 /goal
 *
 * <pre>
 * 基础用法:
 *   /loop 5m check if deployment finished    → fixed interval (5m)
 *   /loop check ci status                   → auto interval (5m default)
 *   /loop cron:"0 *&#47;5 * * * ? *" check status  → cron expression (7 fields)
 *   /loop ls                                → list active tasks
 *   /loop stop <id>                         → stop a task
 *   /loop stop-all                          → stop all tasks
 *   /loop pause <id>                        → disable a scheduled task
 *   /loop resume <id>                       → enable a scheduled task
 *
 * Loop Engineering 扩展:
 *   /goal fix auth                          → 会话目标（已从 /loop 迁出）
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
        return "定时任务 (ls, stop, stop-all, pause, resume, <interval> <prompt>, cron:<expr> <prompt>)";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/loop ls",
                "/loop stop <id>",
                "/loop stop-all",
                "/loop pause <id>",
                "/loop resume <id>",
                "/loop 5m <prompt>",
                "/loop cron:\"<expr>\" <prompt> ",
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) throws Exception {
        String sessionId = ctx.getSession().getSessionId();
        Path sessionsRoot = WorkspaceDataUtil.sessionsPath(engine.getWorkspace());

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
                    // 同时中断正在执行的 Agent
                    Disposable disposable = (Disposable) ctx.getSession().attrs().remove("disposable");
                    if (disposable != null && !disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }

                ctx.println(ctx.color(GREEN + "Loop task '" + taskId + "' stopped." + RESET));
            }
        } else if ("stop-all".equals(sub)) {
            scheduler.stopAll(sessionId);
            // 同时中断正在执行的 Agent，防止当前轮次完成后 scheduleContinuation 继续调度
            Disposable disposable = (Disposable) ctx.getSession().attrs().remove("disposable");
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
            ctx.println(ctx.color(GREEN + "All loop tasks stopped." + RESET));
        } else if ("pause".equals(sub) || "resume".equals(sub)) {
            // pause/resume 仅对 goal 有意义，goal 已独立
            ctx.println(ctx.color(YELLOW + "/loop " + sub + " has been moved to /goal " + sub + RESET));
            ctx.println(ctx.color(DIM + "  Usage: /goal " + sub + RESET));
        } else if ("goal".equals(sub)) {
            // Goal 已独立为会话级能力，不再经由 loop 体系
            ctx.println(ctx.color(YELLOW + "/loop goal has been moved to /goal" + RESET));
            ctx.println(ctx.color(DIM + "  Usage: /goal <objective>" + RESET));
        } else {
            // Schedule a new task
            doSchedule(ctx, sessionId, sessionsRoot);
        }
    }

    /**
     * 解析并创建新的 loop 任务
     */
    private void doSchedule(CommandContext ctx, String sessionId, Path sessionsRoot) {
        int intervalMinutes = 5; // default
        String cronExpr = null;
        boolean runNow = false;

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
                ctx.println(ctx.color(DIM + "  /loop cron:\"0 */5 * * * ? *\" check status" + RESET));
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
                        case "max-tokens":
                        case "max-duration":
                            ctx.println(ctx.color(YELLOW + "Warning: --" + key + " is only applicable to '/goal'" + RESET));
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
        LoopTask task = new LoopTask(prompt, intervalMinutes, cronExpr, runNow);

        try {
            scheduler.schedule(sessionId, task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            ctx.println(ctx.color(RED + "Failed: " + e.getMessage() + RESET));
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


        ctx.println(ctx.color("  " + DIM + "State:" + RESET + " persisted in workspace tasks.json"));
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

            line.append(" ").append(CYAN).append("[heartbeat]").append(RESET);

            line.append(" ").append(scheduleInfo);
            line.append(" ").append(status);

            if (t.isRunNow()) {
                line.append(" ").append(MAGENTA).append("[now]").append(RESET);
            }
            line.append(" ").append(DIM).append("iter:").append(t.getCurrentIteration()).append(RESET);

            line.append(" ").append(DIM).append(t.getPrompt()).append(RESET);
            line.append(lastInfo);


            ctx.println(ctx.color(line.toString()));
        }
        ctx.println(ctx.color(DIM + "\nUsage: /loop stop <id> | /loop stop-all" + RESET));
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
        ctx.println(ctx.color(DIM + "  /loop cron:\"0 */5 * * * ? *\" check status" + RESET));
        ctx.println(ctx.color(DIM + "" + RESET));
        ctx.println(ctx.color(DIM + "Session goals moved to /goal (see /goal for usage)" + RESET));
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
