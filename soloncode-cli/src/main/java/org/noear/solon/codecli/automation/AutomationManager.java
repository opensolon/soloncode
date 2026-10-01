package org.noear.solon.codecli.automation;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.command.builtin.AutomationMeta;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.command.builtin.LoopTask;
import org.noear.solon.codecli.session.SessionManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动任务薄门面：统一存储（tasks.json）后任务本体由 LoopScheduler 管理，
 * 本类只负责 auto- 专用会话生命周期、automation 覆盖层读写与 Web 端视图。
 */
public class AutomationManager {
    public static final String SESSION_PREFIX = "auto-";

    private final LoopScheduler scheduler;
    private final SessionManager sessionManager;

    public AutomationManager(String workspacePath, LoopScheduler scheduler, SessionManager sessionManager) {
        this.scheduler = scheduler;
        this.sessionManager = sessionManager;
    }

    /** 兼容旧调用点：任务随 LoopScheduler.loadAll() 统一恢复，此处不再有独立恢复逻辑。 */
    public synchronized void restore() {
        scheduler.restoreAll();
    }

    // ==================== 视图 ====================

    /** 全部任务（自动任务 + 会话循环任务），供自动任务页统一展示。 */
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<LoopTask>> entry : scheduler.listAll().entrySet()) {
            for (LoopTask task : entry.getValue()) {
                result.add(toMap(entry.getKey(), task));
            }
        }
        return result;
    }

    public synchronized Map<String, Object> get(String id) {
        for (Map.Entry<String, List<LoopTask>> entry : scheduler.listAll().entrySet()) {
            for (LoopTask task : entry.getValue()) {
                if (task.isAutomation() && id.equals(task.getAutomation().getId())) {
                    return toMap(entry.getKey(), task);
                }
            }
        }
        return null;
    }

    /** 空名称时取提示词前 20 字作为展示名（首个换行处截断），避免多个任务同名不可辨。 */
    private static String displayName(String name, String prompt) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String base = prompt == null ? "" : prompt.trim().split("\r?\n", 2)[0];
        return base.length() > 20 ? base.substring(0, 20) : (base.isEmpty() ? "自动任务" : base);
    }

    // ==================== CRUD（委托 scheduler + 覆盖层） ====================

    public synchronized String create(String name, String prompt, Integer intervalMinutes,
                                      String cron, String type, Boolean runNow,
                                      Long maxTokens, Long maxDurationMs, String modelName,
                                      String agentName) throws IOException {
        validate(name, prompt, intervalMinutes, cron, maxTokens, maxDurationMs);
        String sessionId = SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
        // 触发 FileAgentSession 的懒创建，确保专用会话目录在任务落盘前存在。
        sessionManager.getSession(sessionId);

        LoopTask.TaskType taskType = parseType(type);
        LoopTask task = new LoopTask(prompt.trim(), intervalMinutes == null ? 5 : intervalMinutes,
                emptyToNull(cron), taskType, Boolean.TRUE.equals(runNow));
        if (maxTokens != null) task.setMaxTokens(maxTokens);
        if (maxDurationMs != null) task.setMaxDurationMs(maxDurationMs);
        task.setAutomation(new AutomationMeta(displayName(name, prompt.trim()),
                emptyToNull(modelName), emptyToNull(agentName)));
        applySelections(sessionId, task.getAutomation());
        scheduler.schedule(sessionId, task);
        sessionManager.markAutomationSession(sessionId, task.getAutomation().getId());
        if (!task.getAutomation().isEnabled()) scheduler.toggle(sessionId, task.getId());
        return task.getAutomation().getId();
    }

    /** 定位 automation id 对应的 (sessionId, task)。 */
    private LoopTask locate(String id, String[] sessionIdOut) {
        for (Map.Entry<String, List<LoopTask>> entry : scheduler.listAll().entrySet()) {
            for (LoopTask task : entry.getValue()) {
                if (task.isAutomation() && id.equals(task.getAutomation().getId())) {
                    sessionIdOut[0] = entry.getKey();
                    return task;
                }
            }
        }
        throw new IllegalArgumentException("Automation not found");
    }

    public synchronized void update(String id, String name, String prompt, Integer intervalMinutes,
                                    String cron, String type, Boolean runNow,
                                     Long maxTokens, Long maxDurationMs, String modelName,
                                     String agentName) throws IOException {
        String[] sessionIdOut = new String[1];
        LoopTask old = locate(id, sessionIdOut);
        String sessionId = sessionIdOut[0];
        AutomationMeta meta = old.getAutomation();

        validate(name == null ? meta.getName() : name,
                prompt == null ? old.getPrompt() : prompt,
                intervalMinutes, cron == null ? old.getCron() : cron,
                maxTokens == null ? old.getMaxTokens() : maxTokens,
                maxDurationMs == null ? old.getMaxDurationMs() : maxDurationMs);

        int interval = intervalMinutes == null ? old.getIntervalMinutes() : intervalMinutes;
        String effectivePrompt = prompt == null || prompt.trim().isEmpty() ? old.getPrompt() : prompt.trim();
        LoopTask.TaskType newType = type == null ? old.getType() : parseType(type);
        LoopTask replacement = old.copyWithUpdate(effectivePrompt, interval,
                cron == null ? old.getCron() : emptyToNull(cron), newType,
                runNow == null ? old.isRunNow() : runNow,
                maxTokens == null ? old.getMaxTokens() : maxTokens,
                maxDurationMs == null ? old.getMaxDurationMs() : maxDurationMs);
        replacement.setEnabled(old.isEnabled());
        replacement.setAutomation(meta);
        meta.update(name, emptyToNull(modelName), emptyToNull(agentName));
        scheduler.update(sessionId, old.getId(), replacement);
        applySelections(sessionId, meta);
    }

    public synchronized void toggle(String id) throws IOException {
        String[] sessionIdOut = new String[1];
        LoopTask task = locate(id, sessionIdOut);
        scheduler.toggle(sessionIdOut[0], task.getId());
        task = scheduler.getTaskById(sessionIdOut[0], task.getId());
        if (task != null && task.isAutomation()) {
            task.getAutomation().setEnabled(task.isEnabled());
        }
    }

    public synchronized void trigger(String id) {
        String[] sessionIdOut = new String[1];
        LoopTask task = locate(id, sessionIdOut);
        scheduler.trigger(sessionIdOut[0], task.getId());
    }

    public synchronized void remove(String id) throws IOException {
        String[] sessionIdOut = new String[1];
        LoopTask task = locate(id, sessionIdOut);
        String sessionId = sessionIdOut[0];
        scheduler.remove(sessionId, task);
        sessionManager.archiveAutomationSession(sessionId, id);
    }

    public String sessionId(String id) {
        try {
            String[] sessionIdOut = new String[1];
            locate(id, sessionIdOut);
            return sessionIdOut[0];
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== 视图构建 ====================

    private Map<String, Object> toMap(String sessionId, LoopTask task) {
        AutomationMeta meta = task.getAutomation();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", meta != null ? meta.getId() : task.getId());
        item.put("type", "AUTOMATION");
        item.put("sessionId", sessionId);
        item.put("taskId", task.getId());
        // 普通会话任务无覆盖层：名称用提示词截断，和自动任务无名称时的规则一致
        item.put("name", meta != null ? meta.getName() : displayName(null, task.getPrompt()));
        item.put("prompt", task.getPrompt());
        item.put("intervalMinutes", task.getIntervalMinutes());
        if (task.getCron() != null) item.put("cron", task.getCron());
        item.put("taskType", task.getType().name());
        item.put("runNow", task.isRunNow());
        if (meta != null) {
            if (meta.getModelName() != null) item.put("modelName", meta.getModelName());
            if (meta.getAgentName() != null) item.put("agentName", meta.getAgentName());
        }
        item.put("enabled", task.isEnabled());
        item.put("running", task.isRunning());
        item.put("cancelled", task.isCancelled());
        item.put("currentIteration", task.getCurrentIteration());
        if (task.getLastResult() != null) item.put("lastResult", task.getLastResult());
        if (task.getLastExecutedAt() != null) item.put("lastExecutedAt", task.getLastExecutedAt().toString());
        if (task.isGoalMode()) item.put("goal", task.getGoalState().getStatus().name());
        item.put("automation", task.isAutomation());
        return item;
    }

    // ==================== 内部工具 ====================

    private void applySelections(String sessionId, AutomationMeta meta) {
        org.noear.solon.ai.agent.AgentSession session = sessionManager.getSession(sessionId);
        if (meta.getModelName() != null) session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, meta.getModelName());
        if (meta.getAgentName() != null) session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED, meta.getAgentName());
        session.updateSnapshot();
    }

    private static LoopTask.TaskType parseType(String type) {
        if (type == null || type.trim().isEmpty()) return LoopTask.TaskType.HEARTBEAT;
        try { return LoopTask.TaskType.valueOf(type.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid task type: " + type); }
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void validate(String name, String prompt, Integer intervalMinutes, String cron,
                                 Long maxTokens, Long maxDurationMs) {
        if (name != null && name.trim().length() > 200) throw new IllegalArgumentException("name is too long");
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("prompt is required");
        if (prompt.length() > 100000) throw new IllegalArgumentException("prompt is too long");
        if (intervalMinutes != null && intervalMinutes <= 0) throw new IllegalArgumentException("intervalMinutes must be greater than 0");
        if (cron != null && cron.trim().length() > 200) throw new IllegalArgumentException("cron is too long");
        if (maxTokens != null && maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be greater than 0");
        if (maxDurationMs != null && maxDurationMs <= 0) throw new IllegalArgumentException("maxDurationMs must be greater than 0");
        if ((cron == null || cron.trim().isEmpty()) && (intervalMinutes == null || intervalMinutes <= 0)) {
            throw new IllegalArgumentException("cron or intervalMinutes is required");
        }
    }
}
