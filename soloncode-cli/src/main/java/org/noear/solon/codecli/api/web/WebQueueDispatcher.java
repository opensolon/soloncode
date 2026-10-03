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
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.codecli.session.queue.SessionQueue;
import org.noear.solon.codecli.session.queue.SessionQueueDrainer;
import org.noear.solon.codecli.session.queue.SessionQueueItem;
import org.noear.solon.codecli.session.queue.SessionQueueStore;
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
 * 从队头领取任务并转发给输入处理、以及队列项回执（确认完成或恢复到队首）。
 * 队列存储格式与恢复语义保持不变（queue-tasks.json / queue.json、RUNNING/PENDING、generation）。</p>
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

    /** 工作区上下文完成初始化后恢复 session 队列，包括旧版 Web 队列。 */
    void recoverSessionQueues(WorkspaceContext wsContext) {
        if (wsContext == null || wsContext.getSessionManager() == null) return;
        Path root = wsContext.getSessionsRoot();
        if (root == null || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)
                        || !(Files.exists(dir.resolve(SessionQueueStore.FILE_NAME))
                        || Files.exists(dir.resolve("queue.json")))) continue;
                String sessionId = dir.getFileName().toString();
                AgentSession session = wsContext.getSessionManager().getSession(sessionId);
                SessionQueue.bindStorage(session, dir);
                drainSessionQueue(wsContext, session);
            }
        } catch (IOException e) {
            LOG.warn("[WebGate] recover session queues failed for workspace {}: {}",
                    wsContext.getMeta().getId(), e.toString());
        }
    }

    /**
     * 从队头领取任务并派发给输入处理；繁忙时直接返回。
     *
     * <p>调度与日志路由规则与原实现一致：有工作区日志键时派发动作调度到
     * boundedElastic，否则同步执行。</p>
     */
    void drainSessionQueue(WorkspaceContext wsContext, AgentSession session) {
        if (session != null && wsContext != null) {
            SessionQueue.bindStorage(session, wsContext.getSessionPath(session.getSessionId()));
        }
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
                        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(next.getId(), next.getText(), next.getSource()));
                        session.attrs().put("session.queue.executing", next);
                        session.attrs().put(ATTR_QUEUE_DISPATCH, Thread.currentThread());
                        try {
                            gate.onChatInput(wsContext, sessionId, null, next.getText(), next.getModel(), null, null, null,
                                    next.getSource(), next.getReasoningEffort(), next.getThinkingMode(), next.getSelectedAgent(),
                                    next.getSourceUserId(), next.getReplyTarget(), next.getMessageId());
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
