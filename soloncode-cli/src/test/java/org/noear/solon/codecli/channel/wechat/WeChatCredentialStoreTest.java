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
package org.noear.solon.codecli.channel.wechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信凭据持久化单元测试
 *
 * @author soloncode 2026/9/6 created
 */
class WeChatCredentialStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoad_shouldRoundTripAllFields() {
        Path file = tempDir.resolve("nested").resolve("wechat-bindings.json");
        WeChatCredentialStore store = new WeChatCredentialStore(file);

        WeChatLink.WeChatBinding binding = new WeChatLink.WeChatBinding();
        binding.botToken = "tk";
        binding.ilinkBotId = "bot1";
        binding.ilinkUserId = "user1";
        binding.baseUrl = "https://node1.weixin.qq.com";
        binding.cursor = "CURSOR_9";
        binding.replyTarget = new WeChatLink.ReplyTarget("user1", "ctx-9");

        store.save(Collections.singletonMap("s1", binding));

        assertTrue(Files.exists(file), "父目录应被自动创建");
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")),
                "临时文件应已原子移动为正式文件");

        Map<String, WeChatLink.WeChatBinding> loaded = store.load();
        assertEquals(1, loaded.size());

        WeChatLink.WeChatBinding restored = loaded.get("s1");
        assertNotNull(restored);
        assertEquals("tk", restored.botToken);
        assertEquals("bot1", restored.ilinkBotId);
        assertEquals("user1", restored.ilinkUserId);
        assertEquals("https://node1.weixin.qq.com", restored.baseUrl);
        assertEquals("CURSOR_9", restored.cursor);
        assertEquals("ctx-9", restored.getLastContextToken());
        assertEquals("user1", restored.getLastFromUserId());
    }

    @Test
    void saveEmpty_shouldDeleteStoreFile() throws Exception {
        Path file = tempDir.resolve("wechat-bindings.json");
        WeChatCredentialStore store = new WeChatCredentialStore(file);

        WeChatLink.WeChatBinding binding = new WeChatLink.WeChatBinding();
        binding.botToken = "tk";
        store.save(Collections.singletonMap("s1", binding));
        assertTrue(Files.exists(file));

        store.save(new LinkedHashMap<>());

        assertFalse(Files.exists(file));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void load_shouldSkipEntryWithoutToken() throws Exception {
        Path file = tempDir.resolve("wechat-bindings.json");
        Files.write(file, ("{\"s1\":{\"botToken\":\"\",\"cursor\":\"C\"},"
                + "\"s2\":{\"botToken\":\"tk\",\"cursor\":\"C2\"}}").getBytes(StandardCharsets.UTF_8));

        Map<String, WeChatLink.WeChatBinding> loaded = new WeChatCredentialStore(file).load();

        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey("s2"));
    }

    @Test
    void load_shouldDropUntrustedBaseUrl() throws Exception {
        Path file = tempDir.resolve("wechat-bindings.json");
        Files.write(file, ("{\"s1\":{\"botToken\":\"tk\",\"cursor\":\"C\","
                + "\"baseUrl\":\"https://evil.example.com\"}}").getBytes(StandardCharsets.UTF_8));

        WeChatLink.WeChatBinding restored = new WeChatCredentialStore(file).load().get("s1");

        assertNotNull(restored);
        assertNull(restored.baseUrl, "落盘文件被篡改时也不能把 bot_token 发往不可信接入点");
    }

    @Test
    void load_shouldReturnEmptyOnBrokenJson() throws Exception {
        Path file = tempDir.resolve("wechat-bindings.json");
        Files.write(file, "{\"s1\":{\"botToken\"".getBytes(StandardCharsets.UTF_8));

        assertTrue(new WeChatCredentialStore(file).load().isEmpty());
    }

    @Test
    void load_missingFileShouldReturnEmpty() {
        assertTrue(new WeChatCredentialStore(tempDir.resolve("absent.json")).load().isEmpty());
    }
}
