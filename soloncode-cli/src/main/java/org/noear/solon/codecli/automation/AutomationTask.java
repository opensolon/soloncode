package org.noear.solon.codecli.automation;

import org.noear.solon.codecli.command.builtin.LoopTask;

import java.time.Instant;
import java.util.UUID;

/** 以任务为中心的自动任务定义。 */
public class AutomationTask {
    private final String id;
    private String name;
    private String prompt;
    private String sessionId;
    private String loopTaskId;
    private int intervalMinutes;
    private String cron;
    private LoopTask.TaskType type;
    private boolean runNow;
    private Long maxTokens;
    private Long maxDurationMs;
    private String modelName;
    private String agentName;
    private boolean enabled;
    private final Instant createdAt;
    private Instant updatedAt;

    public AutomationTask(String name, String prompt, int intervalMinutes, String cron,
                          LoopTask.TaskType type, boolean runNow, Long maxTokens, Long maxDurationMs,
                          String modelName, String agentName) {
        this(UUID.randomUUID().toString().replace("-", "").substring(0, 12), name, prompt,
                null, null, intervalMinutes, cron, type, runNow, maxTokens, maxDurationMs,
                modelName, agentName, true, Instant.now(), Instant.now());
    }

    private AutomationTask(String id, String name, String prompt, String sessionId, String loopTaskId,
                           int intervalMinutes, String cron, LoopTask.TaskType type, boolean runNow,
                           Long maxTokens, Long maxDurationMs, String modelName, String agentName,
                           boolean enabled, Instant createdAt, Instant updatedAt) {
        this.id = id; this.name = name; this.prompt = prompt; this.sessionId = sessionId;
        this.loopTaskId = loopTaskId; this.intervalMinutes = intervalMinutes; this.cron = cron;
        this.type = type == null ? LoopTask.TaskType.HEARTBEAT : type;
        this.runNow = runNow; this.maxTokens = maxTokens; this.maxDurationMs = maxDurationMs;
        this.modelName = emptyToNull(modelName); this.agentName = emptyToNull(agentName);
        this.enabled = enabled; this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public static AutomationTask restored(String id, String name, String prompt, String sessionId,
                                          String loopTaskId, int intervalMinutes, String cron,
                                          LoopTask.TaskType type, boolean runNow, Long maxTokens,
                                          Long maxDurationMs, String modelName, String agentName,
                                          boolean enabled, Instant createdAt, Instant updatedAt) {
        return new AutomationTask(id, name, prompt, sessionId, loopTaskId, intervalMinutes, cron,
                type, runNow, maxTokens, maxDurationMs, modelName, agentName, enabled, createdAt, updatedAt);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getPrompt() { return prompt; }
    public String getSessionId() { return sessionId; }
    public String getLoopTaskId() { return loopTaskId; }
    public int getIntervalMinutes() { return intervalMinutes; }
    public String getCron() { return cron; }
    public LoopTask.TaskType getType() { return type; }
    public boolean isRunNow() { return runNow; }
    public Long getMaxTokens() { return maxTokens; }
    public Long getMaxDurationMs() { return maxDurationMs; }
    public String getModelName() { return modelName; }
    public String getAgentName() { return agentName; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void bind(String sessionId, String loopTaskId) { this.sessionId = sessionId; this.loopTaskId = loopTaskId; touch(); }

    public void update(String name, String prompt, int intervalMinutes, String cron, LoopTask.TaskType type,
                       Boolean runNow, Long maxTokens, Long maxDurationMs, String modelName, String agentName) {
        if (name != null && !name.trim().isEmpty()) this.name = name.trim();
        if (prompt != null && !prompt.trim().isEmpty()) this.prompt = prompt.trim();
        this.intervalMinutes = intervalMinutes; this.cron = cron;
        if (type != null) this.type = type;
        if (runNow != null) this.runNow = runNow;
        this.maxTokens = maxTokens; this.maxDurationMs = maxDurationMs;
        this.modelName = emptyToNull(modelName); this.agentName = emptyToNull(agentName); touch();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }
    private static String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
}
