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
package org.noear.solon.codecli.portal.web;

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

    /** AI 引擎实例，提供会话管理、模型获取、命令注册等核心能力 */
    private final HarnessEngine engine;

    /** Agent 配置属性（模型参数、工作空间路径等） */
    private final AgentProperties agentProps;

    /** 流式响应构建器，负责组装 ReAct Agent 的流式输出并通过本网关推送 */
    private final WebStreamBuilder streamBuilder;

    /**
     * WebSocket 连接池。
     *
     * <p>每个浏览器 Tab 建立一个独立的 WebSocket 连接并注册到此列表中。
     * 所有出站消息（AI 响应、命令输出、系统事件）均通过遍历此列表广播，
     * 每条消息携带 sessionId 由前端自行路由到对应会话面板。</p>
     *
     * <p>使用 {@link CopyOnWriteArrayList} 保证并发读写安全。</p>
     */
    private final List<WebSocket> connections = new CopyOnWriteArrayList<>();


    /**
     * 构造网关实例。
     *
     * @param engine     AI 引擎，提供会话、模型、Agent、命令等核心服务
     * @param agentProps Agent 配置属性
     */
    public WebGate(HarnessEngine engine, AgentProperties agentProps) {
        this.engine = engine;
        this.agentProps = agentProps;
        this.streamBuilder = new WebStreamBuilder(engine);
    }

    /**
     * 获取流式响应构建器。
     *
     * <p>供 WeChatLink 等外部组件引用，用于构建与 WebSocket 网关共享的流式输出管道。</p>
     *
     * @return 当前网关关联的 {@link WebStreamBuilder} 实例
     */
    public WebStreamBuilder getStreamBuilder() {
        return streamBuilder;
    }

    // ═══════════════════════════════════════════════════════════════
    //  WebSocket 生命周期管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * WebSocket 连接建立时回调。
     *
     * <p>将新连接加入 {@link #connections} 连接池，后续出站消息将自动广播至此连接。</p>
     *
     * @param socket 新建立的 WebSocket 连接
     */
    @Override
    public void onOpen(WebSocket socket) {
        connections.add(socket);
        LOG.info("[WebGate] WebSocket opened: {}", socket.id());
    }

    /**
     * WebSocket 连接关闭时回调。
     *
     * <p>从 {@link #connections} 连接池中移除已断开的连接，停止向其推送消息。</p>
     *
     * @param socket 已关闭的 WebSocket 连接
     */
    @Override
    public void onClose(WebSocket socket) {
        connections.remove(socket);
        LOG.info("[WebGate] WebSocket closed: {}", socket.id());
    }

    /**
     * WebSocket 文本消息接收回调。
     *
     * <p>当前仅处理心跳检测（ping/pong），业务消息通过 HTTP 接口入口进入。</p>
     *
     * @param socket 来源 WebSocket 连接
     * @param text   接收到的文本消息
     */
    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        // 心跳处理
        if ("ping".equals(text)) {
            socket.send("pong");
        }
    }


    // ═══════════════════════════════════════════════════════════════
    //  输出端口 —— 向前端推送消息
    // ═══════════════════════════════════════════════════════════════

    /**
     * 统一输出：将消息块通过 WebSocket 推送至前端。
     *
     * <p>将 sessionId 注入到消息块中，然后序列化为 JSON 广播给所有已连接的前端。
     * 前端根据消息中的 sessionId 字段路由到对应的会话面板进行渲染。</p>
     *
     * @param sessionId 会话标识，用于前端路由消息到正确的会话面板
     * @param jsonChunk 待推送的消息块（可为文本流、错误、完成信号等多种类型）
     */
    public void emitToClient(String sessionId, WebChunk jsonChunk) {
        if (jsonChunk == null) {
            return;
        } else {
            jsonChunk.setSessionId(sessionId);
        }

        // 确保消息中包含 sessionId
        String enriched = ONode.serialize(jsonChunk);

        if (LOG.isDebugEnabled()) {
            LOG.debug("emit: " + enriched);
        }

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

    /**
     * 广播原始 JSON 字符串到所有 WebSocket 连接。
     *
     * <p>与 {@link #emitToClient} 不同，此方法不注入 sessionId，
     * 适用于系统级事件（如文件变化通知）等需要全局广播的场景。</p>
     *
     * @param json 待广播的原始 JSON 字符串
     */
    public void broadcastRaw(String json) {
        for (WebSocket socket : connections) {
            if (socket != null) {
                try {
                    socket.send(json);
                } catch (Throwable e) {
                    LOG.warn("[WebGate] broadcastRaw failed for {}: {}", socket.id(), e.getMessage());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  输入端口 —— 接收并处理用户请求
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用户聊天输入入口（由 WebController HTTP 接口调用）。
     *
     * <p>核心处理流程：</p>
     * <ol>
     *   <li>解析 Agent 指定前缀（如 "@agentName 消息内容"）</li>
     *   <li>处理 HITL（Human-in-the-Loop）审批/拒绝操作</li>
     *   <li>处理文件附件上传（图片走 Base64 编码，其他走文件路径引用）</li>
     *   <li>判断是否为斜杠命令（/command），若是则走命令分发</li>
     *   <li>构建 Prompt 并启动 Agent 流式任务</li>
     * </ol>
     *
     * @param sessionId       会话标识
     * @param sessionCwd      会话当前工作目录，用于 Agent 执行文件操作的基准路径
     * @param input           用户输入的文本内容
     * @param selectedModel   用户选择的 AI 模型标识（可为 null，表示使用默认模型）
     * @param attachments     上传的文件附件数组（可为 null）
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应（如 "image"）
     * @param hitlAction      HITL 操作类型，取值 "approve" 或 "reject"（可为 null）
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

                    if (engine.getAgentManager().hasAgent(agentName)) {
                        currentInput = currentInput.substring(agentNameIdx + 1);
                    }
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
                        java.nio.file.Path savePath = java.nio.file.Paths.get(engine.getWorkspace(), fileName).toAbsolutePath().normalize();

                        if (savePath.startsWith(java.nio.file.Paths.get(engine.getWorkspace()).toAbsolutePath().normalize())) {
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

    /**
     * 执行 Agent 流式任务。
     *
     * <p>通过 {@link WebStreamBuilder} 构建 ReAct Agent 的响应流，
     * 订阅流数据并通过 {@link #emitToClient} 逐条推送至前端。
     * 同时将 RxJava {@link Disposable} 保存到会话属性中，以支持 {@link #interruptSession} 中断。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param prompt       用户输入的 Prompt（为 null 时表示 HITL 恢复等无需新 Prompt 的场景）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称（可为 null，表示使用默认 Agent）
     */
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
                            session.attrs().remove("disposable");

                            emitToClient(sessionId, WebChunk.ofError(e));
                            emitToClient(sessionId, WebChunk.ofDone());
                        },
                        () -> {
                            session.attrs().remove("disposable");  // 正常完成时清理
                        }
                );

        session.attrs().put("disposable", disposable);
    }

    /**
     * 尝试将用户输入解析为斜杠命令并执行。
     *
     * <p>解析输入字符串中的命令名和参数，查找已注册的 {@link Command} 并执行。
     * 若命令执行后产生非 Agent 任务结果，会通过 WebSocket 推送命令输出；
     * 若为 rewind 命令，会发送特殊的回退事件通知前端删除历史 DOM。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param input        用户输入的完整文本（以 "/" 开头）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称
     * @return true 表示输入已被识别为命令并执行，false 表示非命令输入
     * @throws Exception 命令执行过程中可能抛出的异常
     */
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
                    try {
                        rewindCount = Integer.parseInt(args.get(0));
                    } catch (NumberFormatException ignored) {
                    }
                }

                //加一条删掉自己发出的一条
                emitToClient(session.getSessionId(), WebChunk.ofRewind(rewindCount + 1));
            } else {
                final String text;
                if (ctx.getOutputBuffer().length() > 0) {
                    text = ctx.getOutputBuffer().toString();
                } else {
                    text = "命令执行完成";
                }

                if (streamBuilder.getWeChatLink() != null) {
                    //命令执行后也通知给微信
                    if (streamBuilder.getWeChatLink().isBound(session.getSessionId())) {
                        streamBuilder.getWeChatLink().sendReply(session.getSessionId(), text, true);
                    }
                }

                emitToClient(session.getSessionId(), WebChunk.ofCommand(text));
            }

            emitToClient(session.getSessionId(), WebChunk.ofDone());
        }

        return true;
    }


    /**
     * 判断指定会话是否有 AI 任务正在执行。
     *
     * <p>通过检查会话属性中保存的 {@link Disposable} 对象是否仍处于活跃状态来判断。</p>
     *
     * @param session Agent 会话实例
     * @return true 表示会话有正在执行的 AI 任务
     */
    private boolean isSessionBusy(AgentSession session) {
        Disposable disposable = (Disposable) session.attrs().get("disposable");
        return disposable != null && !disposable.isDisposed();
    }

    /**
     * 安全聊天输入入口。
     *
     * <p>在调用 {@link #onChatInput} 之前先检查会话是否繁忙（有 AI 任务正在执行），
     * 若繁忙则跳过本次输入并记录警告日志。用于微信回调等需要避免并发冲突的场景。</p>
     *
     * @param sessionId 会话标识
     * @param input     用户输入文本
     * @param source    调用来源标识（用于日志记录，如 "WeChat"）
     */
    public void safeChatInput(String sessionId, String input, String source) {
        try {
            AgentSession session = engine.getSession(sessionId);
            if (isSessionBusy(session)) {
                LOG.warn("[WebGate] {} event skipped for session {}: task in progress", source, sessionId);
                return;
            }
        } catch (Exception e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return;
        }

        // 先推送用户消息到前端，确保对话记录中显示用户侧消息
        emitToClient(sessionId, WebChunk.ofUserInput(input, source));

        onChatInput(sessionId, null, input, null, null, null, null);
    }


    // ═══════════════════════════════════════════════════════════════
    //  工具方法 —— 附件类型判断与 MIME 映射
    // ═══════════════════════════════════════════════════════════════

    /** 支持的图片扩展名集合 */
    private static final Set<String> IMAGE_EXTENSIONS = org.noear.solon.Utils.asSet(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg");

    /**
     * 判断附件是否为图片类型。
     *
     * @param ext             文件扩展名（含点号，如 ".png"）
     * @param attachmentsType 前端传递的附件类型标识（如 "image"）
     * @return true 表示该附件应作为图片处理
     */
    private static boolean isImageAttachment(String ext, String attachmentsType) {
        return "image".equals(attachmentsType) && IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 将文件扩展名映射为 MIME 类型。
     *
     * @param ext 文件扩展名（含点号，如 ".jpg"）
     * @return 对应的 MIME 类型字符串，未匹配时默认返回 "image/png"
     */
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

    // ═══════════════════════════════════════════════════════════════
    //  会话中断支持
    // ═══════════════════════════════════════════════════════════════

    /**
     * 中断指定会话的当前 AI 任务。
     *
     * <p>从会话属性中取出并销毁 RxJava {@link Disposable} 以终止流式订阅，
     * 同时向会话历史追加一条取消记录，并向前端推送完成信号。</p>
     *
     * @param sessionId 待中断的会话标识
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