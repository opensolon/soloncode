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
package org.noear.solon.codecli.channel.dingtalk;

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
 * 钉钉凭据持久化存储
 *
 * <p>将 sessionId -> DingTalkBinding 的映射保存到本地文件，
 * 确保重启后已绑定的钉钉通道自动恢复。</p>
 *
 * <p>DingTalkBinding 中包含 appKey/appSecret，
 * 重启后可据此自动恢复 Stream 连接。</p>
 *
 * @author noear 2026/5/9 created
 */
public class DingTalkCredentialStore {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkCredentialStore.class);

    private static final String STORE_FILE = "dingtalk-bindings.json";

    private final Path storePath;

    /** 绑定归属的工作区 ID；null 表示不做工作区过滤（兼容旧行为）。 */
    private final String workspaceId;

    public DingTalkCredentialStore(HarnessEngine engine) {
        this(engine, null);
    }

    public DingTalkCredentialStore(HarnessEngine engine, String workspaceId) {
        storePath = Paths.get(engine.getUserDir(),
                engine.getHarnessChannels(),
                STORE_FILE).toAbsolutePath();
        this.workspaceId = workspaceId;
    }

    /** 供 Link 在装载后回写归属标记。 */
    public String workspaceId() {
        return workspaceId;
    }

    /**
     * 加载所有已保存的绑定凭据
     */
    public Map<String, DingTalkLink.DingTalkBinding> load() {
        File file = storePath.toFile();
        if (!file.exists()) {
            LOG.debug("[DingTalkStore] No credential file found at {}", storePath);
            return Collections.emptyMap();
        }

        try {
            String content = new String(Files.readAllBytes(storePath));
            ONode root = ONode.ofJson(content);

            Map<String, DingTalkLink.DingTalkBinding> result = new LinkedHashMap<>();

            if (root.isObject()) {
                for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
                    String sessionId = entry.getKey();
                    ONode node = entry.getValue();

                    DingTalkLink.DingTalkBinding binding = new DingTalkLink.DingTalkBinding();
                    binding.userId = node.get("userId").getString();
                    binding.robotCode = node.get("robotCode").getString();
                    binding.lastMessageId = node.get("lastMessageId").getString();
                    binding.appKey = node.get("appKey").getString();
                    binding.appSecret = node.get("appSecret").getString();
                    binding.workspaceId = node.get("workspaceId").getString();

                    if (binding.userId != null && !binding.userId.isEmpty()) {
                        result.put(sessionId, binding);
                    }
                }
            }

            LOG.info("[DingTalkStore] Loaded {} bindings from {}", result.size(), storePath);
            return result;
        } catch (Exception e) {
            LOG.warn("[DingTalkStore] Failed to load credentials from {}: {}", storePath, e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * 保存所有绑定凭据到文件
     */
    public void save(Map<String, DingTalkLink.DingTalkBinding> bindings) {
        if (bindings == null) {
            return;
        }

        // 合并持久化：全局文件里可能还有其它工作区的绑定。
        // 旧实现直接整文件覆盖本工作区视角的 map，会把其它工作区的绑定一并抹掉（重启即丢失）。
        // 注意 load() 在文件不存在时返回 emptyMap()（不可变），必须包装为可变 map。
        Map<String, DingTalkLink.DingTalkBinding> all = new LinkedHashMap<>(load());
        Set<String> mine = new HashSet<>(bindings.keySet());
        for (String sessionId : new HashSet<>(all.keySet())) {
            DingTalkLink.DingTalkBinding existing = all.get(sessionId);
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
            for (Map.Entry<String, DingTalkLink.DingTalkBinding> entry : all.entrySet()) {
                String sessionId = entry.getKey();
                DingTalkLink.DingTalkBinding binding = entry.getValue();

                ONode node = new ONode();
                node.set("userId", binding.userId);
                node.set("robotCode", binding.robotCode);
                node.set("lastMessageId", binding.lastMessageId);
                node.set("appKey", binding.appKey);
                node.set("appSecret", binding.appSecret);
                if (binding.workspaceId != null) {
                    node.set("workspaceId", binding.workspaceId);
                }

                root.set(sessionId, node);
            }

            Files.write(storePath, root.toJson().getBytes());
            LOG.debug("[DingTalkStore] Saved {} bindings (mine={}) to {}", all.size(), bindings.size(), storePath);
        } catch (IOException e) {
            LOG.error("[DingTalkStore] Failed to save credentials to {}: {}", storePath, e.toString());
        }
    }

    /**
     * 绑定是否归属本工作区：null 归属（未指定 workspaceId，测试或旧路径）不做过滤，
     * 视为可全量管理；显式归属以 workspaceId 相等为准。
     */
    private boolean isMine(DingTalkLink.DingTalkBinding binding) {
        if (workspaceId == null) {
            return true;
        }
        return workspaceId.equals(binding.workspaceId);
    }
}
