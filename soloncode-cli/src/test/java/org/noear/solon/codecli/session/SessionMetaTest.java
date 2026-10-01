package org.noear.solon.codecli.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionMeta 单元测试：读写、label.txt 迁移、copy、忽略 pin.txt。
 */
public class SessionMetaTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("session-meta-");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    @DisplayName("空目录 load 返回空对象并补 createdAt")
    void load_emptyDir_returnsEmptyWithCreatedAt() {
        SessionMeta meta = SessionMeta.load(tempDir);
        assertNotNull(meta);
        assertTrue(meta.getLabel() == null || meta.getLabel().isEmpty());
        assertFalse(meta.isPinned());
        assertTrue(meta.getCreatedAt() > 0L);
    }

    @Test
    @DisplayName("save/load 往返 label/pinned/createdAt")
    void saveLoad_roundTrip() throws Exception {
        SessionMeta meta = new SessionMeta();
        meta.setLabel("重构会话列表");
        meta.setPinned(true);
        meta.setCreatedAt(1_700_000_000_000L);
        meta.save(tempDir);

        assertTrue(Files.isRegularFile(tempDir.resolve(SessionMeta.FILE_NAME)));

        SessionMeta loaded = SessionMeta.load(tempDir);
        assertEquals("重构会话列表", loaded.getLabel());
        assertTrue(loaded.isPinned());
        assertEquals(1_700_000_000_000L, loaded.getCreatedAt());
    }

    @Test
    @DisplayName("旧 label.txt 迁移为 meta.json 并删除旧文件")
    void load_migratesLegacyLabelTxt() throws Exception {
        Files.write(tempDir.resolve("label.txt"), "旧标题\nignored".getBytes(StandardCharsets.UTF_8));

        SessionMeta meta = SessionMeta.load(tempDir);
        assertEquals("旧标题", meta.getLabel());
        assertTrue(meta.getCreatedAt() > 0L);
        assertTrue(Files.isRegularFile(tempDir.resolve(SessionMeta.FILE_NAME)));
        assertFalse(Files.exists(tempDir.resolve("label.txt")));

        // 二次 load 只读 meta.json
        SessionMeta again = SessionMeta.load(tempDir);
        assertEquals("旧标题", again.getLabel());
    }

    @Test
    @DisplayName("忽略 pin.txt，不读取其内容")
    void load_ignoresPinTxt() throws Exception {
        Files.write(tempDir.resolve("pin.txt"), "true".getBytes(StandardCharsets.UTF_8));
        SessionMeta meta = SessionMeta.load(tempDir);
        assertFalse(meta.isPinned());
    }

    @Test
    @DisplayName("updateLabel / updatePinned 写回 meta.json")
    void updateHelpers_persist() throws Exception {
        SessionMeta.updateLabel(tempDir, "新标题");
        SessionMeta.updatePinned(tempDir, true);

        SessionMeta loaded = SessionMeta.load(tempDir);
        assertEquals("新标题", loaded.getLabel());
        assertTrue(loaded.isPinned());
        assertTrue(loaded.getCreatedAt() > 0L);
    }

    @Test
    @DisplayName("copy 复制 label/pinned 并刷新 createdAt")
    void copy_preservesLabelAndRefreshesCreatedAt() throws Exception {
        SessionMeta source = new SessionMeta();
        source.setLabel("源会话");
        source.setPinned(true);
        source.setCreatedAt(1_600_000_000_000L);
        source.save(tempDir);

        Path targetDir = tempDir.resolve("forked");
        Files.createDirectories(targetDir);
        long before = System.currentTimeMillis();
        SessionMeta.copy(tempDir, targetDir);
        long after = System.currentTimeMillis();

        SessionMeta target = SessionMeta.load(targetDir);
        assertEquals("源会话", target.getLabel());
        assertTrue(target.isPinned());
        assertTrue(target.getCreatedAt() >= before);
        assertTrue(target.getCreatedAt() <= after + 1000L);
        assertNotEquals(1_600_000_000_000L, target.getCreatedAt());
    }

    @Test
    @DisplayName("copy 源仅有 label.txt 时目标得到 meta.json")
    void copy_migratesSourceLabelAndWritesTargetMeta() throws Exception {
        Files.write(tempDir.resolve("label.txt"), "迁移标题".getBytes(StandardCharsets.UTF_8));
        Path targetDir = tempDir.resolve("forked2");
        Files.createDirectories(targetDir);

        SessionMeta.copy(tempDir, targetDir);

        assertTrue(Files.isRegularFile(targetDir.resolve(SessionMeta.FILE_NAME)));
        assertFalse(Files.exists(targetDir.resolve("label.txt")));
        assertEquals("迁移标题", SessionMeta.load(targetDir).getLabel());
        // 源侧也应完成迁移
        assertTrue(Files.isRegularFile(tempDir.resolve(SessionMeta.FILE_NAME)));
        assertFalse(Files.exists(tempDir.resolve("label.txt")));
    }

    @Test
    @DisplayName("copy 无 label/未 pin 时仍写出目标 meta（仅刷新 createdAt）")
    void copy_emptyBusinessFields_stillWritesTargetMeta() throws Exception {
        // 源目录存在，但无业务字段；load 后 createdAt 会被回填
        SessionMeta source = SessionMeta.load(tempDir);
        assertTrue(source.isEmpty());
        assertTrue(source.getCreatedAt() > 0L);

        Path targetDir = tempDir.resolve("forked-empty");
        Files.createDirectories(targetDir);
        long before = System.currentTimeMillis();
        SessionMeta.copy(tempDir, targetDir);
        long after = System.currentTimeMillis();

        assertTrue(Files.isRegularFile(targetDir.resolve(SessionMeta.FILE_NAME)));
        SessionMeta target = SessionMeta.load(targetDir);
        assertTrue(target.getLabel() == null || target.getLabel().isEmpty());
        assertFalse(target.isPinned());
        assertTrue(target.getCreatedAt() >= before);
        assertTrue(target.getCreatedAt() <= after + 1000L);
    }

    @Test
    @DisplayName("已有 meta.json 时清理残留 label.txt / pin.txt")
    void load_cleansResidualLegacyFilesWhenMetaExists() throws Exception {
        SessionMeta meta = new SessionMeta();
        meta.setLabel("正式标题");
        meta.setPinned(true);
        meta.setCreatedAt(1_700_000_000_000L);
        meta.save(tempDir);

        Files.write(tempDir.resolve("label.txt"), "残留旧标题".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("pin.txt"), "true".getBytes(StandardCharsets.UTF_8));

        SessionMeta loaded = SessionMeta.load(tempDir);
        assertEquals("正式标题", loaded.getLabel());
        assertTrue(loaded.isPinned());
        assertFalse(Files.exists(tempDir.resolve("label.txt")));
        assertFalse(Files.exists(tempDir.resolve("pin.txt")));
    }

    @Test
    @DisplayName("自动任务会话元数据可持久化")
    void automationMetadata_roundTrip() throws Exception {
        SessionMeta meta = new SessionMeta();
        meta.setSessionType("AUTOMATION");
        meta.setOwnerAutomationId("auto-001");
        meta.setArchived(true);
        meta.save(tempDir);

        SessionMeta loaded = SessionMeta.load(tempDir);
        assertEquals("AUTOMATION", loaded.getSessionType());
        assertEquals("auto-001", loaded.getOwnerAutomationId());
        assertTrue(loaded.isArchived());
    }

    @Test
    @DisplayName("isEmpty 只看业务字段，不看 createdAt")
    void isEmpty_ignoresCreatedAt() {
        SessionMeta meta = new SessionMeta();
        meta.setCreatedAt(System.currentTimeMillis());
        assertTrue(meta.isEmpty());

        meta.setLabel("有标题");
        assertFalse(meta.isEmpty());

        meta.setLabel(null);
        meta.setPinned(true);
        assertFalse(meta.isEmpty());
    }
}
