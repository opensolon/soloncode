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
import org.noear.solon.ai.harness.extension.goal.GoalProjection;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性数据迁移：{@code tasks.json} 中的 {@code type=GOAL} 条目 → 各会话快照
 * 的 {@code session.goal} 投影。
 *
 * <p><b>执行时机</b>：工作区初始化早期、任何会话实例化之前（单线程），且必须早于 loop 插件挂接。
 * 迁移直接读写快照文件，绕过 {@code AgentSession} 的会话锁，与运行中的会话并发操作会互相覆盖，
 * 因此必须早于所有 {@code getSession}。</p>
 *
 * <p><b>两段式</b>（失败可重试，不依赖 loop 插件的行为）：</p>
 * <ol>
 *   <li><b>摘出</b>：把 GOAL 条目先原子写入同目录的 {@value #LEGACY_FILE}，再把 {@code tasks.json}
 *       改写为不含 GOAL 的版本。这一步只有文件读写；做完后 loop 插件拿到的 {@code tasks.json}
 *       里已没有 GOAL——此前 loop 插件拆分 {@code tasks.json} 时会把 GOAL 只留在
 *       {@code .migrated-<时间戳>} 备份里，goal 迁移一旦失败就再也不会重试；</li>
 *   <li><b>逐会话迁移</b>：每成功（或确认无需迁移）一条就从 {@value #LEGACY_FILE} 移除，
 *       失败的留着下次启动重试；文件空了即删除。一个会话快照损坏不再拖累其它会话。</li>
 * </ol>
 *
 * <p><b>补救</b>：曾用过有缺陷开发版的工作区，GOAL 条目可能只存在于 {@code tasks.json.migrated-*}
 * 备份里。迁移时一并摘出这些条目（备份随之去掉 GOAL，避免每次启动重复扫描）；补救条目只写入
 * 尚无 goal 的会话，不覆盖用户后来新建的 goal。</p>
 *
 * <p><b>幂等</b>：目标快照里已有同一 goalId 的投影视为已迁移，崩溃后重跑不会用旧数据覆盖新进度。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public final class GoalDataMigrator {
    private static final Logger LOG = LoggerFactory.getLogger(GoalDataMigrator.class);
    private static final String SNAPSHOT_SUFFIX = ".snapshot.json";

    /** 待迁移 GOAL 条目的暂存文件（与 tasks.json 同目录） */
    public static final String LEGACY_FILE = "goal-legacy.json";
    /** 补救条目标记：来自 loop 插件的拆分备份，只写入尚无 goal 的会话 */
    static final String SALVAGE_FLAG = "legacySalvage";
    private static final String LOOP_BACKUP_INFIX = ".migrated-";
    private static final String TEMP_SUFFIX = ".migrating";

    /** 迁移结果 */
    public enum Result {
        /** 没有待迁移的 goal */
        NOTHING,
        /** 待迁移的 goal 已全部处理 */
        MIGRATED,
        /** 部分失败，失败条目留在 {@value #LEGACY_FILE} 等下次启动重试 */
        PARTIAL
    }

    /** 单条迁移结局 */
    private enum Apply { DONE, SKIPPED, FAILED }

    private GoalDataMigrator() {
    }

    /**
     * 执行迁移。
     *
     * @param tasksFile   工作区的 tasks.json（可不存在：仍会处理暂存文件与补救备份）
     * @param sessionsDir 会话目录根
     */
    public static Result migrate(Path tasksFile, Path sessionsDir) {
        if (tasksFile == null || sessionsDir == null) {
            return Result.NOTHING;
        }

        Path legacy = tasksFile.resolveSibling(LEGACY_FILE);

        // 1. 摘出：tasks.json 的 GOAL 条目，以及 loop 拆分备份里残留的 GOAL 条目（补救）
        extract(tasksFile, legacy, false);
        for (Path backup : loopBackups(tasksFile)) {
            extract(backup, legacy, true);
        }

        // 2. 逐会话迁移
        return drain(legacy, sessionsDir);
    }

    // ========== 摘出 ==========

    /**
     * 把 {@code source} 中的 GOAL 条目并入暂存文件，再把 source 改写为不含 GOAL 的版本。
     * 先写暂存、后改写来源：中途崩溃时两边都有，重跑按 sessionId 合并（幂等）。
     */
    private static void extract(Path source, Path legacy, boolean salvage) {
        if (!Files.isRegularFile(source)) {
            return;
        }

        ONode root;
        try {
            root = ONode.ofJson(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warn("[GoalMigrator] read {} failed: {}", source.getFileName(), e.toString());
            return;
        }
        if (root == null || root.getOrNull("tasks") == null || !root.get("tasks").isArray()) {
            return;
        }

        List<ONode> goals = new ArrayList<>();
        List<ONode> others = new ArrayList<>();
        for (ONode item : root.get("tasks").getArray()) {
            if ("GOAL".equals(strOrNull(item, "type"))) {
                goals.add(item);
            } else {
                others.add(item);
            }
        }
        if (goals.isEmpty()) {
            return;
        }

        try {
            Map<String, ONode> merged = readLegacy(legacy);
            for (ONode item : goals) {
                String sessionId = strOrNull(item, "sessionId");
                if (sessionId == null || sessionId.trim().isEmpty()) {
                    continue; // 无归属的 GOAL 无处可迁，随来源改写丢弃（与旧实现一致）
                }
                if (salvage) {
                    ONode existing = merged.get(sessionId);
                    if (existing != null && existing.getOrNull(SALVAGE_FLAG) == null) {
                        continue; // 正常摘出的条目优先于补救条目
                    }
                    item.set(SALVAGE_FLAG, true);
                }
                merged.put(sessionId, item); // 单槽语义：同会话后者覆盖前者
            }
            writeLegacy(legacy, merged);
        } catch (Exception e) {
            LOG.error("[GoalMigrator] stage goals from {} failed (will retry next start): {}",
                    source.getFileName(), e.toString());
            return; // 来源保持原样
        }

        try {
            ONode tasks = new ONode().asArray();
            for (ONode item : others) {
                tasks.add(item);
            }
            root.set("tasks", tasks);
            atomicWrite(source, root.toJson());
            LOG.info("[GoalMigrator] staged {} goal(s) from {} into {}", goals.size(), source.getFileName(), LEGACY_FILE);
        } catch (Exception e) {
            // 暂存已写入：下次重跑会再次摘出，同 goalId 的已迁移投影会被识别跳过
            LOG.error("[GoalMigrator] rewrite {} failed: {}", source.getFileName(), e.toString());
        }
    }

    /** loop 插件拆分 tasks.json 时留下的备份（tasks.json.migrated-*），按名称排序（时间戳递增）。 */
    private static List<Path> loopBackups(Path tasksFile) {
        List<Path> result = new ArrayList<>();
        Path dir = tasksFile.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        String prefix = tasksFile.getFileName().toString() + LOOP_BACKUP_INFIX;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.startsWith(prefix) && !name.endsWith(TEMP_SUFFIX) && Files.isRegularFile(file)) {
                    result.add(file);
                }
            }
        } catch (IOException e) {
            LOG.warn("[GoalMigrator] list loop backups failed: {}", e.toString());
        }
        result.sort(null);
        return result;
    }

    // ========== 逐会话迁移 ==========

    private static Result drain(Path legacy, Path sessionsDir) {
        if (!Files.isRegularFile(legacy)) {
            return Result.NOTHING;
        }

        Map<String, ONode> pending;
        try {
            pending = readLegacy(legacy);
        } catch (Exception e) {
            LOG.error("[GoalMigrator] read {} failed (kept for manual check): {}", LEGACY_FILE, e.toString());
            return Result.PARTIAL;
        }
        if (pending.isEmpty()) {
            deleteQuietly(legacy);
            return Result.NOTHING;
        }

        Map<String, ONode> remaining = new LinkedHashMap<>();
        int done = 0;
        for (Map.Entry<String, ONode> entry : pending.entrySet()) {
            Apply apply = applyOne(sessionsDir, entry.getKey(), entry.getValue());
            if (apply == Apply.FAILED) {
                remaining.put(entry.getKey(), entry.getValue());
            } else if (apply == Apply.DONE) {
                done++;
            }
        }

        try {
            if (remaining.isEmpty()) {
                Files.deleteIfExists(legacy);
            } else {
                writeLegacy(legacy, remaining);
            }
        } catch (Exception e) {
            // 写回失败：下次重跑时已迁移的条目会被同 goalId 判定跳过
            LOG.error("[GoalMigrator] update {} failed: {}", LEGACY_FILE, e.toString());
        }

        LOG.info("[GoalMigrator] migrated {} goal(s) to session snapshots, {} pending retry", done, remaining.size());
        return remaining.isEmpty() ? Result.MIGRATED : Result.PARTIAL;
    }

    private static Apply applyOne(Path sessionsDir, String sessionId, ONode item) {
        try {
            Path snapshot = sessionsDir.resolve(sessionId).resolve(sessionId + SNAPSHOT_SUFFIX);
            if (!Files.isRegularFile(snapshot)) {
                LOG.warn("[GoalMigrator] snapshot missing for session '{}', goal dropped", sessionId);
                return Apply.SKIPPED;
            }

            GoalProjection projection = toProjection(item);
            if (projection == null) {
                LOG.warn("[GoalMigrator] goal of session '{}' has no goalState, dropped", sessionId);
                return Apply.SKIPPED;
            }

            FlowContext context;
            try {
                context = FlowContext.fromJson(new String(Files.readAllBytes(snapshot), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IOException("snapshot parse failed: " + e.getMessage(), e);
            }
            if (context == null) {
                throw new IOException("snapshot parse returned null");
            }

            GoalProjection existing = GoalProjection.from(context);
            if (existing != null) {
                boolean sameGoal = projection.getGoalId() != null && projection.getGoalId().equals(existing.getGoalId());
                if (sameGoal || item.getOrNull(SALVAGE_FLAG) != null) {
                    // 同一 goal 已迁移过（崩溃重跑）；补救条目不覆盖会话里已有的 goal
                    return Apply.SKIPPED;
                }
            }

            projection.writeTo(context);
            atomicWrite(snapshot, context.toJson());
            return Apply.DONE;
        } catch (Exception e) {
            LOG.error("[GoalMigrator] migrate goal for session '{}' failed (will retry next start): {}",
                    sessionId, e.toString());
            return Apply.FAILED;
        }
    }

    /**
     * 历史 GOAL 任务（LoopTask + GoalState）→ 会话投影。
     *
     * <p>旧版 GoalState 只持久化 id/condition/status/consumedTokens/maxTokens/maxIterations/startEpochMs；
     * 迭代次数、最后结果、最后执行时间在 LoopTask 顶层（{@code lastExecutedAt} 为 ISO 字符串）。
     * 每个字段都先读 goalState、再读顶层，时间同时接受毫秒数与 ISO 字符串。</p>
     *
     * <p>运行时计数器（stagnation / consecutiveErrors / lastFingerprint）迁移时重置——
     * 它们描述的是上一进程内的执行连续性，跨重启保留只会造成误判。</p>
     *
     * <p>字段集合与键名由 harness 的 {@link GoalProjection.Builder} 定义，本方法只负责 ONode → 值的映射。</p>
     */
    static GoalProjection toProjection(ONode item) {
        ONode gs = item.getOrNull("goalState");
        if (gs == null) {
            return null;
        }

        return GoalProjection.builder()
                .goalId(str2(gs, item, "id"))
                .objective(str2(gs, item, "condition"))
                .statusName(strOrNull(gs, "status"))
                .consumedTokens(long2(gs, item, "consumedTokens"))
                .maxTokens(long2(gs, item, "maxTokens"))
                .maxDurationMs(long2(gs, item, "maxDurationMs"))
                .maxIterations((int) long2(gs, item, "maxIterations"))
                .startEpochMs(long2(gs, item, "startEpochMs"))
                .currentIteration((int) long2(gs, item, "currentIteration"))
                .lastResult(str2(gs, item, "lastResult"))
                .lastExecutedAt(epochMs2(gs, item, "lastExecutedAt"))
                .build();
    }

    // ========== 暂存文件 ==========

    private static Map<String, ONode> readLegacy(Path legacy) throws IOException {
        Map<String, ONode> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(legacy)) {
            return map;
        }
        ONode root = ONode.ofJson(new String(Files.readAllBytes(legacy), StandardCharsets.UTF_8));
        if (root == null || root.getOrNull("goals") == null || !root.get("goals").isArray()) {
            throw new IOException(LEGACY_FILE + " malformed");
        }
        for (ONode item : root.get("goals").getArray()) {
            String sessionId = strOrNull(item, "sessionId");
            if (sessionId != null && !sessionId.trim().isEmpty()) {
                map.put(sessionId, item);
            }
        }
        return map;
    }

    private static void writeLegacy(Path legacy, Map<String, ONode> goals) throws IOException {
        ONode root = new ONode();
        root.set("version", 1);
        ONode array = root.getOrNew("goals").asArray();
        for (ONode item : goals.values()) {
            array.add(item);
        }
        atomicWrite(legacy, root.toJson());
    }

    // ========== 字段读取 ==========

    private static String strOrNull(ONode node, String name) {
        ONode v = node.getOrNull(name);
        return v != null && v.isNull() == false ? v.getString() : null;
    }

    /** 先读 goalState，再读 LoopTask 顶层 */
    private static String str2(ONode gs, ONode item, String name) {
        String v = strOrNull(gs, name);
        return v != null ? v : strOrNull(item, name);
    }

    private static long long2(ONode gs, ONode item, String name) {
        long v = epochOrLong(gs, name, false);
        return v != 0L ? v : epochOrLong(item, name, false);
    }

    private static long epochMs2(ONode gs, ONode item, String name) {
        long v = epochOrLong(gs, name, true);
        return v != 0L ? v : epochOrLong(item, name, true);
    }

    /** 数字直接取；字符串先按数字解析，{@code allowIso} 时再按 ISO-8601 时刻解析；都不行为 0。 */
    private static long epochOrLong(ONode node, String name, boolean allowIso) {
        String text = strOrNull(node, name);
        if (text == null || text.trim().isEmpty()) {
            return 0L;
        }
        text = text.trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignore) {
        }
        try {
            return (long) Double.parseDouble(text);
        } catch (NumberFormatException ignore) {
        }
        if (allowIso) {
            try {
                return Instant.parse(text).toEpochMilli();
            } catch (Exception ignore) {
            }
        }
        return 0L;
    }

    // ========== 文件 ==========

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("[GoalMigrator] delete {} failed: {}", file.getFileName(), e.toString());
        }
    }

    /** temp + ATOMIC_MOVE 原子替换（迁移窗口内不与任何会话实例并发，自行保证安全）。 */
    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
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
