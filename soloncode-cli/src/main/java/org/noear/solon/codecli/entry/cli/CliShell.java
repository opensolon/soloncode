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

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.CommandContextFactory;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.ParsedCommand;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.command.CliCommandContext;
import org.noear.solon.ai.harness.session.goal.GoalTalent;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.ai.harness.runtime.ExecutionBridge;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.util.TraceUtil;
import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.solon.ai.harness.session.queue.SessionQueueDrainer;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.DateUtil;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Code CLI 终端
 */
@Preview("3.9.4")
public class CliShell implements Runnable {
    /** CLI 渠道来源身份（原先由 CliCommandContext.getSource 硬编码，现统一由输入侧声明） */
    private static final InputOrigin CLI_ORIGIN = InputOrigin.channel("CLI");

    private final static Logger LOG = LoggerFactory.getLogger(CliShell.class);

    private final static String SESSION_ID_CLI = "cli";

    private Terminal terminal;
    private LineReader reader;
    private final HarnessEngine engine;
    private final AgentSettings agentProps;
    private final LoopScheduler loopScheduler;
    /** 执行桥（loop 与 goal 共享；CLI 在此注册主线程执行器与繁忙检查器）。 */
    private final ExecutionBridge executionBridge;
    private boolean cliQueueDraining;

    // ANSI 颜色常量
    private final static String
            BOLD = "\033[1m",      // 粗体 / 高亮
            DIM = "\033[2m",       // 暗淡 / 半透明（部分终端支持）
            GREEN = "\033[32m",    // 前景色：绿色
            YELLOW = "\033[33m",   // 前景色：黄色
            RED = "\033[31m",      // 前景色：红色
            CYAN = "\033[36m",     // 前景色：青色 / 蓝绿色
            BLUE = "\033[34m",     // 前景色：蓝色
            PURPLE = "\033[35m",   // 前景色：紫色 / 品红
            RESET = "\033[0m";     // 重置所有样式和颜色（恢复终端默认）


