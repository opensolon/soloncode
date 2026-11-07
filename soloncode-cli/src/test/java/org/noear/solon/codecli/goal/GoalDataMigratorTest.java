package org.noear.solon.codecli.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.extension.goal.GoalProjection;
import org.noear.solon.flow.FlowContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 旧版 tasks.json 的 GOAL 条目 → 会话快照 session.goal 的迁移。
 *
 * <p>守护的缺陷：①旧版迭代次数 / 最后结果 / 最后执行时间存在 LoopTask 顶层（时间是 ISO 字符串），
 * 只读 goalState 会把它们丢掉；②迁移失败时 loop 插件会把 tasks.json 拆进 .migrated-* 备份，
 * goal 迁移从此不再重试；③一个会话快照损坏会拖累其它会话。</p>
 */
class GoalDataMigratorTest {
    @TempDir
    Path dir;

    private Path tasksFile() {
        return dir.resolve("tasks.json");
    }

    private Path sessions() throws Exception {
        return Files.createDirectories(dir.resolve("sessions"));
    }

    private Path snapshot(String sessionId) throws Exception {
        return sessions().resolve(sessionId).resolve(sessionId + ".snapshot.json");
    }

    private void writeSnapshot(String sessionId, String json) throws Exception {
        Path file = snapshot(sessionId);
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private void emptySnapshot(String sessionId) throws Exception {
        writeSnapshot(sessionId, FlowContext.of(sessionId).toJson());
    }

    private GoalProjection goalOf(String sessionId) throws Exception {
        FlowContext ctx = FlowContext.fromJson(new String(Files.readAllBytes(snapshot(sessionId)), StandardCharsets.UTF_8));
        return GoalProjection.from(ctx);
    }

    private static String goalItem(String sessionId, String goalId, String extraTopLevel) {
        return "{\"id\":\"t-" + sessionId + "\",\"type\":\"GOAL\",\"sessionId\":\"" + sessionId + "\",\"prompt\":\"p\""
                + (extraTopLevel == null ? "" : "," + extraTopLevel)
                + ",\"goalState\":{\"id\":\"" + goalId + "\",\"condition\":\"build it\",\"status\":\"PURSUING\","
                + "\"consumedTokens\":12,\"maxTokens\":1000,\"maxIterations\":9,\"startEpochMs\":5}}";
    }

    private void writeTasks(String... items) throws Exception {
        Files.write(tasksFile(), ("{\"version\":2,\"tasks\":[" + String.join(",", items) + "]}").getBytes(StandardCharsets.UTF_8));
    }

    private ONode readJson(Path file) throws Exception {
        return ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void topLevelFieldsOfLegacyTaskAreMigrated() throws Exception {
        emptySnapshot("s1");
        String iso = "2026-10-20T08:00:00Z";
        writeTasks(goalItem("s1", "g1",
                        "\"currentIteration\":4,\"lastResult\":\"half done\",\"lastExecutedAt\":\"" + iso + "\""),
                "{\"id\":\"loop-1\",\"type\":\"LOOP\",\"sessionId\":\"s1\"}");

        assertEquals(GoalDataMigrator.Result.MIGRATED, GoalDataMigrator.migrate(tasksFile(), sessions()));

        GoalProjection goal = goalOf("s1");
        assertNotNull(goal);
        assertEquals("g1", goal.getGoalId());
        assertEquals("build it", goal.getObjective());
        assertEquals(4, goal.getCurrentIteration(), "迭代次数在旧版 LoopTask 顶层");
        assertEquals("half done", goal.getLastResult());
        assertEquals(Instant.parse(iso).toEpochMilli(), goal.getLastExecutedAt(), "ISO 字符串按时刻解析");
        assertEquals(1000, goal.getMaxTokens());
        assertEquals(9, goal.getMaxIterations());

        ONode tasks = readJson(tasksFile()).get("tasks");
        assertEquals(1, tasks.getArray().size(), "tasks.json 只剩非 GOAL 条目");
        assertEquals(2, readJson(tasksFile()).get("version").getInt(), "其它顶层字段保留");
        assertFalse(Files.exists(dir.resolve(GoalDataMigrator.LEGACY_FILE)), "全部迁移后删除暂存文件");
    }

    @Test
    void numericLastExecutedAtAndGoalStateValuesAreAccepted() throws Exception {
        emptySnapshot("s1");
        writeTasks("{\"type\":\"GOAL\",\"sessionId\":\"s1\",\"goalState\":{\"id\":\"g1\",\"condition\":\"c\","
                + "\"status\":\"PAUSED\",\"currentIteration\":2,\"lastExecutedAt\":1700000000000}}");

        assertEquals(GoalDataMigrator.Result.MIGRATED, GoalDataMigrator.migrate(tasksFile(), sessions()));
        GoalProjection goal = goalOf("s1");
        assertEquals(2, goal.getCurrentIteration(), "goalState 里有值时优先");
        assertEquals(1700000000000L, goal.getLastExecutedAt());
    }

    @Test
    void brokenSnapshotOnlyBlocksItsOwnSessionAndIsRetried() throws Exception {
        writeSnapshot("bad", "{not json");
        emptySnapshot("good");
        writeTasks(goalItem("bad", "gb", null), goalItem("good", "gg", null));

        assertEquals(GoalDataMigrator.Result.PARTIAL, GoalDataMigrator.migrate(tasksFile(), sessions()));
        assertEquals("gg", goalOf("good").getGoalId(), "坏快照不拖累其它会话");

        Path legacy = dir.resolve(GoalDataMigrator.LEGACY_FILE);
        assertTrue(Files.exists(legacy));
        assertEquals(1, readJson(legacy).get("goals").getArray().size(), "只留下失败的会话");
        assertTrue(readJson(tasksFile()).get("tasks").getArray().isEmpty(),
                "GOAL 已摘出：loop 插件随后拆分 tasks.json 不会把它们只留在备份里");

        // 模拟 loop 插件挂接：把 tasks.json 改名为备份（不影响暂存文件）
        Files.move(tasksFile(), dir.resolve("tasks.json.migrated-1"));

        emptySnapshot("bad"); // 用户修好了快照
        assertEquals(GoalDataMigrator.Result.MIGRATED, GoalDataMigrator.migrate(tasksFile(), sessions()));
        assertEquals("gb", goalOf("bad").getGoalId(), "下次启动重试成功");
        assertFalse(Files.exists(legacy));
    }

    @Test
    void goalsLeftInLoopBackupAreSalvagedWithoutOverwritingNewGoals() throws Exception {
        emptySnapshot("lost");
        emptySnapshot("recreated");
        // recreated 会话里用户已新建了 goal
        FlowContext ctx = FlowContext.of("recreated");
        GoalProjection.builder().goalId("new").objective("new objective").statusName("PURSUING").build().writeTo(ctx);
        writeSnapshot("recreated", ctx.toJson());

        Path backup = dir.resolve("tasks.json.migrated-1700000000000");
        Files.write(backup, ("{\"tasks\":[" + goalItem("lost", "g-lost", null) + ","
                + goalItem("recreated", "g-old", null) + ",{\"id\":\"loop-1\",\"type\":\"LOOP\"}]}")
                .getBytes(StandardCharsets.UTF_8));

        assertEquals(GoalDataMigrator.Result.MIGRATED, GoalDataMigrator.migrate(tasksFile(), sessions()));
        assertEquals("g-lost", goalOf("lost").getGoalId(), "只存在于备份里的 goal 被补回");
        assertEquals("new", goalOf("recreated").getGoalId(), "补救不覆盖用户后来新建的 goal");

        ONode rest = readJson(backup).get("tasks");
        assertEquals(1, rest.getArray().size(), "备份去掉 GOAL，避免每次启动重复扫描；其它条目保留");
        assertEquals(GoalDataMigrator.Result.NOTHING, GoalDataMigrator.migrate(tasksFile(), sessions()));
    }

    @Test
    void rerunAfterCrashDoesNotOverwriteProgressOfSameGoal() throws Exception {
        emptySnapshot("s1");
        writeTasks(goalItem("s1", "g1", "\"currentIteration\":1"));
        assertEquals(GoalDataMigrator.Result.MIGRATED, GoalDataMigrator.migrate(tasksFile(), sessions()));

        // 运行期推进了进度；随后模拟「tasks.json 改写失败」导致旧条目又出现
        FlowContext ctx = FlowContext.fromJson(new String(Files.readAllBytes(snapshot("s1")), StandardCharsets.UTF_8));
        GoalProjection.builder().goalId("g1").objective("build it").statusName("PURSUING").currentIteration(7).build().writeTo(ctx);
        writeSnapshot("s1", ctx.toJson());
        writeTasks(goalItem("s1", "g1", "\"currentIteration\":1"));

        GoalDataMigrator.migrate(tasksFile(), sessions());
        assertEquals(7, goalOf("s1").getCurrentIteration(), "同一 goalId 已迁移：不用旧数据覆盖");
    }

    @Test
    void missingSnapshotDropsGoalAndNothingToDoIsNothing() throws Exception {
        writeTasks(goalItem("gone", "g1", null));
        assertEquals(GoalDataMigrator.Result.MIGRATED, GoalDataMigrator.migrate(tasksFile(), sessions()));
        assertFalse(Files.exists(dir.resolve(GoalDataMigrator.LEGACY_FILE)), "会话已删除：目标随之丢弃");

        assertEquals(GoalDataMigrator.Result.NOTHING, GoalDataMigrator.migrate(tasksFile(), sessions()));
    }
}
