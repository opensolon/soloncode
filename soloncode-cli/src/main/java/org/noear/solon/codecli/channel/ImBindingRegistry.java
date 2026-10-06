/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.noear.solon.codecli.channel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 进程内 IM 绑定登记表。
 *
 * <p>本类只管理绑定元数据，不创建连接、不执行网络操作。</p>
 *
 * <p>登记主键为 channel + identity + userKey。identity 是 bot 身份（飞书 appId /
 * 钉钉 appKey / 微信 botToken）；把它并入主键是必要的，因为 userKey 并不总是
 * 应用维度的——飞书 openId 是 app 维度的，但钉钉 staffId 是<b>组织维度</b>的，
 * 同一个人用两个不同 bot 会拿到完全相同的 userKey。若主键不含 identity，第二个
 * bot 的绑定会覆盖第一个，表现为「绑了 bot2，对话1 的绑定消失」。</p>
 *
 * <p>归属约束是「一个 bot（同一 identity）同时只能绑定一个对话」，由
 * {@link #findOwner} 保证；不同 identity 之间互不干扰，因此天然支持多 bot 并存。</p>
 */
public class ImBindingRegistry {
    /** composite key 的分隔符；channel、identity、userKey 均不应包含该控制字符。 */
    public static final String KEY_SEPARATOR = "\u001f";

    private final Map<String, Binding> bindings = new LinkedHashMap<>();

    /** 构造 snapshot/load 使用的内部复合键。 */
    public static String compositeKey(String channel, String identityKey, String userKey) {
        return String.valueOf(channel) + KEY_SEPARATOR
                + normalize(identityKey) + KEY_SEPARATOR
                + String.valueOf(userKey);
    }

    /** 取 bot 身份键（appId / appKey / botToken 中首个非空者）。 */
    public static String identityKey(Identity identity) {
        return identity == null ? "" : identity.key();
    }

    /** 计算一条绑定在登记表中的主键。 */
    public static String keyOf(Binding binding) {
        return compositeKey(binding.getChannel(), identityKey(binding.getIdentity()), binding.getUserKey());
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    /** 绑定或迁移一个用户键。所有状态检查与修改在同一把锁内完成。 */
    public synchronized Result adopt(String channel, String userKey, Identity identity,
                                     String workspaceId, String sessionId, boolean force) {
        return adopt(channel, userKey, identity, workspaceId, sessionId, force, null);
    }

    /**
     * 绑定或迁移一个用户键，并附带通道私密凭据（如飞书 appSecret）。
     *
     * <p>凭据不参与冲突判定（冲突只看 identity），只随绑定一起持久化。</p>
     */
    public synchronized Result adopt(String channel, String userKey, Identity identity,
                                     String workspaceId, String sessionId, boolean force,
                                     String secret) {
        String key = compositeKey(channel, identityKey(identity), userKey);
        Binding current = bindings.get(key);
        // 归属约束：同一 bot（identity）只能有一个对话，但同对话可以有多个 bot
        Binding owner = findOwner(channel, identity, userKey);

        if (!force && current != null && !sameIdentity(current.getIdentity(), identity)) {
            return Result.conflict(current);
        }
        if (!force && owner != null) {
            return Result.conflict(owner);
        }

        Binding previous = current != null ? current : owner;
        if (current != null) {
            bindings.remove(key);
        }
        if (owner != null) {
            bindings.remove(keyOf(owner));
        }

        Binding adopted = new Binding(channel, userKey, identity, workspaceId, sessionId,
                System.currentTimeMillis(), secret, "");
        bindings.put(key, adopted);
        return Result.accepted(previous);
    }

    /** 用给定快照整体替换登记表（用于从存储加载）。 */
    public synchronized void replaceAll(Map<String, Binding> loaded) {
        bindings.clear();
        if (loaded != null) {
            bindings.putAll(loaded);
        }
    }

    /** 按 channel+workspaceId 列出归属该工作区的绑定。 */
    public synchronized java.util.List<Binding> listByWorkspace(String channel, String workspaceId) {
        java.util.List<Binding> out = new java.util.ArrayList<>();
        for (Binding binding : bindings.values()) {
            if (Objects.equals(channel, binding.getChannel())
                    && Objects.equals(workspaceId, binding.getWorkspaceId())) {
                out.add(binding);
            }
        }
        return out;
    }

    /** 列出无归属（workspaceId 为空）的绑定，供历史数据认领。 */
    public synchronized java.util.List<Binding> listUnclaimed(String channel) {
        java.util.List<Binding> out = new java.util.ArrayList<>();
        for (Binding binding : bindings.values()) {
            if (Objects.equals(channel, binding.getChannel())
                    && binding.getWorkspaceId() == null) {
                out.add(binding);
            }
        }
        return out;
    }

    /** 历史数据认领：把无归属绑定归入指定工作区，返回认领后的绑定（无可认领时返回原值或 null）。 */
    public synchronized Binding claim(String channel, Identity identity, String userKey, String workspaceId) {
        String key = compositeKey(channel, identityKey(identity), userKey);
        Binding binding = bindings.get(key);
        if (binding != null && binding.getWorkspaceId() == null) {
            Binding claimed = new Binding(binding.getChannel(), binding.getUserKey(),
                    binding.getIdentity(), workspaceId, binding.getSessionId(),
                    binding.getUpdatedAt(), binding.getSecret(), binding.getLastMessageId());
            bindings.put(key, claimed);
            return claimed;
        }
        return binding;
    }

    /** 按 channel+workspaceId+sessionId 查找绑定（会话维度，用于状态/回复路由）。 */
    public synchronized Binding findBySession(String channel, String workspaceId, String sessionId) {
        for (Binding binding : bindings.values()) {
            if (Objects.equals(channel, binding.getChannel())
                    && Objects.equals(workspaceId, binding.getWorkspaceId())
                    && Objects.equals(sessionId, binding.getSessionId())) {
                return binding;
            }
        }
        return null;
    }

    /** 按 channel+identity+workspaceId+sessionId 查找绑定（指定 bot 的会话绑定）。 */
    public synchronized Binding findBySession(String channel, String identityKey,
                                              String workspaceId, String sessionId) {
        for (Binding binding : bindings.values()) {
            if (Objects.equals(channel, binding.getChannel())
                    && Objects.equals(normalize(identityKey), identityKey(binding.getIdentity()))
                    && Objects.equals(workspaceId, binding.getWorkspaceId())
                    && Objects.equals(sessionId, binding.getSessionId())) {
                return binding;
            }
        }
        return null;
    }

    /** 按通道身份（如 appId）查找绑定归属，用于跨工作区冲突预检。 */
    public synchronized Binding findByAppId(String channel, String appId) {
        if (appId == null) {
            return null;
        }
        for (Binding binding : bindings.values()) {
            if (Objects.equals(channel, binding.getChannel())
                    && binding.getIdentity() != null
                    && Objects.equals(appId, binding.getIdentity().getAppId())) {
                return binding;
            }
        }
        return null;
    }

    /** 按通道身份（如钉钉 appKey）查找绑定归属，用于跨工作区冲突预检。 */
    public synchronized Binding findByAppKey(String channel, String appKey) {
        if (appKey == null) {
            return null;
        }
        for (Binding binding : bindings.values()) {
            if (Objects.equals(channel, binding.getChannel())
                    && binding.getIdentity() != null
                    && Objects.equals(appKey, binding.getIdentity().getAppKey())) {
                return binding;
            }
        }
        return null;
    }

    /** 按 channel+workspaceId+sessionId 移除，返回被移除的绑定。 */
    public synchronized Binding removeBySession(String channel, String workspaceId, String sessionId) {
        Binding binding = findBySession(channel, workspaceId, sessionId);
        if (binding != null) {
            bindings.remove(keyOf(binding));
        }
        return binding;
    }

    /** 按 channel+identity+userKey 移除，返回被移除的绑定（不校验归属）。 */
    public synchronized Binding removeByUserKey(String channel, Identity identity, String userKey) {
        return bindings.remove(compositeKey(channel, identityKey(identity), userKey));
    }

    /** 更新去重缓存（最后处理的消息 ID）。 */
    public synchronized void updateLastMessageId(String channel, Identity identity,
                                                 String userKey, String messageId) {
        Binding binding = bindings.get(compositeKey(channel, identityKey(identity), userKey));
        if (binding != null) {
            binding.lastMessageId = messageId;
        }
    }

    /** 按 workspace/session 条件解绑，避免旧请求误删后来建立的绑定。 */
    public synchronized boolean remove(String channel, Identity identity, String userKey,
                                       String workspaceId, String sessionId) {
        String key = compositeKey(channel, identityKey(identity), userKey);
        Binding current = bindings.get(key);
        if (current == null || !Objects.equals(workspaceId, current.getWorkspaceId())
                || !Objects.equals(sessionId, current.getSessionId())) {
            return false;
        }
        bindings.remove(key);
        return true;
    }

    /** 精确查找一条绑定（按 channel + bot 身份 + userKey）。 */
    public synchronized Binding find(String channel, Identity identity, String userKey) {
        return bindings.get(compositeKey(channel, identityKey(identity), userKey));
    }

    /**
     * 按 channel + userKey 扫描查找（不区分 bot 身份），返回首个匹配。
     *
     * <p>仅用于调用方拿不到 identity 的场景；能拿到 identity 时应优先用
     * {@link #find(String, Identity, String)}，避免多 bot 并存时命中错误的那条。</p>
     */
    public synchronized Binding findAny(String channel, String userKey) {
        for (Binding binding : bindings.values()) {
            if (Objects.equals(channel, binding.getChannel())
                    && Objects.equals(userKey, binding.getUserKey())) {
                return binding;
            }
        }
        return null;
    }

    /** 返回以 composite key 为键的不可变登记表快照。 */
    public synchronized Map<String, Binding> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /** 查询指定会话在当前工作区绑定状态；不存在同 session 的异地绑定时返回 unbound。 */
    public synchronized SessionStatus statusForSession(String channel, String workspaceId,
                                                        String sessionId) {
        Binding local = null;
        Binding elsewhere = null;
        for (Binding binding : bindings.values()) {
            if (!Objects.equals(channel, binding.getChannel())
                    || !Objects.equals(sessionId, binding.getSessionId())) {
                continue;
            }
            if (Objects.equals(workspaceId, binding.getWorkspaceId())) {
                local = binding;
                break;
            }
            if (elsewhere == null) {
                elsewhere = binding;
            }
        }
        if (local != null) {
            return new SessionStatus(true, false, local.getWorkspaceId(), local.getSessionId());
        }
        if (elsewhere != null) {
            return new SessionStatus(false, true, elsewhere.getWorkspaceId(), elsewhere.getSessionId());
        }
        return new SessionStatus(false, false, null, null);
    }

    /**
     * 查找「同一 bot 已被占用的其它 userKey」——即归属约束的执行点。
     *
     * <p>只比较 identity，不看 userKey：同一 identity 已被别的用户占着就返回它。
     * 不同 identity 之间互相看不见，这正是多 bot 并存的实现方式。</p>
     */
    private Binding findOwner(String channel, Identity identity, String exceptUserKey) {
        for (Binding binding : bindings.values()) {
            if (!Objects.equals(channel, binding.getChannel())
                    || Objects.equals(exceptUserKey, binding.getUserKey())) {
                continue;
            }
            if (sameIdentity(binding.getIdentity(), identity)) {
                return binding;
            }
        }
        return null;
    }

    private static boolean sameIdentity(Identity left, Identity right) {
        return Objects.equals(left, right);
    }

    public static final class Identity {
        private final String appId;
        private final String appKey;
        private final String botToken;

        public Identity(String appId, String appKey, String botToken) {
            this.appId = appId;
            this.appKey = appKey;
            this.botToken = botToken;
        }

        public String getAppId() { return appId; }
        public String getAppKey() { return appKey; }
        public String getBotToken() { return botToken; }

        /** 身份的规范键：appId / appKey / botToken 中首个非空者，全空时为空串。 */
        public String key() {
            if (appId != null && !appId.isEmpty()) return appId;
            if (appKey != null && !appKey.isEmpty()) return appKey;
            if (botToken != null && !botToken.isEmpty()) return botToken;
            return "";
        }

        @Override
        public String toString() {
            return "Identity(" + key() + ")";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Identity)) return false;
            Identity other = (Identity) obj;
            return Objects.equals(appId, other.appId)
                    && Objects.equals(appKey, other.appKey)
                    && Objects.equals(botToken, other.botToken);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appId, appKey, botToken);
        }
    }

    public static final class Binding {
        private final String channel;
        private final String userKey;
        private final Identity identity;
        private final String workspaceId;
        private final String sessionId;
        private final long updatedAt;
        /** 通道私密凭据（飞书 appSecret 等）；不参与冲突判定。 */
        private final String secret;
        /** 去重缓存：最后处理的消息 ID（运行时可变）。 */
        private volatile String lastMessageId;

        public Binding(String channel, String userKey, Identity identity,
                       String workspaceId, String sessionId, long updatedAt) {
            this(channel, userKey, identity, workspaceId, sessionId, updatedAt, null, null);
        }

        public Binding(String channel, String userKey, Identity identity,
                       String workspaceId, String sessionId, long updatedAt,
                       String secret, String lastMessageId) {
            this.channel = channel;
            this.userKey = userKey;
            this.identity = identity;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.updatedAt = updatedAt;
            this.secret = secret;
            this.lastMessageId = lastMessageId;
        }

        public String getChannel() { return channel; }
        public String getUserKey() { return userKey; }
        public Identity getIdentity() { return identity; }
        public String getWorkspaceId() { return workspaceId; }
        public String getSessionId() { return sessionId; }
        public long getUpdatedAt() { return updatedAt; }
        public String getSecret() { return secret; }
        public String getLastMessageId() { return lastMessageId; }
    }

    public static final class Result {
        private final boolean accepted;
        private final Binding conflict;
        private final Binding previous;

        private Result(boolean accepted, Binding conflict, Binding previous) {
            this.accepted = accepted;
            this.conflict = conflict;
            this.previous = previous;
        }

        private static Result accepted(Binding previous) {
            return new Result(true, null, previous);
        }

        private static Result conflict(Binding conflict) {
            return new Result(false, conflict, null);
        }

        public boolean isAccepted() { return accepted; }
        public Binding getConflict() { return conflict; }
        public Binding getPrevious() { return previous; }
    }

    public static final class SessionStatus {
        private final boolean bound;
        private final boolean boundElsewhere;
        private final String workspaceId;
        private final String sessionId;

        private SessionStatus(boolean bound, boolean boundElsewhere,
                              String workspaceId, String sessionId) {
            this.bound = bound;
            this.boundElsewhere = boundElsewhere;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
        }

        public boolean isBound() { return bound; }
        public boolean isBoundElsewhere() { return boundElsewhere; }
        public String getWorkspaceId() { return workspaceId; }
        public String getSessionId() { return sessionId; }
    }
}
