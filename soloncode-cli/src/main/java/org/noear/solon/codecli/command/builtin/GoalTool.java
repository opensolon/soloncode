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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Goal 工具 — 模型侧 Goal 生命周期控制（Codex CLI 对齐）
 *
 * <p>提供三个工具：
 * <ul>
 *   <li>{@code create_goal} — 创建新 goal（参数：objective + token_budget）</li>
 *   <li>{@code get_goal} — 查询当前 goal 状态，无 goal 返回 null</li>
 *   <li>{@code update_goal} — 标记 complete 或 blocked（模型驱动，对齐 Codex）</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.3
 */
public class GoalTool extends AbsTalent {

    private static final Logger log = LoggerFactory.getLogger(GoalTool.class);

    private final LoopScheduler scheduler;

    public GoalTool(LoopScheduler scheduler) {
        this.scheduler = scheduler;
    }


    /**
     * 创建新的 Goal（Codex 签名对齐：objective + token_budget）
     */
    @ToolMapping(name = "create_goal",
            description = "创建一个新目标，引导对话朝向特定目标推进。" +
                    "每次对话同一时间最多只能有一个活跃目标。" +
                    "如果已存在活跃目标，此操作将失败。")
    public String createGoal(
            @Param(name = "objective", description = "要达成的目标") String objective,
            @Param(name = "token_budget", description = "Token 预算上限（可选）", required = false) Long tokenBudget,
            String __sessionId,
            String __cwd) {

        if (objective == null || objective.isEmpty()) {
            return "错误：objective 参数不能为空";
        }

        String sessionId = __sessionId;
        if (sessionId == null) {
            return "错误：未找到活跃会话";
        }

        LoopTask existing = scheduler.findActiveGoalAcrossSessions();
        if (existing != null) {
            GoalState gs = existing.getGoalState();
            return "错误：已有一个活跃目标（" + existing.getId()
                    + ": " + gs.getCondition()
                    + "）。请先使用 update_goal 或完成当前目标。";
        }

        LoopTask task = new LoopTask(objective, 0, null,
                objective, false, 0, true);

        long maxTokens = tokenBudget != null ? tokenBudget : 0;
        task.setMaxTokens(maxTokens);

        try {
            scheduler.schedule(sessionId, task);
            GoalState gs = task.getGoalState();

            return "已创建目标 — taskId='" + task.getId()
                    + "', objective='" + objective
                    + "'. 可调用 get_goal 查看状态。";
        } catch (Exception e) {
            return "错误：创建目标失败 — " + e.getMessage();
        }
    }

    /**
     * 获取当前 goal 状态（无 goal 时返回 null，Codex 对齐）
     */
    @ToolMapping(name = "get_goal",
            description = "获取当前活跃目标的状态，若无活跃目标则返回 null。")
    public String getGoal(String __sessionId,
                          String __cwd) {
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
     * 更新 goal 状态（Codex 对齐：支持 complete 和 blocked）
     *
     * <p>complete: 通过 ValidatorFactory 执行客观校验后标记完成。
     * blocked: 模型主动声明阻塞（同一困境尝试了 3 次后），暂停调度等待 resume。
     */
    @ToolMapping(name = "update_goal",
            description = "更新当前活跃目标的状态。" +
                    "status='complete' 标记目标已完成；" +
                    "status='blocked' 声明遇到阻塞（同一困境尝试了3次后使用）。")
    public String updateGoal(
            @Param(name = "status", description = "状态值：complete 或 blocked") String status,
            String __sessionId,
            String __cwd) {

        if (status == null) {
            return "错误：status 参数必填（complete 或 blocked）";
        }

        LoopTask task = scheduler.findActiveGoalAcrossSessions();
        if (task == null) {
            return "错误：未找到活跃目标";
        }

        GoalState gs = task.getGoalState();
        if (!gs.getStatus().isActive()) {
            return "警告：目标不在活跃状态（" + gs.getStatus() + "），无法更新";
        }

        String sessionId = __sessionId;
        if (sessionId == null) {
            return "错误：无活跃会话";
        }

        // ---- complete ----
        if ("complete".equals(status)) {
            // 验证闸：AI 声称完成时，通过 ValidatorFactory 执行外部校验
            LoopConfig config = new LoopConfig();
            if (config.isValidatorEnabled()) {
                GoalValidator validator = ValidatorFactory.forCondition(gs.getCondition());
                GoalValidator.ValidationResult vr = validator.validate(gs.getCondition(), sessionId);
                if (!vr.isPassed()) {
                    log.warn("updateGoal: 验证失败 for goal '{}': {}", task.getId(), vr.detail());
                    return "目标尚未完成：验证失败 - " + vr.detail()
                            + "。请继续改进后重新调用 update_goal(complete)。";
                }
            }

            gs.achieve();
            scheduler.clearGoal(sessionId, task.getId());

            long used = gs.getConsumedTokens();
            String tokenReport = gs.getMaxTokens() > 0
                    ? used + "/" + gs.getMaxTokens() + " tokens 已消耗"
                    : used > 0 ? used + " tokens 已消耗" : "token 统计不可用";
            return "已完成目标 '" + gs.getCondition() + "' 已标记为完成（" + tokenReport + "）";
        }

        // ---- blocked ----
        if ("blocked".equals(status)) {
            gs.markBlocked();
            scheduler.pauseGoal(sessionId, task.getId());
            log.info("updateGoal: goal '{}' marked as BLOCKED by model", task.getId());
            return "目标已标记为阻塞 — 目标暂停，等待 resume。";
        }

        return "错误：未知状态 '" + status + "'. 仅支持 'complete' 或 'blocked'.";
    }
}