    public CliShell(HarnessEngine engine, AgentSettings agentProps, LoopScheduler loopScheduler,
                    ExecutionBridge executionBridge) {
        this.engine = engine;
        this.agentProps = agentProps;
        this.loopScheduler = loopScheduler;
        this.executionBridge = executionBridge;

        try {
            this.terminal = TerminalBuilder.builder()
                    .jna(true).jansi(true).system(true).dumb(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();

            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new CliCompleter(engine))
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

        AgentSession session = engine.getSession(sessionId);
        printWelcome(session);
        drainCliQueue(session);
        return session;
    }

    /**
     * 单次调用
     */
    public void call(String input) {
        if (Assert.isEmpty(input)) {
            return;
        }

        AgentSession session = prepare(SESSION_ID_CLI);

        try {
            handleInput(session, input);
        } catch (Throwable e) {
            terminal.writer().println("\n" + RED + "! Error: " + RESET + e.getMessage());
        }
    }

    /**
     * 长运行
     */
    @Override
    public void run() {
        AgentSession session = prepare(SESSION_ID_CLI);


        // 恢复上次未过期的 loop 定时任务（如果有）
        if (loopScheduler != null) {
            loopScheduler.restore(session.getSessionId());
        }

        // 执行桥注册：loop 定时任务与 goal 轮次共用同一桥（与 loopScheduler 是否存在无关）
        if (executionBridge != null) {
            // 会话繁忙守卫：CLI 为单线程模型，仅当主线程在等待用户输入（reader.isReading）时才空闲。
            // 若不在等待输入，说明 agent 任务正在执行，触发应跳过本次。
            executionBridge.addBusyChecker(sessionId -> {
                if (SESSION_ID_CLI.equals(sessionId) == false) {
                    return false;
                }
                return reader == null || !reader.isReading();
            });

            // 注入任务执行器：loop 定时任务触发时，在调度线程执行 agent 回合
            // 注意：它并非运行在主线程 —— 此刻主线程通常仍阻塞于 readLine，两者共用同一 reader，
            // 因此这里不消费队列（队列消费只归属主循环）
            executionBridge.addTaskExecutor((sessionId, prompt, agentName) -> {
                if (SESSION_ID_CLI.equals(sessionId) == false) {
                    return null;
                }

                // P0-fix: 如果指定了 agentName，将 prompt 拼接为 @agentName prompt 格式
                // performAgentTask 内部通过 input.startsWith("@") 识别并路由到对应 agent
                String effectiveInput = prompt;
                if (agentName != null && !agentName.isEmpty()) {
                    effectiveInput = "@" + agentName + " " + prompt;
                }

                // 直接返回 ReAct 完成时的权威全文，由 goal_update(complete) 标记完成。
                return safeChatInput(session, effectiveInput);
            });
        }

        // 2. 主循环
        while (true) {
            try {
                // 回到提示符前消费积压：loop 注入回合不排水，由主线程在掌控终端时统一消费
                drainCliQueue(session);

                String input;

                try {
                    terminal.writer().println();
                    terminal.writer().print(BOLD + CYAN + "User" + RESET);
                    terminal.writer().println();
                    terminal.flush();

                    input = reader.readLine(CYAN + "❯ " + RESET).trim();
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    terminal.writer().println("\nBye!");
                    terminal.flush();
                    break; // 直接跳出主循环，优雅退出
                }

                if (Assert.isEmpty(input)) {
                    continue;
                }

                handleInput(session, input);
            } catch (Throwable e) {
                LOG.warn(e.getMessage(), e);
                terminal.writer().println("\n" + RED + "! Error: " + RESET + e.getMessage());
            }
        }
    }

    private String safeChatInput(AgentSession session, String prompt) {
        if (reader != null && reader.isReading()) {
            try {
                return handleMessageInput(session, prompt, null);
            } catch (Exception e) {
                LOG.error("Loop task execution failed: {}", e.getMessage(), e);
            } finally {
                //打断主线程的 readLine 阻塞 // 触发 run() 循环中的 UserInterruptException
                terminal.raise(Terminal.Signal.INT);
            }
        }
        return null;
    }

    /**
     * 用户手输与单次调用：识别命令、具备终端能力、回合结束后消费队列
     * <p>
     * 命令识别、执行、子代理解析、请求装配全部由引擎完成；宿主只负责「回执怎么渲染」
     * 与「流怎么消费」。
     */
    private String handleInput(AgentSession session, String input) throws Exception {
        HarnessInput harnessInput = HarnessInput.of(session, input)
                .origin(CLI_ORIGIN)
                .commandAware(true)
                //只在识别命令时装配：这两项全仓仅 dispatchCommand 读取，不识别命令时传了也是死配置
                .terminal(true)
                .commandContextFactory(cliCommandFactory());

        return dispatchOutcome(session, engine.handle(harnessInput), true);
    }

    /**
     * 按普通消息处理一段已受理的文本（不识别命令，仍解析 @子代理）
     * <p>
     * 队列项与 loop 注入都走这里：文本来源不是用户手输，即使以命令前缀开头也不当作命令。
     * 也不消费队列 —— 队列消费只归属用户手输回合与主循环，避免调度线程排水与主线程
     * readLine 争用同一个 reader。
     */
    private String handleMessageInput(AgentSession session, String text, String model) throws Exception {
        HarnessInput harnessInput = HarnessInput.of(session, text)
                .origin(CLI_ORIGIN)
                .commandAware(false)
                .selectedModel(model);

        return dispatchOutcome(session, engine.handle(harnessInput), false);
    }

    /**
     * 消费一次处置结果（手输、队列项、loop 注入共用）
     *
     * @return 任务执行时的最终答复（无任务时为 null）
     */
    private String dispatchOutcome(AgentSession session, InputOutcome outcome, boolean allowDrain) throws Exception {
        switch (outcome.getKind()) {
            case TASK:
                // 有任务就执行（命令触发与用户手输同一分支）：命令回执已由命令即时写入终端
                String answer = performAgentTask(session, outcome);
                //只有用户手输回合消费队列（与改造前的既有行为一致）
                if (allowDrain) {
                    drainCliQueue(session);
                }
                return answer;
            case REPLY:
                // 只有回执：命令已执行或在本渠道不可用（CLI 侧不会出现），保持原行为
                if (outcome.hasReply()) {
                    terminal.writer().println(outcome.getCommandOutput());
                    terminal.flush();
                }
                // CLI 与 Web/IM 共用 SessionQueue；命令完成后由 CLI 自身消费空闲队列
                //注意顺序：先 drain 再 printWelcome（与改造前的既有行为一致）
                if (allowDrain) {
                    drainCliQueue(session);
                }
                if ("clear".equals(outcome.getCommandName())) {
                    printWelcome(session);
                }
                return null;
            case HITL:
                // 仅当宿主只写决策不续流时出现；CLI 单卡审批走 .hitl().resume(true)，得到 TASK
                return null;
            case IGNORED:
            default:
                return null;
        }
    }

    /**
     * 命令行上下文工厂：保留 Terminal/Reader 以支持 /help 交互与 /model 选择器
     * <p>
     * 命令内部触发的任务只记录意图（由分发器交给引擎二段装配），
     * 因此这里不再需要传回调闭包。
     */
    private CommandContextFactory cliCommandFactory() {
        return new CommandContextFactory() {
            @Override
            public CommandContext create(HarnessEngine e, AgentSession s, ParsedCommand command, boolean terminal) {
                return new CliCommandContext(s, CliShell.this.terminal, reader,
                        command.getRawInput(), command.getName(), command.getArgs(), terminal);
            }
        };
    }

    private void drainCliQueue(AgentSession session) {
        if (session == null || cliQueueDraining) return;
        cliQueueDraining = true;
        try {
            while (SessionQueueDrainer.drainOne(session, item -> handleMessageInput(session, item.getText(), item.getModel()))) {
                // 统一由 SessionQueueDrainer 负责 claim/poll/ack/requeue。
            }
        } finally {
            cliQueueDraining = false;
        }
    }

    private String getTimeNow() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /**
     * 执行一轮 Agent 任务（CLI 的 HITL 多轮模型：一次输入可能对应多轮流）
     * <p>
     * 首轮直接用引擎装配好的请求；HITL 恢复后的续轮用 {@link InputOutcome} 里的最终生效字段
     * 继续驱动 —— 不重新解析提及、不重新回退模型。
     */
    private String performAgentTask(AgentSession session, InputOutcome outcome) throws Exception {
        terminal.writer().println("\n" + BOLD + "Assistant" + RESET + DIM + " " + getTimeNow() + RESET);

        final String agentName = outcome.getAgentName();
        final String modelName = outcome.getModelName();

        final AtomicBoolean isFirstConversation = new AtomicBoolean(true);
        // ReAct 完成时的权威全文，多轮 HITL 时以最后一轮为准。
        final AtomicReference<String> finalAnswer = new AtomicReference<>();

        //待执行结果：首轮由引擎装配；后续每轮由 HITL 决策（决策 + 续流合一次调用）产生
        InputOutcome pendingOutcome = outcome;

        while (true) {
            CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean isInterrupted = new AtomicBoolean(false);
            final AtomicBoolean isFirstReasonDeltaChunk = new AtomicBoolean(true);

            //首轮与 HITL 续轮均用引擎装配好的请求（正文为 null 的续轮不重新解析提及、不重新回退模型）
            final ReActRequest request;
            if (pendingOutcome != null && pendingOutcome.hasRequest()) {
                request = pendingOutcome.getRequest();
                pendingOutcome = null;
            } else {
                //无可执行请求：仅当决策已被外部写入而任务未继续时出现，安全收尾
                return finalAnswer.get();
            }

            Disposable disposable = request
                    .stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(chunk -> {
                        if (chunk instanceof ReasonDeltaEvent) {
                            // ReasonChunk （思考）为增量块（工具调用时为全量，不需要打印）
                            onReasonDeltaEvent((ReasonDeltaEvent) chunk, isFirstReasonDeltaChunk, isFirstConversation);
                        } else if (chunk instanceof ReasonEndEvent) {
                            //ThoughtChunk （想法）为完成块
                            onReasonEndEvent(session, (ReasonEndEvent) chunk);
                        } else if (chunk instanceof ToolCallEndEvent) {
                            //ObservationChunk 为全量，一次工具调用产生一个 ObservationChunk
                            onToolCallEndEvent((ToolCallEndEvent) chunk, isFirstReasonDeltaChunk);
                        } else if (chunk instanceof RunEndEvent) {
                            // ReActChunk 为全量，ReAct 完成任务时的最后答复
                            String answer = onRunEndEvent(session, (RunEndEvent) chunk);
                            if (Assert.isNotEmpty(answer)) {
                                finalAnswer.set(answer);
                            }
                        }
                    })
                    .doOnError(e -> {
                        LOG.error("Task fail: {}", e.getMessage(), e);

                        terminal.writer().println("\n" + RED + "── Error ────────────────" + RESET);
                        terminal.writer().println(e.getMessage());
                        terminal.flush();
                    })
                    .doFinally(signal -> latch.countDown())
                    .subscribe();

            // 监听回车中断
            if (disposable == null || disposable.isDisposed()) {
                // 处理订阅失败的情况
                return finalAnswer.get();
            }

            waitForTask(latch, disposable, session, isInterrupted);

            if (isInterrupted.get()) {
                terminal.writer().println(DIM + "[Task interrupted]" + RESET);
                terminal.flush();
                AssistantMessage cancelMessage = ChatMessage.ofAssistant("用户已取消任务.");
                ReActTrace trace = TraceUtil.getCurrentTrace(session);
                if (trace != null) {
                    cancelMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
                }
                session.addMessage(cancelMessage);
                // 补快照：中断时 agent 收尾被跳过，需将当前 trace 与取消消息一并持久化，避免重载后 trace 丢失
                session.updateSnapshot();
                LOG.info("用户已取消任务.");
                return finalAnswer.get();
            }

            // HITL 处理 (授权交互)：决策写入与续流装配统一走引擎入口
            if (HITL.isHitl(session)) {
                pendingOutcome = handleHitlRound(session, agentName, modelName);
                if (pendingOutcome == null) {
                    //审批未能转化为续流：给出可见说明，不让用户无交代地回到提示符
                    terminal.writer().println(DIM + "[审批未生效：无挂起任务或续流未生成请求]" + RESET);
                    terminal.flush();
                    return finalAnswer.get();
                }
                continue;
            }

            //既未中断、也无挂起审批：本轮流已终结，收尾
            terminal.writer().println();
            terminal.flush();
            return finalAnswer.get();
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
                    if (c == 27) { //|| c == '\r' || c == '\n'
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
        } catch (Throwable e) {
            LOG.warn(e.getMessage(), e);
        } finally {
            terminal.setAttributes(originalAttributes);
        }

        latch.await();
    }

    /**
     * 处理一轮 HITL 审批：读决策 → 交引擎写入 → 同一次调用完成续流装配
     * <p>
     * 决策与续流合并为一次 {@code handle}（{@code .hitl(...).resume(true)}）：
     * 既走唯一入口（不绕过 {@link HarnessEngine#handle}），又能享受引擎的
     * callId 精确定位与工具返回改写（{@code hitlOutput}）。
     *
     * @return 下一轮要执行的处置结果（必有请求）；决策不可用时返回 null
     */
    private InputOutcome handleHitlRound(AgentSession session, String agentName, String modelName) throws Exception {
        HITLTask task = HITL.getPendingTask(session);

        if (task == null) {
            return null;
        }

        // 上一轮决策仍有残留（CLI 会话 ID 固定为 cli，不会与 Web/IM 会话共用）：不再提问，按原决策的三态透传（skip 不能升格为 approve）
        HITLDecision existing = HITL.getDecision(session, task);
        HITLAction action;

        if (existing != null) {
            action = existing.isRejected() ? HITLAction.REJECT
                    : existing.isSkipped() ? HITLAction.SKIP : HITLAction.APPROVE;
        } else {
            terminal.writer().println("\n" + BOLD + YELLOW + "Permission Required" + RESET);
            if ("bash".equals(task.getToolName())) {
                terminal.writer().println(DIM + "Command: " + RESET + task.getArgs().get("command"));
            }

            String choice = reader.readLine(BOLD + GREEN + "Approve? (y/n) " + RESET).trim().toLowerCase();
            action = ("y".equals(choice) || "yes".equals(choice)) ? HITLAction.APPROVE : HITLAction.REJECT;

            if (action == HITLAction.REJECT) {
                terminal.writer().println(DIM + "Action rejected." + RESET);
            }
        }

        // 按 callUuid 指定目标：避免并行挂起同名工具时误命中其它任务
        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                .hitl(action, task.getCallUuid())
                .resume(true)
                .selectedModel(modelName)
                .selectedAgent(agentName));

        return outcome.hasRequest() ? outcome : null;
    }

    private StringBuilder getTraceInfo(ReActTrace trace) {
        long start_time = trace.getBeginTimeMs();

        StringBuilder buf = new StringBuilder();
        buf.append(" (");

        buf.append(trace.getOptions().getChatModel().getNameOrModel());

        if (trace.getMetrics() != null) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            buf.append(trace.getMetrics().getTotalTokens()).append("tk");
        }

        if (start_time > 0) {
            if (buf.length() > 2) {
                buf.append(", ");
            }

            long seconds = Duration.ofMillis(System.currentTimeMillis() - start_time).getSeconds();
            buf.append(seconds).append("s");
        }

        buf.append(")");

        return buf;
    }

