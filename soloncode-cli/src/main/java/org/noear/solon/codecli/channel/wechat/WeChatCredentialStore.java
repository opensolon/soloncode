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
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 微信凭据持久化存储
 *
 * <p>将 sessionId -> WeChatBinding 的映射保存到本地文件，
 * 确保重启后已绑定的微信通道自动恢复。</p>
 *
 * <p>写入采用「临时文件 + 原子移动」：直接覆盖写在进程被中断或崩溃时
 * 会留下截断的 JSON，下次加载即丢失全部绑定。</p>
 *
 * @author noear 2026/5/5 created
 */
public class WeChatCredentialStore {
    private static final Logger LOG = LoggerFactory.getLogger(WeChatCredentialStore.class);

    private static final String STORE_FILE = "wechat-bindings.json";

    /**
     * 节流写入的最小间隔。长轮询每轮都可能推进游标，全量重写过于频繁。
     */
    private static final long SAVE_MIN_INTERVAL_MS = 2_000L;

    private final Path storePath;

    private final AtomicLong lastSaveAt = new AtomicLong(0);
    private final AtomicBoolean flushPending = new AtomicBoolean(false);

    public WeChatCredentialStore(HarnessEngine engine) {
        this(Paths.get(engine.getUserDir(),
                engine.getHarnessChannels(),
                STORE_FILE).toAbsolutePath());
    }

    /**
     * 测试用构造：直接指定存储文件
     */
    WeChatCredentialStore(Path storePath) {
        this.storePath = storePath;
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
            String content = new String(Files.readAllBytes(storePath), StandardCharsets.UTF_8);
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
                    binding.baseUrl = WeChatClient.normalizeBaseUrl(node.get("baseUrl").getString());
                    binding.cursor = node.get("cursor").getString();
                    binding.restoreReplyTarget(
                            node.get("lastFromUserId").getString(),
                            node.get("lastContextToken").getString());

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
     * 节流保存：高频路径（长轮询推进游标）使用，合并短时间内的多次写入。
     *
     * <p>传入的是活的映射引用，延迟触发时会以那一刻的最新内容落盘。</p>
     */
    public void saveThrottled(Map<String, WeChatLink.WeChatBinding> bindings) {
        long waitMs = SAVE_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastSaveAt.get());
        if (waitMs <= 0) {
            save(bindings);
            return;
        }

        if (flushPending.compareAndSet(false, true)) {
            RunUtil.delay(() -> {
                flushPending.set(false);
                save(bindings);
            }, waitMs);
        }
    }

    /**
     * 保存所有绑定凭据到文件
     */
    public void save(Map<String, WeChatLink.WeChatBinding> bindings) {
        lastSaveAt.set(System.currentTimeMillis());

        if (bindings == null || bindings.isEmpty()) {
            File file = storePath.toFile();
            if (file.exists() && !file.delete()) {
                LOG.warn("[WeChatStore] Failed to delete {}", storePath);
            }
            return;
        }

        try {
            // 确保目录存在
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
            for (Map.Entry<String, WeChatLink.WeChatBinding> entry : bindings.entrySet()) {
                String sessionId = entry.getKey();
                WeChatLink.WeChatBinding binding = entry.getValue();

                ONode node = new ONode();
                node.set("botToken", binding.botToken);
                node.set("ilinkBotId", binding.ilinkBotId);
                node.set("ilinkUserId", binding.ilinkUserId);
                node.set("baseUrl", binding.baseUrl);
                node.set("cursor", binding.cursor);
                node.set("lastContextToken", binding.getLastContextToken());
                node.set("lastFromUserId", binding.getLastFromUserId());

                root.set(sessionId, node);
            }

            writeAtomic(root.toJson());
            LOG.debug("[WeChatStore] Saved {} bindings to {}", bindings.size(), storePath);
        } catch (IOException e) {
            LOG.error("[WeChatStore] Failed to save credentials to {}: {}", storePath, e.toString());
        }
    }

    /**
     * 先写同目录临时文件再原子移动，保证读到的永远是完整 JSON
     */
    private void writeAtomic(String json) throws IOException {
        Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, storePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 个别文件系统不支持原子移动，退回普通替换
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
