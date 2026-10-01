package org.noear.solon.codecli.automation;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.codecli.command.builtin.LoopTask;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 自动任务的工作区级持久化。 */
public class AutomationStore {
    private static final Logger LOG = LoggerFactory.getLogger(AutomationStore.class);
    private static final String DIR_AUTOMATIONS = "automations";
    private static final String FILE_SUFFIX = ".json";
    private final Path root;

    public AutomationStore(String workspacePath) {
        this.root = WorkspaceDataUtil.dataDir(workspacePath).toPath().resolve(DIR_AUTOMATIONS).normalize();
    }

    public List<AutomationTask> list() {
        List<AutomationTask> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            paths.filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        try {
                            AutomationTask task = fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                            if (task != null) result.add(task);
                        } catch (Exception e) {
                            LOG.warn("Skip invalid automation file {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOG.warn("List automations failed: {}", e.getMessage());
        }
        return result;
    }

    public AutomationTask get(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        Path path = root.resolve(safeFileName(id));
        if (!Files.isRegularFile(path)) return null;
        try {
            return fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warn("Read automation {} failed: {}", id, e.getMessage());
            return null;
        }
    }

    public void save(AutomationTask task) throws IOException {
        Files.createDirectories(root);
        Path target = root.resolve(safeFileName(task.getId()));
        Path temp = root.resolve(safeFileName(task.getId()) + ".tmp");
        Files.write(temp, toJson(task).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(root.resolve(safeFileName(id)));
        Files.deleteIfExists(root.resolve(safeRunFileName(id)));
    }

    public synchronized void appendRun(String automationId, String runId, String status,
                                       String sessionId, String taskId, String result, String error) throws IOException {
        Files.createDirectories(root);
        ONode node = new ONode();
        node.set("runId", runId);
        node.set("automationId", automationId);
        node.set("status", status);
        node.set("sessionId", sessionId);
        node.set("taskId", taskId);
        node.set("at", Instant.now().toString());
        if (result != null) node.set("result", result);
        if (error != null) node.set("error", error);
        Files.write(root.resolve(safeRunFileName(automationId)),
                (node.toJson() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /**
     * 返回按 runId 聚合后的最近执行记录。写入端采用 append-only 事件，
     * 读取端把 RUNNING 与终态合并，避免一次执行在 UI 中显示成两条记录。
     */
    public List<Map<String, Object>> runs(String automationId, int limit) {
        Map<String, Map<String, Object>> byRun = new LinkedHashMap<>();
        Path path = root.resolve(safeRunFileName(automationId));
        if (!Files.isRegularFile(path)) return new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                ONode node = ONode.ofJson(line);
                if (node == null || !node.isObject()) continue;
                String runId = text(node, "runId", null);
                if (runId == null) continue;
                Map<String, Object> item = byRun.get(runId);
                if (item == null) {
                    item = new LinkedHashMap<>();
                    item.put("runId", runId);
                    item.put("automationId", text(node, "automationId", automationId));
                    item.put("sessionId", text(node, "sessionId", null));
                    item.put("taskId", text(node, "taskId", null));
                    byRun.put(runId, item);
                }
                item.put("status", text(node, "status", "UNKNOWN"));
                item.put("at", text(node, "at", null));
                if (node.getOrNull("result") != null) item.put("result", text(node, "result", null));
                if (node.getOrNull("error") != null) item.put("error", text(node, "error", null));
            }
        } catch (Exception e) {
            LOG.warn("Read automation runs {} failed: {}", automationId, e.getMessage());
        }
        List<Map<String, Object>> result = new ArrayList<>(byRun.values());
        java.util.Collections.reverse(result);
        if (result.size() > Math.max(1, limit)) return new ArrayList<>(result.subList(0, Math.max(1, limit)));
        return result;
    }

    private static String safeFileName(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_") + FILE_SUFFIX;
    }

    private static String safeRunFileName(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_") + ".runs.ndjson";
    }

    private static String toJson(AutomationTask task) {
        ONode node = new ONode(Options.of(Feature.Write_PrettyFormat));
        node.set("version", 1);
        node.set("id", task.getId());
        node.set("name", task.getName());
        node.set("prompt", task.getPrompt());
        node.set("sessionId", task.getSessionId());
        node.set("loopTaskId", task.getLoopTaskId());
        node.set("intervalMinutes", task.getIntervalMinutes());
        if (task.getCron() != null) node.set("cron", task.getCron());
        node.set("type", task.getType().name());
        node.set("runNow", task.isRunNow());
        if (task.getMaxTokens() != null) node.set("maxTokens", task.getMaxTokens());
        if (task.getMaxDurationMs() != null) node.set("maxDurationMs", task.getMaxDurationMs());
        if (task.getModelName() != null) node.set("modelName", task.getModelName());
        if (task.getAgentName() != null) node.set("agentName", task.getAgentName());
        node.set("enabled", task.isEnabled());
        node.set("createdAt", task.getCreatedAt().toString());
        node.set("updatedAt", task.getUpdatedAt().toString());
        return node.toJson();
    }

    private static AutomationTask fromJson(String json) {
        ONode node = ONode.ofJson(json);
        if (node == null || !node.isObject()) return null;
        String id = required(node, "id");
        String prompt = required(node, "prompt");
        if (id == null || prompt == null) return null;
        String typeText = text(node, "type", LoopTask.TaskType.HEARTBEAT.name());
        LoopTask.TaskType type;
        try { type = LoopTask.TaskType.valueOf(typeText.toUpperCase()); }
        catch (Exception e) { type = LoopTask.TaskType.HEARTBEAT; }
        return AutomationTask.restored(
                id,
                text(node, "name", id),
                prompt,
                text(node, "sessionId", null),
                text(node, "loopTaskId", null),
                integer(node, "intervalMinutes", 5),
                text(node, "cron", null),
                type,
                bool(node, "runNow", false),
                longValue(node, "maxTokens"),
                longValue(node, "maxDurationMs"),
                text(node, "modelName", null),
                text(node, "agentName", null),
                bool(node, "enabled", true),
                instant(node, "createdAt"),
                instant(node, "updatedAt"));
    }

    private static String required(ONode node, String key) { return text(node, key, null); }
    private static String text(ONode node, String key, String fallback) {
        return node.getOrNull(key) == null ? fallback : node.get(key).getString();
    }
    private static boolean bool(ONode node, String key, boolean fallback) {
        return node.getOrNull(key) == null ? fallback : node.get(key).getBoolean();
    }
    private static int integer(ONode node, String key, int fallback) {
        return node.getOrNull(key) == null ? fallback : node.get(key).getInt();
    }
    private static Long longValue(ONode node, String key) {
        return node.getOrNull(key) == null ? null : node.get(key).getLong();
    }
    private static Instant instant(ONode node, String key) {
        String value = text(node, key, null);
        if (value == null) return Instant.now();
        try { return Instant.parse(value); } catch (Exception e) { return Instant.now(); }
    }
}