    private void onReasonDeltaEvent(ReasonDeltaEvent reasonDeltaEvent, AtomicBoolean isFirstReasonDeltaChunk, AtomicBoolean isFirstConversation) {
        String delta = clearThink(reasonDeltaEvent.getText());

        if (reasonDeltaEvent.isThinking()) {
            if (agentProps.getGeneral().isCliThinkPrinted()) {
                onReasonDeltaChunkDo(DIM + delta + RESET, isFirstReasonDeltaChunk, isFirstConversation);
            }
        } else {
            onReasonDeltaChunkDo(delta, isFirstReasonDeltaChunk, isFirstConversation);
        }
    }

    private void onReasonDeltaChunkDo(String delta, AtomicBoolean isFirstReasonDeltaChunk, AtomicBoolean isFirstConversation) {
        if (Assert.isNotEmpty(delta)) {
            if (isFirstReasonDeltaChunk.get()) {
                String trimmed = delta.replaceAll("^[\\s\\n]+", "");
                if (Assert.isNotEmpty(trimmed)) {
                    if (isFirstConversation.get()) {
                        terminal.writer().print("  ");
                        isFirstConversation.set(false);
                    } else {
                        terminal.writer().print("\n  ");
                    }

                    terminal.writer().print(trimmed.replace("\n", "\n  "));
                    isFirstReasonDeltaChunk.set(false);
                }
            } else {
                // 连续的思考内容，保持缩进替换即可
                terminal.writer().print(delta.replace("\n", "\n  "));
            }
            terminal.flush();
        }
    }


