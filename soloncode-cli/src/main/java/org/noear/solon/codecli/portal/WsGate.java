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

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

public class WsGate extends SimpleWebSocketListener {
    private static final Logger LOG = LoggerFactory.getLogger(WsGate.class);
    private final HarnessEngine kernel;
    private final AgentProperties agentPros;
    private final WebStreamBuilder streamBuilder;

    public WsGate(HarnessEngine kernel, AgentProperties agentPros, WebStreamBuilder streamBuilder) {
        this.kernel = kernel;
        this.agentPros = agentPros;
        this.streamBuilder = streamBuilder;
    }

    @Override
    public void onOpen(WebSocket socket) {
        String sessionId = socket.paramOrDefault("sessionId", agentPros.getSessionId());
        String sessionCwd = socket.param(AgentProperties.X_SESSION_CWD);

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
            ONode root = ONode.ofJson(text);
            String msgType = root.get("type") != null ? root.get("type").getString() : null;

            if ("config".equals(msgType)) {
                handleConfigMessage(socket, root);
                return;
            }

            if ("hitl_action".equals(msgType)) {
                handleHitlAction(socket, root);
                return;
            }

            // 解析请求
            WsMessage req = root.toBean(WsMessage.class);
            String sessionId = socket.paramOrDefault("sessionId", "");
            String input = req.getInput();
            String cwd = req.getCwd();

            if (Assert.isEmpty(sessionId)) {
                sessionId = "ws_" + System.currentTimeMillis();
                socket.send(new ONode().set("type", "session")
                        .set("sessionId", sessionId)
                        .toJson());
            }

            AgentSession session = kernel.getSession(sessionId);

            if ("[(sec)interrupt]".equals(req.getInput())) {
                Disposable disposable = (Disposable) session.attrs().remove("disposable");
                if (disposable != null) {
                    disposable.dispose();
                }
                session.addMessage(ChatMessage.ofAssistant("用户已取消任务."));
                LOG.info("用户已取消任务.");

                String interruptModelName = req.getModel();
                if (interruptModelName == null || interruptModelName.isEmpty()) {
                    interruptModelName = kernel.getMainModel().getConfig().getNameOrModel();
                }

                socket.send(new ONode().set("type", "reason")
                        .set("sessionId", session.getSessionId())
                        .set("text", "[Task interrupted]")
                        .toJson());

                socket.send(new ONode().set("type", "done")
                        .set("sessionId", session.getSessionId())
                        .set("modelName", interruptModelName)
                        .set("totalTokens", 0)
                        .set("elapsedMs", 0).toJson());
                return;
            }

            if (Assert.isEmpty(req.getCwd())) {
                cwd = session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, ".").toString();
            }

            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                socket.send(new ONode().set("type", "error").set("text", "Invalid Session ID").toJson());
                return;
            }

            if (Assert.isNotEmpty(cwd)) {
                if (cwd.contains("..")) {
                    socket.send(new ONode().set("type", "error").set("text", "Invalid Session Cwd").toJson());
                    return;
                }
            }

            if (Assert.isEmpty(input)) {
                return;
            }

            String agentName = null;
            if (input.startsWith("@")) {
                int agentNameIdx = input.indexOf(" ");
                if (agentNameIdx > 0) {
                    agentName = input.substring(1, agentNameIdx);
                }
            }

            String modelName = req.getModel();
            ChatModel chatModel = kernel.getModelOrMain(modelName);
            session.getContext().put(HarnessFlags.VAR_MODEL_SELECTED, modelName);
            final ReActAgent agent = kernel.getAgentOrMain(agentName);

            // 命令处理
            if (input.startsWith("/")) {
                handleCommand(socket, session, agent, chatModel, cwd, input, sessionId);
                return;
            }

            final String finalSessionId = sessionId;

            // 处理附件
            List<WsMessage.WsAttachment> attachments = req.getAttachments();
            List<ImageBlock> imageBlocks = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();

            if (attachments != null && !attachments.isEmpty()) {
                for (WsMessage.WsAttachment att : attachments) {
                    if ("image".equals(att.getType()) && att.getData() != null) {
                        String base64 = att.getData();
                        int commaIdx = base64.indexOf(',');
                        if (commaIdx > 0) {
                            base64 = base64.substring(commaIdx + 1);
                        }
                        imageBlocks.add(ImageBlock.ofBase64(base64, att.getMimeType() != null ? att.getMimeType() : "image/png"));
                    } else if (att.getName() != null) {
                        fileNames.add(att.getName());
                    }
                }
            }

            if (!fileNames.isEmpty()) {
                String filePrefix = fileNames.stream()
                        .map(f -> "[附件: " + f + "]")
                        .collect(java.util.stream.Collectors.joining("\n"));
                input = filePrefix + "\n" + input;
            }

            // 构建 Prompt
            Prompt prompt;
            if (!imageBlocks.isEmpty()) {
                Contents contents = new Contents();
                contents.addBlock(TextBlock.of(input));
                for (ImageBlock block : imageBlocks) {
                    contents.addBlock(block);
                }
                prompt = Prompt.of(new UserMessage(contents)).attrPut("start_time", System.currentTimeMillis());
            } else {
                prompt = Prompt.of(input).attrPut("start_time", System.currentTimeMillis());
            }

            String finalCwd = cwd;

            // 使用 WebStreamBuilder 构建流（支持 HITL + IM 渠道回复）
            final String finalModelName = chatModel.getConfig().getNameOrModel();
            Disposable disposable = streamBuilder.buildStreamFlux(session, agent, chatModel, finalCwd, prompt)
                    .doFinally(signal -> session.attrs().remove("disposable"))
                    .subscribe(
                            chunk -> {
                                // 缓存 meta 数据，用于填充 done 的真实值
                                if ("meta".equals(chunk.getType())) {
                                    if (chunk.getModelName() != null) {
                                        session.attrs().put("_lastMetaModel", chunk.getModelName());
                                    }
                                    if (chunk.getTotalTokens() != null) {
                                        session.attrs().put("_lastMetaTokens", chunk.getTotalTokens());
                                    }
                                    if (chunk.getElapsedMs() != null) {
                                        session.attrs().put("_lastMetaElapsed", chunk.getElapsedMs());
                                    }
                                    return; // meta 不发送到前端
                                }

                                String msg = webChunkToJson(chunk, finalSessionId, finalModelName, session);
                                if (msg != null) {
                                    socket.send(msg);
                                }
                            },
                            err -> {
                                socket.send(new ONode().set("type", "error")
                                        .set("sessionId", finalSessionId)
                                        .set("text", err.getMessage())
                                        .toJson());
                            }
                    );

            Disposable old = (Disposable) session.attrs().put("disposable", disposable);
            if (old != null && !old.isDisposed()) {
                old.dispose();
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            socket.send(new ONode().set("type", "error").set("text", errorMsg).toJson());
        }
    }

    /**
     * 将 WebChunk 转换为桌面端 WebSocket JSON 格式
     */
    private String webChunkToJson(WebChunk chunk, String sessionId, String modelName, AgentSession session) {
        if (chunk == null || chunk.getType() == null) return null;

        ONode node = new ONode().set("sessionId", sessionId);

        switch (chunk.getType()) {
            case "text":
                // 桌面端用 "reason" 类型渲染正文
                node.set("type", "reason");
                node.set("text", chunk.getText());
                break;
            case "reason":
                node.set("type", "think");
                node.set("text", chunk.getText());
                break;
            case "action":
                node.set("type", "action");
                node.set("text", chunk.getText());
                if (chunk.getToolName() != null) node.set("toolName", chunk.getToolName());
                if (chunk.getArgs() != null) node.set("args", chunk.getArgs());
                break;
            case "hitl":
                node.set("type", "hitl");
                node.set("toolName", chunk.getToolName());
                if (chunk.getCommand() != null) node.set("command", chunk.getCommand());
                break;
            case "done":
                node.set("type", "done");
                String doneModel = (String) session.attrs().getOrDefault("_lastMetaModel",
                        modelName != null ? modelName : kernel.getMainModel().getConfig().getNameOrModel());
                long doneTokens = session.attrs().get("_lastMetaTokens") != null
                        ? (Long) session.attrs().get("_lastMetaTokens") : 0;
                long doneElapsed = session.attrs().get("_lastMetaElapsed") != null
                        ? (Long) session.attrs().get("_lastMetaElapsed") : 0;
                node.set("modelName", doneModel);
                node.set("totalTokens", doneTokens);
                node.set("elapsedMs", doneElapsed);
                // 清理缓存
                session.attrs().remove("_lastMetaModel");
                session.attrs().remove("_lastMetaTokens");
                session.attrs().remove("_lastMetaElapsed");
                break;
            case "error":
                node.set("type", "error");
                node.set("text", chunk.getText());
                break;
            case "command":
                node.set("type", "reason");
                node.set("text", chunk.getText());
                break;
            case "rewind":
                node.set("type", "rewind");
                node.set("text", chunk.getText());
                break;
            default:
                return null;
        }

        return node.toJson();
    }

    /**
     * 处理 HITL 审批/拒绝操作
     * 消息格式: {"type":"hitl_action","action":"approve|reject","sessionId":"..."}
     */
    private void handleHitlAction(WebSocket socket, ONode root) {
        try {
            String sessionId = root.get("sessionId") != null ? root.get("sessionId").getString() : null;
            String action = root.get("action") != null ? root.get("action").getString() : null;

            if (sessionId == null || action == null) {
                socket.send(new ONode().set("type", "error").set("text", "sessionId and action required").toJson());
                return;
            }

            AgentSession session = kernel.getSession(sessionId);
            HITLTask task = HITL.getPendingTask(session);
            if (task == null) {
                socket.send(new ONode().set("type", "error").set("text", "No pending HITL task").toJson());
                return;
            }

            if ("approve".equals(action)) {
                HITL.approve(session, task.getToolName());
            } else {
                HITL.reject(session, task.getToolName());
            }

            // 审批后恢复流执行
            String modelName = (String) session.getContext().get(HarnessFlags.VAR_MODEL_SELECTED);
            ChatModel chatModel = kernel.getModelOrMain(modelName);
            ReActAgent agent = kernel.getAgentOrMain(null);
            String cwd = session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, ".").toString();

            Disposable disposable = streamBuilder.buildStreamFlux(session, agent, chatModel, cwd, null)
                    .doFinally(signal -> session.attrs().remove("disposable"))
                    .subscribe(
                            chunk -> {
                                String msg = webChunkToJson(chunk, sessionId, modelName, session);
                                if (msg != null) socket.send(msg);
                            },
                            err -> socket.send(new ONode().set("type", "error")
                                    .set("sessionId", sessionId).set("text", err.getMessage()).toJson())
                    );

            session.attrs().put("disposable", disposable);
        } catch (Exception e) {
            LOG.error("[WS] HITL action failed", e);
            socket.send(new ONode().set("type", "error").set("text", e.getMessage()).toJson());
        }
    }

    /**
     * 处理前端推送的配置变更
     */
    private void handleConfigMessage(WebSocket socket, ONode root) {
        try {
            ONode chatModelNode = root.get("chatModel");
            if (chatModelNode != null && !chatModelNode.isNull()) {
                String apiUrl = chatModelNode.get("apiUrl") != null ? chatModelNode.get("apiUrl").getString() : null;
                String apiKey = chatModelNode.get("apiKey") != null ? chatModelNode.get("apiKey").getString() : null;
                String model = chatModelNode.get("model") != null ? chatModelNode.get("model").getString() : null;

                if (apiUrl != null || apiKey != null || model != null) {
                    if (agentPros.getChatModel() != null) {
                        if (apiUrl != null) agentPros.getChatModel().setApiUrl(apiUrl);
                        if (apiKey != null) agentPros.getChatModel().setApiKey(apiKey);
                        if (model != null) agentPros.getChatModel().setModel(model);
                    }

                    agentPros.removeModel(agentPros.getChatModel().getNameOrModel());
                    agentPros.addModel(agentPros.getChatModel());
                    kernel.switchMainModel(agentPros.getChatModel().getNameOrModel());

                    LOG.info("[WS] Config updated: model={}", model);
                    saveConfigToFile(apiUrl, apiKey, model);

                    socket.send(new ONode().set("type", "config").set("status", "ok").set("model", model).toJson());
                }
            }
        } catch (Exception e) {
            LOG.error("[WS] Config update failed", e);
            socket.send(new ONode().set("type", "config").set("status", "error").set("text", e.getMessage()).toJson());
        }
    }

    private void saveConfigToFile(String apiUrl, String apiKey, String model) {
        try {
            String home = System.getProperty("user.home");
            Path configDir = Paths.get(home, ".soloncode");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("chat-model.yml");

            String existApiUrl = agentPros.getChatModel() != null ? agentPros.getChatModel().getApiUrl() : null;
            String existApiKey = agentPros.getChatModel() != null ? agentPros.getChatModel().getApiKey() : null;
            String existModel = agentPros.getChatModel() != null ? agentPros.getChatModel().getNameOrModel() : null;

            String finalApiUrl = apiUrl != null ? apiUrl : existApiUrl;
            String finalApiKey = apiKey != null ? apiKey : existApiKey;
            String finalModel = model != null ? model : existModel;

            StringBuilder yaml = new StringBuilder();
            yaml.append("soloncode:\n  chatModel:\n");
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

    private void handleCommand(WebSocket socket, AgentSession session, ReActAgent agent, ChatModel chatModel,
                               String sessionCwd, String input, String finalSessionId) {
        // todo: 命令分发待实现
    }
}
