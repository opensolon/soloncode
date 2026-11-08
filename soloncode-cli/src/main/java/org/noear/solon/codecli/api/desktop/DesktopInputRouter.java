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
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.host.SubmitSpec;
import org.noear.solon.ai.harness.input.AgentMentions;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 桌面端聊天输入路由：解析 WsMessage、会话与 Agent 归一、模式配置、
 * 附件落盘、命令分发与流回合触发。
 *
 * <p>本类只做协议适配：把 WS 消息组装成输入交给 HarnessHost（{@code host.submit / decide / interrupt}）。
 * 结局分派、开流、终态、续发积压、停止屏障都在框架；呈现在 {@link DesktopHostSink}（会话级）与
 * {@link DesktopRunSink}（每轮）。Goal 启动经 {@code WsGate.startGoalStream}。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopInputRouter {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopInputRouter.class);

    /** 宿主域输入属性：推理强度（经 input.attr 随积压保存，续发时从 QueueItem#getAttrs 取回；旧数据的顶层字段由 queue 读入时并入 attrs） */
    static final String ATTR_REASONING_EFFORT = "reasoningEffort";
    /** 宿主域输入属性：运行模式（忙时随积压保存，续发时再写入会话） */
    static final String ATTR_RUN_MODE = "runMode";
    /** 宿主域输入属性：子代理（受理后写入会话；null = 主代理） */
    static final String ATTR_AGENT = "agent";

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
            if (gate.goal != null && gate.goal.get(sessionId) != null) {
                // goal 轮次：只停流，不落取消消息（历史由 goal.remove 收尾）。
                // 不能用用户停止（INTERRUPT_ALL）：它会补写取消消息，而 goal 循环会把历史里的取消文案当作本轮输出的一部分。
                gate.host.admin(WsGate.TENANT).interrupt(sessionId, InterruptMode.CANCEL_STREAM);
                // remove 门面内含「先摘调度再清除」
                gate.goal.remove(sessionId);
                return;
            }

            // 普通轮次：用户停止（清积压 + 落取消消息 + 停流）。停止之后框架不再投递本轮事件、本轮不发终态，
            // reason + done 在这里同步发出（每轮恰好一个终态）
            gate.host.interrupt(WsGate.TENANT, sessionId);
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
            cwd = engine.getSessionCwd(session);
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
            engine.setSessionCwd(session, cwd);
        }

        if (Assert.isEmpty(input)) {
            return;
        }

        // 受理判定（待审批拦截、忙时排队、占位）统一在引擎 handle 第 0 步，见 dispatchTurn 的 REJECTED / DEFERRED 分支。

        String agentName = null;
        String currentInput = input;

        String requestedAgent = req.getAgent();
        String validRequestedAgent = null;
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
            validRequestedAgent = requestedAgent;
        }

        // @子代理 解析口径与 CLI/Web/引擎完全统一：Options 只在 HarnessInput#parseMention 里构造一处。
        // 旧实现分两个分支各拼一份 Options（显式分支不 trim、兜底分支 trim），
        // 同一段输入在宿主与引擎里各解析一次且口径不同；现在宿主提前解析与 handle 内部解析逐字一致。
        // 桌面端输入框允许换行与缩进，故固定 trimContent(true)。
        AgentMentions.Mention mention = HarnessInput.of(session, input)
                .explicitAgent(validRequestedAgent)
                .agentNameValidator(gate::isValidAgentName)
                .trimContent(true)
                .parseMention(engine);
        agentName = mention.getAgentName();
        currentInput = mention.getText();

        if (Assert.isEmpty(currentInput)) {
            socket.send(new ONode().set("type", "error")
                    .set("sessionId", sessionId)
                    .set("text", "请输入发送给 Agent 的内容")
                    .toJson());
            return;
        }

        // 前端指定的 model（受理后才写进会话，见 DesktopHostSink#admitted）
        final String modelName = req.getModel();

        // 请求显式 effort 优先；否则用会话 context
        final String reasoningEffort = ReasoningSupportUtil.resolveEffectiveEffort(
                req.getReasoningEffort(),
                ReasoningSupportUtil.getSessionEffort(session),
                null,
                req.getReasoningEffort() != null);

        // 模式处理：根据前端 mode 字段配置 session 行为（受理后写入）
        final String mode = DesktopRunModes.normalize(req.getMode());
        if ("plan".equals(mode)) {
            // 规划模式：只读分析，不执行文件/命令操作
            if (!currentInput.contains("不要执行") && !currentInput.contains("只分析")) {
                currentInput = "[规划模式 - 仅分析不执行任何操作] " + currentInput;
            }
        }
        // default 模式：write/edit/bash 都在真正执行前进入 HITL 审批。

        // 与 WebStreamBuilder 保持一致：记录本轮真正的源 Agent，供 HITL 恢复继续使用。
        final String resolvedAgentName = engine.getAgentOrMain(agentName).name();

        // Goal 是对话持续流，不再交给斜杠命令返回“任务已注册”回执。
        // Goal 不经 handle 受理：选择照旧在创建前写入
        String goalObjective = WsGate.extractGoalObjective(currentInput, mode);
        if (goalObjective != null) {
            applySelection(session, modelName, req.getReasoningEffort(), mode, agentName);
            gate.startGoalStream(socket, sessionId, req, goalObjective, cwd, resolvedAgentName);
            return;
        }

        // 处理附件：图片构建 ImageBlock，文件作为引用拼入文本前缀
        List<WsMessage.WsAttachment> attachments = req.getAttachments();
        InputAttachments attachResult = InputAttachments.of();
        // 本轮新建的附件文件（未受理时删除；覆盖了已有同名文件的不计入，避免删掉上一轮的附件）
        List<Path> createdFiles = new ArrayList<>();

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
                Path target = DesktopAttachmentSupport.targetOf(Paths.get(cwd), att.getName());
                boolean existed = Files.exists(target);
                String savedName = DesktopAttachmentSupport.save(Paths.get(cwd), att.getName(), bytes);
                if (!existed) {
                    createdFiles.add(target);
                }
                if (DesktopAttachmentSupport.isMultimodalImage(att)) {
                    attachResult.media(ImageBlock.ofBase64(
                            Base64.getEncoder().encodeToString(bytes), att.getMimeType()).filePath(savedName));
                } else {
                    attachResult.reference(savedName);
                }
            }
        }

        // 受理、命令识别、附件前缀拼装、Prompt 构建在引擎；结局分派、开流、终态、续发积压在 HarnessHost。
        // 会话级选择（模型 / 推理强度 / 运行模式 / 子代理）作为输入属性交出去，受理后才写进会话（DesktopHostSink#admitted）：
        // 被排队或拒绝的输入不得改写正在运行会话的选择；排队的条目带着这些属性，续发时再生效。
        // agent 已在此前解析（桌面端需先做 Goal 拦截与格式校验），用 explicitAgent 透传避免二次判断。
        final String agent = agentName;
        final String text = currentInput;
        gate.hostSink.bind(sessionId, socket);
        SubmitResult result;
        try {
            result = gate.host.submit(WsGate.TENANT, sessionId, SubmitSpec.of(text).customize(in -> in
                    .explicitAgent(agent)
                    .selectedModel(modelName)
                    .attachments(attachResult)
                    .attr(ATTR_REASONING_EFFORT, reasoningEffort)
                    .attr(ATTR_RUN_MODE, mode)
                    .attr(ATTR_AGENT, agent)));
        } catch (RuntimeException e) {
            deleteQuietly(createdFiles);
            throw e;
        }
        if (result.getKind() != SubmitResult.Kind.STARTED) {
            // 未开流（如忙时带附件被拒）：附件不会被任何一轮引用，删掉本轮新建的文件
            deleteQuietly(createdFiles);
        }
    }

    private static void deleteQuietly(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                LOG.warn("[Desktop] delete unused attachment failed: {}", e.toString());
            }
        }
    }

    /**
     * 写入会话级选择（Goal 不经受理，选择照旧在创建前写入）。
     */
    private void applySelection(AgentSession session, String modelName, String effort, String mode, String agentName) {
        HarnessEngine engine = gate.engine;
        if (Assert.isNotEmpty(modelName)) {
            engine.setSessionModel(session, modelName);
        }
        if (effort != null) {
            ReasoningSupportUtil.putSessionEffort(session, effort, true);
        }
        if (mode != null) {
            session.attrs().put(DesktopRunSink.SESSION_ATTR_RUN_MODE, mode);
        }
        engine.setSessionAgent(session, engine.getAgentOrMain(agentName).name());
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

            // 写决策 + 本批齐备时续流，一次完成（模型 / 子代理沿用会话选择）：
            //   开流 —— 审批续跑，续用原来的流（DesktopRunSink 按 isResume 续用序号）；
            //   未续流 —— 过期审批卡（hitlExpired）或本批尚有未决卡（推下一张，DesktopHostSink#hitl）。
            // 协议字符串在边界解析为枚举（脏值抛 IllegalArgumentException，落入下方 catch 回错误，不再静默当拒绝）
            gate.hostSink.bind(sessionId, socket);
            gate.host.decide(WsGate.TENANT, sessionId, callId, HITLAction.of(action), output, null);
        } catch (Exception e) {
            LOG.error("[WS] HITL action failed", e);
            socket.send(new ONode().set("type", "error").set("text", e.getMessage()).toJson());
        }
    }
}
