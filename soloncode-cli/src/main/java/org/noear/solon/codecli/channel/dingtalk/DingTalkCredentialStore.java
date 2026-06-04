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
import org.noear.solon.codecli.config.AgentProperties;
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

    public DingTalkCredentialStore(HarnessEngine engine) {
        storePath = Paths.get(AgentProperties.getUserDir(),
                engine.getHarnessChannels(),
                STORE_FILE).toAbsolutePath();
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
        if (bindings == null || bindings.isEmpty()) {
            File file = storePath.toFile();
            if (file.exists()) {
                file.delete();
            }
            return;
        }

        try {
            Files.createDirectories(storePath.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            for (Map.Entry<String, DingTalkLink.DingTalkBinding> entry : bindings.entrySet()) {
                String sessionId = entry.getKey();
                DingTalkLink.DingTalkBinding binding = entry.getValue();

                ONode node = new ONode();
                node.set("userId", binding.userId);
                node.set("robotCode", binding.robotCode);
                node.set("lastMessageId", binding.lastMessageId);
                node.set("appKey", binding.appKey);
                node.set("appSecret", binding.appSecret);

                root.set(sessionId, node);
            }

            Files.write(storePath, root.toJson().getBytes());
            LOG.debug("[DingTalkStore] Saved {} bindings to {}", bindings.size(), storePath);
        } catch (IOException e) {
            LOG.error("[DingTalkStore] Failed to save credentials to {}: {}", storePath, e.toString());
        }
    }
}
