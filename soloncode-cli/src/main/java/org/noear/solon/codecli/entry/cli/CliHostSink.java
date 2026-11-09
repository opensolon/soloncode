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
package org.noear.solon.codecli.entry.cli;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.extension.goal.GoalTalent;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.Disposition;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.talents.todo.TodoTalent;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.util.MarkdownTableRenderer;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 终端呈现（L0 的 {@link HostSink}）：只决定「怎么打印」，受理、开流、续发积压、停流、审批续流、
 * goal / loop 后台轮次都由 {@link org.noear.solon.ai.harness.host.HarnessHost} 代办。
 *
 * <p>另外维护一个「在跑轮次数」（{@link #activeRuns()}）：主线程据此决定何时把终端交还给提示符——
 * 用户这一轮、它结束后续发的排队消息、后台轮次，都要跑完才回到 {@code readLine}。</p>
 *
 * <p>后台轮次（goal / loop）可能在主线程阻塞于 {@code readLine} 时开出：结束后打断 {@code readLine}
 * （{@code raise(INT)}），让提示符重新出现，主线程随后处理续发与审批。</p>
 */
class CliHostSink implements HostSink {
    private final static Logger LOG = LoggerFactory.getLogger(CliHostSink.class);

    final static String
            BOLD = "\033[1m",
            DIM = "\033[2m",
            GREEN = "\033[32m",
            YELLOW = "\033[33m",
            RED = "\033[31m",
            CYAN = "\033[36m",
            PURPLE = "\033[35m",
            RESET = "\033[0m";

    private final Terminal terminal;
    private final LineReader reader;
    private final HarnessEngine engine;
    private final AgentSettings agentProps;
    private final Consumer<AgentSession> welcomePrinter;
    private final AtomicInteger activeRuns = new AtomicInteger();

    CliHostSink(Terminal terminal, @Nullable LineReader reader, HarnessEngine engine, AgentSettings agentProps,
                Consumer<AgentSession> welcomePrinter) {
        this.terminal = terminal;
        this.reader = reader;
        this.engine = engine;
        this.agentProps = agentProps;
        this.welcomePrinter = welcomePrinter;
    }

    /** 当前在跑（已 open、未 finished）的轮次数 */
    int activeRuns() {
        return activeRuns.get();
    }

    // ==================== 会话级 ====================

    @Override
    public void reply(HostInput input, String text) {
        // CLI 的命令上下文直接写终端，通常没有缓冲回执；有就照打
        if (Assert.isNotEmpty(text)) {
            terminal.writer().println(text);
            terminal.flush();
        }
        if (input.getDisposition() == Disposition.REPLY && "clear".equals(input.getCommandName())) {
            welcomePrinter.accept(input.getSession());
        }
    }

    @Override
    public void admitted(HostInput input) {
        // /clear 没有回执时 reply 不会被调用，在这里补欢迎信息
        if (input.getDisposition() == Disposition.REPLY && input.hasReply() == false
                && "clear".equals(input.getCommandName())) {
            welcomePrinter.accept(input.getSession());
        }
    }

    @Override
    public void deferred(HostInput input, int position) {
        println("当前任务正在执行，已加入队列（第 " + position + " 位）");
    }

    @Override
    public void rejected(HostInput input, RejectReason reason, @Nullable String detail) {
        println(detail != null ? detail : "输入未受理（" + reason.code() + "）");
    }

    @Override
    public void hitl(HostInput input, List<HitlCard> cards) {
        // 不在这里提问：审批由主线程在轮次结束后逐张询问（CliShell#handleHitl）
    }

    @Override
    public void hitlExpired(HostInput input) {
        println(DIM + "[审批未生效：无挂起任务或续流未生成请求]" + RESET);
    }

    // ==================== 轮次级 ====================

    @Override
    public RunSink open(HostRun run) {
        activeRuns.incrementAndGet();
        return new CliRunSink(run);
    }

    private void println(String text) {
        terminal.writer().println(text);
        terminal.flush();
    }

    /** 一轮的终端输出（回调串行，按轮保存缩进状态） */
    private class CliRunSink implements RunSink {
        private final HostRun run;
        private boolean firstConversation = true;
        private boolean firstReasonDelta = true;

        CliRunSink(HostRun run) {
            this.run = run;
        }

        @Override
        public void prepare(RunPrep prep) {
            // 审批续流是同一个回答的延续：不再打印标题
            if (run.getInput().isHitlDecision()) {
                return;
            }
            String label = run.isProgram() ? " [" + run.getProgramChannel() + "]" : "";
            terminal.writer().println("\n" + BOLD + "Assistant" + RESET + DIM + label + " " + timeNow() + RESET);
            terminal.flush();
        }

        @Override
        public void event(AgentEvent chunk) {
            if (chunk instanceof ReasonDeltaEvent) {
                onReasonDelta((ReasonDeltaEvent) chunk);
            } else if (chunk instanceof ReasonEndEvent) {
                onReasonEnd((ReasonEndEvent) chunk);
            } else if (chunk instanceof ToolCallEndEvent) {
                onToolCallEnd((ToolCallEndEvent) chunk);
            } else if (chunk instanceof RunEndEvent) {
                onRunEnd((RunEndEvent) chunk);
            }
        }

        @Override
        public void finished(RunOutcome outcome) {
            try {
                if (outcome.isCancelled()) {
                    println(DIM + "[Task interrupted]" + RESET);
                } else if (outcome.isFailed()) {
                    Throwable e = outcome.getError();
                    LOG.error("Task fail: {}", e == null ? null : e.getMessage(), e);
                    terminal.writer().println("\n" + RED + "── Error ────────────────" + RESET);
                    println(e == null ? "run failed" : String.valueOf(e.getMessage()));
                } else if (outcome.isStreamOpened() && outcome.getHitlCards().isEmpty()) {
                    println("");
                }
            } finally {
                activeRuns.decrementAndGet();
                // 后台轮次在用户停在提示符时开出：打断 readLine，让提示符重现并由主线程接着处理续发与审批
                if (reader != null && reader.isReading()) {
                    terminal.raise(Terminal.Signal.INT);
                }
            }
        }

        private void onReasonDelta(ReasonDeltaEvent e) {
            String delta = clearThink(e.getText());
            if (e.isThinking()) {
                if (agentProps.getGeneral().isCliThinkPrinted()) {
                    printDelta(DIM + delta + RESET);
                }
            } else {
                printDelta(delta);
            }
        }

        private void printDelta(String delta) {
            if (Assert.isEmpty(delta)) {
                return;
            }
            if (firstReasonDelta) {
                String trimmed = delta.replaceAll("^[\\s\\n]+", "");
                if (Assert.isNotEmpty(trimmed)) {
                    if (firstConversation) {
                        terminal.writer().print("  ");
                        firstConversation = false;
                    } else {
                        terminal.writer().print("\n  ");
                    }
                    terminal.writer().print(indent(MarkdownTableRenderer.render(trimmed)));
                    firstReasonDelta = false;
                }
            } else {
                // 连续的思考内容，保持缩进替换即可
                terminal.writer().print(indent(MarkdownTableRenderer.render(delta)));
            }
            terminal.flush();
        }

        /**
         * 给每行加上两格缩进，与回复正文的缩进风格保持一致。
         */
        private String indent(String text) {
            return text.replace("\n", "\n  ");
        }

        private void onReasonEnd(ReasonEndEvent e) {
            // 仅在多任务并行且有内容时输出
            if (e.hasMeta(TaskTalent.TOOL_MULTITASK)) {
                String content = e.getText();
                if (Assert.isNotEmpty(content)) {
                    terminal.writer().println();
                    terminal.writer().print("  " + content.trim().replace("\n", "\n  "));
                    terminal.writer().println();
                    terminal.flush();
                }
            }
        }

        private void onRunEnd(RunEndEvent e) {
            ReActTrace trace = e.getTrace();
            if (trace == null) {
                return;
            }
            StringBuilder traceInfo = traceInfo(trace);
            if (traceInfo.length() > 4) {
                println(DIM + traceInfo + RESET);
            }
        }

        private void onToolCallEnd(ToolCallEndEvent e) {
            if (e.getError() != null || Assert.isEmpty(e.getToolName())) {
                return;
            }
            String toolName = e.getToolName();
            if (TaskTalent.TOOL_MULTITASK.equals(toolName) || TaskTalent.TOOL_TASK.equals(toolName)
                    || MemoryTalent.isMemoryTool(toolName) || GoalTalent.isGoalTool(toolName)) {
                return;
            }

            final String fullToolName = engine.getName().equals(e.getAgentName()) ? toolName : e.getAgentName() + "/" + toolName;

            // 1. 准备参数字符串
            StringBuilder argsBuilder = new StringBuilder();
            Map<String, Object> args = e.getArgs();
            if (args != null && !args.isEmpty()) {
                args.forEach((k, v) -> {
                    if (argsBuilder.length() > 0) {
                        argsBuilder.append(" ");
                    }
                    if (v instanceof List) {
                        argsBuilder.append(k).append("=[").append(((List<?>) v).size()).append("项]");
                    } else {
                        argsBuilder.append(k).append("=").append(v);
                    }
                });
            }

            boolean isTodo = TodoTalent.TOOL_TODOREAD.equals(toolName) || TodoTalent.TOOL_TODOWRITE.equals(toolName);
            String argsStr = argsBuilder.toString().replace("\n", " ");
            boolean hasBigArgs = argsStr.length() > 100 || (args != null && args.values().stream()
                    .anyMatch(v -> v instanceof String && ((String) v).contains("\n")));

            if (agentProps.getGeneral().isCliPrintSimplified() && isTodo == false) {
                // --- 简化风格：单行摘要模式 ---
                String content = e.getText() == null ? "" : e.getText().trim();
                String summary;
                if (Assert.isEmpty(content)) {
                    summary = "completed";
                } else {
                    String[] lines = content.split("\n");
                    if (lines.length > 1) {
                        summary = "returned " + lines.length + " lines";
                    } else {
                        summary = content.length() > 40 ? content.substring(0, 37) + "..." : content;
                    }
                }
                String shortArgs = argsStr.length() > 40 ? argsStr.substring(0, 37) + "..." : argsStr;

                terminal.writer().println();
                println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + shortArgs + " (" + summary + ")" + RESET);
            } else {
                // --- 全量风格 ---
                terminal.writer().println();
                if (TodoTalent.TOOL_TODOWRITE.equals(toolName) && args != null && args.get("todos") instanceof String) {
                    argsStr = "\n" + ((String) args.get("todos")).trim();
                    terminal.writer().println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + argsStr + RESET);
                } else if (!hasBigArgs) {
                    terminal.writer().println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + argsStr + RESET);
                } else {
                    // 大参数块，指令名独占一行，参数作为缩进内容打印
                    terminal.writer().println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET);
                    if (args != null) {
                        args.forEach((k, v) -> {
                            String val = String.valueOf(v).trim();
                            if ("content".equals(k) && val.split("\n").length > 10) {
                                // 写文件且内容太长：只显示头尾
                                String[] lines = val.split("\n");
                                val = lines[0] + "\n    ...\n    " + lines[lines.length - 1];
                            }
                            terminal.writer().println(DIM + "  [" + k + "]: " + val.replace("\n", "\n    ") + RESET);
                        });
                    }
                }

                if (Assert.isNotEmpty(e.getText())) {
                    String indentedContent = "  " + e.getText().trim().replace("\n", "\n  ");
                    terminal.writer().println(DIM + indentedContent + RESET);
                }
                println(DIM + "  (End of output)" + RESET);
            }

            // 接下来 AI 可能会针对这个结果进行分析，设置首行缩进标记
            firstReasonDelta = true;
        }
    }

    private static StringBuilder traceInfo(ReActTrace trace) {
        long startTime = trace.getBeginTimeMs();

        StringBuilder buf = new StringBuilder();
        buf.append(" (");
        if (trace.getOptions() != null && trace.getOptions().getChatModel() != null) {
            buf.append(trace.getOptions().getChatModel().getNameOrModel());
        }
        if (trace.getMetrics() != null) {
            if (buf.length() > 2) {
                buf.append(", ");
            }
            buf.append(trace.getMetrics().getTotalTokens()).append("tk");
        }
        if (startTime > 0) {
            if (buf.length() > 2) {
                buf.append(", ");
            }
            long seconds = Duration.ofMillis(System.currentTimeMillis() - startTime).getSeconds();
            buf.append(seconds).append("s");
        }
        buf.append(")");
        return buf;
    }

    static String clearThink(String chunk) {
        if (chunk == null) {
            return null;
        }
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    private static String timeNow() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
