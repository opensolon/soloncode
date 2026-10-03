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
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.event.payload.SystemTracePayload;
import org.noear.solon.codecli.session.queue.SessionQueueItem;
import org.noear.solon.codecli.session.steer.SteerInterceptor;
import org.noear.solon.codecli.session.steer.SteerMessage;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.codecli.util.LogDirUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.solon.core.util.RunUtil;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;

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
            // 残留不能静默丢弃（包括 HITL 挂起期间提交的插话），一律广播 dropped 让前端转排队。
            // 与 steer 提交及 onAgentEnd 共用短临界区，保证 runId 与邮箱按同一任务边界切换。
            Queue<SteerMessage> staleBox;
            synchronized (session.attrs()) {
                session.attrs().remove(SteerInterceptor.ATTR_ACTIVE_RUN_ID);
                @SuppressWarnings("unchecked")
                Queue<SteerMessage> currentBox =
                        (Queue<SteerMessage>) session.attrs().remove(SteerInterceptor.ATTR_STEER_BOX);
                staleBox = currentBox;
            }

            gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofResetStream());

            if (staleBox != null && staleBox.isEmpty() == false) {
                gate.handleDroppedSteers(wsContext, session, staleBox, null);
            }
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
    void failStreamTurn(WorkspaceContext wsContext, AgentSession session, Disposable.Composite composite, Throwable e) {
        LOG.error("Task fail: {}", e.getMessage(), e);
        boolean queued = session.attrs().get("session.queue.executing") instanceof SessionQueueItem;
        if (queued && Thread.currentThread().equals(session.attrs().get(WebQueueDispatcher.ATTR_QUEUE_DISPATCH))) {
            session.attrs().put("session.queue.startFailed", Boolean.TRUE);
        }
        gate.getQueueDispatcher().finishQueuedTurn(session, false);

        gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofError(e));

        if (composite != null) {
            //本轮未挂上任何流（self=null）：仅当槽位已无其它活跃流时才清空
            releaseStreamSlot(session, composite, null);
        }

        gate.getEventPublisher().emitDoneGuarded(wsContext, session);
        // 启动失败的任务已回队首；不要立即重试同一失败任务形成无限循环。
        if (!queued) gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
    }

    /**
     * 执行 Agent 流式任务（异步）：注册句柄槽位后将订阅动作调度到 boundedElastic。
     *
     * <p>通过 {@link WebStreamBuilder} 构建 ReAct Agent 的响应流，
     * 订阅流数据并通过 {@code emitToClient} 逐条推送至前端。</p>
     *
     * @param prompt       用户输入的 Prompt（为 null 时表示 HITL 恢复等无需新 Prompt 的场景）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称（可为 null，表示使用默认 Agent）
     */
    void performAgentTaskAsync(WorkspaceContext wsContext, AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName) {
        //订阅前（调用线程内）先注册 composite：订阅动作被调度到别的线程异步执行，若把注册也放进去，
        //这段窗口内 busy 判定会误判空闲（并发 input 可能在同一会话上开出第二条流），
        //且 interruptSession 取不到 disposable，只会补发一个假 done —— 任务照跑且再也无法取消。
        Disposable.Composite composite = (Disposable.Composite) session.attrs()
                .computeIfAbsent("disposable", k -> Disposables.composite());

        //agent 执行主体在 boundedElastic 线程上跑，订阅动作包在工作区日志作用域里：
        //同步源的整个管道执行发生在 subscribe() 调用栈内，故管道主体线程携带工作区标记
        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                doSubscribeAgentTask(wsContext, session, sessionCwd, prompt, selectedModel, agentName, composite);
            } catch (Throwable e) {
                //调度线程内的异常不会回到 onChatInput 的 catch，必须自行收尾
                failStreamTurn(wsContext, session, composite, e);
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

    private void doSubscribeAgentTask(WorkspaceContext wsContext, AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName, Disposable.Composite composite) {
        String sessionId = session.getSessionId();

        //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）。
        //与 sync 路径对称地补发终态：若 interrupt 已发过 done，会被 emitDoneOnce 的去重门
        //静默吞掉（无害）；若上游异常路径未发过，则前端不至因收不到任何事件而卡在等待态
        if (composite.isDisposed()) {
            LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
            gate.getEventPublisher().emitDoneOnce(wsContext, session);
            return;
        }

        if (selectedModel != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
        } else {
            selectedModel = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }

        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(selectedModel);
        ReActAgent agent = wsContext.getEngine().getAgentOrMain(agentName);

        // 新开流前重置：后端 done 门 + 前端流门（否则上一轮 done 会让本轮输出被前端丢弃）
        beginStreamTurn(wsContext, session);

        AtomicReference<Disposable> selfRef = new AtomicReference<>();
        Disposable disposable = gate.getStreamBuilder().buildStreamFlux(wsContext, session, agent, chatModel, sessionCwd, prompt)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    gate.emitToClient(wsContext, sessionId, line);
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    gate.emitToClient(wsContext, sessionId, WebEvent.ofError(e));
                })
                .doFinally(s -> {
                    gate.getQueueDispatcher().finishQueuedTurn(session, true);
                    releaseStreamSlot(session, composite, selfRef.get());  // 只摘自己那条流

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);

                    // 任务结束后续发排队消息（IM 排队的后端调度点）：
                    // 中断路径已先清空队列，此处 drain 无残留；正常结束则取队头续发。
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);

                    //MDC 不在此处清理：Reactor 调度钩子会在任务结束时自动还原线程现场，
                    //提前 remove 反而会让同一任务后续日志丢掉工作区归属
                })
                .subscribe();

        selfRef.set(disposable);
        // add 到 composite：若 composite 已被 dispose()（interrupt 先到达），会立即 dispose 该 disposable。
        // 流可能在 subscribe() 返回前就已结束，此时 doFinally 看到的 self 仍为 null；
        // 因而补一次 disposed 检查，避免已结束的 disposable 残留在共享 composite 中，
        // 让后续 continue/rerun 永久误判为“当前有任务正在执行”。
        composite.add(disposable);
        if (disposable.isDisposed()) {
            releaseStreamSlot(session, composite, disposable);
        }
    }

    /**
     * 执行 Agent 流式任务（同步等待）：供 Loop 等需要捕获本轮最终文本的调用方使用。
     *
     * @return 捕获到的 finalAnswer 文本（无则返回空串）
     */
    String performAgentTaskSync(WorkspaceContext wsContext, AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName) {
        String sessionId = session.getSessionId();

        if (selectedModel != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
        } else {
            selectedModel = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }

        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(selectedModel);
        ReActAgent agent = wsContext.getEngine().getAgentOrMain(agentName);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicReference<String> finalAnswerRef = new AtomicReference<>("");

        // 先注册句柄槽位，再开流：注册必须早于 beginStreamTurn。否则在「门已复位、句柄未挂」的窗口里，
        // interrupt 会误入 no-active-stream 分支并真的发出一个 done，而随后的调度照常订阅 ——
        // 本轮既不可取消（Loop 会一直跑），输出又因前端已收尾而被丢弃。
        Disposable.Composite composite = (Disposable.Composite) session.attrs().computeIfAbsent("disposable", k -> Disposables.composite());

        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）；
                //但必须释放闩锁，否则同步等待方（Loop）永久阻塞
                if (composite.isDisposed()) {
                    LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);
                    countDownLatch.countDown();
                    return;
                }

                // 新开流前重置：后端 done 门 + 前端流门（与 async 路径对称，放在 isDisposed 之后，
                // 避免为一个已取消的轮次解封前端）
                beginStreamTurn(wsContext, session);

                AtomicReference<Disposable> selfRef = new AtomicReference<>();
                Disposable d = gate.getStreamBuilder().buildStreamFlux(wsContext, session, agent, chatModel, sessionCwd, prompt)
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
                })
                .doFinally(s -> {
                    releaseStreamSlot(session, composite, selfRef.get());  // 只摘自己那条流

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    gate.getEventPublisher().emitDoneOnce(wsContext, session);
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
                    countDownLatch.countDown();

                    //MDC 不在此处清理：Reactor 调度钩子会在任务结束时自动还原线程现场，
                    //提前 remove 反而会让同一任务后续日志丢掉工作区归属
                }).subscribe();
                //subscribe 后立即挂上：同步等待线程可能已读到 null，若不在任务内补挂则 Stop/interrupt 无法取消本轮。
                //订阅可能在 subscribe() 返回前完成，doFinally 此时还拿不到 self；
                //补做 disposed 清理，避免已结束句柄留在共享 composite 中造成永久忙碌。
                selfRef.set(d);
                composite.add(d);
                if (d.isDisposed()) {
                    releaseStreamSlot(session, composite, d);
                }
            } catch (Throwable e) {
                //调度线程内的异常不会回到调用方；未订阅成功则 doFinally 不会跑，
                //此处必须补发终态包并释放闩锁，否则同步等待方（Loop）永久阻塞
                failStreamTurn(wsContext, session, composite, e);
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

        //等待订阅完成（闩锁在 doFinally 释放）；disposable 已在 subscribeAction 内挂入 composite，Stop/interrupt 可随时取消
        RunUtil.runAndTry(() -> {
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return finalAnswerRef.get();
    }

    /**
     * 流结束时归还句柄槽位：只摘自己那条流。
     *
     * <p>HITL 审批恢复、命令触发的 agent 任务、连发 input 都会把多条流挂进同一个 composite。
     * 若在 doFinally 里无条件 {@code remove("disposable")}，第一条流结束就把槽位摘空 ——
     * 此后 busy 判定转假、Stop 取不到 composite 只能补一个假 done，
     * 仍在跑的另一条流便再也无法取消。故先从 composite 摘掉自己，仅当已无活跃流时才清空槽位；
     * 清空按值条件删除，避免误删后续新轮次刚放进去的 composite。</p>
     */
    static void releaseStreamSlot(AgentSession session, Disposable.Composite composite, Disposable self) {
        if (self != null) {
            composite.remove(self);
        }

        if (composite.size() == 0) {
            session.attrs().remove("disposable", composite);
        }
    }

    /** 当前工作区的日志标识（无上下文时为 null，走默认路由）。 */
    private static String wsLogKey(WorkspaceContext wsContext) {
        return wsContext == null ? null : LogDirUtil.workspaceKey(wsContext.getMeta().getPath());
    }
}
