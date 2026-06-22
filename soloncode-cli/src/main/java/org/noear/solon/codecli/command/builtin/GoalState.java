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

import org.noear.snack4.ONode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Goal 状态模型 — 绑定到 LoopTask
 *
 * <p>管理目标条件的全生命周期状态，包括评估历史、预算控制。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class GoalState {
    private static final int MAX_HISTORY_SIZE = 50;

    private final String condition;                    // 目标条件原文
    private GoalStatus status;                         // 当前状态
    private int currentIteration;                      // 当前迭代次数
    private int maxIterations;                         // 最大迭代次数
    private String lastEvaluationReason;               // 最近一次评估原因
    private final List<GoalEvaluation> history;         // 评估历史
    private Instant startedAt;                         // 开始时间
    private Instant lastEvaluatedAt;                   // 最近评估时间
    private long consumedTokens;                       // 已消耗 token（P1）
    private Long maxTokens;                            // token 预算（null=不限制，P1）
    private Long maxDurationMs;                        // 时间预算毫秒（null=不限制，P1）

    // ===== 构造 =====

    public GoalState(String condition, GoalStatus status, int maxIterations) {
        this.condition = condition;
        this.status = status;
        this.currentIteration = 0;
        this.maxIterations = maxIterations;
        this.lastEvaluationReason = null;
        this.history = Collections.synchronizedList(new ArrayList<>());
        this.startedAt = status == GoalStatus.CREATING || status == GoalStatus.PURSUING
                ? Instant.now() : null;
        this.lastEvaluatedAt = null;
        this.consumedTokens = 0;
        this.maxTokens = null;
        this.maxDurationMs = null;
    }

    // ===== 状态转换 =====

    /**
     * 标记任务开始执行（CREATING → PURSUING）
     */
    public synchronized void start() {
        if (status == GoalStatus.CREATING) {
            this.status = GoalStatus.PURSUING;
            this.startedAt = Instant.now();
        }
    }

    /**
     * 暂停（PURSUING → PAUSED）
     */
    public synchronized boolean pause() {
        if (status == GoalStatus.PURSUING) {
            this.status = GoalStatus.PAUSED;
            return true;
        }
        return false;
    }

    /**
     * 恢复（PAUSED/BLOCKED → PURSUING）
     */
    public synchronized boolean resume() {
        if (status == GoalStatus.PAUSED || status == GoalStatus.BLOCKED) {
            this.status = GoalStatus.PURSUING;
            return true;
        }
        return false;
    }

    /**
     * 标记达成
     */
    public synchronized void achieve() {
        if (status == GoalStatus.PURSUING) {
            this.status = GoalStatus.ACHIEVED;
        }
    }

    /**
     * 标记不可达
     */
    public synchronized void markUnmet(String reason) {
        if (status == GoalStatus.PURSUING) {
            this.status = GoalStatus.UNMET;
            this.lastEvaluationReason = reason;
        }
    }

    /**
     * 标记预算耗尽
     */
    public synchronized void markBudgetLimited() {
        if (status == GoalStatus.PURSUING) {
            this.status = GoalStatus.BUDGET_LIMITED;
        }
    }

    /**
     * 标记阻塞（PURSUING → BLOCKED）
     * 当模型声明 [GOAL_BLOCKED] 时调用
     */
    public synchronized boolean block(String reason) {
        if (status == GoalStatus.PURSUING) {
            this.status = GoalStatus.BLOCKED;
            this.lastEvaluationReason = reason;
            return true;
        }
        return false;
    }

    /**
     * 返回当前是否可进入阻塞状态
     */
    public boolean isBlockable() {
        return status == GoalStatus.PURSUING;
    }

    /**
     * 终结
     */
    public synchronized void terminate() {
        this.status = GoalStatus.TERMINATED;
    }

    // ===== 评估相关 =====

    /**
     * 添加一条评估记录
     */
    public synchronized void addEvaluation(GoalEvaluation eval) {
        synchronized (history) {
            history.add(eval);
            if (history.size() > MAX_HISTORY_SIZE) {
                history.remove(0);
            }
        }
        this.lastEvaluationReason = eval.getReason();
        this.lastEvaluatedAt = Instant.now();
        this.currentIteration = eval.getIteration();
    }

    /**
     * 获取评估历史（不可变视图）
     */
    public List<GoalEvaluation> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    // ===== 预算相关（P1） =====

    /**
     * 是否超出预算
     */
    public boolean isBudgetExceeded() {
        if (maxIterations > 0 && currentIteration >= maxIterations) {
            return true;
        }
        if (maxTokens != null && consumedTokens >= maxTokens) {
            return true;
        }
        if (maxDurationMs != null && startedAt != null) {
            long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
            if (elapsed >= maxDurationMs) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否即将达到预算临界（剩余 20% 以内）
     */
    public boolean isBudgetCritical() {
        // 迭代预算临界
        if (maxIterations > 0) {
            double ratio = (double) currentIteration / maxIterations;
            if (ratio >= 0.8) return true;
        }
        // token 预算临界
        if (maxTokens != null && maxTokens > 0) {
            double ratio = (double) consumedTokens / maxTokens;
            if (ratio >= 0.8) return true;
        }
        // 时间预算临界
        if (maxDurationMs != null && maxDurationMs > 0 && startedAt != null) {
            long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
            double ratio = (double) elapsed / maxDurationMs;
            if (ratio >= 0.8) return true;
        }
        return false;
    }

    /**
     * 消耗 token（P1）
     */
    public void addTokens(long tokens) {
        this.consumedTokens += tokens;
    }

    // ===== Getter 方法（供 LoopTask 委托调用） =====

    public String getCondition() {
        return condition;
    }

    public GoalStatus getStatus() {
        return status;
    }

    /**
     * 设置状态（供状态机内部使用）
     */
    public void setStatus(GoalStatus status) {
        this.status = status;
    }

    public int getCurrentIteration() {
        return currentIteration;
    }

    public void setCurrentIteration(int iteration) {
        this.currentIteration = iteration;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public String getLastEvaluationReason() {
        return lastEvaluationReason;
    }

    public void setLastEvaluationReason(String reason) {
        this.lastEvaluationReason = reason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public long getConsumedTokens() {
        return consumedTokens;
    }

    public Long getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Long maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Long getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(Long maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }

    // ===== 序列化 =====

    public ONode toONode() {
        ONode node = new ONode();
        node.set("condition", condition);
        node.set("status", status.name());
        node.set("currentIteration", currentIteration);
        node.set("maxIterations", maxIterations);
        if (lastEvaluationReason != null) {
            node.set("lastEvaluationReason", lastEvaluationReason);
        }
        if (startedAt != null) {
            node.set("startedAt", startedAt.toString());
        }
        if (lastEvaluatedAt != null) {
            node.set("lastEvaluatedAt", lastEvaluatedAt.toString());
        }
        node.set("consumedTokens", consumedTokens);
        if (maxTokens != null) {
            node.set("maxTokens", maxTokens);
        }
        if (maxDurationMs != null) {
            node.set("maxDurationMs", maxDurationMs);
        }

        // 历史记录
        synchronized (history) {
            if (!history.isEmpty()) {
                ONode historyArray = new ONode();
                for (GoalEvaluation eval : history) {
                    historyArray.add(eval.toONode());
                }
                node.set("history", historyArray);
            }
        }

        return node;
    }

    public static GoalState fromONode(ONode node) {
        String condition = node.get("condition").getString();
        GoalStatus status = GoalStatus.valueOf(node.get("status").getString());
        int maxIterations = node.getOrNull("maxIterations") != null
                ? node.get("maxIterations").getInt() : 20;

        GoalState gs = new GoalState(condition, status, maxIterations);

        gs.currentIteration = node.getOrNull("currentIteration") != null
                ? node.get("currentIteration").getInt() : 0;
        gs.lastEvaluationReason = node.getOrNull("lastEvaluationReason") != null
                ? node.get("lastEvaluationReason").getString() : null;
        gs.startedAt = node.getOrNull("startedAt") != null
                ? Instant.parse(node.get("startedAt").getString()) : null;
        gs.lastEvaluatedAt = node.getOrNull("lastEvaluatedAt") != null
                ? Instant.parse(node.get("lastEvaluatedAt").getString()) : null;
        gs.consumedTokens = node.getOrNull("consumedTokens") != null
                ? node.get("consumedTokens").getLong() : 0;
        gs.maxTokens = node.getOrNull("maxTokens") != null
                ? node.get("maxTokens").getLong() : null;
        gs.maxDurationMs = node.getOrNull("maxDurationMs") != null
                ? node.get("maxDurationMs").getLong() : null;

        // 历史记录
        if (node.getOrNull("history") != null && node.get("history").isArray()) {
            for (ONode evalNode : node.get("history").getArray()) {
                gs.history.add(GoalEvaluation.fromONode(evalNode));
            }
        }

        return gs;
    }
}
