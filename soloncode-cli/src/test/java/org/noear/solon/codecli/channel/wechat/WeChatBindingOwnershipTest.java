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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信绑定归属与合并持久化验证。
 *
 * <p>背景：绑定文件是全局单文件，多工作区各为同一 botToken 起一条长轮询时，
 * 同一条消息会被两个工作区重复处理（重复回复）、游标互相覆盖回退。
 * 与飞书/钉钉同构的 workspaceId 归属协议必须覆盖微信通道。</p>
 */
class WeChatBindingOwnershipTest {

    private static WeChatLink.WeChatBinding binding(String botToken, String ilinkUserId, String workspaceId) {
        WeChatLink.WeChatBinding binding = new WeChatLink.WeChatBinding();
        binding.botToken = botToken;
        binding.ilinkBotId = "bot-1";
        binding.ilinkUserId = ilinkUserId;
        binding.baseUrl = null;
        binding.cursor = "";
        binding.workspaceId = workspaceId;
        return binding;
    }

    @Test
    void saveMergesOtherWorkspaceBindingsInsteadOfOverwriting(@TempDir Path dir) throws Exception {
        // 全局文件里已存在其它工作区的绑定
        Path storeFile = dir.resolve("wechat-bindings.json");
        Map<String, WeChatLink.WeChatBinding> existing = new LinkedHashMap<>();
        existing.put("session-other", binding("tk-other", "user-other", "ws-other"));
        writeStore(storeFile, existing);

        WeChatCredentialStore mineStore = storeFor(dir, "ws-mine");
        Map<String, WeChatLink.WeChatBinding> mine = new LinkedHashMap<>();
        mine.put("session-mine", binding("tk-mine", "user-mine", "ws-mine"));
        mineStore.save(mine);

        // 合并持久化：其它工作区条目必须保留，本工作区条目写入
        Map<String, WeChatLink.WeChatBinding> merged = readStore(storeFile);
        assertEquals(2, merged.size());
        assertEquals("ws-other", merged.get("session-other").workspaceId);
        assertEquals("ws-mine", merged.get("session-mine").workspaceId);
    }

    @Test
    void saveRemovesOnlyOwnUnboundEntries(@TempDir Path dir) throws Exception {
        Path storeFile = dir.resolve("wechat-bindings.json");
        Map<String, WeChatLink.WeChatBinding> existing = new LinkedHashMap<>();
        existing.put("session-mine", binding("tk-mine", "user-mine", "ws-mine"));
        existing.put("session-other", binding("tk-other", "user-other", "ws-other"));
        writeStore(storeFile, existing);

        // 本工作区 unbind 后保存空 map：只应删掉自己的，不能碰其它工作区的
        WeChatCredentialStore mineStore = storeFor(dir, "ws-mine");
        mineStore.save(new LinkedHashMap<>());

        Map<String, WeChatLink.WeChatBinding> merged = readStore(storeFile);
        assertEquals(1, merged.size());
        assertEquals("session-other", merged.keySet().iterator().next());
    }

    @Test
    void legacyBindingsWithoutWorkspaceIdAreNotClaimedByOtherWorkspace(@TempDir Path dir) throws Exception {
        Path storeFile = dir.resolve("wechat-bindings.json");
        Map<String, WeChatLink.WeChatBinding> existing = new LinkedHashMap<>();
        existing.put("session-legacy", binding("tk-legacy", "user-legacy", null));
        writeStore(storeFile, existing);

        // 其它工作区保存自己的绑定：无归属的遗留条目不算它的，不得被认领/覆盖
        WeChatCredentialStore otherStore = storeFor(dir, "ws-other");
        Map<String, WeChatLink.WeChatBinding> other = new LinkedHashMap<>();
        other.put("session-other", binding("tk-other", "user-other", "ws-other"));
        otherStore.save(other);

        Map<String, WeChatLink.WeChatBinding> merged = readStore(storeFile);
        assertEquals(2, merged.size());
        assertNull(merged.get("session-legacy").workspaceId);
    }

    // ==================== helpers ====================

    /** 用与生产一致的 JSON 形态写入全局绑定文件（模拟旧版/其它工作区写下的现场）。 */
    private static void writeStore(Path storeFile, Map<String, WeChatLink.WeChatBinding> bindings) throws Exception {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, WeChatLink.WeChatBinding> entry : bindings.entrySet()) {
            WeChatLink.WeChatBinding b = entry.getValue();
            sb.append("  \"").append(entry.getKey()).append("\": {")
                    .append("\"botToken\": \"").append(b.botToken).append("\", ")
                    .append("\"ilinkBotId\": \"").append(b.ilinkBotId).append("\", ")
                    .append("\"ilinkUserId\": \"").append(b.ilinkUserId).append("\", ")
                    .append("\"baseUrl\": ").append(b.baseUrl == null ? "null" : "\"" + b.baseUrl + "\"").append(", ")
                    .append("\"cursor\": \"").append(b.cursor).append("\"");
            if (b.workspaceId != null) {
                sb.append(", \"workspaceId\": \"").append(b.workspaceId).append("\"");
            }
            sb.append("}");
            if (++i < bindings.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("}");
        Files.write(storeFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, WeChatLink.WeChatBinding> readStore(Path storeFile) throws Exception {
        String content = new String(Files.readAllBytes(storeFile), StandardCharsets.UTF_8);
        org.noear.snack4.ONode root = org.noear.snack4.ONode.ofJson(content);
        Map<String, WeChatLink.WeChatBinding> result = new LinkedHashMap<>();
        for (Map.Entry<String, org.noear.snack4.ONode> entry : root.getObject().entrySet()) {
            WeChatLink.WeChatBinding b = new WeChatLink.WeChatBinding();
            b.botToken = entry.getValue().get("botToken").getString();
            b.ilinkUserId = entry.getValue().get("ilinkUserId").getString();
            b.workspaceId = entry.getValue().get("workspaceId") != null
                    ? entry.getValue().get("workspaceId").getString() : null;
            result.put(entry.getKey(), b);
        }
        return result;
    }

    private static WeChatCredentialStore storeFor(Path dir, String workspaceId) {
        return new WeChatCredentialStore(dir.resolve("wechat-bindings.json"), workspaceId);
    }
}
