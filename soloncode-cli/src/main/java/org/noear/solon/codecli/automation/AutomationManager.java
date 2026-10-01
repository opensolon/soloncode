package org.noear.solon.codecli.automation;

import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.command.builtin.LoopTask;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

/**
 * 自动任务领域服务。
 * 配置保存于 automations/，执行调度复用现有 LoopScheduler，隐藏 session 使用 auto- 前缀。
 */
public class AutomationManager {
    private final java.util.Map<String, String> activeRunIds = new java.util.HashMap<>();
    private final LoopScheduler scheduler;
    private final SessionManager sessionManager;
    private final AutomationStore store;

    public AutomationManager(String workspacePath, LoopScheduler scheduler, SessionManager sessionManager) {
        this.scheduler = scheduler;
        this.sessionManager = sessionManager;
        this.store = new AutomationStore(workspacePath);
        scheduler.addExecutionListener(new LoopScheduler.ExecutionListener() {
            @Override
            public void onStarted(String sessionId, LoopTask task) {
                recordRun(sessionId, task, "RUNNING", null);
            }

            @Override
            public void onFinished(String sessionId, LoopTask task, boolean success, Throwable error) {
                recordRun(sessionId, task, success ? "SUCCESS" : "FAILED",
                        error == null ? task.getLastResult() : error.getMessage());
            }
        });
    }

