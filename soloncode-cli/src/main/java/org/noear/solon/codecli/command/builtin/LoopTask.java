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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.noear.solon.codecli.command.builtin.GoalStatus.*;

/**
 * 定时循环任务模型，用于 /loop 命令
 *
 * <p>支持 Loop Engineering 的 6 个基元：
 * <ul>
 *   <li>Automations — 定时/cron 触发（intervalMinutes / cron）</li>
 *   <li>Skills — AI 根据 prompt 自动匹配可用技能</li>
 *   <li>Worktrees — worktreeEnabled 在独立分支执行</li>
 *   <li>Connectors — channelNotify 结果通知</li>
 *   <li>State — stateDir 持久状态目录 (.soloncode/loops/&lt;id&gt;/)</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
public class LoopTask {
    private static final int MIN_INTERVAL = 0; // 0 = 即时模式（goal 专用）
    private static final int MAX_INTERVAL = 1440; // 24h
    private static final int EXPIRE_DAYS = 7;
    private static final int DEFAULT_MAX_ITERATIONS = 20;

    // ---- 核心调度字段 ----
    private final String id;
    private final String prompt;
    private final int intervalMinutes;
    private final String cron;
    private final Instant createdAt;
    private final Instant expireAt;
    private final boolean autoInterval;

    // ---- Loop Engineering 扩展字段 ----
    private final String goalCondition;      // 目标条件，如 "all tests pass"（保留向后兼容）
    private GoalState goalState;             // 新增：Goal 状态模型（P0）
    private final boolean worktreeEnabled;   // 是否在独立 worktree 中执行
    private final String worktreeBranch;     // worktree 分支名（运行时分配）
    private final int maxIterations;         // 最大迭代次数
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

    /**
     * 固定间隔构造
     */
    public LoopTask(String prompt, int intervalMinutes) {
        this(prompt, intervalMinutes, null, null, false, null);
    }

    /**
     * cron 表达式构造
     */
    public LoopTask(String prompt, String cron) {
        this(prompt, 0, cron, null, false, null);
    }


    /**
     * 全参数构造（由 Builder、copyWithUpdate 调用）
     */
    LoopTask(String id, String prompt, int intervalMinutes, String cron,
                     Instant createdAt, Instant expireAt, boolean autoInterval,
                     boolean enabled,
                     String goalCondition, boolean worktreeEnabled, String worktreeBranch,
                     int maxIterations, boolean runNow, Long maxTokens, Long maxDurationMs,
                     boolean cancelled, String lastResult, Instant lastExecutedAt, int currentIteration) {
        this.id = id;
        this.prompt = prompt;
        this.intervalMinutes = intervalMinutes;
        this.cron = cron;
        this.createdAt = createdAt;
        this.expireAt = expireAt;
        this.autoInterval = autoInterval;
        this.enabled = enabled;
        this.goalCondition = goalCondition;
        this.worktreeEnabled = worktreeEnabled;
        this.worktreeBranch = worktreeBranch;
        this.maxIterations = maxIterations;
        this.runNow = runNow;
        this.maxTokens = maxTokens;
        this.maxDurationMs = maxDurationMs;
        this.cancelled = cancelled;
        this.lastResult = lastResult;
        this.lastExecutedAt = lastExecutedAt;
        this.currentIteration = currentIteration;

        // Goal 状态初始化：如果存在 goalCondition 但无 goalState，自动构造
        if (goalCondition != null && !goalCondition.isEmpty()) {
            this.goalState = new GoalState(goalCondition, PURSUING, maxIterations);
        }
    }

    /**
     * 便捷构造（固定间隔 + 扩展参数）
     */
    public LoopTask(String prompt, int intervalMinutes, String cron,
                    String goalCondition, Boolean worktreeEnabled,
                    Integer maxIterations) {
        this(prompt, intervalMinutes, cron, goalCondition, worktreeEnabled, maxIterations, false);
    }

    /**
     * 便捷构造（固定间隔 + 扩展参数 + runNow）
     */
    public LoopTask(String prompt, int intervalMinutes, String cron,
                    String goalCondition, Boolean worktreeEnabled,
                    Integer maxIterations, boolean runNow) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.prompt = prompt;
        // 间隔钒在 [MIN_INTERVAL, MAX_INTERVAL]，不存在 0 间隔；goal 模式通过 fixedDelay 串行调度逐轮触发
        this.intervalMinutes = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, intervalMinutes));
        this.cron = cron;
        this.createdAt = Instant.now();
        this.expireAt = createdAt.plus(EXPIRE_DAYS, ChronoUnit.DAYS);
        this.autoInterval = false;
        this.goalCondition = goalCondition;
        this.worktreeEnabled = worktreeEnabled != null ? worktreeEnabled : false;
        this.worktreeBranch = null; // 运行时分配
        this.maxIterations = maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS;
        this.runNow = runNow;
        this.currentIteration = 0;
        this.enabled = true;

        // Goal 状态初始化
        if (goalCondition != null && !goalCondition.isEmpty()) {
            this.goalState = new GoalState(goalCondition, PURSUING, this.maxIterations);
        }
    }

    /**
     * 基于当前任务复制出一份更新后的任务定义，保留任务身份和运行时状态。
     */
    public LoopTask copyWithUpdate(String prompt, int intervalMinutes, String cron,
                                    String goalCondition, Boolean worktreeEnabled,
                                    Integer maxIterations, Boolean runNow,
                                    Long maxTokens, Long maxDurationMs) {
        LoopTask task = new LoopTask(
                this.id,
                prompt,
                Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, intervalMinutes)),
                cron,
                this.createdAt,
                this.expireAt,
                this.autoInterval,
                this.enabled,
                goalCondition,
                worktreeEnabled != null ? worktreeEnabled : false,
                this.worktreeBranch,
                maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS,
                runNow != null ? runNow : this.runNow,
                maxTokens != null ? maxTokens : this.maxTokens,
                maxDurationMs != null ? maxDurationMs : this.maxDurationMs,
                this.cancelled,
                this.lastResult,
                this.lastExecutedAt,
                this.currentIteration
        );
        task.running = false;
        return task;
    }

    /**
     * 是否已过期
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expireAt);
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
        return !cancelled && !isExpired();
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
     * 是否已达到最大迭代次数
     */
    public boolean isMaxIterationsReached() {
        return maxIterations > 0 && currentIteration >= maxIterations;
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

    public void setMaxTokens(Long maxTokens) { this.maxTokens = maxTokens; }
    public void setMaxDurationMs(Long maxDurationMs) { this.maxDurationMs = maxDurationMs; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void setWrapUpPending(boolean wrapUpPending) { this.wrapUpPending = wrapUpPending; }

    public boolean isWrapUpPending() { return wrapUpPending; }

    public void setLastResult(String lastResult) { this.lastResult = lastResult; }

    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }

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
        node.set("expireAt", expireAt.toString());
        node.set("autoInterval", autoInterval);
        node.set("cancelled", cancelled);
        node.set("running", running);
        node.set("enabled", enabled);

        // 运行时状态
        if (lastResult != null) {
            node.set("lastResult", lastResult);
        }
        if (lastExecutedAt != null) {
            node.set("lastExecutedAt", lastExecutedAt.toString());
        }
        node.set("currentIteration", currentIteration);

        // Loop Engineering 扩展字段
        if (goalCondition != null) node.set("goalCondition", goalCondition);
        if (worktreeEnabled) node.set("worktreeEnabled", worktreeEnabled);
        if (worktreeBranch != null) node.set("worktreeBranch", worktreeBranch);

        if (maxIterations != DEFAULT_MAX_ITERATIONS) node.set("maxIterations", maxIterations);
        if (runNow) node.set("runNow", true);

        // ★ P0: 写入 GoalState
        if (goalState != null) {
            node.set("goalState", goalState.toONode());
        }

        // ★ P1: 预算字段
        if (maxTokens != null) node.set("maxTokens", maxTokens);
        if (maxDurationMs != null) node.set("maxDurationMs", maxDurationMs);

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

        String goalConditionVal = node.getOrNull("goalCondition") != null
                ? node.get("goalCondition").getString() : null;
        boolean worktreeEnabledVal = node.getOrNull("worktreeEnabled") != null
                && node.get("worktreeEnabled").getBoolean();
        String worktreeBranchVal = node.getOrNull("worktreeBranch") != null
                ? node.get("worktreeBranch").getString() : null;
        int maxIterationsVal = node.getOrNull("maxIterations") != null
                ? node.get("maxIterations").getInt() : DEFAULT_MAX_ITERATIONS;
        int currentIterationVal = node.getOrNull("currentIteration") != null
                ? node.get("currentIteration").getInt() : 0;

        boolean runNowVal = node.getOrNull("runNow") != null
                && node.get("runNow").getBoolean();

        // ★ P1: 读取预算字段
        Long maxTokensVal = node.getOrNull("maxTokens") != null
                ? (long) node.get("maxTokens").getInt() : null;
        Long maxDurationMsVal = node.getOrNull("maxDurationMs") != null
                ? (long) node.get("maxDurationMs").getInt() : null;

        // 读取 GoalState（新格式）
        GoalState goalStateVal = null;
        if (node.getOrNull("goalState") != null) {
            goalStateVal = GoalState.fromONode(node.get("goalState"));
        } else if (goalConditionVal != null) {
            // 向后兼容：只有 goalCondition 字符串，自动构造 GoalState
            goalStateVal = new GoalState(goalConditionVal, GoalStatus.PURSUING, maxIterationsVal);
            goalStateVal.setCurrentIteration(currentIterationVal);
        }

        LoopTask task = new LoopTask(
                node.get("id").getString(),
                node.get("prompt").getString(),
                node.get("intervalMinutes").getInt(),
                cronVal,
                Instant.parse(node.get("createdAt").getString()),
                Instant.parse(node.get("expireAt").getString()),
                node.get("autoInterval").getBoolean(),
                enabledVal,
                goalConditionVal,
                worktreeEnabledVal,
                worktreeBranchVal,
                maxIterationsVal,
                runNowVal,
                maxTokensVal,
                maxDurationMsVal,
                node.getOrNull("cancelled") != null
                        ? node.get("cancelled").getBoolean() : false,
                lastResultVal,
                lastExecutedAtVal,
                currentIterationVal
        );

        // 覆盖构造函数中自动创建的 GoalState（保留 JSON 中的完整状态）
        if (goalStateVal != null) {
            task.goalState = goalStateVal;
        }

        return task;
    }
}
