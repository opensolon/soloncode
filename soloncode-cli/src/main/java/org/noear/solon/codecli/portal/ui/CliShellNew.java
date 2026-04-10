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
package org.noear.solon.codecli.portal.ui;

import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.ConfigLoader;
import org.noear.solon.codecli.GlobalConfigWriter;
import org.noear.solon.codecli.SessionManager;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.portal.ui.bottom.BottomInputController;
import org.noear.solon.codecli.portal.ui.bottom.panel.BottomListPanel;
import org.noear.solon.codecli.portal.ui.theme.PortalTheme;
import org.noear.solon.codecli.portal.ui.theme.PortalThemes;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Code CLI 终端 (printAbove 架构 — 输入始终可用)
 */
@Preview("3.9.4")
public class CliShellNew implements Runnable {

    private final static Logger LOG = LoggerFactory.getLogger(CliShellNew.class);
    private static final int MAX_PENDING_INPUTS = 3;
    private static final int MAX_TOOL_PRIMARY_LINES = 3;
    private static final int MAX_TOOL_RESULT_LINES = 5;
    private static final String TOOL_BODY_PREFIX = "     ";
    // ANSI 颜色常量 - 对齐 Go TUI 主题
    private final static String BOLD = "\033[1m",
        DIM = "\033[2m",
        RESET = "\033[0m";
    // 图标常量 - 对齐 Go TUI
    private final static String ICON_ASSISTANT = "\u2726", // ✦
        ICON_USER = "\u25C9", // ◉
        ICON_PROMPT = "\u276F", // ❯
        ICON_TOOL = "\u25C8", // ◈
        ICON_CROSS = "\u2718", // ✘
        ICON_WARN = "\u26A0", // ⚠
        ICON_THINKING = "\u25CC", // ◌
        ICON_CHECK = "\u2714"; // ✔
    private final static DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final HarnessEngine kernel;
    private final AgentProperties agentProps;
    private final CommandRegistry commandRegistry;
    private final SessionManager sessionManager = new SessionManager();
    // ── 共享状态 ──
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean taskRunning = new AtomicBoolean(false);
    // ── 行缓冲 (printAbove 逐行输出) ──
    private final StringBuilder lineBuffer = new StringBuilder();
    private final StringBuilder thinkingTranscript = new StringBuilder();
    private final StringBuilder assistantTranscript = new StringBuilder();
    private final List<String> pendingInputs = new ArrayList<>();
    private final Object pendingInputsLock = new Object();
    private Terminal terminal;
    private LineReader reader;
    private StatusBar statusBar;
    private BottomInputController bottomInputController;
    private PortalScreenRenderer screenRenderer;
    private volatile ReentrantLock terminalLock = new ReentrantLock();
    private volatile boolean thinkingStarted = false;
    private volatile boolean thinkingLineStart = true;
    private volatile boolean lastContentLineBlank = true;
    // ── 流式 Markdown 渲染器 ──
    private final MarkdownRenderer mdRenderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
        @Override
        public void append(String styled) {
            appendToLineBuffer(styled);
        }

