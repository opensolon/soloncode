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
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.CommandContextFactory;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.codecli.command.CliCommandContext;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionIdOwnership;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.DateUtil;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.noear.solon.codecli.entry.cli.CliHostSink.*;

/**
 * Code CLI 终端（L0 宿主）
 *
 * <p>编排（受理 → 开流 → 终态 → 续发积压、两段式中断、审批续流、goal / loop 后台轮次）全部交给
 * {@link HarnessHost}，本类只保留终端独有的三件事：</p>
 * <ul>
 *   <li>读输入：{@code readLine} 提交给 {@code host.submit}（具备终端能力、命令上下文直接读写终端）；</li>
 *   <li>前台等待：有轮次在跑时占住终端，ESC 停止（{@code host.interrupt}，即 INTERRUPT_ALL，连带清掉排队）；</li>
 *   <li>审批：轮次结束后逐张询问 y/n，交给 {@code host.decide}（齐备即续流）。</li>
 * </ul>
 * <p>呈现在 {@link CliHostSink}。</p>
 */
@Preview("3.9.4")
public class CliShell implements Runnable {
    /** CLI 渠道来源身份 */
    private static final InputOrigin CLI_ORIGIN = InputOrigin.channel("CLI");

    private final static Logger LOG = LoggerFactory.getLogger(CliShell.class);

    /** 单租户：CLI 只服务默认工作区这一个引擎 */
    private final static String TENANT = "cli";
    private final static String SESSION_ID_CLI = "cli";

    /** 判定「全部轮次已结束」时的静默窗口：上一轮结束到续发的下一轮开出之间有极短空档 */
    private final static long SETTLE_QUIET_MS = 150;

    private Terminal terminal;
    private LineReader reader;
    private final HarnessEngine engine;
    private final AgentSettings agentProps;
    private CliHostSink sink;
    private HarnessHost host;

    public CliShell(HarnessEngine engine, AgentSettings agentProps) {
        this.engine = engine;
        this.agentProps = agentProps;

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
     * 骨架懒建：本类在 serve 模式下也会被构造（只用 {@link #printWelcome(String)}），不能提前接管引擎上的后台轮次。
     */
    private HarnessHost host() {
        if (host == null) {
            sink = new CliHostSink(terminal, reader, engine, agentProps, this::printWelcome);
            host = HarnessHost.builder()
                    .engines(tenant -> engine)
                    .sink(sink)
                    // 只接管 CLI 会话的 goal / loop 后台轮次与积压（Web / 桌面会话由各自入口认领）
                    .programRuns(SessionIdOwnership::isCli)
                    .build();
        }
        return host;
    }

    /**
     * 预备开始：开通会话（首次取引擎时骨架登记 CLI 的后台执行器、恢复 CLI 会话的 goal 与积压）
     */
    private AgentSession prepare(String sessionId) {
        // Windows 下将控制台切换为 UTF-8 代码页，避免中文输入乱码
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                Process process = new ProcessBuilder("cmd", "/c", "chcp", "65001").start();
                // 读取并丢弃输出，避免显示到控制台
                try (InputStream is = process.getInputStream()) {
                    while (is.read() != -1) {
                    }
                }
                process.waitFor();
            } catch (Exception ignored) {
            }
        }

        printWelcome(engine.getSession(sessionId));
        AgentSession session = host().openSession(TENANT, sessionId, null);
        // 恢复出的积压可能已经开跑：先在前台跑完
        awaitRuns(session);
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
            submit(session, input);
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

        while (true) {
            try {
                // 回到提示符前：把还在跑的轮次（续发的排队消息、后台轮次）与待审批卡处理完
                awaitRuns(session);

                String input;

                try {
                    terminal.writer().println();
                    terminal.writer().print(BOLD + CYAN + "User" + RESET);
                    terminal.writer().println();
                    terminal.flush();

                    input = reader.readLine(CYAN + "❯ " + RESET + DIM + workspaceShort() + RESET + " ").trim();
                } catch (UserInterruptException e) {
                    // Ctrl+C，或后台轮次结束后打断 readLine（由循环顶部接着处理）
                    continue;
                } catch (EndOfFileException e) {
                    terminal.writer().println("\nBye!");
                    terminal.flush();
                    break; // 直接跳出主循环，优雅退出
                }

                if (Assert.isEmpty(input)) {
                    continue;
                }

                submit(session, input);
            } catch (Throwable e) {
                LOG.warn(e.getMessage(), e);
                terminal.writer().println("\n" + RED + "! Error: " + RESET + e.getMessage());
            }
        }
    }