    private void onReasonEndEvent(AgentSession session, ReasonEndEvent reasonEndEvent) {
        ReActTrace trace = reasonEndEvent.getTrace();

        Long totalTokens = trace.getMetrics() != null ? trace.getMetrics().getTotalTokens() : null;
        // ★ 捕获真实 token 消耗，供 LoopScheduler 预算控制使用
        if (totalTokens != null) {
            session.attrs().put("_loop_last_total_tokens", totalTokens);
        }

        if (reasonEndEvent.hasMeta(TaskTalent.TOOL_MULTITASK)) {
            // 仅在多任务并行且有内容时输出
            String content = reasonEndEvent.getText();
            if (Assert.isNotEmpty(content)) {

                //content = content + DIM + "(" + thought.getTrace().getOptions().getChatModel().getNameOrModel() + ")" + RESET;

                // 保持间接缩进，去掉首尾多余换行
                terminal.writer().println();
                terminal.writer().print("  " + content.trim().replace("\n", "\n  "));
                terminal.writer().println();
                terminal.flush();
            }
        }
    }

    private String onRunEndEvent(AgentSession session, RunEndEvent runEndEvent) {
        ReActTrace trace = runEndEvent.getTrace();
        StringBuilder traceInfo = getTraceInfo(trace);

        if (traceInfo.length() > 4) {
            terminal.writer().println(DIM + traceInfo + RESET);
        }

        Long totalTokens = trace.getMetrics() != null ? trace.getMetrics().getTotalTokens() : null;
        // ★ 捕获真实 token 消耗，供 LoopScheduler 预算控制使用
        if (totalTokens != null) {
            session.attrs().put("_loop_last_total_tokens", totalTokens);
        }

        // 返回 ReAct 完成时的权威全量答复，由调用方用于 loop goal 判定。
        return clearThink(runEndEvent.getText());
    }

