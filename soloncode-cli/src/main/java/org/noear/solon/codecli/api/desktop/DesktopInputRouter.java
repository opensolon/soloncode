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
package org.noear.solon.codecli.api.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.codecli.command.builtin.LoopTask;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.codecli.util.TraceUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 桌面端聊天输入路由：解析 WsMessage、会话与 Agent 归一、模式配置、
 * 附件落盘、命令分发与流回合触发。
 *
 * <p>从原 {@code WsGate.onMessage} 的主体拆出；校验规则、错误文案、
 * 事件时序与 session attrs 键名均逐字保留。真正的流订阅统一经
 * {@link DesktopStreamTurn}，Goal 启动经 {@code WsGate.startGoalStream}。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopInputRouter {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopInputRouter.class);

    private final WsGate gate;

    DesktopInputRouter(WsGate gate) {
        this.gate = gate;
    }

    void handleChat(WebSocket socket, ONode root) throws IOException {
        HarnessEngine engine = gate.engine;

        // 解析请求
        WsMessage req = root.toBean(WsMessage.class);
        String sessionId = socket.paramOrDefault("sessionId", "");
        String input = req.getInput();
        String cwd = req.getCwd();

        if (Assert.isEmpty(sessionId)) {
            sessionId = "ws_" + System.currentTimeMillis();
            // 及时通知客户端自动生成的 sessionId
            socket.send(new ONode().set("type", "session")
                    .set("sessionId", sessionId)
                    .toJson());
        }

        AgentSession session = engine.getSession(sessionId);

        if ("[(sec)interrupt]".equals(req.getInput())) {
            LoopTask activeGoal = gate.loopScheduler == null ? null : gate.loopScheduler.findActiveGoalInSession(sessionId);
            reactor.core.Disposable disposable = (reactor.core.Disposable) session.attrs().remove("disposable");
            if (disposable != null) {
                disposable.dispose();
            }
            if (activeGoal != null) {
                gate.loopScheduler.remove(sessionId, activeGoal);
                return;
            }
            AssistantMessage cancelMessage = ChatMessage.ofAssistant("用户已取消任务.");
            ReActTrace trace = TraceUtil.getCurrentTrace(session);
            if (trace != null) {
                cancelMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
            }
            session.addMessage(cancelMessage);
            // 补快照：中断时 agent 收尾被跳过，需将当前 trace 与取消消息一并持久化，避免重载后 trace 丢失
            session.updateSnapshot();
            LOG.info("用户已取消任务.");

            String interruptModelName = req.getModel();
            if (interruptModelName == null || interruptModelName.isEmpty()) {
                interruptModelName = engine.getMainModel().getConfig().getNameOrModel();
            }

            String interruptReason = new ONode().set("type", "reason")
                    .set("sessionId", session.getSessionId())
                    .set("text", "[Task interrupted]")
                    .toJson();
            if (!gate.streamHub.emit(sessionId, interruptReason)) {
                socket.send(interruptReason);
            }

            String interruptDone = new ONode().set("type", "done")
                    .set("sessionId", session.getSessionId())
                    .set("modelName", interruptModelName)
                    .set("totalTokens", 0)
                    .set("elapsedMs", 0).toJson();
            if (!gate.streamHub.emit(sessionId, interruptDone)) {
                socket.send(interruptDone);
            }
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
            // 桌面端切换项目或继续已有会话时，以本次请求的 cwd 覆盖旧会话工作区。
            session.attrs().put(HarnessEngine.ATTR_CWD, cwd);
        }

        if (Assert.isEmpty(input)) {
            return;
        }

        String agentName = null;
        String currentInput = input;

        String requestedAgent = req.getAgent();
        if (Assert.isNotEmpty(requestedAgent) && !"default".equals(requestedAgent)) {
            requestedAgent = requestedAgent.trim();
            if (!gate.isValidAgentName(requestedAgent) || !engine.getAgentManager().hasAgent(requestedAgent)) {
                socket.send(new ONode().set("type", "error")
                        .set("sessionId", sessionId)
                        .set("text", "Agent 不可用或已禁用")
                        .toJson());
                return;
            }
            agentName = requestedAgent;
            currentInput = gate.removeLeadingAgentMention(currentInput, agentName);
        } else if (input.startsWith("@")) {
            int agentNameIdx = gate.firstWhitespaceIndex(input);
            if (agentNameIdx > 1) {
                String mentionedAgent = input.substring(1, agentNameIdx);
                if (gate.isValidAgentName(mentionedAgent) && engine.getAgentManager().hasAgent(mentionedAgent)) {
                    agentName = mentionedAgent;
                    currentInput = input.substring(agentNameIdx).trim();
                }
            }
        }

        if (Assert.isEmpty(currentInput)) {
            socket.send(new ONode().set("type", "error")
                    .set("sessionId", sessionId)
                    .set("text", "请输入发送给 Agent 的内容")
                    .toJson());
            return;
        }

        // 根据前端指定的 model 选择对应 ChatModel
        String modelName = req.getModel();
        ChatModel chatModel = engine.getModelOrDefInstance(modelName);

        // 会话级模型选择只记录显式指定的值：桌面端未携带 model 时保持已有选择，
        // 否则 HITL 恢复等后续轮次会因 null 覆盖回落默认模型（与 WebChatInputHandler 守卫一致）。
        if (Assert.isNotEmpty(modelName)) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, modelName);
        }
        if (req.getReasoningEffort() != null) {
            ReasoningSupportUtil.putSessionEffort(session,
                    req.getReasoningEffort(), true);
        }
        // 请求显式 effort 优先；否则用会话 context
        final String reasoningEffort = ReasoningSupportUtil.resolveEffectiveEffort(
                req.getReasoningEffort(),
                ReasoningSupportUtil.getSessionEffort(session),
                null,
                req.getReasoningEffort() != null);

        // 模式处理：根据前端 mode 字段配置 session 行为
        String mode = DesktopRunModes.normalize(req.getMode());
        session.attrs().put(DesktopStreamTurn.SESSION_ATTR_RUN_MODE, mode);
        if ("plan".equals(mode)) {
            // 规划模式：只读分析，不执行文件/命令操作
            session.attrs().put("_plan_mode", true);
            if (!currentInput.contains("不要执行") && !currentInput.contains("只分析")) {
                currentInput = "[规划模式 - 仅分析不执行任何操作] " + currentInput;
            }
        } else {
            session.attrs().remove("_plan_mode");
        }
        // default 模式：write/edit/bash 都在真正执行前进入 HITL 审批。

        final ReActAgent agent = engine.getAgentOrMain(agentName);
        // 与 WebStreamBuilder 保持一致：记录本轮真正的源 Agent，供 HITL 恢复继续使用。
        session.attrs().put(WsGate.SESSION_ATTR_SELECTED_AGENT, agent.name());

        // Goal 是对话持续流，不再交给斜杠命令返回“任务已注册”回执。
        String goalObjective = WsGate.extractGoalObjective(currentInput, mode);
        if (goalObjective != null) {
            gate.startGoalStream(socket, sessionId, req, goalObjective, cwd, agent.name());
            return;
        }

        // 命令处理：以 / 开头的输入走命令分发
        if (currentInput.startsWith("/")) {
            handleCommand(socket, session, agent, chatModel, cwd, currentInput, sessionId, reasoningEffort);
            return;
        }

        // 流式处理
        final String finalSessionId = sessionId;

        // 处理附件：图片构建 ImageBlock，文件拼入文本前缀
        List<WsMessage.WsAttachment> attachments = req.getAttachments();
        List<ImageBlock> imageBlocks = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();

        if (attachments != null && !attachments.isEmpty()) {
            if (attachments.size() > DesktopAttachmentSupport.MAX_ATTACHMENTS) {
                throw new IllegalArgumentException("附件数量不能超过 10 个");
            }
            int totalAttachmentBytes = 0;
            for (WsMessage.WsAttachment att : attachments) {
                if (att == null || (!("image".equals(att.getType())) && !("file".equals(att.getType())))) {
                    throw new IllegalArgumentException("附件类型无效");
                }
                byte[] bytes = DesktopAttachmentSupport.decode(att);
                totalAttachmentBytes += bytes.length;
                if (totalAttachmentBytes > DesktopAttachmentSupport.MAX_TOTAL_ATTACHMENT_BYTES) {
                    throw new IllegalArgumentException("附件总大小不能超过 50 MB");
                }
                String savedName = DesktopAttachmentSupport.save(java.nio.file.Paths.get(cwd), att.getName(), bytes);
                if (DesktopAttachmentSupport.isMultimodalImage(att)) {
                    imageBlocks.add(ImageBlock.ofBase64(
                            Base64.getEncoder().encodeToString(bytes), att.getMimeType()));
                } else {
                    fileNames.add(savedName);
                }
            }
        }

        // 文件附件拼入输入文本前缀
        if (!fileNames.isEmpty()) {
            String filePrefix = fileNames.stream()
                    .map(f -> "[附件: " + f + "]")
                    .collect(Collectors.joining("\n"));
            currentInput = filePrefix + "\n" + currentInput;
        }

        // 构建 Prompt（含图片时用 Contents）
        Prompt prompt;
        if (!imageBlocks.isEmpty()) {
            Contents contents = new Contents();
            contents.addBlock(TextBlock.of(currentInput));
            for (ImageBlock block : imageBlocks) {
                contents.addBlock(block);
            }
            prompt = Prompt.of(new UserMessage(contents)).attrPut("start_time", System.currentTimeMillis());
        } else {
            prompt = Prompt.of(currentInput).attrPut("start_time", System.currentTimeMillis());
        }
        gate.applyReasoningEffort(prompt, reasoningEffort);

        gate.streamTurn.run(session, agent, chatModel, prompt, finalSessionId, cwd,
                reasoningEffort, socket, false, gate.desktopHitlInterceptor);
    }

    /**
     * 处理 HITL 审批/拒绝操作
     * 消息格式: {"type":"hitl_action","action":"approve|reject","sessionId":"..."}
     */
    void handleHitlAction(WebSocket socket, ONode root) {
        try {
            HarnessEngine engine = gate.engine;
            String sessionId = root.get("sessionId") != null ? root.get("sessionId").getString() : null;
            String action = root.get("action") != null ? root.get("action").getString() : null;
            String callId = root.get("callId") != null ? root.get("callId").getString() : null;
            String output = root.get("output") != null ? root.get("output").getString() : null;

            if (sessionId == null || action == null) {
                socket.send(new ONode().set("type", "error").set("text", "sessionId and action required").toJson());
                return;
            }

            AgentSession session = engine.getSession(sessionId);
            HITLTask task = Assert.isNotEmpty(callId)
                    ? HITL.getPendingTaskByCallUuid(session, callId)
                    : HITL.getPendingTask(session);
            if (task == null) {
                socket.send(new ONode().set("type", "error").set("text", "No pending HITL task").toJson());
                return;
            }

            if ("approve".equals(action)) {
                if (Assert.isNotEmpty(output)) {
                    HITL.approve(session, task, output);
                } else {
                    HITL.approve(session, task);
                }
            } else {
                if (Assert.isNotEmpty(output)) {
                    HITL.reject(session, task, output);
                } else {
                    HITL.reject(session, task);
                }
            }

            // 审批后恢复流执行
            String modelName = (String) session.getContext().get(HarnessEngine.CTX_MODEL_SELECTED);
            ChatModel chatModel = engine.getModelOrDefInstance(modelName);
            String selectedAgentName = (String) session.attrs().get(WsGate.SESSION_ATTR_SELECTED_AGENT);
            ReActAgent selectedAgent = engine.getAgentOrMain(selectedAgentName);
            String cwd = session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, ".").toString();
            String reasoningEffort = ReasoningSupportUtil.getSessionEffort(session);

            Prompt hitlPrompt = Prompt.of().attrPut("start_time", System.currentTimeMillis());
            gate.applyReasoningEffort(hitlPrompt, reasoningEffort);

            gate.streamTurn.run(session, selectedAgent, chatModel, hitlPrompt, sessionId, cwd,
                    reasoningEffort, socket, true, gate.desktopHitlInterceptor);
        } catch (Exception e) {
            LOG.error("[WS] HITL action failed", e);
            socket.send(new ONode().set("type", "error").set("text", e.getMessage()).toJson());
        }
    }

    /**
     * 处理命令输入（/ 开头），通过 CommandRegistry 分发执行
     */
    private void handleCommand(WebSocket socket, AgentSession session, ReActAgent agent, ChatModel chatModel,
                               String sessionCwd, String input, String finalSessionId, String reasoningEffort) {
        try {
            HarnessEngine engine = gate.engine;
            // 解析命令名和参数
            List<String> parts = org.noear.solon.ai.util.CmdUtil.parseArguments(input.trim().substring(1));
            if (parts.isEmpty()) {
                return;
            }

            String cmdName = parts.get(0).toLowerCase();
            List<String> args = parts.size() > 1 ? parts.subList(1, parts.size()) : new ArrayList<>();

            // 查找命令
            Command command = engine.getCommandRegistry().find(cmdName);
            if (command == null) {
                // 不是有效命令，当作普通输入走流式处理
                runFallbackPrompt(socket, session, agent, chatModel, sessionCwd, input, finalSessionId, reasoningEffort);
                return;
            }

            // 构建 context（注入 agentTaskRunner 回调）
            WebCommandContext ctx = new WebCommandContext(session, engine, input, cmdName, args,
                    (prompt, model) -> {
                        ChatModel selectedModel = model != null ? engine.getModelOrDefInstance(model) : chatModel;
                        runFallbackPrompt(socket, session, agent, selectedModel, sessionCwd, prompt, finalSessionId, reasoningEffort);
                    });

            // 执行命令
            command.execute(ctx);

            if (!ctx.isAgentTask()) {
                // rewind 命令特殊处理：发送 rewind 事件让前端同步删除 DOM
                if ("rewind".equals(cmdName)) {
                    int rewindCount = 1;
                    if (!args.isEmpty()) {
                        try {
                            rewindCount = Integer.parseInt(args.get(0));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    socket.send(new ONode().set("type", "rewind")
                            .set("sessionId", finalSessionId)
                            .set("count", rewindCount + 1)
                            .toJson());
                } else {
                    String text = ctx.getOutputBuffer().length() > 0
                            ? ctx.getOutputBuffer().toString()
                            : "命令执行完成";
                    socket.send(new ONode().set("type", "command")
                            .set("sessionId", finalSessionId)
                            .set("text", text)
                            .toJson());
                }

                socket.send(new ONode().set("type", "done")
                        .set("sessionId", finalSessionId)
                        .set("modelName", chatModel.getConfig().getNameOrModel())
                        .set("totalTokens", 0)
                        .set("elapsedMs", 0).toJson());
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            socket.send(new ONode().set("type", "error")
                    .set("sessionId", finalSessionId)
                    .set("text", errorMsg).toJson());
        }
    }

    /**
     * 将输入作为普通 prompt 走流式处理（原 handleFallbackPrompt）
     */
    private void runFallbackPrompt(WebSocket socket, AgentSession session, ReActAgent agent, ChatModel chatModel,
                                   String sessionCwd, String input, String finalSessionId, String reasoningEffort) {
        Prompt prompt = Prompt.of(input).attrPut("start_time", System.currentTimeMillis());
        gate.applyReasoningEffort(prompt, reasoningEffort);
        gate.streamTurn.run(session, agent, chatModel, prompt, finalSessionId, sessionCwd,
                reasoningEffort, socket, false, gate.desktopHitlInterceptor);
    }
}
