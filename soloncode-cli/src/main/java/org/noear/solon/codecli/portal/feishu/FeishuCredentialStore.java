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
package org.noear.solon.codecli.portal.feishu;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
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
 * 飞书凭据持久化存储
 *
 * <p>将 sessionId -> FeishuBinding 的映射及 appId/appSecret 凭据保存到本地文件，
 * 确保重启后已绑定的飞书通道自动恢复。</p>
 *
 * @author noear 2026/5/9 created
 */
public class FeishuCredentialStore {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuCredentialStore.class);

    private static final String STORE_FILE = "feishu-bindings.json";

    private final Path storePath;

    public FeishuCredentialStore(AgentProperties agentProps) {
        storePath = Paths.get(AgentProperties.getUserDir(),
                agentProps.getHarnessHome(),
                STORE_FILE).toAbsolutePath();
    }

    /**
     * 恢复数据（包含凭据和绑定）
     */
    public static class RestoreData {
        public String appId;
        public String appSecret;
        public Map<String, FeishuLink.FeishuBinding> bindings = new LinkedHashMap<>();
    }

    /**
     * 加载所有已保存的数据（含 appId/appSecret）
     */
    public RestoreData loadWithCredentials() {
        RestoreData result = new RestoreData();
        File file = storePath.toFile();
        if (!file.exists()) {
            LOG.debug("[FeishuStore] No credential file found at {}", storePath);
            return result;
        }

        try {
            String content = new String(Files.readAllBytes(storePath));
            ONode root = ONode.ofJson(content);

            // 读取凭据
            if (root.hasKey("_credentials")) {
                ONode cred = root.get("_credentials");
                result.appId = cred.get("appId").getString();
                result.appSecret = cred.get("appSecret").getString();
            }

            // 读取绑定
            if (root.isObject()) {
                for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
                    String sessionId = entry.getKey();
                    if (sessionId.startsWith("_")) continue; // 跳过元数据

                    ONode node = entry.getValue();
                    FeishuLink.FeishuBinding binding = new FeishuLink.FeishuBinding();
                    binding.openId = node.get("openId").getString();
                    binding.lastMessageId = node.get("lastMessageId").getString();

                    if (binding.openId != null && !binding.openId.isEmpty()) {
                        result.bindings.put(sessionId, binding);
                    }
                }
            }

            LOG.info("[FeishuStore] Loaded {} bindings from {}", result.bindings.size(), storePath);
            return result;
        } catch (Exception e) {
            LOG.warn("[FeishuStore] Failed to load credentials from {}: {}", storePath, e.toString());
            return result;
        }
    }

    /**
     * 保存所有绑定凭据和 appId/appSecret 到文件
     */
    public void saveWithCredentials(Map<String, FeishuLink.FeishuBinding> bindings, String appId, String appSecret) {
        try {
            Files.createDirectories(storePath.getParent());

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));

            // 保存凭据
            if (appId != null && !appId.isEmpty()) {
                ONode cred = new ONode();
                cred.set("appId", appId);
                cred.set("appSecret", appSecret != null ? appSecret : "");
                root.set("_credentials", cred);
            }

            // 保存绑定
            if (bindings != null && !bindings.isEmpty()) {
                for (Map.Entry<String, FeishuLink.FeishuBinding> entry : bindings.entrySet()) {
                    String sessionId = entry.getKey();
                    FeishuLink.FeishuBinding binding = entry.getValue();

                    ONode node = new ONode();
                    node.set("openId", binding.openId);
                    node.set("lastMessageId", binding.lastMessageId != null ? binding.lastMessageId : "");

                    root.set(sessionId, node);
                }
            }

            // 如果没有任何数据，删除文件
            String json = root.toJson();
            if (json.equals("{}") || json.trim().isEmpty()) {
                File file = storePath.toFile();
                if (file.exists()) {
                    file.delete();
                }
                return;
            }

            Files.write(storePath, json.getBytes());
            LOG.debug("[FeishuStore] Saved {} bindings to {}", bindings != null ? bindings.size() : 0, storePath);
        } catch (IOException e) {
            LOG.error("[FeishuStore] Failed to save credentials to {}: {}", storePath, e.toString());
        }
    }
}
