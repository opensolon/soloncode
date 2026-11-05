/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.api.desktop.controller;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.api.desktop.WsGate;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalSpec;
import org.noear.solon.ai.harness.extension.goal.GoalState;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Desktop Goal Controller。
 *
 * <p>承载桌面端 Goal（长任务目标）的全生命周期：列表、从对话输入框创建（含
 * 预算限制）、编辑（PURSUING 状态下先暂停-中断-再恢复）、暂停/恢复/触发/
 * 移除。目标正文通过 JSON 传输，不经过命令行解析。</p>
 *
 * <p>Goal 属于会话（每会话单槽），状态存于会话快照的 {@code session.goal} 投影，
 * 与 Web/CLI 共用同一个 {@link GoalExtension}，不再经过 loop 定时任务体系。</p>
 *
 * @author bai
 */
public class DesktopGoalController extends AbstractDesktopController {
    private final WsGate wsGate;
    private final GoalExtension goal;

    public DesktopGoalController(HarnessEngine engine, WsGate wsGate,
                                 SessionManager sessionManager, GoalExtension goal) {
        super(engine, sessionManager);
        this.wsGate = wsGate;
        this.goal = goal;
        // 桌面端 TaskExecutor 已由 WsGate 构造时注册到 GoalEngine 自己的执行通道；
        // 忙判定由会话快照（SessionStatus）统一派生，桌面端不再单独注册
    }

    /** 当前桌面会话的 Goal 列表（单槽语义下至多一条）。 */
    @Get
    @Mapping("/desktop/chat/goals/list")
    public Result<List<Map>> goalsList(@Param("sessionId") String sessionId) {
        if (!DesktopSessionIds.isValid(sessionId) || goal == null) {
            return Result.failure(400, "Invalid session id");
        }
        GoalState gs = goal.stateOf(sessionId);
        List<Map> items = new ArrayList<>();
        if (gs != null) {
            items.add(goal.toTaskMap(goal.sessionOf(sessionId), gs));
        }
        return Result.succeed(items);
    }

    /** 从对话输入框启动 Goal；目标正文通过 JSON 传输，不经过命令行解析。 */
    @Post
    @Mapping("/desktop/chat/goals/add")
    public Result goalsAdd(Context ctx) throws Exception {
        if (goal == null || !goal.hasScheduler()) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String prompt = root.get("prompt").getString();
        if (!DesktopSessionIds.isValid(sessionId)) {
            return Result.failure(400, "Invalid session id");
        }
        if (Assert.isEmpty(prompt) || prompt.trim().length() > 20_000) {
            return Result.failure(400, "Goal prompt is required and must not exceed 20000 characters");
        }

        Long maxTokens = optionalPositiveLong(root, "maxTokens", 1_000_000_000L);
        Long maxDurationMinutes = optionalPositiveLong(root, "maxDurationMinutes", 525_600L);
        Integer maxIterations = optionalNonNegativeInt(root, "maxIterations", 10_000);
        if (maxTokens != null && maxTokens < 0 || maxDurationMinutes != null && maxDurationMinutes < 0
                || maxIterations != null && maxIterations < 0) {
            return Result.failure(400, "Invalid Goal budget");
        }

        try {
            wsGate.configureGoalSession(
                    sessionId,
                    root.get("modelName").getString(),
                    root.get("agent").getString(),
                    root.get("workspace").getString(),
                    root.get("reasoningEffort").getString());

            AgentSession session = goal.sessionOf(sessionId);
            if (session == null) {
                return Result.failure(404, "Session not found");
            }
            GoalState gs = goal.attachAndSchedule(session, GoalSpec.of(prompt)
                    .maxTokens(maxTokens != null ? maxTokens : 0L)
                    .maxDuration(maxDurationMinutes != null
                            ? java.time.Duration.ofMinutes(maxDurationMinutes) : null)
                    .maxIterations(maxIterations != null ? maxIterations : 0));
            return Result.succeed(gs.getId());
        } catch (IllegalArgumentException | ArithmeticException error) {
            return Result.failure(400, error.getMessage());
        } catch (IllegalStateException error) {
            return Result.failure(409, error.getMessage());
        }
    }

