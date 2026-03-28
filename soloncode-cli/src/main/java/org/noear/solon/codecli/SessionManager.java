package org.noear.solon.codecli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话管理器 — 管理 ~/.soloncode/sessions/ 下的所有会话
 *
 * 每个会话一个子目录，包含:
 *   meta.json      — 元信息（id, title, cwd, createdAt, updatedAt, messageCount）
 *   *.messages.ndjson — 消息记录（由 FileAgentSession 管理）
 *
 * @author xieshuang
 * @since 2.0
 */
public class SessionManager {
    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path sessionsDir;

    public SessionManager() {
        this.sessionsDir = ConfigLoader.getGlobalConfigDir().resolve("sessions");
        ensureDir(sessionsDir);
    }

    /**
     * 创建新会话，返回 sessionId
     */
    public String createSession(String cwd) {
        String id = generateId();
        Path dir = sessionsDir.resolve(id);
        ensureDir(dir);

        SessionMeta meta = new SessionMeta();
        meta.id = id;
        meta.title = "";
        meta.cwd = cwd;
        meta.createdAt = Instant.now().toEpochMilli();
        meta.updatedAt = meta.createdAt;
        meta.messageCount = 0;

        writeMeta(dir, meta);
        LOG.info("Created session: {}", id);
        return id;
    }

    /**
     * 更新会话标题（通常在第一条用户消息后调用，取前30字作为标题）
     */
    public void updateTitle(String sessionId, String title) {
        Path dir = sessionsDir.resolve(sessionId);
        SessionMeta meta = readMeta(dir);
        if (meta != null) {
            meta.title = title.length() > 40 ? title.substring(0, 40) + "..." : title;
            meta.updatedAt = Instant.now().toEpochMilli();
            writeMeta(dir, meta);
        }
    }

    /**
     * 更新会话的消息计数和最后修改时间
     */
    public void touch(String sessionId) {
        Path dir = sessionsDir.resolve(sessionId);
        SessionMeta meta = readMeta(dir);
        if (meta != null) {
            meta.updatedAt = Instant.now().toEpochMilli();
            meta.messageCount++;
            writeMeta(dir, meta);
        }
    }

    /**
     * 列出所有会话，按最后更新时间倒序
     * 可选：只列出指定 cwd 的会话
     */
    public List<SessionMeta> listSessions(String filterCwd) {
        if (!Files.exists(sessionsDir)) return Collections.emptyList();

        try (Stream<Path> dirs = Files.list(sessionsDir)) {
            return dirs
                    .filter(Files::isDirectory)
                    .map(this::readMeta)
                    .filter(Objects::nonNull)
                    .filter(m -> filterCwd == null || filterCwd.equals(m.cwd))
                    .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.warn("Failed to list sessions", e);
            return Collections.emptyList();
        }
    }

    /**
     * 列出所有会话（不过滤）
     */
    public List<SessionMeta> listSessions() {
        return listSessions(null);
    }

    /**
     * 获取单个会话的 meta
     */
    public SessionMeta getSessionMeta(String sessionId) {
        return readMeta(sessionsDir.resolve(sessionId));
    }

    /**
     * 删除会话
     */
    public boolean deleteSession(String sessionId) {
        Path dir = sessionsDir.resolve(sessionId);
        if (!Files.exists(dir)) return false;
        try {
            deleteDir(dir);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to delete session: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 格式化时间戳为可读字符串
     */
    public static String formatTime(long epochMs) {
        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        LocalDateTime now = LocalDateTime.now();

        if (dt.toLocalDate().equals(now.toLocalDate())) {
            return "today " + dt.format(DateTimeFormatter.ofPattern("HH:mm"));
        } else if (dt.toLocalDate().equals(now.toLocalDate().minusDays(1))) {
            return "yesterday " + dt.format(DateTimeFormatter.ofPattern("HH:mm"));
        } else {
            return dt.format(FMT);
        }
    }

    // ── internal ──

    private String generateId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void ensureDir(Path dir) {
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create directory: " + dir, e);
        }
    }

    private void writeMeta(Path sessionDir, SessionMeta meta) {
        Path file = sessionDir.resolve("meta.json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
            w.write("{\n");
            w.write("  \"id\": \"" + esc(meta.id) + "\",\n");
            w.write("  \"title\": \"" + esc(meta.title) + "\",\n");
            w.write("  \"cwd\": \"" + esc(meta.cwd) + "\",\n");
            w.write("  \"createdAt\": " + meta.createdAt + ",\n");
            w.write("  \"updatedAt\": " + meta.updatedAt + ",\n");
            w.write("  \"messageCount\": " + meta.messageCount + "\n");
            w.write("}\n");
        } catch (IOException e) {
            LOG.warn("Failed to write meta: {}", file, e);
        }
    }

    private SessionMeta readMeta(Path sessionDir) {
        Path file = sessionDir.resolve("meta.json");
        if (!Files.exists(file)) return null;
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            SessionMeta m = new SessionMeta();
            m.id = extractString(json, "id");
            m.title = extractString(json, "title");
            m.cwd = extractString(json, "cwd");
            m.createdAt = extractLong(json, "createdAt");
            m.updatedAt = extractLong(json, "updatedAt");
            m.messageCount = (int) extractLong(json, "messageCount");
            return m;
        } catch (Exception e) {
            LOG.warn("Failed to read meta: {}", file, e);
            return null;
        }
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return "";
        int quote1 = json.indexOf('"', colon + 1);
        if (quote1 < 0) return "";
        int quote2 = json.indexOf('"', quote1 + 1);
        if (quote2 < 0) return "";
        return json.substring(quote1 + 1, quote2).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long extractLong(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return 0;
        StringBuilder num = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9') num.append(c);
            else if (num.length() > 0) break;
        }
        return num.length() > 0 ? Long.parseLong(num.toString()) : 0;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void deleteDir(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        }
    }

    /**
     * 会话元信息
     */
    public static class SessionMeta {
        public String id;
        public String title;
        public String cwd;
        public long createdAt;
        public long updatedAt;
        public int messageCount;
    }
}
