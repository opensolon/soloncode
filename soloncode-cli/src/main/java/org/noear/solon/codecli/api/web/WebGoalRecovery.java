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

import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalSnapshot;
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
 * 进程重启后恢复快照中的活跃 goal 调度。
 *
 * <p>从 {@code WebQueueDispatcher} 拆出的 goal 域职责：goal 恢复的知识（状态口径、
 * 内存会话优先、轻读投影）不属于队列派发器，混在一起让 queue 类携带了跨域知识。</p>
 *
 * <p>重启后 job 已丢失，PURSUING（进程中断残留，新体系无 shutdown hook 落盘 PAUSED）
 * 重新挂上调度直接续跑，避免「PURSUING 无法 resume 只能 clear 重建」的卡死回归。</p>
 *
 * <p>PAUSED/BLOCKED 不挂 job：新体系下 PAUSED 只能来自用户显式暂停
 * （旧 shutdown-hook 路径已随 LoopScheduler goal 链退役），重启后保持静默、
 * 等待用户 /goal resume（或 Web toggle）是尊重用户意图的语义；
 * BLOCKED 为熔断态，同理需显式恢复。终态则无需调度。</p>
 *
 * @author noear 2026/5/8 created
 */
final class WebGoalRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(WebGoalRecovery.class);

    private WebGoalRecovery() { }

    static void recover(WorkspaceContext wsContext) {
        if (wsContext == null || wsContext.getSessionManager() == null) return;
        GoalExtension goal = wsContext.getGoalExtension();
        if (goal == null) return;

        Path root = wsContext.getSessionsRoot();
        if (root == null || !Files.isDirectory(root)) return;
        try {
            // 先枚举会话 ID（目录布局是宿主存储细节），挂接语义交给门面：
            // 仅活跃态才挂、终态/PAUSED/BLOCKED 跳过、已调度幂等跳过。
            List<String> sessionIds = new ArrayList<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
                for (Path dir : dirs) {
                    if (Files.isDirectory(dir)) {
                        sessionIds.add(dir.getFileName().toString());
                    }
                }
            }

            int recovered = goal.recover(sessionIds, sessionId -> {
                Path dir = root.resolve(sessionId);
                // 已加载的会话优先（与写侧同源）；未加载则轻读投影状态，不把会话带进内存
                if (wsContext.getSessionManager().getLoadedSession(sessionId) != null) {
                    GoalSnapshot task = goal.get(sessionId);
                    return task == null ? null : task.getStatus();
                }
                return SessionSnapshots.readGoalStatus(dir, sessionId);
            });

            if (recovered > 0) {
                LOG.info("[WebGate] resumed {} active goal(s) for workspace {}",
                        recovered, wsContext.getMeta().getId());
            }
        } catch (IOException e) {
            LOG.warn("[WebGate] recover session goals failed for workspace {}: {}",
                    wsContext.getMeta().getId(), e.toString());
        }
    }
}
