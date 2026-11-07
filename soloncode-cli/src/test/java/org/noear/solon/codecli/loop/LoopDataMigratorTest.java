package org.noear.solon.codecli.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧版每会话 loop-tasks.json → 工作区 tasks.json 的一次性迁移。
 */
public class LoopDataMigratorTest {
    @TempDir
    Path dir;

    private Path tasksFile() {
        return dir.resolve("tasks.json");
    }

    private Path sessions() throws Exception {
        return Files.createDirectories(dir.resolve("sessions"));
    }

    @Test
    public void migratesLegacyFilesAndDeletesThem() throws Exception {
        Path s1 = Files.createDirectories(sessions().resolve("web-1"));
        Files.write(s1.resolve("loop-tasks.json"),
                "[{\"id\":\"a1\",\"prompt\":\"p\",\"hostField\":\"x\"}]".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, LoopDataMigrator.migrate(tasksFile(), sessions()));

        ONode root = ONode.ofJson(new String(Files.readAllBytes(tasksFile()), StandardCharsets.UTF_8));
        ONode item = root.get("tasks").get(0);
        assertEquals("web-1", item.get("sessionId").getString());
        assertEquals("x", item.get("hostField").getString(), "按原始 JSON 搬运，不认识的字段不丢");
        assertFalse(Files.exists(s1.resolve("loop-tasks.json")));
    }

    @Test
    public void createsLoopDirMarkerWhenNothingToMigrate() throws Exception {
        Files.createDirectories(sessions().resolve("web-1"));

        assertEquals(0, LoopDataMigrator.migrate(tasksFile(), sessions()));
        assertFalse(Files.exists(tasksFile()), "没有旧数据不写 tasks.json（否则插件每次启动都要迁移一个空文件）");
        assertTrue(Files.isDirectory(dir.resolve(LoopDataMigrator.LOOP_DIR)), "loop/ 目录即「已迁移」标记，避免每次启动重扫");

        Path s1 = sessions().resolve("web-1");
        Files.write(s1.resolve("loop-tasks.json"), "[{\"id\":\"a1\"}]".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, LoopDataMigrator.migrate(tasksFile(), sessions()), "已迁移后不再扫描");
    }

    @Test
    public void neverTouchesExistingTasksFile() throws Exception {
        String existing = "{\"version\":2,\"tasks\":[{\"id\":\"keep\"}]}";
        Files.write(tasksFile(), existing.getBytes(StandardCharsets.UTF_8));
        Path s1 = Files.createDirectories(sessions().resolve("web-1"));
        Files.write(s1.resolve("loop-tasks.json"), "[{\"id\":\"a1\"}]".getBytes(StandardCharsets.UTF_8));

        assertEquals(0, LoopDataMigrator.migrate(tasksFile(), sessions()));
        assertEquals(existing, new String(Files.readAllBytes(tasksFile()), StandardCharsets.UTF_8));
        assertTrue(Files.exists(s1.resolve("loop-tasks.json")), "已有 tasks.json 时不迁移、不删旧文件");
    }
}
