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
package org.noear.solon.bot.codecli.portal;

import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.FileNameCompleter;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.bot.codecli.portal.ui.CommandRegistry;
import org.noear.solon.bot.codecli.portal.ui.SlashCommandCompleter;
import org.noear.solon.bot.codecli.portal.ui.MarkdownRenderer;
import org.noear.solon.bot.codecli.portal.ui.StatusBar;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.File;
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
    private final AgentKernel kernel;
    private final CommandRegistry commandRegistry;
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

    public CliShellNew(AgentKernel kernel) {
        this.kernel = kernel;
        this.commandRegistry = new CommandRegistry();
        registerBuiltinCommands();

        try {
            this.terminal = TerminalBuilder.builder()
                    .jna(true).jansi(true).system(true)
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
                    } catch (Exception ignored) {
                    }
                }
            });

            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new AggregateCompleter(
                            new SlashCommandCompleter(commandRegistry),
                            new FileNameCompleter()))
                    .option(LineReader.Option.AUTO_LIST, true)
                    .option(LineReader.Option.AUTO_MENU, true)
                    .option(LineReader.Option.AUTO_MENU_LIST, true)
                    .option(LineReader.Option.MENU_COMPLETE, true)
                    .option(LineReader.Option.LIST_PACKED, true)
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

            // ── Widget：/ 自动触发补全 ──
            reader.getWidgets().put("slash-auto-complete", () -> {
                reader.getBuffer().write('/');
                if ("/".equals(reader.getBuffer().toString().trim())) {
                    reader.runMacro("\t");
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
                String buf = reader.getBuffer().toString().trim();
                if (taskRunning.get()) {
                    if (!buf.isEmpty()) {
                        pendingInputs.add(buf);
                        updatePromptWithPending();
                    }
                    reader.getBuffer().clear();
                    return true; // 消费 Enter，不让 readLine 返回
                }
                // HITL 状态下也拦截
                if (HITL.isHitl(currentSession)) {
                    reader.getBuffer().clear();
                    if (!buf.isEmpty()) {
                        handleHITLInput(buf);
                    }
                    return true;
                }
                // 正常情况：空输入不提交
                if (buf.isEmpty()) {
                    return true; // 消费 Enter，不做任何事
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

            // ── Widget：Ctrl+C 清空当前输入（不产生历史记录）──
            reader.getWidgets().put("clear-input", () -> {
                // 用 kill-whole-line 清空并视觉更新
                reader.getBuffer().clear();
                reader.callWidget(LineReader.REDISPLAY);
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("clear-input"), KeyMap.ctrl('C'));

        } catch (Exception e) {
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

        commandRegistry.register("/init", "重新初始化代码索引", ctx -> {
            AgentSession session = ctx.getSession();
            String result = kernel.init(session);
            terminal.writer().println(DIM + result + RESET);
            terminal.flush();
        });

        commandRegistry.register("/clear", "清空会话历史", ctx -> {
            AgentSession session = ctx.getSession();
            session.clear();
            // 只清屏，不重新创建 StatusBar（Status 是终端单例）
            terminal.puts(InfoCmp.Capability.clear_screen);
            terminal.flush();
            if (statusBar != null) {
                statusBar.draw();
            }
        });

        commandRegistry.register("/model", "显示当前模型信息", ctx -> {
            String model = kernel.getProps().getChatModel() != null
                    ? kernel.getProps().getChatModel().getModel()
                    : "未配置";
            terminal.writer().println(DIM + "Model: " + RESET + BOLD + model + RESET);
            terminal.flush();
        });

        commandRegistry.register("/compact", "切换精简/详细输出模式", ctx -> {
            kernel.getProps().setCliPrintSimplified(!kernel.getProps().isCliPrintSimplified());
            String mode = kernel.getProps().isCliPrintSimplified() ? "精简模式" : "详细模式";
            terminal.writer().println(DIM + "已切换为: " + RESET + BOLD + mode + RESET);
            terminal.flush();
            if (statusBar != null) {
                statusBar.setCompactMode(kernel.getProps().isCliPrintSimplified());
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
    private final List<String> pendingInputs = new ArrayList<>(); // AI 运行期间收集的用户输入
    private final String normalPrompt = "\n" + ACCENT_BOLD + ICON_PROMPT + RESET + " ";

    /** 构建带待发送列表的 prompt */
    private String buildPrompt() {
        StringBuilder p = new StringBuilder("\n");
        for (String s : pendingInputs) {
            p.append(DIM + "  \u25B8 " + s + RESET + "\n");
        }
        p.append(ACCENT_BOLD + ICON_PROMPT + RESET + " ");
        return p.toString();
    }

    /** 更新 prompt（含待发送） — 带 REDRAW */
    private void updatePromptWithPending() {
        ((LineReaderImpl) reader).setPrompt(buildPrompt());
        reader.callWidget(LineReader.REDRAW_LINE);
    }

    /** 恢复正常 prompt（用 REDISPLAY 彻底刷新，清除多余行） */
    private void resetPrompt() {
        ((LineReaderImpl) reader).setPrompt(normalPrompt);
        reader.callWidget(LineReader.REDISPLAY);
    }

    @Override
    public void run() {
        printWelcome();
        currentSession = kernel.getSession("cli");
        kernel.init(currentSession);

        while (true) {
            try {
                String input;

                try {
                    input = reader.readLine(normalPrompt);
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (Assert.isEmpty(input))
                    continue;

                if (!isSystemCommand(currentSession, input)) {
                    // readLine 已经回显了 ❯ input，不需要再 printAbove
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

        currentDisposable = kernel.stream(session.getSessionId(), Prompt.of(input))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (cancelRequested.get())
                        return;

                    if (chunk instanceof ReasonChunk) {
                        onReasonChunk((ReasonChunk) chunk, isFirstReasonChunk, isFirstConversation);
                    } else if (chunk instanceof ActionChunk) {
                        onActionChunk((ActionChunk) chunk, isFirstReasonChunk);
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

    /** 后台线程调用 — 通过 printAbove 显示 HITL 提示 */
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

    /** 主线程调用 — 处理用户在 readLine() 中输入的 HITL 选择 */
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

    private void onActionChunk(ActionChunk action, AtomicBoolean isFirstReasonChunk) {
        // 如果 thinking 还在进行，先结束
        if (thinkingStarted) {
            flushLineBuffer();
            thinkingStarted = false;
        }
        flushLineBuffer();

        if (Assert.isNotEmpty(action.getToolName())) {
            final String fullToolName;

            if(kernel.getProps().isSubagentEnabled()) {
                fullToolName = action.getAgentName() + "/" + action.getToolName();
            } else {
                fullToolName = action.getToolName();
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

            if (kernel.getProps().isCliPrintSimplified()) {
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

    /** 向行缓冲追加内容（不立即输出） */
    private void appendToLineBuffer(String text) {
        synchronized (lineBuffer) {
            lineBuffer.append(text);
        }
    }

    /** 将行缓冲内容 flush 到 printAbove（一整行） */
    private void flushLineBuffer() {
        synchronized (lineBuffer) {
            if (lineBuffer.length() > 0) {
                printAboveLine(lineBuffer.toString() + RESET);
                lineBuffer.setLength(0);
            }
        }
    }

    /** 通过 printAbove 输出一整行（线程安全 — 与状态栏 draw 同步） */
    private void printAboveLine(String line) {
        synchronized (terminal) {
            if (reader != null) {
                reader.printAbove(line);
            } else {
                terminal.writer().println(line);
                terminal.flush();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private String clearThink(String chunk) {
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
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
        if ("exit".equals(lower) || "init".equals(lower) || "clear".equals(lower)) {
            CommandRegistry.CommandContext ctx = new CommandRegistry.CommandContext(session, null);
            return commandRegistry.execute("/" + lower, ctx);
        }

        return false;
    }

    private void printHelp() {
        terminal.writer().println();
        terminal.writer().println(TEXT + BOLD + "  可用命令" + RESET);
        terminal.writer().println();
        for (CommandRegistry.Command cmd : commandRegistry.getAllCommands()) {
            String name = cmd.getName();
            String padded = name + repeatChar(' ', Math.max(1, 14 - name.length()));
            terminal.writer()
                    .println("    " + ACCENT_BOLD + padded + RESET + MUTED + cmd.getDescription() + RESET);
        }
        terminal.writer().println();
        terminal.writer().println(MUTED + "  快捷键" + RESET);
        terminal.writer().println(MUTED + "    Esc     中断当前操作" + RESET);
        terminal.writer().println(MUTED + "    Tab     自动补全命令" + RESET);
        terminal.writer().println(MUTED + "    Ctrl+C  取消当前输入" + RESET);
        terminal.writer().println(MUTED + "    Ctrl+D  退出程序" + RESET);
        terminal.writer().println(MUTED + "    Ctrl+L  清屏" + RESET);
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
        String modelName = kernel.getProps().getChatModel() != null
                ? kernel.getProps().getChatModel().getModel()
                : "unknown";
        statusBar.setModelName(modelName);
        statusBar.setWorkDir(new File(kernel.getProps().getWorkDir()).getAbsolutePath());
        statusBar.setVersion(kernel.getVersion());
        statusBar.setSessionId("cli");
        statusBar.setCompactMode(kernel.getProps().isCliPrintSimplified());
        statusBar.setup();

        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.flush();
        statusBar.draw(); // 清屏后重绘

        String path = new File(kernel.getProps().getWorkDir()).getAbsolutePath();
        String version = kernel.getVersion();

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
        terminal.writer().println(SOFT + "  Model  " + RESET + TEXT + BOLD + modelName + RESET);
        terminal.writer().println(SOFT + "  Dir    " + RESET + SOFT + path + RESET);
        terminal.writer().println(SOFT + "  Ver    " + RESET + SOFT + version + RESET);
        terminal.writer().println();
        terminal.writer().println(MUTED + "  " + ICON_PROMPT + " " + RESET + ACCENT + "Tip" + RESET + SOFT + " Type "
                + RESET + TEXT + BOLD + "/help" + RESET + SOFT + " to see all commands" + RESET);
        terminal.writer().println(MUTED + "  " + ICON_PROMPT + " " + RESET + SOFT + "Use " + RESET + TEXT + BOLD + "Tab"
                + RESET + SOFT + " for auto-completion" + RESET);
        terminal.writer().println(MUTED + "  " + ICON_PROMPT + " " + RESET + SOFT + "Press " + RESET + TEXT + BOLD
                + "Esc" + RESET + SOFT + " to cancel operation" + RESET);
        terminal.writer().println();
        terminal.writer().println(MUTED + "  " + repeatChar('\u2500', 40) + RESET);
        terminal.writer().println();
        terminal.flush();
    }
}