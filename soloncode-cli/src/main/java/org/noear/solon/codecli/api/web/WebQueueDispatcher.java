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
import org.noear.solon.ai.harness.session.queue.QueueDispatch;
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
        if (session == null || !isNotOccupied(session)) return;
        final QueueExtension queue = queueOf(wsContext);
        if (queue == null) return;
        final String sessionId = session.getSessionId();
        final String wsLogKey = wsLogKey(wsContext);
        Runnable action = () -> {
            Object logScope = (wsLogKey != null) ? WorkspaceLogRouter.beginScopeByKey(wsLogKey) : null;
            try {
                // 续发循环、终止条件（含 START_FAILED 不得立即重试）与「能不能接」的原子判定
                // 全部归 harness；宿主的回调只表达「把这条任务交给输入处理」。
                queue.drainLoop(session, WebQueueDispatcher::isNotOccupied, claim -> {
                    QueueItem item = claim.getItem();
                    gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(item.getId(), item.getText(), item.getOrigin().getChannel()));
                    gate.onChatInput(wsContext, sessionId, null, item.getText(), item.getModel(), null, null, null,
                            item.getOrigin().getChannel(), item.getReasoningEffort(), item.getThinkingMode(), item.getSelectedAgent(),
                            item.getOrigin().getSenderId(), item.getOrigin().getReplyTarget(), item.getOrigin().getMessageId());
                    // 同步完成的命令（如 /status）在回调返回时就没有活跃流：当场收口，不留悬挂 claim。
                    // 异步任务此时必然忙，claim 由流终态的 finishQueuedTurn 收口。
                    if (!isBusy(session)) queue.settleDispatch(session, true);
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

    /**
     * 队列能否接单：用「占用」而不是「忙」。
     *
     * <p>HITL 挂起时没有活跃流（{@code isBusy} 为 false），但此时派发会把排队任务变成新回合，
     * 与未决 trace 并存——正是受理层要拒的那种情况。队列必须用同一口径，
     * 否则从队列这条路绕过了提交路径上的闸门。</p>
     */
    private static boolean isNotOccupied(AgentSession session) {
        return !SessionActivity.isOccupied(session);
    }

    /**
     * 仅确认本次队列领取；启动失败则恢复到队首，不按消息来源分支。
     *
     * <p>派发事务的登记与注销、身份校验、代际判定全部由 harness 的 {@code QueueDispatch}
     * 负责：成功走 {@code acknowledge()}，失败走 {@code rollback()}（带回领取瞬间的代际快照，
     * Stop 之后必然被拒）。无未收口派发时为空操作。</p>
     */
    void finishQueuedTurn(WorkspaceContext wsContext, AgentSession session, boolean started) {
        QueueExtension queue = queueOf(wsContext);
        if (queue == null) return;
        queue.settleDispatch(session, started);
    }

    /**
     * 本轮输入是否由队列派发而来（且就在当前线程）。
     *
     * <p>只在输入入口使用：判断「这是一轮队列派发，不要再占受理位」。
     * 流终态请用 {@link #hasQueuedDispatch}（跨线程）。</p>
     */
    static boolean isQueueDispatch(AgentSession session) {
        QueueDispatch dispatch = QueueDispatch.current(session);
        return dispatch != null && dispatch.isOnDispatchThread();
    }

    /**
     * 会话是否有未收口的队列派发事务（跨线程可判）。
     *
     * <p>取代此前的线程判定：异步订阅失败发生在 {@code boundedElastic} 上，用
     * {@link #isQueueDispatch} 必然得到 false，于是「回滚到队首 → 立即续发 → 又领到同一条
     * → 再失败」形成死循环。</p>
     */
    static boolean hasQueuedDispatch(AgentSession session) {
        return QueueDispatch.current(session) != null;
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
