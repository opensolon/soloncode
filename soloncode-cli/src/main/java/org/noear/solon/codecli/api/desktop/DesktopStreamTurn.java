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
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面端流回合执行器：把一次 prompt 的订阅、事件转换与终态收尾集中到一处。
 *
 * <p>从原 {@code WsGate} 中拆出主输入流 / HITL 恢复流 / 命令 fallback 流三段
 * 几乎相同的订阅逻辑，保持时序逐字一致：begin/subscribe 建流 → doFinally 归还
 * disposable → doOnNext 事件分发（RunEnd 优先，HITL 挂起时发 hitl 事件）→
 * doOnComplete/doOnError 经 terminalSent CAS 只发一次 done/error → 订阅后写回
 * session 的 disposable 并 dispose 旧流。</p>
 *
 * <p>终态事件结构（done/error/hitl 的字段与 JSON 顺序）与原实现零变化。
 * cwd 为空的容忍性差异：主输入流的 cwd 恒非空（会话默认 "."），统一为
 * 非空才注入 toolContext，与原三处写法取交集，行为不变。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopStreamTurn {
    static final String SESSION_ATTR_RUN_MODE = "_desktop_run_mode";

    private final DesktopStreamHub streamHub;
    private final DesktopEventConverter converter;

    DesktopStreamTurn(DesktopStreamHub streamHub, DesktopEventConverter converter) {
        this.streamHub = streamHub;
        this.converter = converter;
    }

    /**
     * 执行一次流回合并登记 disposable。
     *
     * @param hitlResume true 表示 HITL 审批后的续跑（复用既有流状态 subscribe，不再 begin）
     */
    Disposable run(AgentSession session, ReActAgent agent, ChatModel chatModel,
                   Prompt prompt, String sessionId, String cwd, String reasoningEffort,
                   WebSocket socket, boolean hitlResume,
                   HITLInterceptor desktopHitlInterceptor) {
        AtomicBoolean terminalSent = new AtomicBoolean(false);
        if (hitlResume) {
            streamHub.subscribe(sessionId, socket);
        } else {
            streamHub.begin(sessionId, socket);
        }
        Disposable disposable = agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);
                    applyRunMode(o, session, desktopHitlInterceptor);
                    if (Assert.isNotEmpty(cwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, cwd);
                    }
                    applyReasoningEffort(o, reasoningEffort);
                })
                .stream()
                .doFinally(signal -> session.attrs().remove("disposable"))
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
                .doOnComplete(() -> sendDoneIfNeeded(terminalSent, sessionId,
                        chatModel.getConfig().getNameOrModel(), 0, 0))
                .doOnError(err -> sendErrorIfNeeded(terminalSent, sessionId, err))
                .subscribe();

        Disposable old = (Disposable) session.attrs().put("disposable", disposable);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
        return disposable;
    }

    private void onRunEndEvent(RunEndEvent runEndEvent, AgentSession session, String finalSessionId,
                               AtomicBoolean terminalSent) {
        ReActTrace trace = runEndEvent.getTrace();
        Long start_time = trace.getOriginalPrompt().attrAs("start_time");
        long elapsed = start_time != null ? System.currentTimeMillis() - start_time : 0;
        long totalTokens = trace.getMetrics() != null ? trace.getMetrics().getTotalTokens() : 0;

        if (HITL.isHitl(session)) {
            HITLTask task = HITL.getPendingTask(session);
            if (task != null && terminalSent.compareAndSet(false, true)) {
                String command = "bash".equals(task.getToolName())
                        ? String.valueOf(task.getArgs().get("command"))
                        : null;
                streamHub.emit(finalSessionId, new ONode().set("type", "hitl")
                        .set("sessionId", finalSessionId)
                        .set("callId", task.getCallUuid())
                        .set("toolName", task.getToolName())
                        .set("command", command)
                        .set("comment", task.getComment())
                        .toJson());
            }
            return;
        }

        sendDoneIfNeeded(terminalSent, finalSessionId,
                trace.getOptions().getChatModel().getNameOrModel(), totalTokens, elapsed);
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
