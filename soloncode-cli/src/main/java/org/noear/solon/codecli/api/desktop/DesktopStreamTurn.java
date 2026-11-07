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
import org.noear.solon.codecli.session.HostAttrs;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.RunTicket;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面端流回合执行器：把一次 prompt 的订阅、事件转换与终态收尾集中到一处。
 *
 * <p>从原 {@code WsGate} 中拆出主输入流 / HITL 恢复流 / 命令 fallback 流三段
 * 几乎相同的订阅逻辑：停掉本会话上一轮遗留流 → begin/subscribe 建流 →
 * doOnNext 事件分发（RunEnd 优先，HITL 挂起时发 hitl 事件）→
 * doOnComplete/doOnError 经 terminalSent CAS 只发一次 done/error。</p>
 *
 * <p><b>运行句柄不再由本类持有</b>：{@code ReActRequest#stream()} 订阅时自动登记到
 * {@code SessionRuns}，结束时按值注销。本类只负责「新回合开始前停掉上一轮」
 * （{@link SessionRuns#cancelAll}）与取消轮次的终态去重。</p>
 *
 * <p><b>取消以 complete 收尾</b>：框架把「取消」当作终止而不是失败，因此被顶掉/被中断的
 * 流仍会走 doOnComplete。本类用取消代数判定自己是否被取消，取消时不再发 done
 * （中断入口已同步发过）。</p>
 *
 * <p>终态事件结构（done/error/hitl 的字段与 JSON 顺序）与原实现零变化。
 * cwd 为空的容忍性差异：主输入流的 cwd 恒非空（会话默认 "."），统一为
 * 非空才注入 toolContext，与原三处写法取交集，行为不变。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopStreamTurn {
    static final String SESSION_ATTR_RUN_MODE = HostAttrs.ATTR_DESKTOP_RUN_MODE;

    private final HarnessEngine engine;
    private final DesktopStreamHub streamHub;
    private final DesktopEventConverter converter;

    DesktopStreamTurn(HarnessEngine engine, DesktopStreamHub streamHub, DesktopEventConverter converter) {
        this.engine = engine;
        this.streamHub = streamHub;
        this.converter = converter;
    }

    /**
     * 执行一次流回合。
     *
     * <p>运行句柄由 {@code ReActRequest} 自行登记到 {@code SessionRuns}，本方法只负责
     * 订阅、事件转换与终态收尾。</p>
     *
     * <p>请求装配（session/模型/retry/maxTurns）由 {@code engine.handle} 在调用前完成，
     * 本方法只做桌面端请求级补充：运行模式、HITL 拦截器、cwd、推理强度。</p>
     *
     * <p>本方法不负责续发积压：那要等运行句柄注销后才判得到空闲，
     * 由调用方在开流前经 {@code InputOutcome#afterRun} 登记（本方法的 doFinally 里会话仍算忙）。</p>
     *
     * @param hitlResume true 表示 HITL 审批后的续跑（复用既有流状态 subscribe，不再 begin）
     */
    void run(AgentSession session, ReActRequest request, ChatModel chatModel,
             String sessionId, String cwd, String reasoningEffort,
             WebSocket socket, boolean hitlResume,
             HITLInterceptor desktopHitlInterceptor) {
        AtomicBoolean terminalSent = new AtomicBoolean(false);

        // 同一会话上的上一轮若还在跑（连发输入 / 桌面端重发），先停掉再开新流：
        // 不停会造成两条流的 doOnNext 同时往同一个 streamHub 写，前端两个轮次的输出交错。
        //
        // 用 cancelStream（推进取消代数）而不是 preemptStream：取消现在以 complete 收尾，
        // 被顶掉的旧流仍会走 doOnComplete。旧流要能据此判定「自己是被取消的」，
        // 否则它会往刚 begin 的新一轮里发一个 done，前端提前收尾并丢弃后续输出。
        // 这里不会“自己拒掉自己”：本调用在 request.stream() 之前，新流的代数基准在之后才取。
        engine.cancelStream(session);

        if (hitlResume) {
            streamHub.subscribe(sessionId, socket);
        } else {
            streamHub.begin(sessionId, socket);
        }

        // 开一轮（须在 stream() 之前）：取消后框架以 complete 收尾，会触发下面的
        // doOnComplete。但取消路径（DesktopInputRouter 的 interrupt 分支）已经自己发过
        // reason + done，若这里再发一个 done，前端会收到两个终态帧。
        final RunTicket ticket = RunTicket.begin(session);

        request.options(o -> {
                    // 模型已由 handle 装配；这里仅在宿主显式解析出模型时覆盖（幂等，零差异）
                    if (chatModel != null) {
                        o.chatModel(chatModel);
                    }
                    DesktopCollaborationPrompt.applyTo(o);
                    applyRunMode(o, session, desktopHitlInterceptor);
                    if (Assert.isNotEmpty(cwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, cwd);
                    }
                    applyReasoningEffort(o, reasoningEffort);
                })
                .stream()
                .doOnNext(event -> {
                    if (event instanceof RunEndEvent) {
                        onRunEndEvent((RunEndEvent) event, session, sessionId, terminalSent);
                        return;
                    }
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
                        streamHub.emit(sessionId, msg);
                    }
                })
                .doOnComplete(() -> {
                    if (ticket.isCancelled()) {
                        // 本轮是被取消的：中断入口已同步发过 reason + done，不再重复终态
                        return;
                    }
                    sendDoneIfNeeded(terminalSent, sessionId,
                            chatModel.getConfig().getNameOrModel(), 0, 0);
                })
                .doOnError(err -> sendErrorIfNeeded(terminalSent, sessionId, err))
                .doFinally(signal -> {
                    // 收口本轮所属的程序派发（积压续发的那一轮；非派发为空操作，幂等）
                    ticket.settle(true);
                    // 不在这里续发积压：此时运行句柄尚未注销（见 InputOutcome#afterRun）
                })
                .subscribe();
    }

    private void onRunEndEvent(RunEndEvent runEndEvent, AgentSession session, String finalSessionId,
                               AtomicBoolean terminalSent) {
        ReActTrace trace = runEndEvent.getTrace();
        Long start_time = trace.getOriginalPrompt().attrAs("start_time");
        long elapsed = start_time != null ? System.currentTimeMillis() - start_time : 0;
        long totalTokens = trace.getMetrics() != null ? trace.getMetrics().getTotalTokens() : 0;

        // 判据用 next() 而非 hasPendingHitl()：残留决策（整批已决策但续流未发生）
        // 也要推卡给前端重批/透传，否则残留决策永远等不到续流（next 含回退首项语义）
        HitlCard card = HitlCard.next(session);
        if (card != null) {
            if (terminalSent.compareAndSet(false, true)) {
                streamHub.emit(finalSessionId, hitlEvent(finalSessionId, card).toJson());
            }
            return;
        }

        sendDoneIfNeeded(terminalSent, finalSessionId,
                trace.getOptions().getChatModel().getNameOrModel(), totalTokens, elapsed);
    }

    /**
     * 取下一张待审批的卡（批挂起时可能有多个）。
     *
     * <p>已决策项跳过（否则会重复推同一张卡），整批已决策时回退到首项供续流透传。
     * 实现已收归 {@link HitlCard#next}，本方法仅为兼容旧调用保留。</p>
     *
     * @return 待处理任务；无挂起时返回 null
     */
    static HitlCard nextPendingHitlTask(AgentSession session) {
        return HitlCard.next(session);
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

    private void sendDoneIfNeeded(AtomicBoolean terminalSent, String sessionId,
                                  String modelName, long totalTokens, long elapsedMs) {
        if (!terminalSent.compareAndSet(false, true)) {
            return;
        }

        streamHub.emit(sessionId, new ONode().set("type", "done")
                .set("sessionId", sessionId)
                .set("modelName", modelName)
                .set("totalTokens", totalTokens)
                .set("elapsedMs", elapsedMs)
                .toJson());
    }

    private void sendErrorIfNeeded(AtomicBoolean terminalSent, String sessionId,
                                   Throwable error) {
        if (!terminalSent.compareAndSet(false, true)) {
            return;
        }

        String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        streamHub.emit(sessionId, new ONode().set("type", "error")
                .set("sessionId", sessionId)
                .set("text", errorMessage)
                .toJson());
    }

    private void applyReasoningEffort(ReActOptionsAmend options, String reasoningEffort) {
        ReasoningSupportUtil.applyToOptions(options, reasoningEffort);
    }

    private void applyRunMode(ReActOptionsAmend options, AgentSession session,
                              HITLInterceptor desktopHitlInterceptor) {
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
            options.interceptorAdd(desktopHitlInterceptor);
        }
    }
}
