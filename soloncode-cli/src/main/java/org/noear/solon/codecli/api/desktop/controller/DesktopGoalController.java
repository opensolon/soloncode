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
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.command.builtin.GoalState;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.command.builtin.LoopTask;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.api.desktop.WsGate;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop Goal Controller。
 *
 * <p>承载桌面端 Goal（长任务目标）的全生命周期：列表、从对话输入框创建（含
 * 预算限制）、编辑（PURSUING 状态下先暂停-中断-再恢复）、暂停/恢复/触发/
 * 移除。目标正文通过 JSON 传输，不经过命令行解析。</p>
 *
 * <p>构造器同时向 LoopScheduler 注册桌面端的 BusyChecker 与 TaskExecutor：
 * 调度器据此识别桌面会话忙态并把 Goal 回合交给 WsGate 执行与捕获。
 * （原 WsController 构造器中的注册逻辑随领域拆分唯一迁入本类。）</p>
 *
 * @author bai
 */
public class DesktopGoalController extends AbstractDesktopController {
    private final WsGate wsGate;

    public DesktopGoalController(HarnessEngine engine, WsGate wsGate, LoopScheduler loopScheduler,
                                 SessionManager sessionManager) {
        super(engine, loopScheduler, sessionManager);
        this.wsGate = wsGate;

        if (loopScheduler != null) {
            loopScheduler.addBusyChecker(sessionId -> DesktopSessionIds.isValid(sessionId) && wsGate.isSessionBusy(sessionId));
            loopScheduler.addTaskExecutor((sessionId, prompt, agentName) -> {
                if (!DesktopSessionIds.isValid(sessionId)) {
                    return null;
                }
                return wsGate.runGoalRoundAndCapture(sessionId, prompt, agentName);
            });
        }
    }

    /** 当前桌面会话的 Goal 列表。 */
    @Get
    @Mapping("/desktop/chat/goals/list")
    public Result<List<Map>> goalsList(@Param("sessionId") String sessionId) {
        if (!DesktopSessionIds.isValid(sessionId) || loopScheduler == null) {
            return Result.failure(400, "Invalid session id");
        }
        loopScheduler.restore(sessionId);
        List<Map> items = new ArrayList<>();
        for (LoopTask task : loopScheduler.listAll(sessionId)) {
            if (task.isGoalMode()) {
                items.add(buildGoalTaskMap(task));
            }
        }
        return Result.succeed(items);
    }

    /** 从对话输入框启动 Goal；目标正文通过 JSON 传输，不经过命令行解析。 */
    @Post
    @Mapping("/desktop/chat/goals/add")
    public Result goalsAdd(Context ctx) throws Exception {
        if (loopScheduler == null) {
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

            synchronized (loopScheduler) {
                LoopTask active = loopScheduler.findActiveGoalInSession(sessionId);
                if (active != null) {
                    return Result.failure(409, "A Goal is already active in this session");
                }
                LoopTask task = new LoopTask(prompt.trim(), 0, null, LoopTask.TaskType.GOAL, true);
                if (maxTokens != null) {
                    task.setMaxTokens(maxTokens);
                }
                if (maxDurationMinutes != null) {
                    task.setMaxDurationMs(Math.multiplyExact(maxDurationMinutes, 60_000L));
                }
                if (maxIterations != null) {
                    task.getGoalState().setMaxIterations(maxIterations);
                }
                loopScheduler.schedule(sessionId, task);
                return Result.succeed(task.getId());
            }
        } catch (IllegalArgumentException | ArithmeticException error) {
            return Result.failure(400, error.getMessage());
        } catch (IllegalStateException error) {
            return Result.failure(409, error.getMessage());
        }
    }