    @Post
    @Mapping("/desktop/chat/goals/update")
    public Result goalsUpdate(Context ctx) throws Exception {
        if (goal == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String taskId = root.get("taskId").getString();
        if (!DesktopSessionIds.isValid(sessionId) || Assert.isEmpty(taskId) || taskId.length() > 64) {
            return Result.failure(400, "Invalid Goal request");
        }

        GoalState gs = goal.find(sessionId, taskId);
        if (gs == null) {
            return Result.failure(404, "Goal not found");
        }
        if (gs.getStatus().isTerminal()) {
            return Result.failure(409, "Completed Goal cannot be edited");
        }

        String objective = root.getOrNull("prompt") == null
                ? gs.getCondition() : root.get("prompt").getString().trim();
        if (Assert.isEmpty(objective) || objective.length() > 20_000) {
            return Result.failure(400, "Goal prompt is required and must not exceed 20000 characters");
        }
        Long maxTokens = optionalNonNegativeLong(root, "maxTokens", 1_000_000_000L);
        Integer maxIterations = optionalNonNegativeInt(root, "maxIterations", 10_000);
        if (maxTokens != null && maxTokens < 0 || maxIterations != null && maxIterations < 0) {
            return Result.failure(400, "Invalid Goal limit");
        }

        boolean resumeAfterUpdate = gs.getStatus() == GoalState.Status.PURSUING;
        if (resumeAfterUpdate) {
            // pause → 中断 → 停等三连由门面一步完成（不忙等，超时即回 409）
            if (!goal.pauseAndAwait(goal.sessionOf(sessionId), gs,
                    () -> wsGate.interruptGoalSession(sessionId), 2000L)) {
                return Result.failure(409, "Goal is still stopping; please retry");
            }
            if (gs.getStatus() != GoalState.Status.PAUSED) {
                return Result.failure(409, "Goal state changed before settings could be applied");
            }
        }

        // 后半段（update + resume + schedule）由门面一步完成；桌面端不支持改时长
        goal.updateAndReschedule(goal.sessionOf(sessionId), gs, objective, maxTokens, null, resumeAfterUpdate);
        return Result.succeed();
    }

    @Post
    @Mapping("/desktop/chat/goals/pause")
    public Result goalsPause(Context ctx) throws Exception {
        return operateGoal(ctx, "pause");
    }

    @Post
    @Mapping("/desktop/chat/goals/resume")
    public Result goalsResume(Context ctx) throws Exception {
        return operateGoal(ctx, "resume");
    }

    @Post
    @Mapping("/desktop/chat/goals/trigger")
    public Result goalsTrigger(Context ctx) throws Exception {
        return operateGoal(ctx, "trigger");
    }

    @Post
    @Mapping("/desktop/chat/goals/remove")
    public Result goalsRemove(Context ctx) throws Exception {
        return operateGoal(ctx, "remove");
    }

    private Result operateGoal(Context ctx, String action) throws Exception {
        if (goal == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String taskId = root.get("taskId").getString();
        if (!DesktopSessionIds.isValid(sessionId) || Assert.isEmpty(taskId) || taskId.length() > 64) {
            return Result.failure(400, "Invalid Goal request");
        }
        GoalState gs = goal.find(sessionId, taskId);
        if (gs == null) {
            return Result.failure(404, "Goal not found");
        }
        switch (action) {
            case "pause":
                goal.pause(sessionId, taskId);
                wsGate.interruptGoalSession(sessionId);
                break;
            case "resume":
                if (!goal.resume(sessionId, taskId)) {
                    return Result.failure(409, "Goal cannot be resumed in state: " + gs.getStatus());
                }
                break;
            case "trigger":
                goal.scheduleNow(sessionId);
                break;
            case "remove":
                wsGate.interruptGoalSession(sessionId);
                goal.clear(sessionId);
                break;
            default:
                return Result.failure(400, "Invalid Goal action");
        }
        return Result.succeed();
    }

    private Long optionalPositiveLong(ONode root, String name, long maximum) {
        ONode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        long value = node.getLong();
        if (value < 0) {
            return -1L;
        }
        if (value == 0) {
            return null;
        }
        if (value > maximum) {
            return -1L;
        }
        return value;
    }

    private Long optionalNonNegativeLong(ONode root, String name, long maximum) {
        if (root.getOrNull(name) == null) {
            return null;
        }
        long value = root.get(name).getLong();
        return value < 0 || value > maximum ? -1L : value;
    }

    private Integer optionalNonNegativeInt(ONode root, String name, int maximum) {
        if (root.getOrNull(name) == null) {
            return null;
        }
        long value = root.get(name).getLong();
        return value < 0 || value > maximum ? -1 : (int) value;
    }
}
