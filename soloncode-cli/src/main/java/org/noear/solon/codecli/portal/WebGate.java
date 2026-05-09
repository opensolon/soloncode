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
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebGate - 前端统一 WebSocket 网关
 *
 * <p>作为后端的统一输出调度 + 统一输入入口，消除双通道问题。
 * 前端整个生命周期只维护一个 WebSocket 连接，不跟任何特定 sessionId 绑定。
 * 后端推送的所有消息包都携带 sessionId 字段，前端根据此字段分发到对应会话进行渲染。</p>
 *
 * @author noear 2026/5/8 created
 */
public class WebGate extends SimpleWebSocketListener {
    private static final Logger LOG = LoggerFactory.getLogger(WebGate.class);

    private final HarnessEngine engine;
    private final AgentProperties agentProps;
    private final WebStreamBuilder streamBuilder;

    /**
     * WebSocket 连接池：每个浏览器 Tab 一个连接
     */
    private final List<WebSocket> connections = new CopyOnWriteArrayList<>();


    public WebGate(HarnessEngine engine, AgentProperties agentProps) {
        this.engine = engine;
        this.agentProps = agentProps;
        this.streamBuilder = new WebStreamBuilder(engine);
    }

    /**
     * 获取 WebStreamBuilder 实例（供 WeChatLink 等组件使用）
     */
    public WebStreamBuilder getStreamBuilder() {
        return streamBuilder;
    }

    // ==================== WebSocket 生命周期 ====================

    @Override
    public void onOpen(WebSocket socket) {
        connections.add(socket);
        LOG.info("[WebGate] WebSocket opened: {}", socket.id());
    }

