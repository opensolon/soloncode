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
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.AgentMentions;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.BufferedCommandContextFactory;
import org.noear.solon.ai.harness.input.CommandContextFactory;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.ParsedCommand;
import org.noear.solon.ai.harness.session.goal.GoalState;
import org.noear.solon.codecli.loop.LoopTask;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.codecli.util.TraceUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

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
            AgentSession goalSession = gate.goalEngine == null ? null : gate.goalEngine.sessionOf(sessionId);
            GoalState activeGoal = gate.goalEngine == null
                    ? null : gate.goalEngine.state(goalSession);
            reactor.core.Disposable disposable = (reactor.core.Disposable) session.attrs().remove("disposable");
            if (disposable != null) {
                disposable.dispose();
            }
            if (activeGoal != null) {
                if (gate.goalScheduler != null) {
                    gate.goalScheduler.unschedule(sessionId);
                }
                gate.goalEngine.clear(goalSession);
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
            // 请求体显式指定：先做格式与可用性校验，无效则直接报错（与迁移前一致）
            requestedAgent = requestedAgent.trim();
            if (!gate.isValidAgentName(requestedAgent) || !engine.getAgentManager().hasAgent(requestedAgent)) {
                socket.send(new ONode().set("type", "error")
                        .set("sessionId", sessionId)
                        .set("text", "Agent 不可用或已禁用")
                        .toJson());
                return;
            }

            // @子代理 解析口径与 CLI/Web 统一（harness AgentMentions）
            AgentMentions.Mention mention = AgentMentions.parse(engine, input,
                    AgentMentions.Options.builder()
                            .explicitAgent(requestedAgent)
                            .nameValidator(gate::isValidAgentName));
            agentName = mention.getAgentName();
            currentInput = mention.getText();
        } else {
            // 桌面端多一道名字格式校验，并需要去掉首尾空白
            AgentMentions.Mention mention = AgentMentions.parse(engine, input,
                    AgentMentions.Options.builder()
                            .nameValidator(gate::isValidAgentName)
                            .trimContent(true));
            agentName = mention.getAgentName();
            currentInput = mention.getText();
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

        // 与 WebStreamBuilder 保持一致：记录本轮真正的源 Agent，供 HITL 恢复继续使用。
        final String resolvedAgentName = engine.getAgentOrMain(agentName).name();
        session.attrs().put(WsGate.SESSION_ATTR_SELECTED_AGENT, resolvedAgentName);

        // Goal 是对话持续流，不再交给斜杠命令返回“任务已注册”回执。
        String goalObjective = WsGate.extractGoalObjective(currentInput, mode);
        if (goalObjective != null) {
            gate.startGoalStream(socket, sessionId, req, goalObjective, cwd, resolvedAgentName);
            return;
        }

        // 流式处理
        final String finalSessionId = sessionId;

        // 处理附件：图片构建 ImageBlock，文件作为引用拼入文本前缀
        List<WsMessage.WsAttachment> attachments = req.getAttachments();
        InputAttachments attachResult = InputAttachments.of();

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
                    attachResult.media(ImageBlock.ofBase64(
                            Base64.getEncoder().encodeToString(bytes), att.getMimeType()).filePath(savedName));
                } else {
                    attachResult.reference(savedName);
                }
            }
        }

        // 命令识别、附件前缀拼装、Prompt 构建统一交给引擎。
        // agent 已在此前解析（桌面端需先做 Goal 拦截与格式校验），用 explicitAgent 透传避免二次判断。
        InputOutcome outcome = gate.engine.handle(
                HarnessInput.of(session, currentInput)
                        .explicitAgent(agentName)
                        .selectedModel(modelName)
                        .attachments(attachResult)
                        .commandContextFactory(desktopCommandFactory()));

        switch (outcome.getKind()) {
            case TASK:
                // 命令触发的任务：不发独立回执（与原实现一致），命令输出由任务流自身携带；
                // 若在此先发回执，会绕过 DesktopStreamHub 的序号/回放缓冲，且与随后开流的时序耦合
                break;
            case REPLY:
                // 命令已执行，或命令在本渠道不可用：回执后结束本轮
                handleCommandReceipt(socket, outcome, finalSessionId);
                sendDone(socket, chatModel, finalSessionId);
                return;
            default:
                // HITL / IGNORED
                return;
        }

        // 模型/子代理一律用引擎生效值（命令声明 → 会话选择 → 默认），与普通消息同口径。
        // 请求主体（session/retry/maxTurns）已由 handle 装配，此处仅解析桌面端展示所需的模型句柄。
        final ChatModel turnModel = Assert.isEmpty(outcome.getModelName())
                ? chatModel : gate.engine.getModelOrDefInstance(outcome.getModelName());

        // 续流（如 /continue）：正文为空，靠会话挂起的 trace 继续（不能传空 Prompt，否则会命中空提示分支）
        Prompt prompt = outcome.getPrompt();
        if (prompt != null) {
            prompt.attrPut("start_time", System.currentTimeMillis());
            gate.applyReasoningEffort(prompt, reasoningEffort);
        }

        gate.streamTurn.run(session, outcome.getRequest(), turnModel, finalSessionId, cwd,
                reasoningEffort, socket, false, gate.desktopHitlInterceptor);
    }

    /**
     * 桌面端命令上下文工厂：只保留回执缓冲。
     * <p>
     * 桌面进程虽在本地，但没有命令行终端交互能力，故保持默认 terminal=false。
     * 命令内部触发的任务只记录意图（分发器交给引擎二段装配），
     * 流由主路径根据 outcome 的生效字段统一发起。
     */
    private CommandContextFactory desktopCommandFactory() {
        return new BufferedCommandContextFactory();
    }

    /**
     * 命令回执渲染（含 rewind 特殊通道），<b>不发 done</b>
     * <p>
     * 仅用于 {@code REPLY}（命令已执行或本渠道不可用）：命令输出自成一轮。
     * 命令触发任务（{@code TASK}）不发独立回执，与原实现一致。
     */
    private void handleCommandReceipt(WebSocket socket, InputOutcome outcome, String finalSessionId) {
        if ("rewind".equals(outcome.getCommandName())) {
            ParsedCommand cmd = outcome.getCommand();
            int rewindCount = 1;
            if (cmd != null && cmd.argCount() > 0) {
                try {
                    rewindCount = Integer.parseInt(cmd.argAt(0));
                } catch (NumberFormatException ignored) {
                }
            }
            socket.send(new ONode().set("type", "rewind")
                    .set("sessionId", finalSessionId)
                    .set("count", rewindCount + 1)
                    .toJson());
        } else {
            String text = outcome.hasReply() ? outcome.getCommandOutput() : "命令执行完成";
            socket.send(new ONode().set("type", "command")
                    .set("sessionId", finalSessionId)
                    .set("text", text)
                    .toJson());
        }
    }

    /**
     * 本轮收尾
     */
    private void sendDone(WebSocket socket, ChatModel chatModel, String finalSessionId) {
        socket.send(new ONode().set("type", "done")
                .set("sessionId", finalSessionId)
                .set("modelName", chatModel.getConfig().getNameOrModel())
                .set("totalTokens", 0)
                .set("elapsedMs", 0).toJson());
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

            // 一次调用完成「写决策 + 必要时的续流装配」，宿主只 switch kind：
            //   TASK —— 决策已写入且本批已齐备，直接执行续流；
            //   HITL —— 未续流：过期审批卡（isHitlApplied()==false，与迁移前「task == null → 报错」同口径）
            //            或本批尚有未决卡（推下一张卡，保持迁移前逐卡交互的观察结果）。
            String modelName = (String) session.getContext().get(HarnessEngine.CTX_MODEL_SELECTED);
            ChatModel chatModel = engine.getModelOrDefInstance(modelName);
            String selectedAgentName = (String) session.attrs().get(WsGate.SESSION_ATTR_SELECTED_AGENT);
            String cwd = session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, ".").toString();
            String reasoningEffort = ReasoningSupportUtil.getSessionEffort(session);

            // 决策应用与续流判定统一交给引擎（input 唯一入口）；
            // 协议字符串在边界解析为枚举（脏值抛 IllegalArgumentException，落入下方 catch 回错误，不再静默当拒绝）
            InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                    .hitl(HITLAction.of(action), callId)
                    .hitlOutput(output)
                    .selectedAgent(selectedAgentName)
                    .selectedModel(modelName)
                    .resume(true));

            if (outcome.getKind() == InputOutcome.Kind.TASK) {
                if (outcome.hasRequest() == false) {
                    socket.send(new ONode().set("type", "error").set("text", "No pending task to resume").toJson());
                    return;
                }

                gate.streamTurn.run(session, outcome.getRequest(), chatModel, sessionId, cwd,
                        reasoningEffort, socket, true, gate.desktopHitlInterceptor);
                return;
            }

            if (outcome.isHitlApplied() == Boolean.FALSE) {
                // 过期审批卡：任务已结束，前端残留的卡仍可点击。与迁移前同口径报错。
                socket.send(new ONode().set("type", "error").set("text", "No pending HITL task").toJson());
                return;
            }

            // 本批尚有未决卡：推送下一张（跳过已决策项），等宿主再次点击
            HITLTask next = DesktopStreamTurn.nextPendingHitlTask(session);
            if (next != null) {
                socket.send(DesktopStreamTurn.hitlEvent(sessionId, next).toJson());
            } else {
                socket.send(new ONode().set("type", "error").set("text", "No pending task to resume").toJson());
            }
        } catch (Exception e) {
            LOG.error("[WS] HITL action failed", e);
            socket.send(new ONode().set("type", "error").set("text", e.getMessage()).toJson());
        }
    }
}
