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
package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登记表承载微信（v3 统一）的行为测试。
 *
 * <p>覆盖两件事：通道私有状态随绑定往返；旧版 {@code wechat-bindings.json}
 * 能无损迁入统一登记表并留底。</p>
 */
class ImBindingStoreWeChatTest {

    @TempDir
    Path tempDir;

    private Path storeFile() {
        return tempDir.resolve(ImBindingStore.STORE_FILE);
    }

    private static ImBindingRegistry.Binding weChat(String userKey, String ilinkBotId,
                                                    String botToken, String workspaceId, String sessionId) {
        return new ImBindingRegistry.Binding("wechat", userKey,
                new ImBindingRegistry.Identity(null, null, ilinkBotId),
                workspaceId, sessionId, 1L, botToken, null);
    }

    @Test
    void runtimeRoundTripsAndVersionIsThree() throws Exception {
        ImBindingStore store = new ImBindingStore(storeFile());

        ImBindingRegistry.Binding binding = weChat("user1", "bot-id-1", "tk-1", "ws1", "s1");
        binding.putRuntime(ImBindingStore.RT_WECHAT_BASE_URL, "https://node1.weixin.qq.com");
        binding.putRuntime(ImBindingStore.RT_WECHAT_LAST_FROM_USER, "user1");
        binding.putRuntime(ImBindingStore.RT_WECHAT_LAST_CONTEXT_TOKEN, "ctx-9");

        Map<String, ImBindingRegistry.Binding> toSave = new LinkedHashMap<>();
        toSave.put(ImBindingRegistry.keyOf(binding), binding);
        store.save(toSave);

        String json = new String(Files.readAllBytes(storeFile()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\": 3"), "登记表版本应为 3：" + json);

        ImBindingRegistry.Binding loaded = store.load().get(ImBindingRegistry.keyOf(binding));
        assertNotNull(loaded, "微信绑定应能往返");
        assertEquals("tk-1", loaded.getSecret(), "botToken 随 secret 往返");
        assertEquals("bot-id-1", loaded.getIdentity().getBotToken(), "身份维取 ilinkBotId");
        assertEquals("https://node1.weixin.qq.com", loaded.getRuntime(ImBindingStore.RT_WECHAT_BASE_URL));
        assertEquals("ctx-9", loaded.getRuntime(ImBindingStore.RT_WECHAT_LAST_CONTEXT_TOKEN));
    }

    @Test
    void migratesLegacyWeChatFileAndBacksItUp() throws Exception {
        // 旧格式：sessionId 为键
        String legacy = "{\n"
                + "  \"s1\": {\n"
                + "    \"botToken\": \"tk-1\",\n"
                + "    \"ilinkBotId\": \"bot-id-1\",\n"
                + "    \"ilinkUserId\": \"user1\",\n"
                + "    \"baseUrl\": \"https://node1.weixin.qq.com\",\n"
                + "    \"cursor\": \"CURSOR_9\",\n"
                + "    \"lastFromUserId\": \"user1\",\n"
                + "    \"lastContextToken\": \"ctx-9\",\n"
                + "    \"workspaceId\": \"ws1\"\n"
                + "  }\n"
                + "}";
        Path legacyFile = tempDir.resolve(ImBindingStore.LEGACY_WECHAT_FILE);
        Files.write(legacyFile, legacy.getBytes(StandardCharsets.UTF_8));

        Map<String, ImBindingRegistry.Binding> loaded = new ImBindingStore(storeFile()).load();

        ImBindingRegistry.Binding binding = loaded.get(ImBindingRegistry.compositeKey(
                "wechat", "bot-id-1", "user1"));
        assertNotNull(binding, "旧微信条目应迁入统一登记表");
        assertEquals("ws1", binding.getWorkspaceId());
        assertEquals("s1", binding.getSessionId());
        assertEquals("tk-1", binding.getSecret());
        assertEquals("ctx-9", binding.getRuntime(ImBindingStore.RT_WECHAT_LAST_CONTEXT_TOKEN));
        assertFalse(Files.exists(legacyFile), "旧文件应被留底改名");
    }
}
