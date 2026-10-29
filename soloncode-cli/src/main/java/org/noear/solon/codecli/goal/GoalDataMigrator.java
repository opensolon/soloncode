/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.goal;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.session.goal.GoalEngine;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性数据迁移：{@code tasks.json} 中的 {@code type=GOAL} 条目 → 各会话快照
 * 的 {@code session.goal} 投影。
 *
 * <p><b>执行时机</b>：工作区初始化早期、任何会话实例化之前（单线程）。迁移直接读写
 * 快照文件，绕过 {@code AgentSession} 的会话锁，与运行中的会话并发操作会互相覆盖，
 * 因此必须早于所有 {@code getSession}。</p>
 *
 * <p><b>幂等</b>：投影写入采用覆盖语义，崩溃后重跑安全；只有全部会话迁移成功
 * 才从 tasks.json 摘除 GOAL 条目，失败保留现场下次启动重试。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public final class GoalDataMigrator {
    private static final Logger LOG = LoggerFactory.getLogger(GoalDataMigrator.class);
    private static final String SNAPSHOT_SUFFIX = ".snapshot.json";

    private GoalDataMigrator() {
    }

    /**
     * 执行迁移。
     *
     * @param tasksFile   工作区的 tasks.json
     * @param sessionsDir 会话目录根
     * @return 迁移的 goal 数量（0 表示无需迁移或失败待重试）
     */
    public static int migrate(Path tasksFile, Path sessionsDir) {
        if (tasksFile == null || sessionsDir == null || !Files.isRegularFile(tasksFile)) {
            return 0;
        }

        ONode root;
        try {
            root = ONode.ofJson(new String(Files.readAllBytes(tasksFile), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warn("[GoalMigrator] read tasks.json failed: {}", e.toString());
            return 0;
        }
        if (root == null || root.get("tasks") == null || !root.get("tasks").isArray()) {
            return 0;
        }

        // 1. 收集 GOAL 条目（按 sessionId 归并：单槽语义，后者覆盖前者）
        Map<String, ONode> goalsBySession = new LinkedHashMap<>();
        List<ONode> loopItems = new ArrayList<>();
        for (ONode item : root.get("tasks").getArray()) {
            String type = item.getOrNull("type") != null ? item.get("type").getString() : null;
            String sessionId = item.getOrNull("sessionId") != null
                    ? item.get("sessionId").getString() : null;
            if ("GOAL".equals(type) && sessionId != null && !sessionId.trim().isEmpty()) {
                goalsBySession.put(sessionId, item);
            } else {
                loopItems.add(item);
            }
        }

        if (goalsBySession.isEmpty()) {
            return 0;
        }

        // 2. 逐个写入会话快照；任一异常即中止（保留 tasks.json 原样，下次重试）
        for (Map.Entry<String, ONode> entry : goalsBySession.entrySet()) {
            try {
                if (!writeGoalProjection(sessionsDir, entry.getKey(), entry.getValue())) {
                    LOG.warn("[GoalMigrator] snapshot missing for session '{}', goal dropped", entry.getKey());
                }
            } catch (Exception e) {
                LOG.error("[GoalMigrator] migrate goal for session '{}' failed (will retry next start): {}",
                        entry.getKey(), e.toString());
                return 0;
            }
        }

        // 3. 全部成功后从 tasks.json 摘除 GOAL 条目（原子替换）
        try {
            ONode rewritten = new ONode();
            if (root.getOrNull("version") != null) {
                rewritten.set("version", root.get("version").getInt());
            }
            ONode array = rewritten.getOrNew("tasks").asArray();
            for (ONode item : loopItems) {
                array.add(item);
            }
            atomicWrite(tasksFile, rewritten.toJson());
            LOG.info("[GoalMigrator] migrated {} goal(s) from tasks.json to session snapshots", goalsBySession.size());
            return goalsBySession.size();
        } catch (Exception e) {
            LOG.error("[GoalMigrator] rewrite tasks.json failed: {}", e.toString());
            return 0;
        }
    }

    /**
     * 把一条 GOAL 任务写入对应会话快照的 {@code session.goal} 投影。
     *
     * @return false 表示会话快照不存在（会话已被删除，目标随之丢弃）
     */
    private static boolean writeGoalProjection(Path sessionsDir, String sessionId, ONode item) throws IOException {
        Path snapshot = sessionsDir.resolve(sessionId).resolve(sessionId + SNAPSHOT_SUFFIX);
        if (!Files.isRegularFile(snapshot)) {
            return false;
        }

        FlowContext context;
        try {
            context = FlowContext.fromJson(new String(Files.readAllBytes(snapshot), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("snapshot parse failed: " + e.getMessage(), e);
        }
        if (context == null) {
            return false;
        }

        Map<String, Object> projection = toProjection(item);
        if (projection == null) {
            return false;
        }
        context.data().put(GoalEngine.KEY_GOAL, projection);

        atomicWrite(snapshot, context.toJson());
        return true;
    }

    /**
     * 历史 GOAL 任务（LoopTask + GoalState）→ 会话投影。
     *
     * <p>持久字段全部保留；运行时计数器（stagnation / consecutiveErrors /
     * lastFingerprint）迁移时重置——它们描述的是上一进程内的执行连续性，
     * 跨重启保留只会造成误判。</p>
     */
    private static Map<String, Object> toProjection(ONode item) {
        ONode gsNode = item.getOrNull("goalState");
        if (gsNode == null) {
            return null;
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("goalId", strOrNull(gsNode, "id"));
        String condition = strOrNull(gsNode, "condition");
        map.put("condition", condition);
        map.put("objective", condition);
        map.put("status", strOrNull(gsNode, "status"));
        map.put("consumedTokens", longOf(gsNode, "consumedTokens"));
        // maxTokens 历史上双写（GoalState + LoopTask 顶层），GoalState 为准、顶层兜底
        long maxTokens = longOf(gsNode, "maxTokens");
        if (maxTokens == 0 && item.getOrNull("maxTokens") != null) {
            maxTokens = item.get("maxTokens").getLong();
        }
        map.put("maxTokens", maxTokens);
        map.put("maxIterations", (int) longOf(gsNode, "maxIterations"));
        map.put("startEpochMs", longOf(gsNode, "startEpochMs"));
        long maxDurationMs = longOf(gsNode, "maxDurationMs");
        if (maxDurationMs == 0 && item.getOrNull("maxDurationMs") != null) {
            maxDurationMs = item.get("maxDurationMs").getLong();
        }
        map.put("maxDurationMs", maxDurationMs);
        map.put("currentIteration", (int) longOf(gsNode, "currentIteration"));
        String lastResult = strOrNull(gsNode, "lastResult");
        if (lastResult != null) {
            map.put("lastResult", lastResult);
        }
        long lastExecutedAt = longOf(gsNode, "lastExecutedAt");
        if (lastExecutedAt > 0) {
            map.put("lastExecutedAt", lastExecutedAt);
        }
        return map;
    }

    private static String strOrNull(ONode node, String name) {
        return node.getOrNull(name) != null ? node.get(name).getString() : null;
    }

    private static long longOf(ONode node, String name) {
        return node.getOrNull(name) != null ? node.get(name).getLong() : 0L;
    }

    /** temp + ATOMIC_MOVE 原子替换（迁移窗口内不与任何会话实例并发，自行保证安全）。 */
    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".migrating");
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8)) {
            w.write(content);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
