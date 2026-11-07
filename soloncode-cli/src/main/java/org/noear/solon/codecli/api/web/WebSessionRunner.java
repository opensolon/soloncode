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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.extension.goal.GoalOutcome;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.event.payload.SystemTracePayload;
import org.noear.solon.codecli.api.web.pipeline.ToolViewUtil;
import org.noear.solon.ai.harness.turn.SessionTurn;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.codecli.util.LogDirUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.solon.core.util.RunUtil;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 流回合的生命周期管理：开流门复位、异步订阅、同步捕获与失败收尾。
 *
 * <p>从 WebGate 拆出的流执行职责。流构建仍走 {@link WebStreamBuilder}，
 * 事件下发、队列续发、插话兜底均经组合根 {@link WebGate} 中转，
 * 与输出/队列组件保持单向依赖；订阅时序、MDC 日志作用域与
 * done 去重语义与拆分前逐字一致。</p>
 *
 * @author noear 2026/5/8 created
 */
class WebSessionRunner {
    private static final Logger LOG = LoggerFactory.getLogger(WebSessionRunner.class);

    private final WebGate gate;

    WebSessionRunner(WebGate gate) {
        this.gate = gate;
    }

    /**
     * 新开流统一入口：后端 done 门与前端流门必须成对重置。
     *
     * <p>后端 {@code resetStreamDoneSent} 只解开自己的 done 去重门，前端在收到上一轮
     * done 后会把该会话置为 _streamClosed=true 并丢弃后续所有 chunk。若只重置后端，
     * 同一会话第二段流（HITL 恢复、命令触发的 agent 任务、Loop/Goal 续跑等）在后端
     * 正常 emit，前端却全部静默丢弃 —— 表现为「任务跑着突然没输出、新开会话又正常」。
     * 因此这里在开流前额外下发 system.reset 解封前端。</p>
     */
    void beginStreamTurn(WorkspaceContext wsContext, AgentSession session) {
        gate.getEventPublisher().resetStreamDoneSent(session);

        if (session != null) {
            // 不再需要 steer 开流前交接：上一任务被 interrupt / 异常终止后的残留插话，
            // 由 harness 在新任务 onAgentStart 自动转排队，并经 SteerDroppedEvent 进入
            // WebEventMapper 的唯一映射分支（与任务正常结束的残留同一通道）。
            gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofResetStream());
        }
    }

    /**
     * 收口本轮所属的队列派发事务（幂等，非队列输入时为空操作）。
     *
     * <p>由 {@link SessionTurn#settle(boolean)} 承担：它在 {@code begin} 时已捕获
     * 「这一条流自己的」派发事务（有身份保证），拿不到时退回“收口会话当前的派发”——
     * 与改造前行为一致，不存在回退风险。</p>
     */
    private void settleTurn(WorkspaceContext wsContext, AgentSession session, SessionTurn turn, boolean started) {
        if (turn != null) {
            turn.settle(started);
        } else {
            gate.getQueueDispatcher().finishQueuedTurn(session, started);
        }
    }

    /**
     * 一轮流启动失败的收尾：发 error、归还句柄槽位、确保前端能收到 done。
     *
     * <p>订阅动作被调度到 boundedElastic 后，开流前的异常（如模型未配置、agent 不存在、
     * 管道装配失败）不再落入 {@code onChatInput} 的 catch，而是被调度器吞掉：若不处理，
     * composite 会长驻 attrs 使 busy 判定永久为真（Stop 也只能发假 done），
     * 且前端收不到任何终态包。</p>
     *
     * <p>「这是队列派发的一轮」不再用会话态判定（旧写法读 hasQueuedDispatch，历史上还
     * 误用过线程判定引发无限循环）：{@code turn} 是订阅前捕获的本轮派发事务，有身份保证，
     * 异步线程上同样可靠。非队列输入 turn 为 null，自然落到 drain 续发分支。</p>
     */
    void failStreamTurn(WorkspaceContext wsContext, AgentSession session, SessionTurn turn, Throwable e) {
        LOG.error("Task fail: {}", e.getMessage(), e);
        // 队列派发的启动失败：交给队列派发侧收口（claim 回滚），
        // 「不重试」由 drainLoop 的 START_FAILED 语义保证，宿主不再自己维护标记。
        settleTurn(wsContext, session, turn, false);

        gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofError(e));
        // 失败终态同步到 IM（与 web 一致），避免 IM 端停在「已读不回」
        gate.getStreamBuilder().replyErrorToBoundChannels(wsContext, session, e);

        gate.getEventPublisher().emitDoneGuarded(wsContext, session);
        // 启动失败的任务已回队首；不要立即重试同一失败任务形成无限循环。
        // turn 非空 = 本轮属于队列派发：claim 已回滚回队首，立即 drain 会领回同一条。
        if (turn == null) gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
    }

    /**
     * 执行 Agent 流式任务（异步）：注册句柄槽位后将订阅动作调度到 boundedElastic。
     *
     * <p>请求主体（session/模型/retry/maxTurns）已由 {@code engine.handle} 装配完成，
     * 本方法只负责流生命周期：订阅调度与失败收尾。运行句柄由 {@code ReActRequest} 自行登记，
     * 宿主不再维护任何句柄容器。</p>
     *
     * @param outcome 引擎装配结果（含 request 与生效的模型/子代理名）
     */
    void performAgentTaskAsync(WorkspaceContext wsContext, AgentSession session, String sessionCwd, InputOutcome outcome) {
        // 订阅前（调用线程内）开一轮：捕获取消代数基准与本轮派发事务。
        // 订阅动作被调度到别的线程异步执行，这段窗口内若发生 interrupt，本流到订阅时应立即放弃，
        // 而不是成为一条取消不掉的幽灵流；终态也必须收「这一条流自己的」派发事务。
        // 两件事均收归 harness（SessionTurn），宿主不再接触 SessionRuns / QueueDispatch。
        final SessionTurn turn = SessionTurn.begin(session);

        //agent 执行主体在 boundedElastic 线程上跑，订阅动作包在工作区日志作用域里：
        //同步源的整个管道执行发生在 subscribe() 调用栈内，故管道主体线程携带工作区标记
        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                doSubscribeAgentTask(wsContext, session, sessionCwd, outcome, turn);
            } catch (Throwable e) {
                //调度线程内的异常不会回到 onChatInput 的 catch，必须自行收尾（含释放本轮受理占位）
                outcome.discard();
                failStreamTurn(wsContext, session, turn, e);
            } finally {
                WorkspaceLogRouter.endScope(logScope);
            }
        };

        if (wsLogKey == null) {
            subscribeAction.run();
        } else {
            Schedulers.boundedElastic().schedule(subscribeAction);
        }
    }

    private void doSubscribeAgentTask(WorkspaceContext wsContext, AgentSession session, String sessionCwd, InputOutcome outcome, SessionTurn turn) {
        String sessionId = session.getSessionId();

        //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）。
        //与 sync 路径对称地补发终态：若 interrupt 已发过 done，会被 emitDoneOnce 的去重门
        //静默吞掉（无害）；若上游异常路径未发过，则前端不至因收不到任何事件而卡在等待态
        if (turn.isCancelled()) {
            LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
            outcome.discard();
            gate.getEventPublisher().emitDoneOnce(wsContext, session);
            return;
        }

        // 会话级模型选择回写：outcome 里是引擎已解析的生效值（命令声明 → 会话选择 → 默认），
        // 回写保持后续轮次（HITL 恢复、queue 续发）读到同一口径。
        if (outcome.getModelName() != null) {
            wsContext.getEngine().setSessionModel(session, outcome.getModelName());
        }
        // 展示用模型句柄（trace 事件的 model 标签）：由生效名现取，取不到时用默认实例
        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(outcome.getModelName());

        // 新开流前重置：后端 done 门 + 前端流门（否则上一轮 done 会让本轮输出被前端丢弃）
        beginStreamTurn(wsContext, session);

        // 任务结束后续发排队消息（IM 排队的后端调度点）：登记在「运行句柄注销之后」。
        // 不能写在下方 doFinally：那里会话仍算忙，drainSessionQueue 会直接返回。
        // 中断路径已先清空队列，此处 drain 无残留；正常结束则取队头续发。
        outcome.afterRun(() -> gate.getQueueDispatcher().drainSessionQueue(wsContext, session));

        // 运行句柄由 ReActRequest 自行登记到 SessionRuns，宿主只持有订阅句柄用于本地收尾
        gate.getStreamBuilder()
                .buildStreamFlux(wsContext, session, outcome, chatModel, sessionCwd)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    gate.emitToClient(wsContext, sessionId, line);
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    gate.emitToClient(wsContext, sessionId, WebEvent.ofError(e));
                    gate.getStreamBuilder().replyErrorToBoundChannels(wsContext, session, e);
                })
                .doFinally(s -> {
                    settleTurn(wsContext, session, turn, true);

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);
                    // 续发见上方 afterRun（此时运行句柄尚未注销）

                    //MDC 不在此处清理：Reactor 调度钩子会在任务结束时自动还原线程现场，
                    //提前 remove 反而会让同一任务后续日志丢掉工作区归属
                })
                .subscribe();
    }

    /**
     * 执行 Agent 流式任务（同步等待）：供 Loop 等需要捕获本轮最终文本的调用方使用。
     *
     * @return 捕获到的 finalAnswer 文本（无则返回空串）
     */
    String performAgentTaskSync(WorkspaceContext wsContext, AgentSession session, String sessionCwd, InputOutcome outcome) {
        GoalOutcome result = performAgentTaskSyncRound(wsContext, session, sessionCwd, outcome);
        return result == null ? "" : result.getText();
    }

    /**
     * 执行 Agent 流式任务（同步等待）并取回本轮客观事实（goal 使用）。
     *
     * <p>与 {@link #performAgentTaskSync} 是同一段执行，差别只在返回值：
     * goal 需要 token 与工具证据做完成判定，loop 只需最终文本。
     * 因此事实在流回调里就地累加、随返回值上交，不写会话 attrs。</p>
     *
     * @return 本轮结果；无正文时返回 null（不得伪造「有结果的轮」）
     */
    GoalOutcome performAgentTaskSyncRound(WorkspaceContext wsContext, AgentSession session, String sessionCwd, InputOutcome outcome) {
        return performAgentTaskSyncRound(wsContext, session, sessionCwd, outcome, null);
    }

    /**
     * 同上，并把本轮流错误交给 {@code errorSink}（loop 据此把失败与「没有正文」区分开，计入任务级错误）。
     *
     * @param errorSink 可为 null；非 null 时记录首个流错误
     */
    GoalOutcome performAgentTaskSyncRound(WorkspaceContext wsContext, AgentSession session, String sessionCwd, InputOutcome outcome,
                                          AtomicReference<Throwable> errorSink) {
        String sessionId = session.getSessionId();

        // 会话级模型选择回写：与 async 路径同口径（生效值回写，展示句柄现取）
        if (outcome.getModelName() != null) {
            wsContext.getEngine().setSessionModel(session, outcome.getModelName());
        }
        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(outcome.getModelName());
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicReference<String> finalAnswerRef = new AtomicReference<>("");
        // 本轮客观事实：token 取自 system.trace，工具证据在 tool.end 上就地判定
        java.util.concurrent.atomic.AtomicLong tokensRef = new java.util.concurrent.atomic.AtomicLong(0L);
        java.util.concurrent.atomic.AtomicBoolean hasToolCallsRef = new java.util.concurrent.atomic.AtomicBoolean(false);

        // 开一轮（早于任何开流动作）：同步等待方（Loop）依赖它判定本轮是否已被取消；
        // 同步路径（Loop 等）通常不经过队列派发，dispatch 为 null，收口落到空操作。
        final SessionTurn turn = SessionTurn.begin(session);

        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）；
                //但必须释放闩锁，否则同步等待方（Loop）永久阻塞
                if (turn.isCancelled()) {
                    LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
                    outcome.discard();
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);
                    countDownLatch.countDown();
                    return;
                }

                // 新开流前重置：后端 done 门 + 前端流门（与 async 路径对称，放在 isDisposed 之后，
                // 避免为一个已取消的轮次解封前端）
                beginStreamTurn(wsContext, session);
                // 续发与 async 路径同口径：登记在运行句柄注销之后
                outcome.afterRun(() -> gate.getQueueDispatcher().drainSessionQueue(wsContext, session));

                gate.getStreamBuilder().buildStreamFlux(wsContext, session, outcome, chatModel, sessionCwd)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    gate.emitToClient(wsContext, sessionId, line);

                    if (WebEventNames.SYSTEM_TRACE.equals(line.getEvent()) && line.getPayload() instanceof SystemTracePayload) {
                        SystemTracePayload tracePayload = (SystemTracePayload) line.getPayload();
                        if (org.noear.solon.core.util.Assert.isNotEmpty(tracePayload.getFinalAnswer())) {
                            finalAnswerRef.set(tracePayload.getFinalAnswer());
                        }
                        if (tracePayload.getTotalTokens() != null) {
                            tokensRef.set(tracePayload.getTotalTokens());
                        }
                    } else if (WebEventNames.TOOL_END.equals(line.getEvent())
                            && line.getPayload() instanceof org.noear.solon.codecli.api.web.event.payload.ToolEndPayload) {
                        // 与桌面/CLI 同口径：内部工具（task/multitask/memory/goal）不算行动证据
                        org.noear.solon.codecli.api.web.event.payload.ToolEndPayload toolEnd =
                                (org.noear.solon.codecli.api.web.event.payload.ToolEndPayload) line.getPayload();
                        if (toolEnd.isError() == false && ToolViewUtil.isInternalTool(toolEnd.getName()) == false) {
                            hasToolCallsRef.set(true);
                        }
                    }
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);
                    if (errorSink != null) {
                        errorSink.compareAndSet(null, e);
                    }

                    gate.emitToClient(wsContext, sessionId, WebEvent.ofError(e));
                    gate.getStreamBuilder().replyErrorToBoundChannels(wsContext, session, e);
                })
                .doFinally(s -> {
                    // 与 async 路径同构地收口派发事务（旧实现在此漏了 settle：
                    // 同一不变量写两遍必然漏一遍，收归 SessionTurn 后不再依赖人工对称）
                    settleTurn(wsContext, session, turn, true);

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);
                    countDownLatch.countDown();

                    //MDC 不在此处清理：Reactor 调度钩子会在任务结束时自动还原线程现场，
                    //提前 remove 反而会让同一任务后续日志丢掉工作区归属
                }).subscribe();
            } catch (Throwable e) {
                //调度线程内的异常不会回到调用方；未订阅成功则 doFinally 不会跑，
                //此处必须补发终态包并释放闩锁，否则同步等待方（Loop）永久阻塞
                if (errorSink != null) {
                    errorSink.compareAndSet(null, e);
                }
                outcome.discard();
                failStreamTurn(wsContext, session, turn, e);
                countDownLatch.countDown();
            } finally {
                WorkspaceLogRouter.endScope(logScope);
            }
        };

        if (wsLogKey == null) {
            subscribeAction.run();
        } else {
            //订阅调度到携带 MDC 的 boundedElastic 线程：管道主体执行线程携带工作区标记
            Schedulers.boundedElastic().schedule(subscribeAction);
        }

        //等待订阅完成（闩锁在 doFinally 释放）；运行句柄由 ReActRequest 自行登记，Stop/interrupt 可随时取消
        RunUtil.runAndTry(() -> {
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        String text = finalAnswerRef.get();
        if (org.noear.solon.core.util.Assert.isEmpty(text)) {
            return null;
        }
        return GoalOutcome.done(text, hasToolCallsRef.get(), tokensRef.get());
    }

    /** 当前工作区的日志标识（无上下文时为 null，走默认路由）。 */
    private static String wsLogKey(WorkspaceContext wsContext) {
        return wsContext == null ? null : LogDirUtil.workspaceKey(wsContext.getMeta().getPath());
    }
}
