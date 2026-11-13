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
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.host.Disposition;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.codecli.session.SessionRewindService;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面端的会话级输出（HarnessHost 的 {@link HostSink}）：受理、回执、排队、拒收、审批的 WS 回执。
 *
 * <p>会话级回执直接发给「该会话最近一次发起请求的连接」（不进流的序号缓冲，与迁移前一致）；
 * 队列续发、审批续流的回执沿用这个连接。每轮的流输出在 {@link DesktopRunSink}。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopHostSink implements HostSink {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopHostSink.class);

    private final WsGate gate;
    /** 会话 → 最近一次发起请求的连接 */
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();

    DesktopHostSink(WsGate gate) {
        this.gate = gate;
    }

    /** 记下本会话的请求连接（提交之前调用） */
    void bind(String sessionId, WebSocket socket) {
        if (sessionId != null && socket != null) {
            sockets.put(sessionId, socket);
        }
    }

    /** 连接关闭：不再向它回执 */
    void unbind(WebSocket socket) {
        if (socket != null) {
            sockets.values().removeIf(s -> s == socket);
        }
    }

    // ==================== 会话级 ====================

    /** 受理后才写会话级选择：被排队 / 拒绝的输入、运行中任务旁边执行的命令、审批续跑都不改写 */
    @Override
    public void admitted(HostInput input) {
        AgentSession session = input.getSession();
        if (session == null || input.isResume() || input.isBesideRunning()) {
            return;
        }
        Disposition disposition = input.getDisposition();
        if (disposition == Disposition.STARTED || disposition == Disposition.REPLY) {
            applySelection(gate.engine, session, input.getSelectedModel(),
                    input.getAttr(DesktopInputRouter.ATTR_REASONING_EFFORT),
                    input.getAttr(DesktopInputRouter.ATTR_RUN_MODE),
                    input.getAttr(DesktopInputRouter.ATTR_AGENT));
        }

        if (disposition == Disposition.REPLY && input.hasReply() == false) {
            receipt(input, null);
        }
    }

    /**
     * 写入桌面会话级选择（唯一实现）：普通输入在受理后写（{@link #admitted}），goal 不经受理、创建前写。
     * 桌面端的子代理是「每条消息显式」语义：没带就是主代理（与 Web 的「没带就保持」不同）。
     */
    static void applySelection(HarnessEngine engine, AgentSession session, String model, String effort, String mode,
                               String agent) {
        if (Assert.isNotEmpty(model)) {
            // 空时保持已有选择（否则审批续跑等后续轮次会回落默认模型）
            engine.setSessionModel(session, model);
        }
        if (effort != null) {
            ReasoningSupportUtil.putSessionEffort(session, effort, true);
        }
        if (mode != null) {
            session.attrs().put(DesktopRunSink.SESSION_ATTR_RUN_MODE, mode);
        }
        engine.setSessionAgent(session, agentNameOf(engine, session, agent));
    }

    /**
     * 本轮实际生效的代理名：认本会话的会话级子代理；解析不到时为主代理。
     * 只取名字，不构建代理。
     */
    static String agentNameOf(HarnessEngine engine, AgentSession session, String agent) {
        AgentDefinition definition = engine.resolveAgent(session, agent);
        return definition == null ? AgentDefinition.AGENT_MAIN : definition.getName();
    }

    @Override
    public void reply(HostInput input, String text) {
        receipt(input, text);
    }

    /** 命令回执自成一轮：回执（含 rewind 特殊通道）+ done（报受理时的模型名） */
    private void receipt(HostInput input, String text) {
        String sid = input.getSessionId();
        if ("rewind".equals(input.getCommandName())) {
            ParsedCommand cmd = input.getCommand();
            int count = SessionRewindService.rewindReceiptCount(cmd != null && cmd.argCount() > 0 ? cmd.argAt(0) : null);
            send(input, new ONode().set("type", "rewind").set("sessionId", sid).set("count", count));
        } else {
            send(input, new ONode().set("type", "command").set("sessionId", sid)
                    .set("text", text != null ? text : "命令执行完成"));
        }
        String model = gate.engine.getModelOrDefInstance(input.getSelectedModel()).getConfig().getNameOrModel();
        send(input, new ONode().set("type", "done")
                .set("sessionId", sid)
                .set("modelName", model)
                .set("totalTokens", 0)
                .set("elapsedMs", 0));
    }

    @Override
    public void deferred(HostInput input, int position) {
        // 会话忙，已入积压：只回执排队位置，当前一轮结束后框架续发
        send(input, new ONode().set("type", "command")
                .set("sessionId", input.getSessionId())
                .set("text", "当前任务正在执行，已加入队列（第 " + position + " 位）"));
    }

    @Override
    public void rejected(HostInput input, RejectReason reason, String detail) {
        // 未受理：待审批卡 / 忙且无处可延 / 附件不能排队（不发 done：正在运行的那一轮自己会发）
        send(input, new ONode().set("type", "error")
                .set("sessionId", input.getSessionId())
                .set("code", reason.code())
                .set("text", detail));
    }

    @Override
    public void hitl(HostInput input, List<HitlCard> cards) {
        // 运行挂起时的卡由本轮的 RunEnd 推送；这里只处理「一次审批后本批仍有未决卡」：推下一张
        if (input.getDisposition() != Disposition.HITL || input.getSession() == null) {
            return;
        }
        HitlCard next = HitlCard.next(input.getSession());
        if (next != null) {
            send(input, DesktopRunSink.hitlEvent(input.getSessionId(), next));
        } else {
            send(input, new ONode().set("type", "error").set("text", "No pending task to resume"));
        }
    }

    @Override
    public void hitlExpired(HostInput input) {
        // 过期审批卡：任务已结束，前端残留的卡仍可点击
        send(input, new ONode().set("type", "error").set("text", "No pending HITL task"));
    }

    // ==================== 轮次级 ====================

    @Override
    public RunSink open(HostRun run) {
        if (run.isProgram() && "goal".equals(run.getProgramChannel()) == false) {
            // 桌面端没有 loop 的界面：照常执行（可停止、结束后续发），不推流（与迁移前插件缺省执行器一致）
            return RunSink.NONE;
        }
        return new DesktopRunSink(gate, run, sockets.get(run.getSessionId()));
    }

    // ==================== 工具 ====================

    private void send(HostInput input, ONode node) {
        WebSocket socket = sockets.get(input.getSessionId());
        if (socket == null) {
            LOG.debug("[Desktop] no socket for session {}, drop: {}", input.getSessionId(), node.get("type").getString());
            return;
        }
        try {
            socket.send(node.toJson());
        } catch (Throwable e) {
            LOG.debug("[Desktop] send failed for session {}: {}", input.getSessionId(), e.getMessage());
        }
    }
}
