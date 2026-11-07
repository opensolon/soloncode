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
import org.noear.solon.ai.harness.extension.queue.QueueClaim;
import org.noear.solon.ai.harness.spi.RunDispatch;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.queue.QueueItem;
import org.noear.solon.codecli.api.web.event.WebEvent;
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

    private final WebGate gate;

    WebQueueDispatcher(WebGate gate) {
        this.gate = gate;
    }

    /** 工作区上下文完成初始化后恢复 session 队列（含旧文件一次性迁移）。goal 恢复由
     *  {@link WebGoalRecovery} 独立负责（领域归位：queue 派发器不携带 goal 域知识）。 */
    void recoverSessionQueues(WorkspaceContext wsContext) {
        if (wsContext == null || wsContext.getSessionManager() == null) return;
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

    /** 会话是否携带排队任务：已加载的会话优先（与写侧同源），未加载时只读解析快照判定。
     *  不为探测而加载会话（getSession/getSessionOrNull 都会常驻内存并装载全量历史）。 */
    private static boolean hasQueueTask(WorkspaceContext wsContext, Path sessionDir) {
        String sessionId = sessionDir.getFileName().toString();
        AgentSession inMemory = wsContext.getSessionManager().getLoadedSession(sessionId);
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
                // 全部归 harness；宿主的回调只表达两件事：「把这条任务交给输入处理」+
                // 「返回时它是否已同步跑完」（同步命令如 /status 不忙 → true，内核当场收口；
                // 异步 Agent 流必然忙 → false，claim 由流终态的 finishQueuedTurn 收口）。
                // 旧写法（isBusy 反推后手动 settleDispatch）已由返回值语义取代，不再可写出
                // 「忙态推断错时机导致悬挂 claim」的代码。
                queue.drainLoop(session, WebQueueDispatcher::isNotOccupied, claim -> {
                    QueueItem item = claim.getItem();
                    gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(item.getId(), item.getText(), item.getOrigin().getChannel()));
                    gate.onChatInput(wsContext, sessionId, null, item.getText(), item.getModel(), null, null, null,
                            item.getOrigin().getChannel(), item.getAttrs().get(WebChatInputHandler.ATTR_REASONING_EFFORT), item.getAttrs().get(WebChatInputHandler.ATTR_THINKING_MODE), item.getSelectedAgent(),
                            item.getOrigin().getSenderId(), item.getOrigin().getReplyTarget(), item.getOrigin().getMessageId());
                    return !isBusy(session);
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
     * 兜底收口：没有捕获到 {@code RunTicket} 时，收口会话当前未收口的程序派发（无则空操作）。
     *
     * <p>只经核心契约 {@link RunDispatch}：登记与注销、身份校验、代际判定全部由派发方
     * （队列的 {@code QueueClaim}）负责，Stop 之后的回滚必然被拒。</p>
     *
     * @return true = 确实收口了一条派发
     */
    boolean finishQueuedTurn(AgentSession session, boolean started) {
        RunDispatch dispatch = RunDispatch.current(session);
        return dispatch != null && dispatch.settle(started);
    }

    /**
     * 本轮输入是否由队列派发而来（且就在当前线程）。
     *
     * <p>只在输入入口使用：判断「这是一轮队列派发，不要再占受理位」。
     * 流终态不要用会话态判定，应用订阅前捕获的 {@code ticket.dispatch() != null}（身份化，见
     * {@code WebSessionRunner#failStreamTurn}）——异步线程上会话态可能已被下一轮派发覆盖。</p>
     */
    static boolean isQueueDispatch(AgentSession session) {
        QueueClaim claim = QueueClaim.current(session);
        return claim != null && claim.isOnDispatchThread();
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
