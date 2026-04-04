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
package org.noear.solon.codecli.remoting;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskSkill;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * Code CLI WebSocket 网关
 * <p>基于 WebSocket 的流式通信接口</p>
 *
 * @author bai
 * @since 3.9.1
 */

public class WebSocketGate extends SimpleWebSocketListener {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketGate.class);
    private final HarnessEngine kernel;
    private final AgentProperties agentPros;

    public WebSocketGate(HarnessEngine kernel, AgentProperties agentPros) {
        this.kernel = kernel;
        this.agentPros = agentPros;
    }

    @Override
    public void onOpen(WebSocket socket) {
        String sessionId = socket.paramOrDefault("sessionId", agentPros.getSessionId());
        String sessionCwd = socket.param(AgentProperties.X_SESSION_CWD);//工作区

        if (Assert.isNotEmpty(sessionId)) {
            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                socket.send("{\"type\":\"error\",\"text\":\"Invalid Session ID\"}");
                socket.close();
                return;
            }
        }

        if (Assert.isNotEmpty(sessionCwd)) {
            if (sessionCwd.contains("..")) {
                socket.send("{\"type\":\"error\",\"text\":\"Invalid Session Cwd\"}");
                socket.close();
                return;
            }

            AgentSession session = kernel.getSession(sessionId);
            session.attrs().putIfAbsent(HarnessEngine.ATTR_CWD, sessionCwd);
        }
    }

    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        try {
            // 解析请求
            ChatMessage req = ONode.ofJson(text).toBean(ChatMessage.class);
            String sessionId = req.getSessionId();
            String input = req.getInput();
            String cwd = req.getCwd();

            if (Assert.isEmpty(sessionId)) {
                sessionId = "ws_" + System.currentTimeMillis();
                // 及时通知客户端自动生成的 sessionId
                socket.send(new ONode().set("type", "session")
                        .set("sessionId", req.getSessionId())
                        .toJson());
            }

            AgentSession session = kernel.getSession(sessionId);

            if (Assert.isEmpty(req.getCwd())) {
                cwd = session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, ".").toString();
            }


            // 验证 sessionId
            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                socket.send(new ONode().set("type", "error")
                        .set("text", "Invalid Session ID").toJson());
                return;
            }

            // 验证 cwd
            if (Assert.isNotEmpty(cwd)) {
                if (cwd.contains("..")) {
                    socket.send(new ONode().set("type", "error")
                            .set("text", "Invalid Session Cwd").toJson());
                    return;
                }
            }

            if (Assert.isEmpty(input)) {
                return;
            }

            final String finalInput;
            if (Assert.isNotEmpty(req.getContext())) {
                finalInput = input + "<context>" + req.getContext() + "</context>";
            } else {
                finalInput = input;
            }


            // 记录开始时间
            final long startTime = System.currentTimeMillis();

            // 用于收集 metrics 的容器
            final long[] totalTokens = {0};
            final String[] modelName = {""};

            // 流式处理
            final String finalSessionId = sessionId;

            String finalCwd = cwd;
            Flux<String> stringFlux = kernel.getMainAgent()
                    .prompt(finalInput)
                    .session(session)
                    .options(o -> {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, finalCwd);
                    })
                    .stream()
                    .map(chunk -> {
                        String chunkType = chunk.getClass().getSimpleName();
                        LOG.debug("[WS] chunk: type={}, hasContent={}, isNormal={}",
                                chunkType,
                                chunk.hasContent(),
                                chunk instanceof ReActChunk ? ((ReActChunk) chunk).isNormal() : "N/A");

                        // ReActChunk 需要优先处理 metrics 收集（无论 hasContent 状态）
                        if (chunk instanceof ReActChunk) {
                            return onReActChunk((ReActChunk) chunk, finalSessionId, totalTokens, modelName);
                        } else if (chunk instanceof ReasonChunk) {
                            return onReasonChunk((ReasonChunk) chunk, finalSessionId);
                        } else if (chunk instanceof ActionEndChunk) {
                            return onActionEndChunk((ActionEndChunk) chunk, finalSessionId);
                        } else if (chunk instanceof ThoughtChunk) {
                            return onThoughtChunk((ThoughtChunk) chunk, finalSessionId);
                        }

                        return "";
                    })
                    .filter(Assert::isNotEmpty)
                    .onErrorResume(e -> {
                        String message = new ONode().set("type", "error")
                                .set("sessionId", finalSessionId)
                                .set("text", e.getMessage())
                                .toJson();

                        return Flux.just(message);
                    })
                    .concatWithValues(new ONode().set("type", "done")
                            .set("sessionId", finalSessionId)
                            .set("modelName", modelName[0])
                            .set("totalTokens", totalTokens[0])
                            .set("elapsedMs", System.currentTimeMillis() - startTime).toJson());

            // 订阅并发送
            stringFlux.subscribe(msg -> {
                try {
                    socket.send(msg);
                } catch (Exception e) {
                    // 连接可能已关闭
                }
            });

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            socket.send(new ONode().set("type", "error")
                    .set("text", errorMsg).toJson());
        }
    }

    private String onReActChunk(ReActChunk chunk, String finalSessionId, long[] totalTokens, String[] modelName) {
        // 收集 metrics 信息（无论 isNormal 和 hasContent 状态，trace 都可能携带 metrics）
        if (chunk.getTrace() != null) {
            if (chunk.getTrace().getMetrics() != null) {
                totalTokens[0] = chunk.getTrace().getMetrics().getTotalTokens();
            }
            if (chunk.getTrace().getConfig() != null && chunk.getTrace().getConfig().getChatModel() != null) {
                modelName[0] = chunk.getTrace().getConfig().getChatModel().toString();
            }
        }

        // 参考 CLI 的 CliShellNew.onFinalChunk 逻辑：
        // - isNormal==false: 内容通过 reason 类型发送（和 ReasonChunk 一样处理）
        // - isNormal==true: 这是最终汇总，内容已经通过 ReasonChunk 发送过了，跳过避免重复
        if (!chunk.isNormal() && chunk.hasContent()) {
            LOG.debug("[WS] sending reason from ReActChunk: {}",
                    chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())));
            return new ONode().set("type", "reason")
                    .set("sessionId", finalSessionId)
                    .set("text", chunk.getContent())
                    .toJson();
        }

        // isNormal==true 或无内容时，内容已通过 ReasonChunk 完整发送，此处跳过
        LOG.debug("[WS] skipping ReActChunk (isNormal={}, hasContent={})",
                chunk.isNormal(), chunk.hasContent());
        return "";
    }

    private String onReasonChunk(ReasonChunk chunk, String finalSessionId) {
        if (chunk.hasContent()) {
            if (!chunk.isToolCalls() && chunk.hasContent()) {
                // 检查是否是 thinking 内容
                boolean isThinking = chunk.getMessage() != null && chunk.getMessage().isThinking();
                String chunkTypeToSend = isThinking ? "think" : "reason";

                LOG.debug("[WS] sending {}: {}", chunkTypeToSend,
                        chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())));
                return new ONode().set("type", chunkTypeToSend)
                        .set("sessionId", finalSessionId)
                        .set("text", chunk.getContent())
                        .toJson();
            }
        }

        return "";
    }

    private String onActionEndChunk(ActionEndChunk chunk, String finalSessionId) {
        if (chunk.hasContent()) {
            ONode oNode = new ONode().set("type", "action")
                    .set("sessionId", finalSessionId)
                    .set("text", chunk.getContent());

            if (Assert.isNotEmpty(chunk.getToolName())) {
                if ("main".equals(chunk.getAgentName())) {
                    oNode.set("toolName", chunk.getToolName());
                } else {
                    oNode.set("toolName", chunk.getAgentName() + "/" + chunk.getToolName());
                }
                oNode.set("args", chunk.getArgs());
            }

            return oNode.toJson();
        }

        return "";
    }

    private String onThoughtChunk(ThoughtChunk chunk, String finalSessionId) {
        if (chunk.hasMeta(TaskSkill.TOOL_MULTITASK)) {
            // 仅在多任务并行且有内容时输出
            String content = chunk.getAssistantMessage().getResultContent();
            if (Assert.isNotEmpty(content)) {
                // 检查是否是 thinking 内容
                boolean isThinking = chunk.getMessage() != null && chunk.getMessage().isThinking();
                String chunkTypeToSend = isThinking ? "think" : "reason";

                LOG.debug("[WS] sending {}: {}", chunkTypeToSend,
                        chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())));
                return new ONode().set("type", chunkTypeToSend)
                        .set("sessionId", finalSessionId)
                        .set("text", chunk.getContent())
                        .toJson();
            }
        }

        return "";
    }
}