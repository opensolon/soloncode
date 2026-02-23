package org.noear.solon.ai.codecli.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证管理器
 * 提供基础的用户认证和权限控制
 */
public class AuthManager {
    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);
    
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> permissions = new ConcurrentHashMap<>();
    
    /**
     * 创建用户会话
     */
    public UserSession createSession(String userId, Map<String, Object> metadata) {
        UserSession session = new UserSession(userId, metadata);
        sessions.put(userId, session);
        log.info("Created session for user: {}", userId);
        return session;
    }
    
    /**
     * 获取用户会话
     */
    public UserSession getSession(String userId) {
        return sessions.get(userId);
    }
    
    /**
     * 验证用户权限
     */
    public boolean hasPermission(String userId, String permission) {
        Set<String> userPerms = permissions.get(userId);
        return userPerms != null && userPerms.contains(permission);
    }
    
    /**
     * 添加用户权限
     */
    public void addPermission(String userId, String permission) {
        permissions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(permission);
        log.info("Added permission '{}' to user: {}", permission, userId);
    }
    
    /**
     * 移除用户权限
     */
    public void removePermission(String userId, String permission) {
        Set<String> userPerms = permissions.get(userId);
        if (userPerms != null) {
            userPerms.remove(permission);
            if (userPerms.isEmpty()) {
                permissions.remove(userId);
            }
        }
        log.info("Removed permission '{}' from user: {}", permission, userId);
    }
    
    /**
     * 清理过期会话
     */
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().getLastActive() > 30 * 60 * 1000; // 30分钟
            if (expired) {
                log.info("Cleaned up expired session for user: {}", entry.getKey());
                permissions.remove(entry.getKey());
            }
            return expired;
        });
    }
    
    /**
     * 用户会话
     */
    public static class UserSession {
        private final String userId;
        private final Map<String, Object> metadata;
        private final long createdAt;
        private volatile long lastActive;
        
        public UserSession(String userId, Map<String, Object> metadata) {
            this.userId = userId;
            this.metadata = new HashMap<>(metadata != null ? metadata : new HashMap<>());
            this.createdAt = System.currentTimeMillis();
            this.lastActive = this.createdAt;
        }
        
        public void updateActivity() {
            this.lastActive = System.currentTimeMillis();
        }
        
        // Getters
        public String getUserId() { return userId; }
        public Map<String, Object> getMetadata() { return metadata; }
        public long getCreatedAt() { return createdAt; }
        public long getLastActive() { return lastActive; }
    }
    
    /**
     * 权限常量
     */
    public static class Permissions {
        public static final String READ_MESSAGES = "read_messages";
        public static final String SEND_MESSAGES = "send_messages";
        public static final String EXECUTE_COMMANDS = "execute_commands";
        public static final String APPROVE_ACTIONS = "approve_actions";
        public static final String ADMIN = "admin";
    }
}