package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 绑定归属与合并持久化验证。
 *
 * <p>背景（线上事故）：绑定文件是全局单文件，各工作区的 FeishuLink 各自为同一 appId
 * 建立 WS 连接，飞书服务端随机路由消息——消息可能落到错误工作区，在那里凭空建出
 * 空 session（忙态判定失明 + 无选中模型回落默认模型 404）。</p>
 */
class FeishuBindingOwnershipTest {

    private static FeishuLink.FeishuBinding binding(String openId, String appId, String workspaceId) {
        FeishuLink.FeishuBinding binding = new FeishuLink.FeishuBinding();
        binding.openId = openId;
        binding.appId = appId;
        binding.appSecret = "secret";
        binding.workspaceId = workspaceId;
        return binding;
    }

    @Test
    void saveMergesOtherWorkspaceBindingsInsteadOfOverwriting(@TempDir Path dir) throws Exception {
        // 全局文件里已存在其它工作区的绑定
        Path storeFile = dir.resolve("feishu-bindings.json");
        Map<String, FeishuLink.FeishuBinding> existing = new LinkedHashMap<>();
        existing.put("session-other", binding("user-other", "app-1", "ws-other"));
        writeStore(storeFile, existing);

        FeishuCredentialStore mineStore = storeFor(dir, "ws-mine");
        Map<String, FeishuLink.FeishuBinding> mine = new LinkedHashMap<>();
        mine.put("session-mine", binding("user-mine", "app-2", "ws-mine"));
        mineStore.save(mine);

        // 合并持久化：其它工作区条目必须保留，本工作区条目写入
        Map<String, FeishuLink.FeishuBinding> merged = readStore(storeFile);
        assertEquals(2, merged.size());
        assertEquals("ws-other", merged.get("session-other").workspaceId);
        assertEquals("ws-mine", merged.get("session-mine").workspaceId);
    }

    @Test
    void saveRemovesOnlyOwnUnboundEntries(@TempDir Path dir) throws Exception {
        Path storeFile = dir.resolve("feishu-bindings.json");
        Map<String, FeishuLink.FeishuBinding> existing = new LinkedHashMap<>();
        existing.put("session-mine", binding("user-mine", "app-1", "ws-mine"));
        existing.put("session-other", binding("user-other", "app-2", "ws-other"));
        writeStore(storeFile, existing);

        // 本工作区 unbind 后保存空 map：只应删掉自己的，不能碰其它工作区的
        FeishuCredentialStore mineStore = storeFor(dir, "ws-mine");
        mineStore.save(new LinkedHashMap<>());

        Map<String, FeishuLink.FeishuBinding> merged = readStore(storeFile);
        assertEquals(1, merged.size());
        assertEquals("session-other", merged.keySet().iterator().next());
    }

    @Test
    void legacyBindingsWithoutWorkspaceIdAreNotClaimedByOtherWorkspace(@TempDir Path dir) throws Exception {
        Path storeFile = dir.resolve("feishu-bindings.json");
        Map<String, FeishuLink.FeishuBinding> existing = new LinkedHashMap<>();
        existing.put("session-legacy", binding("user-legacy", "app-1", null));
        writeStore(storeFile, existing);

        // 其它工作区保存自己的绑定：无归属的遗留条目不算它的，不得被认领/覆盖
        FeishuCredentialStore otherStore = storeFor(dir, "ws-other");
        Map<String, FeishuLink.FeishuBinding> other = new LinkedHashMap<>();
        other.put("session-other", binding("user-other", "app-2", "ws-other"));
        otherStore.save(other);

        Map<String, FeishuLink.FeishuBinding> merged = readStore(storeFile);
        assertEquals(2, merged.size());
        assertNull(merged.get("session-legacy").workspaceId);
    }

    // ==================== helpers ====================

    /** 用与生产一致的 JSON 形态写入全局绑定文件（模拟旧版/其它工作区写下的现场）。 */
    private static void writeStore(Path storeFile, Map<String, FeishuLink.FeishuBinding> bindings) throws Exception {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, FeishuLink.FeishuBinding> entry : bindings.entrySet()) {
            FeishuLink.FeishuBinding b = entry.getValue();
            sb.append("  \"").append(entry.getKey()).append("\": {")
                    .append("\"openId\": \"").append(b.openId).append("\", ")
                    .append("\"lastMessageId\": \"\", ")
                    .append("\"appId\": \"").append(b.appId).append("\", ")
                    .append("\"appSecret\": \"").append(b.appSecret).append("\"");
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

    private static Map<String, FeishuLink.FeishuBinding> readStore(Path storeFile) throws Exception {
        String content = new String(Files.readAllBytes(storeFile), StandardCharsets.UTF_8);
        org.noear.snack4.ONode root = org.noear.snack4.ONode.ofJson(content);
        Map<String, FeishuLink.FeishuBinding> result = new LinkedHashMap<>();
        for (Map.Entry<String, org.noear.snack4.ONode> entry : root.getObject().entrySet()) {
            FeishuLink.FeishuBinding b = new FeishuLink.FeishuBinding();
            b.openId = entry.getValue().get("openId").getString();
            b.appId = entry.getValue().get("appId").getString();
            b.appSecret = entry.getValue().get("appSecret").getString();
            b.workspaceId = entry.getValue().get("workspaceId") != null
                    ? entry.getValue().get("workspaceId").getString() : null;
            result.put(entry.getKey(), b);
        }
        return result;
    }

    private static FeishuCredentialStore storeFor(Path dir, String workspaceId) {
        return new FeishuCredentialStore(dir.resolve("feishu-bindings.json"), workspaceId);
    }
}