    @Override
    public void onClose(WebSocket socket) {
        connections.remove(socket);
        LOG.info("[WebGate] WebSocket closed: {}", socket.id());
    }

    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        // 心跳处理
        if ("ping".equals(text)) {
            socket.send("pong");
        }
    }


    // ==================== 输出端口 ====================

    /**
     * 统一输出：通过 WebSocket 推送 JSON 到前端（消息携带 sessionId）
     */
    public void emitToClient(String sessionId, WebChunk jsonChunk) {
        if (jsonChunk == null) {
            return;
        } else {
            jsonChunk.setSessionId(sessionId);
        }

        // 确保消息中包含 sessionId
        String enriched = ONode.serialize(jsonChunk);

//        if (LOG.isDebugEnabled()) {
//            LOG.debug("emit: " + enriched);
//        }

        // 广播给所有连接（每条消息都带 sessionId，前端自行路由）
        for (WebSocket socket : connections) {
            if (socket != null) {
                try {
                    socket.send(enriched);
                } catch (Throwable e) {
                    LOG.warn("[WebGate] Failed to send to socket {}: {}", socket.id(), e.getMessage());
                }
            }
        }
    }

    // ==================== 输入端口 ====================

    /**
     * ① 用户聊天输入（由 WebController 调用）
     */
    public void onChatInput(String sessionId,
                            String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction) {
        try {
            AgentSession session = engine.getSession(sessionId);
            String agentName = null;
            String currentInput = input;

            if (currentInput != null && currentInput.startsWith("@")) {
                int agentNameIdx = currentInput.indexOf(" ");
                if (agentNameIdx > 0) {
                    agentName = currentInput.substring(1, agentNameIdx);
                    currentInput = currentInput.substring(agentNameIdx + 1);
                }
            }

            if (selectedModel != null) {
                session.getContext().put(HarnessFlags.VAR_MODEL_SELECTED, selectedModel);
            } else {
                selectedModel = session.getContext().getAs(HarnessFlags.VAR_MODEL_SELECTED);
            }


            // HITL approve/reject handling
            if (Assert.isNotEmpty(hitlAction)) {
                HITLTask task = HITL.getPendingTask(session);
                if (task != null) {
                    if ("approve".equals(hitlAction)) {
                        HITL.approve(session, task.getToolName());
                    } else {
                        HITL.reject(session, task.getToolName());
                    }
                }
                // Resume streaming after HITL decision
                performAgentTask(session, sessionCwd, null, selectedModel, agentName);
                return;
            }

            // Handle file upload
            List<ImageBlock> imageBlocks = new ArrayList<>();
            List<String> fileAttachments = new ArrayList<>();

            if (attachments != null) {
                for (int i = 0; i < attachments.length; i++) {
                    UploadedFile attachment = attachments[i];
                    String fileName = attachment.getName();
                    if (fileName != null && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\")) {
                        String ext = "." + attachment.getExtension();
                        java.nio.file.Path savePath = java.nio.file.Paths.get(engine.getProps().getWorkspace(), fileName).toAbsolutePath().normalize();

                        if (savePath.startsWith(java.nio.file.Paths.get(engine.getProps().getWorkspace()).toAbsolutePath().normalize())) {
                            java.nio.file.Files.copy(attachment.getContent(), savePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                            if (isImageAttachment(ext, attachmentTypes != null && i < attachmentTypes.length ? attachmentTypes[i] : null)) {
                                byte[] bytes = java.nio.file.Files.readAllBytes(savePath);
                                String base64 = Base64.getEncoder().encodeToString(bytes);
                                String mime = extensionToMime(ext);
                                imageBlocks.add(ImageBlock.ofBase64(base64, mime));
                            } else {
                                fileAttachments.add(fileName);
                            }
                        }
                    }
                }
            }

            // Build input text with file attachment prefix
            if (!fileAttachments.isEmpty()) {
                String filePrefix = fileAttachments.stream()
                        .map(f -> "[附件: " + f + "]")
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (currentInput == null || currentInput.isEmpty()) {
                    currentInput = filePrefix + "\n请帮我处理这些附件";
                } else {
                    currentInput = filePrefix + "\n" + currentInput;
                }
            }

            if (Assert.isNotEmpty(currentInput) || !imageBlocks.isEmpty()) {
                if (currentInput == null || currentInput.isEmpty()) {
                    currentInput = imageBlocks.size() > 1 ? "请描述这些图片" : "请描述这张图片";
                }

                // 命令分发
                if (currentInput.startsWith("/") && imageBlocks.isEmpty()) {
                    if (isCommand(session, sessionCwd, currentInput, selectedModel, agentName)) {
                        return;
                    }
                }

                Prompt prompt;
                if (!imageBlocks.isEmpty()) {
                    Contents contents = new Contents();
                    contents.addBlock(TextBlock.of(currentInput));
                    for (ImageBlock block : imageBlocks) {
                        contents.addBlock(block);
                    }
                    prompt = Prompt.of(new UserMessage(contents));
                } else {
                    prompt = Prompt.of(currentInput);
                }

                // 流式处理：输出通过 WebSocket 推送
                performAgentTask(session, sessionCwd, prompt, selectedModel, agentName);
            }
        } catch (Exception e) {
            LOG.error("Task fail: {}", e.getMessage(), e);
            emitToClient(sessionId, WebChunk.ofError(e));
            emitToClient(sessionId, WebChunk.ofDone());
        }
    }

    private void performAgentTask(AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName) {
        String sessionId = session.getSessionId();

        ChatModel chatModel = engine.getModelOrMain(selectedModel);
        ReActAgent agent = engine.getAgentOrMain(agentName);

        Disposable disposable = streamBuilder.buildStreamFlux(session, agent, chatModel, sessionCwd, prompt)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        line -> emitToClient(sessionId, line),
                        e -> {
                            LOG.error("Task fail: {}", e.getMessage(), e);
                            emitToClient(sessionId, WebChunk.ofError(e));
                            emitToClient(sessionId, WebChunk.ofDone());
                        }
                );

        session.attrs().put("disposable", disposable);
    }

    private boolean isCommand(AgentSession session, String sessionCwd, String input, String selectedModel, String agentName) throws Exception {
        if (!input.startsWith("/")) {
            return false;
        }

        // 解析命令名和参数
        List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
        String cmdName = parts.get(0).toLowerCase();
        List<String> args = parts.size() > 1
                ? parts.subList(1, parts.size())
                : Collections.emptyList();

        // 查找命令
        Command command = engine.getCommandRegistry().find(cmdName);
        if (command == null) {
            return false;
        }

        // 构建 context（注入 agentTaskRunner 回调）
        WebCommandContext ctx = new WebCommandContext(session, engine, input, cmdName, args,
                (prompt, model) -> {
                    try {
                        if (model == null) {
                            model = selectedModel;
                        }

                        performAgentTask(session, sessionCwd, Prompt.of(prompt), model, agentName);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // 执行命令
        command.execute(ctx);


        if (ctx.isAgentTask() == false) {
            // rewind 命令走特殊通道：发送 rewind 事件让前端同步删除 DOM
            if ("rewind".equals(cmdName)) {
                int rewindCount = 1;
                if (!args.isEmpty()) {
                    try { rewindCount = Integer.parseInt(args.get(0)); } catch (NumberFormatException ignored) {}
                }
                emitToClient(session.getSessionId(), WebChunk.ofRewind(rewindCount));
            } else {
                final String text;
                if (ctx.getOutputBuffer().length() > 0) {
                    text = ctx.getOutputBuffer().toString();
                } else {
                    text = "命令执行完成";
                }

                emitToClient(session.getSessionId(), WebChunk.ofCommand(text));
            }

            emitToClient(session.getSessionId(), WebChunk.ofDone());
        }

        return true;
    }


    /**
     * ③ Loop 事件输入 - 异步版本（返回 CompletableFuture）
     */
    public void onLoopEvent(String sessionId, String input) {
        onChatInput(sessionId, null, input, null, null, null, null);
    }

    /**
     * ④ 微信消息输入（由 WeChatLink 调用）
     */
    public void onWeChatMessage(String sessionId, String input) {
        onChatInput(sessionId, null, input, null, null, null, null);
    }


    // ==================== 工具方法 ====================

    private static final Set<String> IMAGE_EXTENSIONS = org.noear.solon.Utils.asSet(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg");

    private static boolean isImageAttachment(String ext, String attachmentsType) {
        return "image".equals(attachmentsType) && IMAGE_EXTENSIONS.contains(ext);
    }

    private static String extensionToMime(String ext) {
        switch (ext) {
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".png":
                return "image/png";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".bmp":
                return "image/bmp";
            case ".svg":
                return "image/svg+xml";
            default:
                return "image/png";
        }
    }

    // ==================== 中断支持 ====================

    /**
     * 中断指定会话的当前 AI 任务
     */
    public void interruptSession(String sessionId) {
        try {
            AgentSession session = engine.getSession(sessionId);
            Disposable disposable = (Disposable) session.attrs().remove("disposable");
            if (disposable != null) {
                disposable.dispose();
            }
            session.addMessage(ChatMessage.ofAssistant("用户已取消任务."));
            emitToClient(sessionId, WebChunk.ofDone());
            LOG.info("[WebGate] Session {} interrupted", sessionId);
        } catch (Exception e) {
            LOG.error("[WebGate] Interrupt failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}