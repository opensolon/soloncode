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

import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.CommandType;
import org.noear.solon.codecli.config.AgentProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * /loop 命令 - 定时任务管理
 *
 * <pre>
 * /loop 5m check if deployment finished    → fixed interval (5m)
 * /loop 30s check ci status               → fixed interval (30s)
 * /loop check ci status                   → auto interval (5m default)
 * /loop ls                                → list active tasks
 * /loop stop <id>                         → stop a task
 * /loop stop-all                          → stop all tasks
 * /loop                                   → same as /loop ls
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
        return "循环任务管理 (ls, stop, stop-all, <interval> <prompt>)";
    }

    @Override
    public CommandType type() {
        return CommandType.SYSTEM;
    }

    @Override
    public boolean cliOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandContext ctx) throws Exception {
        String sessionId = ctx.getSession().getSessionId();
        AgentProperties props = (AgentProperties) ctx.getEngine().getProps();
        String workspace = props.getWorkspace();
        String harnessSessions = props.getHarnessSessions();

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
            // Schedule a new task: /loop [interval] <prompt>
            int intervalMinutes = 5; // default
            int promptStartIndex = 0;

            Integer parsed = parseInterval(sub);
            if (parsed != null) {
                intervalMinutes = parsed;
                promptStartIndex = 1;
            }

            // Build prompt from remaining args
            StringBuilder promptBuilder = new StringBuilder();
            for (int i = promptStartIndex; ; i++) {
                String arg = ctx.argAt(i);
                if (arg == null) break;
                if (promptBuilder.length() > 0) promptBuilder.append(" ");
                promptBuilder.append(arg);
            }

            String prompt = promptBuilder.toString().trim();
            if (prompt.isEmpty()) {
                ctx.println(ctx.color(RED + "Usage: /loop [interval] <prompt>" + RESET));
                ctx.println(ctx.color(DIM + "  /loop 5m check deployment" + RESET));
                ctx.println(ctx.color(DIM + "  /loop 30s check CI status" + RESET));
                ctx.println(ctx.color(DIM + "  /loop check CI status   (auto 5m)" + RESET));
                return true;
            }

            // Create and schedule
            LoopTask task = new LoopTask(prompt, intervalMinutes);
            scheduler.schedule(sessionId, workspace, harnessSessions, task);

            ctx.println(ctx.color(GREEN + "Loop task registered:" + RESET));
            ctx.println(ctx.color("  " + BOLD + "ID:" + RESET + " " + task.getId()));
            ctx.println(ctx.color("  " + BOLD + "Interval:" + RESET + " " + formatInterval(intervalMinutes)));
            ctx.println(ctx.color("  " + BOLD + "Prompt:" + RESET + " " + prompt));
            ctx.println(ctx.color(DIM + "  Expires: " + task.getExpireAt() + RESET));
        }

        return true;
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
            String lastInfo = t.getLastExecutedAt() != null
                    ? DIM + " (last: " + formatAgo(t.getLastExecutedAt()) + ": " + (t.getLastResult() != null ? t.getLastResult() : "-") + ")" + RESET
                    : "";
            ctx.println(ctx.color("  " + CYAN + t.getId() + RESET + " " + formatInterval(t.getIntervalMinutes()) + " " + status + " " + DIM + t.getPrompt() + RESET + lastInfo));
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