    private void onToolCallEndEvent(ToolCallEndEvent toolCallEndEvent, AtomicBoolean isFirstReasonDeltaChunk) {
        if (toolCallEndEvent.getError() != null) {
            return;
        }

        if (Assert.isNotEmpty(toolCallEndEvent.getToolName())) {
            if (TaskTalent.TOOL_MULTITASK.equals(toolCallEndEvent.getToolName()) ||
                    TaskTalent.TOOL_TASK.equals(toolCallEndEvent.getToolName()) ||
                    MemoryTalent.isMemoryTool(toolCallEndEvent.getToolName()) ||
                    GoalTalent.isGoalTool(toolCallEndEvent.getToolName())) {
                return;
            }

            final String fullToolName;

            if (engine.getName().equals(toolCallEndEvent.getAgentName())) {
                fullToolName = toolCallEndEvent.getToolName();
            } else {
                fullToolName = toolCallEndEvent.getAgentName() + "/" + toolCallEndEvent.getToolName();
            }


            // 1. 准备参数字符串
            StringBuilder argsBuilder = new StringBuilder();
            Map<String, Object> args = toolCallEndEvent.getArgs();
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

            boolean isTodo = TodoTalent.TOOL_TODOREAD.equals(toolCallEndEvent.getToolName()) || TodoTalent.TOOL_TODOWRITE.equals(toolCallEndEvent.getToolName());
            String argsStr = argsBuilder.toString().replace("\n", " ");
            boolean hasBigArgs = argsStr.length() > 100 || (args != null && args.values().stream().anyMatch(v -> v instanceof String && ((String) v).contains("\n")));

            if (agentProps.getGeneral().isCliPrintSimplified() && isTodo == false) {
                // --- 简化风格：单行摘要模式 ---
                String content = toolCallEndEvent.getText() == null ? "" : toolCallEndEvent.getText().trim();
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
                terminal.writer().println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + shortArgs + " (" + summary + ")" + RESET);
                terminal.flush();

            } else {
                // --- 全量风格 ---
                // 1. 打印指令行
                terminal.writer().println();
                if (TodoTalent.TOOL_TODOWRITE.equals(toolCallEndEvent.getToolName())) {
                    //优化 todowrite 打印
                    argsStr = "\n" + ((String) args.get("todos")).trim();
                    terminal.writer().println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + argsStr + RESET);
                } else {

                    if (!hasBigArgs) {
                        // 短参数直接跟在后面
                        terminal.writer().println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET + " " + DIM + argsStr + RESET);
                    } else {
                        // 大参数块，指令名独占一行，参数作为缩进内容打印（类似 write_file 的 content 部分）
                        terminal.writer().println(PURPLE + "❯ " + RESET + BOLD + fullToolName + RESET);
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
                }

                // 2. 处理工具返回的结果内容 (getContent)
                if (Assert.isNotEmpty(toolCallEndEvent.getText())) {
                    // 在参数和结果之间如果内容较多，可以加个小分隔，或者直接缩进打印
                    String indentedContent = "  " + toolCallEndEvent.getText().trim().replace("\n", "\n  ");
                    terminal.writer().println(DIM + indentedContent + RESET);
                }

                terminal.writer().println(DIM + "  (End of output)" + RESET);
                terminal.flush();
            }

