package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 旧版工作区内日志清理的行为校验
 */
public class LogDirUtilLegacyCleanTest {

    @Test
    public void case1_delete_direct_log_files_and_remove_empty_dir() throws Exception {
        Path ws = Files.createTempDirectory("sc-ws-");
        Path logs = ws.resolve(".soloncode/logs");
        Files.createDirectories(logs);

        Files.write(logs.resolve("soloncode.log"), "x".getBytes());
        Files.write(logs.resolve("soloncode_2026-08-01_0.log"), "x".getBytes());

        LogDirUtil.cleanLegacyLogs(ws.toString());

        assertFalse(logs.toFile().exists(), "日志文件全部删除后，空目录应被移除");
        assertTrue(ws.resolve(".soloncode").toFile().isDirectory(), ".soloncode 目录必须保留");
    }

    @Test
    public void case2_keep_non_log_files_and_subdirs() throws Exception {
        Path ws = Files.createTempDirectory("sc-ws-");
        Path logs = ws.resolve(".soloncode/logs");
        Files.createDirectories(logs);

        Files.write(logs.resolve("soloncode.log"), "x".getBytes());
        Files.write(logs.resolve("notes.txt"), "x".getBytes());
        //孙级：子目录内的 .log 不应被触碰
        Path sub = logs.resolve("abc123-myapp");
        Files.createDirectories(sub);
        Files.write(sub.resolve("soloncode.log"), "x".getBytes());

        LogDirUtil.cleanLegacyLogs(ws.toString());

        assertFalse(logs.resolve("soloncode.log").toFile().exists(), "直接子级 .log 应删除");
        assertTrue(logs.resolve("notes.txt").toFile().exists(), "非 .log 文件应保留");
        assertTrue(sub.resolve("soloncode.log").toFile().exists(), "子目录内的日志不应被删除");
        assertTrue(logs.toFile().isDirectory(), "目录非空时应保留");
    }

    @Test
    public void case3_no_logs_dir_is_noop() throws Exception {
        Path ws = Files.createTempDirectory("sc-ws-");
        LogDirUtil.cleanLegacyLogs(ws.toString());
        LogDirUtil.cleanLegacyLogs(null);
        LogDirUtil.cleanLegacyLogs("");
        assertTrue(ws.toFile().isDirectory());
    }

    @Test
    public void case4_new_log_dir_is_not_touched() throws Exception {
        //工作区 == 用户主目录时，legacy 路径恰好是 ~/.soloncode/logs（旧全局位置）；
        //新位置已改为 ~/.soloncode/workspaces/<标识>/logs，不能被清理逻辑误伤
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("sc-home-");
        System.setProperty("user.home", home.toString());
        try {
            File newDir = LogDirUtil.logDir(home.toString());
            assertTrue(newDir.mkdirs() || newDir.isDirectory());

            Path probe = newDir.toPath().resolve("soloncode.log");
            Files.write(probe, "x".getBytes());

            LogDirUtil.cleanLegacyLogs(home.toString());

            assertTrue(probe.toFile().exists(), "新版日志目录下的文件不应被清理");
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    public void case5_clean_legacy_global_log_dir_of_this_workspace() throws Exception {
        //上一版全局位置 ~/.soloncode/logs/<标识>/ 应被清理（日志不做迁移），且不影响其它工作区
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("sc-home-");
        System.setProperty("user.home", home.toString());
        try {
            Path ws = Files.createTempDirectory("sc-ws-");
            Path legacyRoot = home.resolve(".soloncode/logs");

            Path mine = legacyRoot.resolve(LogDirUtil.workspaceKey(ws.toString()));
            Files.createDirectories(mine);
            Files.write(mine.resolve("soloncode.log"), "x".getBytes());
            Files.write(mine.resolve("soloncode_2026-08-01_0.log.gz"), "x".getBytes());

            Path other = legacyRoot.resolve("other-workspace-key");
            Files.createDirectories(other);
            Files.write(other.resolve("soloncode.log"), "x".getBytes());

            LogDirUtil.cleanLegacyLogs(ws.toString());

            assertFalse(mine.toFile().exists(), "本工作区的旧全局日志目录应被清理（含归档产物）");
            assertTrue(other.resolve("soloncode.log").toFile().exists(), "其它工作区的日志不得被连带删除");
            assertTrue(legacyRoot.toFile().isDirectory(), "旧根目录非空时应保留");
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
