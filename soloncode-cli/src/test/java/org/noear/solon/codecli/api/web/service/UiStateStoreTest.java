package org.noear.solon.codecli.api.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UiStateStore 单元测试：路径契约、合并保存、键值校验、作用域隔离、容错。
 *
 * <p>背景：localStorage 按 origin（含端口）隔离，随机端口重启即丢，故界面状态改由
 * 服务端持久化（Gitee #IKJOCR）。</p>
 */
public class UiStateStoreTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ui-state-");
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

    private UiStateStore store() {
        return new UiStateStore(tempDir.resolve(UiStateStore.STORE_FILE));
    }

    @Test
    @DisplayName("状态文件锚定在用户级目录：<userHome>/<harnessHome>/ui-state.json")
    void storePathIsRootedAtGivenHarnessRoot() {
        Path path = UiStateStore.storePath("/home/tester", ".soloncode/");

        assertEquals(Paths.get("/home/tester/.soloncode/ui-state.json"), path);
        assertTrue(path.isAbsolute());
    }

    @Test
    @DisplayName("合并保存后可跨实例读回（落盘，不依赖内存态）")
    void mergePersistsAcrossInstances() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("chat-theme", "dark");
        changes.put("soloncode-active-session", "web-abc");

        Map<String, String> saved = store().merge(null, changes);

        assertEquals("dark", saved.get("chat-theme"));
        assertEquals("web-abc", saved.get("soloncode-active-session"));

        //新实例（模拟重启后的进程）从磁盘读回，这是 issue 的关键路径
        Map<String, String> reloaded = store().load(null);
        assertEquals("dark", reloaded.get("chat-theme"));
        assertEquals("web-abc", reloaded.get("soloncode-active-session"));

        assertTrue(Files.exists(tempDir.resolve(UiStateStore.STORE_FILE)));
    }

    @Test
    @DisplayName("值为 null 表示删除该键，其余键保持不动")
    void nullValueRemovesKey() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("chat-theme", "dark");
        changes.put("sidebar-width", "320");
        store().merge(null, changes);

        Map<String, String> removal = new LinkedHashMap<>();
        removal.put("chat-theme", null);
        Map<String, String> left = store().merge(null, removal);

        assertFalse(left.containsKey("chat-theme"));
        assertEquals("320", left.get("sidebar-width"));
    }

    @Test
    @DisplayName("非法键/超长值被忽略，不影响同批次的合法键")
    void invalidEntriesAreIgnored() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("bad key", "x");                       //空格非法
        changes.put("k/../escape", "x");                   //路径类字符非法
        changes.put("good-key", "ok");
        changes.put("toolong", repeat('a', 3000));         //超过 2048

        Map<String, String> saved = store().merge(null, changes);

        assertEquals(1, saved.size());
        assertEquals("ok", saved.get("good-key"));
        assertNull(saved.get("bad key"));
        assertNull(saved.get("toolong"));
    }

    @Test
    @DisplayName("工作区标识类键名（含 @、:、点、斜杠后缀）合法")
    void workspaceStyleKeysAreAccepted() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("soloncode-active-session-ws-0123456789abcdef0123456789abcdef-demo", "web-1");
        changes.put("soloncode-active-session-@solon-source", "web-2");
        changes.put("workspace-active-tab", "git");

        Map<String, String> saved = store().merge(null, changes);
        assertEquals(3, saved.size());
    }

    @Test
    @DisplayName("作用域互相隔离：多用户不互相改写界面状态")
    void scopesAreIsolated() {
        Map<String, String> u1 = new LinkedHashMap<>();
        u1.put("chat-theme", "dark");
        store().merge("u1", u1);

        Map<String, String> u2 = new LinkedHashMap<>();
        u2.put("chat-theme", "light");
        store().merge("u2", u2);

        assertEquals("dark", store().load("u1").get("chat-theme"));
        assertEquals("light", store().load("u2").get("chat-theme"));
        assertTrue(store().load("u3").isEmpty());

        //作用域键为空/空白时归一为默认作用域
        assertEquals(UiStateStore.SCOPE_DEFAULT, UiStateStore.normalizeScope("  "));
    }

    @Test
    @DisplayName("空作用域不落盘；文件损坏时降级为空表且仍可写入")
    void emptyScopeIsDroppedAndCorruptFileRecovers() throws Exception {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("chat-theme", "dark");
        store().merge(null, changes);

        Map<String, String> removal = new LinkedHashMap<>();
        removal.put("chat-theme", null);
        store().merge(null, removal);
        String text = new String(Files.readAllBytes(tempDir.resolve(UiStateStore.STORE_FILE)), StandardCharsets.UTF_8);
        assertFalse(text.contains("chat-theme"));

        Files.write(tempDir.resolve(UiStateStore.STORE_FILE), "{ not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(store().load(null).isEmpty());

        Map<String, String> again = new LinkedHashMap<>();
        again.put("chat-theme", "light");
        assertEquals("light", store().merge(null, again).get("chat-theme"));
    }

    @Test
    @DisplayName("构造参数为 null 时立刻拒绝（避免状态无声写到别处）")
    void nullStorePathRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UiStateStore(null));
    }

    @Test
    @DisplayName("键名白名单校验")
    void keyValidation() {
        assertTrue(UiStateStore.isValidKey("chat-theme"));
        assertTrue(UiStateStore.isValidKey("soloncode-active-session-@mount"));
        assertFalse(UiStateStore.isValidKey(null));
        assertFalse(UiStateStore.isValidKey(""));
        assertFalse(UiStateStore.isValidKey("a b"));
        assertFalse(UiStateStore.isValidKey("a/b"));
        assertFalse(UiStateStore.isValidKey("状态"));
    }

    @Test
    @DisplayName("快照包含全部作用域（诊断用）")
    void snapshotContainsAllScopes() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("chat-theme", "dark");
        store().merge("u1", changes);

        Map<String, Map<String, String>> snapshot = store().snapshot();
        assertNotNull(snapshot.get("u1"));
        assertEquals("dark", snapshot.get("u1").get("chat-theme"));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
