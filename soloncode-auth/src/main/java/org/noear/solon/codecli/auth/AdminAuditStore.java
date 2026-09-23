package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理端审计日志的追加写入与只读查询。审计失败必须是 best effort，不能影响认证业务。
 */
public class AdminAuditStore {
    private static final Logger LOG = LoggerFactory.getLogger(AdminAuditStore.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
    private static final int MAX_PAGE_SIZE = 100;
    private static volatile AdminAuditStore INSTANCE;

    private final Path auditDirectory;
    private final Object writeLock = new Object();

    public AdminAuditStore() {
        this(Paths.get(System.getProperty("user.home"), ".soloncode", "admin", "audit"));
    }

    AdminAuditStore(Path auditDirectory) {
        this.auditDirectory = auditDirectory;
        INSTANCE = this;
    }

    /** 供 UserAuthController/AdminController/UserLoginController 使用的无依赖静态 API。 */
    public static void record(String event, Map<String, ?> fields) {
        AdminAuditStore store = INSTANCE;
        if (store != null) store.append(event, fields);
    }

    public static void record(String event) {
        record(event, Collections.<String, Object>emptyMap());
    }

    private void append(String event, Map<String, ?> fields) {
        try {
            if (event == null || event.trim().isEmpty()) return;
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("timestamp", Instant.now().toString());
            safe.put("event", event.trim());
            if (fields != null) {
                for (Map.Entry<String, ?> entry : fields.entrySet()) {
                    if (entry.getKey() == null || isSensitiveName(entry.getKey())) continue;
                    Object value = safeValue(entry.getValue());
                    if (value != null) safe.put(entry.getKey(), value);
                }
            }
            String line = ONode.ofBean(safe).toJson() + System.lineSeparator();
            Path file = auditDirectory.resolve(MONTH.format(Instant.now()) + ".jsonl");
            synchronized (writeLock) {
                Files.createDirectories(auditDirectory);
                Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
        } catch (Throwable e) {
            LOG.warn("[AdminAudit] audit write failed: {}", e.getClass().getSimpleName());
        }
    }

    private Object safeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object item : ((Map) value).entrySet()) {
                Map.Entry entry = (Map.Entry) item;
                String key = String.valueOf(entry.getKey());
                if (!isSensitiveName(key)) result.put(key, safeValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : (Iterable) value) result.add(safeValue(item));
            return result;
        }
        if (value.getClass().isArray()) return null;
        if (value instanceof Boolean || value instanceof Number) return value;
        return value instanceof String ? value : null;
    }

    private boolean isSensitiveName(String name) {
        String key = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return key.contains("password") || key.contains("passwd") || key.contains("token")
                || key.contains("secret") || key.contains("authorization") || key.contains("ldapadmin")
                || key.contains("credential") || key.contains("hash");
    }

    /** 查询所有按月文件，结果按文件名和文件内追加顺序倒序返回。 */
    public Map<String, Object> query(int page, int pageSize, String event, String field, String value) {
        int safePage = page < 1 ? 1 : page;
        int safePageSize = pageSize < 1 ? 20 : Math.min(pageSize, MAX_PAGE_SIZE);
        List<Map<String, Object>> all = new ArrayList<>();
        try {
            List<Path> files = auditFiles();
            for (Path file : files) readFile(file, all, event, field, value);
        } catch (Throwable e) {
            LOG.warn("[AdminAudit] audit query failed: {}", e.getClass().getSimpleName());
        }
        long start = ((long) safePage - 1) * safePageSize;
        int from = (int) Math.min(start, all.size());
        int to = (int) Math.min((long) from + safePageSize, all.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        result.put("total", all.size());
        result.put("items", new ArrayList<>(all.subList(from, to)));
        return result;
    }

    private List<Path> auditFiles() throws IOException {
        if (!Files.isDirectory(auditDirectory)) return Collections.emptyList();
        List<Path> files = new ArrayList<>();
        DirectoryStream<Path> stream = Files.newDirectoryStream(auditDirectory, "*.jsonl");
        try {
            for (Path path : stream) {
                if (path.getFileName().toString().matches("[0-9]{4}-[0-9]{2}\\.jsonl")) files.add(path);
            }
        } finally {
            stream.close();
        }
        Collections.sort(files, Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()));
        return files;
    }

    private void readFile(Path file, List<Map<String, Object>> output, String event, String field, String value) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> raw = ONode.ofJson(line).toBean(new TypeRef<Map<String, Object>>() { });
                    if (raw == null) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : raw.entrySet()) {
                        if (!isSensitiveName(entry.getKey())) item.put(entry.getKey(), safeValue(entry.getValue()));
                    }
                    if (matches(item, event, field, value)) output.add(0, item);
                } catch (Throwable ignored) {
                    // 单条损坏记录不影响其余日志查询。
                }
            }
        } catch (IOException e) {
            LOG.warn("[AdminAudit] audit file read failed: {}", e.getClass().getSimpleName());
        }
    }

    private boolean matches(Map<String, Object> item, String event, String field, String value) {
        if (event != null && !event.trim().isEmpty() && !event.equals(String.valueOf(item.get("event")))) return false;
        if (field == null || field.trim().isEmpty()) return true;
        if (isSensitiveName(field)) return false;
        Object fieldValue = item.get(field);
        return fieldValue != null && (value == null || value.isEmpty() || String.valueOf(fieldValue).contains(value));
    }

    public Path getAuditDirectory() {
        return auditDirectory;
    }

    public static int maxPageSize() {
        return MAX_PAGE_SIZE;
    }
}
