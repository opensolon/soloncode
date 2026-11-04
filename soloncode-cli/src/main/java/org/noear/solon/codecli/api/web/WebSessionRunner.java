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
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.event.payload.SystemTracePayload;
import org.noear.solon.ai.harness.session.steer.SteerExtension;
import org.noear.solon.ai.harness.session.queue.QueueDispatch;
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
            // 新任务开流：清上一轮 runId 与残留插话邮箱。
            // onAgentEnd 不在 finally 中（interrupt/异常路径不触发），若不在此清理，
            // 上一任务未消费的插话会在新任务第二轮被注入，破坏方案 A 的任务级隔离。
            // 残留不能静默丢弃（包括 HITL 挂起期间提交的插话），一律转排队并告知前端。
            // 清邮箱 + 转排队的内部约定（两个 attrs key、短临界区）已收归 harness 的 handover。
            gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofResetStream());

            SteerExtension steer = wsContext.getExtension(SteerExtension.class);
            SteerExtension.SteerHandover handover = steer == null
                    ? null : steer.handover(session);

            if (handover != null && handover.isEmpty() == false) {
                if (handover.getQueued().isEmpty() == false) {
                    gate.emitToClient(wsContext, session.getSessionId(),
                            WebEvent.ofSteerDroppedItems(null, handover.getQueued()));
                }
                if (handover.getFailed().isEmpty() == false) {
                    gate.emitToClient(wsContext, session.getSessionId(),
                            WebEvent.ofError("插话尚未排队，已暂存；请清理队列后继续"));
                }
            }
        }
    }

    /**
     * 收口本轮所属的队列派发事务（幂等，非队列输入时为空操作）。
     *
     * <p>优先用捕获到的 {@code turn}：它是「这一条流自己的」派发事务，收口有身份保证。
     * 早期路径（同步捕获、取消前抛弃）可能拿不到 turn，此时退回“收口会话当前的派发”——
     * 与改造前行为一致，不存在回退风险。</p>
     */
    private void settleTurn(WorkspaceContext wsContext, AgentSession session, QueueDispatch turn, boolean started) {
        if (turn != null) {
            turn.settle(started);
        } else {
            gate.getQueueDispatcher().finishQueuedTurn(wsContext, session, started);
        }
    }

    /**
     * 一轮流启动失败的收尾：发 error、归还句柄槽位、确保前端能收到 done。
     *
     * <p>订阅动作被调度到 boundedElastic 后，开流前的异常（如模型未配置、agent 不存在、
     * 管道装配失败）不再落入 {@code onChatInput} 的 catch，而是被调度器吞掉：若不处理，
     * composite 会长驻 attrs 使 busy 判定永久为真（Stop 也只能发假 done），
     * 且前端收不到任何终态包。</p>
     */
    void failStreamTurn(WorkspaceContext wsContext, AgentSession session, QueueDispatch turn, Throwable e) {
        LOG.error("Task fail: {}", e.getMessage(), e);
        // 队列派发的启动失败：交给队列派发侧收口（claim 回滚），
        // 「不重试」由 drainLoop 的 START_FAILED 语义保证，宿主不再自己维护标记。
        //
        // 这里必须用【跨线程】的 hasQueuedDispatch，不能用 isQueueDispatch：
        // 本方法由 subscribeAction 在 boundedElastic 上调用，当前线程不是派发线程，
        // 线程判定恒为 false → 回滚后立即续发 → 又领到同一条 → 无限循环。
        boolean queued = WebQueueDispatcher.hasQueuedDispatch(session);
        settleTurn(wsContext, session, turn, false);

        gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofError(e));
        // 失败终态同步到 IM（与 web 一致），避免 IM 端停在「已读不回」
        gate.getStreamBuilder().replyErrorToBoundChannels(wsContext, session, e);

        gate.getEventPublisher().emitDoneGuarded(wsContext, session);
        // 启动失败的任务已回队首；不要立即重试同一失败任务形成无限循环。
        if (!queued) gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
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
        //订阅前（调用线程内）取取消代数基准：订阅动作被调度到别的线程异步执行，
        //这段窗口内若发生 interrupt，本流到订阅时应立即放弃，而不是成为一条取消不掉的幽灵流。
        final int cancelGen = SessionRuns.cancelCount(session);
        // 同样在订阅前（派发调用栈内）捕获本轮所属的派发事务：终态收口必须收「这一条流自己的」
        // 那个事务，而不是「会话当前」的那个。二者在真实时序里总是同一条（busy 期间
        // isNotOccupied 挡住任何新派发），但那份安全性来自外部不变量而非 API 保证——
        // 捕获后即便发生了意外的并发派发，也不会把别人的 claim 收掉。
        final QueueDispatch turn = QueueDispatch.current(session);

        //agent 执行主体在 boundedElastic 线程上跑，订阅动作包在工作区日志作用域里：
        //同步源的整个管道执行发生在 subscribe() 调用栈内，故管道主体线程携带工作区标记
        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                doSubscribeAgentTask(wsContext, session, sessionCwd, outcome, cancelGen, turn);
            } catch (Throwable e) {
                //调度线程内的异常不会回到 onChatInput 的 catch，必须自行收尾
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

    private void doSubscribeAgentTask(WorkspaceContext wsContext, AgentSession session, String sessionCwd, InputOutcome outcome, int cancelGen, QueueDispatch turn) {
        String sessionId = session.getSessionId();

        //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）。
        //与 sync 路径对称地补发终态：若 interrupt 已发过 done，会被 emitDoneOnce 的去重门
        //静默吞掉（无害）；若上游异常路径未发过，则前端不至因收不到任何事件而卡在等待态
        if (SessionRuns.isCancelledSince(session, cancelGen)) {
            LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
            gate.getEventPublisher().emitDoneOnce(wsContext, session);
            return;
        }

        // 会话级模型选择回写：outcome 里是引擎已解析的生效值（命令声明 → 会话选择 → 默认），
        // 回写保持后续轮次（HITL 恢复、queue 续发）读到同一口径。
        if (outcome.getModelName() != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, outcome.getModelName());
        }
        // 展示用模型句柄（trace 事件的 model 标签）：由生效名现取，取不到时用默认实例
        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(outcome.getModelName());

        // 新开流前重置：后端 done 门 + 前端流门（否则上一轮 done 会让本轮输出被前端丢弃）
        beginStreamTurn(wsContext, session);

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

                    // 任务结束后续发排队消息（IM 排队的后端调度点）：
                    // 中断路径已先清空队列，此处 drain 无残留；正常结束则取队头续发。
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);

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
        String sessionId = session.getSessionId();

        // 会话级模型选择回写：与 async 路径同口径（生效值回写，展示句柄现取）
        if (outcome.getModelName() != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, outcome.getModelName());
        }
        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(outcome.getModelName());
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicReference<String> finalAnswerRef = new AtomicReference<>("");

        // 取取消代数基准（早于任何开流动作）：同步等待方（Loop）依赖它判定本轮是否已被取消
        final int cancelGen = SessionRuns.cancelCount(session);
        // 同步路径（Loop 等）不经过队列派发，此值通常为 null；仍统一捕获以保持两条路径同构，
        // 收口时 null 落到「无未收口派发 → 空操作」。
        final QueueDispatch turn = QueueDispatch.current(session);

        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）；
                //但必须释放闩锁，否则同步等待方（Loop）永久阻塞
                if (SessionRuns.isCancelledSince(session, cancelGen)) {
                    LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);
                    countDownLatch.countDown();
                    return;
                }

                // 新开流前重置：后端 done 门 + 前端流门（与 async 路径对称，放在 isDisposed 之后，
                // 避免为一个已取消的轮次解封前端）
                beginStreamTurn(wsContext, session);

                gate.getStreamBuilder().buildStreamFlux(wsContext, session, outcome, chatModel, sessionCwd)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    gate.emitToClient(wsContext, sessionId, line);

                    if (WebEventNames.SYSTEM_TRACE.equals(line.getEvent()) && line.getPayload() instanceof SystemTracePayload) {
                        SystemTracePayload tracePayload = (SystemTracePayload) line.getPayload();
                        if (org.noear.solon.core.util.Assert.isNotEmpty(tracePayload.getFinalAnswer())) {
                            finalAnswerRef.set(tracePayload.getFinalAnswer());
                        }
                    }
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    gate.emitToClient(wsContext, sessionId, WebEvent.ofError(e));
                    gate.getStreamBuilder().replyErrorToBoundChannels(wsContext, session, e);
                })
                .doFinally(s -> {
                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
                    countDownLatch.countDown();

                    //MDC 不在此处清理：Reactor 调度钩子会在任务结束时自动还原线程现场，
                    //提前 remove 反而会让同一任务后续日志丢掉工作区归属
                }).subscribe();
            } catch (Throwable e) {
                //调度线程内的异常不会回到调用方；未订阅成功则 doFinally 不会跑，
                //此处必须补发终态包并释放闩锁，否则同步等待方（Loop）永久阻塞
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
        return finalAnswerRef.get();
    }

    /** 当前工作区的日志标识（无上下文时为 null，走默认路由）。 */
    private static String wsLogKey(WorkspaceContext wsContext) {
        return wsContext == null ? null : LogDirUtil.workspaceKey(wsContext.getMeta().getPath());
    }
}
