package org.noear.solon.codecli.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 用户登录会话仅保存在进程内；重启后所有令牌自动失效。 */
public class UserSessionManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ConcurrentMap<String, UserSession> sessionMap = new ConcurrentHashMap<>();
    private final Path userHome;
    // 保留字段以兼容原有测试/调用；不再存储原始 token。
    private Path sessionsFilePath;
    private UserAuthConfig config;

    public UserSessionManager() {
        this(Paths.get(System.getProperty("user.home")));
    }

    public UserSessionManager(Path userHome) {
        if (userHome == null) throw new IllegalArgumentException("用户目录不能为空");
        this.userHome = userHome.toAbsolutePath().normalize();
    }

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
        public boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /** 对外只提供不可变会话摘要，不暴露原始令牌及可变属性。 */
    public static final class SessionSummary {
        private final String id;
        private final String userId;
        private final String username;
        private final String displayName;
        private final String role;
        private final long createdAt;
        private final long lastAccessedAt;
        private final long expiresAt;

        private SessionSummary(String id, UserSession session) {
            this.id = id;
            this.userId = session.getUserId();
            this.username = session.getUsername();
            this.displayName = session.getDisplayName();
            this.role = session.getRole();
            this.createdAt = session.getCreatedAt();
            this.lastAccessedAt = session.getLastAccessedAt();
            this.expiresAt = session.getExpiresAt();
        }

        public String getId() { return id; }
        public String getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getDisplayName() { return displayName; }
        public String getRole() { return role; }
        public long getCreatedAt() { return createdAt; }
        public long getLastAccessedAt() { return lastAccessedAt; }
        public long getExpiresAt() { return expiresAt; }
    }

    public void init(UserAuthConfig config) {
        this.config = config;
        sessionMap.clear();
        sessionsFilePath = userHome.resolve(".soloncode").resolve("user-sessions.json").toAbsolutePath();
        // 旧版本曾以明文持久化令牌；绝不重新载入，且尽力清理遗留文件。
        try {
            Files.deleteIfExists(sessionsFilePath);
        } catch (Exception e) {
            throw new IllegalStateException("无法清理旧会话令牌文件", e);
        }
    }

    public UserSession createSession(UserEntity user) {
        byte[] bytes = new byte[config == null ? 32 : Math.max(32, config.getSessionTokenLength())];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) token.append(String.format("%02x", b));
        UserSession session = new UserSession();
        session.setToken(token.toString());
        session.setUserId(user.getId());
        session.setUsername(user.getUsername());
        session.setDisplayName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        session.setRole(user.getRole() != null ? user.getRole() : "user");
        session.setCreatedAt(System.currentTimeMillis());
        session.setLastAccessedAt(session.getCreatedAt());
        session.setExpiresAt(session.getCreatedAt() + (config.getSessionTimeoutMinutes() * 60 * 1000L));
        sessionMap.put(session.getToken(), session);
        return session;
    }

    public UserSession getSession(String token) {
        if (token == null) return null;
        UserSession session = sessionMap.get(token);
        if (session == null) return null;
        if (session.isExpired()) {
            sessionMap.remove(token, session);
            return null;
        }
        session.setLastAccessedAt(System.currentTimeMillis());
        return session;
    }

    /** 完整 SHA-256 摘要作为管理用 ID，不能凭此 ID 登录。 */
    private static String sessionId(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = alphabet[(digest[i] & 0xff) >>> 4];
                hex[i * 2 + 1] = alphabet[digest[i] & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public List<SessionSummary> listActiveSessions() {
        List<SessionSummary> result = new ArrayList<>();
        for (Map.Entry<String, UserSession> entry : sessionMap.entrySet()) {
            UserSession session = entry.getValue();
            if (session.isExpired()) {
                sessionMap.remove(entry.getKey(), session);
            } else {
                result.add(new SessionSummary(sessionId(entry.getKey()), session));
            }
        }
        return result;
    }

    /** 仅按管理 ID 撤销，绝不将管理 ID 当成登录令牌使用。 */
    public boolean revokeSessionById(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) return false;
        for (Map.Entry<String, UserSession> entry : sessionMap.entrySet()) {
            if (id.equals(sessionId(entry.getKey()))) {
                UserSession session = entry.getValue();
                if (session.isExpired()) {
                    sessionMap.remove(entry.getKey(), session);
                    return false;
                }
                return sessionMap.remove(entry.getKey(), session);
            }
        }
        return false;
    }

    public void destroySession(String token) {
        if (token != null) sessionMap.remove(token);
    }

    public int revokeUserSessions(String userId) {
        if (userId == null) return 0;
        int removed = 0;
        for (Map.Entry<String, UserSession> entry : sessionMap.entrySet()) {
            if (userId.equals(entry.getValue().getUserId()) && sessionMap.remove(entry.getKey(), entry.getValue())) removed++;
        }
        return removed;
    }

    public int revokeAllSessions() {
        int count = sessionMap.size();
        sessionMap.clear();
        return count;
    }

    public List<UserSession> getUserSessions(String userId) {
        List<UserSession> list = new ArrayList<>();
        if (userId == null) return list;
        for (UserSession session : sessionMap.values()) {
            if (userId.equals(session.getUserId()) && !session.isExpired()) list.add(session);
        }
        return list;
    }

    public int getActiveSessionCount() {
        int count = 0;
        for (UserSession session : sessionMap.values()) if (!session.isExpired()) count++;
        return count;
    }
}
