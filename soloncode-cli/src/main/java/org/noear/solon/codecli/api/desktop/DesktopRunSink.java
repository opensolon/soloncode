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
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.codecli.session.HostAttrs;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;

/**
 * 桌面端一轮运行的输出（每轮一个，由 {@link DesktopHostSink#open} 创建）：ReAct 事件 → WS 协议 JSON，经
 * {@link DesktopStreamHub} 投递（带序号、可断线回放）。
 *
 * <p>编排（订阅前领凭据、开流前失败回滚、续发积压、停止屏障、后台轮次）都在 HarnessHost；本类只决定呈现。
 * 时序由框架保证：{@code [reply] → prepare → event* → finished}，回调串行、finished 恰好一次、停止之后不再有事件，
 * 所以字段都是普通字段。</p>
 * <ul>
 *   <li>用户轮次：prepare 时开流（审批续跑 {@code isResume} 续用原来的流与序号，否则新开一段），
 *       装配桌面请求选项（模型、协作提示、运行模式 / 审批拦截、cwd、推理强度）；</li>
 *   <li>终态只在 finished 里、只看 {@link RunOutcome}（不解析 RunEnd）：挂起 → 第一张 hitl 卡；完成 → done（模型、用量）；
 *       出错 → error；用户停止 → reason + done（与 Web 同一写法：停止入口只在没有在跑的轮次时兜底）；</li>
 *   <li>goal 轮次：沿用 {@code startGoalStream} 开的流，先发 {@code goal_round}，只推过程事件、不发终态
 *       （goal 生命周期由 {@link DesktopGoalBroadcaster} 广播）。</li>
 * </ul>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopRunSink implements RunSink {
    static final String SESSION_ATTR_RUN_MODE = HostAttrs.ATTR_DESKTOP_RUN_MODE;

    private final WsGate gate;
    private final HostRun run;
    private final AgentSession session;
    private final String sessionId;
    private final WebSocket socket;
    private final boolean goal;
    private boolean begun;
    private String modelName;

    DesktopRunSink(WsGate gate, HostRun run, WebSocket socket) {
        this.gate = gate;
        this.run = run;
        this.session = run.getSession();
        this.sessionId = run.getSessionId();
        this.socket = socket;
        this.goal = "goal".equals(run.getProgramChannel());
    }

    /** 本轮开始（第一次输出时，幂等） */
    private void begin() {
        if (begun) {
            return;
        }
        begun = true;
        if (goal) {
            emit(new ONode().set("type", "goal_round").set("sessionId", sessionId));
        } else if (run.getInput().isResume()) {
            // 审批续跑：上一段停在 hitl 卡上（不是终态），续用原来的流与序号，否则前端按序号丢帧
            gate.streamHub.subscribe(sessionId, socket);
        } else {
            gate.streamHub.begin(sessionId, socket);
        }
    }

    @Override
    public void reply(String text) {
        // 命令触发的任务不发独立回执（与迁移前一致）：命令输出由任务流自身携带
    }

    @Override
    public void prepare(RunPrep prep) {
        begin();
        if (prep.request() == null) {
            // 仅续流路径可能出现：没有可续的挂起任务（按开流前失败收尾，发 error）
            throw new IllegalStateException("No pending task to resume");
        }
        HarnessEngine engine = prep.engine();
        String cwd = engine.getSessionCwd(session);
        String effort = effortOf();

        Prompt prompt = prep.prompt();
        if (prompt != null) {
            // 续流（如 /continue）正文为空，靠会话挂起的 trace 继续，此时没有 prompt
            ReasoningSupportUtil.applyToPrompt(prompt, effort);
        }

        if (goal) {
            prep.request().options(o -> {
                DesktopCollaborationPrompt.applyTo(o);
                o.toolContextPut(HarnessEngine.ATTR_CWD, cwd);
                ReasoningSupportUtil.applyToOptions(o, effort);
            });
            return;
        }

        // 模型用引擎生效值（命令声明 → 会话选择 → 默认）；取不到时用本次输入的选择或会话选择
        String selected = Assert.isNotEmpty(prep.modelName()) ? prep.modelName()
                : (run.getInput().getSelectedModel() != null ? run.getInput().getSelectedModel() : engine.getSessionModel(session));
        ChatModel chatModel = engine.getModelOrDefInstance(selected);
        modelName = chatModel.getConfig().getNameOrModel();
        prep.request().options(o -> {
            o.chatModel(chatModel);
            DesktopCollaborationPrompt.applyTo(o);
            applyRunMode(o);
            if (Assert.isNotEmpty(cwd)) {
                o.toolContextPut(HarnessEngine.ATTR_CWD, cwd);
            }
            ReasoningSupportUtil.applyToOptions(o, effort);
        });
    }

    /** 推理强度：本次输入显式给出的；审批续跑沿用会话的 */
    private String effortOf() {
        String effort = run.getInput().getAttr(DesktopInputRouter.ATTR_REASONING_EFFORT);
        if (effort == null && (goal || run.getInput().isResume())) {
            effort = ReasoningSupportUtil.getSessionEffort(session);
        }
        return effort;
    }

    @Override
    public void event(AgentEvent event) {
        DesktopEventConverter converter = gate.eventConverter;
        String msg = null;
        if (event instanceof ReasonDeltaEvent) {
            msg = converter.onReasonDeltaEvent((ReasonDeltaEvent) event, sessionId);
        } else if (event instanceof ToolCallStartEvent) {
            msg = converter.onToolCallStartEvent((ToolCallStartEvent) event, sessionId);
        } else if (event instanceof ToolCallEndEvent) {
            msg = converter.onToolCallEndEvent((ToolCallEndEvent) event, sessionId);
        } else if (event instanceof ReasonEndEvent) {
            msg = converter.onReasonEndEvent((ReasonEndEvent) event, sessionId);
        }
        if (Assert.isNotEmpty(msg)) {
            gate.streamHub.emit(sessionId, msg);
        }
    }

    @Override
    public void finished(RunOutcome outcome) {
        if (goal) {
            return; // goal 轮次不发终态（生命周期由 DesktopGoalBroadcaster 广播）
        }
        if (outcome.isCancelled()) {
            if (outcome.getCancelCause() == RunOutcome.CancelCause.INTERRUPT_ALL) {
                stopped(gate, socket, sessionId, modelName != null ? modelName
                        : gate.engine.getMainModel().getConfig().getNameOrModel());
            }
            return; // 其它取消（程序取消、腾位）不发终态，与迁移前一致
        }
        if (outcome.isFailed()) {
            begin(); // 开流前失败也要让前端看得见
            Throwable e = outcome.getError();
            String text = e == null ? "任务执行失败" : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            emit(new ONode().set("type", "error").set("sessionId", sessionId).set("text", text));
            return;
        }
        if (outcome.getHitlCards().isEmpty() == false) {
            // 审批挂起：逐张提问，先推第一张（其余的在每次决策后由 DesktopHostSink#hitl 推）
            emit(hitlEvent(sessionId, outcome.getHitlCards().get(0)));
            return;
        }
        emit(doneEvent(sessionId, modelName, outcome.getUsage().getTotalTokens(), outcome.getUsage().getElapsedMs()));
    }

    private void emit(ONode node) {
        gate.streamHub.emit(sessionId, node.toJson());
    }

    static ONode doneEvent(String sessionId, String model, long totalTokens, long elapsedMs) {
        return new ONode().set("type", "done")
                .set("sessionId", sessionId)
                .set("modelName", model)
                .set("totalTokens", totalTokens)
                .set("elapsedMs", elapsedMs);
    }

    /**
     * 用户停止的终态：reason + done。本轮的流还在就进流（带序号），否则直接发给请求连接。
     * 停止入口在没有在跑的轮次时也用它兜底。
     */
    static void stopped(WsGate gate, WebSocket socket, String sessionId, String model) {
        String reason = new ONode().set("type", "reason")
                .set("sessionId", sessionId)
                .set("text", "[Task interrupted]")
                .toJson();
        String done = doneEvent(sessionId, model, 0, 0).toJson();
        for (String json : new String[]{reason, done}) {
            if (gate.streamHub.emit(sessionId, json) == false && socket != null) {
                try {
                    socket.send(json);
                } catch (Throwable e) {
                    // 连接已断：前端重连后以会话状态为准
                }
            }
        }
    }

    private void applyRunMode(ReActOptionsAmend options) {
        Object configuredMode = session.attrs().get(SESSION_ATTR_RUN_MODE);
        String runMode = DesktopRunModes.normalize(configuredMode == null ? null : String.valueOf(configuredMode));
        boolean planning = "plan".equals(runMode);
        options.planningMode(planning);
        if (planning) {
            options.planningInstruction("只分析问题并输出可执行计划，不调用文件、命令或外部工具，不修改任何状态。");
            return;
        }

        // 请求级拦截只作用于桌面会话，不修改 HarnessEngine 的全局开关，Web 行为保持不变。
        if ("default".equals(runMode) || "auto".equals(runMode)) {
            // Interceptor 按类型去重；用一个桌面实例替换本次请求中的全局 HITL，防止重复挂起。
            options.interceptorAdd(gate.desktopHitlInterceptor);
        }
    }

    /**
     * hitl 事件载荷（字段与顺序沿用原实现，不变更前端协议）
     */
    static ONode hitlEvent(String sessionId, HitlCard card) {
        return new ONode().set("type", "hitl")
                .set("sessionId", sessionId)
                .set("callId", card.getCallId())
                .set("toolName", card.getToolName())
                .set("command", card.getCommand())
                .set("comment", card.getComment());
    }
}
