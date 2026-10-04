/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.feishu;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.harness.HarnessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 飞书凭据持久化存储
 *
 * <p>将 sessionId -> FeishuBinding 的映射保存到本地文件，
 * 确保重启后已绑定的飞书通道自动恢复。</p>
 *
 * <p>FeishuBinding 中包含 appId/appSecret，
 * 重启后可据此自动恢复 WebSocket 连接。</p>
 *
 * @author noear 2026/5/9 created
 */
public class FeishuCredentialStore {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuCredentialStore.class);

    private static final String STORE_FILE = "feishu-bindings.json";

    private final Path storePath;

    /** 绑定归属的工作区 ID；null 表示不做工作区过滤（兼容旧行为）。 */
    private final String workspaceId;

    public FeishuCredentialStore(HarnessEngine engine) {
        this(engine, null);
    }

    public FeishuCredentialStore(HarnessEngine engine, String workspaceId) {
        this(Paths.get(engine.getUserDir(),
                engine.getHarnessChannels(),
                STORE_FILE).toAbsolutePath(), workspaceId);
    }

    /** 测试友好：直接指定存储文件路径。 */
    FeishuCredentialStore(Path storePath, String workspaceId) {
        this.storePath = storePath;
        this.workspaceId = workspaceId;
    }

    /** 供 Link 在装载后回写归属标记。 */
    public String workspaceId() {
        return workspaceId;
    }

    /**
     * 加载所有已保存的绑定凭据
     */
    public Map<String, FeishuLink.FeishuBinding> load() {
        File file = storePath.toFile();
        if (!file.exists()) {
            LOG.debug("[FeishuStore] No credential file found at {}", storePath);
            return Collections.emptyMap();
        }

        try {
            String content = new String(Files.readAllBytes(storePath));
            ONode root = ONode.ofJson(content);

            Map<String, FeishuLink.FeishuBinding> result = new LinkedHashMap<>();

            if (root.isObject()) {
                for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
                    String sessionId = entry.getKey();
                    ONode node = entry.getValue();

                    FeishuLink.FeishuBinding binding = new FeishuLink.FeishuBinding();
                    binding.openId = node.get("openId").getString();
                    binding.lastMessageId = node.get("lastMessageId").getString();
                    binding.appId = node.get("appId").getString();
                    binding.appSecret = node.get("appSecret").getString();
                    binding.workspaceId = node.get("workspaceId").getString();

                    if (binding.openId != null && !binding.openId.isEmpty()) {
                        result.put(sessionId, binding);
                    }
                }
            }

            LOG.info("[FeishuStore] Loaded {} bindings from {}", result.size(), storePath);
            return result;
        } catch (Exception e) {
            LOG.warn("[FeishuStore] Failed to load credentials from {}: {}", storePath, e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * 保存所有绑定凭据到文件
     */
    public void save(Map<String, FeishuLink.FeishuBinding> bindings) {
        if (bindings == null) {
            return;
        }

        // 合并持久化：全局文件里可能还有其它工作区的绑定。
        // 旧实现直接整文件覆盖本工作区视角的 map，会把其它工作区的绑定一并抹掉（重启即丢失）。
        Map<String, FeishuLink.FeishuBinding> all = new LinkedHashMap<>(load());
        // 先移除本工作区要删除的条目（被 unbind 的），再覆盖本工作区的绑定。
        Set<String> mine = new HashSet<>(bindings.keySet());
        for (String sessionId : new HashSet<>(all.keySet())) {
            FeishuLink.FeishuBinding existing = all.get(sessionId);
            if (existing != null && isMine(existing) && !mine.contains(sessionId)) {
                all.remove(sessionId);
            }
        }
        all.putAll(bindings);

        if (all.isEmpty()) {
            File file = storePath.toFile();
            if (file.exists()) {
                file.delete();
            }
            return;
        }

        try {
            Files.createDirectories(storePath.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            for (Map.Entry<String, FeishuLink.FeishuBinding> entry : all.entrySet()) {
                String sessionId = entry.getKey();
                FeishuLink.FeishuBinding binding = entry.getValue();

                ONode node = new ONode();
                node.set("openId", binding.openId);
                node.set("lastMessageId", binding.lastMessageId != null ? binding.lastMessageId : "");
                node.set("appId", binding.appId);
                node.set("appSecret", binding.appSecret);
                if (binding.workspaceId != null) {
                    node.set("workspaceId", binding.workspaceId);
                }

                root.set(sessionId, node);
            }

            Files.write(storePath, root.toJson().getBytes());
            LOG.debug("[FeishuStore] Saved {} bindings (mine={}) to {}", all.size(), bindings.size(), storePath);
        } catch (IOException e) {
            LOG.error("[FeishuStore] Failed to save credentials to {}: {}", storePath, e.toString());
        }
    }

    /**
     * 绑定是否归属本工作区：null 归属（未指定 workspaceId，测试或旧路径）不做过滤，
     * 视为可全量管理；显式归属以 workspaceId 相等为准。
     */
    private boolean isMine(FeishuLink.FeishuBinding binding) {
        if (workspaceId == null) {
            return true;
        }
        return workspaceId.equals(binding.workspaceId);
    }
}