    /** 工作区上下文创建完成且 Loop 执行器已注册后调用。 */
    public synchronized void restore() {
        for (AutomationTask automation : store.list()) {
            if (!automation.isEnabled()) continue;
            try {
                ensureScheduled(automation);
            } catch (Exception e) {
                // 单个自动任务损坏不能阻断工作区恢复。
                System.err.println("Restore automation failed: " + automation.getId() + ": " + e.getMessage());
            }
        }
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AutomationTask task : store.list()) {
            result.add(toMap(task));
        }
        return result;
    }

    public synchronized Map<String, Object> get(String id) {
        AutomationTask task = store.get(id);
        return task == null ? null : toMap(task);
    }

    /** 空名称时取提示词前 20 字作为展示名（首个换行处截断），避免多个任务同名不可辨。 */
    private static String displayName(String name, String prompt) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String base = prompt == null ? "" : prompt.trim().split("\r?\n", 2)[0];
        return base.length() > 20 ? base.substring(0, 20) : (base.isEmpty() ? "自动任务" : base);
    }

    public synchronized AutomationTask create(String name, String prompt, Integer intervalMinutes,
                                              String cron, String type, Boolean runNow,
                                               Long maxTokens, Long maxDurationMs, String modelName,
                                               String agentName) throws IOException {
        validate(name, prompt, intervalMinutes, cron, maxTokens, maxDurationMs);
        String sessionId = "auto-" + UUID.randomUUID().toString().replace("-", "");
        // 触发 FileAgentSession 的懒创建，确保专用会话目录在任务落盘前存在。
        sessionManager.getSession(sessionId);

        LoopTask.TaskType taskType = parseType(type);
        AutomationTask automation = new AutomationTask(
                displayName(name, prompt),
                prompt.trim(), intervalMinutes == null ? 5 : intervalMinutes,
                emptyToNull(cron), taskType, Boolean.TRUE.equals(runNow), maxTokens, maxDurationMs,
                modelName, agentName);
        LoopTask loopTask = createLoopTask(automation);
        scheduler.schedule(sessionId, loopTask);
        automation.bind(sessionId, loopTask.getId());
        applySelections(sessionId, automation.getModelName(), automation.getAgentName());
        sessionManager.markAutomationSession(sessionId, automation.getId());
        if (!automation.isEnabled()) scheduler.toggle(sessionId, loopTask.getId());
        store.save(automation);
        return automation;
    }

    public synchronized void update(String id, String name, String prompt, Integer intervalMinutes,
                                    String cron, String type, Boolean runNow,
                                     Long maxTokens, Long maxDurationMs, String modelName,
                                     String agentName) throws IOException {
        AutomationTask automation = require(id);
        validate(name == null ? automation.getName() : name,
                prompt == null ? automation.getPrompt() : prompt,
                intervalMinutes == null ? automation.getIntervalMinutes() : intervalMinutes,
                cron == null ? automation.getCron() : cron,
                maxTokens == null ? automation.getMaxTokens() : maxTokens,
                maxDurationMs == null ? automation.getMaxDurationMs() : maxDurationMs);
        LoopTask old = scheduler.getTaskById(automation.getSessionId(), automation.getLoopTaskId());
        if (old == null) {
            ensureScheduled(automation);
            old = scheduler.getTaskById(automation.getSessionId(), automation.getLoopTaskId());
        }
        if (old == null) throw new IllegalStateException("Automation task is not scheduled");

        int interval = intervalMinutes == null ? automation.getIntervalMinutes() : intervalMinutes;
        String effectivePrompt = prompt == null || prompt.trim().isEmpty() ? automation.getPrompt() : prompt.trim();
        LoopTask.TaskType newType = type == null ? automation.getType() : parseType(type);
        LoopTask replacement = old.copyWithUpdate(effectivePrompt, interval,
                cron == null ? automation.getCron() : emptyToNull(cron), newType,
                runNow == null ? automation.isRunNow() : runNow,
                maxTokens == null ? old.getMaxTokens() : maxTokens,
                maxDurationMs == null ? old.getMaxDurationMs() : maxDurationMs);
        replacement.setEnabled(old.isEnabled());
        scheduler.update(automation.getSessionId(), old.getId(), replacement);
        applySelections(automation.getSessionId(), modelName, agentName);
        automation.update(name, effectivePrompt, interval,
                cron == null ? automation.getCron() : emptyToNull(cron), newType,
                runNow, maxTokens == null ? old.getMaxTokens() : maxTokens,
                maxDurationMs == null ? old.getMaxDurationMs() : maxDurationMs,
                modelName == null ? automation.getModelName() : modelName,
                agentName == null ? automation.getAgentName() : agentName);
        store.save(automation);
    }

    public synchronized void toggle(String id) throws IOException {
        AutomationTask automation = require(id);
        ensureScheduled(automation);
        scheduler.toggle(automation.getSessionId(), automation.getLoopTaskId());
        LoopTask task = scheduler.getTaskById(automation.getSessionId(), automation.getLoopTaskId());
        automation.setEnabled(task == null || task.isEnabled());
        store.save(automation);
    }

    public synchronized void trigger(String id) {
        AutomationTask automation = require(id);
        ensureScheduled(automation);
        scheduler.trigger(automation.getSessionId(), automation.getLoopTaskId());
    }

    public synchronized void remove(String id) throws IOException {
        AutomationTask automation = require(id);
        LoopTask task = scheduler.getTaskById(automation.getSessionId(), automation.getLoopTaskId());
        if (task != null) scheduler.remove(automation.getSessionId(), task);
        sessionManager.archiveAutomationSession(automation.getSessionId(), automation.getId());
        store.delete(id);
    }

    public String sessionId(String id) {
        AutomationTask task = store.get(id);
        return task == null ? null : task.getSessionId();
    }

    public synchronized List<Map<String, Object>> runs(String id, int limit) {
        if (store.get(id) == null) return null;
        return store.runs(id, limit);
    }

    private synchronized void recordRun(String sessionId, LoopTask task, String status, String value) {
        for (AutomationTask automation : store.list()) {
            if (!sessionId.equals(automation.getSessionId()) || !task.getId().equals(automation.getLoopTaskId())) continue;
            String key = automation.getId();
            String runId = activeRunIds.get(key);
            if ("RUNNING".equals(status) || runId == null) {
                runId = UUID.randomUUID().toString().replace("-", "");
                activeRunIds.put(key, runId);
            }
            try {
                store.appendRun(automation.getId(), runId, status, sessionId, task.getId(),
                        "RUNNING".equals(status) ? null : value,
                        "FAILED".equals(status) ? value : null);
                if (!"RUNNING".equals(status)) activeRunIds.remove(key);
            } catch (IOException e) {
                System.err.println("Persist automation run failed: " + e.getMessage());
            }
            return;
        }
    }

    private AutomationTask require(String id) {
        AutomationTask task = store.get(id);
        if (task == null) throw new IllegalArgumentException("Automation not found");
        return task;
    }

    private void ensureScheduled(AutomationTask automation) {
        if (automation.getSessionId() == null || automation.getSessionId().trim().isEmpty()) {
            throw new IllegalStateException("Automation session is missing");
        }
        sessionManager.getSession(automation.getSessionId());
        if (automation.getLoopTaskId() != null
                && scheduler.getTaskById(automation.getSessionId(), automation.getLoopTaskId()) != null) return;
        LoopTask task = createLoopTask(automation);
        scheduler.schedule(automation.getSessionId(), task);
        automation.bind(automation.getSessionId(), task.getId());
        try { store.save(automation); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private LoopTask createLoopTask(AutomationTask automation) {
        LoopTask task = new LoopTask(automation.getPrompt(), automation.getIntervalMinutes(),
                automation.getCron(), automation.getType(), automation.isRunNow());
        if (automation.getMaxTokens() != null) task.setMaxTokens(automation.getMaxTokens());
        if (automation.getMaxDurationMs() != null) task.setMaxDurationMs(automation.getMaxDurationMs());
        return task;
    }

    private Map<String, Object> toMap(AutomationTask automation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", automation.getId());
        item.put("type", "AUTOMATION");
        item.put("name", automation.getName());
        item.put("prompt", automation.getPrompt());
        item.put("sessionId", automation.getSessionId());
        item.put("taskId", automation.getLoopTaskId());
        item.put("intervalMinutes", automation.getIntervalMinutes());
        if (automation.getCron() != null) item.put("cron", automation.getCron());
        item.put("taskType", automation.getType().name());
        item.put("runNow", automation.isRunNow());
        if (automation.getModelName() != null) item.put("modelName", automation.getModelName());
        if (automation.getAgentName() != null) item.put("agentName", automation.getAgentName());
        item.put("enabled", automation.isEnabled());
        item.put("createdAt", automation.getCreatedAt().toString());
        item.put("updatedAt", automation.getUpdatedAt().toString());
        LoopTask loop = automation.getSessionId() == null ? null :
                scheduler.getTaskById(automation.getSessionId(), automation.getLoopTaskId());
        if (loop != null) {
            item.put("running", loop.isRunning());
            item.put("cancelled", loop.isCancelled());
            item.put("currentIteration", loop.getCurrentIteration());
            if (loop.getLastResult() != null) item.put("lastResult", loop.getLastResult());
            if (loop.getLastExecutedAt() != null) item.put("lastExecutedAt", loop.getLastExecutedAt().toString());
            if (loop.isGoalMode()) item.put("goal", loop.getGoalState().getStatus().name());
        }
        return item;
    }

    private static LoopTask.TaskType parseType(String type) {
        if (type == null || type.trim().isEmpty()) return LoopTask.TaskType.HEARTBEAT;
        try { return LoopTask.TaskType.valueOf(type.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid task type: " + type); }
    }

    private void applySelections(String sessionId, String modelName, String agentName) {
        org.noear.solon.ai.agent.AgentSession session = sessionManager.getSession(sessionId);
        if (modelName != null && !modelName.trim().isEmpty()) session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, modelName.trim());
        if (agentName != null) session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED, agentName.trim());
        session.updateSnapshot();
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
