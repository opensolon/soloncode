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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.codecli.core.AgentRuntime;
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
    private final AgentRuntime kernel;

    public WebSocketGate(AgentRuntime kernel) {
        this.kernel = kernel;
    }

    @Override
    public void onOpen(WebSocket socket) {
        // 可以在这里做认证
        String sessionId = socket.param("sessionId");
        String sessionCwd = socket.param("cwd");

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
            session.attrs().putIfAbsent(AgentRuntime.ATTR_CWD, sessionCwd);
        }
    }

    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        try {
            // 解析请求
            ONode req = ONode.ofJson(text);
            String input = req.get("input").getString();
            String sessionId = req.get("sessionId").getString();
            String sessionCwd = req.get("cwd").getString();

            if (Assert.isEmpty(sessionId)) {
                sessionId = "ws_" + System.currentTimeMillis();
            }

            // 验证 sessionId
            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                socket.send("{\"type\":\"error\",\"text\":\"Invalid Session ID\"}");
                return;
            }

            // 验证 cwd
            if (Assert.isNotEmpty(sessionCwd)) {
                if (sessionCwd.contains("..")) {
                    socket.send("{\"type\":\"error\",\"text\":\"Invalid Session Cwd\"}");
                    return;
                }

                AgentSession session = kernel.getSession(sessionId);
                session.attrs().putIfAbsent(AgentRuntime.ATTR_CWD, sessionCwd);
            }

            if (Assert.isEmpty(input)) {
                return;
            }

            // 记录开始时间
            final long startTime = System.currentTimeMillis();

            // 用于收集 metrics 的容器
            final long[] totalTokens = {0};
            final String[] modelName = {""};

            // 流式处理
            final String finalSessionId = sessionId;
            Flux<String> stringFlux = kernel.stream(sessionId, Prompt.of(input))
                    .map(chunk -> {
                        String chunkType = chunk.getClass().getSimpleName();
                        LOG.debug("[WS] chunk: type={}, hasContent={}, isNormal={}",
                                chunkType,
                                chunk.hasContent(),
                                chunk instanceof ReActChunk ? ((ReActChunk) chunk).isNormal() : "N/A");

                        if (chunk.hasContent()) {
                            if (chunk instanceof ReasonChunk) {
                                ReasonChunk reason = (ReasonChunk) chunk;

                                if (!reason.isToolCalls() && reason.hasContent()) {
                                    // 检查是否是 thinking 内容
                                    boolean isThinking = reason.getMessage() != null && reason.getMessage().isThinking();
                                    String chunkTypeToSend = isThinking ? "think" : "reason";

                                    LOG.debug("[WS] sending {}: {}", chunkTypeToSend,
                                            chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())));
                                    return new ONode().set("type", chunkTypeToSend)
                                            .set("sessionId", finalSessionId)
                                            .set("text", chunk.getContent())
                                            .toJson();
                                }
                            } else if (chunk instanceof ActionEndChunk) {
                                ActionEndChunk action = (ActionEndChunk) chunk;
                                ONode oNode = new ONode().set("type", "action")
                                        .set("sessionId", finalSessionId)
                                        .set("text", chunk.getContent());

                                if (Assert.isNotEmpty(action.getToolName())) {
                                    oNode.set("toolName", action.getToolName());
                                    oNode.set("args", action.getArgs());
                                }

                                return oNode.toJson();
                            } else if (chunk instanceof ReActChunk) {
                                ReActChunk react = (ReActChunk) chunk;

                                // 收集 metrics 信息
                                if (react.getTrace() != null && react.getTrace().getMetrics() != null) {
                                    totalTokens[0] = react.getTrace().getMetrics().getTotalTokens();
                                }
                                if (react.getTrace() != null && react.getTrace().getConfig().getChatModel() != null) {
                                    modelName[0] = react.getTrace().getConfig().getChatModel().toString();
                                }

                                // 参考 CLI 的 CliShellNew.onFinalChunk 逻辑：
                                // - isNormal==false: 内容通过 reason 类型发送（和 ReasonChunk 一样处理）
                                // - isNormal==true: 这是最终汇总，内容已经通过 ReasonChunk 发送过了，跳过避免重复
                                if (!react.isNormal()) {
                                    LOG.debug("[WS] sending reason from ReActChunk: {}",
                                            chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())));
                                    return new ONode().set("type", "reason")
                                            .set("sessionId", finalSessionId)
                                            .set("text", chunk.getContent())
                                            .toJson();
                                }

                                // isNormal==true 时，内容已通过 ReasonChunk 完整发送，此处跳过
                                LOG.debug("[WS] skipping ReActChunk with isNormal=true (content already sent via ReasonChunk)");
                                return "";
                            }
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
            socket.send("{\"type\":\"error\",\"text\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}");
        }
    }
}
