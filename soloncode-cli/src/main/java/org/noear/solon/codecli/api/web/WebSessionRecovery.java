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
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalSnapshot;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.host.SessionAdmin;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.codecli.session.SessionSnapshots;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧数据的一次性迁移（每个工作区只做一次，完成后在状态来源写入标记）。
 *
 * <p>重启恢复本身已是框架的不变量：HarnessHost 首次绑定引擎时按 goal 活跃索引恢复调度、按 queue 积压索引续发。
 * 本类只补那些索引上线之前就存在的数据：</p>
 * <ul>
 *   <li>goal：PURSUING（进程中断残留）重新挂上调度（挂上即写入活跃索引）；PAUSED / BLOCKED 需用户显式恢复；</li>
 *   <li>queue：旧版队列文件迁移进会话；快照里已有积压的会话补登记积压索引。经 {@link SessionAdmin#call}
 *       执行，成功后由 HarnessHost 自动续发。</li>
 * </ul>
 *
 * @author noear
 * @since 2026.11
 */
final class WebSessionRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(WebSessionRecovery.class);

    /** 迁移完成标记（状态来源内） */
    static final String MIGRATED_MARKER = "migrations/web-session-index-v1";

    private WebSessionRecovery() {
    }

    /** 每个工作区只迁移一次（标记已存在时什么都不做） */
    static void migrateOnce(WorkspaceContext ctx, SessionAdmin admin) {
        if (ctx == null || ctx.getEngine() == null || ctx.getSessionManager() == null) return;
        MountSource state = ctx.getEngine().getStateSource();
        try {
            if (state != null && state.exists(MIGRATED_MARKER)) return;
        } catch (Throwable e) {
            LOG.debug("[WebGate] migration marker check failed: {}", e.toString());
        }

        Path root = ctx.getSessionsRoot();
        if (root != null && Files.isDirectory(root)) {
            List<String> sessionIds = new ArrayList<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
                for (Path dir : dirs) {
                    if (Files.isDirectory(dir)) {
                        sessionIds.add(dir.getFileName().toString());
                    }
                }
            } catch (IOException e) {
                LOG.warn("[WebGate] migrate sessions failed for workspace {}: {}", ctx.getMeta().getId(), e.toString());
                return; // 未完成：下次启动重试
            }
            recoverGoals(ctx, root, sessionIds);
            recoverQueues(ctx, admin, root, sessionIds);
        }

        try {
            if (state != null) state.writeString(MIGRATED_MARKER, "");
        } catch (Throwable e) {
            LOG.warn("[WebGate] migration marker write failed: {}", e.toString());
        }
    }

    private static void recoverGoals(WorkspaceContext ctx, Path root, List<String> sessionIds) {
        GoalExtension goal = ctx.getGoalExtension();
        if (goal == null) return;
        try {
            int recovered = goal.recover(sessionIds, sessionId -> {
                // 已加载的会话优先（与写侧同源）；未加载则轻读投影状态，不把会话带进内存
                if (ctx.getSessionManager().getLoadedSession(sessionId) != null) {
                    GoalSnapshot task = goal.get(sessionId);
                    return task == null ? null : task.getStatus();
                }
                return SessionSnapshots.readGoalStatus(root.resolve(sessionId), sessionId);
            });
            if (recovered > 0) {
                LOG.info("[WebGate] resumed {} active goal(s) for workspace {}", recovered, ctx.getMeta().getId());
            }
        } catch (Throwable e) {
            LOG.warn("[WebGate] recover session goals failed for workspace {}: {}", ctx.getMeta().getId(), e.toString());
        }
    }

    private static void recoverQueues(WorkspaceContext ctx, SessionAdmin admin, Path root, List<String> sessionIds) {
        for (String sessionId : sessionIds) {
            Path dir = root.resolve(sessionId);
            boolean legacy = Files.exists(dir.resolve("queue-tasks.json")) || Files.exists(dir.resolve("queue.json"));
            if (!legacy && !hasQueueTask(ctx, dir)) continue;
            try {
                // 补登记积压索引（之后重启由框架续发）；成功后 HarnessHost 立即续发
                HarnessResult<Void> r = admin.call(sessionId, QueueExtension.class, (queue, session) -> {
                    if (legacy) {
                        LegacyQueueMigrator.migrate(ctx, session, dir);
                    }
                    if (queue.hasItems(session)) {
                        queue.markPending(sessionId);
                    }
                    return HarnessResult.ok(null);
                });
                if (r.isOk() == false) {
                    LOG.debug("[WebGate] recover session queue skipped for {}: {}", sessionId, r);
                }
            } catch (Throwable e) {
                LOG.warn("[WebGate] recover session queue failed for {}: {}", sessionId, e.toString());
            }
        }
    }

    /** 会话是否携带排队任务：已加载的会话优先（与写侧同源），未加载时只读解析快照判定（不为探测而加载会话） */
    private static boolean hasQueueTask(WorkspaceContext ctx, Path sessionDir) {
        String sessionId = sessionDir.getFileName().toString();
        AgentSession inMemory = ctx.getSessionManager().getLoadedSession(sessionId);
        if (inMemory != null) {
            QueueExtension queue = ctx.getExtension(QueueExtension.class);
            return queue != null && queue.hasItems(inMemory);
        }
        return SessionSnapshots.hasQueue(sessionDir, sessionId);
    }
}
