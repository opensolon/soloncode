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
import org.noear.solon.ai.harness.session.queue.QueueClaim;
import org.noear.solon.ai.harness.session.queue.QueueExtension;
import org.noear.solon.ai.harness.session.queue.QueueItem;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.ai.harness.session.goal.GoalExtension;
import org.noear.solon.ai.harness.session.goal.GoalState;
import org.noear.solon.codecli.session.SessionSnapshots;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.codecli.util.LogDirUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Session 队列的后端派发与恢复。
 *
 * <p>从 WebGate 拆出的队列职责：恢复进程重启前遗留的后端 follow-up 队列、
 *  从队头领取任务并转发给输入处理、以及队列项回执（确认完成或恢复到队首）。
 *  队列状态存于会话快照（FlowContext 的 session.queue.items 投影，由 AgentSession 实现持久化），
 *  无独立存储文件；旧版 queue-tasks.json / queue.json 由恢复流程一次性迁移导入。</p>
 *
 * <p>事件下发与输入派发均经组合根 {@link WebGate} 中转，与其它组件保持单向依赖。</p>
 *
 * @author noear 2026/5/8 created
 */
class WebQueueDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(WebQueueDispatcher.class);

    /**
     * 会话属性：当前队列领取事务（宿主私有键，cli. 前缀；值为 {@link QueuedTurn}）。
     *
     * <p>一条 {@link QueuedTurn} 同时回答两件事：「本轮由队列派发而来」与「该用哪个 claim 收口」。
     * 此前这两个事实分别存在两个键里（claim + 派发线程），要么同步写入遗漏、要么读侧忘了判线程；
     * 合为一个值后不可能只读到一半。</p>
     */
    static final String ATTR_QUEUE_TURN = "cli.queue.turn";

    /**
     * 一次队列派发事务：领取句柄 + 派发线程。
     *
     * <p>线程之所以必须一起存：异步任务会在别的线程结束，只有派发线程上的那次回调
     * 才能把「启动完成、可以收口」与「任务已在跑、等流结束再收口」区分开。</p>
     */
    static final class QueuedTurn {
        private final QueueClaim claim;
        private final Thread dispatchThread;

        QueuedTurn(QueueClaim claim) {
            this.claim = claim;
            this.dispatchThread = Thread.currentThread();
        }

        QueueClaim getClaim() {
            return claim;
        }

        /** 当前线程是否就是派发该任务的那条线程（即「正处在队列派发调用栈里」）。 */
        boolean isOnDispatchThread() {
            return Thread.currentThread() == dispatchThread;
        }
    }

    private final WebGate gate;

    WebQueueDispatcher(WebGate gate) {
        this.gate = gate;
    }

    /** 工作区上下文完成初始化后恢复 session 队列（含旧文件一次性迁移），并恢复未终态的 goal 调度。 */
    void recoverSessionQueues(WorkspaceContext wsContext) {
        if (wsContext == null || wsContext.getSessionManager() == null) return;
        recoverSessionGoals(wsContext);
        Path root = wsContext.getSessionsRoot();
        if (root == null || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                boolean legacy = Files.exists(dir.resolve("queue-tasks.json"))
                        || Files.exists(dir.resolve("queue.json"));
                if (!legacy && !hasQueueTask(wsContext, dir)) continue;
                String sessionId = dir.getFileName().toString();
                AgentSession session = wsContext.getSessionManager().getSession(sessionId);
                if (session == null) continue;
                if (legacy) LegacyQueueMigrator.migrate(wsContext, session, dir);
                drainSessionQueue(wsContext, session);
            }
        } catch (IOException e) {
            LOG.warn("[WebGate] recover session queues failed for workspace {}: {}",
                    wsContext.getMeta().getId(), e.toString());
        }
    }

    /**
     * 恢复快照中的活跃 goal：重启后 job 已丢失，PURSUING（进程中断残留，
     * 新体系无 shutdown hook 落盘 PAUSED）重新挂上调度直接续跑，
     * 避免「PURSUING 无法 resume 只能 clear 重建」的卡死回归。
     *
     * <p>PAUSED/BLOCKED 不挂 job：新体系下 PAUSED 只能来自用户显式暂停
     * （旧 shutdown-hook 路径已随 LoopScheduler goal 链退役），重启后保持静默、
     * 等待用户 /goal resume（或 Web toggle）是尊重用户意图的语义；
     * BLOCKED 为熔断态，同理需显式恢复。终态则无需调度。</p>
     */
    private static void recoverSessionGoals(WorkspaceContext wsContext) {
        GoalExtension goal = wsContext.getGoalExtension();
        if (goal == null) return;

        Path root = wsContext.getSessionsRoot();
        if (root == null || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                String sessionId = dir.getFileName().toString();

                // 内存会话优先（与写侧同源）；未实例化则轻读投影状态，不把会话带进内存
                AgentSession inMemory = wsContext.getSessionManager().getSessionIfPresent(sessionId);
                if (inMemory != null) {
                    GoalState gs = goal.state(inMemory);
                    if (gs != null && gs.getStatus().isActive()) {
                        // 恢复期只挂 job，不能立即跑一轮（resume() 是 runNow=true）
                        goal.resumeScheduleOnly(sessionId);
                    }
                    continue;
                }

                GoalState.Status status = SessionSnapshots.readGoalStatus(dir, sessionId);
                if (status == null || !status.isActive()) {
                    continue;
                }
                goal.resumeScheduleOnly(sessionId);
            }
        } catch (IOException e) {
            LOG.warn("[WebGate] recover session goals failed for workspace {}: {}",
                    wsContext.getMeta().getId(), e.toString());
        }
    }

    /** 会话是否携带排队任务：内存会话优先（与写侧同源），未命中时只读解析快照判定。
     *  不为探测而实例化会话（getSession 会常驻内存并装载全量历史）。 */
    private static boolean hasQueueTask(WorkspaceContext wsContext, Path sessionDir) {
        String sessionId = sessionDir.getFileName().toString();
        AgentSession inMemory = wsContext.getSessionManager().getSessionIfPresent(sessionId);
        if (inMemory != null) return wsContext.getExtension(QueueExtension.class).hasItems(inMemory);
        return SessionSnapshots.hasQueue(sessionDir, sessionId);
    }

    /**
     * 从队头领取任务并派发给输入处理；繁忙时直接返回。
     *
     * <p>调度与日志路由规则与原实现一致：有工作区日志键时派发动作调度到
     * boundedElastic，否则同步执行。</p>
     */
    void drainSessionQueue(WorkspaceContext wsContext, AgentSession session) {
        if (session == null || isBusy(session)) return;
        final QueueExtension queue = queueOf(wsContext);
        if (queue == null) return;
        final String sessionId = session.getSessionId();
        final String wsLogKey = wsLogKey(wsContext);
        Runnable action = () -> {
            Object logScope = (wsLogKey != null) ? WorkspaceLogRouter.beginScopeByKey(wsLogKey) : null;
            try {
                // 续发循环与终止条件（含 START_FAILED 不得立即重试）由 harness 的 drainLoop 负责，
                // 宿主只回答一个问题：现在能不能接单。
                queue.drainLoop(session, WebQueueDispatcher::isNotBusy, claim -> {
                    synchronized (session.attrs()) {
                        if (isBusy(session) || !claim.isValid()) {
                            throw new IllegalStateException("session became busy or queue generation changed");
                        }
                        QueueItem item = claim.getItem();
                        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(item.getId(), item.getText(), item.getOrigin().getChannel()));
                        session.attrs().put(ATTR_QUEUE_TURN, new QueuedTurn(claim));
                        try {
                            gate.onChatInput(wsContext, sessionId, null, item.getText(), item.getModel(), null, null, null,
                                    item.getOrigin().getChannel(), item.getReasoningEffort(), item.getThinkingMode(), item.getSelectedAgent(),
                                    item.getOrigin().getSenderId(), item.getOrigin().getReplyTarget(), item.getOrigin().getMessageId());
                            // 同步完成的命令（如 /status）在回调返回时就没有活跃流：当场收口，不留悬挂 claim。
                            // 异步任务此时必然忙，claim 由流终态的 finishQueuedTurn 收口。
                            if (!isBusy(session)) finishQueuedTurn(wsContext, session, true);
                        } finally {
                            // 只清「本轮还没收口」的登记，避免把流终态已收口的记录重新拉回
                            Object turn = session.attrs().get(ATTR_QUEUE_TURN);
                            if (turn instanceof QueuedTurn && ((QueuedTurn) turn).getClaim() == claim) {
                                session.attrs().remove(ATTR_QUEUE_TURN);
                            }
                        }
                    }
                });
            } catch (Throwable e) {
                LOG.warn("[WebGate] drain session queue failed for session {}: {}", sessionId, e.toString());
            } finally {
                if (logScope != null) WorkspaceLogRouter.endScope(logScope);
            }
        };
        if (wsLogKey == null) action.run();
        else try { Schedulers.boundedElastic().schedule(action); }
        catch (Throwable e) { LOG.warn("[WebGate] schedule session queue drain failed: {}", e.toString()); }
    }

    private static boolean isNotBusy(AgentSession session) {
        return !isBusy(session);
    }

    /**
     * 仅确认本次队列领取；启动失败则恢复到队首，不按消息来源分支。
     *
     * <p>身份校验与代际判定均由 {@link QueueClaim} 内部完成：成功走 {@code acknowledge()}，
     * 失败走 {@code rollback()}（带回领取瞬间的代际快照，Stop 之后必然被拒）。</p>
     */
    void finishQueuedTurn(WorkspaceContext wsContext, AgentSession session, boolean started) {
        synchronized (session.attrs()) {
            Object value = session.attrs().remove(ATTR_QUEUE_TURN);
            if (!(value instanceof QueuedTurn)) return;
            QueueClaim claim = ((QueuedTurn) value).getClaim();
            boolean saved = started ? claim.acknowledge() : claim.rollback();
            if (!saved && !claim.isSettled()) {
                LOG.warn("[WebGate] could not persist queued task completion for session {}", session.getSessionId());
            } else if (!saved) {
                LOG.debug("[WebGate] queued task settlement skipped for session {} (already settled or canceled)",
                        session.getSessionId());
            }
        }
    }

    /**
     * 本轮输入是否由队列派发而来（且就在当前线程）。
     *
     * <p>取代原来的「读 attrs 取 QueueItem 再 instanceof」：宿主只看有没有派发事务，不看队列内部结构。</p>
     */
    static boolean isQueueDispatch(AgentSession session) {
        if (session == null) return false;
        Object value = session.attrs().get(ATTR_QUEUE_TURN);
        return value instanceof QueuedTurn && ((QueuedTurn) value).isOnDispatchThread();
    }

    /** 当前队列派发事务的领取句柄（非队列派发时为 null）。 */
    static QueueClaim currentClaim(AgentSession session) {
        if (session == null) return null;
        Object value = session.attrs().get(ATTR_QUEUE_TURN);
        return value instanceof QueuedTurn ? ((QueuedTurn) value).getClaim() : null;
    }

    /** 取队列门面（扩展未装配时为 null，调用方需容错） */
    private static QueueExtension queueOf(WorkspaceContext wsContext) {
        return wsContext == null ? null : wsContext.getExtension(QueueExtension.class);
    }

    /** 会话是否正在跑（统一口径：输入受理中或活跃流） */
    private static boolean isBusy(AgentSession session) {
        return SessionActivity.isBusy(session);
    }

    /** 当前工作区的日志标识（无上下文时为 null，走默认路由）。 */
    private static String wsLogKey(WorkspaceContext wsContext) {
        return wsContext == null ? null : LogDirUtil.workspaceKey(wsContext.getMeta().getPath());
    }
}
