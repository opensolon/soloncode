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
package org.noear.solon.ai.codecli.impl;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.FileNameCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.summarize.*;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.cli.CliSkill;
import org.noear.solon.ai.skills.diff.DiffSkill;
import org.noear.solon.ai.skills.lucene.LuceneSkill;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class CodeCLI implements Handler, Runnable {
    private final static Logger LOG = LoggerFactory.getLogger(CodeCLI.class);
    private final static String SESSION_DEFAULT = "cli";

    private final ChatModel chatModel;
    private AgentSessionProvider sessionProvider;
    private String name = "CodeCLI";
    private String workDir = ".";
    private final Map<String, String> extraPools = new LinkedHashMap<>();
    private Consumer<ReActAgent.Builder> configurator;
    private boolean enableWeb = true;
    private boolean enableConsole = true;
    private boolean enableHitl = false;

    // JLine 3 终端与行读取器句柄
    private Terminal terminal;
    private LineReader reader;

    public CodeCLI(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 设置 Agent 名称 (同时也作为控制台输出前缀)
     */
    public CodeCLI name(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
        return this;
    }

    public CodeCLI workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public CodeCLI mountPool(String alias, String dir) {
        if (dir != null) {
            this.extraPools.put(alias, dir);
        }
        return this;
    }

    public CodeCLI session(AgentSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
        return this;
    }

    public CodeCLI config(Consumer<ReActAgent.Builder> configurator) {
        this.configurator = configurator;
        return this;
    }

    /**
     * 是否启用 Web 交互
     */
    public CodeCLI enableWeb(boolean enableWeb) {
        this.enableWeb = enableWeb;
        return this;
    }

    /**
     * 是否启用控制台交互
     */
    public CodeCLI enableConsole(boolean enableConsole) {
        this.enableConsole = enableConsole;
        return this;
    }

    /**
     * 是否启用 HITL 交互
     */
    public CodeCLI enableHitl(boolean enableHitl) {
        this.enableHitl = enableHitl;
        return this;
    }

    private ReActAgent agent;

    protected CliSkill getCliSkill(AgentSession session) {
        String boxId = session.getSessionId();

        return (CliSkill) session.attrs().computeIfAbsent("CliSkill", x -> {
            CliSkill skill = new CliSkill(boxId, workDir);
            extraPools.forEach(skill::mountPool);
            return skill;
        });
    }

    protected LuceneSkill getLuceneSkill(AgentSession session) {
        return (LuceneSkill) session.attrs().computeIfAbsent("LuceneSkill", x -> {
            return new LuceneSkill(workDir);
        });
    }

    protected DiffSkill getDiffSkill(AgentSession session) {
        return (DiffSkill) session.attrs().computeIfAbsent("DiffSkill", x -> {
            return new DiffSkill(workDir);
        });
    }

    protected void prepare() {
        if (agent == null) {
            if (sessionProvider == null) {
                Map<String, AgentSession> store = new ConcurrentHashMap<>();
                sessionProvider = (k) -> store.computeIfAbsent(k, InMemoryAgentSession::new);
            }

            ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel)
                    .role("你的名字叫 " + name + "。")
                    .instruction(
                            "你是一个具备深度工程能力的 AI 协作终端。请遵循以下准则：\n" +
                                    "1.【行动原则】：不要假设，要验证。修改前必读，交付前必测。\n" +
                                    "2.【权限边界】：写操作（创建/修改/删除）仅限在当前盒子（Box）路径内。严禁修改盒子外的文件。\n" +
                                    "3.【自主性】：bash 是你的核心工具，用于构建、测试及自动化任务。当内置工具不足时，应自主编写脚本解决。\n" +
                                    "4.【规范对齐】：遇到 @pool 路径时，必读其 SKILL.md；所有相对路径严禁使用 './' 前缀。\n" +
                                    "5.【交互风格】：资深工程师风格——简洁、直接、结果导向。避免 AI 废话。\n" +
                                    "6.【安全性】：保护环境安全，不泄露密钥，不访问盒子外的绝对路径。"
                    );

            //上下文摘要
            CompositeSummarizationStrategy compositeStrategy = new CompositeSummarizationStrategy();
            compositeStrategy.addStrategy(new KeyInfoExtractionStrategy(chatModel));
            compositeStrategy.addStrategy(new HierarchicalSummarizationStrategy(chatModel));
            SummarizationInterceptor summarizationInterceptor = new SummarizationInterceptor(12, compositeStrategy);

            agentBuilder.defaultInterceptorAdd(summarizationInterceptor);

            if (enableHitl) {
                agentBuilder.defaultInterceptorAdd(new HITLInterceptor()
                        .onTool("bash", new CodeHITLStrategy()));
            }

            if (configurator != null) {
                configurator.accept(agentBuilder);
            }

            agent = agentBuilder.build();

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
    }

    private ReActRequest buildRequest(String sessonId, Prompt prompt) {
        if (sessonId == null) {
            sessonId = SESSION_DEFAULT;
        }

        AgentSession session = sessionProvider.getSession(sessonId);

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.skillAdd(getCliSkill(session));
                    o.skillAdd(getLuceneSkill(session));
                    o.skillAdd(getDiffSkill(session));
                });
    }

    public Flux<AgentChunk> stream(String sessionId, Prompt prompt) {
        return buildRequest(sessionId, prompt)
                .stream();
    }

    public AgentResponse call(String sessionId, Prompt prompt) throws Throwable {
        return buildRequest(sessionId, prompt).call();
    }

    @Override
    public void handle(Context ctx) throws Throwable {
        if (!enableWeb) {
            ctx.status(404); // 如果未启用，直接返回 404
            return;
        }

        prepare();

        String input = ctx.param("input");
        String mode = ctx.param("m");
        String sessionId = ctx.headerOrDefault("X-Session-Id", SESSION_DEFAULT);

        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            ctx.status(400);
            ctx.output("Invalid Session ID");
            return;
        }

        if (Assert.isNotEmpty(input)) {
            if ("call".equals(mode)) {
                ctx.contentType(MimeType.TEXT_PLAIN_UTF8_VALUE);
                String result = call(sessionId, Prompt.of(input))
                        .getContent();

                ctx.output(result);
            } else {
                ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);


                Flux<String> stringFlux = stream(sessionId, Prompt.of(input))
                        .map(chunk -> {
                            if (chunk.hasContent()) {
                                if (chunk instanceof ReasonChunk) {
                                    return ONode.serialize(new Chunk("reason", chunk.getContent()));
                                } else if (chunk instanceof ActionChunk) {
                                    return ONode.serialize(new Chunk("action", chunk.getContent()));
                                } else if (chunk instanceof ReActChunk) {
                                    return ONode.serialize(new Chunk("agent", chunk.getContent()));
                                }
                            }

                            return "";
                        })
                        .filter(Assert::isNotEmpty)
                        .onErrorResume(e -> Flux.just(ONode.serialize(new Chunk("error", e.getMessage()))))
                        .concatWithValues("[DONE]");

                ctx.returnValue(stringFlux);
            }
        }
    }

    @Override
    public void run() {
        if (!enableConsole) {
            LOG.warn("SolonCodeCLI 控制台交互已禁用");
            return;
        }

        prepare();
        printWelcome();
        AgentSession session = sessionProvider.getSession("cli");

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
                    terminal.writer().print("\r" + name + ": "); // \r 清除当前的输入行
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

            reactor.core.Disposable disposable = stream(session.getSessionId(), Prompt.of(currentInput))
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
                            terminal.writer().println("\n" + GREEN + "━━ " + name + " 回复 ━━━━━━━━━━━━━━━━━━━━" + RESET);
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
            terminal.writer().println(CYAN + "🔍 正在构建工作区索引..." + RESET);
            terminal.flush();
            String result = getLuceneSkill(session).refreshIndex();
            terminal.writer().println(GREEN + "✅ " + result + RESET);
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
            absolutePath = new File(workDir).getCanonicalPath();
        } catch (Exception e) {
            absolutePath = new File(workDir).getAbsolutePath();
        }
        terminal.writer().println("==================================================");
        terminal.writer().println("🚀 " + name + " 已就绪");
        terminal.writer().println("--------------------------------------------------");
        terminal.writer().println("📂 工作空间: " + absolutePath);
        terminal.writer().println("💡 支持 Tab 补全、方向键历史记录");
        terminal.writer().println("🛑 输出时按回车(Enter)中断");
        terminal.writer().println("==================================================");
        terminal.flush();
    }

    public static class Chunk implements Serializable {
        public final String type;
        public final String text;

        public Chunk(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}