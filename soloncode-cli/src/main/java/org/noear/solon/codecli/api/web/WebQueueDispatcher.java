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
import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.solon.ai.harness.session.queue.SessionQueueDrainer;
import org.noear.solon.ai.harness.session.queue.SessionQueueItem;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.ai.harness.session.goal.GoalEngine;
import org.noear.solon.ai.harness.session.goal.GoalScheduler;
import org.noear.solon.ai.harness.session.goal.GoalState;
import org.noear.solon.codecli.session.SessionActivity;
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

    /** 会话属性：当前正在派发的队列项派发线程（用于区分派发上下文） */
    static final String ATTR_QUEUE_DISPATCH = "session.queue.dispatch";

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
                if (legacy) LegacyQueueMigrator.migrate(session, dir);
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
        GoalEngine engine = wsContext.getGoalEngine();
        GoalScheduler scheduler = wsContext.getGoalScheduler();
        if (engine == null || scheduler == null) return;

        Path root = wsContext.getSessionsRoot();
        if (root == null || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                String sessionId = dir.getFileName().toString();

                // 内存会话优先（与写侧同源）；未实例化则轻读投影状态，不把会话带进内存
                AgentSession inMemory = wsContext.getSessionManager().getSessionIfPresent(sessionId);
                if (inMemory != null) {
                    GoalState gs = engine.state(inMemory);
                    if (gs != null && gs.getStatus().isActive()) {
                        scheduler.schedule(sessionId, false);
                    }
                    continue;
                }

                String status = SessionSnapshots.readGoalStatus(dir, sessionId);
                if (status == null || !GoalEngine.isActiveStatus(status)) {
                    continue;
                }
                scheduler.schedule(sessionId, false);
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
        if (inMemory != null) return SessionQueue.hasItems(inMemory);
        return SessionSnapshots.hasQueue(sessionDir, sessionId);
    }

    /**
     * 从队头领取任务并派发给输入处理；繁忙时直接返回。
     *
     * <p>调度与日志路由规则与原实现一致：有工作区日志键时派发动作调度到
     * boundedElastic，否则同步执行。</p>
     */
    void drainSessionQueue(WorkspaceContext wsContext, AgentSession session) {
        if (session == null || SessionActivity.isBusy(session)) return;
        final long generation = SessionQueue.generation(session);
        final String sessionId = session.getSessionId();
        final String wsLogKey = wsLogKey(wsContext);
        Runnable action = () -> {
            Object logScope = (wsLogKey != null) ? WorkspaceLogRouter.beginScopeByKey(wsLogKey) : null;
            try {
                boolean started = SessionQueueDrainer.drainOne(session, next -> {
                    synchronized (session.attrs()) {
                        if (SessionActivity.isBusy(session) || !SessionQueue.isGenerationActive(session, generation)) {
                            throw new IllegalStateException("session became busy or queue generation changed");
                        }
                        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(next.getId(), next.getText(), next.getOrigin().getChannel()));
                        session.attrs().put("session.queue.executing", next);
                        session.attrs().put(ATTR_QUEUE_DISPATCH, Thread.currentThread());
                        try {
                            gate.onChatInput(wsContext, sessionId, null, next.getText(), next.getModel(), null, null, null,
                                    next.getOrigin().getChannel(), next.getReasoningEffort(), next.getThinkingMode(), next.getSelectedAgent(),
                                    next.getOrigin().getSenderId(), next.getOrigin().getReplyTarget(), next.getOrigin().getMessageId());
                            if (!SessionActivity.isBusy(session)) finishQueuedTurn(session, true);
                        } finally {
                            session.attrs().remove(ATTR_QUEUE_DISPATCH);
                        }
                    }
                }, false);
                // 同步完成的命令可能在 claim 释放前已触发 drain；释放后再续发下一项。
                boolean failedToStart = Boolean.TRUE.equals(session.attrs().remove("session.queue.startFailed"));
                if (started && !failedToStart && !SessionActivity.isBusy(session) && SessionQueue.pendingSize(session) > 0) {
                    drainSessionQueue(wsContext, session);
                }
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

    /** 仅确认本次队列领取；启动失败则恢复到队首，不按消息来源分支。 */
    void finishQueuedTurn(AgentSession session, boolean started) {
        synchronized (session.attrs()) {
            Object value = session.attrs().remove("session.queue.executing");
            if (!(value instanceof SessionQueueItem)) return;
            SessionQueueItem item = (SessionQueueItem) value;
            boolean saved = started ? SessionQueue.acknowledge(session, item.getId())
                    : SessionQueue.requeueFront(session, item, SessionQueue.generation(session));
            if (!saved) LOG.warn("[WebGate] could not persist queued task completion for session {}", session.getSessionId());
        }
    }

    /** 当前工作区的日志标识（无上下文时为 null，走默认路由）。 */
    private static String wsLogKey(WorkspaceContext wsContext) {
        return wsContext == null ? null : LogDirUtil.workspaceKey(wsContext.getMeta().getPath());
    }
}
