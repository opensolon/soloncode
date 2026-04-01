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

import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.codecli.portal.ui.CommandRegistry;
import org.noear.solon.codecli.portal.ui.SlashCommandCompleter;
import org.noear.solon.codecli.portal.ui.MarkdownRenderer;
import org.noear.solon.codecli.portal.ui.StatusBar;
import org.noear.solon.codecli.core.AgentRuntime;
import org.noear.solon.codecli.ConfigLoader;
import org.noear.solon.codecli.SessionManager;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Code CLI 终端 (printAbove 架构 — 输入始终可用)
 */
@Preview("3.9.4")
public class CliShellNew implements Runnable {
    private final static Logger LOG = LoggerFactory.getLogger(CliShellNew.class);

    private Terminal terminal;
    private LineReader reader;
    private final AgentRuntime agentRuntime;
    private final CommandRegistry commandRegistry;
    private final SessionManager sessionManager = new SessionManager();
    private StatusBar statusBar;

    // ── 共享状态 ──
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean taskRunning = new AtomicBoolean(false);
    private volatile boolean thinkingStarted = false;
    private volatile boolean thinkingLineStart = true;

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
    });

    // ── 行缓冲 (printAbove 逐行输出) ──
    private final StringBuilder lineBuffer = new StringBuilder();

    // ANSI 颜色常量 - 对齐 Go TUI 主题
    private final static String BOLD = "\033[1m",
            DIM = "\033[2m",
            ACCENT = "\033[38;2;255;125;144m",
            ACCENT_BOLD = "\033[1;38;2;255;125;144m",
            SOFT = "\033[38;2;160;168;184m",
            MUTED = "\033[38;2;114;123;137m",
            ERROR_COLOR = "\033[38;2;244;124;124m",
            WARN = "\033[38;2;232;194;122m",
            TEXT = "\033[38;2;243;245;247m",
            RESET = "\033[0m";

    // 图标常量 - 对齐 Go TUI
    private final static String ICON_ASSISTANT = "\u2726", // ✦
            ICON_PROMPT = "\u276F", // ❯
            ICON_TOOL = "\uD83D\uDEE0", // 🛠
            ICON_CROSS = "\u2718", // ✘
            ICON_WARN = "\u26A0", // ⚠
            ICON_THINKING = "\u2699", // ⚙
            ICON_CHECK = "\u2714"; // ✔

    public CliShellNew(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
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
                if (statusBar != null) {
                    statusBar.draw();
                }
                if (reader != null) {
                    try {
                        reader.callWidget(LineReader.REDRAW_LINE);
                        reader.callWidget(LineReader.REDISPLAY);
                    } catch (Throwable ignored) {
                    }
                }
            });

            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new SlashCommandCompleter(commandRegistry))
                    .option(LineReader.Option.AUTO_LIST, true)
                    .option(LineReader.Option.AUTO_MENU, true)
                    .option(LineReader.Option.AUTO_MENU_LIST, true)
                    .option(LineReader.Option.LIST_PACKED, true)
                    .option(LineReader.Option.LIST_AMBIGUOUS, false)
                    .option(LineReader.Option.RECOGNIZE_EXACT, false)
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();

            // 补全菜单样式
            reader.setVariable(LineReader.COMPLETION_STYLE_LIST_SELECTION, "fg:white,bg:bright-black,bold");
            reader.setVariable(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:default");
            reader.setVariable(LineReader.COMPLETION_STYLE_LIST_DESCRIPTION, "fg:bright-black");
            reader.setVariable(LineReader.COMPLETION_STYLE_LIST_STARTING, "fg:cyan");
            reader.setVariable(LineReader.COMPLETION_STYLE_SELECTION, "fg:white,bg:bright-black,bold");
            reader.setVariable(LineReader.COMPLETION_STYLE_BACKGROUND, "bg:default");

            // 禁用终端蜂鸣（空缓冲区按 Backspace 等场景）
            reader.setVariable(LineReader.BELL_STYLE, "none");

            // ── Widget：/ 自动触发补全列表 ──
            reader.getWidgets().put("slash-auto-complete", () -> {
                reader.getBuffer().write('/');
                if ("/".equals(reader.getBuffer().toString().trim())) {
                    reader.callWidget(LineReader.COMPLETE_WORD);
                }
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("slash-auto-complete"), "/");

            // ── Widget：ESC 取消当前 AI 任务 ──
            reader.getWidgets().put("cancel-ai-task", () -> {
                if (taskRunning.get()) {
                    cancelRequested.set(true);
                    Disposable d = currentDisposable;
                    if (d != null && !d.isDisposed()) {
                        d.dispose();
                    }
                    // 清空待发送输入
                    int discarded = pendingInputs.size();
                    pendingInputs.clear();
                    if (discarded > 0) {
                        resetPrompt();
                    }
                }
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("cancel-ai-task"), KeyMap.esc());

            // ── Widget：Enter 智能提交 ──
            // AI 运行期间按 Enter 不让 readLine 返回，避免 ❯ xx 行打断输出流
            reader.getWidgets().put("smart-accept", () -> {
                String buf = reader.getBuffer().toString();
                String trimmed = buf.trim();

                // \ + Enter → 换行（对标 Claude Code）
                if (buf.endsWith("\\")) {
                    reader.getBuffer().delete(reader.getBuffer().length() - 1);
                    reader.getBuffer().write('\n');
                    return true;
                }

                if (taskRunning.get()) {
                    if (!trimmed.isEmpty()) {
                        pendingInputs.add(trimmed);
                        updatePromptWithPending();
                    }
                    reader.getBuffer().clear();
                    return true;
                }
                if (HITL.isHitl(currentSession)) {
                    reader.getBuffer().clear();
                    if (!trimmed.isEmpty()) {
                        handleHITLInput(trimmed);
                    }
                    return true;
                }
                if (trimmed.isEmpty()) {
                    return true;
                }
                reader.callWidget(LineReader.ACCEPT_LINE);
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("smart-accept"), "\r");
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("smart-accept"), "\n");

            // ── Widget：Ctrl+L 清屏 + 重绘状态栏 ──
            reader.getWidgets().put("clear-screen-redraw", () -> {
                terminal.puts(InfoCmp.Capability.clear_screen);
                terminal.flush();
                if (statusBar != null) {
                    statusBar.draw();
                }
                reader.callWidget(LineReader.REDRAW_LINE);
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("clear-screen-redraw"), KeyMap.ctrl('L'));

            // ── Widget：Ctrl+C — 中断生成 / 清空输入（对标 Claude Code）──
            reader.getWidgets().put("clear-input", () -> {
                if (taskRunning.get()) {
                    cancelRequested.set(true);
                    Disposable d = currentDisposable;
                    if (d != null && !d.isDisposed()) {
                        d.dispose();
                    }
                    pendingInputs.clear();
                }
                reader.getBuffer().clear();
                reader.callWidget(LineReader.REDISPLAY);
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("clear-input"), KeyMap.ctrl('C'));

        } catch (Throwable e) {
            LOG.error("JLine initialization failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 内置命令注册
    // ═══════════════════════════════════════════════════════════

    private void registerBuiltinCommands() {
        commandRegistry.register("/help", "显示帮助信息", ctx -> {
            printHelp();
        });

        commandRegistry.register("/exit", "退出程序", ctx -> {
            terminal.writer().println(DIM + "Exiting..." + RESET);
            terminal.flush();
            System.exit(0);
        });

        commandRegistry.register("/clear", "清空当前会话历史", ctx -> {
            AgentSession session = ctx.getSession();
            session.clear();
            terminal.puts(InfoCmp.Capability.clear_screen);
            terminal.flush();
            if (statusBar != null) {
                statusBar.draw();
            }
        });

        commandRegistry.register("/new", "开始新会话", ctx -> {
            // 新建临时 session，只在第一条消息时才持久化
            currentSession = agentRuntime.getSession("_tmp_" + System.currentTimeMillis());
            terminal.puts(InfoCmp.Capability.clear_screen);
            terminal.flush();
            if (statusBar != null) {
                statusBar.setSessionId("(new)");
                statusBar.draw();
            }
            terminal.writer().println(DIM + "  New session started." + RESET);
            terminal.flush();
        });

        commandRegistry.register("/resume", "恢复历史会话", ctx -> {
            String arg = ctx.getArg();

            if (arg != null && !arg.trim().isEmpty()) {
                resumeByIndex(arg.trim());
                return;
            }

            String cwd = agentRuntime.getProps().getWorkDir();
            List<SessionManager.SessionMeta> sessions = sessionManager.listSessions(cwd);
            if (sessions.isEmpty()) {
                terminal.writer().println(DIM + "  No sessions for this directory." + RESET);
                terminal.flush();
                return;
            }

            int selected = interactiveSelect(sessions);
            if (selected >= 0) {
                resumeByIndex(String.valueOf(selected + 1));
            } else {
                terminal.writer().println(DIM + "  Cancelled." + RESET);
                terminal.flush();
            }
        });

        commandRegistry.register("/model", "显示当前模型信息", ctx -> {
            String model = agentRuntime.getProps().getChatModel() != null
                    ? agentRuntime.getProps().getChatModel().getModel()
                    : "未配置";
            terminal.writer().println(DIM + "Model: " + RESET + BOLD + model + RESET);
            terminal.flush();
        });

        commandRegistry.register("/compact", "压缩当前会话上下文", ctx -> {
            terminal.writer().println(DIM + "  Compacting session context..." + RESET);
            terminal.flush();
            // TODO: 实际调用 summarization 压缩上下文
            terminal.writer().println(DIM + "  Session context compacted." + RESET);
            terminal.flush();
        });

        commandRegistry.register("/thinking", "切换思考内容显示", ctx -> {
            agentRuntime.getProps().setThinkPrinted(!agentRuntime.getProps().isThinkPrinted());
            String mode = agentRuntime.getProps().isThinkPrinted() ? "ON" : "OFF";
            terminal.writer().println(DIM + "  Thinking display: " + RESET + BOLD + mode + RESET);
            terminal.flush();
        });

        commandRegistry.register("/details", "切换工具调用详情显示", ctx -> {
            agentRuntime.getProps().setCliPrintSimplified(!agentRuntime.getProps().isCliPrintSimplified());
            String mode = agentRuntime.getProps().isCliPrintSimplified() ? "simplified" : "detailed";
            terminal.writer().println(DIM + "  Tool details: " + RESET + BOLD + mode + RESET);
            terminal.flush();
            if (statusBar != null) {
                statusBar.setCompactMode(agentRuntime.getProps().isCliPrintSimplified());
            }
        });

        commandRegistry.register("/statusbar", "配置状态栏显示内容", ctx -> {
            if (statusBar != null && !taskRunning.get()) {
                statusBar.showConfigUI();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    // 主循环 — readLine() 始终活跃
    // ═══════════════════════════════════════════════════════════

    private volatile AgentSession currentSession;
    private volatile Disposable currentDisposable;
    private final List<String> pendingInputs = new ArrayList<>();

    // Prompt style: RGB(255,125,144) bold for the ❯ icon
    private static final AttributedStyle PROMPT_STYLE = AttributedStyle.BOLD
            .foreground(255, 125, 144);

    /**
     * Build the normal prompt as AttributedString so JLine calculates width correctly
     */
    private AttributedString buildNormalPrompt() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append("\n");
        sb.styled(PROMPT_STYLE, ICON_PROMPT);
        sb.append(" ");
        return sb.toAttributedString();
    }

    /**
     * Build prompt with pending inputs list
     */
    private AttributedString buildPendingPrompt() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append("\n");
        for (String s : pendingInputs) {
            sb.styled(AttributedStyle.DEFAULT.faint(), "  \u25B8 " + s);
            sb.append("\n");
        }
        sb.styled(PROMPT_STYLE, ICON_PROMPT);
        sb.append(" ");
        return sb.toAttributedString();
    }

    /**
     * Update prompt with pending inputs — with REDRAW
     */
    private void updatePromptWithPending() {
        ((LineReaderImpl) reader).setPrompt(buildPendingPrompt().toAnsi(terminal));
        reader.callWidget(LineReader.REDRAW_LINE);
    }

    /**
     * Reset to normal prompt
     */
    private void resetPrompt() {
        ((LineReaderImpl) reader).setPrompt(buildNormalPrompt().toAnsi(terminal));
        reader.callWidget(LineReader.REDISPLAY);
    }

    @Override
    public void run() {
        // Windows 下将控制台切换为 UTF-8 代码页，避免中文输入乱码
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                Process process = new ProcessBuilder("cmd", "/c", "chcp", "65001").start();
                // 读取并丢弃输出，避免显示到控制台
                try (java.io.InputStream is = process.getInputStream()) {
                    while (is.read() != -1) {}
                }
                process.waitFor();
            } catch (Exception ignored) {
            }
        }

        printWelcome();
        // 启动时用临时 session，不持久化。第一条消息时才正式创建会话。
        currentSession = agentRuntime.getSession("_tmp_" + System.currentTimeMillis());

        while (true) {
            try {
                String input;

                try {
                    input = reader.readLine(buildNormalPrompt().toAnsi(terminal));
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (Assert.isEmpty(input))
                    continue;

                if (!isSystemCommand(currentSession, input)) {
                    // 延迟创建：第一条消息时才正式创建会话
                    ensureSessionCreated(input);

                    printAboveLine("\n" + ACCENT_BOLD + ICON_ASSISTANT + " Assistant" + RESET);
                    startAgentTask(currentSession, input);
                }
            } catch (Throwable e) {
                terminal.writer().println("\n" + ERROR_COLOR + ICON_CROSS + " Error: " + RESET + e.getMessage());
                terminal.flush();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AI 任务执行（完全异步）
    // ═══════════════════════════════════════════════════════════

    private void startAgentTask(AgentSession session, String input) {
        taskRunning.set(true);
        cancelRequested.set(false);
        thinkingStarted = false;

        // 状态栏：任务开始（taskStart 内部自动 draw）
        if (statusBar != null) {
            statusBar.incrementTurns();
            statusBar.taskStart();
        }

        final AtomicBoolean isFirstConversation = new AtomicBoolean(true);
        final AtomicBoolean isFirstReasonChunk = new AtomicBoolean(true);

        currentDisposable = agentRuntime.getRootAgent()
                .prompt(input)
                .session(session)
                .stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (cancelRequested.get())
                        return;

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
                        int discarded = pendingInputs.size();
                        if (discarded > 0) {
                            printAboveLine(DIM + "  (" + discarded + " 条待发送输入已丢弃)" + RESET);
                            pendingInputs.clear();
                            resetPrompt();
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
                    if (!pendingInputs.isEmpty() && !wasCancelled) {
                        // 显示每条待发送输入作为用户历史
                        for (String pi : pendingInputs) {
                            printAboveLine(ACCENT_BOLD + ICON_PROMPT + RESET + " " + pi);
                        }
                        String merged = String.join("\n", pendingInputs);
                        pendingInputs.clear();
                        resetPrompt();
                        printAboveLine("\n" + ACCENT_BOLD + ICON_ASSISTANT + " Assistant" + RESET);
                        startAgentTask(session, merged);
                    }
                })
                .subscribe();
    }

    // ═══════════════════════════════════════════════════════════
    // HITL 授权（异步：后台线程显示提示，主线程 readLine 输入）
    // ═══════════════════════════════════════════════════════════

    /**
     * 后台线程调用 — 通过 printAbove 显示 HITL 提示
     */
    private void showHITLPrompt(AgentSession session) {
        HITLTask task = HITL.getPendingTask(session);
        if (task == null)
            return;

        // 状态栏同步
        if (statusBar != null) {
            statusBar.updateStatus("⚠ awaiting approval");
        }

        printAboveLine("");
        printAboveLine(MUTED + "  " + repeatChar('\u2500', 20) + RESET);
        printAboveLine(WARN + "  " + ICON_WARN + " Permission Required" + RESET);
        if ("bash".equals(task.getToolName())) {
            printAboveLine(MUTED + "     Command: " + RESET + String.valueOf(task.getArgs().get("command")));
        } else {
            printAboveLine(MUTED + "     Tool: " + RESET + task.getToolName());
        }
        printAboveLine("");
        printAboveLine("     " + ACCENT_BOLD + ICON_CHECK + " allow" + RESET + MUTED + "    允许执行" + RESET);
        printAboveLine("     " + ERROR_COLOR + ICON_CROSS + " deny" + RESET + MUTED + "     拒绝执行" + RESET);
        printAboveLine(MUTED + "  " + repeatChar('\u2500', 20) + RESET);
        printAboveLine("");
    }

    /**
     * 主线程调用 — 处理用户在 readLine() 中输入的 HITL 选择
     */
    private void handleHITLInput(String input) {
        HITLTask task = HITL.getPendingTask(currentSession);
        if (task == null)
            return;

        String choice = input.trim().toLowerCase();
        if ("allow".equals(choice) || "y".equals(choice) || "yes".equals(choice) || "a".equals(choice)) {
            HITL.approve(currentSession, task.getToolName());
            printAboveLine(DIM + "  " + ICON_CHECK + " Approved" + RESET);
            // 继续 AI ReAct 循环
            startAgentTask(currentSession, null);
        } else {
            HITL.reject(currentSession, task.getToolName());
            printAboveLine(DIM + "  " + ICON_CROSS + " Rejected" + RESET);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 流式回调 — 全部通过行缓冲 + printAbove
    // ═══════════════════════════════════════════════════════════

    private void onFinalChunk(ReActChunk react, AtomicBoolean isFirstReasonChunk,
                              AtomicBoolean isFirstConversation) {
        if (react.isNormal() == false) {
            String delta = clearThink(react.getContent());
            onReasonChunkDo(delta, isFirstReasonChunk, isFirstConversation);
        }

        flushLineBuffer();
        mdRenderer.flush(); // 确保 Markdown 状态重置

        if (react.getTrace().getMetrics() != null) {
            long tokens = react.getTrace().getMetrics().getTotalTokens();
            String timeInfo = statusBar != null ? ", " + statusBar.getTaskTimeText() : "";
            printAboveLine(DIM + "  (" + tokens + " tokens" + timeInfo + ")" + RESET);
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
                // ── 思考内容：MUTED 色 + │ 左边线 ──
                if (!thinkingStarted) {
                    flushLineBuffer();
                    printAboveLine(MUTED + "  " + ICON_THINKING + " Thinking..." + RESET);
                    thinkingStarted = true;
                    thinkingLineStart = true;
                    // 状态栏：thinking（updateStatus 内部自动 draw）
                    if (statusBar != null) {
                        statusBar.updateStatus("⚙ thinking");
                    }
                }

                String delta = clearThink(reason.getContent());
                // 去掉前导空行
                if (thinkingLineStart) {
                    delta = delta.replaceAll("^[\\n\\r]+", "");
                }
                if (Assert.isNotEmpty(delta)) {
                    for (char ch : delta.toCharArray()) {
                        if (ch == '\n') {
                            flushLineBuffer();
                            thinkingLineStart = true;
                        } else if (ch != '\r') {
                            if (thinkingLineStart) {
                                appendToLineBuffer(MUTED + "  \u2502 ");
                                thinkingLineStart = false;
                            }
                            appendToLineBuffer(String.valueOf(ch));
                        }
                    }
                }
            } else {
                // ── 正常内容 ──
                if (thinkingStarted) {
                    flushLineBuffer();
                    printAboveLine(""); // thinking 和正文之间空一行
                    thinkingStarted = false;
                    // 状态栏：进入 responding（updateStatus 内部自动 draw）
                    if (statusBar != null) {
                        statusBar.updateStatus("✦ responding");
                    }
                }
                String delta = clearThink(reason.getContent());
                onReasonChunkDo(delta, isFirstReasonChunk, isFirstConversation);
            }
        }
    }

    private volatile boolean reasonAtLineStart = true;

    private void onReasonChunkDo(String delta, AtomicBoolean isFirstReasonChunk,
                                 AtomicBoolean isFirstConversation) {
        if (Assert.isNotEmpty(delta)) {
            if (isFirstReasonChunk.get()) {
                String trimmed = delta.replaceAll("^[\\s\\n]+", "");
                if (Assert.isNotEmpty(trimmed)) {
                    isFirstConversation.set(false);
                    isFirstReasonChunk.set(false);
                    mdRenderer.reset(); // 新回合重置渲染器
                    mdRenderer.feed(trimmed);
                }
            } else {
                mdRenderer.feed(delta);
            }
        }
    }

    private void onActionEndChunk(ActionEndChunk action, AtomicBoolean isFirstReasonChunk) {
        // 如果 thinking 还在进行，先结束
        if (thinkingStarted) {
            flushLineBuffer();
            thinkingStarted = false;
        }
        flushLineBuffer();

        if (Assert.isNotEmpty(action.getToolName())) {
            final String fullToolName;

            if (agentRuntime.getName().equals(action.getAgentName())) {
                fullToolName = action.getToolName();
            } else {
                fullToolName = action.getAgentName() + "/" + action.getToolName();
            }

            // 状态栏：工具调用（updateStatus 内部自动 draw）
            if (statusBar != null) {
                statusBar.updateStatus("⊙ " + fullToolName);
            }
            // 准备参数
            StringBuilder argsBuilder = new StringBuilder();
            Map<String, Object> args = action.getArgs();
            if (args != null && !args.isEmpty()) {
                args.forEach((k, v) -> {
                    if (argsBuilder.length() > 0)
                        argsBuilder.append(" ");
                    argsBuilder.append(k).append("=").append(v);
                });
            }
            String argsStr = argsBuilder.toString().replace("\n", " ");

            // 结果摘要
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

            if (agentRuntime.getProps().isCliPrintSimplified()) {
                // 简化模式 — 一行式
                String shortArgs = argsStr.length() > 40 ? argsStr.substring(0, 37) + "..." : argsStr;
                printAboveLine("");
                printAboveLine(SOFT + "  " + ICON_TOOL + " " + TEXT + BOLD + fullToolName + RESET
                        + " " + MUTED + shortArgs + " (" + summary + ")" + RESET);
            } else {
                // 详细模式 — 工具名 + 缩进参数（无边框）
                printAboveLine("");
                printAboveLine(SOFT + "  " + ICON_TOOL + " " + TEXT + BOLD
                        + fullToolName + RESET);

                // 参数
                if (args != null && !args.isEmpty()) {
                    args.forEach((k, v) -> {
                        String val = String.valueOf(v).trim().replace("\n", " ");
                        if (val.length() > 80) {
                            val = val.substring(0, 77) + "...";
                        }
                        printAboveLine(MUTED + "     " + k + ": " + val + RESET);
                    });
                }

                // 返回结果
                if (Assert.isNotEmpty(content)) {
                    printAboveLine("");
                    String[] contentLines = content.split("\n");
                    if (contentLines.length > 10) {
                        // 只显示首3行 + ... + 末1行
                        for (int i = 0; i < 3; i++) {
                            printAboveLine(MUTED + "     " + contentLines[i] + RESET);
                        }
                        printAboveLine(MUTED + "     ..." + RESET);
                        printAboveLine(MUTED + "     " + contentLines[contentLines.length - 1] + RESET);
                    } else {
                        for (String line : contentLines) {
                            printAboveLine(MUTED + "     " + line + RESET);
                        }
                    }
                }

                printAboveLine(MUTED + "     (" + summary + ")" + RESET);
            }

            isFirstReasonChunk.set(true);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 行缓冲 + printAbove 工具方法
    // ═══════════════════════════════════════════════════════════

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
     * 通过 printAbove 输出一整行（printAbove 内部用 JLine 的 ReentrantLock，跟 StatusBar.draw 共用同一把锁）
     */
    private void printAboveLine(String line) {
        if (reader != null) {
            reader.printAbove(line);
        } else {
            terminal.writer().println(line);
            terminal.flush();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private String clearThink(String chunk) {
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    /**
     * Interactive arrow-key selector. Returns selected index (0-based), or -1 if cancelled.
     */
    private int interactiveSelect(List<SessionManager.SessionMeta> sessions) {
        int selected = 0;
        int count = sessions.size();

        // Draw initial list
        drawSessionMenu(sessions, selected);

        try {
            while (true) {
                int ch = terminal.reader().read();
                if (ch == 27) { // ESC or arrow key sequence
                    // Check for arrow key: ESC [ A/B
                    int ch2 = terminal.reader().read();
                    if (ch2 == '[' || ch2 == 'O') {
                        int ch3 = terminal.reader().read();
                        if (ch3 == 'A') { // Up
                            selected = (selected - 1 + count) % count;
                            redrawSessionMenu(sessions, selected, count);
                            continue;
                        } else if (ch3 == 'B') { // Down
                            selected = (selected + 1) % count;
                            redrawSessionMenu(sessions, selected, count);
                            continue;
                        }
                    }
                    // Plain Esc — cancel
                    clearMenuLines(count + 3);
                    return -1;
                }
                if (ch == 3 || ch == -1) { // Ctrl+C / EOF
                    clearMenuLines(count + 3);
                    return -1;
                }
                if (ch == '\r' || ch == '\n') { // Enter — confirm
                    clearMenuLines(count + 3);
                    return selected;
                }
                if (ch == 'j') { // vim down
                    selected = (selected + 1) % count;
                    redrawSessionMenu(sessions, selected, count);
                }
                if (ch == 'k') { // vim up
                    selected = (selected - 1 + count) % count;
                    redrawSessionMenu(sessions, selected, count);
                }
                if (ch == 'q') { // quit
                    clearMenuLines(count + 3);
                    return -1;
                }
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private void drawSessionMenu(List<SessionManager.SessionMeta> sessions, int selected) {
        terminal.writer().println();
        terminal.writer().println(TEXT + BOLD + "  Sessions" + RESET + MUTED + "  (↑↓ select, Enter confirm, Esc cancel)" + RESET);
        terminal.writer().println();
        for (int i = 0; i < sessions.size(); i++) {
            terminal.writer().println(formatSessionItem(sessions.get(i), i, selected));
        }
        terminal.flush();
    }

    private void redrawSessionMenu(List<SessionManager.SessionMeta> sessions, int selected, int count) {
        // Move cursor up to overwrite the menu items
        for (int i = 0; i < count; i++) {
            terminal.writer().print("\033[A"); // cursor up
        }
        terminal.writer().print("\r"); // carriage return
        for (int i = 0; i < count; i++) {
            terminal.writer().println("\033[2K" + formatSessionItem(sessions.get(i), i, selected));
        }
        terminal.flush();
    }

    private String formatSessionItem(SessionManager.SessionMeta m, int index, int selected) {
        boolean isSel = (index == selected);
        String pointer = isSel ? ACCENT_BOLD + " > " + RESET : "   ";
        String titleColor = isSel ? TEXT + BOLD : SOFT;
        String title = m.title.isEmpty() ? "(untitled)" : m.title;
        String time = SessionManager.formatTime(m.updatedAt);
        String dir = m.cwd != null ? m.cwd : "";
        if (dir.length() > 30) dir = "..." + dir.substring(dir.length() - 27);
        return pointer + titleColor + title + RESET
                + MUTED + "  [" + time + "]  "
                + "(" + m.messageCount + " msgs)  " + dir + RESET;
    }

    private void clearMenuLines(int lines) {
        for (int i = 0; i < lines; i++) {
            terminal.writer().print("\033[A\033[2K"); // up + clear line
        }
        terminal.writer().print("\r");
        terminal.flush();
    }

    private void printSessionList(List<SessionManager.SessionMeta> sessions) {
        terminal.writer().println();
        terminal.writer().println(TEXT + BOLD + "  Sessions" + RESET);
        terminal.writer().println();
        int idx = 1;
        for (SessionManager.SessionMeta m : sessions) {
            boolean isCurrent = currentSession != null
                    && currentSession.getSessionId().equals(m.id);
            String marker = isCurrent ? ACCENT_BOLD + " * " + RESET : "   ";
            String title = m.title.isEmpty() ? "(untitled)" : m.title;
            String time = SessionManager.formatTime(m.updatedAt);
            String dir = m.cwd != null ? m.cwd : "";
            if (dir.length() > 30) dir = "..." + dir.substring(dir.length() - 27);
            terminal.writer().println(marker + ACCENT_BOLD + idx + RESET
                    + MUTED + "  [" + time + "]  " + RESET
                    + TEXT + title + RESET
                    + MUTED + "  (" + m.messageCount + " msgs)  " + dir + RESET);
            idx++;
        }
        terminal.writer().println();
        terminal.flush();
    }

    /**
     * Lazy session creation: only persist a session when actual conversation happens.
     * Temp sessions (id starts with _tmp_) are replaced with a real one on first message.
     */
    private void ensureSessionCreated(String firstMessage) {
        String sid = currentSession.getSessionId();
        if (sid.startsWith("_tmp_")) {
            // First real message — create a persistent session
            String newId = sessionManager.createSession(agentRuntime.getProps().getWorkDir());
            sessionManager.updateTitle(newId, firstMessage);
            currentSession = agentRuntime.getSession(newId);
            if (statusBar != null) {
                statusBar.setSessionId(newId);
                statusBar.draw();
            }
        } else {
            // Existing session — just update meta
            sessionManager.touch(sid);
        }
    }

    private void resumeByIndex(String input) {
        try {
            int idx = Integer.parseInt(input);
            List<SessionManager.SessionMeta> sessions = sessionManager.listSessions();
            if (idx < 1 || idx > sessions.size()) {
                terminal.writer().println(ERROR_COLOR + "  Invalid number: " + idx
                        + " (1-" + sessions.size() + ")" + RESET);
                terminal.flush();
                return;
            }
            SessionManager.SessionMeta meta = sessions.get(idx - 1);
            currentSession = agentRuntime.getSession(meta.id);
            terminal.puts(InfoCmp.Capability.clear_screen);
            terminal.flush();
            if (statusBar != null) {
                statusBar.setSessionId(meta.id);
                statusBar.draw();
            }
            String title = meta.title.isEmpty() ? meta.id : meta.title;
            terminal.writer().println(DIM + "  Resumed: " + RESET + TEXT + BOLD + title + RESET);
            terminal.flush();
        } catch (NumberFormatException e) {
            terminal.writer().println(ERROR_COLOR + "  Please enter a number." + RESET);
            terminal.flush();
        }
    }

    private boolean isSystemCommand(AgentSession session, String input) {
        String cmd = input.trim();

        if (cmd.startsWith("/")) {
            CommandRegistry.CommandContext ctx = new CommandRegistry.CommandContext(session, null);
            if (commandRegistry.execute(cmd, ctx)) {
                return true;
            }
            terminal.writer()
                    .println(ERROR_COLOR + "未知命令: " + RESET + cmd + MUTED + " (输入 /help 查看可用命令)" + RESET);
            terminal.flush();
            return true;
        }

        String lower = cmd.toLowerCase();
        if ("exit".equals(lower) || "clear".equals(lower)) {
            CommandRegistry.CommandContext ctx = new CommandRegistry.CommandContext(session, null);
            return commandRegistry.execute("/" + lower, ctx);
        }

        return false;
    }

    private void printHelp() {
        terminal.writer().println();
        terminal.writer().println(TEXT + BOLD + "  Commands" + RESET);
        terminal.writer().println();
        for (CommandRegistry.Command cmd : commandRegistry.getAllCommands()) {
            String name = cmd.getName();
            String padded = name + repeatChar(' ', Math.max(1, 16 - name.length()));
            terminal.writer()
                    .println("    " + ACCENT_BOLD + padded + RESET + MUTED + cmd.getDescription() + RESET);
        }
        terminal.writer().println();
        terminal.writer().println(TEXT + BOLD + "  Keybindings" + RESET);
        terminal.writer().println();
        terminal.writer().println(MUTED + "    Tab          " + RESET + SOFT + "Auto-complete /commands" + RESET);
        terminal.writer().println(MUTED + "    \\+Enter      " + RESET + SOFT + "Insert newline" + RESET);
        terminal.writer().println(MUTED + "    Esc          " + RESET + SOFT + "Cancel running task" + RESET);
        terminal.writer().println(MUTED + "    Ctrl+C       " + RESET + SOFT + "Cancel task / clear input" + RESET);
        terminal.writer().println(MUTED + "    Ctrl+D       " + RESET + SOFT + "Exit" + RESET);
        terminal.writer().println(MUTED + "    Ctrl+L       " + RESET + SOFT + "Clear screen" + RESET);
        terminal.writer().println();
        terminal.writer().println(MUTED + "  " + repeatChar('\u2500', 40) + RESET);
        terminal.writer().println();
        terminal.flush();
    }

    private static String repeatChar(char c, int count) {
        if (count <= 0)
            return "";
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // 欢迎界面（冻结 — 不允许改动）
    // ═══════════════════════════════════════════════════════════

    protected void printWelcome() {
        // 初始化状态栏
        this.statusBar = new StatusBar(terminal);
        String modelName = agentRuntime.getProps().getChatModel() != null
                ? agentRuntime.getProps().getChatModel().getModel()
                : "unknown";
        statusBar.setModelName(modelName);
        statusBar.setWorkDir(new File(agentRuntime.getProps().getWorkDir()).getAbsolutePath());
        statusBar.setVersion(agentRuntime.getVersion());
        statusBar.setSessionId(AgentRuntime.SESSION_DEFAULT);
        statusBar.setCompactMode(agentRuntime.getProps().isCliPrintSimplified());
        statusBar.setup();
        // 把 JLine 内部的 ReentrantLock 传给 StatusBar，确保 draw() 跟 printAbove() 用同一把锁
        try {
            java.lang.reflect.Field lockField = LineReaderImpl.class.getDeclaredField("lock");
            lockField.setAccessible(true);
            statusBar.setJLineLock((java.util.concurrent.locks.ReentrantLock) lockField.get(reader));
        } catch (Exception e) {
            LOG.warn("Cannot access JLine internal lock, statusbar animation may flicker", e);
        }

        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.flush();
        statusBar.draw(); // 清屏后重绘

        String path = new File(agentRuntime.getProps().getWorkDir()).getAbsolutePath();
        String version = agentRuntime.getVersion();

        // ── ASCII Art Logo (对齐 Go TUI renderWelcomeLogo) ──
        terminal.writer().println();
        terminal.writer().println(ACCENT_BOLD + "   ███████  ██████  ██      ██████  ███    ██" + RESET + SOFT + BOLD
                + "   ██████  ██████  ██████  ███████" + RESET);
        terminal.writer().println(ACCENT_BOLD + "   ██      ██    ██ ██     ██    ██ ████   ██" + RESET + SOFT + BOLD
                + "  ██      ██    ██ ██   ██ ██" + RESET);
        terminal.writer().println(ACCENT_BOLD + "   ███████ ██    ██ ██     ██    ██ ██ ██  ██" + RESET + SOFT + BOLD
                + "  ██      ██    ██ ██   ██ █████" + RESET);
        terminal.writer().println(ACCENT_BOLD + "        ██ ██    ██ ██     ██    ██ ██  ██ ██" + RESET + SOFT + BOLD
                + "  ██      ██    ██ ██   ██ ██" + RESET);
        terminal.writer().println(ACCENT_BOLD + "   ███████  ██████  ██████  ██████  ██   ████" + RESET + SOFT + BOLD
                + "   ██████  ██████  ██████  ███████" + RESET);
        terminal.writer().println();

        // ── Meta info ──
        String configSource = ConfigLoader.loadConfig() != null
                ? ConfigLoader.loadConfig().toAbsolutePath().toString()
                : "(built-in)";

        terminal.writer().println(SOFT + "  Model   " + RESET + TEXT + BOLD + modelName + RESET);
        terminal.writer().println(SOFT + "  Dir     " + RESET + SOFT + path + RESET);
        terminal.writer().println(SOFT + "  Config  " + RESET + SOFT + configSource + RESET);
        terminal.writer().println(SOFT + "  Ver     " + RESET + SOFT + version + RESET);
        terminal.writer().println();
        terminal.writer().println(MUTED + "  " + ICON_PROMPT + " " + RESET + ACCENT + "Tip" + RESET + SOFT + " Type "
                + RESET + TEXT + BOLD + "/help" + RESET + SOFT + " to see all commands" + RESET);
        terminal.writer().println(MUTED + "  " + ICON_PROMPT + " " + RESET + SOFT + "Use " + RESET + TEXT + BOLD + "Tab"
                + RESET + SOFT + " for auto-completion, " + RESET + TEXT + BOLD + "\\" + RESET + SOFT + "+Enter for newline" + RESET);
        terminal.writer().println(MUTED + "  " + ICON_PROMPT + " " + RESET + SOFT + "Press " + RESET + TEXT + BOLD
                + "Esc" + RESET + SOFT + "/" + RESET + TEXT + BOLD + "Ctrl+C" + RESET + SOFT + " to cancel operation" + RESET);
        terminal.writer().println();
        terminal.writer().println(MUTED + "  " + repeatChar('\u2500', 40) + RESET);
        terminal.writer().println();
        terminal.flush();
    }
}