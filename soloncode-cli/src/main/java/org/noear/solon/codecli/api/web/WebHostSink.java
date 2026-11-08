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
package org.noear.solon.codecli.api.web;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.host.Disposition;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.channel.ImMessages;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.session.HostAttrs;
import org.noear.solon.codecli.session.SessionRewindService;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.codecli.util.TraceUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Web / IM 的会话级输出（HarnessHost 的 {@link HostSink}）：把「发生了什么」渲染成 SAEP 事件与 IM 状态信号。
 *
 * <p>编排（受理、开流、终态、续发、两段式中断、后台轮次）全部在 HarnessHost；本类只决定呈现：</p>
 * <ul>
 *   <li>受理后才写回复路由与会话级选择（被排队 / 拒绝的输入不得改写正在运行会话的选择）；</li>
 *   <li>所有「没有开流」的结局都要给前端一个终态包（前端 POST 前就置了流式态，只有 system.done 会复位）；</li>
 *   <li>命令回执自成一轮时用完整的 reset … done 信封；运行中的任务旁边执行时不解封、不抢发 done；</li>
 *   <li>IM 看不到 web 的排队 / loading：受理、排队、插话、拒收都给来源渠道一条状态信号。</li>
 * </ul>
 *
 * <p>每轮的状态（事件映射管道、图片回复轮次、取消回执）在 {@link WebRunSink} 里。</p>
 *
 * @author noear
 * @since 2026.11
 */
final class WebHostSink implements HostSink {
    private static final Logger LOG = LoggerFactory.getLogger(WebHostSink.class);

    /** 命令没有输出时的兜底回执 */
    static final String COMMAND_DONE_TEXT = "命令执行完成";

    /** 命令触发的任务撞上在跑的流时的提示（与迁移前一致） */
    static final String BUSY_TASK_IGNORED_TEXT = "当前有任务正在执行，本次触发已忽略";

    private final WebGate gate;

    WebHostSink(WebGate gate) {
        this.gate = gate;
    }

    // ==================== 会话级 ====================

    @Override
    public void admitted(HostInput input) {
        WorkspaceContext ctx = gate.contextOf(input.getTenant());
        AgentSession session = input.getSession();
        if (ctx == null || session == null) {
            return;
        }

        // 回放：IM / 外部入口与队列续发的消息前端没有本地上屏，这里补一条用户消息
        if (input.isDispatched()) {
            InputOrigin o = input.getQueueItem().getOrigin();
            gate.emitToClient(ctx, input.getSessionId(),
                    WebEvent.ofUserInput(input.getQueueItem().getId(), input.getText(), o == null ? null : o.getChannel()));
        } else if (WebInputAttrs.isEcho(input)) {
            gate.emitToClient(ctx, input.getSessionId(), WebEvent.ofUserInput(input.getText(), input.getOrigin().getChannel()));
            // IM 侧感知补齐：受理后给来源端一条「已收到」；斜杠命令自带回执，不重复提示
            if (input.getDisposition() == Disposition.STARTED && input.isCommand() == false) {
                signal(ctx, input, ImStatus.ACCEPTED, null);
            }
        }

        // 忙时可执行命令在运行中的任务旁边执行：不能改写正在运行任务的回复目标与会话选择
        if (input.isBesideRunning() == false) {
            setReplyRoute(session, input.getOrigin());

            if (Assert.isNotEmpty(input.getSelectedModel())) {
                ctx.getEngine().setSessionModel(session, input.getSelectedModel());
            }
            // IM 等未携带该字段的输入为 null，不能把 web 端已选的子代理清空
            if (input.getSelectedAgent() != null) {
                ctx.getEngine().setSessionAgent(session, input.getSelectedAgent());
            }
            String effort = input.getAttr(WebInputAttrs.REASONING_EFFORT);
            ReasoningSupportUtil.putSessionEffort(session, effort, effort != null);
            String mode = input.getAttr(WebInputAttrs.THINKING_MODE);
            ReasoningSupportUtil.putSessionThinkingMode(session, mode, mode != null);
        }

        // 命令执行了但没有回执：给一个兜底提示（有回执的走 reply）
        if (input.getDisposition() == Disposition.REPLY && input.hasReply() == false) {
            renderReply(ctx, input, COMMAND_DONE_TEXT);
        }
    }

