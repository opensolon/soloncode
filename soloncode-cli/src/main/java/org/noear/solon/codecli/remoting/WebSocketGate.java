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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskSkill;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

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
            // 先判断消息类型（config 消息结构不同于 chat 消息）
            ONode root = ONode.ofJson(text);
            String msgType = root.get("type") != null ? root.get("type").getString() : null;

            if ("config".equals(msgType)) {
                handleConfigMessage(socket, root);
                return;
            }

            // 解析请求
            WebMessage req = root.toBean(WebMessage.class);
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

            if("[(sec)interrupt]".equals(req.getInput())) {
                Disposable disposable = (Disposable)session.attrs().get("disposable");
                if (disposable != null) {
                    disposable.dispose();
                }
                session.addMessage(ChatMessage.ofAssistant("用户中途取消了这个任务."));

                String msg = new ONode().set("type", "reason")
                        .set("sessionId", session.getSessionId())
                        .set("text", "[Task interrupted]")
                        .toJson();
                socket.send(msg);

                String msg2 = new ONode().set("type", "done")
                        .set("sessionId", session.getSessionId())
                        .set("modelName", kernel.getMainAgent().getConfig().getChatModel().getModel())
                        .set("totalTokens", 0)
                        .set("elapsedMs", 0).toJson();

                socket.send(msg2);
                return;
            }



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

            // 流式处理
            final String finalSessionId = sessionId;
            Prompt prompt = Prompt.of(input).attrPut("start_time", System.currentTimeMillis());


            String finalCwd = cwd;
            Disposable disposable = kernel.getMainAgent()
                    .prompt(prompt)
                    .session(session)
                    .options(o -> {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, finalCwd);
                    })
                    .stream()
                    .doOnNext(chunk -> {
                        String chunkType = chunk.getClass().getSimpleName();
                        LOG.debug("[WS] chunk: type={}, hasContent={}, isNormal={}",
                                chunkType,
                                chunk.hasContent(),
                                chunk instanceof ReActChunk ? ((ReActChunk) chunk).isNormal() : "N/A");

                        // ReActChunk 需要优先处理 metrics 收集（无论 hasContent 状态）
                        String msg = null;
                        if (chunk instanceof ReActChunk) {
                           onReActChunk((ReActChunk) chunk, finalSessionId, socket);
                           return;
                        } else if (chunk instanceof ReasonChunk) {
                            msg = onReasonChunk((ReasonChunk) chunk, finalSessionId);
                        } else if (chunk instanceof ActionEndChunk) {
                            msg = onActionEndChunk((ActionEndChunk) chunk, finalSessionId);
                        } else if (chunk instanceof ThoughtChunk) {
                            msg = onThoughtChunk((ThoughtChunk) chunk, finalSessionId);
                        }

                        if (Assert.isNotEmpty(msg)) {
                            socket.send(msg);
                        }
                    }).doOnError(err -> {
                        String msg = new ONode().set("type", "error")
                                .set("sessionId", finalSessionId)
                                .set("text", err.getMessage())
                                .toJson();

                        socket.send(msg);
                    }).subscribe();

            session.attrs().put("disposable", disposable);

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            socket.send(new ONode().set("type", "error")
                    .set("text", errorMsg).toJson());
        }
    }

    private void onReActChunk(ReActChunk chunk, String finalSessionId, WebSocket socket) {
        // 参考 CLI 的 CliShellNew.onFinalChunk 逻辑：
        // - isNormal==false: 内容通过 reason 类型发送（和 ReasonChunk 一样处理）
        // - isNormal==true: 这是最终汇总，内容已经通过 ReasonChunk 发送过了，跳过避免重复
        if (!chunk.isNormal() && chunk.hasContent()) {
            LOG.debug("[WS] sending reason from ReActChunk: {}",
                    chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())));
            String msg = new ONode().set("type", "reason")
                    .set("sessionId", finalSessionId)
                    .set("text", chunk.getContent())
                    .toJson();
            socket.send(msg);
        }

        // isNormal==true 或无内容时，内容已通过 ReasonChunk 完整发送，此处跳过
        LOG.debug("[WS] skipping ReActChunk (isNormal={}, hasContent={})",
                chunk.isNormal(), chunk.hasContent());

        Long start_time = chunk.getTrace().getOriginalPrompt().attrAs("start_time");

        String msg2 = new ONode().set("type", "done")
                .set("sessionId", finalSessionId)
                .set("modelName", chunk.getTrace().getConfig().getChatModel().getModel())
                .set("totalTokens", chunk.getTrace().getMetrics().getTotalTokens())
                .set("elapsedMs", System.currentTimeMillis() - start_time).toJson();

        socket.send(msg2);
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
                    oNode.set("toolName", chunk.getAgentName() + "_" + chunk.getToolName());
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

    /**
     * 处理前端推送的配置变更
     * 消息格式: {"type":"config","chatModel":{"apiUrl":"...","apiKey":"...","model":"..."}}
     */
    private void handleConfigMessage(WebSocket socket, ONode root) {
        try {
            ONode chatModelNode = root.get("chatModel");
            if (chatModelNode != null && !chatModelNode.isNull()) {
                String apiUrl = chatModelNode.get("apiUrl") != null ? chatModelNode.get("apiUrl").getString() : null;
                String apiKey = chatModelNode.get("apiKey") != null ? chatModelNode.get("apiKey").getString() : null;
                String model = chatModelNode.get("model") != null ? chatModelNode.get("model").getString() : null;

                if (apiUrl != null || apiKey != null || model != null) {
                    // 更新 AgentProperties 的 chatModel 配置
                    if (agentPros.getChatModel() != null) {
                        if (apiUrl != null) agentPros.getChatModel().setApiUrl(apiUrl);
                        if (apiKey != null) agentPros.getChatModel().setApiKey(apiKey);
                        if (model != null) agentPros.getChatModel().setModel(model);
                    }

                    // 重建 ChatModel 并注入 kernel
                    ChatModel newChatModel = ChatModel.of(agentPros.getChatModel()).build();
                    kernel.setChatModel(newChatModel);

                    LOG.info("[WS] Config updated: model={}", model);

                    // 持久化到 YAML 文件
                    saveConfigToFile(apiUrl, apiKey, model);

                    socket.send(new ONode()
                            .set("type", "config")
                            .set("status", "ok")
                            .set("model", model)
                            .toJson());
                }
            }
        } catch (Exception e) {
            LOG.error("[WS] Config update failed", e);
            socket.send(new ONode()
                    .set("type", "config")
                    .set("status", "error")
                    .set("text", e.getMessage())
                    .toJson());
        }
    }

    /**
     * 将 chatModel 配置持久化到 YAML 文件（~/.soloncode/chat-model.yml）
     */
    private void saveConfigToFile(String apiUrl, String apiKey, String model) {
        try {
            String home = System.getProperty("user.home");
            Path configDir = Paths.get(home, ".soloncode");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("chat-model.yml");

            // 读取已有配置，保留未更新的字段
            String existApiUrl = agentPros.getChatModel() != null ? agentPros.getChatModel().getApiUrl() : null;
            String existApiKey = agentPros.getChatModel() != null ? agentPros.getChatModel().getApiKey() : null;
            String existModel = agentPros.getChatModel() != null ? agentPros.getChatModel().getModel() : null;

            String finalApiUrl = apiUrl != null ? apiUrl : existApiUrl;
            String finalApiKey = apiKey != null ? apiKey : existApiKey;
            String finalModel = model != null ? model : existModel;

            StringBuilder yaml = new StringBuilder();
            yaml.append("soloncode:\n");
            yaml.append("  chatModel:\n");
            if (finalApiUrl != null) yaml.append("    apiUrl: \"").append(escapeYaml(finalApiUrl)).append("\"\n");
            if (finalApiKey != null) yaml.append("    apiKey: \"").append(escapeYaml(finalApiKey)).append("\"\n");
            if (finalModel != null) yaml.append("    model: \"").append(escapeYaml(finalModel)).append("\"\n");

            Files.write(configFile, yaml.toString().getBytes(StandardCharsets.UTF_8));
            LOG.info("[WS] Config persisted to: {}", configFile);
        } catch (Exception e) {
            LOG.error("[WS] Failed to persist config to YAML", e);
        }
    }

    private String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}