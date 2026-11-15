package org.noear.solon.codecli.loop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.extension.loop.LoopPatch;
import org.noear.solon.ai.harness.extension.loop.LoopSpec;
import org.noear.solon.ai.harness.extension.loop.LoopTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    /** 任务级技能视野（null = 不限制，回退智能体声明） */
    private volatile List<String> skills;
    /** 任务级 MCP 视野（null = 不限制，回退智能体声明） */
    private volatile List<String> mcps;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public AutomationMeta(String name, String modelName, String agentName) {
        this(name, modelName, agentName, null, null);
    }

    public AutomationMeta(String name, String modelName, String agentName,
                          List<String> skills, List<String> mcps) {
        this(UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                name, modelName, agentName, skills, mcps, Instant.now(), Instant.now());
    }

    private AutomationMeta(String id, String name, String modelName, String agentName,
                           List<String> skills, List<String> mcps,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.modelName = emptyToNull(modelName);
        this.agentName = emptyToNull(agentName);
        this.skills = normalize(skills);
        this.mcps = normalize(mcps);
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
    public List<String> getSkills() { return skills; }
    public List<String> getMcps() { return mcps; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 兼容旧调用：只改名称 / 模型 / 智能体，技能与 MCP 视野保持不变。 */
    public void update(String name, String modelName, String agentName) {
        update(name, modelName, agentName, skills, mcps);
    }

    public void update(String name, String modelName, String agentName,
                       List<String> skills, List<String> mcps) {
        if (name != null && !name.trim().isEmpty()) this.name = name.trim();
        this.modelName = emptyToNull(modelName);
        this.agentName = emptyToNull(agentName);
        this.skills = normalize(skills);
        this.mcps = normalize(mcps);
        this.updatedAt = Instant.now();
    }

    public ONode toONode() {
        ONode node = new ONode();
        node.set("id", id);
        node.set("name", name);
        if (modelName != null) node.set("modelName", modelName);
        if (agentName != null) node.set("agentName", agentName);
        if (skills != null) {
            ONode skillsNode = node.getOrNew("skills");
            for (String skill : skills) skillsNode.add(skill);
        }
        if (mcps != null) {
            ONode mcpsNode = node.getOrNew("mcps");
            for (String mcp : mcps) mcpsNode.add(mcp);
        }
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
                strings(node, "skills"),
                strings(node, "mcps"),
                instant(node, "createdAt"),
                instant(node, "updatedAt"));
    }

    private static String text(ONode node, String key, String fallback) {
        return node.getOrNull(key) == null ? fallback : node.get(key).getString();
    }

    private static List<String> strings(ONode node, String key) {
        ONode list = node.getOrNull(key);
        if (list == null || list.isArray() == false) return null;

        List<String> values = new ArrayList<>();
        for (ONode item : list.getArray()) {
            String value = item.getString();
            if (value != null && value.trim().isEmpty() == false) values.add(value.trim());
        }
        return values.isEmpty() ? null : values;
    }

    /** trim + 丢空 + 去重保序；空集合归一为 null（不限制） */
    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) return null;

        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String trimmed = value == null ? null : value.trim();
            if (trimmed != null && trimmed.isEmpty() == false && normalized.contains(trimmed) == false) {
                normalized.add(trimmed);
            }
        }
        return normalized.isEmpty() ? null : normalized;
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