    @Override
    public void reply(HostInput input, String text) {
        WorkspaceContext ctx = gate.contextOf(input.getTenant());
        if (ctx != null && input.getSession() != null) {
            renderReply(ctx, input, text);
        }
    }

    /**
     * 仅有回执的一轮（命令已执行，或在本渠道不可用）。
     *
     * <p>此刻没有 agent 流在跑：用完整的「reset ... done」信封包住它——前端在上一轮 done 之后会丢弃后续 chunk，
     * 命令事件不带 runId，缺了 reset 时远端触发（IM/API）的回执在 Web 端会静默消失。
     * 会话繁忙（流式期间来一条 /status）：本轮归那条流，既不解封也不抢发 done。</p>
     */
    private void renderReply(WorkspaceContext ctx, HostInput input, String text) {
        AgentSession session = input.getSession();
        String sid = input.getSessionId();
        // 受理那一刻的事实（不再现查）：不在运行中的任务旁边执行 = 这条回执自成一轮
        boolean ownTurn = input.isBesideRunning() == false;
        if (ownTurn) {
            gate.beginStreamTurn(ctx, session);
        }

        if ("rewind".equals(input.getCommandName())) {
            // rewind 走特殊通道：发送 rewind 事件让前端同步删除 DOM（加一条删掉自己发出的那条）
            ParsedCommand cmd = input.getCommand();
            int count = SessionRewindService.rewindReceiptCount(cmd != null && cmd.argCount() > 0 ? cmd.argAt(0) : null);
            // 被回退的那一轮的执行过程还留在上下文快照里：不清掉的话刷新页面会把它原样回放回来
            TraceUtil.removeCurrentTrace(session);
            session.updateSnapshot();
            gate.emitToClient(ctx, sid, WebEvent.ofRewind(count));
        } else {
            gate.emitToClient(ctx, sid, WebEvent.ofCommand(text));
            InputOrigin o = input.getOrigin();
            if (ownTurn) {
                gate.getStreamBuilder().replyToBoundChannel(ctx, sid, text, true);
            } else {
                // 忙态命令使用自身的回复目标，不消费运行任务的路由
                gate.getStreamBuilder().replyToBoundChannel(ctx, sid, text, true,
                        o.getChannel(), o.getSenderId(), o.getReplyTarget(), o.getMessageId());
            }
        }

        if (ownTurn) {
            gate.getEventPublisher().emitDoneOnce(ctx, session);
        }
    }

    @Override
    public void deferred(HostInput input, int position) {
        WorkspaceContext ctx = gate.contextOf(input.getTenant());
        if (ctx == null) {
            return;
        }
        gate.emitToClient(ctx, input.getSessionId(), WebEvent.ofUserInput(input.getText(), input.getOrigin().getChannel()));
        if (input.getOrigin().isIm()) {
            // IM 看不到排队状态：把位次主动推给来源端（位置从 1 计，前面还有 position-1 条）。
            // 命令引导（/steer、/interrupt）同一轮忙态只教学一次，避免连发多条时刷屏。
            boolean withCommandHint = gate.getStreamBuilder().claimCommandHint(input.getSession());
            signal(ctx, input, ImStatus.QUEUED, ImMessages.queued(position - 1, withCommandHint));
        }
    }

    @Override
    public void steered(HostInput input) {
        // 不回放 user_input：web 端由 steer_applied 就地渲染 steer-note，提前推会重复上屏
        WorkspaceContext ctx = gate.contextOf(input.getTenant());
        if (ctx != null) {
            signal(ctx, input, ImStatus.STEERED, null);
        }
    }

