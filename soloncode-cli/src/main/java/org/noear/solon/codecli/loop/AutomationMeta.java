package org.noear.solon.codecli.loop;

import org.noear.snack4.ONode;

import java.time.Instant;
import java.util.UUID;

/**
 * 自动任务覆盖层：LoopTask 上的可选扩展信息。
 *
 * <p>tasks.json 统一存储后，自动任务与普通会话循环任务共用 {@link LoopTask} 结构；
 * 本类只承载自动任务独有的展示与执行元数据（名称、模型/智能体选择、时间戳），
 * 挂在 LoopTask.automation 字段上，null 表示普通会话循环任务。</p>
 */
public class AutomationMeta {
    private final String id;
    private volatile String name;
    private volatile String modelName;
    private volatile String agentName;
    private volatile boolean enabled = true;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public AutomationMeta(String name, String modelName, String agentName) {
        this(UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                name, modelName, agentName, true, Instant.now(), Instant.now());
    }

    private AutomationMeta(String id, String name, String modelName, String agentName,
                           boolean enabled, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.modelName = emptyToNull(modelName);
        this.agentName = emptyToNull(agentName);
        this.enabled = enabled;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getModelName() { return modelName; }
    public String getAgentName() { return agentName; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String name, String modelName, String agentName) {
        if (name != null && !name.trim().isEmpty()) this.name = name.trim();
        this.modelName = emptyToNull(modelName);
        this.agentName = emptyToNull(agentName);
        touch();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }

    public ONode toONode() {
        ONode node = new ONode();
        node.set("id", id);
        node.set("name", name);
        if (modelName != null) node.set("modelName", modelName);
        if (agentName != null) node.set("agentName", agentName);
        node.set("enabled", enabled);
        node.set("createdAt", createdAt.toString());
        node.set("updatedAt", updatedAt.toString());
        return node;
    }

    public static AutomationMeta fromONode(ONode node) {
        String id = text(node, "id", null);
        if (id == null || id.isEmpty()) return null;
        return new AutomationMeta(
                id,
                text(node, "name", id),
                text(node, "modelName", null),
                text(node, "agentName", null),
                bool(node, "enabled", true),
                instant(node, "createdAt"),
                instant(node, "updatedAt"));
    }

    private static String text(ONode node, String key, String fallback) {
        return node.getOrNull(key) == null ? fallback : node.get(key).getString();
    }

    private static boolean bool(ONode node, String key, boolean fallback) {
        return node.getOrNull(key) == null ? fallback : node.get(key).getBoolean();
    }

    private static Instant instant(ONode node, String key) {
        String value = text(node, key, null);
        if (value == null) return Instant.now();
        try { return Instant.parse(value); } catch (Exception e) { return Instant.now(); }
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
