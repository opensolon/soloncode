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
import java.util.UUID;

/**
 * Goal 状态模型 — Codex CLI 对齐。
 *
 * <p>5 态状态机：
 * <pre>
 *   PURSUING ⇄ PAUSED → ACHIEVED | BUDGET_LIMITED
 *       ↓
 *     BLOCKED → (resume) → PURSUING
 * </pre>
 * BLOCKED 是模型主动声明的阻塞（通过 update_goal("blocked")），可与 PAUSED 区分。
 */
public class GoalState {

    public enum Status {
        PURSUING, PAUSED, BLOCKED, ACHIEVED, BUDGET_LIMITED;

        public boolean isActive() { return this == PURSUING; }
        public boolean isTerminal() { return this == ACHIEVED || this == BUDGET_LIMITED; }
        public boolean isResumable() { return this == PAUSED || this == BLOCKED; }
    }

    private String id;
    private String condition;
    private Status status;
    private long consumedTokens;
    private long maxTokens;
    private long startEpochMs;

    // ===== 静态配置（由 LoopScheduler 在启动时通过 configure() 设置） =====
    private static volatile int budgetWarningPercent = 70;
    private static volatile int budgetCriticalPercent = 85;

    public static void configure(int warningPct, int criticalPct) {
        budgetWarningPercent = warningPct;
        budgetCriticalPercent = criticalPct;
    }

    public GoalState(String condition, long maxTokens) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.condition = condition;
        this.status = Status.PURSUING;
        this.consumedTokens = 0;
        this.maxTokens = maxTokens;
        this.startEpochMs = System.currentTimeMillis();
    }

    private GoalState() {} // for deserialization

    public boolean pause() {
        if (status != Status.PURSUING) return false;
        this.status = Status.PAUSED;
        return true;
    }

    public boolean resume() {
        if (!status.isResumable()) return false;
        this.status = Status.PURSUING;
        return true;
    }

    public void achieve() {
        if (status == Status.PURSUING) this.status = Status.ACHIEVED;
    }

    public void markBudgetLimited() {
        if (status == Status.PURSUING) this.status = Status.BUDGET_LIMITED;
    }

    /** 模型主动声明阻塞（PURSUING → BLOCKED） */
    public void markBlocked() {
        if (status == Status.PURSUING) {
            this.status = Status.BLOCKED;
        }
    }

    public void addTokens(long tokens) {
        this.consumedTokens += tokens;
    }

    public boolean isBudgetExceeded() {
        return maxTokens > 0 && consumedTokens >= maxTokens;
    }

    public boolean isBudgetCritical() {
        if (maxTokens <= 0) return false;
        double threshold = budgetCriticalPercent / 100.0;
        return (double) consumedTokens / maxTokens >= threshold;
    }

    public boolean isBudgetWarning() {
        if (maxTokens <= 0) return false;
        if (isBudgetCritical()) return false;
        double threshold = budgetWarningPercent / 100.0;
        return (double) consumedTokens / maxTokens >= threshold;
    }

    // ===== 预算扩容 =====

    public void extendBudget(long additionalTokens) {
        if (additionalTokens <= 0) return;
        this.maxTokens += additionalTokens;
        if (status == Status.BUDGET_LIMITED) {
            this.status = Status.PURSUING;
        }
    }

    // ===== Getters =====

    public String getId() { return id; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getConsumedTokens() { return consumedTokens; }
    public long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
    public long getStartEpochMs() { return startEpochMs; }

    // ===== 序列化 =====

    public ONode toONode() {
        ONode node = new ONode();
        node.set("id", id);
        node.set("condition", condition);
        node.set("status", status.name());
        node.set("consumedTokens", consumedTokens);
        node.set("maxTokens", maxTokens);
        node.set("startEpochMs", startEpochMs);
        return node;
    }

    public static GoalState fromONode(ONode node) {
        GoalState gs = new GoalState();
        gs.id = node.get("id").getString();
        gs.condition = node.get("condition").getString();
        gs.status = Status.valueOf(node.get("status").getString());
        gs.consumedTokens = node.get("consumedTokens").getLong();
        gs.maxTokens = node.get("maxTokens").getLong();
        gs.startEpochMs = node.get("startEpochMs").getLong();
        return gs;
    }
}
