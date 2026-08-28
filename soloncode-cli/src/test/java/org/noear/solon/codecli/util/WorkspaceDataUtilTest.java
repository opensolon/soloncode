package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区数据目录（~/.soloncode/workspaces/&lt;标识&gt;/）与旧版会话迁移的行为校验。
 * <p>全部用例把 user.home 指向临时目录，避免污染真实主目录。</p>
 */
public class WorkspaceDataUtilTest {

    /**
     * 在临时 user.home 下执行动作
     */
    private void withTempHome(HomeAction action) throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("sc-home-");
        System.setProperty("user.home", home.toString());
        try {
            action.run(home);
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    private interface HomeAction {
        void run(Path home) throws Exception;
    }

    @Test
    public void case1_layout_is_workspaces_key_subdirs() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            String key = WorkspaceDataUtil.workspaceKey(ws.toString());

            Path expectData = home.resolve(".soloncode/workspaces").resolve(key);

            assertEquals(expectData.toString(),
                    WorkspaceDataUtil.dataDir(ws.toString()).getAbsolutePath(),
                    "数据目录应为 ~/.soloncode/workspaces/<标识>/");
            assertEquals(expectData.resolve("sessions").toString(),
                    WorkspaceDataUtil.sessionsDir(ws.toString()).getAbsolutePath(),
                    "会话目录应为数据目录下的 sessions/");
            assertEquals(expectData.resolve("logs").toString(),
                    LogDirUtil.logDir(ws.toString()).getAbsolutePath(),
                    "日志目录应为数据目录下的 logs/（与 app.yml 层级一致）");
        });
    }

    @Test
    public void case2_key_is_stable_and_path_based() throws Exception {
        Path ws = Files.createTempDirectory("sc-ws-");

        String k1 = WorkspaceDataUtil.workspaceKey(ws.toString());
        String k2 = WorkspaceDataUtil.workspaceKey(ws.toString() + File.separator + ".");

        assertEquals(k1, k2, "同一工作区（规范化后同路径）必须得到同一标识");
        assertTrue(k1.endsWith("-" + ws.getFileName().toString()), "标识应带可读目录名后缀");
        assertNotEquals(k1, WorkspaceDataUtil.workspaceKey(Files.createTempDirectory("sc-ws-").toString()),
                "不同工作区标识必须不同");
    }

    @Test
    public void case3_migrate_legacy_sessions() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            Path legacy = ws.resolve(".soloncode/sessions/s1");
            Files.createDirectories(legacy);
            Files.write(legacy.resolve("s1.messages.ndjson"), "{}".getBytes(StandardCharsets.UTF_8));
            Files.write(legacy.resolve("TODO.md"), "- [x] done".getBytes(StandardCharsets.UTF_8));

            int moved = WorkspaceDataUtil.migrateLegacySessions(ws.toString());

            Path target = WorkspaceDataUtil.sessionsDir(ws.toString()).toPath().resolve("s1");
            assertEquals(1, moved, "应迁移 1 个会话");
            assertTrue(Files.exists(target.resolve("s1.messages.ndjson")), "消息文件应搬到新位置");
            assertTrue(Files.exists(target.resolve("TODO.md")), "TODO.md 应一并搬走（与会话同目录）");
            assertFalse(ws.resolve(".soloncode/sessions").toFile().exists(), "源目录搬空后应移除");
            assertTrue(ws.resolve(".soloncode").toFile().isDirectory(), ".soloncode 目录必须保留");
        });
    }

    @Test
    public void case4_migrate_never_overwrites_existing_session() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");

            //新位置已有同名会话（内容 new）
            Path target = WorkspaceDataUtil.sessionsDir(ws.toString()).toPath().resolve("s1");
            Files.createDirectories(target);
            Files.write(target.resolve("mark.txt"), "new".getBytes(StandardCharsets.UTF_8));

            //旧位置同名会话（内容 old）
            Path legacy = ws.resolve(".soloncode/sessions/s1");
            Files.createDirectories(legacy);
            Files.write(legacy.resolve("mark.txt"), "old".getBytes(StandardCharsets.UTF_8));

            int moved = WorkspaceDataUtil.migrateLegacySessions(ws.toString());

            assertEquals(0, moved, "同名会话不应被迁移");
            assertEquals("new", new String(Files.readAllBytes(target.resolve("mark.txt")), StandardCharsets.UTF_8),
                    "新位置内容不可被旧数据覆盖");
            assertTrue(Files.exists(legacy.resolve("mark.txt")), "冲突项应原地保留，交由人工处理");
        });
    }

    @Test
    public void case5_migrate_is_noop_without_legacy_dir() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            assertEquals(0, WorkspaceDataUtil.migrateLegacySessions(ws.toString()));
            assertEquals(0, WorkspaceDataUtil.migrateLegacySessions(null));
            assertEquals(0, WorkspaceDataUtil.migrateLegacySessions(""));
        });
    }

    @Test
    public void case6_mark_workspace_writes_absolute_path() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");

            WorkspaceDataUtil.markWorkspace(ws.toString());
            //重复调用应幂等
            WorkspaceDataUtil.markWorkspace(ws.toString());

            Path mark = WorkspaceDataUtil.dataDir(ws.toString()).toPath()
                    .resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK);

            assertTrue(Files.exists(mark), "应写入 _workspace 标记文件");
            assertEquals(ws.toAbsolutePath().normalize().toString(),
                    new String(Files.readAllBytes(mark), StandardCharsets.UTF_8).trim(),
                    "标记内容应为工作区绝对路径（供改名/移动后反查）");
        });
    }
}
