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
import org.noear.snack4.Options;
import org.noear.snack4.Feature;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.annotation.Param;

import java.time.Duration;
import java.time.Instant;

/**
 * Goal 工具 — 模型侧 Goal 生命周期控制（100% Codex CLI 对齐）
 *
 * <p>提供三个工具：
 * <ul>
 *   <li>{@code create_goal} — 创建新 goal（参数：objective + token_budget）</li>
 *   <li>{@code get_goal} — 查询当前 goal 状态，无 goal 返回 null</li>
 *   <li>{@code update_goal} — 仅标记 complete（blocked 由运行时自动检测）</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.3
 */
public class GoalTool extends AbsTalent {

    private final LoopScheduler scheduler;
    private volatile String currentSessionId; // 由 LoopCommand 每次执行前同步

    public GoalTool(LoopScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 设置当前会话 ID（由 LoopCommand 在 execute 时同步）
     */
    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    /**
     * 获取当前会话 ID：优先使用显式设置的，fallback 到 scheduler 推断
     */
    private String resolveSessionId() {
        if (currentSessionId != null) return currentSessionId;
        return scheduler.getFirstSessionId();
    }

    /**
     * 创建新的 Goal（Codex 签名对齐：objective + token_budget）
     */
    @ToolMapping(name = "create_goal",
            description = "Create a new goal to guide the conversation towards a specific objective. " +
                    "Every conversation can have at most one active goal at a time. " +
                    "If there's already an active goal, this will fail.")
    public String createGoal(
            @Param(name = "objective", description = "The objective to achieve") String objective,
            @Param(name = "token_budget", description = "Token budget limit (optional)", required = false) Long tokenBudget) {

        if (objective == null || objective.isEmpty()) {
            return "ERROR: objective is required";
        }

        String sessionId = resolveSessionId();
        if (sessionId == null) {
            return "ERROR: no active session found";
        }

        LoopTask existing = scheduler.findActiveGoalAcrossSessions();
        if (existing != null) {
            GoalState gs = existing.getGoalState();
            return "ERROR: a goal is already active (" + existing.getId()
                    + ": " + gs.getCondition()
                    + "). Use update_goal or complete it first.";
        }

        LoopTask task = new LoopTask(objective, 0, null,
                objective, false, 0, true);

        long maxTokens = tokenBudget != null ? tokenBudget : 0;
        task.setMaxTokens(maxTokens);

        try {
            scheduler.schedule(sessionId, task);
            GoalState gs = task.getGoalState();

            return "OK: goal created — taskId='" + task.getId()
                    + "', objective='" + objective
                    + "'. Use get_goal to check status.";
        } catch (Exception e) {
            return "ERROR: failed to create goal — " + e.getMessage();
        }
    }

    /**
     * 获取当前 goal 状态（无 goal 时返回 null，Codex 对齐）
     */
    @ToolMapping(name = "get_goal",
            description = "Get the status of the current active goal, or null if no active goal.")
    public String getGoal() {
        LoopTask task = scheduler.findActiveGoalAcrossSessions();
        if (task == null) {
            return "null";
        }

        GoalState gs = task.getGoalState();

        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
        root.set("taskId", task.getId());
        root.set("objective", gs.getCondition());
        root.set("status", gs.getStatus().name().toLowerCase());
        root.set("iteration", task.getCurrentIteration());

        long elapsed = (System.currentTimeMillis() - gs.getStartEpochMs()) / 1000;
        root.set("elapsedSeconds", elapsed);
        root.set("consumedTokens", gs.getConsumedTokens());
        if (gs.getMaxTokens() > 0) {
            root.set("maxTokens", gs.getMaxTokens());
        }
        root.set("budgetExceeded", gs.isBudgetExceeded());

        return root.toJson();
    }

    /**
     * 标记 goal 完成（Codex：仅接受 complete，blocked 由运行时自动检测）
     */
    @ToolMapping(name = "update_goal",
            description = "Mark the current active goal as complete. " +
                    "Only call this when the objective has been successfully achieved. " +
                    "If blocked, do NOT call this — the system will detect it automatically.")
    public String updateGoal(
            @Param(name = "status", description = "Status: complete") String status) {

        if (status == null) {
            return "ERROR: status is required (complete)";
        }

        if (!"complete".equals(status)) {
            return "ERROR: unknown status '" + status + "'. Only 'complete' is supported.";
        }

        LoopTask task = scheduler.findActiveGoalAcrossSessions();
        if (task == null) {
            return "ERROR: no active goal found";
        }

        GoalState gs = task.getGoalState();
        if (!gs.getStatus().isActive()) {
            return "WARN: goal is not in an active state (" + gs.getStatus() + "), cannot mark complete";
        }

        String sessionId = resolveSessionId();
        if (sessionId == null) {
            return "ERROR: no active session";
        }

        gs.achieve();
        scheduler.clearGoal(sessionId, task.getId());

        long used = gs.getConsumedTokens();
        String tokenReport = gs.getMaxTokens() > 0
                ? used + "/" + gs.getMaxTokens() + " tokens used"
                : used > 0 ? used + " tokens used" : "token tracking N/A";
        return "OK: goal '" + gs.getCondition() + "' marked as complete (" + tokenReport + ")";
    }
}
