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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.CommandContextFactory;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.HitlCard;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.ParsedCommand;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.command.CliCommandContext;
import org.noear.solon.ai.harness.session.goal.GoalExtension;
import org.noear.solon.ai.harness.session.goal.GoalTalent;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.ai.harness.runtime.RoundResult;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.ai.harness.session.queue.DrainStatus;
import org.noear.solon.ai.harness.session.queue.QueueExtension;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.DateUtil;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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


    public CliShell(HarnessEngine engine, AgentSettings agentProps, LoopScheduler loopScheduler) {
        this.engine = engine;
        this.agentProps = agentProps;
        this.loopScheduler = loopScheduler;

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
            handleInput(session, input, null, true);
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

        // loop 执行通道：只在 LoopScheduler 自己的通道上注册（与 goal 无共享）
        if (loopScheduler != null) {
            // 注入屏障：终端所有权（不是会话状态）。
            // 放行条件 = 主线程正阻塞于 readLine（主线程空闲、只等着输入）。
            // 反之主线程在跑 /help、/model 选择器等非流命令时会话不忙，但终端正被占用，
            // 此时注入会与主线程并发写同一个 terminal，因此不放行。
            // 注：这与改造前 busyChecker 的 reader==null || !reader.isReading() 完全等价，
            // 属于既有行为的搬迁，不是新增屏障。
            loopScheduler.setInjectionGate(() -> reader == null || reader.isReading());

            // 任务执行器：在调度线程执行 agent 回合
            // 注意：它并非运行在主线程 —— 此刻主线程通常仍阻塞于 readLine，两者共用同一 reader，
            // 因此这里不消费队列（队列消费只归属主循环）
            // 子代理归属是会话属性（AutomationManager 已写入 CTX_AGENT_SELECTED），无需在此拼接 @ 前缀
            loopScheduler.addExecutor((sessionId, prompt) -> {
                if (SESSION_ID_CLI.equals(sessionId) == false) {
                    return null;
                }

                // 直接返回 ReAct 完成时的权威全文，由 loop 记录 lastResult
                return safeChatInput(session, prompt);
            });
        }

        // goal 执行通道：只在 GoalEngine 自己的通道上注册（与 loop 无共享）。
        // 与 loop 同口径（主线程阻塞于 readLine 时在调度线程代跑一轮、结束后 raise(INT) 让提示符重现），
        // 区别是必须返回 RoundResult：null 表示「本入口不认领」，失败必须显式携带文本。
        GoalExtension goal = engine.getExtension(GoalExtension.class).orElse(null);
        if (goal != null && goal.getGoalEngine() != null) {
            goal.getGoalEngine().addTaskExecutor((sessionId, prompt) -> {
                if (SESSION_ID_CLI.equals(sessionId) == false) {
                    return null;
                }
                return runInjectedRound(session, prompt);
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

                handleInput(session, input, null, true);
            } catch (Throwable e) {
                LOG.warn(e.getMessage(), e);
                terminal.writer().println("\n" + RED + "! Error: " + RESET + e.getMessage());
            }
        }
    }

    private String safeChatInput(AgentSession session, String prompt) {
        if (reader != null && reader.isReading()) {
            try {
                RoundResult result = handleInput(session, prompt, null, false);
                return result == null ? null : result.getFinalResult();
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
     * 在调度线程代跑一轮注入回合（goal 用），返回本轮客观事实。
     *
     * <p>前置条件是主线程阻塞于 {@code readLine}（终端空闲）。不满足时不认领（返回 null），
     * 让 GoalEngine 按「无执行者认领」处理——绝不伪造成功轮。</p>
     */
    private RoundResult runInjectedRound(AgentSession session, String prompt) {
        if (reader == null || reader.isReading() == false) {
            return null;
        }

        try {
            RoundResult result = handleInput(session, prompt, null, false);
            // 无任务可执行（未装配出请求）时返回 null，绝不伪造一个「文本为空但已完成」的轮次：
            // 那会被上层记成 ok（目标在跑、实际什么都没做），并清零熔断计数。
            return result;
        } catch (Throwable e) {
            LOG.error("Goal round execution failed: {}", e.getMessage(), e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return RoundResult.ofText("error: " + message);
        } finally {
            // 后台注入轮结束后打断 readLine，让提示符重新出现
            terminal.raise(Terminal.Signal.INT);
        }
    }

    /**
     * 输入受理唯一入口：用户手输与已受理文本共用一套装配与处置逻辑。
     * <p>
     * 命令识别、执行、子代理解析、请求装配全部由引擎完成；宿主只负责「回执怎么渲染」
     * 与「流怎么消费」。两类输入的差异全部收敛为参数：
     * <ul>
     *   <li>{@code userTurn=true}（用户手输 / 单次调用）—— 识别命令、具备终端能力、回合结束后消费队列；</li>
     *   <li>{@code userTurn=false}（队列项 / loop 注入）—— 不识别命令（即使以命令前缀开头）、
     *       不消费队列（队列消费只归属主线程，避免调度线程排水与 readLine 争用同一 reader）。</li>
     * </ul>
     * 注意：{@code commandAware} 与「回合结束后排水」必须同源 —— 两者都严格等于 {@code userTurn}，
     * 不可拆成两个独立布尔，否则会出现「手输不排水」或「注入回合识别命令」的错配。
     *
     * @param model 会话级模型选择（仅已受理文本的队列项携带；手输传 null）
     */
    private RoundResult handleInput(AgentSession session, String text, String model, boolean userTurn) throws Exception {
        HarnessInput harnessInput = HarnessInput.of(session, text)
                .origin(CLI_ORIGIN)
                .commandAware(userTurn)
                .selectedModel(model);

        if (userTurn) {
            //只在识别命令时装配：这两项全仓仅 dispatchCommand 读取，不识别命令时传了也是死配置
            harnessInput.terminal(true)
                    .commandContextFactory(cliCommandFactory());
        }

        return dispatchOutcome(session, engine.handle(harnessInput), userTurn);
    }

    /**
     * 消费一次处置结果（手输、队列项、注入回合共用）
     *
     * @return 任务执行时的本轮客观事实；无任务时为 null
     */
    private RoundResult dispatchOutcome(AgentSession session, InputOutcome outcome, boolean allowDrain) throws Exception {
        switch (outcome.getKind()) {
            case TASK:
                // 有任务就执行（命令触发与用户手输同一分支）：命令回执已由命令即时写入终端
                RoundResult result = performAgentTask(session, outcome);
                //只有用户手输回合消费队列（与改造前的既有行为一致）
                if (allowDrain) {
                    drainCliQueue(session);
                }
                return result;
            case REPLY:
                // 只有回执：命令已执行或在本渠道不可用（CLI 侧不会出现），保持原行为
                if (outcome.hasReply()) {
                    terminal.writer().println(outcome.getCommandOutput());
                    terminal.flush();
                }
                // CLI 与 Web/IM 共用会话队列（QueueHelper）；命令完成后由 CLI 自身消费空闲队列
                //注意顺序：先 drain 再 printWelcome（与改造前的既有行为一致）
                if (allowDrain) {
                    drainCliQueue(session);
                }
                if ("clear".equals(outcome.getCommandName())) {
                    printWelcome(session);
                }
                return null;
            case HITL:
                // 决策已受理但未续流（两种情形：未传 resume；批内仍有未决卡；决策未命中挂起任务）
                // CLI 正常审批路径不经此处：handleHitlRound 逐张提问直到拿到 TASK（见该方法）
                // 仅当残留快照全已决策却拿不到请求时才会到这里，此时无可展示的卡，直接收尾
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
            QueueExtension queue = engine.getExtension(QueueExtension.class).get();
            if (queue == null) {
                return;
            }

            // 同步语义：回调返回即任务跑完，就地 acknowledge 收口；启动异常由内核自动回滚到队首。
            // ack 失败（期间被 Stop）必须拖异常：让它走 START_FAILED 分支由内核回滚，
            // 静默吞掉会停不下来地把后续任务也跑完。
            while (queue.claimNext(session, claim -> {
                handleInput(session, claim.getItem().getText(), claim.getItem().getModel(), false);
                if (!claim.acknowledge()) {
                    throw new IllegalStateException("queue ack failed: " + claim.getItem().getId());
                }
            }) == DrainStatus.STARTED) {
                // claim/poll/ack/requeue 由内核负责。
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
    private RoundResult performAgentTask(AgentSession session, InputOutcome outcome) throws Exception {
        terminal.writer().println("\n" + BOLD + "Assistant" + RESET + DIM + " " + getTimeNow() + RESET);

        final String agentName = outcome.getAgentName();
        final String modelName = outcome.getModelName();

        final AtomicBoolean isFirstConversation = new AtomicBoolean(true);
        // ReAct 完成时的权威全文，多轮 HITL 时以最后一轮为准。
        final AtomicReference<String> finalAnswer = new AtomicReference<>();
        // 本轮客观事实：在流回调里就地累加，随返回值上交（不再写会话 attrs）
        final AtomicReference<Long> tokensUsed = new AtomicReference<>(0L);
        final AtomicBoolean hasToolCalls = new AtomicBoolean(false);

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
                return buildRoundResult(hasToolCalls, tokensUsed, finalAnswer);
            }

            // 取消目标由 ReActRequest 自行登记到 SessionRuns，本类不持有句柄；
            // 订阅失败会走 doOnError，无需另设「是否开流」的判断
            request.stream()
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
                            ToolCallEndEvent toolEnd = (ToolCallEndEvent) chunk;
                            if (toolEnd.getError() == null
                                    && Assert.isNotEmpty(toolEnd.getToolName())
                                    && GoalTalent.isGoalTool(toolEnd.getToolName()) == false
                                    && MemoryTalent.isMemoryTool(toolEnd.getToolName()) == false
                                    && TaskTalent.TOOL_MULTITASK.equals(toolEnd.getToolName()) == false
                                    && TaskTalent.TOOL_TASK.equals(toolEnd.getToolName()) == false) {
                                hasToolCalls.set(true);
                            }
                            onToolCallEndEvent(toolEnd, isFirstReasonDeltaChunk);
                        } else if (chunk instanceof RunEndEvent) {
                            // ReActChunk 为全量，ReAct 完成任务时的最后答复
                            RunEndEvent runEnd = (RunEndEvent) chunk;
                            if (runEnd.getTrace() != null && runEnd.getTrace().getMetrics() != null) {
                                tokensUsed.set(runEnd.getTrace().getMetrics().getTotalTokens());
                            }
                            String answer = onRunEndEvent(session, runEnd);
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

            waitForTask(latch, session, isInterrupted);

            if (isInterrupted.get()) {
                terminal.writer().println(DIM + "[Task interrupted]" + RESET);
                terminal.flush();
                // 取消消息与快照已由 engine.interrupt 幂等补齐，这里只打印用户可见提示
                LOG.info("用户已取消任务.");
                return buildRoundResult(hasToolCalls, tokensUsed, finalAnswer);
            }

            // HITL 处理 (授权交互)：决策写入与续流装配统一走引擎入口
            // 判据用 next() 而非 hasPendingHitl()：残留决策（整批已决策但续流未发生）
            // 也必须进 handleHitlRound 透传给引擎，否则永远等不到续流
            if (HitlCard.next(session) != null) {
                pendingOutcome = handleHitlRound(session, agentName, modelName);
                if (pendingOutcome == null) {
                    //审批未能转化为续流：给出可见说明，不让用户无交代地回到提示符
                    terminal.writer().println(DIM + "[审批未生效：无挂起任务或续流未生成请求]" + RESET);
                    terminal.flush();
                    return buildRoundResult(hasToolCalls, tokensUsed, finalAnswer);
                }
                continue;
            }

            //既未中断、也无挂起审批：本轮流已终结，收尾
            terminal.writer().println();
            terminal.flush();
            return buildRoundResult(hasToolCalls, tokensUsed, finalAnswer);
        }
    }

    /** 把一轮的客观事实冻结成返回值（无正文时返回 null，表示本入口未产出结果）。 */
    private RoundResult buildRoundResult(AtomicBoolean hasToolCalls, AtomicReference<Long> tokensUsed,
                                         AtomicReference<String> finalAnswer) {
        String text = finalAnswer.get();
        if (Assert.isEmpty(text)) {
            return null;
        }
        return RoundResult.of(hasToolCalls.get(), tokensUsed.get(), text);
    }

    private void waitForTask(CountDownLatch latch,
                             AgentSession session, AtomicBoolean isInterrupted) throws Exception {
        Attributes originalAttributes = terminal.getAttributes();
        try {
            terminal.enterRawMode();

            while (latch.getCount() > 0) {
                int c = terminal.reader().read(50);
                if (c > 0) {
                    if (c == 27) { //|| c == '\r' || c == '\n'
                        // 停流 + 补写取消消息与快照统一走引擎入口（幂等）
                        engine.interrupt(session);
                        isInterrupted.set(true);
                        latch.countDown();
                        break;
                    }
                }

                // 与主循环同判据：残留决策也需在此唤醒等待（HitlCard.next 含回退首项语义）
                if (HitlCard.next(session) != null) {
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
     * 处理一轮 HITL 审批：读决策 → 交引擎写入 → 需要时逐卡继续
     * <p>
     * 决策与续流合并为一次 {@code handle}（{@code .hitl(...).resume(true)}）：
     * 既走唯一入口（不绕过 {@link HarnessEngine#handle}），又能享受引擎的
     * callId 精确定位与工具返回改写（{@code hitlOutput}）。
     * <p>
     * 批挂起（多个敏感工具同一批）时引擎不会在未齐备时续流，而是返回
     * {@link InputOutcome.Kind#HITL} + 剩余未决数，故此处循环逐张提问，
     * 直到本批齐备拿到可执行的续流请求。
     *
     * @return 下一轮要执行的处置结果（必有请求）；无卡可问或决策失效时返回 null
     */
    private InputOutcome handleHitlRound(AgentSession session, String agentName, String modelName) throws Exception {
        while (true) {
            HitlCard card = HitlCard.next(session);

            if (card == null) {
                return null;
            }

            // 上一轮决策仍有残留（CLI 会话 ID 固定为 cli，不会与 Web/IM 会话共用）：不再提问，按原决策的三态透传（skip 不能升格为 approve）
            HITLAction action;

            if (card.isDecided()) {
                action = card.getDecision();
            } else {
                terminal.writer().println("\n" + BOLD + YELLOW + "Permission Required" + RESET);
                if (card.isBash()) {
                    terminal.writer().println(DIM + "Command: " + RESET + card.getCommand());
                }

                String line;

                try {
                    line = reader.readLine(BOLD + GREEN + "Approve? (y/n) " + RESET);
                } catch (EndOfFileException e) {
                    return null;
                }

                //EOF / 流关闭时 readLine 可能返回 null：不写决策直接退出，交由上层提示（不能 NPE）
                if (line == null) {
                    return null;
                }

                String choice = line.trim().toLowerCase();
                action = ("y".equals(choice) || "yes".equals(choice)) ? HITLAction.APPROVE : HITLAction.REJECT;

                if (action == HITLAction.REJECT) {
                    terminal.writer().println(DIM + "Action rejected." + RESET);
                }
            }

            // 按 callId 指定目标：避免并行挂起同名工具时误命中其它任务
            InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                    .hitl(action, card.getCallId())
                    .resume(true)
                    .selectedModel(modelName)
                    .selectedAgent(agentName));

            if (outcome.getKind() == InputOutcome.Kind.TASK) {
                return outcome;
            }

            if (outcome.isHitlApplied() == Boolean.FALSE) {
                //决策未命中挂起任务（理论上不会，card 刚查出来）：不续流，交给上层提示
                return null;
            }

            //本批仍有未决卡：继续问下一张（与迁移前「拦截器再次挂起」的观察结果一致）
        }
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

        // token 不再写会话 attrs：CLI 的 loop 回合只需最终文本，
        // goal 回合则由执行器把 RoundResult 随返回值上交（不再有跨模块旁路键）。
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

        // token 不在此记录：见 onReasonEndEvent 注释

        // 返回 ReAct 完成时的权威全量答复，由调用方用于 loop 判定。
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