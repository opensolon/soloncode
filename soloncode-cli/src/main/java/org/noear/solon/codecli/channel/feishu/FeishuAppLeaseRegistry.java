/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.noear.solon.codecli.channel.feishu;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 进程级飞书应用连接租约登记表。
 *
 * <p>本类只管理 appId 与工作区/会话的归属元数据，不创建连接、不执行网络操作。</p>
 */
public class FeishuAppLeaseRegistry {
    private final Map<String, Lease> leases = new LinkedHashMap<>();

    /** 原子获取或迁移一个 appId 的连接租约。 */
    public synchronized Result acquire(String appId, String appSecret, String workspaceId,
                                       String sessionId, boolean force) {
        Lease current = leases.get(appId);
        if (current != null) {
            boolean sameOwner = Objects.equals(workspaceId, current.getWorkspaceId())
                    && Objects.equals(sessionId, current.getSessionId());
            if (sameOwner) {
                return Result.acquired(current, null);
            }
            if (!force) {
                return Result.conflict(current);
            }
        }

        Lease previous = current;
        Lease acquired = new Lease(appId, appSecret, workspaceId, sessionId,
                System.currentTimeMillis());
        leases.put(appId, acquired);
        return Result.acquired(acquired, previous);
    }

    /** 仅当 workspaceId/sessionId 仍匹配时释放租约。 */
    public synchronized boolean release(String appId, String workspaceId, String sessionId) {
        Lease current = leases.get(appId);
        if (current == null || !Objects.equals(workspaceId, current.getWorkspaceId())
                || !Objects.equals(sessionId, current.getSessionId())) {
            return false;
        }
        leases.remove(appId);
        return true;
    }

    public synchronized Lease find(String appId) {
        return leases.get(appId);
    }

    /** 返回以 appId 为键的不可变快照。 */
    public synchronized Map<String, Lease> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(leases));
    }

    public static final class Lease {
        private final String appId;
        private final String workspaceId;
        private final String sessionId;
        private final String appSecret;
        private final long acquiredAt;

        public Lease(String appId, String appSecret, String workspaceId, String sessionId,
                     long acquiredAt) {
            this.appId = appId;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.appSecret = appSecret;
            this.acquiredAt = acquiredAt;
        }

        public String getAppId() { return appId; }
        public String getWorkspaceId() { return workspaceId; }
        public String getSessionId() { return sessionId; }
        public String getAppSecret() { return appSecret; }
        public long getAcquiredAt() { return acquiredAt; }
    }

    public static final class Result {
        private final boolean acquired;
        private final Lease conflict;
        private final Lease previous;
        private final Lease lease;

        private Result(boolean acquired, Lease conflict, Lease previous, Lease lease) {
            this.acquired = acquired;
            this.conflict = conflict;
            this.previous = previous;
            this.lease = lease;
        }

        private static Result acquired(Lease lease, Lease previous) {
            return new Result(true, null, previous, lease);
        }

        private static Result conflict(Lease conflict) {
            return new Result(false, conflict, null, null);
        }

        public boolean isAcquired() { return acquired; }
        public Lease getLease() { return lease; }
        public Lease getConflict() { return conflict; }
        public Lease getPrevious() { return previous; }
    }
}