    @Post
    @Mapping("/desktop/chat/goals/update")
    public Result goalsUpdate(Context ctx) throws Exception {
        if (loopScheduler == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String taskId = root.get("taskId").getString();
        if (!DesktopSessionIds.isValid(sessionId) || Assert.isEmpty(taskId) || taskId.length() > 64) {
            return Result.failure(400, "Invalid Goal request");
        }

        loopScheduler.restore(sessionId);
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            return Result.failure(404, "Goal not found");
        }
        if (task.getGoalState().getStatus().isTerminal()) {
            return Result.failure(409, "Completed Goal cannot be edited");
        }

        String objective = root.getOrNull("prompt") == null
                ? task.getGoalState().getCondition() : root.get("prompt").getString().trim();
        if (Assert.isEmpty(objective) || objective.length() > 20_000) {
            return Result.failure(400, "Goal prompt is required and must not exceed 20000 characters");
        }
        Long maxTokens = optionalNonNegativeLong(root, "maxTokens", 1_000_000_000L);
        Integer maxIterations = optionalNonNegativeInt(root, "maxIterations", 10_000);
        if (maxTokens != null && maxTokens < 0 || maxIterations != null && maxIterations < 0) {
            return Result.failure(400, "Invalid Goal limit");
        }

        boolean resumeAfterUpdate = task.getGoalState().getStatus() == GoalState.Status.PURSUING;
        if (resumeAfterUpdate) {
            loopScheduler.pauseGoal(sessionId, taskId);
            wsGate.interruptGoalSession(sessionId);
            if (task.getGoalState().getStatus() != GoalState.Status.PAUSED) {
                return Result.failure(409, "Goal state changed before settings could be applied");
            }
            for (int attempt = 0; task.isRunning() && attempt < 200; attempt++) {
                Thread.sleep(10L);
            }
            if (task.isRunning()) {
                return Result.failure(409, "Goal is still stopping; please retry");
            }
        }

        loopScheduler.updateGoalConfiguration(
                sessionId,
                taskId,
                objective,
                maxTokens != null ? maxTokens : task.getGoalState().getMaxTokens(),
                maxIterations != null ? maxIterations : task.getGoalState().getMaxIterations());
        if (resumeAfterUpdate) {
            loopScheduler.resumeGoal(sessionId, taskId);
            loopScheduler.trigger(sessionId, taskId);
        }
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
        if (loopScheduler == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        ONode root = ONode.ofJson(ctx.body());
        String sessionId = root.get("sessionId").getString();
        String taskId = root.get("taskId").getString();
        if (!DesktopSessionIds.isValid(sessionId) || Assert.isEmpty(taskId) || taskId.length() > 64) {
            return Result.failure(400, "Invalid Goal request");
        }
        loopScheduler.restore(sessionId);
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null || !task.isGoalMode()) {
            return Result.failure(404, "Goal not found");
        }
        switch (action) {
            case "pause":
                loopScheduler.pauseGoal(sessionId, taskId);
                wsGate.interruptGoalSession(sessionId);
                break;
            case "resume":
                loopScheduler.resumeGoal(sessionId, taskId);
                break;
            case "trigger":
                loopScheduler.trigger(sessionId, taskId);
                break;
            case "remove":
                if (task.getGoalState().getStatus() == GoalState.Status.PURSUING) {
                    loopScheduler.pauseGoal(sessionId, taskId);
                }
                wsGate.interruptGoalSession(sessionId);
                loopScheduler.remove(sessionId, task);
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

    private Map<String, Object> buildGoalTaskMap(LoopTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getId());
        item.put("type", task.getType().name());
        item.put("prompt", task.getPrompt());
        item.put("enabled", task.isEnabled());
        item.put("running", task.isRunning());
        item.put("currentIteration", task.getCurrentIteration());
        if (task.getMaxTokens() != null) item.put("maxTokens", task.getMaxTokens());
        if (task.getMaxDurationMs() != null) item.put("maxDurationMs", task.getMaxDurationMs());
        if (task.getLastResult() != null) item.put("lastResult", task.getLastResult());
        if (task.getLastExecutedAt() != null) item.put("lastExecutedAt", task.getLastExecutedAt().toString());

        GoalState state = task.getGoalState();
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("condition", state.getCondition());
        goal.put("status", state.getStatus().name());
        goal.put("iteration", task.getCurrentIteration());
        goal.put("maxIterations", state.getMaxIterations());
        goal.put("consumedTokens", state.getConsumedTokens());
        goal.put("maxTokens", state.getMaxTokens());
        if (state.getStartEpochMs() > 0) {
            goal.put("startedAt", Instant.ofEpochMilli(state.getStartEpochMs()).toString());
        }
        item.put("goal", goal);
        return item;
    }
}
