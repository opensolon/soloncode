package org.noear.solon.codecli.loop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.session.loop.LoopPatch;
import org.noear.solon.ai.harness.session.loop.LoopSpec;
import org.noear.solon.ai.harness.session.loop.LoopTask;

import java.time.Instant;
import java.util.UUID;

/**
 * 自动任务覆盖层：挂在 {@link LoopTask} 扩展字段 {@value #EXTRA_KEY} 下的宿主元数据。
 *
 * <p>loop 插件不认识「自动任务」这个宿主概念，只把不认识的顶层字段原样存取；本类负责
 * 该字段的编解码（名称、模型/智能体选择、时间戳）。键名与历史 tasks.json 一致，格式不变。</p>
 *
 * <p>启停状态只有一个真相源：{@code task.isEnabled()}（旧版覆盖层上的 enabled 是重复状态，已删除；
 * 历史文件里残留的该字段读取时忽略）。</p>
 */
public class AutomationMeta {
    /** tasks.json 中的扩展字段名（历史契约，不可改）。 */
    public static final String EXTRA_KEY = "automation";

    private final String id;
    private volatile String name;
    private volatile String modelName;
    private volatile String agentName;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public AutomationMeta(String name, String modelName, String agentName) {
        this(UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                name, modelName, agentName, Instant.now(), Instant.now());
    }

    private AutomationMeta(String id, String name, String modelName, String agentName,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.modelName = emptyToNull(modelName);
        this.agentName = emptyToNull(agentName);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    /** 读取任务上的覆盖层（普通会话循环任务返回 null）。 */
    public static AutomationMeta of(LoopTask task) {
        if (task == null) {
            return null;
        }
        ONode node = task.getExtra(EXTRA_KEY);
        return node == null ? null : fromONode(node);
    }

    /** 新建任务时写入覆盖层（覆盖层是任务定义的一部分，随任务持久化）。 */
    public LoopSpec writeTo(LoopSpec spec) {
        return spec.extra(EXTRA_KEY, toONode());
    }

    /** 修改任务时回写覆盖层（未回写则不生效）。 */
    public LoopPatch writeTo(LoopPatch patch) {
        return patch.extra(EXTRA_KEY, toONode());
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getModelName() { return modelName; }
    public String getAgentName() { return agentName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String name, String modelName, String agentName) {
        if (name != null && !name.trim().isEmpty()) this.name = name.trim();
        this.modelName = emptyToNull(modelName);
        this.agentName = emptyToNull(agentName);
        this.updatedAt = Instant.now();
    }

    public ONode toONode() {
        ONode node = new ONode();
        node.set("id", id);
        node.set("name", name);
        if (modelName != null) node.set("modelName", modelName);
        if (agentName != null) node.set("agentName", agentName);
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
                instant(node, "createdAt"),
                instant(node, "updatedAt"));
    }

    private static String text(ONode node, String key, String fallback) {
        return node.getOrNull(key) == null ? fallback : node.get(key).getString();
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