    /**
     * 提交一条用户手输（含命令），并在前台跑完它开出的轮次。
     *
     * <p>结局的呈现（回执、排队、拒收、审批失效）都在 {@link CliHostSink}；这里只决定终端什么时候交还给提示符。</p>
     */
    private void submit(AgentSession session, String text) {
        host().submit(TENANT, session.getSessionId(), HarnessInput.of(text)
                .origin(CLI_ORIGIN)
                // 命令行宿主：/help 不裁掉 terminalOnly 命令，命令上下文直接读写终端（分页、选择器）
                .terminal(true)
                .commandContextFactory(cliCommandFactory()));
        awaitRuns(session);
    }

    /**
     * 命令行上下文工厂：保留 Terminal/Reader 以支持 /help 交互与 /model 选择器
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

    /**
     * 前台等待：直到没有在跑的轮次、也没有待审批卡。
     *
     * <p>等待期间 ESC 停止（{@code host.interrupt}：清掉排队、写取消消息、停流）。轮次结束后若有待审批卡，
     * 逐张询问并交给 {@code host.decide}，续流开出的轮次接着等。</p>
     */
    private void awaitRuns(AgentSession session) {
        while (true) {
            waitUntilQuiet(session);

            if (HitlCard.next(session) == null) {
                return;
            }
            if (handleHitl(session) == false) {
                return;
            }
        }
    }

    /** 占住终端直到没有在跑的轮次（静默窗口内也没有新轮次开出） */
    private void waitUntilQuiet(AgentSession session) {
        if (isRunning(session) == false) {
            return;
        }

        Attributes originalAttributes = null;
        try {
            originalAttributes = terminal.getAttributes();
            terminal.enterRawMode();

            long quietSince = 0;
            while (true) {
                int c = terminal.reader().read(50);
                if (c == 27) {
                    // 停流 + 补写取消消息与快照统一走骨架（幂等）；终态由 sink 打印 [Task interrupted]
                    host().interrupt(TENANT, session.getSessionId());
                }

                if (isRunning(session)) {
                    quietSince = 0;
                } else if (quietSince == 0) {
                    quietSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - quietSince >= SETTLE_QUIET_MS) {
                    return;
                }
            }
        } catch (Throwable e) {
            LOG.warn(e.getMessage(), e);
        } finally {
            if (originalAttributes != null) {
                terminal.setAttributes(originalAttributes);
            }
        }
    }

    private boolean isRunning(AgentSession session) {
        if (sink != null && sink.activeRuns() > 0) {
            return true;
        }
        SessionStatus st = engine.status(session);
        return st.isBusy() || st.isAdmitting();
    }

    /**
     * 处理一张审批卡：读决策 → 交骨架写入并尝试续流。
     *
     * @return true = 已提交决策（可能开出续流、也可能还有下一张卡），调用方继续等待；false = 结束审批
     */
    private boolean handleHitl(AgentSession session) {
        HitlCard card = HitlCard.next(session);
        if (card == null) {
            return false;
        }

        // 上一轮决策仍有残留：不再提问，按原决策的三态透传（skip 不能升格为 approve）
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
            } catch (EndOfFileException | UserInterruptException e) {
                return false;
            }
            //EOF / 流关闭时 readLine 可能返回 null：不写决策直接退出
            if (line == null) {
                return false;
            }

            String choice = line.trim().toLowerCase();
            action = ("y".equals(choice) || "yes".equals(choice)) ? HITLAction.APPROVE : HITLAction.REJECT;
            if (action == HITLAction.REJECT) {
                terminal.writer().println(DIM + "Action rejected." + RESET);
            }
        }

        // 按 callId 指定目标：避免并行挂起同名工具时误命中其它任务
        SubmitResult r = host().decide(TENANT, session.getSessionId(), card.getCallId(), action, null, CLI_ORIGIN);
        switch (r.getDisposition()) {
            case STARTED:
                return true;
            case HITL:
                // 本批仍有未决卡就继续问；卡已失效（sink 已提示）则结束
                return r.getHitlCards().isEmpty() == false;
            default:
                return false;
        }
    }

    protected void printWelcome(AgentSession session) {
        final String modelName;

        if (engine.getModels().isEmpty()) {
            modelName = "no model";
        } else {
            if (session == null) {
                modelName = engine.getMainModel().getNameOrModel();
            } else {
                String modelSelected = engine.getSessionModel(session);
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


    /**
     * 输入提示处显示的工作目录名，只取最后一段，避免提示符过长。
     *
     * <p>启动欢迎信息里的路径只在开头出现一次，对话过程中看不到当前在哪个项目。</p>
     */
    private String workspaceShort() {
        String path = engine.getWorkspace();
        if (Assert.isEmpty(path)) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String name = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        return Assert.isEmpty(name) ? path : name;
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