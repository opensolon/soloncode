package org.noear.solon.codecli;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
     * 列出可恢复的会话（即消息文件存在）
     */
    public List<SessionMeta> listRestorableSessions(String filterCwd) {
        List<SessionMeta> sessions = listSessions(filterCwd);
        if (sessions.isEmpty()) {
            return sessions;
        }

        List<SessionMeta> result = new ArrayList<SessionMeta>();
        for (SessionMeta meta : sessions) {
            if (hasSessionData(meta)) {
                result.add(meta);
            }
        }
        return result;
    }

    /**
     * 获取指定目录最近可恢复的会话
     */
    public SessionMeta getLatestRestorableSession(String filterCwd) {
        List<SessionMeta> sessions = listRestorableSessions(filterCwd);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    /**
     * 获取单个会话的 meta
     */
    public SessionMeta getSessionMeta(String sessionId) {
        return readMeta(sessionsDir.resolve(sessionId));
    }

    public boolean hasSessionData(SessionMeta meta) {
        return resolveMessageFile(meta) != null;
    }

    public boolean hasPortalEvents(SessionMeta meta) {
        Path file = resolvePortalEventFile(meta);
        if (file == null || !Files.exists(file)) {
            return false;
        }

        try {
            return Files.size(file) > 0;
        } catch (IOException e) {
            LOG.warn("Failed to inspect portal event file: {}", file, e);
            return false;
        }
    }

    public List<SessionMessage> readMessages(SessionMeta meta) {
        Path file = resolveMessageFile(meta);
        if (file == null || !Files.exists(file)) {
            return Collections.emptyList();
        }

        List<SessionMessage> messages = new ArrayList<SessionMessage>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                ONode node = ONode.ofJson(line);
                SessionMessage message = new SessionMessage();
                message.role = node.get("role").getString();
                message.content = node.get("content").getString();
                message.contentRaw = node.get("contentRaw").getString();
                message.thinking = node.get("isThinking").getBoolean();

                if (isBlank(message.content) && !isBlank(message.contentRaw)) {
                    message.content = message.contentRaw;
                }

                if (!isBlank(message.role) && !isBlank(message.content)) {
                    messages.add(message);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read session messages: {}", file, e);
        }

        return messages;
    }

    public List<SessionEvent> readPortalEvents(SessionMeta meta) {
        Path file = resolvePortalEventFile(meta);
        if (file == null || !Files.exists(file)) {
            return Collections.emptyList();
        }

        List<SessionEvent> events = new ArrayList<SessionEvent>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                ONode node = ONode.ofJson(line);
                SessionEvent event = new SessionEvent();
                event.type = node.get("type").getString();
                event.timestamp = node.get("timestamp").getLong(0L);
                event.content = node.get("content").getString();
                event.toolName = node.get("toolName").getString();
                event.argsText = node.get("argsText").getString();

                ONode segmentsNode = node.get("argSegments");
                if (segmentsNode != null && segmentsNode.isArray()) {
                    for (ONode item : segmentsNode.getArray()) {
                        String value = item.getString();
                        if (!isBlank(value)) {
                            event.argSegments.add(value);
                        }
                    }
                }

                if (!isBlank(event.type)) {
                    events.add(event);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read portal events: {}", file, e);
        }

        return events;
    }

    public String extractLastUserMessage(SessionMeta meta) {
        List<SessionMessage> messages = readMessages(meta);
        for (int i = messages.size() - 1; i >= 0; i--) {
            SessionMessage message = messages.get(i);
            if ("USER".equalsIgnoreCase(message.role) && !isBlank(message.content)) {
                return message.content;
            }
        }

        return "";
    }

    public Path resolveSessionDataDir(SessionMeta meta) {
        if (meta == null || isBlank(meta.id) || isBlank(meta.cwd)) {
            return null;
        }

        return Paths.get(meta.cwd, ".soloncode", "sessions", meta.id).normalize();
    }

    public Path resolvePortalEventFile(SessionMeta meta) {
        Path sessionDir = resolveSessionDataDir(meta);
        if (sessionDir == null) {
            return null;
        }

        return sessionDir.resolve("portal.events.ndjson");
    }

    public Path resolveMessageFile(SessionMeta meta) {
        Path sessionDir = resolveSessionDataDir(meta);
        if (sessionDir == null || !Files.exists(sessionDir)) {
            return null;
        }

        Path named = sessionDir.resolve(meta.id + ".messages.ndjson");
        if (Files.exists(named)) {
            return named;
        }

        try (Stream<Path> files = Files.list(sessionDir)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".messages.ndjson"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOG.warn("Failed to resolve message file: {}", sessionDir, e);
            return null;
        }
    }

    public void appendPortalEvent(String sessionId, String cwd, SessionEvent event) {
        if (event == null || isBlank(sessionId) || isBlank(cwd)) {
            return;
        }

        Path file = resolvePortalEventFile(sessionId, cwd);
        if (file == null) {
            return;
        }

        ensureDir(file.getParent());

        ONode node = new ONode().asObject();
        node.set("type", event.type);
        node.set("timestamp", event.timestamp);
        if (!isBlank(event.content)) {
            node.set("content", event.content);
        }
        if (!isBlank(event.toolName)) {
            node.set("toolName", event.toolName);
        }
        if (!isBlank(event.argsText)) {
            node.set("argsText", event.argsText);
        }
        if (event.argSegments != null && !event.argSegments.isEmpty()) {
            node.set("argSegments", event.argSegments);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            writer.write(node.toJson());
            writer.write('\n');
        } catch (IOException e) {
            LOG.warn("Failed to append portal event: {}", file, e);
        }
    }

    public void clearPortalEvents(String sessionId, String cwd) {
        Path file = resolvePortalEventFile(sessionId, cwd);
        if (file == null || !Files.exists(file)) {
            return;
        }

        try {
            Files.delete(file);
        } catch (IOException e) {
            LOG.warn("Failed to clear portal events: {}", file, e);
        }
    }

    public void bootstrapPortalEvents(SessionMeta meta) {
        if (meta == null) {
            return;
        }

        Path file = resolvePortalEventFile(meta);
        if (file == null || Files.exists(file)) {
            return;
        }

        List<SessionMessage> messages = readMessages(meta);
        if (messages.isEmpty()) {
            return;
        }

        ensureDir(file.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (SessionMessage message : messages) {
                SessionEvent event = new SessionEvent();
                event.content = message.content;
                if ("USER".equalsIgnoreCase(message.role)) {
                    event.type = "user";
                } else if ("ASSISTANT".equalsIgnoreCase(message.role)) {
                    event.type = message.thinking ? "thinking" : "assistant";
                } else {
                    event.type = "message";
                }

                ONode node = new ONode().asObject();
                node.set("type", event.type);
                node.set("content", event.content);
                writer.write(node.toJson());
                writer.write('\n');
            }
        } catch (IOException e) {
            LOG.warn("Failed to bootstrap portal events: {}", file, e);
        }
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Path resolvePortalEventFile(String sessionId, String cwd) {
        if (isBlank(sessionId) || isBlank(cwd)) {
            return null;
        }

        return Paths.get(cwd, ".soloncode", "sessions", sessionId, "portal.events.ndjson").normalize();
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

    public static class SessionMessage {
        public String role;
        public String content;
        public String contentRaw;
        public boolean thinking;
    }

    public static class SessionEvent {
        public String type;
        public long timestamp;
        public String content;
        public String toolName;
        public String argsText;
        public List<String> argSegments = new ArrayList<String>();
    }
}
