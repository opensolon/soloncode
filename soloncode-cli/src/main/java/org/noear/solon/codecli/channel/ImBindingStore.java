/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.noear.solon.codecli.channel;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按 channel/identity/userKey 持久化 IM 绑定登记表。 */
public class ImBindingStore {
    private static final Logger LOG = LoggerFactory.getLogger(ImBindingStore.class);

    /**
     * 3 起单一文件承载全部三渠道（含微信）；2 为仅飞书/钉钉、键含 bot 身份维；
     * 1 为最旧的 channel + userKey。
     */
    private static final int VERSION = 3;
    /** 登记表文件名。 */
    public static final String STORE_FILE = "im-bindings.json";
    /** 旧版飞书绑定文件（sessionId 为键，节点内含 openId/appId/appSecret）。 */
    public static final String LEGACY_FEISHU_FILE = "feishu-bindings.json";
    /** 旧版钉钉绑定文件（sessionId 为键，节点内含 userId/appKey/appSecret）。 */
    public static final String LEGACY_DINGTALK_FILE = "dingtalk-bindings.json";
    /** 旧版微信绑定文件（sessionId 为键，节点内含 botToken/ilinkBotId/ilinkUserId）。 */
    public static final String LEGACY_WECHAT_FILE = "wechat-bindings.json";

    // ==================== 通道私有状态的键 ====================
    /** 微信：服务端指派的接入点。 */
    public static final String RT_WECHAT_BASE_URL = "wechat.baseUrl";
    /** 微信：上一条入站消息的用户 ID（与下一个键一起构成回复目标）。 */
    public static final String RT_WECHAT_LAST_FROM_USER = "wechat.lastFromUserId";
    /** 微信：上一条入站消息的 context_token。 */
    public static final String RT_WECHAT_LAST_CONTEXT_TOKEN = "wechat.lastContextToken";

    private final Path storePath;

    public ImBindingStore(Path storePath) {
        if (storePath == null) {
            throw new IllegalArgumentException("storePath must not be null");
        }
        this.storePath = storePath;
    }

    public Path getStorePath() {
        return storePath;
    }

    /**
     * 加载绑定。
     *
     * <p>登记表存在则直接读；否则扫描历史目录做一次性迁移（回收 + 旧格式转换），
     * 迁移有结果时立即落到正确位置。两者皆无则返回空映射。</p>
     */
    public Map<String, ImBindingRegistry.Binding> load() {
        try {
            if (Files.exists(storePath)) {
                return readNew(Files.readAllBytes(storePath));
            }
            Map<String, ImBindingRegistry.Binding> migrated = new LinkedHashMap<>();
            if (migrate(migrated)) {
                write(migrated);
            }
            return migrated;
        } catch (Exception e) {
            // 损坏或不可读的登记表不应阻止 CLI 启动，但必须留痕：否则绑定会无声消失。
            LOG.warn("[ImBinding] Failed to load {}: {}", storePath, e.toString());
        }
        return Collections.emptyMap();
    }

    /** 保存全量绑定，写入采用同目录临时文件再移动。 */
    public void save(Map<String, ImBindingRegistry.Binding> bindings) {
        if (bindings == null) {
            return;
        }
        try {
            write(bindings);
        } catch (IOException e) {
            LOG.warn("[ImBinding] Failed to save {}: {}", storePath, e.toString());
        }
    }

    /**
     * 从历史目录回收绑定；返回是否消费了任何来源（用于决定是否需要落盘）。
     */
    private boolean migrate(Map<String, ImBindingRegistry.Binding> result) {
        boolean consumed = false;
        for (Path dir : scanDirs()) {
            // 旧格式文件（sessionId 为键）：飞书 / 钉钉 / 微信
            consumed |= adoptFile(result, dir.resolve(LEGACY_FEISHU_FILE), this::readLegacyFeishu);
            consumed |= adoptFile(result, dir.resolve(LEGACY_DINGTALK_FILE), this::readLegacyDingTalk);
            consumed |= adoptFile(result, dir.resolve(LEGACY_WECHAT_FILE), this::readLegacyWeChat);
        }
        return consumed;
    }

    /** 扫描目录：只含登记表自身所在目录（旧格式迁移仅在此进行）。 */
    private List<Path> scanDirs() {
        List<Path> dirs = new ArrayList<>();
        Path parent = storePath.getParent();
        if (parent != null) {
            dirs.add(parent);
        }
        return dirs;
    }

    /**
     * 读取一个历史文件并并入结果，成功后改名留底。
     *
     * @return 是否真的消费了该文件
     */
    private boolean adoptFile(Map<String, ImBindingRegistry.Binding> result, Path source, Reader reader) {
        if (source.equals(storePath) || !Files.exists(source)) {
            return false;
        }
        try {
            Map<String, ImBindingRegistry.Binding> parsed = reader.read(Files.readAllBytes(source));
            for (ImBindingRegistry.Binding binding : parsed.values()) {
                putIfNewer(result, binding);
            }
            backup(source);
            LOG.info("[ImBinding] Migrated {} entries from {}", parsed.size(), source);
            return true;
        } catch (Exception e) {
            LOG.warn("[ImBinding] Failed to migrate {}: {}", source, e.toString());
            return false;
        }
    }

