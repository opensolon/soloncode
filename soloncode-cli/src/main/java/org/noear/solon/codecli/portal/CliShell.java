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
package org.noear.solon.codecli.portal;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.agent.TaskSkill;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.codecli.command.CliCommandContext;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Code CLI 终端
 */
@Preview("3.9.4")
public class CliShell implements Runnable {
    private final static Logger LOG = LoggerFactory.getLogger(CliShell.class);

    private Terminal terminal;
    private LineReader reader;
    private final HarnessEngine agentRuntime;
    private final AgentProperties agentProps;

    // ANSI 颜色常量
    private final static String
            BOLD = "\033[1m",
            DIM = "\033[2m",
            GREEN = "\033[32m",
            YELLOW = "\033[33m",
            RED = "\033[31m",
            CYAN = "\033[36m",
            RESET = "\033[0m";

    public CliShell(HarnessEngine agentRuntime, AgentProperties agentProps) {
        this.agentRuntime = agentRuntime;
        this.agentProps = agentProps;

        try {
            this.terminal = TerminalBuilder.builder()
                    .jna(true).jansi(true).system(true).dumb(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();

            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new CliCompleter(agentRuntime.getCommandRegistry()))
                    .build();
        } catch (Throwable e) {
            LOG.error("JLine initialization failed", e);
        }
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public LineReader getReader() {
        return reader;
    }

    /**
     * 预备开始
     */
    private AgentSession prepare(String sessionId) {
        // Windows 下将控制台切换为 UTF-8 代码页，避免中文输入乱码
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                Process process = new ProcessBuilder("cmd", "/c", "chcp", "65001").start();
                // 读取并丢弃输出，避免显示到控制台
                try (java.io.InputStream is = process.getInputStream()) {
                    while (is.read() != -1) {
                    }
                }
                process.waitFor();
            } catch (Exception ignored) {
            }
        }

