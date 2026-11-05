package org.noear.solon.codecli.loop;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.session.loop.LoopExtension;
import org.noear.solon.ai.harness.session.loop.LoopPatch;
import org.noear.solon.ai.harness.session.loop.LoopSpec;
import org.noear.solon.ai.harness.session.loop.LoopTask;
import org.noear.solon.codecli.session.SessionManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动任务薄门面：任务本体由 loop 插件（{@link LoopExtension}）管理，
 * 本类只负责 auto- 专用会话生命周期、automation 覆盖层（任务扩展字段）读写与 Web 端视图。
 *
 * <p>任务随 LoopExtension 挂接时统一加载恢复，本类没有独立的恢复逻辑。</p>
 */
public class AutomationManager {
    public static final String SESSION_PREFIX = "auto-";

    private final LoopExtension loop;
    private final SessionManager sessionManager;
    private final HarnessEngine engine;

    public AutomationManager(LoopExtension loop, SessionManager sessionManager, HarnessEngine engine) {
        this.loop = loop;
        this.sessionManager = sessionManager;
        this.engine = engine;
    }

    // ==================== 视图 ====================

    /** 全部任务（自动化任务 + 会话循环任务），供自动任务页统一展示。 */
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<LoopTask>> entry : loop.listAll().entrySet()) {
            for (LoopTask task : entry.getValue()) {
                result.add(toMap(entry.getKey(), task));
            }
        }
        return result;
    }

    public synchronized Map<String, Object> get(String id) {
        Located found = find(id);
        return found == null ? null : toMap(found.sessionId, found.task);
    }

    /** 空名称时取提示词前 20 字作为展示名（首个换行处截断），避免多个任务同名不可辨。 */
    private static String displayName(String name, String prompt) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String base = prompt == null ? "" : prompt.trim().split("\r?\n", 2)[0];
        return base.length() > 20 ? base.substring(0, 20) : (base.isEmpty() ? "自动化任务" : base);
    }

    // ==================== CRUD（委托 loop 插件 + 覆盖层） ====================

    public synchronized String create(String name, String prompt, Integer intervalMinutes,
                                      String cron, String type, Boolean runNow,
                                      Long maxTokens, Long maxDurationMs, String modelName,
                                      String agentName) throws IOException {
        validate(name, prompt, intervalMinutes, cron);
        rejectGoalType(type);
        String sessionId = SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        // 触发 FileAgentSession 的懒创建，确保专用会话目录在任务落盘前存在。
        sessionManager.getSession(sessionId);

        AutomationMeta meta = new AutomationMeta(displayName(name, prompt.trim()),
                emptyToNull(modelName), emptyToNull(agentName));
        applySelections(sessionId, meta);
        // 请求参数原样交给规格（null = 未设置），间隔范围与 cron 可触发性由插件统一校验
        loop.schedule(sessionId, meta.writeTo(LoopSpec.of(prompt)
                .everyMinutes(intervalMinutes)
                .cron(cron)
                .runNow(runNow)));
        sessionManager.markAutomationSession(sessionId, meta.getId());
        return meta.getId();
    }

    /** automation id 定位到的任务及其所属会话。 */
    private static final class Located {
        final String sessionId;
        final LoopTask task;

        Located(String sessionId, LoopTask task) {
            this.sessionId = sessionId;
            this.task = task;
        }
    }

    /** 定位 automation id 对应的任务；不存在返回 null。 */
    private Located find(String id) {
        if (id == null) {
            return null;
        }
        for (Map.Entry<String, List<LoopTask>> entry : loop.listAll().entrySet()) {
            for (LoopTask task : entry.getValue()) {
                AutomationMeta meta = AutomationMeta.of(task);
                if (meta != null && id.equals(meta.getId())) {
                    return new Located(entry.getKey(), task);
                }
            }
        }
        return null;
    }

    private Located locate(String id) {
        Located found = find(id);
        if (found == null) {
            throw new IllegalArgumentException("Automation not found");
        }
        return found;
    }

    public synchronized void update(String id, String name, String prompt, Integer intervalMinutes,
                                    String cron, String type, Boolean runNow,
                                    Long maxTokens, Long maxDurationMs, String modelName,
                                    String agentName) throws IOException {
        Located found = locate(id);
        LoopTask old = found.task;
        String sessionId = found.sessionId;
        AutomationMeta meta = AutomationMeta.of(old);

        validate(name == null ? meta.getName() : name,
                prompt == null ? old.getPrompt() : prompt,
                intervalMinutes, cron == null ? old.getCron() : cron);
        rejectGoalType(type);

        // 补丁语义：null = 保持原值、cron 空串 = 回到固定间隔；启停与运行状态由插件保留
        meta.update(name, emptyToNull(modelName), emptyToNull(agentName));
        loop.update(sessionId, old.getId(), meta.writeTo(LoopPatch.create()
                .prompt(prompt)
                .everyMinutes(intervalMinutes)
                .cron(cron)
                .runNow(runNow)));
        applySelections(sessionId, meta);
    }

    public synchronized void toggle(String id) throws IOException {
        Located found = locate(id);
        loop.toggle(found.sessionId, found.task.getId());
    }

    public synchronized void trigger(String id) {
        Located found = locate(id);
        loop.trigger(found.sessionId, found.task.getId());
    }

    public synchronized void remove(String id) throws IOException {
        Located found = locate(id);
        loop.remove(found.sessionId, found.task.getId());
        sessionManager.archiveAutomationSession(found.sessionId, id);
    }

    public String sessionId(String id) {
        Located found = find(id);
        return found == null ? null : found.sessionId;
    }

    // ==================== 视图构建 ====================

    /** 插件标准视图 + 自动任务字段（id 用 automation id，任务自身 id 放 taskId）。 */
    private Map<String, Object> toMap(String sessionId, LoopTask task) {
        AutomationMeta meta = AutomationMeta.of(task);
        Map<String, Object> view = task.toMap();
        view.remove("id");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", meta != null ? meta.getId() : task.getId());
        item.put("type", "AUTOMATION");
        item.put("sessionId", sessionId);
        item.put("taskId", task.getId());
        // 普通会话任务无覆盖层：名称用提示词截断，和自动任务无名称时的规则一致
        item.put("name", meta != null ? meta.getName() : displayName(null, task.getPrompt()));
        item.putAll(view);
        if (meta != null) {
            if (meta.getModelName() != null) item.put("modelName", meta.getModelName());
            if (meta.getAgentName() != null) item.put("agentName", meta.getAgentName());
        }
        item.put("automation", meta != null);
        return item;
    }

    // ==================== 内部工具 ====================

    private void applySelections(String sessionId, AutomationMeta meta) {
        AgentSession session = sessionManager.getSession(sessionId);
        if (meta.getModelName() != null) engine.setSessionModel(session, meta.getModelName());
        if (meta.getAgentName() != null) engine.setSessionAgent(session, meta.getAgentName());
        session.updateSnapshot();
    }

    /** GOAL 已属会话级独立能力（/goal 或桌面 goal 端点），自动任务只创建定时任务。 */
    private static void rejectGoalType(String type) {
        if (type != null && "GOAL".equalsIgnoreCase(type.trim())) {
            throw new IllegalArgumentException("GOAL is a session-scoped capability, not an automation type");
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void validate(String name, String prompt, Integer intervalMinutes, String cron) {
        if (name != null && name.trim().length() > 200) throw new IllegalArgumentException("name is too long");
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("prompt is required");
        if (prompt.length() > 100000) throw new IllegalArgumentException("prompt is too long");
        if (cron != null && cron.trim().length() > 200) throw new IllegalArgumentException("cron is too long");
        if ((cron == null || cron.trim().isEmpty()) && (intervalMinutes == null || intervalMinutes <= 0)) {
            throw new IllegalArgumentException("cron or intervalMinutes is required");
        }
    }
}
