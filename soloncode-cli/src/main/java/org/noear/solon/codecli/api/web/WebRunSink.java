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

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptPlan;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 一轮运行的 Web / IM 输出（每轮一个，由 {@link WebHostSink#open} 创建）。
 *
 * <p>时序由 HarnessHost 保证：{@code open → [reply] → prepare → event* → finished}，各回调串行、finished 恰好一次；
 * 停止之后不再有 event，终态为 CANCELLED。所以本类的字段都是普通字段，也不需要在停止前做任何准备。</p>
 * <ul>
 *   <li>第一次输出（reply 或 prepare）时复位后端 done 门与前端流门（system.reset）：调度窗口内就被取消、
 *       又没有回执的一轮根本不开始，只补一个 done；</li>
 *   <li>prepare：会话模型回写、装配 SAEP 管道（推理强度、图片拦截器、cwd）；</li>
 *   <li>finished：失败补 IM 终态；用户停止且已开流时发 error → IM 取消回执 → trace；最后发 done 并释放管道。</li>
 * </ul>
 *
 * @author noear
 * @since 2026.11
 */
final class WebRunSink implements RunSink {
    private static final Logger LOG = LoggerFactory.getLogger(WebRunSink.class);

    private final WebGate gate;
    private final WorkspaceContext ctx;
    private final HostRun run;
    private final AgentSession session;
    private final String sessionId;
    private boolean begun;
    private WebRunPipeline pipeline;

    WebRunSink(WebGate gate, WorkspaceContext ctx, HostRun run) {
        this.gate = gate;
        this.ctx = ctx;
        this.run = run;
        this.session = run.getSession();
        this.sessionId = run.getSessionId();
    }

    /** 本轮开始（第一次输出时，幂等） */
    private void begin() {
        if (begun) {
            return;
        }
        begun = true;
        if (run.isProgram()) {
            // goal / loop 的提示词是「已受理的文本」：受理后才改写路由与回放用户消息。
            // 后台轮次不是 IM 来源：清掉上一轮残留的 IM 定向路由（过程消息不外发，终态仍广播到绑定渠道）
            String label = programLabel(run.getProgramChannel());
            WebHostSink.setReplyRoute(session, InputOrigin.channel(label));
            gate.emitToClient(ctx, sessionId, WebEvent.ofUserInput(run.getInput().getText(), label));
        }
        gate.beginStreamTurn(ctx, session);
    }

    @Override
    public void reply(String text) {
        begin();
        // 命令触发的任务：回执照发、不抢发 done（收尾交给本轮）。
        // IM 回执必须按「非终态」发送：终态会封闭 IM 去重门，随后任务的最终答复会被吞掉。
        gate.emitToClient(ctx, sessionId, WebEvent.ofCommand(text));
        gate.getStreamBuilder().replyToBoundChannel(ctx, sessionId, text, false);
    }

    @Override
    public void prepare(RunPrep prep) {
        begin();
        HarnessEngine engine = prep.engine();
        // 会话级模型选择回写：生效值（命令声明 → 会话选择 → 默认），保持后续轮次（HITL 恢复、续发）同一口径
        if (prep.modelName() != null) {
            engine.setSessionModel(session, prep.modelName());
        }
        // 展示用模型句柄（trace 事件的 model 标签）：由生效名现取，取不到时用默认实例
        ChatModel chatModel = engine.getModelOrDefInstance(prep.modelName());
        String cwd = run.getInput().getAttr(WebInputAttrs.SESSION_CWD);
        pipeline = gate.getStreamBuilder().openPipeline(ctx, session, prep.request(), prep.prompt(),
                prep.agentName(), chatModel, cwd);
    }

    @Override
    public void event(AgentEvent event) {
        if (pipeline == null) {
            return;
        }
        for (WebEvent<?> e : pipeline.map(event)) {
            gate.emitToClient(ctx, sessionId, e);
        }
    }

    @Override
    public void finished(RunOutcome outcome) {
        try {
            if (begun == false) {
                if (outcome.isCancelled()) {
                    // 调度窗口内就被取消、又没有回执：本轮没开始，只补一个 done（前端 POST 前已置流式态）
                    gate.getEventPublisher().emitDoneGuarded(ctx, session);
                    return;
                }
                begin(); // 开流前失败：先解封前端流门，错误才看得见
            }
            if (outcome.isFailed()) {
                onFailed(outcome);
            } else if (outcome.getCancelCause() == RunOutcome.CancelCause.INTERRUPT_ALL && outcome.isStreamOpened()) {
                onUserStop(outcome);
            }
        } catch (Throwable e) {
            LOG.warn("[WebGate] run finish rendering failed: {}", run, e);
        } finally {
            if (pipeline != null) {
                pipeline.cleanup();
            }
            if (begun) {
                // 流级终态：每轮恰好一次（interrupt 的兜底 done 经同一去重门）
                gate.getEventPublisher().emitDoneOnce(ctx, session);
            }
        }
    }

    private void onFailed(RunOutcome outcome) {
        Throwable e = outcome.getError();
        LOG.error("Task fail: {}", e == null ? null : e.getMessage(), e);
        // 失败终态同步到 IM（与 web 一致），避免 IM 端停在「已读不回」
        if (pipeline != null) {
            pipeline.replyError(e);
        } else {
            gate.getStreamBuilder().replyErrorToBoundChannels(ctx, session, e);
        }
        gate.emitToClient(ctx, sessionId, e == null ? WebEvent.ofError("任务执行失败") : WebEvent.ofError(e));
    }

    /**
     * 用户停止（取消消息已由 HarnessHost 先于停流落库）：error → IM 取消回执 → trace。
     *
     * <p>IM 只会收到一条终态：停止受理后不再有事件，成功回执要么已在停止前发出（取消回执被去重门挡下），
     * 要么不会再发。其它方式的取消（程序取消、腾位）只发 done。</p>
     */
    private void onUserStop(RunOutcome outcome) {
        gate.emitToClient(ctx, sessionId, WebEvent.ofError(InterruptPlan.CANCEL_TEXT));
        try {
            gate.getStreamBuilder().prepareCanceledReply(ctx, session).run();
        } catch (Throwable e) {
            LOG.warn("[WebGate] canceled reply delivery failed for session {}", sessionId, e);
        }
        gate.emitToClient(ctx, sessionId, WebEvent.ofTrace(null, outcome.getUsage().getTotalTokens(),
                outcome.getUsage().getElapsedMs() / 1000, InterruptPlan.CANCEL_TEXT));
    }

    /** 后台轮次的展示来源名（与旧版保持一致：Goal / Loop） */
    static String programLabel(String channel) {
        if ("goal".equals(channel)) return "Goal";
        if ("loop".equals(channel)) return "Loop";
        return channel;
    }
}