        @Override
        public void flushLine() {
            // Markdown 渲染器要求空行也输出（段落间距）
            synchronized (lineBuffer) {
                printAboveLine(lineBuffer.toString() + RESET);
                lineBuffer.setLength(0);
            }
        }
    }, new MarkdownRenderer.WidthProvider() {
        @Override
        public int getWidth() {
            return terminal == null ? 80 : terminal.getWidth();
        }
    });
    private volatile long thinkingStartedAt = 0L;
    private volatile long assistantStartedAt = 0L;
    private PortalTheme theme = PortalThemes.defaultTheme();
    private String ACCENT = theme.accent().ansiFg();
    private String ACCENT_BOLD = theme.accentStrong().ansiBoldFg();
    private String SOFT = theme.textSoft().ansiFg();
    private String MUTED = theme.textMuted().ansiFg();
    private String ERROR_COLOR = theme.error().ansiFg();
    private String WARN = theme.warning().ansiFg();
    private String SUCCESS_COLOR = theme.success().ansiFg();
    private String TEXT = theme.textPrimary().ansiFg();
    private String USER_TITLE = theme.userTitle().ansiBoldFg();
    private String ASSISTANT_TITLE = theme.assistantTitle().ansiBoldFg();
    private String THINKING_TITLE = theme.thinkingTitle().ansiFg();
    private String THINKING_BORDER = theme.thinkingBorder().ansiFg();
    private String TOOL_TITLE = theme.toolTitle().ansiBoldFg();
    private String TOOL_META = theme.toolMeta().ansiDimFg();
    private String TOOL_VALUE = theme.toolValue().ansiFg();
    private String TOOL_RESULT = theme.toolResult().ansiFg();
    private String TOOL_PREVIEW = theme.toolPreview().ansiFg();
    private String TIME_COLOR = DIM;

    // ═══════════════════════════════════════════════════════════
    // 内置命令注册
    // ═══════════════════════════════════════════════════════════
    private volatile AgentSession currentSession;

    // ═══════════════════════════════════════════════════════════
    // 主循环 — readLine() 始终活跃
    // ═══════════════════════════════════════════════════════════
    private volatile Disposable currentDisposable;
    private volatile boolean reasonAtLineStart = true;

    public CliShellNew(HarnessEngine kernel) {
        this(kernel, resolveAgentProps(kernel));
    }

    public CliShellNew(HarnessEngine kernel, AgentProperties agentProps) {
        this.kernel = kernel;
        this.agentProps = agentProps;
        this.commandRegistry = new CommandRegistry();
        registerBuiltinCommands();

        try {
            this.terminal = TerminalBuilder.builder()
                .jna(true).jansi(true).system(true)
                .encoding(StandardCharsets.UTF_8)
                .signalHandler(Terminal.SignalHandler.SIG_IGN) // 禁止默认信号处理
                .build();

            // 禁用 ISIG，让 Ctrl+C 作为普通按键传递而不是信号
            Attributes attrs = terminal.getAttributes();
            attrs.setLocalFlag(Attributes.LocalFlag.ISIG, false);
            terminal.setAttributes(attrs);

            // 窗口 Resize 信号处理
            terminal.handle(Terminal.Signal.WINCH, signal -> {
                if (screenRenderer != null) {
                    screenRenderer.renderNow();
                }
            });

            this.bottomInputController = new BottomInputController(
                terminal,
                commandRegistry,
                java.nio.file.Paths.get(agentProps.getWorkspace()),
                new BottomInputController.Listener() {
                    @Override
                    public boolean isTaskRunning() {
                        return taskRunning.get();
                    }

                    @Override
                    public void cancelRunningTask() {
                        cancelRequested.set(true);
                        Disposable d = currentDisposable;
                        if (d != null && !d.isDisposed()) {
                            d.dispose();
                        }
                    }

                    @Override
                    public int clearPendingInputs() {
                        return clearPendingInputsInternal();
                    }

                    @Override
                    public List<String> getPendingInputs() {
                        return getPendingInputsSnapshot();
                    }

                    @Override
                    public int enqueuePendingInput(String text) {
                        return enqueuePendingInputInternal(text);
                    }

                    @Override
                    public int getPendingInputLimit() {
                        return MAX_PENDING_INPUTS;
                    }

                    @Override
                    public boolean isHitlActive() {
                        return HITL.isHitl(currentSession);
                    }

                    @Override
                    public void handleHitlInput(String text) {
                        CliShellNew.this.handleHITLInput(text);
                    }

                    @Override
                    public void clearScreen() {
                        if (screenRenderer != null) {
                            screenRenderer.clearContent();
                        }
                    }

                    @Override
                    public void showTerminalCursor() {
                        if (screenRenderer != null) {
                            screenRenderer.showTerminalCursor();
                        }
                    }

                    @Override
                    public void updateFooter(List<AttributedString> popupLines, AttributedString inputLine,
                                             org.jline.terminal.Cursor cursor) {
                        if (statusBar != null) {
                            statusBar.updateFooter(popupLines, inputLine, cursor);
                        }
                    }
                });
            applyTheme(resolveConfiguredTheme());
            this.reader = bottomInputController.createReader();

        } catch (Throwable e) {
            LOG.error("JLine initialization failed", e);
        }
    }

    private static AgentProperties resolveAgentProps(HarnessEngine kernel) {
        if (kernel.getProps() instanceof AgentProperties) {
            return (AgentProperties) kernel.getProps();
        }

        throw new IllegalArgumentException("CliShellNew requires AgentProperties");
    }

    private static String repeatChar(char c, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private void applyTheme(PortalTheme theme) {
        this.theme = theme == null ? PortalThemes.defaultTheme() : theme;
        this.ACCENT = this.theme.accent().ansiFg();
        this.ACCENT_BOLD = this.theme.accentStrong().ansiBoldFg();
        this.SOFT = this.theme.textSoft().ansiFg();
        this.MUTED = this.theme.textMuted().ansiFg();
        this.ERROR_COLOR = this.theme.error().ansiFg();
        this.WARN = this.theme.warning().ansiFg();
        this.SUCCESS_COLOR = this.theme.success().ansiFg();
        this.TEXT = this.theme.textPrimary().ansiFg();
        this.USER_TITLE = this.theme.userTitle().ansiBoldFg();
        this.ASSISTANT_TITLE = this.theme.assistantTitle().ansiBoldFg();
        this.THINKING_TITLE = this.theme.thinkingTitle().ansiFg();
        this.THINKING_BORDER = this.theme.thinkingBorder().ansiFg();
        this.TOOL_TITLE = this.theme.toolTitle().ansiBoldFg();
        this.TOOL_META = this.theme.toolMeta().ansiDimFg();
        this.TOOL_VALUE = this.theme.toolValue().ansiFg();
        this.TOOL_RESULT = this.theme.toolResult().ansiFg();
        this.TOOL_PREVIEW = this.theme.toolPreview().ansiFg();
        this.TIME_COLOR = DIM;
        mdRenderer.setTheme(this.theme);
        if (bottomInputController != null) {
            bottomInputController.setTheme(this.theme);
        }
        if (statusBar != null) {
            statusBar.setTheme(this.theme);
        }
    }

    private PortalTheme resolveConfiguredTheme() {
        String configured = agentProps.getUiTheme();
        PortalTheme resolved = PortalThemes.find(configured);
        if (resolved != null) {
            return resolved;
        }

        if (Assert.isNotEmpty(configured)) {
            LOG.warn("Unknown configured portal theme: {}", configured);
        }

        return PortalThemes.defaultTheme();
    }

    // ═══════════════════════════════════════════════════════════
    // AI 任务执行（完全异步）
    // ═══════════════════════════════════════════════════════════

    private void registerBuiltinCommands() {
        commandRegistry.register("/help", "显示帮助信息", ctx -> {
            printHelp();
        });

        commandRegistry.register("/exit", "退出程序", ctx -> {
            printAboveLine(DIM + "Exiting..." + RESET);
            System.exit(0);
        });

        commandRegistry.register("/init", "重新初始化代码索引", ctx -> {
            AgentSession session = ctx.getSession();
            //String result = kernel.init(session);
            //printAboveLine(DIM + result + RESET);
        });

        commandRegistry.register("/clear", "清空当前会话历史", ctx -> {
            AgentSession session = ctx.getSession();
            session.clear();
            sessionManager.clearPortalEvents(session.getSessionId(), agentProps.getWorkspace());
            if (screenRenderer != null) {
                screenRenderer.clearContent();
            }
            if (statusBar != null) {
                statusBar.draw();
            }
        });

        commandRegistry.register("/new", "开始新会话", ctx -> {
            // 新建临时 session，只在第一条消息时才持久化
            currentSession = kernel.getSession("_tmp_" + System.currentTimeMillis());
            //kernel.init(currentSession);
            if (screenRenderer != null) {
                screenRenderer.clearContent();
            }
            lastContentLineBlank = true;
            if (statusBar != null) {
                statusBar.setSessionId("(new)");
                statusBar.draw();
            }
            printAboveLine(DIM + "  New session started." + RESET);
        });

        commandRegistry.register("/resume", "恢复历史会话，支持 /resume last|list|<id|number>", ctx -> {
            List<SessionManager.SessionMeta> sessions = getRestorableSessionsForCurrentDir();
            String arg = ctx.getArg() == null ? "" : ctx.getArg().trim();

            if (!arg.isEmpty()) {
                if ("list".equalsIgnoreCase(arg) || "ls".equalsIgnoreCase(arg)) {
                    if (sessions.isEmpty()) {
                        printAboveLine(DIM + "  No sessions for this directory." + RESET);
                    } else {
                        printSessionList(sessions);
                    }
                    return;
                }

                if ("last".equalsIgnoreCase(arg) || "latest".equalsIgnoreCase(arg)) {
                    resumeLatestSession(sessions, true);
                    return;
                }

                SessionManager.SessionMeta target = resolveResumeTarget(arg, sessions);
                if (target != null) {
                    resumeSession(target, true);
                }
                return;
            }

            if (sessions.isEmpty()) {
                printAboveLine(DIM + "  No sessions for this directory." + RESET);
                return;
            }

            SessionManager.SessionMeta selected = bottomInputController.selectFromList(
                "/resume",
                buildSessionSelectionItems(sessions));
            if (selected != null) {
                resumeSession(selected, true);
            } else {
                printAboveLine(DIM + "  Cancelled." + RESET);
            }
        });

        commandRegistry.register("/model", "显示当前模型信息", ctx -> {
            String model = kernel.getMainModel().getModel();
            printAboveLine(DIM + "Model: " + RESET + BOLD + model + RESET);
        });

        commandRegistry.register("/theme", "选择主题，支持 /theme 或 /theme <name>", ctx -> {
            String arg = ctx.getArg() == null ? "" : ctx.getArg().trim();
            if (!arg.isEmpty()) {
                PortalTheme matched = PortalThemes.find(arg);
                if (matched == null) {
                    printAboveLine(ERROR_COLOR + "  Unknown theme: " + RESET + arg);
                    return;
                }
                persistThemeSelection(matched);
                return;
            }

            openThemePicker();
        });

        commandRegistry.register("/compact", "压缩当前会话上下文", ctx -> {
            printAboveLine(DIM + "  Compacting session context..." + RESET);
            // TODO: 实际调用 summarization 压缩上下文
            printAboveLine(DIM + "  Session context compacted." + RESET);
        });

        commandRegistry.register("/thinking", "切换思考内容显示", ctx -> {
            agentProps.setThinkPrinted(!agentProps.isThinkPrinted());
            String mode = agentProps.isThinkPrinted() ? "ON" : "OFF";
            printAboveLine(DIM + "  Thinking display: " + RESET + BOLD + mode + RESET);
        });

        commandRegistry.register("/details", "切换工具调用详情显示", ctx -> {
            agentProps.setCliPrintSimplified(!agentProps.isCliPrintSimplified());
            String mode = agentProps.isCliPrintSimplified() ? "simplified" : "detailed";
            printAboveLine(DIM + "  Tool details: " + RESET + BOLD + mode + RESET);
            if (statusBar != null) {
                statusBar.setCompactMode(agentProps.isCliPrintSimplified());
            }
        });

        commandRegistry.register("/statusbar", "配置状态栏显示内容", ctx -> {
            if (statusBar != null && !taskRunning.get()) {
                statusBar.showConfigUI();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    // HITL 授权（异步：后台线程显示提示，主线程 readLine 输入）
    // ═══════════════════════════════════════════════════════════

    @Override
    public void run() {
        // Windows 下将控制台切换为 UTF-8 代码页，避免中文输入乱码
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                new ProcessBuilder("cmd", "/c", "chcp", "65001")
                    .inheritIO().start().waitFor();
            } catch (Exception ignored) {
            }
        }

        printWelcome();
        currentSession = createStartupSession();
        //kernel.init(currentSession);

        while (true) {
            try {
                String input = bottomInputController.readInput();
                if (input == null) {
                    break;
                }

                if (Assert.isEmpty(input)) {
                    continue;
                }

                if (!isSystemCommand(currentSession, input)) {
                    // 延迟创建：第一条消息时才正式创建会话
                    ensureSessionCreated(input);

                    printUserInput(input);
                    startAgentTask(currentSession, input);
                }
            } catch (Throwable e) {
                printAboveLine("\n" + ERROR_COLOR + ICON_CROSS + " Error: " + RESET + e.getMessage());
            }
        }

        if (bottomInputController != null) {
            bottomInputController.close();
        }
    }
    

    private void startAgentTask(AgentSession session, String input) {
        taskRunning.set(true);
        cancelRequested.set(false);
        thinkingStarted = false;
        thinkingStartedAt = 0L;
        assistantStartedAt = 0L;
        resetThinkingTranscript();
        resetAssistantTranscript();

        // 状态栏：任务开始（taskStart 内部自动 draw）
        if (statusBar != null) {
            statusBar.incrementTurns();
            statusBar.taskStart();
        }

        final AtomicBoolean isFirstConversation = new AtomicBoolean(true);
        final AtomicBoolean isFirstReasonChunk = new AtomicBoolean(true);

        String workDir = String.valueOf(session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, agentProps.getWorkspace()));
        session.attrs().putIfAbsent(HarnessEngine.ATTR_CWD, workDir);

        currentDisposable = kernel.getMainAgent()
            .prompt(Prompt.of(input))
            .session(session)
            .options(o -> {
                o.toolContextPut(HarnessEngine.ATTR_CWD, workDir);
            })
            .stream()
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(chunk -> {
                if (cancelRequested.get()) {
                    return;
                }

                if (chunk instanceof ReasonChunk) {
                    onReasonChunk((ReasonChunk) chunk, isFirstReasonChunk, isFirstConversation);
                } else if (chunk instanceof ActionEndChunk) {
                    onActionEndChunk((ActionEndChunk) chunk, isFirstReasonChunk);
                } else if (chunk instanceof ReActChunk) {
                    onFinalChunk((ReActChunk) chunk, isFirstReasonChunk, isFirstConversation);
                }
            })
            .doOnError(e -> {
                printAboveLine(ERROR_COLOR + "  " + ICON_CROSS + " Error: " + RESET + e.getMessage());
            })
            .doFinally(signal -> {
                flushLineBuffer();

                boolean wasCancelled = cancelRequested.getAndSet(false);
                taskRunning.set(false);
                currentDisposable = null;

                if (wasCancelled) {
                    printAboveLine(WARN + "  [Task cancelled]" + RESET);
                    int discarded = clearPendingInputsInternal();
                    refreshBottomFooter();
                    if (discarded > 0) {
                        printAboveLine(DIM + "  (" + discarded + " 条待发送输入已丢弃)" + RESET);
                    }
                    session.addMessage(ChatMessage.ofAssistant("Task interrupted by user."));
                    // 状态栏：回到 idle
                    if (statusBar != null) {
                        statusBar.taskEnd(0);
                    }
                }

                // HITL 检查
                if (HITL.isHitl(session)) {
                    showHITLPrompt(session);
                    return;
                }

                // 待发送输入 → 渲染用户历史 + 合并为一条发送
                List<String> queuedInputs = drainPendingInputs();
                refreshBottomFooter();
                if (!queuedInputs.isEmpty() && !wasCancelled) {
                    // 显示每条待发送输入作为用户历史
                    for (String pi : queuedInputs) {
                        printUserInput(pi);
                    }
                    String merged = String.join("\n", queuedInputs);
                    startAgentTask(session, merged);
                }
            })
            .subscribe();
    }

    // ═══════════════════════════════════════════════════════════
    // 流式回调 — 全部通过行缓冲 + printAbove
    // ═══════════════════════════════════════════════════════════

    /**
     * 后台线程调用 — 通过 printAbove 显示 HITL 提示
     */
    private void showHITLPrompt(AgentSession session) {
        HITLTask task = HITL.getPendingTask(session);
        if (task == null) {
            return;
        }

        // 状态栏同步
        if (statusBar != null) {
            statusBar.updateStatus("awaiting approval");
        }

        printAboveLine("");
        printAboveLine(MUTED + "  " + repeatChar('\u2500', 20) + RESET);
        printAboveLine(WARN + "  " + ICON_WARN + " Permission Required" + RESET);
        if ("bash".equals(task.getToolName())) {
            printAboveLine(
                TOOL_META + "     Command: " + RESET + TOOL_VALUE + String.valueOf(task.getArgs().get("command")) +
                    RESET);
        } else {
            printAboveLine(TOOL_META + "     Tool: " + RESET + TOOL_VALUE + task.getToolName() + RESET);
        }
        printAboveLine("");
        printAboveLine("     " + SUCCESS_COLOR + ICON_CHECK + " allow" + RESET + MUTED + "    允许执行" + RESET);
        printAboveLine("     " + ERROR_COLOR + ICON_CROSS + " deny" + RESET + MUTED + "     拒绝执行" + RESET);
        printAboveLine(MUTED + "  " + repeatChar('\u2500', 20) + RESET);
        printAboveLine("");
    }

    /**
     * 主线程调用 — 处理用户在 readLine() 中输入的 HITL 选择
     */
    private void handleHITLInput(String input) {
        HITLTask task = HITL.getPendingTask(currentSession);
        if (task == null) {
            return;
        }

        String choice = input.trim().toLowerCase();
        if ("allow".equals(choice) || "y".equals(choice) || "yes".equals(choice) || "a".equals(choice)) {
            HITL.approve(currentSession, task.getToolName());
            printAboveLine(SUCCESS_COLOR + "  " + ICON_CHECK + " Approved" + RESET);
            // 继续 AI ReAct 循环
            startAgentTask(currentSession, null);
        } else {
            HITL.reject(currentSession, task.getToolName());
            printAboveLine(DIM + "  " + ICON_CROSS + " Rejected" + RESET);
        }
    }

    private void onFinalChunk(ReActChunk react, AtomicBoolean isFirstReasonChunk,
                              AtomicBoolean isFirstConversation) {
        String delta = clearThink(react.getContent());
        if (Assert.isNotEmpty(delta) && (react.isNormal() == false || isFirstReasonChunk.get())) {
            onReasonChunkDo(delta, isFirstReasonChunk, isFirstConversation);
        }

        if (isFirstReasonChunk.get() && agentProps.isThinkPrinted()) {
            String thinkingFallback = consumeThinkingTranscript();
            if (Assert.isNotEmpty(thinkingFallback)) {
                stopThinkingOutput(true);
                onReasonChunkDo(thinkingFallback, isFirstReasonChunk, isFirstConversation);
            }
        } else {
            resetThinkingTranscript();
            thinkingStartedAt = 0L;
        }

        flushLineBuffer();
        mdRenderer.flush(); // 确保 Markdown 状态重置
        persistAssistantEventIfNeeded();

        if (react.getTrace().getMetrics() != null) {
            long tokens = react.getTrace().getMetrics().getTotalTokens();
            String timeInfo = statusBar != null ? ", " + statusBar.getTaskTimeText() : "";
            String metricText = tokens + " tokens" + timeInfo;
            printAboveLine(TIME_COLOR + "  (" + metricText + ")" + RESET);

            SessionManager.SessionEvent event = new SessionManager.SessionEvent();
            event.type = "metric";
            event.content = metricText;
            appendPortalEvent(event);

            // 状态栏：任务结束
            if (statusBar != null) {
                statusBar.taskEnd(tokens);
            }
        }
    }

    private void onReasonChunk(ReasonChunk reason, AtomicBoolean isFirstReasonChunk,
                               AtomicBoolean isFirstConversation) {
        if (!reason.isToolCalls() && reason.hasContent()) {
            boolean isThinking = reason.getMessage().isThinking();

            if (isThinking) {
                if (statusBar != null) {
                    statusBar.updateStatus("thinking");
                }

                if (!agentProps.isThinkPrinted()) {
                    stopThinkingOutput(false);
                    return;
                }

                // ── 思考内容：MUTED 色 + │ 左边线 ──
                if (!thinkingStarted) {
                    flushLineBuffer();
                    printTimedHeader(ICON_THINKING, "Thinking", THINKING_TITLE, LocalDateTime.now());
                    thinkingStarted = true;
                    thinkingLineStart = true;
                    thinkingStartedAt = System.currentTimeMillis();
                }

                String delta = clearThink(reason.getContent());
                // 去掉前导空行
                if (thinkingLineStart) {
                    delta = delta.replaceAll("^[\\n\\r]+", "");
                }
                if (Assert.isNotEmpty(delta)) {
                    appendThinkingTranscript(delta);
                    for (char ch : delta.toCharArray()) {
                        if (ch == '\n') {
                            flushLineBuffer();
                            thinkingLineStart = true;
                        } else if (ch != '\r') {
                            if (thinkingLineStart) {
                                appendToLineBuffer(THINKING_BORDER + "  \u2502 " + RESET + MUTED);
                                thinkingLineStart = false;
                            }
                            appendToLineBuffer(String.valueOf(ch));
                        }
                    }
                }
            } else {
                // ── 正常内容 ──
                persistThinkingEventIfNeeded();
                stopThinkingOutput(true);
                String delta = clearThink(reason.getContent());
                onReasonChunkDo(delta, isFirstReasonChunk, isFirstConversation);
            }
        }
    }

    private void stopThinkingOutput(boolean switchToResponding) {
        if (thinkingStarted) {
            flushLineBuffer();
            thinkingStarted = false;
            thinkingLineStart = true;
        }
        thinkingStartedAt = 0L;

        if (switchToResponding && statusBar != null) {
            statusBar.updateStatus("responding");
        }
    }

    private void appendThinkingTranscript(String delta) {
        synchronized (thinkingTranscript) {
            thinkingTranscript.append(delta);
        }
    }

    private String consumeThinkingTranscript() {
        synchronized (thinkingTranscript) {
            String text = thinkingTranscript.toString();
            thinkingTranscript.setLength(0);
            return text;
        }
    }

    private void resetThinkingTranscript() {
        synchronized (thinkingTranscript) {
            thinkingTranscript.setLength(0);
        }
    }

    private void appendAssistantTranscript(String delta) {
        synchronized (assistantTranscript) {
            assistantTranscript.append(delta);
        }
    }

    private String consumeAssistantTranscript() {
        synchronized (assistantTranscript) {
            String text = assistantTranscript.toString();
            assistantTranscript.setLength(0);
            return text;
        }
    }

    private void resetAssistantTranscript() {
        synchronized (assistantTranscript) {
            assistantTranscript.setLength(0);
        }
    }

    private void appendPortalEvent(SessionManager.SessionEvent event) {
        if (event == null || currentSession == null) {
            return;
        }

        String sessionId = currentSession.getSessionId();
        if (Assert.isEmpty(sessionId) || sessionId.startsWith("_tmp_")) {
            return;
        }

        SessionManager.SessionMeta meta = sessionManager.getSessionMeta(sessionId);
        if (meta != null && !sessionManager.hasPortalEvents(meta)) {
            sessionManager.bootstrapPortalEvents(meta);
        }

        sessionManager.appendPortalEvent(sessionId, agentProps.getWorkspace(), event);
    }

    private void persistThinkingEventIfNeeded() {
        String thinking = consumeThinkingTranscript();
        if (Assert.isEmpty(thinking)) {
            thinkingStartedAt = 0L;
            return;
        }

        SessionManager.SessionEvent event = new SessionManager.SessionEvent();
        event.type = "thinking";
        event.timestamp = thinkingStartedAt;
        event.content = thinking;
        appendPortalEvent(event);
        thinkingStartedAt = 0L;
    }

    private void persistAssistantEventIfNeeded() {
        String content = consumeAssistantTranscript();
        if (Assert.isEmpty(content)) {
            assistantStartedAt = 0L;
            return;
        }

        SessionManager.SessionEvent event = new SessionManager.SessionEvent();
        event.type = "assistant";
        event.timestamp = assistantStartedAt;
        event.content = content;
        appendPortalEvent(event);
        assistantStartedAt = 0L;
    }

    private void onReasonChunkDo(String delta, AtomicBoolean isFirstReasonChunk,
                                 AtomicBoolean isFirstConversation) {
        if (Assert.isNotEmpty(delta)) {
            if (isFirstReasonChunk.get()) {
                String trimmed = delta.replaceAll("^[\\s\\n]+", "");
                if (Assert.isNotEmpty(trimmed)) {
                    isFirstConversation.set(false);
                    isFirstReasonChunk.set(false);
                    LocalDateTime now = LocalDateTime.now();
                    printTimedHeader(ICON_ASSISTANT, "Assistant", ASSISTANT_TITLE, now);
                    assistantStartedAt = System.currentTimeMillis();
                    mdRenderer.reset(); // 新回合重置渲染器
                    mdRenderer.feed(trimmed);
                    appendAssistantTranscript(trimmed);
                }
            } else {
                mdRenderer.feed(delta);
                appendAssistantTranscript(delta);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 行缓冲 + printAbove 工具方法
    // ═══════════════════════════════════════════════════════════

    private void onActionEndChunk(ActionEndChunk action, AtomicBoolean isFirstReasonChunk) {
        // 如果 thinking 还在进行，先结束
        if (thinkingStarted) {
            persistThinkingEventIfNeeded();
            flushLineBuffer();
            thinkingStarted = false;
            thinkingLineStart = true;
        }
        flushLineBuffer();
        persistAssistantEventIfNeeded();

        if (Assert.isNotEmpty(action.getToolName())) {
            final String fullToolName;

            if (kernel.getName().equals(action.getAgentName())) {
                fullToolName = action.getToolName();
            } else {
                fullToolName = action.getAgentName() + "/" + action.getToolName();
            }

            // 状态栏：工具调用（updateStatus 内部自动 draw）
            if (statusBar != null) {
                statusBar.updateStatus("tool:" + fullToolName);
            }
            // 准备参数
            Map<String, Object> args = action.getArgs();
            List<String> argSegments = buildToolArgSegments(args);
            String argsStr = buildToolArgsText(argSegments);

            // 结果摘要
            String content = action.getContent() == null ? "" : action.getContent().trim();
            long toolTimestamp = System.currentTimeMillis();
            printTimedHeader(ICON_TOOL, fullToolName, TOOL_TITLE, toLocalDateTime(toolTimestamp));

            List<String> primaryLines = agentProps.isCliPrintSimplified()
                ? buildCompactToolPrimaryLines(argsStr, MAX_TOOL_PRIMARY_LINES)
                : buildDetailedToolPrimaryLines(argSegments, MAX_TOOL_PRIMARY_LINES);
            for (String line : primaryLines) {
                printAboveLine(line);
            }

            List<String> resultLines = buildToolResultLines(content, MAX_TOOL_RESULT_LINES);
            if (!resultLines.isEmpty()) {
                if (!agentProps.isCliPrintSimplified()) {
                    printAboveLine("");
                }
                for (String line : resultLines) {
                    printAboveLine(line);
                }
            }

            printAboveLine(TOOL_RESULT + "     (" + buildToolSummary(content) + ")" + RESET);

            SessionManager.SessionEvent event = new SessionManager.SessionEvent();
            event.type = "tool";
            event.timestamp = toolTimestamp;
            event.toolName = fullToolName;
            event.argsText = argsStr;
            event.content = content;
            event.argSegments.addAll(argSegments);
            appendPortalEvent(event);

            isFirstReasonChunk.set(true);
        }
    }

    private List<String> buildCompactToolPrimaryLines(String argsStr, int maxLines) {
        String normalized = normalizeToolValue(argsStr);
        if (Assert.isEmpty(normalized)) {
            List<String> lines = new ArrayList<String>();
            lines.add(TOOL_META + TOOL_BODY_PREFIX + "no arguments" + RESET);
            return lines;
        }

        List<String> segments = new ArrayList<String>();
        segments.add(normalized);
        return buildPackedToolPrimaryLines(segments, maxLines);
    }

    private List<String> buildDetailedToolPrimaryLines(List<String> segments, int maxLines) {
        if (segments == null || segments.isEmpty()) {
            List<String> lines = new ArrayList<String>();
            lines.add(TOOL_META + TOOL_BODY_PREFIX + "no arguments" + RESET);
            return lines;
        }

        return buildPackedToolPrimaryLines(segments, maxLines);
    }

    private List<String> buildToolArgSegments(Map<String, Object> args) {
        List<String> segments = new ArrayList<String>();
        if (args == null || args.isEmpty()) {
            return segments;
        }

        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = normalizeToolValue(entry.getValue());
            if (Assert.isEmpty(key)) {
                if (Assert.isNotEmpty(value)) {
                    segments.add(value);
                }
            } else if (Assert.isEmpty(value)) {
                segments.add(key + "=");
            } else {
                segments.add(key + "=" + value);
            }
        }

        return segments;
    }

    private String buildToolArgsText(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (Assert.isEmpty(segment)) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(segment);
        }

        return builder.toString();
    }

    private List<String> buildPackedToolPrimaryLines(List<String> segments, int maxLines) {
        List<String> lines = new ArrayList<String>();
        if (segments == null || segments.isEmpty() || maxLines <= 0) {
            return lines;
        }

        int availableWidth = Math.max(12, terminal.getWidth() - displayWidth(TOOL_BODY_PREFIX));
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        int hiddenSegments = 0;

        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            if (Assert.isEmpty(segment)) {
                continue;
            }

            String piece = current.length() == 0 ? segment : "  " + segment;
            int pieceWidth = displayWidth(piece);
            if (current.length() > 0 && currentWidth + pieceWidth <= availableWidth) {
                current.append(piece);
                currentWidth += pieceWidth;
                continue;
            }

            if (current.length() > 0) {
                lines.add(formatToolPrimaryLine(current.toString()));
                if (lines.size() >= maxLines) {
                    hiddenSegments = segments.size() - i;
                    break;
                }
                current.setLength(0);
                currentWidth = 0;
            }

            if (displayWidth(segment) <= availableWidth) {
                current.append(segment);
                currentWidth = displayWidth(segment);
                continue;
            }

            List<String> wrapped = wrapPlainText(segment, availableWidth, maxLines - lines.size());
            for (int j = 0; j < wrapped.size(); j++) {
                boolean lastWrappedLine = (j == wrapped.size() - 1);
                if (lastWrappedLine && displayWidth(segment) <= availableWidth * wrapped.size()) {
                    current.append(wrapped.get(j));
                    currentWidth = displayWidth(wrapped.get(j));
                } else {
                    lines.add(formatToolPrimaryLine(wrapped.get(j)));
                    if (lines.size() >= maxLines) {
                        hiddenSegments = segments.size() - i - 1;
                        break;
                    }
                }
            }

            if (hiddenSegments > 0 || lines.size() >= maxLines) {
                break;
            }
        }

        if (hiddenSegments == 0 && current.length() > 0 && lines.size() < maxLines) {
            lines.add(formatToolPrimaryLine(current.toString()));
        } else if (hiddenSegments > 0 && !lines.isEmpty()) {
            String suffix = "  ... +" + hiddenSegments + " more args";
            String plain = trimToolBodyPrefix(stripAnsi(lines.get(lines.size() - 1)));
            String merged = clipToWidth(plain + suffix, availableWidth);
            lines.set(lines.size() - 1, formatToolPrimaryLine(merged));
        } else if (hiddenSegments > 0) {
            lines.add(TOOL_META + TOOL_BODY_PREFIX + "... +" + hiddenSegments + " more args" + RESET);
        }

        return lines;
    }

    private String formatToolPrimaryLine(String plain) {
        StringBuilder builder = new StringBuilder();
        builder.append(TOOL_META).append(TOOL_BODY_PREFIX).append(RESET);

        String[] segments = plain.split(" {2,}");
        boolean appended = false;
        for (String segment : segments) {
            if (Assert.isEmpty(segment)) {
                continue;
            }

            if (appended) {
                builder.append(TOOL_META).append("  ").append(RESET);
            }
            builder.append(formatToolSegment(segment));
            appended = true;
        }

        if (!appended) {
            builder.append(TOOL_VALUE).append(plain).append(RESET);
        }

        return builder.toString();
    }

    private String formatToolSegment(String segment) {
        int eqIdx = segment.indexOf('=');
        if (eqIdx < 0) {
            return TOOL_VALUE + segment + RESET;
        }

        String key = segment.substring(0, eqIdx);
        String value = segment.substring(eqIdx + 1);
        if (value.isEmpty()) {
            return TOOL_META + key + RESET + SOFT + "=" + RESET;
        }

        return TOOL_META + key + RESET
            + SOFT + "=" + RESET
            + TOOL_VALUE + value + RESET;
    }

    private String trimToolBodyPrefix(String text) {
        if (text == null) {
            return "";
        }
        if (text.startsWith(TOOL_BODY_PREFIX)) {
            return text.substring(TOOL_BODY_PREFIX.length());
        }
        return text;
    }

    private String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private List<String> buildToolResultLines(String content, int maxLines) {
        List<String> lines = new ArrayList<String>();
        if (Assert.isEmpty(content)) {
            return lines;
        }

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = normalized.split("\n", -1);
        int limit = Math.min(rawLines.length, maxLines);
        boolean truncated = rawLines.length > maxLines;
        if (truncated) {
            limit = Math.max(0, maxLines - 1);
        }

        for (int i = 0; i < limit; i++) {
            lines.add(TOOL_META + TOOL_BODY_PREFIX + "\u2502 " + RESET
                + TOOL_PREVIEW
                + clipToWidth(rawLines[i], Math.max(10, terminal.getWidth() - displayWidth(TOOL_BODY_PREFIX) - 2))
                + RESET);
        }

        if (truncated) {
            lines.add(TOOL_META + TOOL_BODY_PREFIX + "\u2502 " + RESET
                + TOOL_RESULT + "... +" + (rawLines.length - limit) + " more lines" + RESET);
        }

        return lines;
    }

    private String buildToolSummary(String content) {
        if (Assert.isEmpty(content)) {
            return "completed";
        }

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return "completed";
        }

        String[] lines = normalized.split("\n");
        if (lines.length > 1) {
            return "returned " + lines.length + " lines";
        }

        return clipToWidth(lines[0], 48);
    }

    private String normalizeToolValue(Object value) {
        if (value == null) {
            return "";
        }

        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return "";
        }

        return text.replace("\r\n", " \u21a9 ").replace('\r', ' ').replace('\n', ' ');
    }

    private List<String> wrapPlainText(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<String>();
        if (maxLines <= 0) {
            return lines;
        }

        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            lines.add("");
            return lines;
        }

        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        boolean truncated = false;
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            int charWidth = charDisplayWidth(codePoint);
            if (currentWidth + charWidth > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                if (lines.size() >= maxLines) {
                    truncated = i < normalized.length();
                    break;
                }
                current.setLength(0);
                currentWidth = 0;
            }
            current.appendCodePoint(codePoint);
            currentWidth += charWidth;
            i += Character.charCount(codePoint);
        }

        if (!truncated && current.length() > 0 && lines.size() < maxLines) {
            lines.add(current.toString());
        }

        if (truncated && !lines.isEmpty()) {
            lines.set(lines.size() - 1, clipToWidth(lines.get(lines.size() - 1) + "...", Math.max(3, maxWidth)));
        }

        return lines;
    }

    /**
     * 向行缓冲追加内容（不立即输出）
     */
    private void appendToLineBuffer(String text) {
        synchronized (lineBuffer) {
            lineBuffer.append(text);
        }
    }

    /**
     * 将行缓冲内容 flush 到 printAbove（一整行）
     */
    private void flushLineBuffer() {
        synchronized (lineBuffer) {
            if (lineBuffer.length() > 0) {
                printAboveLine(lineBuffer.toString() + RESET);
                lineBuffer.setLength(0);
            }
        }
    }

    /**
     * 直接输出到主缓冲区。底部输入已自管，不能再使用 printAbove。
     */
    private void printAboveLine(String line) {
        lastContentLineBlank = isVisualBlank(line);
        if (screenRenderer != null) {
            screenRenderer.appendContentLine(line);
        }
    }

    private void printUserInput(String input) {
        if (Assert.isEmpty(input)) {
            return;
        }

        String normalized = input.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0) {
            return;
        }

        long timestamp = System.currentTimeMillis();
        printTimedHeader(ICON_USER, "You", USER_TITLE, toLocalDateTime(timestamp));
        printAboveLine("  " + lines[0]);
        for (int i = 1; i < lines.length; i++) {
            printAboveLine("  " + lines[i]);
        }

        SessionManager.SessionEvent event = new SessionManager.SessionEvent();
        event.type = "user";
        event.timestamp = timestamp;
        event.content = normalized;
        appendPortalEvent(event);
    }

    private void printTimedHeader(String icon, String title, String titleStyle, LocalDateTime time) {
        ensureBlankLineBeforeBlock();
        printAboveLine(buildTimedHeader(icon, title, titleStyle, time));
    }

    private void ensureBlankLineBeforeBlock() {
        if (!lastContentLineBlank) {
            printAboveLine("");
        }
    }

    private String buildTimedHeader(String icon, String title, String titleStyle, LocalDateTime time) {
        String timeText = time == null ? "" : TIME_FORMATTER.format(time);
        String leftPlain = icon + " " + title;
        int terminalWidth = Math.max(20, terminal.getWidth());
        String timeSuffix = timeText.isEmpty() ? "" : " · " + timeText;
        int suffixWidth = displayWidth(timeSuffix);
        int availableLeftWidth = Math.max(1, terminalWidth - suffixWidth);
        String clippedLeft = clipToWidth(leftPlain, availableLeftWidth);
        if (timeText.isEmpty()) {
            return titleStyle + clippedLeft + RESET;
        }
        return titleStyle + clippedLeft + RESET
            + TIME_COLOR + timeSuffix + RESET;
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        if (epochMillis <= 0L) {
            return null;
        }

        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private String clipToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }

        int totalWidth = displayWidth(text);
        if (totalWidth <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = displayWidth(ellipsis);
        int targetWidth = Math.max(0, maxWidth - ellipsisWidth);
        StringBuilder builder = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charWidth = charDisplayWidth(codePoint);
            if (width + charWidth > targetWidth) {
                break;
            }
            builder.appendCodePoint(codePoint);
            width += charWidth;
            i += Character.charCount(codePoint);
        }
        builder.append(ellipsis);
        return builder.toString();
    }

    private int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += charDisplayWidth(codePoint);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private int charDisplayWidth(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
            || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
            || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            || block == Character.UnicodeBlock.HIRAGANA
            || block == Character.UnicodeBlock.KATAKANA
            || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
            || block == Character.UnicodeBlock.HANGUL_JAMO
            || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
            || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES
            || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
            || block == Character.UnicodeBlock.BOPOMOFO
            || block == Character.UnicodeBlock.BOPOMOFO_EXTENDED
            || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
            || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
            return 2;
        }

        // Java 8 does not expose CJK extension E/F blocks, but they are supplementary code points.
        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 2;
        }

        if (Character.isISOControl(codePoint)) {
            return 0;
        }

        return 1;
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private boolean isVisualBlank(String line) {
        if (line == null || line.isEmpty()) {
            return true;
        }
        String plain = line.replaceAll("\u001B\\[[;\\d]*m", "");
        return plain.trim().isEmpty();
    }

    private String clearThink(String chunk) {
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    private void openThemePicker() {
        final PortalTheme originalTheme = theme;
        final List<BottomListPanel.Item<PortalTheme>> items = buildThemeSelectionItems();
        int selectedIndex = Math.max(0, PortalThemes.names().indexOf(theme.name()));

        PortalTheme selected = bottomInputController.selectFromList(
            "/theme",
            items,
            selectedIndex,
            new BottomInputController.SelectionCallbacks<PortalTheme>() {
                @Override
                public void onFocus(PortalTheme value) {
                    if (value != null) {
                        applyTheme(value);
                    }
                }

                @Override
                public void onConfirm(PortalTheme value) {
                    if (value != null) {
                        applyTheme(value);
                    }
                }

                @Override
                public void onCancel() {
                    applyTheme(originalTheme);
                }
            });

        if (selected != null) {
            persistThemeSelection(selected);
        } else {
            printAboveLine(DIM + "  Cancelled." + RESET);
        }
    }

    private void persistThemeSelection(PortalTheme selected) {
        applyTheme(selected);
        agentProps.setUiTheme(selected.name());
        printAboveLine(DIM + "  Theme: " + RESET + TEXT + BOLD + selected.name() + RESET);

        try {
            GlobalConfigWriter.persistUiTheme(selected.name());
        } catch (RuntimeException e) {
            LOG.warn("Persist theme to global config failed", e);
            printAboveLine(WARN + "  Theme save failed: " + RESET + e.getMessage());
        }
    }

    private List<BottomListPanel.Item<PortalTheme>> buildThemeSelectionItems() {
        List<BottomListPanel.Item<PortalTheme>> items = new ArrayList<BottomListPanel.Item<PortalTheme>>();
        for (PortalTheme item : PortalThemes.allThemes()) {
            boolean current = item.name().equalsIgnoreCase(theme.name());
            String secondary = PortalThemes.isCustom(item.name()) ? "custom" : "builtin";
            if (current) {
                secondary += " · current";
            }
            items.add(new BottomListPanel.Item<PortalTheme>(
                item,
                item.name(),
                secondary,
                current ? BottomListPanel.Tone.ACCENT : BottomListPanel.Tone.TEXT));
        }
        return items;
    }

    private void printSessionList(List<SessionManager.SessionMeta> sessions) {
        printAboveLine("");
        printAboveLine(TEXT + BOLD + "  Sessions" + RESET);
        printAboveLine("");
        int idx = 1;
        for (SessionManager.SessionMeta m : sessions) {
            boolean isCurrent = currentSession != null
                && currentSession.getSessionId().equals(m.id);
            String marker = isCurrent ? ACCENT_BOLD + " * " + RESET : "   ";
            String title = displaySessionTitle(m);
            String secondary = buildSessionSecondary(m);
            printAboveLine(marker + ACCENT_BOLD + idx + RESET
                + MUTED + "  [" + m.id + "]  " + RESET
                + TEXT + title + RESET);
            printAboveLine("      " + MUTED + secondary + RESET);
            idx++;
        }
        printAboveLine("");
    }

    /**
     * Lazy session creation: only persist a session when actual conversation happens.
     * Temp sessions (id starts with _tmp_) are replaced with a real one on first message.
     */
    private void ensureSessionCreated(String firstMessage) {
        String sid = currentSession.getSessionId();
        if (sid.startsWith("_tmp_")) {
            // First real message — create a persistent session
            String newId = sessionManager.createSession(agentProps.getWorkspace());
            sessionManager.updateTitle(newId, firstMessage);
            sessionManager.touch(newId);
            currentSession = kernel.getSession(newId);
            //kernel.init(currentSession);
            if (statusBar != null) {
                statusBar.setSessionId(newId);
                statusBar.draw();
            }
        } else {
            // Existing session — just update meta
            sessionManager.touch(sid);
        }
    }

    private SessionManager.SessionMeta resolveResumeTarget(String input, List<SessionManager.SessionMeta> sessions) {
        for (SessionManager.SessionMeta meta : sessions) {
            if (meta.id != null && meta.id.equalsIgnoreCase(input)) {
                return meta;
            }
        }

        SessionManager.SessionMeta byId = sessionManager.getSessionMeta(input);
        if (byId != null
            && agentProps.getWorkspace().equals(byId.cwd)
            && sessionManager.hasSessionData(byId)) {
            return byId;
        }

        try {
            int idx = Integer.parseInt(input);
            if (idx < 1 || idx > sessions.size()) {
                printAboveLine(ERROR_COLOR + "  Invalid number: " + idx
                    + " (1-" + sessions.size() + ")" + RESET);
                return null;
            }
            return sessions.get(idx - 1);
        } catch (NumberFormatException e) {
            printAboveLine(ERROR_COLOR + "  Please enter a valid session number or id." + RESET);
            return null;
        }
    }

    private List<BottomListPanel.Item<SessionManager.SessionMeta>> buildSessionSelectionItems(
        List<SessionManager.SessionMeta> sessions) {
        List<BottomListPanel.Item<SessionManager.SessionMeta>> items =
            new ArrayList<BottomListPanel.Item<SessionManager.SessionMeta>>();

        for (SessionManager.SessionMeta meta : sessions) {
            String title = displaySessionTitle(meta);
            String secondary = buildSessionSecondary(meta);
            items.add(new BottomListPanel.Item<SessionManager.SessionMeta>(
                meta,
                title,
                secondary,
                BottomListPanel.Tone.TEXT));
        }

        return items;
    }

    private void resumeSession(SessionManager.SessionMeta meta, boolean announce) {
        if (meta == null) {
            return;
        }

        currentSession = kernel.getSession(meta.id);
        if (statusBar != null) {
            statusBar.setSessionId(meta.id);
            statusBar.draw();
        }

        List<String> lines = buildSessionHistoryLines(meta, announce);
        lastContentLineBlank = lines.isEmpty() || isVisualBlank(lines.get(lines.size() - 1));

        if (screenRenderer != null) {
            screenRenderer.replaceContent(lines);
        } else {
            for (String line : lines) {
                printAboveLine(line);
            }
        }
    }

    private AgentSession createStartupSession() {
        if (!shouldResumeSessionOnStartup()) {
            if (statusBar != null) {
                statusBar.setSessionId("(new)");
                statusBar.draw();
            }
            return kernel.getSession("_tmp_" + System.currentTimeMillis());
        }

        List<SessionManager.SessionMeta> sessions = getRestorableSessionsForCurrentDir();
        SessionManager.SessionMeta latest = sessions.isEmpty() ? null : sessions.get(0);
        if (latest != null) {
            resumeSession(latest, false);
            return currentSession;
        }

        if (statusBar != null) {
            statusBar.setSessionId("(new)");
            statusBar.draw();
        }
        return kernel.getSession("_tmp_" + System.currentTimeMillis());
    }

    private boolean shouldResumeSessionOnStartup() {
        String mode = agentProps.getStartupSessionMode();
        if (Assert.isEmpty(mode)) {
            return true;
        }

        String normalized = mode.trim().toLowerCase();
        if ("resume".equals(normalized) || "restore".equals(normalized)) {
            return true;
        }
        if ("temp".equals(normalized) || "temporary".equals(normalized) || "new".equals(normalized)) {
            return false;
        }

        LOG.warn("Unknown startupSessionMode: {}, fallback to resume", mode);
        return true;
    }

    private void resumeLatestSession(List<SessionManager.SessionMeta> sessions, boolean announce) {
        if (sessions == null || sessions.isEmpty()) {
            printAboveLine(DIM + "  No sessions for this directory." + RESET);
            return;
        }

        resumeSession(sessions.get(0), announce);
    }

    private List<SessionManager.SessionMeta> getRestorableSessionsForCurrentDir() {
        return sessionManager.listRestorableSessions(agentProps.getWorkspace());
    }

    private String displaySessionTitle(SessionManager.SessionMeta meta) {
        if (meta == null) {
            return "(unknown)";
        }

        String title = Assert.isEmpty(meta.title) ? "" : meta.title.trim();
        if (Assert.isNotEmpty(title)) {
            return title;
        }

        String fallback = sessionManager.extractLastUserMessage(meta);
        if (Assert.isNotEmpty(fallback)) {
            return clipToWidth(fallback.replace('\n', ' '), 42);
        }

        return "(untitled)";
    }

    private String buildSessionSecondary(SessionManager.SessionMeta meta) {
        String time = SessionManager.formatTime(meta.updatedAt);
        int turns = Math.max(1, meta.messageCount);
        String preview = sessionManager.extractLastUserMessage(meta);
        String secondary = "[" + time + "]  (" + turns + " turns)";
        if (Assert.isNotEmpty(preview)) {
            secondary = secondary + "  ·  " + clipToWidth(preview.replace('\n', ' '), 48);
        }
        return secondary;
    }

    private List<String> buildSessionHistoryLines(SessionManager.SessionMeta meta, boolean announce) {
        List<String> lines = new ArrayList<String>();
        if (announce) {
            lines.add(DIM + "  Resumed: " + RESET + TEXT + BOLD + displaySessionTitle(meta) + RESET
                + DIM + " · " + meta.id + RESET);
        }

        List<SessionManager.SessionEvent> events = sessionManager.readPortalEvents(meta);
        if (!events.isEmpty()) {
            for (SessionManager.SessionEvent event : events) {
                appendSessionEvent(lines, event);
            }
            return lines;
        }

        List<SessionManager.SessionMessage> messages = sessionManager.readMessages(meta);
        for (SessionManager.SessionMessage message : messages) {
            appendSessionMessage(lines, message);
        }

        return lines;
    }

    private void appendSessionMessage(List<String> lines, SessionManager.SessionMessage message) {
        if (message == null || Assert.isEmpty(message.content)) {
            return;
        }

        String role = message.role == null ? "" : message.role.trim().toUpperCase();
        if ("USER".equals(role)) {
            appendUserMessageLines(lines, message.content, null);
            return;
        }

        if ("ASSISTANT".equals(role)) {
            appendAssistantMessageLines(lines, message.content, null);
            return;
        }

        appendGenericReplayLines(lines, role, message.content);
    }

    private void appendSessionEvent(List<String> lines, SessionManager.SessionEvent event) {
        if (event == null || Assert.isEmpty(event.type)) {
            return;
        }

        LocalDateTime time = toLocalDateTime(event.timestamp);
        String type = event.type.trim().toLowerCase();
        if ("user".equals(type)) {
            appendUserMessageLines(lines, event.content, time);
            return;
        }

        if ("assistant".equals(type)) {
            appendAssistantMessageLines(lines, event.content, time);
            return;
        }

        if ("thinking".equals(type)) {
            appendThinkingMessageLines(lines, event.content, time);
            return;
        }

        if ("tool".equals(type)) {
            appendToolEventLines(lines, event, time);
            return;
        }

        if ("metric".equals(type)) {
            appendMetricLines(lines, event.content);
            return;
        }

        appendGenericReplayLines(lines, event.type, event.content);
    }

    private void appendUserMessageLines(List<String> lines, String input, LocalDateTime time) {
        if (Assert.isEmpty(input)) {
            return;
        }

        ensureBlankLineBeforeBlock(lines);
        lines.add(buildTimedHeader(ICON_USER, "You", USER_TITLE, time));

        String normalized = input.replace("\r\n", "\n").replace('\r', '\n');
        String[] rows = normalized.split("\n", -1);
        for (String row : rows) {
            lines.add("  " + row);
        }
    }

    private void appendAssistantMessageLines(List<String> lines, String content, LocalDateTime time) {
        if (Assert.isEmpty(content)) {
            return;
        }

        ensureBlankLineBeforeBlock(lines);
        lines.add(buildTimedHeader(ICON_ASSISTANT, "Assistant", ASSISTANT_TITLE, time));
        lines.addAll(renderMarkdownLines(content));
    }

    private void appendThinkingMessageLines(List<String> lines, String content, LocalDateTime time) {
        if (Assert.isEmpty(content)) {
            return;
        }

        ensureBlankLineBeforeBlock(lines);
        lines.add(buildTimedHeader(ICON_THINKING, "Thinking", THINKING_TITLE, time));

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] rows = normalized.split("\n", -1);
        for (String row : rows) {
            lines.add(THINKING_BORDER + "  \u2502 " + RESET + MUTED + row + RESET);
        }
    }

    private void appendToolEventLines(List<String> lines, SessionManager.SessionEvent event, LocalDateTime time) {
        String toolName = Assert.isEmpty(event.toolName) ? "Tool" : event.toolName;
        ensureBlankLineBeforeBlock(lines);
        lines.add(buildTimedHeader(ICON_TOOL, toolName, TOOL_TITLE, time));

        List<String> detailSegments = event.argSegments == null ? new ArrayList<String>() : event.argSegments;
        List<String> primaryLines = agentProps.isCliPrintSimplified()
            ? buildCompactToolPrimaryLines(event.argsText, MAX_TOOL_PRIMARY_LINES)
            : buildDetailedToolPrimaryLines(detailSegments.isEmpty() && Assert.isNotEmpty(event.argsText)
                                            ? java.util.Collections.singletonList(event.argsText)
                                            : detailSegments, MAX_TOOL_PRIMARY_LINES);
        lines.addAll(primaryLines);

        List<String> resultLines = buildToolResultLines(event.content, MAX_TOOL_RESULT_LINES);
        if (!resultLines.isEmpty()) {
            if (!agentProps.isCliPrintSimplified()) {
                lines.add("");
            }
            lines.addAll(resultLines);
        }

        lines.add(TOOL_RESULT + "     (" + buildToolSummary(event.content) + ")" + RESET);
    }

    private void appendMetricLines(List<String> lines, String metricText) {
        if (Assert.isEmpty(metricText)) {
            return;
        }

        lines.add(TIME_COLOR + "  (" + metricText + ")" + RESET);
    }

    private void appendGenericReplayLines(List<String> lines, String role, String content) {
        ensureBlankLineBeforeBlock(lines);
        lines.add(buildTimedHeader(ICON_TOOL, role, TOOL_TITLE, null));
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] rows = normalized.split("\n", -1);
        for (String row : rows) {
            lines.add("  " + row);
        }
    }

    private List<String> renderMarkdownLines(String markdown) {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder replayBuffer = new StringBuilder();
        MarkdownRenderer replayRenderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                replayBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(replayBuffer.toString() + RESET);
                replayBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return terminal == null ? 80 : terminal.getWidth();
            }
        });
        replayRenderer.setTheme(theme);
        replayRenderer.reset();
        replayRenderer.feed(markdown);
        replayRenderer.flush();
        return lines;
    }

    private void ensureBlankLineBeforeBlock(List<String> lines) {
        if (!lines.isEmpty() && !isVisualBlank(lines.get(lines.size() - 1))) {
            lines.add("");
        }
    }

    private boolean isSystemCommand(AgentSession session, String input) {
        String cmd = input.trim();

        if (cmd.startsWith("/")) {
            CommandRegistry.CommandContext ctx = new CommandRegistry.CommandContext(session, null);
            if (commandRegistry.execute(cmd, ctx)) {
                return true;
            }
            printAboveLine(ERROR_COLOR + "未知命令: " + RESET + cmd + MUTED + " (输入 /help 查看可用命令)" + RESET);
            return true;
        }

        String lower = cmd.toLowerCase();
        if ("exit".equals(lower) || "init".equals(lower) || "clear".equals(lower)) {
            CommandRegistry.CommandContext ctx = new CommandRegistry.CommandContext(session, null);
            return commandRegistry.execute("/" + lower, ctx);
        }

        return false;
    }

    private void printHelp() {
        printAboveLine("");
        printAboveLine(TEXT + BOLD + "  Commands" + RESET);
        printAboveLine("");
        for (CommandRegistry.Command cmd : commandRegistry.getAllCommands()) {
            String name = cmd.getName();
            String padded = name + repeatChar(' ', Math.max(1, 16 - name.length()));
            printAboveLine("    " + ACCENT_BOLD + padded + RESET + MUTED + cmd.getDescription() + RESET);
        }
        printAboveLine("");
        printAboveLine(TEXT + BOLD + "  Keybindings" + RESET);
        printAboveLine("");
        printAboveLine(MUTED + "    Tab          " + RESET + SOFT + "Auto-complete /commands" + RESET);
        printAboveLine(MUTED + "    \\+Enter      " + RESET + SOFT + "Insert newline" + RESET);
        printAboveLine(MUTED + "    Esc          " + RESET + SOFT + "Cancel running task" + RESET);
        printAboveLine(MUTED + "    Ctrl+C       " + RESET + SOFT + "Cancel task / clear input" + RESET);
        printAboveLine(MUTED + "    Ctrl+D       " + RESET + SOFT + "Exit" + RESET);
        printAboveLine(MUTED + "    Ctrl+L       " + RESET + SOFT + "Clear screen" + RESET);
        printAboveLine("");
        printAboveLine(MUTED + "  " + repeatChar('\u2500', 40) + RESET);
        printAboveLine("");
    }

    private int clearPendingInputsInternal() {
        synchronized (pendingInputsLock) {
            int discarded = pendingInputs.size();
            pendingInputs.clear();
            return discarded;
        }
    }

    private List<String> getPendingInputsSnapshot() {
        synchronized (pendingInputsLock) {
            return new ArrayList<String>(pendingInputs);
        }
    }

    private int enqueuePendingInputInternal(String text) {
        synchronized (pendingInputsLock) {
            if (pendingInputs.size() >= MAX_PENDING_INPUTS) {
                return -1;
            }
            pendingInputs.add(text);
            return pendingInputs.size();
        }
    }

    private List<String> drainPendingInputs() {
        synchronized (pendingInputsLock) {
            List<String> queuedInputs = new ArrayList<String>(pendingInputs);
            pendingInputs.clear();
            return queuedInputs;
        }
    }

    private void refreshBottomFooter() {
        if (bottomInputController != null) {
            bottomInputController.renderNow();
        } else if (screenRenderer != null) {
            screenRenderer.renderNow();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 欢迎界面（冻结 — 不允许改动）
    // ═══════════════════════════════════════════════════════════

    protected void printWelcome() {
        // 初始化状态栏
        this.statusBar = new StatusBar(terminal);
        this.screenRenderer = new PortalScreenRenderer(terminal, statusBar);
        statusBar.setRenderRequester(new Runnable() {
            @Override
            public void run() {
                if (screenRenderer != null) {
                    screenRenderer.renderNow();
                }
            }
        });
        statusBar.setTheme(theme);
        String modelName = kernel.getMainModel().getModel();
        statusBar.setModelName(modelName);
        statusBar.setWorkDir(new File(agentProps.getWorkspace()).getAbsolutePath());
        statusBar.setVersion(AgentFlags.getVersion());
        statusBar.setSessionId("cli");
        statusBar.setCompactMode(agentProps.isCliPrintSimplified());
        statusBar.setup();
        statusBar.setJLineLock(terminalLock);
        screenRenderer.setTerminalLock(terminalLock);
        if (bottomInputController != null) {
            bottomInputController.setTerminalLock(terminalLock);
        }
        // 把 JLine 内部的 ReentrantLock 传给 StatusBar，确保 draw() 跟 printAbove() 用同一把锁
        try {
            java.lang.reflect.Field lockField = LineReaderImpl.class.getDeclaredField("lock");
            lockField.setAccessible(true);
            ReentrantLock jlineLock = (ReentrantLock) lockField.get(reader);
            if (jlineLock != null) {
                terminalLock = jlineLock;
                statusBar.setJLineLock(terminalLock);
                screenRenderer.setTerminalLock(terminalLock);
                if (bottomInputController != null) {
                    bottomInputController.setTerminalLock(terminalLock);
                }
            }
        } catch (Exception e) {
            LOG.warn("Cannot access JLine internal lock, fallback terminal lock will be used", e);
        }

        String path = new File(agentProps.getWorkspace()).getAbsolutePath();
        String version = AgentFlags.getVersion();
        List<String> welcomeLines = new ArrayList<String>();

        // ── ASCII Art Logo (对齐 Go TUI renderWelcomeLogo) ──
        welcomeLines.add("");
        welcomeLines.add(ACCENT_BOLD + "   ███████  ██████  ██      ██████  ███    ██" + RESET + SOFT + BOLD
            + "   ██████  ██████  ██████  ███████" + RESET);
        welcomeLines.add(ACCENT_BOLD + "   ██      ██    ██ ██     ██    ██ ████   ██" + RESET + SOFT + BOLD
            + "  ██      ██    ██ ██   ██ ██" + RESET);
        welcomeLines.add(ACCENT_BOLD + "   ███████ ██    ██ ██     ██    ██ ██ ██  ██" + RESET + SOFT + BOLD
            + "  ██      ██    ██ ██   ██ █████" + RESET);
        welcomeLines.add(ACCENT_BOLD + "        ██ ██    ██ ██     ██    ██ ██  ██ ██" + RESET + SOFT + BOLD
            + "  ██      ██    ██ ██   ██ ██" + RESET);
        welcomeLines.add(ACCENT_BOLD + "   ███████  ██████  ██████  ██████  ██   ████" + RESET + SOFT + BOLD
            + "   ██████  ██████  ██████  ███████" + RESET);
        welcomeLines.add("");

        // ── Meta info ──
        String configSource = ConfigLoader.loadConfig() != null
            ? ConfigLoader.loadConfig().toAbsolutePath().toString()
            : "(built-in)";

        welcomeLines.add(SOFT + "  Model   " + RESET + TEXT + BOLD + modelName + RESET);
        welcomeLines.add(SOFT + "  Dir     " + RESET + SOFT + path + RESET);
        welcomeLines.add(SOFT + "  Config  " + RESET + SOFT + configSource + RESET);
        welcomeLines.add(SOFT + "  Ver     " + RESET + SOFT + version + RESET);
        welcomeLines.add("");
        welcomeLines.add(MUTED + "  " + ICON_PROMPT + " " + RESET + ACCENT + "Tip" + RESET + SOFT + " Type "
            + RESET + TEXT + BOLD + "/help" + RESET + SOFT + " to see all commands" + RESET);
        welcomeLines.add(MUTED + "  " + ICON_PROMPT + " " + RESET + SOFT + "Use " + RESET + TEXT + BOLD + "Tab"
            + RESET + SOFT + " for auto-completion, " + RESET + TEXT + BOLD + "\\" + RESET + SOFT +
            "+Enter for newline" + RESET);
        welcomeLines.add(MUTED + "  " + ICON_PROMPT + " " + RESET + SOFT + "Press " + RESET + TEXT + BOLD
            + "Esc" + RESET + SOFT + "/" + RESET + TEXT + BOLD + "Ctrl+C" + RESET + SOFT + " to cancel operation" +
            RESET);
        welcomeLines.add("");
        welcomeLines.add(MUTED + "  " + repeatChar('\u2500', 40) + RESET);
        welcomeLines.add("");

        if (screenRenderer != null) {
            screenRenderer.replaceContent(welcomeLines);
        }
        if (bottomInputController != null) {
            bottomInputController.renderNow();
        }
    }
}
