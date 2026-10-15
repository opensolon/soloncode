package org.noear.solon.codecli.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 用户登录会话保存于用户级共享文件，供同一主机不同端口的实例共同使用。 */
public class UserSessionManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    // 认证每次仍从共享文件校验；仅限制滑动过期时间的持久化频率。
    private static final long SESSION_TOUCH_INTERVAL_MILLIS = 30_000L;
    private final Path userHome;
    private UserAuthConfig config;
    // 保留可测试/兼容的存储路径入口；默认指向用户级共享会话文件。
    private Path sessionsFilePath;
    private FileSessionStore store;
    // 兼容当前 JVM 中 UserSession 的可变对象语义；跨进程权威状态仍是文件。
    private final ConcurrentMap<String, UserSession> sessionCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheSnapshot> cacheSnapshots = new ConcurrentHashMap<>();

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
        this.sessionsFilePath = userHome.resolve(".soloncode").resolve("auth").resolve("sessions.json")
                .toAbsolutePath().normalize();
        this.store = new FileSessionStore(sessionsFilePath, true);
        store.init();
        migrateLegacySessionFile();
    }

    private void migrateLegacySessionFile() {
        Path legacy = userHome.resolve(".soloncode").resolve("user-sessions.json");
        Path backup = legacy.resolveSibling("user-sessions.json.legacy");
        try {
            if (Files.exists(legacy) && !Files.exists(backup)) {
                try {
                    Files.move(legacy, backup, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(legacy, backup);
                } catch (java.nio.file.NoSuchFileException e) {
                    // 另一个实例已完成迁移。
                    if (!Files.exists(backup)) throw e;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法隔离旧版会话文件", e);
        }
    }

    public UserSession createSession(UserEntity user) {
        ensureInitialized();
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
        session.setExpiresAt(expiryAt(session.getCreatedAt()));
        final FileSessionStore.Record record = toRecord(session);
        store.update(records -> {
            removeExpired(records, System.currentTimeMillis());
            records.add(record);
            return null;
        });
        sessionCache.put(record.tokenHash, session);
        remember(record.tokenHash, session);
        return session;
    }

    public UserSession getSession(String token) {
        if (token == null || token.isEmpty()) return null;
        ensureInitialized();
        final String hash = sessionId(token);
        final long now = System.currentTimeMillis();
        return store.access(records -> {
            UserSession session = null;
            boolean changed = false;
            for (int i = records.size() - 1; i >= 0; i--) {
                FileSessionStore.Record record = records.get(i);
                if (!hash.equals(record.tokenHash)) {
                    if (isExpired(record, now)) {
                        records.remove(i);
                        changed = true;
                    }
                    continue;
                }
                changed |= reconcileCached(record);
                if (isExpired(record, now)) {
                    records.remove(i);
                    removeCached(record);
                    changed = true;
                } else {
                    UserSession cached = sessionCache.get(hash);
                    if (cached == null) cached = fromRecord(record, token);
                    cached.setLastAccessedAt(now);
                    cached.setExpiresAt(expiryAt(now));
                    sessionCache.put(hash, cached);
                    session = cached;
                    if (now - record.lastAccessedAt >= SESSION_TOUCH_INTERVAL_MILLIS) {
                        copyToRecord(cached, record);
                        changed = true;
                    }
                    remember(hash, cached);
                }
            }
            return FileSessionStore.AccessResult.of(session, changed);
        });
    }

    public List<SessionSummary> listActiveSessions() {
        ensureInitialized();
        final long now = System.currentTimeMillis();
        return store.access(records -> {
            List<SessionSummary> result = new ArrayList<>();
            boolean changed = false;
            for (int i = records.size() - 1; i >= 0; i--) {
                FileSessionStore.Record record = records.get(i);
                UserSession cached = findCached(record);
                changed |= reconcileCached(record);
                if (isExpired(record, now)) {
                    records.remove(i);
                    removeCached(record);
                    changed = true;
                } else {
                    UserSession summarySession = cached != null ? cached : fromRecord(record, null);
                    result.add(new SessionSummary(record.tokenHash, summarySession));
                }
            }
            return FileSessionStore.AccessResult.of(result, changed);
        });
    }

    /** 仅按管理 ID 撤销，绝不将管理 ID 当成登录令牌使用。 */
    public boolean revokeSessionById(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) return false;
        ensureInitialized();
        final long now = System.currentTimeMillis();
        return store.update(records -> {
            boolean removed = false;
            for (int i = records.size() - 1; i >= 0; i--) {
                FileSessionStore.Record record = records.get(i);
                UserSession cached = findCached(record);
                if (isExpired(record, now)) records.remove(i);
                else if (id.equals(record.tokenHash)) {
                    records.remove(i);
                    removeCached(record);
                    removed = true;
                }
            }
            return removed;
        });
    }

    public void destroySession(String token) {
        if (token == null || token.isEmpty()) return;
        ensureInitialized();
        final String hash = sessionId(token);
        store.update(records -> {
            records.removeIf(record -> {
                boolean match = hash.equals(record.tokenHash);
                if (match) removeCached(record);
                return match;
            });
            return null;
        });
    }

    public int revokeUserSessions(String userId) {
        if (userId == null) return 0;
        ensureInitialized();
        return store.update(records -> {
            int before = records.size();
            records.removeIf(record -> {
                boolean match = userId.equals(record.userId);
                if (match) removeCached(record);
                return match;
            });
            return before - records.size();
        });
    }

    public int revokeAllSessions() {
        ensureInitialized();
        return store.update(records -> {
            int count = records.size();
            records.forEach(this::removeCached);
            records.clear();
            return count;
        });
    }

    public List<UserSession> getUserSessions(String userId) {
        if (userId == null) return Collections.emptyList();
        ensureInitialized();
        final long now = System.currentTimeMillis();
        return store.access(records -> {
            List<UserSession> result = new ArrayList<>();
            boolean changed = false;
            for (int i = records.size() - 1; i >= 0; i--) {
                FileSessionStore.Record record = records.get(i);
                UserSession cached = findCached(record);
                changed |= reconcileCached(record);
                if (isExpired(record, now)) {
                    records.remove(i);
                    removeCached(record);
                    changed = true;
                } else if (userId.equals(record.userId)) {
                    UserSession resultSession = cached != null ? cached : fromRecord(record, null);
                    result.add(resultSession);
                }
            }
            return FileSessionStore.AccessResult.of(result, changed);
        });
    }

    public int getActiveSessionCount() {
        ensureInitialized();
        final long now = System.currentTimeMillis();
        return store.access(records -> {
            int before = records.size();
            removeExpired(records, now);
            return FileSessionStore.AccessResult.of(records.size(), records.size() != before);
        });
    }

    private void ensureInitialized() {
        if (store == null) {
            if (sessionsFilePath == null) {
                init(config);
            } else {
                this.store = new FileSessionStore(sessionsFilePath, true);
                store.init();
            }
        }
    }

    private UserSession fromRecord(FileSessionStore.Record record, String token) {
        UserSession session = new UserSession();
        session.setToken(token);
        session.setUserId(record.userId);
        session.setUsername(record.username);
        session.setDisplayName(record.displayName);
        session.setRole(record.role);
        session.setCreatedAt(record.createdAt);
        session.setLastAccessedAt(record.lastAccessedAt);
        session.setExpiresAt(record.expiresAt);
        return session;
    }

    private FileSessionStore.Record toRecord(UserSession session) {
        FileSessionStore.Record record = new FileSessionStore.Record();
        record.tokenHash = sessionId(session.getToken());
        record.userId = session.getUserId();
        record.username = session.getUsername();
        record.displayName = session.getDisplayName();
        record.role = session.getRole();
        record.createdAt = session.getCreatedAt();
        record.lastAccessedAt = session.getLastAccessedAt();
        record.expiresAt = session.getExpiresAt();
        return record;
    }

    private void removeExpired(List<FileSessionStore.Record> records, long now) {
        records.removeIf(record -> {
            boolean expired = isExpired(record, now);
            if (expired) removeCached(record);
            return expired;
        });
    }

    private boolean isExpired(FileSessionStore.Record record, long now) {
        int timeoutMinutes = config == null ? 60 : config.getSessionTimeoutMinutes();
        return record.expiresAt == 0 || (timeoutMinutes > 0
                && now - record.lastAccessedAt > timeoutMinutes * 60 * 1000L);
    }

    private UserSession findCached(FileSessionStore.Record record) {
        return sessionCache.get(record.tokenHash);
    }

    private void removeCached(FileSessionStore.Record record) {
        sessionCache.remove(record.tokenHash);
        cacheSnapshots.remove(record.tokenHash);
    }

    /** 将当前 JVM 中调用方修改过的兼容对象合并回文件；未修改时以共享文件为准。 */
    private boolean reconcileCached(FileSessionStore.Record record) {
        UserSession cached = findCached(record);
        CacheSnapshot snapshot = cacheSnapshots.get(record.tokenHash);
        if (cached == null || snapshot == null || snapshot.matches(cached)) return false;
        copyToRecord(cached, record);
        return true;
    }

    private void remember(String hash, UserSession session) {
        cacheSnapshots.put(hash, new CacheSnapshot(session));
    }

    private static final class CacheSnapshot {
        private final long lastAccessedAt;
        private final long expiresAt;

        private CacheSnapshot(UserSession session) {
            this.lastAccessedAt = session.getLastAccessedAt();
            this.expiresAt = session.getExpiresAt();
        }

        private boolean matches(UserSession session) {
            return lastAccessedAt == session.getLastAccessedAt() && expiresAt == session.getExpiresAt();
        }
    }

    private void copyToRecord(UserSession session, FileSessionStore.Record record) {
        record.lastAccessedAt = session.getLastAccessedAt();
        record.expiresAt = session.getExpiresAt();
    }

    private long expiryAt(long from) {
        int timeoutMinutes = config == null ? 60 : config.getSessionTimeoutMinutes();
        if (timeoutMinutes <= 0) return Long.MAX_VALUE;
        return from + timeoutMinutes * 60 * 1000L;
    }

    /** 完整 SHA-256 摘要作为管理用 ID，同时作为文件中的 Token 标识。 */
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
}
