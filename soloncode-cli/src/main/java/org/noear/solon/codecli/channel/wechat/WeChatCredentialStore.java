/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel.wechat;

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
 * 微信凭据持久化存储
 *
 * <p>将 sessionId -> WeChatBinding 的映射保存到本地文件，
 * 确保重启后已绑定的微信通道自动恢复。</p>
 *
 * @author noear 2026/5/5 created
 */
public class WeChatCredentialStore {
    private static final Logger LOG = LoggerFactory.getLogger(WeChatCredentialStore.class);

    private static final String STORE_FILE = "wechat-bindings.json";

    private final Path storePath;

    public WeChatCredentialStore(HarnessEngine engine) {
        storePath = Paths.get(engine.getUserDir(),
                engine.getHarnessChannels(),
                STORE_FILE).toAbsolutePath();
    }

    /**
     * 加载所有已保存的绑定凭据
     */
    public Map<String, WeChatLink.WeChatBinding> load() {
        File file = storePath.toFile();
        if (!file.exists()) {
            LOG.debug("[WeChatStore] No credential file found at {}", storePath);
            return Collections.emptyMap();
        }

        try {
            String content = new String(Files.readAllBytes(storePath));
            ONode root = ONode.ofJson(content);

            Map<String, WeChatLink.WeChatBinding> result = new LinkedHashMap<>();

            // 遍历所有字段（根节点是对象）
            if (root.isObject()) {
                for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
                    String sessionId = entry.getKey();
                    ONode node = entry.getValue();

                    WeChatLink.WeChatBinding binding = new WeChatLink.WeChatBinding();
                    binding.botToken = node.get("botToken").getString();
                    binding.ilinkBotId = node.get("ilinkBotId").getString();
                    binding.ilinkUserId = node.get("ilinkUserId").getString();
                    binding.cursor = node.get("cursor").getString();
                    binding.lastContextToken = node.get("lastContextToken").getString();
                    binding.lastFromUserId = node.get("lastFromUserId").getString();

                    if (binding.botToken != null && !binding.botToken.isEmpty()) {
                        result.put(sessionId, binding);
                    }
                }
            }

            LOG.info("[WeChatStore] Loaded {} bindings from {}", result.size(), storePath);
            return result;
        } catch (Exception e) {
            LOG.warn("[WeChatStore] Failed to load credentials from {}: {}", storePath, e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * 保存所有绑定凭据到文件
     */
    public void save(Map<String, WeChatLink.WeChatBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            File file = storePath.toFile();
            if (file.exists()) {
                file.delete();
            }
            return;
        }

        try {
            // 确保目录存在
            Files.createDirectories(storePath.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            for (Map.Entry<String, WeChatLink.WeChatBinding> entry : bindings.entrySet()) {
                String sessionId = entry.getKey();
                WeChatLink.WeChatBinding binding = entry.getValue();

                ONode node = new ONode();
                node.set("botToken", binding.botToken);
                node.set("ilinkBotId", binding.ilinkBotId);
                node.set("ilinkUserId", binding.ilinkUserId);
                node.set("cursor", binding.cursor);
                node.set("lastContextToken", binding.lastContextToken);
                node.set("lastFromUserId", binding.lastFromUserId);

                root.set(sessionId, node);
            }

            Files.write(storePath, root.toJson().getBytes());
            LOG.debug("[WeChatStore] Saved {} bindings to {}", bindings.size(), storePath);
        } catch (IOException e) {
            LOG.error("[WeChatStore] Failed to save credentials to {}: {}", storePath, e.toString());
        }
    }
}
