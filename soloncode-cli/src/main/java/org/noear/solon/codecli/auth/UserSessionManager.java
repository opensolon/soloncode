package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.AgentFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户会话管理器 - 管理用户登录会话，实现会话隔离
 * 
 * 每个用户拥有独立的 token，用于在 HTTP 请求中标识用户身份。
 * 会话数据存储在 ~/.soloncode/user-sessions.json
 * 
 * @author noear 2026/8/23 created
 */
public class UserSessionManager {
    private static final Logger LOG = LoggerFactory.getLogger(UserSessionManager.class);
    private static final String SESSIONS_FILE = "user-sessions.json";
    private static final SecureRandom RANDOM = new SecureRandom();
    
    private final ConcurrentMap<String, UserSession> sessionMap = new ConcurrentHashMap<>();
    private Path sessionsFilePath;
    private UserAuthConfig config;
    
    public static class UserSession {
        private String token;
        private String userId;
        private String username;
        private String displayName;
        private String role;
        private long createdAt;
        private long lastAccessedAt;
        private long expiresAt;
        private Map<String, Object> attributes = new HashMap<>();
        
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public long getLastAccessedAt() { return lastAccessedAt; }
        public void setLastAccessedAt(long lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
        public Map<String, Object> getAttributes() { return attributes; }
        public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    public void init(UserAuthConfig config) {
        this.config = config;
        this.sessionsFilePath = Paths.get(AgentFlags.getUserHome(), ".soloncode", SESSIONS_FILE).toAbsolutePath();
        
        if (Files.exists(sessionsFilePath)) {
            loadFromFile();
        }
    }
    
    /**
     * 为用户创建会话
     */
    public UserSession createSession(UserEntity user) {
        String token = generateToken();
        UserSession session = new UserSession();
        session.setToken(token);
        session.setUserId(user.getId());
        session.setUsername(user.getUsername());
        session.setDisplayName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        session.setRole(user.getRole() != null ? user.getRole() : "user");
        session.setCreatedAt(System.currentTimeMillis());
        session.setLastAccessedAt(System.currentTimeMillis());
        session.setExpiresAt(System.currentTimeMillis() + (config.getSessionTimeoutMinutes() * 60 * 1000L));
        
        sessionMap.put(token, session);
        saveToFile();
        return session;
    }
    
    /**
     * 通过 token 获取会话
     */
    public UserSession getSession(String token) {
        if (token == null) return null;
        UserSession session = sessionMap.get(token);
        if (session == null) return null;
        if (session.isExpired()) {
            sessionMap.remove(token);
            saveToFile();
            return null;
        }
        session.setLastAccessedAt(System.currentTimeMillis());
        return session;
    }
    
    /**
     * 销毁会话（登出）
     */
    public void destroySession(String token) {
        if (token != null) {
            sessionMap.remove(token);
            saveToFile();
        }
    }
    
    /**
     * 获取用户的所有活跃会话
     */
    public List<UserSession> getUserSessions(String userId) {
        List<UserSession> list = new ArrayList<>();
        for (UserSession session : sessionMap.values()) {
            if (userId.equals(session.getUserId()) && !session.isExpired()) {
                list.add(session);
            }
        }
        return list;
    }
    
    private String generateToken() {
        byte[] bytes = new byte[config != null ? config.getSessionTokenLength() : 32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    private void loadFromFile() {
        try {
            String json = new String(Files.readAllBytes(sessionsFilePath), "UTF-8");
            ONode root = ONode.ofJson(json);
            if (root.isArray()) {
                for (ONode item : root.getArray()) {
                    UserSession session = new UserSession();
                    session.setToken(item.get("token").getString());
                    session.setUserId(item.get("userId").getString());
                    session.setUsername(item.get("username").getString());
                    session.setDisplayName(item.get("displayName").getString());
                    session.setRole(item.get("role").getString());
                    session.setCreatedAt(item.get("createdAt").getLong());
                    session.setLastAccessedAt(item.get("lastAccessedAt").getLong());
                    session.setExpiresAt(item.get("expiresAt").getLong());
                    
                    if (!session.isExpired()) {
                        sessionMap.put(session.getToken(), session);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[UserSession] Failed to load sessions file: {}", e.getMessage());
        }
    }
    
    private synchronized void saveToFile() {
        try {
            // 清理过期会话
            sessionMap.values().removeIf(UserSession::isExpired);
            
            ONode arr = new ONode().asArray();
            for (UserSession session : sessionMap.values()) {
                ONode item = new ONode().asObject();
                item.set("token", session.getToken());
                item.set("userId", session.getUserId());
                item.set("username", session.getUsername());
                item.set("displayName", session.getDisplayName());
                item.set("role", session.getRole());
                item.set("createdAt", session.getCreatedAt());
                item.set("lastAccessedAt", session.getLastAccessedAt());
                item.set("expiresAt", session.getExpiresAt());
                arr.add(item);
            }
            
            Files.createDirectories(sessionsFilePath.getParent());
            Path tmp = sessionsFilePath.resolveSibling(sessionsFilePath.getFileName() + ".tmp");
            String json = arr.toJson();
            Files.write(tmp, json.getBytes("UTF-8"));
            Files.move(tmp, sessionsFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warn("[UserSession] Failed to save sessions file: {}", e.getMessage());
        }
    }
}