    private Map<String, ImBindingRegistry.Binding> readNew(byte[] bytes) {
        ONode root = ONode.ofJson(new String(bytes, StandardCharsets.UTF_8));
        Map<String, ImBindingRegistry.Binding> result = new LinkedHashMap<>();
        if (!root.isObject()) {
            return result;
        }
        for (Map.Entry<String, ONode> channelEntry : root.getObject().entrySet()) {
            String channel = channelEntry.getKey();
            if ("version".equals(channel) || !channelEntry.getValue().isObject()) {
                continue;
            }
            for (Map.Entry<String, ONode> userEntry : channelEntry.getValue().getObject().entrySet()) {
                ImBindingRegistry.Binding binding = parseBinding(channel, userEntry.getKey(), userEntry.getValue());
                if (binding != null) {
                    putIfNewer(result, binding);
                }
            }
        }
        return result;
    }

    /** 旧版飞书：sessionId 为键，节点内含 openId/appId/appSecret。 */
    private Map<String, ImBindingRegistry.Binding> readLegacyFeishu(byte[] bytes) {
        ONode root = ONode.ofJson(new String(bytes, StandardCharsets.UTF_8));
        Map<String, ImBindingRegistry.Binding> result = new LinkedHashMap<>();
        if (!root.isObject()) {
            return result;
        }
        for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
            ONode node = entry.getValue();
            String userKey = text(node, "openId");
            if (userKey == null || userKey.isEmpty()) {
                continue;
            }
            ImBindingRegistry.Binding binding = new ImBindingRegistry.Binding(
                    "feishu", userKey,
                    new ImBindingRegistry.Identity(text(node, "appId"), null, null),
                    text(node, "workspaceId"), entry.getKey(), 0L,
                    text(node, "appSecret"), text(node, "lastMessageId"));
            // LinkedHashMap iteration is the legacy file order; later equal entries win.
            result.put(ImBindingRegistry.keyOf(binding), binding);
        }
        return result;
    }

    /** 旧版钉钉：sessionId 为键，节点内含 userId/appKey（或 robotCode）/appSecret。 */
    private Map<String, ImBindingRegistry.Binding> readLegacyDingTalk(byte[] bytes) {
        ONode root = ONode.ofJson(new String(bytes, StandardCharsets.UTF_8));
        Map<String, ImBindingRegistry.Binding> result = new LinkedHashMap<>();
        if (!root.isObject()) {
            return result;
        }
        for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
            ONode node = entry.getValue();
            String userKey = text(node, "userId");
            if (userKey == null || userKey.isEmpty()) {
                continue;
            }
            // 新版钉钉落 appKey，旧版另有 robotCode 字段；二者同源，缺失时互为回退。
            String appKey = text(node, "appKey");
            if (appKey == null || appKey.isEmpty()) {
                appKey = text(node, "robotCode");
            }
            ImBindingRegistry.Binding binding = new ImBindingRegistry.Binding(
                    "dingtalk", userKey,
                    new ImBindingRegistry.Identity(null, appKey, null),
                    text(node, "workspaceId"), entry.getKey(), 0L,
                    text(node, "appSecret"), text(node, "lastMessageId"));
            result.put(ImBindingRegistry.keyOf(binding), binding);
        }
        return result;
    }

    /** 旧版微信：sessionId 为键，节点内含 botToken/ilinkBotId/ilinkUserId/baseUrl/回复目标。 */
    private Map<String, ImBindingRegistry.Binding> readLegacyWeChat(byte[] bytes) {
        ONode root = ONode.ofJson(new String(bytes, StandardCharsets.UTF_8));
        Map<String, ImBindingRegistry.Binding> result = new LinkedHashMap<>();
        if (!root.isObject()) {
            return result;
        }

        for (Map.Entry<String, ONode> entry : root.getObject().entrySet()) {
            ONode node = entry.getValue();
            if (node == null || !node.isObject()) {
                continue;
            }
            String botToken = text(node, "botToken");
            String userKey = text(node, "ilinkUserId");
            if (botToken == null || botToken.isEmpty() || userKey == null || userKey.isEmpty()) {
                continue;
            }
            // 身份维取 ilinkBotId（稳定，不随重新授权换发）；缺失时回退 botToken
            String ilinkBotId = text(node, "ilinkBotId");
            ImBindingRegistry.Binding binding = new ImBindingRegistry.Binding(
                    "wechat", userKey,
                    new ImBindingRegistry.Identity(null, null,
                            ilinkBotId == null || ilinkBotId.isEmpty() ? botToken : ilinkBotId),
                    text(node, "workspaceId"), entry.getKey(), 0L,
                    botToken, null);
            binding.putRuntime(RT_WECHAT_BASE_URL, text(node, "baseUrl"));
            binding.putRuntime(RT_WECHAT_LAST_FROM_USER, text(node, "lastFromUserId"));
            binding.putRuntime(RT_WECHAT_LAST_CONTEXT_TOKEN, text(node, "lastContextToken"));
            // cursor 不迁移：旧实现里它由引擎写入，而引擎在生产中用的是空存储（永不推进），
            // 落盘值恒为空串；新设计也明确不持久化游标（重启从当前 seq 起）。
            result.put(ImBindingRegistry.keyOf(binding), binding);
        }
        return result;
    }

    private ImBindingRegistry.Binding parseBinding(String channel, String entryKey, ONode node) {
        if (entryKey == null || entryKey.isEmpty() || node == null || !node.isObject()) {
            return null;
        }
        // 节点内的 userKey 是权威值：v2 起 JSON 条目键已含身份维（identity␟userKey），
        // 不能再直接当 userKey 用；仅为兼容手写的旧式文件才回退到条目键。
        String userKey = text(node, "userKey");
        if (userKey == null || userKey.isEmpty()) {
            userKey = entryKey;
        }
        ImBindingRegistry.Identity identity = new ImBindingRegistry.Identity(
                text(node, "appId"), text(node, "appKey"), text(node, "botToken"));
        ImBindingRegistry.Binding binding = new ImBindingRegistry.Binding(channel, userKey, identity,
                text(node, "workspaceId"), text(node, "sessionId"), number(node, "updatedAt", 0L),
                text(node, "secret"), text(node, "lastMessageId"));
        parseRuntime(binding, node);
        return binding;
    }

    /** 读取通道私有状态子对象；缺失或非对象一律忽略。 */
    private void parseRuntime(ImBindingRegistry.Binding binding, ONode node) {
        ONode runtime = node.get("runtime");
        if (runtime == null || !runtime.isObject()) {
            return;
        }
        for (Map.Entry<String, ONode> entry : runtime.getObject().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            binding.putRuntime(entry.getKey(), entry.getValue().getString());
        }
    }

    private void putIfNewer(Map<String, ImBindingRegistry.Binding> result,
                            ImBindingRegistry.Binding candidate) {
            if (candidate == null || candidate.getUserKey() == null || candidate.getChannel() == null) {
                return;
            }
            // 键由 channel + identity + userKey 重算：v1 文件里虽无身份维的键，
            // 但节点内已存 appId/appKey，因此旧数据可无损升级到新键。
            String key = ImBindingRegistry.keyOf(candidate);
            ImBindingRegistry.Binding current = result.get(key);
            if (current == null || candidate.getUpdatedAt() >= current.getUpdatedAt()) {
                result.put(key, candidate);
            }
    }

    private void write(Map<String, ImBindingRegistry.Binding> bindings) throws IOException {
        Path parent = storePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
        root.set("version", VERSION);
        for (ImBindingRegistry.Binding binding : bindings.values()) {
            if (binding == null || binding.getUserKey() == null || binding.getChannel() == null) {
                continue;
            }
            ONode channel = root.getOrNew(binding.getChannel());
            ONode node = new ONode();
            node.set("channel", binding.getChannel());
            node.set("userKey", binding.getUserKey());
            ImBindingRegistry.Identity identity = binding.getIdentity();
            if (identity != null) {
                node.set("appId", identity.getAppId());
                node.set("appKey", identity.getAppKey());
                node.set("botToken", identity.getBotToken());
            }
            node.set("workspaceId", binding.getWorkspaceId());
            node.set("sessionId", binding.getSessionId());
            node.set("secret", binding.getSecret());
            node.set("lastMessageId", binding.getLastMessageId());
            node.set("updatedAt", binding.getUpdatedAt());
            // 通道私有状态：空则整个子对象不写，避免给空登记表添噪声
            Map<String, String> runtime = binding.getRuntime();
            if (!runtime.isEmpty()) {
                ONode runtimeNode = new ONode();
                for (Map.Entry<String, String> runtimeEntry : runtime.entrySet()) {
                    runtimeNode.set(runtimeEntry.getKey(), runtimeEntry.getValue());
                }
                node.set("runtime", runtimeNode);
            }
            // JSON 键含身份维，避免多 bot 同 userKey 时互相覆盖
            String identityKey = ImBindingRegistry.identityKey(binding.getIdentity());
            String entryKey = identityKey.isEmpty()
                    ? binding.getUserKey()
                    : identityKey + ImBindingRegistry.KEY_SEPARATOR + binding.getUserKey();
            channel.set(entryKey, node);
        }
        Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
        Files.write(tmp, root.toJson().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicFailure);
                throw fallbackFailure;
            }
        }
    }

    private void backup(Path legacy) throws IOException {
        Path backup = legacy.resolveSibling(legacy.getFileName() + ".bak." + System.currentTimeMillis());
        Files.move(legacy, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String text(ONode node, String key) {
        ONode value = node.get(key);
        return value == null ? null : value.getString();
    }

    private static long number(ONode node, String key, long fallback) {
        String value = text(node, key);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @FunctionalInterface
    private interface Reader {
        Map<String, ImBindingRegistry.Binding> read(byte[] bytes);
    }
}
