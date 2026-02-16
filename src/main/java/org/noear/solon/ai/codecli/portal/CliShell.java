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
package org.noear.solon.ai.codecli.portal;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.FileNameCompleter;
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
import org.noear.solon.ai.codecli.core.AgentNexus;
import org.noear.solon.ai.codecli.core.skills.CodeSkill;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class CliShell implements  Runnable {
    private final static Logger LOG = LoggerFactory.getLogger(CliShell.class);

    // JLine 3 终端与行读取器句柄
    private Terminal terminal;
    private LineReader reader;

    private final AgentNexus codeAgent;

    public CliShell(AgentNexus codeAgent) {
        this.codeAgent = codeAgent;

        // [优化点] 初始化 JLine 终端，启用文件名补全
        try {
            this.terminal = TerminalBuilder.builder()
                    .jna(true)    // 尝试使用 JNA 提升兼容性
                    .jansi(true)  // 尝试使用 Jansi 提升兼容性
                    .system(true)
                    .dumb(true)
                    .build();

            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new FileNameCompleter()) // 路径自动补全
                    .build();
        } catch (Exception e) {
            LOG.error("JLine 初始化失败", e);
        }
    }

    @Override
    public void run() {
        codeAgent.prepare();
        printWelcome();
        AgentSession session = codeAgent.getSession("cli");

        CodeSkill codeSkill = codeAgent.getCodeSkill(session);
        if(codeSkill.isSupported(null)) {
            terminal.writer().println(GRAY + "✨ 正在对齐项目规约与索引..." + RESET);
            codeAgent.init(session);
            // 只打印简要的一行，不破坏终端的美感
            terminal.writer().println(GRAY + "  ❯ 已就绪 (Project Contract & Indexing)" + RESET);
        }

        while (true) {
            try {
                // [优化点] 使用 JLine 的清理机制代替原始的 System.in 清理
                String promptStr = CYAN + "\uD83D\uDCBB > " + RESET;
                String input;
                try {
                    input = reader.readLine(promptStr); // 支持历史记录、Tab 补全
                } catch (UserInterruptException e) {
                    continue;
                } // Ctrl+C
                catch (EndOfFileException e) {
                    break;
                }      // Ctrl+D

                if (input == null || input.trim().isEmpty()) continue;

                if (isSystemCommand(session, input) == false) {
                    terminal.writer().print("\r" + codeAgent.getName() + ": "); // \r 清除当前的输入行
                    terminal.flush();

                    performAgentTask(session, input);

                    // 任务结束后，确保新的一行干净利落
                    terminal.writer().println();
                    terminal.flush();
                }
            } catch (Throwable e) {
                terminal.writer().println("\n" + RED + "[错误] " + RESET + e.getMessage());
            }
        }
    }

    final static String GRAY = "\033[90m", YELLOW = "\033[33m", GREEN = "\033[32m",
            RED = "\033[31m", CYAN = "\033[36m", RESET = "\033[0m";

    private void performAgentTask(AgentSession session, String input) throws Exception {
        String currentInput = input;
        boolean isSubmittingDecision = false;
        final AtomicBoolean isTaskCompleted = new AtomicBoolean(false);

        while (true) {
            CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean isInterrupted = new AtomicBoolean(false);
            final AtomicBoolean isFirstChunk = new AtomicBoolean(true);

            reactor.core.Disposable disposable = codeAgent.stream(session.getSessionId(), Prompt.of(currentInput))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(chunk -> {
                        if (chunk instanceof ReasonChunk) {
                            if (chunk.hasContent() && !((ReasonChunk) chunk).isToolCalls()) {
                                String content = clearThink(chunk.getContent());

                                // [核心优化] 消除首行空行：若是第一块内容，剔除其开头的换行和空格
                                if (isFirstChunk.get()) {
                                    content = content.replaceAll("^[\\s\\n]+", "");
                                    if (Assert.isNotEmpty(content)) {
                                        isFirstChunk.set(false);
                                    }
                                }

                                if (Assert.isNotEmpty(content)) {
                                    terminal.writer().print(GRAY + content + RESET);
                                    terminal.flush();
                                }
                            }
                        } else if (chunk instanceof ActionChunk) {
                            ActionChunk actionChunk = (ActionChunk) chunk;
                            if (Assert.isNotEmpty(actionChunk.getToolName())) {
                                if (!isFirstChunk.get()) {
                                    terminal.writer().println();
                                }
                                terminal.writer().println(YELLOW + " ❯ " + actionChunk.getToolName() + RESET);

                                if (Assert.isNotEmpty(chunk.getContent())) {
                                    terminal.writer().println(GRAY + "   " + chunk.getContent().replace("\n", "\n   ") + RESET);
                                }
                                isFirstChunk.set(false);
                                terminal.flush();
                            }
                        } else if (chunk instanceof ReActChunk) {
                            isTaskCompleted.set(true);

                            ReActChunk reActChunk = (ReActChunk) chunk;
                            terminal.writer().println("\n" + GREEN + "━━ " + codeAgent.getName() + " 回复 ━━━━━━━━━━━━━━━━━━━━" + RESET);
                            String finalContent = chunk.getContent();
                            if (finalContent != null) {
                                terminal.writer().println(finalContent.replaceAll("^[\\s\\n]+", ""));
                            }

                            if (reActChunk.getTrace().getMetrics() != null) {
                                long total = reActChunk.getTrace().getMetrics().getTotalTokens();
                                long prompt = reActChunk.getTrace().getMetrics().getPromptTokens();
                                long completion = reActChunk.getTrace().getMetrics().getCompletionTokens();
                                // 使用调色盘中的灰色 (GRAY) 打印，保持低调不干扰视觉
                                terminal.writer().println(GRAY + String.format(" tokens: %d (in: %d, out: %d)", total, prompt, completion) + RESET);
                            }

                            terminal.flush();
                            isFirstChunk.set(false);
                        }
                    })
                    .doOnError(e -> {
                        terminal.writer().println();
                        terminal.writer().println(RED + "[ERROR] 任务执行异常: " + e.getMessage() + RESET);
                        isTaskCompleted.set(true);
                    })
                    .doFinally(signal -> {
                        terminal.writer().println();
                        terminal.flush();
                        latch.countDown();
                    })
                    .subscribe();

            if (isSubmittingDecision) {
                Thread.sleep(100);
                isSubmittingDecision = false;
            }

            // 阻塞监控：监听键盘中断和 HITL
            while (latch.getCount() > 0) {
                if (terminal.reader().peek(10) != -2) {
                    int c = terminal.reader().read();
                    if (c == '\r' || c == '\n') {
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
                Thread.sleep(30);
            }
            latch.await();

            if (isInterrupted.get()) {
                terminal.writer().println(YELLOW + "\n[已中断]" + RESET);
                session.addMessage(ChatMessage.ofAssistant("【执行摘要】：该任务已被用户手动中断。"));
                return;
            }

            // HITL 交互处理
            if (HITL.isHitl(session)) {
                HITLTask task = HITL.getPendingTask(session);
                terminal.writer().println("\n" + RED + " ⚠ 需要授权 " + RESET);
                if (Assert.isNotEmpty(task.getComment())) {
                    terminal.writer().println(GRAY + "   原因: " + task.getComment() + RESET);
                }
                if ("bash".equals(task.getToolName())) {
                    terminal.writer().println(CYAN + "   执行: " + RESET + task.getArgs().get("command"));
                }

                String choice = reader.readLine(GREEN + "   确认执行？(y/n) " + RESET).trim().toLowerCase();

                if (choice.equals("y") || choice.equals("yes")) {
                    HITL.approve(session, task.getToolName());
                } else {
                    terminal.writer().println(RED + "   已拒绝操作。" + RESET);
                    HITL.reject(session, task.getToolName());
                }

                currentInput = null;
                isSubmittingDecision = true;
                continue;
            }

            if (isTaskCompleted.get()) {
                terminal.writer().flush();
                return;
            }
            break;
        }
    }

    private String clearThink(String chunk) {
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    private void cleanInputBuffer() throws Exception {
        // [优化点] 使用 terminal 刷新代替原始 sleep
        terminal.flush();
    }

    private boolean isSystemCommand(AgentSession session, String input) {
        String cmd = input.trim().toLowerCase();
        if ("exit".equals(cmd) || "quit".equals(cmd)) {
            terminal.writer().println("再见！");
            System.exit(0);
            return true;
        }

        if ("init".equals(cmd)) {
            terminal.writer().println(CYAN + "🏗️  正在初始化工作空间 (Pool-Box)..." + RESET);
            terminal.flush();

            // 直接调用核心层封装
            String result = codeAgent.init(session);

            // 格式化输出
            for (String line : result.split("\n")) {
                terminal.writer().println(GRAY + "  ❯ " + line + RESET);
            }
            terminal.writer().println(GREEN + "✅ 初始化完成！" + RESET);
            return true;
        }

        if ("clear".equals(cmd)) {
            terminal.puts(InfoCmp.Capability.clear_screen);
            return false;
        }
        return false;
    }

    protected void printWelcome() {
        String absolutePath;
        try {
            absolutePath = new File(codeAgent.getWorkDir()).getCanonicalPath();
        } catch (Exception e) {
            absolutePath = new File(codeAgent.getWorkDir()).getAbsolutePath();
        }
        terminal.writer().println("==================================================");
        terminal.writer().println("🚀 " + codeAgent.getName() + " 已就绪");
        terminal.writer().println("--------------------------------------------------");
        terminal.writer().println("📂 工作空间: " + absolutePath);
        terminal.writer().println("💡 支持 Tab 补全、方向键历史记录");
        terminal.writer().println("🛑 输出时按回车(Enter)中断");
        terminal.writer().println("==================================================");
        terminal.flush();
    }
}