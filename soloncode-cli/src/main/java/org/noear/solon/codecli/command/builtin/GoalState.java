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
 * Goal 状态模型 — 100% Codex CLI 对齐，无任何冗余。
 *
 * <p>4 态精简状态机：
 * PURSUING ⇄ PAUSED → ACHIEVED | BUDGET_LIMITED</p>
 *
 * <p>含 blockedCycleCount 电路熔断（防止 blocked→resume 无限循环）。</p>
 */
public class GoalState {

    public enum Status {
        PURSUING, PAUSED, ACHIEVED, BUDGET_LIMITED;

        public boolean isActive() { return this == PURSUING; }
        public boolean isTerminal() { return this == ACHIEVED || this == BUDGET_LIMITED; }
    }

    private String id;
    private String condition;
    private Status status;
    private long consumedTokens;
    private long maxTokens;
    private long startEpochMs;
    private int blockedCycleCount;          // 电路熔断：累计 blocked 次数

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
        if (status != Status.PAUSED) return false;
        this.status = Status.PURSUING;
        return true;
    }

    public void achieve() {
        if (status == Status.PURSUING) this.status = Status.ACHIEVED;
    }

    public void markBudgetLimited() {
        if (status == Status.PURSUING) this.status = Status.BUDGET_LIMITED;
    }

    // ===== 电路熔断 =====

    static final int MAX_BLOCKED_CYCLES = 5;

    public boolean isBlockedCycleExhausted() {
        return blockedCycleCount >= MAX_BLOCKED_CYCLES;
    }

    public void incrementBlockedCycleCount() {
        blockedCycleCount++;
    }

    public int getBlockedCycleCount() { return blockedCycleCount; }

    public void addTokens(long tokens) {
        this.consumedTokens += tokens;
    }

    public boolean isBudgetExceeded() {
        return maxTokens > 0 && consumedTokens >= maxTokens;
    }

    public boolean isBudgetCritical() {
        return maxTokens > 0 && (double) consumedTokens / maxTokens >= 0.8;
    }

    // ===== Getters =====

    public String getId() { return id; }
    public String getCondition() { return condition; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getConsumedTokens() { return consumedTokens; }
    public long getMaxTokens() { return maxTokens; }
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
        if (blockedCycleCount > 0) node.set("blockedCycleCount", blockedCycleCount);
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
        gs.blockedCycleCount = node.getOrNull("blockedCycleCount") != null
                ? node.get("blockedCycleCount").getInt() : 0;
        return gs;
    }
}
