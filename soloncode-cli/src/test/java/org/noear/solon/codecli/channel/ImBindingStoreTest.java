package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImBindingStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTripsGroupedBindings() throws Exception {
        Path file = tempDir.resolve("nested").resolve("im-bindings.json");
        ImBindingStore store = new ImBindingStore(file);
        ImBindingRegistry.Binding binding = new ImBindingRegistry.Binding(
                "feishu", "u1", new ImBindingRegistry.Identity("a", "k", "t"),
                "ws", "s1", 42L);
        Map<String, ImBindingRegistry.Binding> input = new LinkedHashMap<>();
        input.put(ImBindingRegistry.compositeKey("feishu", "a", "u1"), binding);

        store.save(input);

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling("im-bindings.json.tmp")));
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"feishu\""));
        ImBindingRegistry.Binding restored = store.load().get(ImBindingRegistry.compositeKey("feishu", "a", "u1"));
        assertNotNull(restored);
        assertEquals("feishu", restored.getChannel());
        assertEquals("k", restored.getIdentity().getAppKey());
        assertEquals("t", restored.getIdentity().getBotToken());
        assertEquals(42L, restored.getUpdatedAt());
    }

    @Test
    void duplicateUserKeyUsesNewerThenLaterEntry() throws Exception {
        Path file = tempDir.resolve("im-bindings.json");
        Files.write(file, ("{\"version\":1,\"feishu\":{"
                + "\"u\":{\"appId\":\"old\",\"updatedAt\":10},"
                + "\"u\":{\"appId\":\"new\",\"updatedAt\":20}},"
                + "\"wechat\":{\"u\":{\"appId\":\"same-time\",\"updatedAt\":20}}}")
                .getBytes(StandardCharsets.UTF_8));

        ImBindingRegistry.Binding binding = new ImBindingStore(file).load().get(ImBindingRegistry.compositeKey("wechat", "same-time", "u"));
        assertNotNull(binding);
        assertEquals("same-time", binding.getIdentity().getAppId());
        assertEquals("wechat", binding.getChannel());
    }

    @Test
    void migratesLegacyFeishuFileAndBacksItUp() throws Exception {
        Path file = tempDir.resolve("im-bindings.json");
        Path legacy = tempDir.resolve("feishu-bindings.json");
        Files.write(legacy, ("{"
                + "\"s1\":{\"openId\":\"u1\",\"appId\":\"a1\",\"appSecret\":\"k1\",\"workspaceId\":\"w1\",\"lastMessageId\":\"m1\"},"
                + "\"s2\":{\"openId\":\"u1\",\"appId\":\"a2\",\"appSecret\":\"k2\",\"workspaceId\":\"w2\",\"lastMessageId\":\"m2\"}"
                + "}").getBytes(StandardCharsets.UTF_8));

        Map<String, ImBindingRegistry.Binding> loaded = new ImBindingStore(file).load();
        // 旧文件里 s1/s2 是同一个 openId 但不同 appId：新模型下它们属于两个 bot，应当并存
        assertEquals(2, loaded.size());
        assertEquals("s1", loaded.get(ImBindingRegistry.compositeKey("feishu", "a1", "u1")).getSessionId());
        assertEquals("s2", loaded.get(ImBindingRegistry.compositeKey("feishu", "a2", "u1")).getSessionId());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(legacy));
        assertEquals(1L, Files.list(tempDir)
                .filter(path -> path.getFileName().toString().startsWith("feishu-bindings.json.bak."))
                .count());
    }

    @Test
    void loadPreservesSameUserKeyAcrossChannels() throws Exception {
        Path file = tempDir.resolve("im-bindings.json");
        Files.write(file, ("{\"version\":1,\"feishu\":{\"u\":{\"appId\":\"f\",\"updatedAt\":10}},"
                + "\"wechat\":{\"u\":{\"appId\":\"w\",\"updatedAt\":20}}}")
                .getBytes(StandardCharsets.UTF_8));

        Map<String, ImBindingRegistry.Binding> loaded = new ImBindingStore(file).load();
        assertEquals(2, loaded.size());
        assertEquals("f", loaded.get(ImBindingRegistry.compositeKey("feishu", "f", "u")).getIdentity().getAppId());
        assertEquals("w", loaded.get(ImBindingRegistry.compositeKey("wechat", "w", "u")).getIdentity().getAppId());
    }

    /**
     * 回归点：同一 userKey 属于两个不同 bot 时必须各自落盘、互不覆盖。
     *
     * <p>持久化若仍以 userKey 作为 JSON 键，后写的会把先写的挤掉——正是真机上
     * 「绑了 bot2，对话1 的绑定消失」在磁盘层面的形态。</p>
     */
    @Test
    void sameUserKeyAcrossBotsRoundTripsWithoutOverwrite() throws Exception {
        Path file = tempDir.resolve("im-bindings.json");
        ImBindingStore store = new ImBindingStore(file);
        Map<String, ImBindingRegistry.Binding> input = new LinkedHashMap<>();
        input.put(ImBindingRegistry.compositeKey("dingtalk", "appKey-1", "staff-9"),
                new ImBindingRegistry.Binding("dingtalk", "staff-9",
                        new ImBindingRegistry.Identity(null, "appKey-1", null), "ws1", "s1", 1L));
        input.put(ImBindingRegistry.compositeKey("dingtalk", "appKey-2", "staff-9"),
                new ImBindingRegistry.Binding("dingtalk", "staff-9",
                        new ImBindingRegistry.Identity(null, "appKey-2", null), "ws2", "s2", 2L));

        store.save(input);

        Map<String, ImBindingRegistry.Binding> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals("s1", loaded.get(ImBindingRegistry.compositeKey("dingtalk", "appKey-1", "staff-9")).getSessionId());
        assertEquals("s2", loaded.get(ImBindingRegistry.compositeKey("dingtalk", "appKey-2", "staff-9")).getSessionId());
    }

    /** 旧版钉钉文件（sessionId 为键，节点内含 userId/appKey）须能迁移；appKey 缺失时回退 robotCode。 */
    @Test
    void migratesLegacyDingTalkFileIncludingRobotCodeFallback() throws Exception {
        Path file = tempDir.resolve("im-bindings.json");
        Path legacy = tempDir.resolve("dingtalk-bindings.json");
        Files.write(legacy, ("{"
                + "\"s1\":{\"userId\":\"staff-1\",\"appKey\":\"key-1\",\"appSecret\":\"sec-1\"},"
                + "\"s2\":{\"userId\":\"staff-2\",\"robotCode\":\"key-2\",\"appSecret\":\"sec-2\"}"
                + "}").getBytes(StandardCharsets.UTF_8));

        Map<String, ImBindingRegistry.Binding> loaded = new ImBindingStore(file).load();

        assertEquals(2, loaded.size());
        ImBindingRegistry.Binding first = loaded.get(ImBindingRegistry.compositeKey("dingtalk", "key-1", "staff-1"));
        assertNotNull(first);
        assertEquals("s1", first.getSessionId());
        assertEquals("sec-1", first.getSecret());
        assertNotNull(loaded.get(ImBindingRegistry.compositeKey("dingtalk", "key-2", "staff-2")),
                "appKey 缺失时应回退到 robotCode");
        assertFalse(Files.exists(legacy));
    }

    /**
     * 回归点：登记表被写到历史目录（旧实现取进程启动目录）时须能回收。
     *
     * <p>契约：登记表位于用户级 harness 目录。旧实现误取 {@code getUserDir()}
     * （进程启动目录），会使同一进程随启动位置读写不同文件。</p>
     */
    @Test
    void reclaimsMisplacedRegistryAndLegacyFilesFromHistoricalDir() throws Exception {
        Path targetDir = tempDir.resolve("home/channels");
        Path historicalDir = tempDir.resolve("launched/channels");
        Files.createDirectories(historicalDir);

        // 历史目录里既有旧格式文件，也有被写错位置的新格式登记表
        Files.write(historicalDir.resolve("dingtalk-bindings.json"),
                ("{\"s1\":{\"userId\":\"staff-1\",\"appKey\":\"key-1\",\"appSecret\":\"sec-1\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        Map<String, ImBindingRegistry.Binding> seed = new LinkedHashMap<>();
        seed.put(ImBindingRegistry.compositeKey("feishu", "a1", "u1"),
                new ImBindingRegistry.Binding("feishu", "u1",
                        new ImBindingRegistry.Identity("a1", null, null), "ws1", "s1", 99L));
        new ImBindingStore(historicalDir.resolve("im-bindings.json")).save(seed);

        List<Path> legacyDirs = Arrays.asList(historicalDir);
        Map<String, ImBindingRegistry.Binding> loaded =
                new ImBindingStore(targetDir.resolve("im-bindings.json"), legacyDirs).load();

        assertEquals(2, loaded.size(), "旧格式与错位登记表应一并回收");
        assertEquals("s1", loaded.get(ImBindingRegistry.compositeKey("feishu", "a1", "u1")).getSessionId());
        assertEquals("sec-1", loaded.get(ImBindingRegistry.compositeKey("dingtalk", "key-1", "staff-1")).getSecret());
        assertTrue(Files.exists(targetDir.resolve("im-bindings.json")), "回收结果应落到正确位置");
        assertFalse(Files.exists(historicalDir.resolve("im-bindings.json")));
        assertFalse(Files.exists(historicalDir.resolve("dingtalk-bindings.json")));
    }

    /** 登记表路径固定为「传入的 harness 根 + channels」，不含进程启动目录分量。 */
    @Test
    void registryPathIsRootedAtGivenHarnessRoot() {
        Path path = ImGateway.storePath("fake-home", ".soloncode/channels/");

        assertEquals(Paths.get("fake-home", ".soloncode", "channels", "im-bindings.json").toAbsolutePath(), path);
    }
}