    @Override
    public void rejected(HostInput input, RejectReason reason, String detail) {
        WorkspaceContext ctx = gate.contextOf(input.getTenant());
        if (ctx == null) {
            LOG.warn("[WebGate] input rejected for unloaded workspace {}: {}", input.getTenant(), reason);
            return;
        }
        if (reason == RejectReason.BUSY && input.isBesideRunning()) {
            // 忙时可执行命令触发了任务（如 /continue），而会话已有流在跑：本次触发被忽略。
            // 与迁移前一致只给一条命令提示：不弹错误、不发终态（终态归正在跑的那条流）
            gate.emitToClient(ctx, input.getSessionId(), WebEvent.ofCommand(BUSY_TASK_IGNORED_TEXT));
            if (input.getOrigin().isIm()) {
                signal(ctx, input, ImStatus.REJECTED, null);
            }
            return;
        }
        if (reason == RejectReason.HITL_PENDING && input.getOrigin().isIm()) {
            // IM 消息撞上待审批：只告知来源端；Web 端正停在审批卡上，不追加错误、不发终态
            signal(ctx, input, ImStatus.HITL_PENDING, null);
            return;
        }
        gate.emitToClient(ctx, input.getSessionId(), WebEvent.ofError(reason.code(), detail));
        // 来源端（IM）也要知道本轮未受理，否则只能盲等
        signal(ctx, input, reason == RejectReason.HITL_PENDING ? ImStatus.HITL_PENDING : ImStatus.REJECTED, null);
        // 必须有终态包：前端在 POST 之前就把 isStreaming 置真，只有 system.done 会复位。
        // 漏发 done 会让输入面板永久停在流式态，后续输入全部被当成 steer 插话。
        doneGuarded(ctx, input);
    }

    @Override
    public void hitl(HostInput input, List<HitlCard> cards) {
        // Web 首次挂起时已由事件流推送全部审批卡（WebEventMapper），这里不重复下发
    }

    @Override
    public void hitlExpired(HostInput input) {
        // 过期审批卡（任务已结束，前端残留的卡仍可点击）：告诉前端并补发终态包，否则前端停在等待态
        WorkspaceContext ctx = gate.contextOf(input.getTenant());
        if (ctx != null) {
            gate.emitToClient(ctx, input.getSessionId(), WebEvent.ofError("hitl_stale", "审批卡已失效（任务已结束）"));
            doneGuarded(ctx, input);
        }
    }

    // ==================== 轮次级 ====================

    @Override
    public RunSink open(HostRun run) {
        WorkspaceContext ctx = gate.contextOf(run.getTenant());
        if (ctx == null) {
            // 工作区已被回收（LRU）：不复活它，本轮输出只记日志
            LOG.warn("[WebGate] run output dropped, workspace not in memory: {}", run);
            return RunSink.NONE;
        }
        // 只建对象：本轮可能在调度窗口内就被取消；流门复位在第一次输出时做（WebRunSink）
        return new WebRunSink(gate, ctx, run);
    }

    // ==================== 工具 ====================

    private void doneGuarded(WorkspaceContext ctx, HostInput input) {
        AgentSession session = input.getSession();
        if (session != null) {
            gate.getEventPublisher().emitDoneGuarded(ctx, session);
        } else {
            gate.emitToClient(ctx, input.getSessionId(), WebEvent.ofDone());
        }
    }

    private void signal(WorkspaceContext ctx, HostInput input, ImStatus status, String detail) {
        InputOrigin o = input.getOrigin();
        gate.getStreamBuilder().signalOriginChannel(ctx, input.getSessionId(), status, detail,
                o.getChannel(), o.getSenderId(), o.getReplyTarget(), o.getMessageId());
    }

    /**
     * 新一轮输入受理：重置 IM 终态去重门，并按来源写回复路由。
     *
     * <p>WEB / Loop / Goal 等来源没有 IM 定向目标：清掉上一轮残留的路由即可（终态仍广播到所有绑定该会话的 IM 通道）。</p>
     */
    static void setReplyRoute(AgentSession session, InputOrigin origin) {
        if (session == null) return;
        session.attrs().remove(WebStreamBuilder.ATTR_IM_TERMINAL_SENT);
        if (origin == null || origin.isIm() == false) {
            session.attrs().remove(HostAttrs.ATTR_REPLY_ROUTE);
            return;
        }
        session.attrs().put(HostAttrs.ATTR_REPLY_ROUTE, origin.toMap());
    }
}
