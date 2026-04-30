package org.noear.solon.codecli.portal;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.PlanChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.core.util.Assert;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AcpLink implements Runnable {
    private final HarnessEngine agentRuntime;
    private final AcpAgentTransport agentTransport;
    private final AgentProperties agentProps;

    public AcpLink(HarnessEngine agentRuntime, AcpAgentTransport agentTransport, AgentProperties agentProps) {
        this.agentRuntime = agentRuntime;
        this.agentTransport = agentTransport;
        this.agentProps = agentProps;
    }

    private final Map<String, AcpSessionContext> sessionStates = new ConcurrentHashMap<>();

    public void run() {
        AcpAsyncAgent acpAgent = createAgent(agentTransport);

        acpAgent.start().subscribe();
    }

    public AcpAsyncAgent createAgent(AcpAgentTransport transport) {
        return AcpAgent.async(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializeHandler(req -> {
                    return Mono.just(new AcpSchema.InitializeResponse(
                            1,
                            new AcpSchema.AgentCapabilities(true,
                                    new AcpSchema.McpCapabilities(true, true),
                                    new AcpSchema.PromptCapabilities(true, true, true)),
                            Arrays.asList()
                    ));
                })
                .newSessionHandler(req -> {
                    String sessionId = UUID.randomUUID().toString();
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.NewSessionResponse(sessionId, null, null));
                })
                .loadSessionHandler(req -> {
                    String sessionId = req.sessionId();
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.LoadSessionResponse(null, null));
                })
                .cancelHandler(req -> {
                    String sessionId = req.sessionId();
                    AcpSessionContext context = sessionStates.get(sessionId);
                    if (context != null) {
                        context.setCancelled(true);
                    }
                    return Mono.empty();
                })
                .promptHandler((request, acpContext) -> {
                    String sessionId = request.sessionId();
                    AcpSessionContext context = sessionStates.get(sessionId);

                    Prompt userInput = toPrompt(request);
                    AgentSession session = agentRuntime.getSession(sessionId);

                    final long startTime = System.currentTimeMillis();

                    return agentRuntime.getMainAgent()
                            .prompt(userInput)
                            .session(session)
                            .options(o -> {
                                if (Assert.isNotEmpty(context.getCwd())) {
                                    o.toolContextPut(HarnessEngine.ATTR_CWD, context.getCwd());
                                }
                            })
                            .stream()
                            .concatMap(chunk -> {
                                // --- 规划阶段 ---
                                if (chunk instanceof PlanChunk) {
                                    return acpContext.sendUpdate(sessionId, new AcpSchema.AgentThoughtChunk(
                                                    "agent_thought_chunk",
                                                    new AcpSchema.TextContent("📋 [规划]: " + chunk.getContent())))
                                            .thenReturn(chunk);
                                }
                                // --- 思考阶段 ---
                                else if (chunk instanceof ReasonChunk) {
                                    ReasonChunk reasonChunk = (ReasonChunk) chunk;
                                    if (chunk.hasContent() && !reasonChunk.isToolCalls()) {
                                        if (agentProps.isThinkPrinted() || !reasonChunk.getMessage().isThinking()) {
                                            return acpContext.sendUpdate(sessionId, new AcpSchema.AgentThoughtChunk(
                                                            "agent_thought_chunk",
                                                            new AcpSchema.TextContent(chunk.getContent())))
                                                    .thenReturn(chunk);
                                        }
                                    }
                                }
                                // --- ThoughtChunk (多任务并行) ---
                                else if (chunk instanceof ThoughtChunk) {
                                    ThoughtChunk thoughtChunk = (ThoughtChunk) chunk;
                                    if (thoughtChunk.hasMeta("multitask")) {
                                        String content = thoughtChunk.getAssistantMessage().getResultContent();
                                        if (Assert.isNotEmpty(content)) {
                                            return acpContext.sendUpdate(sessionId, new AcpSchema.AgentThoughtChunk(
                                                            "agent_thought_chunk",
                                                            new AcpSchema.TextContent(content)))
                                                    .thenReturn(chunk);
                                        }
                                    }
                                }
                                // --- 工具执行阶段 (Action/Observation) ---
                                else if (chunk instanceof ActionEndChunk) {
                                    ActionEndChunk actionChunk = (ActionEndChunk) chunk;
                                    String toolName = actionChunk.getToolName();

                                    // 跳过内部工具
                                    if ("multitask".equals(toolName) || "task".equals(toolName)) {
                                        return Mono.just(chunk);
                                    }

                                    String content = chunk.getContent();
                                    String output;

                                    if (agentProps.isCliPrintSimplified()) {
                                        // 简化模式
                                        if (Assert.isNotEmpty(toolName)) {
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
                                            output = String.format("\n⚙️ [%s] (%s)", toolName, summary);
                                        } else {
                                            output = "\n⚙️ " + content;
                                        }
                                    } else {
                                        // 全量模式
                                        if (Assert.isNotEmpty(toolName)) {
                                            String argsStr = buildArgsStr(actionChunk.getArgs());
                                            if (argsStr.length() > 100) {
                                                output = String.format("\n⚙️ [%s]\n  Args: %s\n  Result: %s\n  (End of output)",
                                                        toolName, argsStr.substring(0, 97) + "...", content);
                                            } else {
                                                output = String.format("\n⚙️ [%s] Args: %s\n  Result: %s\n  (End of output)",
                                                        toolName, argsStr, content);
                                            }
                                        } else {
                                            output = "\n⚙️ " + content + "\n  (End of output)";
                                        }
                                    }

                                    return acpContext.sendUpdate(sessionId, new AcpSchema.AgentMessageChunk(
                                                    "agent_message_chunk",
                                                    new AcpSchema.TextContent(output)))
                                            .thenReturn(chunk);
                                }
                                // --- 最终回复阶段 ---
                                else if (chunk instanceof ReActChunk) {
                                    ReActChunk reActChunk = (ReActChunk) chunk;

                                    String finalContent = chunk.getContent();

                                    // 统计信息
                                    StringBuilder stats = new StringBuilder();
                                    if (reActChunk.getTrace() != null && reActChunk.getTrace().getMetrics() != null) {
                                        stats.append(reActChunk.getTrace().getMetrics().getTotalTokens()).append(" tokens");
                                    }
                                    long seconds = Duration.ofMillis(System.currentTimeMillis() - startTime).getSeconds();
                                    if (stats.length() > 0) {
                                        stats.append(", ");
                                    }
                                    stats.append(seconds).append(" seconds");

                                    String output = "\n----------------------\n" + finalContent + "\n  (" + stats + ")";

                                    return acpContext.sendUpdate(sessionId, new AcpSchema.AgentMessageChunk(
                                                    "agent_message_chunk",
                                                    new AcpSchema.TextContent(output)))
                                            .thenReturn(chunk);
                                }

                                return Mono.just(chunk);
                            })
                            .doFinally(signal -> {
                                sessionStates.remove(sessionId);
                            })
                            .onErrorResume(e -> {
                                acpContext.sendUpdate(request.sessionId(), new AcpSchema.AgentMessageChunk(
                                        "agent_message_chunk",
                                        new AcpSchema.TextContent("\n❌ 错误: " + e.getMessage())));
                                return Mono.empty();
                            })
                            .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
                })
                .build();
    }

    private String buildArgsStr(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(" ");
            sb.append(k).append("=").append(v);
        });
        return sb.toString().replace("\n", " ");
    }

    private String clearThink(String chunk) {
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    public Prompt toPrompt(AcpSchema.PromptRequest promptRequest) {
        Prompt prompt = Prompt.of();

        Contents contents = new Contents();

        for (AcpSchema.ContentBlock cp : promptRequest.prompt()) {
            if (cp instanceof AcpSchema.TextContent) {
                AcpSchema.TextContent text = (AcpSchema.TextContent) cp;

                contents.addBlock(TextBlock.of(text.text()));
            } else if (cp instanceof AcpSchema.ImageContent) {
                AcpSchema.ImageContent image = (AcpSchema.ImageContent) cp;

                if (Assert.isEmpty(image.uri())) {
                    contents.addBlock(ImageBlock.ofBase64(image.data(), image.mimeType()));
                } else {
                    contents.addBlock(ImageBlock.ofUrl(image.uri(), image.mimeType()));
                }
            }
        }

        return prompt.addMessage(ChatMessage.ofUser(contents));
    }

    public static class AcpSessionContext {
        private final String cwd;
        private final List<AcpSchema.McpServer> mcpServers;
        private volatile boolean cancelled;

        public AcpSessionContext(String cwd, List<AcpSchema.McpServer> mcpServers) {
            this.cwd = cwd;
            this.mcpServers = mcpServers;
        }

        public String getCwd() {
            return cwd;
        }

        public List<AcpSchema.McpServer> getMcpServers() {
            return mcpServers;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