            // 3. 接下来 AI 可能会针对这个结果进行分析 (Reasoning)，设置首行缩进标记
            isFirstReasonDeltaChunk.set(true);
        }
    }

    private String clearThink(String chunk) {
        if (chunk == null) {
            return null;
        }
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }


    protected void printWelcome(AgentSession session) {
        final String modelName;

        if (engine.getModels().isEmpty()) {
            modelName = "no model";
        } else {
            if (session == null) {
                modelName = engine.getMainModel().getNameOrModel();
            } else {
                String modelSelected = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
                modelName = engine.getModelOrDef(modelSelected).getNameOrModel();
            }
        }

        String path = new File(engine.getWorkspace()).getAbsolutePath();
        // 连带版本号，紧凑排列
        terminal.writer().println(BOLD + "SolonCode" + RESET + DIM + " " + AgentFlags.getVersion() + " PID-" + Utils.pid() + " Model:" + modelName + RESET);
        terminal.writer().println(DIM + path + RESET);
        terminal.writer().println(DIM + "Tips: " +
                RESET + "(esc)" + DIM + " interrupt | " +
                RESET + "/(tab)" + DIM + " command | " +
                RESET + "$(tab)" + DIM + " skill | " +
                RESET + "@(tab)" + DIM + " agent" + RESET);

        terminal.flush();
    }


    public void printWelcome(String text) {
        final String modelName;

        if (engine.getModels().isEmpty()) {
            modelName = "no model";
        } else {
            modelName = engine.getMainModel().getNameOrModel();
        }

        String path = new File(engine.getWorkspace()).getAbsolutePath();

        System.out.println("SolonCode" + " " + AgentFlags.getVersion() + " PID-" + Utils.pid() + " Model:" + modelName);
        System.out.println(path);
        System.out.println(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm"));
        System.out.println(text);
        System.out.flush();
    }
}