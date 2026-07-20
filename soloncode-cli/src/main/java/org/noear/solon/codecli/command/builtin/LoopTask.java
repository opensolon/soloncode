/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.command.builtin;

import lombok.Getter;
import org.noear.snack4.ONode;

import java.time.Instant;
import java.util.UUID;

/**
 * 定时循环任务模型，用于 /loop 命令
 *
 * <p>支持 Loop Engineering 的 6 个基元：
 * <ul>
 *   <li>Automations — 定时/cron 触发（intervalMinutes / cron）</li>
 *   <li>Skills — AI 根据 prompt 自动匹配可用技能</li>
 *   <li>Connectors — channelNotify 结果通知</li>
 *   <li>State — stateDir 持久状态目录 (.soloncode/loops/&lt;id&gt;/)</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
public class LoopTask {

    /**
     * 循环任务类型枚举
     */
    public enum TaskType {
        HEARTBEAT,   // 心跳循环（定时/cron 触发）
        GOAL         // 目标驱动模式（Goal）
    }

    private static final TaskType DEFAULT_TYPE = TaskType.HEARTBEAT;
    private static final int MIN_INTERVAL = 0; // 0 = 即时模式（goal 专用）
    private static final int MAX_INTERVAL = 1440; // 24h

    // ---- 核心调度字段 ----
    private final String id;
    private final String prompt;
    private final int intervalMinutes;
    private final String cron;
    private final Instant createdAt;
    private final boolean autoInterval;
    private final TaskType type;  // 任务类型（LOOP / GOAL），方便识别

    // ---- Loop Engineering 扩展字段 ----
    private GoalState goalState;             // Goal 状态模型（P0）
    private final boolean runNow;            // 注册后立即执行首次（initialDelay=0）
    private Long maxTokens;            // Token 预算（null = 不限制）
    private Long maxDurationMs;        // 时间预算毫秒（null = 不限制）

    // ---- 运行时状态 ----
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile String lastResult;
    private volatile Instant lastExecutedAt;
    private volatile int currentIteration;
    private volatile boolean enabled = true; // 启用/停用
    private volatile boolean wrapUpPending = false; // 即将收尾（最后一次 wrap-up turn）

    // ---- 运行时兜底：无进展检测 ----
    private volatile int stagnationCount;       // 连续无进展轮次
    private volatile String lastFingerprint;     // 上一轮执行指纹

    // ---- 运行时兜底：连续异常检测 ----
    private volatile int consecutiveErrors;      // 连续异常计数
    private volatile String lastErrorType;       // 最后一次错误分类（SSL/NETWORK/HTTP_4XX/HTTP_5XX/TOOL_EXECUTION/OTHER）
    private volatile String lastErrorSummary;    // 最后一次错误摘要（用于注入 prompt）
    private volatile int consecutiveSameTypeErrors; // 连续同类型错误计数

    /**
     * 固定间隔构造
     */
    public LoopTask(String prompt, int intervalMinutes) {
        this(prompt, intervalMinutes, null, null, false);
    }

    /**
     * cron 表达式构造
     */
    public LoopTask(String prompt, String cron) {
        this(prompt, 0, cron, null, false);
    }


    /**
     * 全参数构造（由 Builder、copyWithUpdate 调用）
     */
    LoopTask(String id, String prompt, int intervalMinutes, String cron,
                     Instant createdAt, boolean autoInterval,
                     boolean enabled,
                     boolean runNow, Long maxTokens, Long maxDurationMs,
                     boolean cancelled, String lastResult, Instant lastExecutedAt, int currentIteration,
                     TaskType type) {
        this.id = id;
        this.prompt = prompt;
        this.intervalMinutes = intervalMinutes;
        this.cron = cron;
        this.createdAt = createdAt;
        this.autoInterval = autoInterval;
        this.enabled = enabled;
        this.runNow = runNow;
        this.maxTokens = maxTokens;
        this.maxDurationMs = maxDurationMs;
        this.cancelled = cancelled;
        this.lastResult = lastResult;
        this.lastExecutedAt = lastExecutedAt;
        this.currentIteration = currentIteration;
        this.type = type != null ? type : DEFAULT_TYPE;

        // Goal 状态初始化：GOAL 类型自动构造 GoalState（使用 prompt 作为条件）
        if (type == TaskType.GOAL) {
            this.goalState = new GoalState(prompt,
                    maxTokens != null ? maxTokens : 0);
        }
    }

    /**
     * 便捷构造（固定间隔 + 扩展参数 + runNow）
     */
    public LoopTask(String prompt, int intervalMinutes, String cron,
                    TaskType type, boolean runNow) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.prompt = prompt;
        // 间隔钒在 [MIN_INTERVAL, MAX_INTERVAL]，不存在 0 间隔；goal 模式通过 fixedDelay 串行调度逐轮触发
        this.intervalMinutes = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, intervalMinutes));
        this.cron = cron;
        this.createdAt = Instant.now();
        this.autoInterval = false;
        this.runNow = runNow;
        this.currentIteration = 0;
        this.enabled = true;
        this.type = type != null ? type : TaskType.HEARTBEAT;

        if (this.type == TaskType.GOAL) {
            this.goalState = new GoalState(prompt, 0);
        }
    }

    /**
     * 基于当前任务复制出一份更新后的任务定义，保留任务身份和运行时状态。
     */
    public LoopTask copyWithUpdate(String prompt, int intervalMinutes, String cron,
                                    TaskType type,
                                    Boolean runNow,
                                    Long maxTokens, Long maxDurationMs) {
        // 使用新类型（如果提供），否则保留原类型
        TaskType newType = type != null ? type : this.type;

        LoopTask task = new LoopTask(
                this.id,
                prompt,
                Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, intervalMinutes)),
                cron,
                this.createdAt,
                this.autoInterval,
                this.enabled,
                runNow != null ? runNow : this.runNow,
                maxTokens != null ? maxTokens : this.maxTokens,
                maxDurationMs != null ? maxDurationMs : this.maxDurationMs,
                this.cancelled,
                this.lastResult,
                this.lastExecutedAt,
                this.currentIteration,
                newType
        );
        task.running = false;
        // ★ 保留原始 GoalState 运行时状态（consumedTokens、status、startEpochMs 等）
        //    同时更新 condition 和 maxTokens 以反映新 prompt 和预算
        if (this.goalState != null) {
            task.goalState = this.goalState;
            task.goalState.setCondition(task.getPrompt());
            if (task.getMaxTokens() != null) {
                task.goalState.setMaxTokens(task.getMaxTokens());
            }
        }
        return task;
    }

    /**
     * 是否为 cron 模式
     */
    public boolean isCronMode() {
        return cron != null && !cron.isEmpty();
    }

    /**
     * 是否仍处于活跃状态（未取消且未过期）
     */
    public boolean isActive() {
        return !cancelled;
    }

    /**
     * 尝试标记为运行中（CAS 语义）
     *
     * @return true 表示成功获取执行权
     */
    public synchronized boolean tryStart() {
        if (running) {
            return false;
        }
        running = true;
        return true;
    }

    /**
     * 标记执行结束
     */
    public synchronized void finish() {
        running = false;
    }

    /**
     * 获取 IJobManager 注册用的任务名称
     */
    public String getJobName() {
        return "loop-" + id;
    }

    /**
     * 取消任务
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * 更新最近一次执行信息
     */
    public void updateLastExecution(String result) {
        this.lastResult = result;
        this.lastExecutedAt = Instant.now();
    }

    /**
     * 递增迭代计数
     *
     * @return 递增后的当前迭代次数
     */
    public int incrementIteration() {
        return ++currentIteration;
    }


    /**
     * 是否为 goal 模式（有目标条件定义）
     */
    public boolean isGoalMode() {
        return goalState != null;
    }

    /**
     * 获取 GoalState（可能为 null）
     */
    public GoalState getGoalState() {
        return goalState;
    }

    /**
     * 设置 GoalState（用于恢复/测试）
     */
    public void setGoalState(GoalState goalState) {
        this.goalState = goalState;
    }

    public void setMaxTokens(Long maxTokens) {
        this.maxTokens = maxTokens;
        // 同步到 GoalState（便捷构造函数中 GoalState 用的是 0，需在此补同步）
        if (goalState != null && maxTokens != null) {
            goalState.setMaxTokens(maxTokens);
        }
    }

    public void setMaxDurationMs(Long maxDurationMs) { this.maxDurationMs = maxDurationMs; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void setWrapUpPending(boolean wrapUpPending) { this.wrapUpPending = wrapUpPending; }

    public boolean isWrapUpPending() { return wrapUpPending; }

    public void setLastResult(String lastResult) { this.lastResult = lastResult; }

    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }

    // ===== 无进展检测 =====

    public int getStagnationCount() { return stagnationCount; }

    public void recordStagnation() { this.stagnationCount++; }

    public void resetStagnation() { this.stagnationCount = 0; }

    public String getLastFingerprint() { return lastFingerprint; }

    public void setLastFingerprint(String fingerprint) { this.lastFingerprint = fingerprint; }

    // ===== 连续异常检测 =====

    public int getConsecutiveErrors() { return consecutiveErrors; }

    public int incrementConsecutiveErrors() { return ++consecutiveErrors; }

    public void resetConsecutiveErrors() {
        this.consecutiveErrors = 0;
        this.consecutiveSameTypeErrors = 0;
        this.lastErrorType = null;
        this.lastErrorSummary = null;
    }

    // ===== 错误分类与同类型熔断 =====

    public String getLastErrorType() { return lastErrorType; }

    public String getLastErrorSummary() { return lastErrorSummary; }

    public int getConsecutiveSameTypeErrors() { return consecutiveSameTypeErrors; }

    /**
     * 记录一次错误，更新错误分类和连续同类型错误计数。
     *
     * @return 连续同类型错误次数
     */
    public int recordError(Exception e) {
        String errorType = classifyError(e);
        String errorSummary = extractErrorSummary(e);

        if (errorType.equals(this.lastErrorType)) {
            this.consecutiveSameTypeErrors++;
        } else {
            this.consecutiveSameTypeErrors = 1;
        }

        this.lastErrorType = errorType;
        this.lastErrorSummary = errorSummary;
        return this.consecutiveSameTypeErrors;
    }

    /**
     * 对异常进行分类，用于判断是否为不可恢复的错误类型。
     */
    public static String classifyError(Exception e) {
        String msg = getFullMessage(e);

        // SSL/TLS 证书问题 — 不可通过重试解决
        if (msg.contains("SSLHandshakeException")
                || msg.contains("PKIX")
                || msg.contains("certificate")
                || msg.contains("trust")
                || msg.contains("unable to find valid certification")) {
            return "SSL";
        }

        // 网络连接问题 — 可能是临时性的
        if (msg.contains("ConnectException")
                || msg.contains("Connection refused")
                || msg.contains("Connection reset")
                || msg.contains("SocketTimeoutException")
                || msg.contains("connect timed out")
                || msg.contains("Read timed out")) {
            return "NETWORK";
        }

        // HTTP 4xx 客户端错误 — 通常不可通过重试解决
        if (msg.contains("HTTP 4") || msg.contains("status code: 4")
                || msg.contains("400") || msg.contains("401") || msg.contains("403")
                || msg.contains("404") || msg.contains("405") || msg.contains("429")) {
            return "HTTP_4XX";
        }

        // HTTP 5xx 服务端错误 — 可能是临时性的
        if (msg.contains("HTTP 5") || msg.contains("status code: 5")
                || msg.contains("500") || msg.contains("502") || msg.contains("503")
                || msg.contains("504")) {
            return "HTTP_5XX";
        }

        // 工具执行失败（Agent 工具调用层面的错误）
        if (msg.contains("execution failed")
                || msg.contains("MethodFunctionTool")
                || msg.contains("ActionTask")) {
            return "TOOL_EXECUTION";
        }

        return "OTHER";
    }

    /**
     * 提取错误摘要（用于注入 prompt，限制长度）
     */
    public static String extractErrorSummary(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        // 限制长度，避免 prompt 过长
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return msg;
    }

    /**
     * 判断错误类型是否为不可恢复的（重试无意义）
     */
    public static boolean isNonRecoverable(String errorType) {
        return "SSL".equals(errorType) || "HTTP_4XX".equals(errorType);
    }

    private static String getFullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(" ");
            }
            sb.append(current.getClass().getSimpleName()).append(" ");
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * 序列化为 ONode
     */
    public ONode toONode() {
        ONode node = new ONode();
        // 核心调度字段
        node.set("id", id);
        node.set("prompt", prompt);
        node.set("intervalMinutes", intervalMinutes);
        if (cron != null) {
            node.set("cron", cron);
        }
        node.set("createdAt", createdAt.toString());
        node.set("autoInterval", autoInterval);
        node.set("cancelled", cancelled);
        node.set("enabled", enabled);
        // running 是瞬态运行时锁，不持久化 — restore() 时兜底解锁
        node.set("type", type.name());

        // 运行时状态
        if (lastResult != null) {
            node.set("lastResult", lastResult);
        }
        if (lastExecutedAt != null) {
            node.set("lastExecutedAt", lastExecutedAt.toString());
        }
        node.set("currentIteration", currentIteration);

        // Loop Engineering 扩展字段

        if (runNow) node.set("runNow", true);

        // ★ P0: 写入 GoalState
        if (goalState != null) {
            node.set("goalState", goalState.toONode());
        }

        // ★ P1: 预算字段
        if (maxTokens != null) node.set("maxTokens", maxTokens);
        if (maxDurationMs != null) node.set("maxDurationMs", maxDurationMs);

        // ★ 运行时兜底字段（持久化以支持重启恢复）
        if (stagnationCount > 0) node.set("stagnationCount", stagnationCount);
        if (consecutiveErrors > 0) node.set("consecutiveErrors", consecutiveErrors);

        return node;
    }

    /**
     * 从 ONode 反序列化（向后兼容：缺失字段给默认值）
     */
    public static LoopTask fromONode(ONode node) {
        String lastResultVal = node.getOrNull("lastResult") != null
                ? node.get("lastResult").getString()
                : null;
        Instant lastExecutedAtVal = node.getOrNull("lastExecutedAt") != null
                ? Instant.parse(node.get("lastExecutedAt").getString())
                : null;
        String cronVal = node.getOrNull("cron") != null
                ? node.get("cron").getString()
                : null;

        // 向后兼容：缺失时给默认值
        boolean enabledVal = node.getOrNull("enabled") != null
                ? node.get("enabled").getBoolean() : true;

        int currentIterationVal = node.getOrNull("currentIteration") != null
                ? node.get("currentIteration").getInt() : 0;

        boolean runNowVal = node.getOrNull("runNow") != null
                && node.get("runNow").getBoolean();

        // ★ P1: 读取预算字段
        Long maxTokensVal = node.getOrNull("maxTokens") != null
                ? (long) node.get("maxTokens").getInt() : null;
        Long maxDurationMsVal = node.getOrNull("maxDurationMs") != null
                ? (long) node.get("maxDurationMs").getInt() : null;

        // ★ 读取运行时兜底字段
        int stagnationCountVal = node.getOrNull("stagnationCount") != null
                ? node.get("stagnationCount").getInt() : 0;
        int consecutiveErrorsVal = node.getOrNull("consecutiveErrors") != null
                ? node.get("consecutiveErrors").getInt() : 0;

        // 读取 TaskType（默认 HEARTBEAT）
        TaskType typeVal = node.getOrNull("type") != null
                ? TaskType.valueOf(node.get("type").getString())
                : TaskType.HEARTBEAT;

        // 读取 GoalState
        GoalState goalStateVal = node.getOrNull("goalState") != null
                ? GoalState.fromONode(node.get("goalState"))
                : null;

        LoopTask task = new LoopTask(
                node.get("id").getString(),
                node.get("prompt").getString(),
                node.get("intervalMinutes").getInt(),
                cronVal,
                Instant.parse(node.get("createdAt").getString()),
                node.get("autoInterval").getBoolean(),
                enabledVal,
                runNowVal,
                maxTokensVal,
                maxDurationMsVal,
                node.getOrNull("cancelled") != null
                        ? node.get("cancelled").getBoolean() : false,
                lastResultVal,
                lastExecutedAtVal,
                currentIterationVal,
                typeVal
        );

        // 覆盖构造函数中自动创建的 GoalState（保留 JSON 中的完整状态）
        if (goalStateVal != null) {
            task.goalState = goalStateVal;
        }

        // 恢复运行时兜底字段
        task.stagnationCount = stagnationCountVal;
        task.consecutiveErrors = consecutiveErrorsVal;

        // running 是瞬态锁，反序列化后始终为 false
        // （kill -9 场景兜底：若有 future 的注册逻辑需显式调用 finish()）

        return task;
    }
}
