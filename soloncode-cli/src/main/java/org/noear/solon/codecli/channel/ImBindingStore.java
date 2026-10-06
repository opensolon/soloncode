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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按 channel/identity/userKey 持久化 IM 绑定登记表。 */
public class ImBindingStore {
    private static final Logger LOG = LoggerFactory.getLogger(ImBindingStore.class);

    /** 2 起登记键含 bot 身份维（channel + identity + userKey）；1 为旧的 channel + userKey。 */
    private static final int VERSION = 2;
    /** 登记表文件名；历史位置可能存在同格式文件，需回收。 */
    public static final String STORE_FILE = "im-bindings.json";
    /** 旧版飞书绑定文件（sessionId 为键，节点内含 openId/appId/appSecret）。 */
    public static final String LEGACY_FEISHU_FILE = "feishu-bindings.json";
    /** 旧版钉钉绑定文件（sessionId 为键，节点内含 userId/appKey/appSecret）。 */
    public static final String LEGACY_DINGTALK_FILE = "dingtalk-bindings.json";

    private final Path storePath;

    /**
     * 历史目录：既用于回收「写错位置的同格式登记表」，也用于旧格式文件的迁移。
     *
     * <p>登记表自身所在目录（{@code storePath.getParent()}）无需在此列出，会隐式参与扫描。</p>
     */
    private final List<Path> legacyDirs;

    public ImBindingStore(Path storePath) {
        this(storePath, Collections.emptyList());
    }

    public ImBindingStore(Path storePath, List<Path> legacyDirs) {
        if (storePath == null) {
            throw new IllegalArgumentException("storePath must not be null");
        }
        this.storePath = storePath;
        this.legacyDirs = legacyDirs == null ? Collections.emptyList() : new ArrayList<>(legacyDirs);
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
            // 1) 同格式登记表被写到了别处（历史版本误取进程启动目录所致）
            consumed |= adoptFile(result, dir.resolve(STORE_FILE), this::readNew);
            // 2) 旧格式文件，均为 sessionId 为键：飞书 / 钉钉
            consumed |= adoptFile(result, dir.resolve(LEGACY_FEISHU_FILE), this::readLegacyFeishu);
            consumed |= adoptFile(result, dir.resolve(LEGACY_DINGTALK_FILE), this::readLegacyDingTalk);
        }
        return consumed;
    }

    /** 扫描目录 = 登记表自身目录 + 显式传入的历史目录（去重保序）。 */
    private List<Path> scanDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        Path parent = storePath.getParent();
        if (parent != null) {
            dirs.add(parent);
        }
        dirs.addAll(legacyDirs);
        return new ArrayList<>(dirs);
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
        return new ImBindingRegistry.Binding(channel, userKey, identity,
                text(node, "workspaceId"), text(node, "sessionId"), number(node, "updatedAt", 0L),
                text(node, "secret"), text(node, "lastMessageId"));
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