        AgentSession session = agentRuntime.getSession(sessionId);
        printWelcome(session);
        return session;
    }

    /**
     * 单次调用
     */
    public void call(String input) {
        AgentSession session = prepare(agentProps.getSessionId());

        try {
            if (!isCommand(session, input)) {
                performAgentTask(session, input, null);
            }
        } catch (Throwable e) {
            terminal.writer().println("\n" + RED + "! Error: " + RESET + e.getMessage());
        }
    }

    /**
     * 长运行
     */
    @Override
    public void run() {
        AgentSession session = prepare(agentProps.getSessionId());

        // 2. 主循环
        while (true) {
            try {
                String input;

                try {
                    terminal.writer().println();
                    terminal.writer().print(BOLD + CYAN + "User" + RESET);
                    terminal.writer().println();
                    terminal.flush();

                    input = reader.readLine(BOLD + CYAN + "> " + RESET).trim();
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (Assert.isEmpty(input)) {
                    continue;
                }

                if (!isCommand(session, input)) {
                    performAgentTask(session, input, null);
                }
            } catch (Throwable e) {
                terminal.writer().println("\n" + RED + "! Error: " + RESET + e.getMessage());
            }
        }
    }

    private boolean isCommand(AgentSession session, String input) throws Exception {
        if (!input.startsWith("/")) {
            return false;
        }

        // 解析命令名和参数
        String[] parts = input.trim().substring(1).split("\\s+");
        String cmdName = parts[0].toLowerCase();
        List<String> args = parts.length > 1
                ? Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length))
                : Collections.emptyList();

        // 查找命令
        Command command = agentRuntime.getCommandRegistry().find(cmdName);
        if (command == null) {
            return false;
        }

        // 构建 context（注入 agentTaskRunner 回调）
        CliCommandContext ctx = new CliCommandContext(session, terminal, reader,
                agentRuntime, input, cmdName, args,
                (prompt, model) -> {
                    try {
                        performAgentTask(session, prompt, model);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // 执行命令
        boolean handled = command.execute(ctx);

        // clear 命令后重新打印 welcome
        if ("clear".equals(cmdName)) {
            printWelcome(session);
        }

        return handled;
    }

    private void performAgentTask(AgentSession session, String input, String modelSelected) throws Exception {
        terminal.writer().println("\n" + BOLD + "Assistant" + RESET);

        String currentInput = input;
        final AtomicBoolean isTaskCompleted = new AtomicBoolean(false);
        final AtomicBoolean isFirstConversation = new AtomicBoolean(true);

        if (modelSelected == null) {
            modelSelected = session.getContext().getAs(HarnessFlags.VAR_MODEL_SELECTED);
        }

        ChatModel chatModel = agentRuntime.getModelOrMain(modelSelected);

        while (true) {
            // 简化状态提示：只在非首次且任务未完成时打印等待符
            if (currentInput == null && !isTaskCompleted.get()) {
                terminal.writer().print("\r" + DIM + "  ... " + RESET);
                terminal.flush();
            }

            CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean isInterrupted = new AtomicBoolean(false);
            final AtomicBoolean isFirstReasonChunk = new AtomicBoolean(true);

            Prompt prompt = Prompt.of(currentInput).attrPut("start_time", System.currentTimeMillis());

            Disposable disposable = agentRuntime.prompt(prompt)
                    .session(session)
                    .options(o -> {
                        o.chatModel(chatModel);
                    })
                    .stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(chunk -> {
                        if (chunk instanceof ReasonChunk) {
                            // ReasonChunk 非工具调用时，为流式增量（工具调用时为全量，不需要打印）
                            onReasonChunk((ReasonChunk) chunk, isFirstReasonChunk, isFirstConversation);
                        } else if (chunk instanceof ThoughtChunk) {
                            //ThoughtChunk 为全量（ReasonChunk 的汇总）
                            onThoughtChunk((ThoughtChunk) chunk);
                        } else if (chunk instanceof ActionEndChunk) {
                            //ActionEndChunk 为全量，一次工具调用一个 ActionEndChunk
                            onActionEndChunk((ActionEndChunk) chunk, isFirstReasonChunk);
                        } else if (chunk instanceof ReActChunk) {
                            // ReActChunk 为全量，ReAct 完成任务时的最后答复
                            onFinalChunk((ReActChunk) chunk);
                        }
                    })
                    .doOnError(e -> {
                        LOG.error("Task fail: {}", e.getMessage(), e);

                        terminal.writer().println("\n" + RED + "── Error ────────────────" + RESET);
                        terminal.writer().println(e.getMessage());
                        terminal.flush();
                    })
                    .doFinally(signal -> {
                        isTaskCompleted.set(true);
                        latch.countDown();
                    })
                    .subscribe();

            // 监听回车中断
            if (disposable == null || disposable.isDisposed()) {
                // 处理订阅失败的情况
                return;
            }

            waitForTask(latch, disposable, session, isInterrupted);

            if (isInterrupted.get()) {
                terminal.writer().println(DIM + "[Task interrupted]" + RESET);
                terminal.flush();
                session.addMessage(ChatMessage.ofAssistant("用户已取消任务."));
                LOG.info("用户已取消任务.");
                return;
            }

            // HITL 处理 (授权交互)
            if (HITL.isHitl(session)) {
                if (handleHITL(session)) {
                    currentInput = null;
                    continue;
                } else {
                    return;
                }
            }

            if (isTaskCompleted.get()) {
                terminal.writer().println();
                terminal.flush();
                return;
            }

            currentInput = null;
        }
    }

    private void waitForTask(CountDownLatch latch, Disposable disposable,
                             AgentSession session, AtomicBoolean isInterrupted) throws Exception {
        Attributes originalAttributes = terminal.getAttributes();
        try {
            terminal.enterRawMode();

            while (latch.getCount() > 0) {
                int c = terminal.reader().read(50);
                if (c > 0) {
                    if (c == 27 || c == '\r' || c == '\n') {
                        disposable.dispose();
                        isInterrupted.set(true);
                        latch.countDown();
                        break;
                    }
                }

                if (HITL.isHitl(session)) {
                    latch.countDown();
                    break;
                }
            }
        } finally {
            terminal.setAttributes(originalAttributes);
        }

        latch.await();
    }

    private boolean handleHITL(AgentSession session) {
        HITLTask task = HITL.getPendingTask(session);
        HITLDecision decision = HITL.getDecision(session, task);

        if (decision != null) {
            if (decision.isRejected()) {
                return false;
            } else {
                return true;
            }
        }

        terminal.writer().println("\n" + BOLD + YELLOW + "Permission Required" + RESET);
        if ("bash".equals(task.getToolName())) {
            terminal.writer().println(DIM + "Command: " + RESET + task.getArgs().get("command"));
        }

        String choice = reader.readLine(BOLD + GREEN + "Approve? (y/n) " + RESET).trim().toLowerCase();
        if ("y".equals(choice) || "yes".equals(choice)) {
            HITL.approve(session, task.getToolName());
            return true;
        } else {
            HITL.reject(session, task.getToolName());
            terminal.writer().println(DIM + "Action rejected." + RESET);
            return false;
        }
    }

    private void onFinalChunk(ReActChunk react) {
        Long start_time = react.getTrace().getOriginalPrompt().attrAs("start_time");

        StringBuilder buf = new StringBuilder();
        buf.append(" (");

        buf.append(react.getTrace().getOptions().getChatModel().getNameOrModel());

        if (react.getTrace().getMetrics() != null) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            buf.append(react.getTrace().getMetrics().getTotalTokens()).append(" tokens");
        }

        if (start_time != null) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            long seconds = Duration.ofMillis(System.currentTimeMillis() - start_time).getSeconds();
            buf.append(seconds).append(" seconds");
        }

        buf.append(")");


        if (buf.length() > 4) {
            terminal.writer().println(DIM + buf + RESET);
        }
    }

    private void onReasonChunk(ReasonChunk reason, AtomicBoolean isFirstReasonChunk, AtomicBoolean isFirstConversation) {
        if (!reason.isToolCalls() && reason.hasContent()) {
            if (agentProps.isThinkPrinted() || !reason.getMessage().isThinking()) {
                String delta = clearThink(reason.getContent());
                onReasonChunkDo(delta, isFirstReasonChunk, isFirstConversation);
            }
        }
    }

    private void onReasonChunkDo(String delta, AtomicBoolean isFirstReasonChunk, AtomicBoolean isFirstConversation) {
        if (Assert.isNotEmpty(delta)) {
            if (isFirstReasonChunk.get()) {
                String trimmed = delta.replaceAll("^[\\s\\n]+", "");
                if (Assert.isNotEmpty(trimmed)) {
                    if (isFirstConversation.get()) {
                        terminal.writer().print("  ");
                        isFirstConversation.set(false);
                    } else {
                        terminal.writer().print("\n  ");
                    }

                    terminal.writer().print(trimmed.replace("\n", "\n  "));
                    isFirstReasonChunk.set(false);
                }
            } else {
                // 连续的思考内容，保持缩进替换即可
                terminal.writer().print(delta.replace("\n", "\n  "));
            }
            terminal.flush();
        }
    }


    private void onThoughtChunk(ThoughtChunk thought) {
        if (thought.hasMeta(TaskSkill.TOOL_MULTITASK)) {
            // 仅在多任务并行且有内容时输出
            String content = thought.getAssistantMessage().getResultContent();
            if (Assert.isNotEmpty(content)) {
                // 保持间接缩进，去掉首尾多余换行
                terminal.writer().println();
                terminal.writer().print("  " + content.trim().replace("\n", "\n  "));
                terminal.writer().println();
                terminal.flush();
            }
        }
    }

    private void onActionEndChunk(ActionEndChunk action, AtomicBoolean isFirstReasonChunk) {
        if (Assert.isNotEmpty(action.getToolName())) {
            if (TaskSkill.TOOL_MULTITASK.equals(action.getToolName()) ||
                    TaskSkill.TOOL_TASK.equals(action.getToolName())) {
                return;
            }

            final String fullToolName;

            if (agentRuntime.getName().equals(action.getAgentName())) {
                fullToolName = action.getToolName();
            } else {
                fullToolName = action.getAgentName() + "/" + action.getToolName();
            }


            // 1. 准备参数字符串
            StringBuilder argsBuilder = new StringBuilder();
            Map<String, Object> args = action.getArgs();
            if (args != null && !args.isEmpty()) {
                args.forEach((k, v) -> {
                    if (argsBuilder.length() > 0) {
                        argsBuilder.append(" ");
                    }

                    if (v instanceof List) {
                        argsBuilder.append(k).append("=[").append(((List) v).size()).append("项]");
                    } else {
                        argsBuilder.append(k).append("=").append(v);
                    }
                });
            }

            String argsStr = argsBuilder.toString().replace("\n", " ");
            boolean hasBigArgs = argsStr.length() > 100 || (args != null && args.values().stream().anyMatch(v -> v instanceof String && ((String) v).contains("\n")));

            if (agentProps.isCliPrintSimplified()) {
                // --- 简化风格：单行摘要模式 ---
                String content = action.getContent() == null ? "" : action.getContent().trim();
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

                // 简化模式下，参数也进行极简压缩
                String shortArgs = argsStr.length() > 40 ? argsStr.substring(0, 37) + "..." : argsStr;

                terminal.writer().println();
                terminal.writer().println(YELLOW + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + shortArgs + " (" + summary + ")" + RESET);
                terminal.flush();

            } else {
                // --- 全量风格 ---
                // 1. 打印指令行
                terminal.writer().println();
                if (!hasBigArgs) {
                    // 短参数直接跟在后面
                    terminal.writer().println(YELLOW + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + argsStr + RESET);
                } else {
                    // 大参数块，指令名独占一行，参数作为缩进内容打印（类似 write_file 的 content 部分）
                    terminal.writer().println(YELLOW + "❯ " + RESET + BOLD + fullToolName + RESET);
                    if (args != null) {
                        args.forEach((k, v) -> {
                            String val = String.valueOf(v).trim();
                            if ("content".equals(k) && val.split("\n").length > 10) {
                                // 如果是写文件，且内容太长，只显示头尾
                                String[] lines = val.split("\n");
                                val = lines[0] + "\n    ...\n    " + lines[lines.length - 1];
                            }
                            terminal.writer().println(DIM + "  [" + k + "]: " + val.replace("\n", "\n    ") + RESET);
                        });
                    }
                }

                // 2. 处理工具返回的结果内容 (getContent)
                if (Assert.isNotEmpty(action.getContent())) {
                    // 在参数和结果之间如果内容较多，可以加个小分隔，或者直接缩进打印
                    String indentedContent = "  " + action.getContent().trim().replace("\n", "\n  ");
                    terminal.writer().println(DIM + indentedContent + RESET);
                }

                terminal.writer().println(DIM + "  (End of output)" + RESET);
                terminal.flush();
            }

            // 3. 接下来 AI 可能会针对这个结果进行分析 (Reasoning)，设置首行缩进标记
            isFirstReasonChunk.set(true);
        }
    }

    private String clearThink(String chunk) {
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }


    protected void printWelcome(AgentSession session) {
        final ChatModel chatModel;

        if (session == null) {
            chatModel = agentRuntime.getMainModel();
        } else {
            String modelSelected = session.getContext().getAs(HarnessFlags.VAR_MODEL_SELECTED);
            chatModel = agentRuntime.getModelOrMain(modelSelected);
        }

        String path = new File(agentRuntime.getProps().getWorkspace()).getAbsolutePath();
        // 连带版本号，紧凑排列
        terminal.writer().println(BOLD + "SolonCode" + RESET + DIM + " " + AgentFlags.getVersion() + " PID-" + Utils.pid() + " Model:" + chatModel.getNameOrModel() + RESET);
        terminal.writer().println(DIM + path + RESET);
        terminal.writer().println(DIM + "Tips: " +
                RESET + "(esc)" + DIM + " interrupt | " +
                RESET + "/(tab)" + DIM + " ls commands" + RESET);

        terminal.flush();
    }

    public void printWelcome(String text) {
        final ChatModel chatModel = agentRuntime.getMainModel();

        String path = new File(agentRuntime.getProps().getWorkspace()).getAbsolutePath();
        // 连带版本号，紧凑排列
        terminal.writer().println(BOLD + "SolonCode" + RESET + DIM + " " + AgentFlags.getVersion() + " PID-" + Utils.pid() + " Model:" + chatModel.getNameOrModel() + RESET);
        terminal.writer().println(DIM + path + RESET);
        terminal.writer().println(text);
        terminal.flush();
    }
}