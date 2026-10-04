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
package org.noear.solon.codecli.api.web.controller;

import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.loop.GoalState;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.codecli.loop.LoopTask;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 循环任务与统一任务门面 Controller（原 WebController 的 loop/tasks 域）。
 *
 * <p>职责：会话级循环任务（loop）的增删改查、启停、触发；Goal 的暂停/恢复/清除/状态查询；
 * 以及 type=AUTOMATION / SESSION_LOOP 的统一任务门面（/web/tasks/*）。</p>
 */
public class LoopWebController extends AbstractWebController {

    public LoopWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    /**
     * 获取当前会话的循环任务列表（含已停用的）。
     */
    @Get
    @Mapping("/web/chat/loop/list")
    public Result<List<Map>> loopList(@Param("sessionId") String sessionId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        List<LoopTask> tasks = loopScheduler().listAll(sessionId);
        List<Map> data = new ArrayList<>();
        for (LoopTask t : tasks) {
            Map<String, Object> item = buildTaskMap(t);
            data.add(item);
        }
        return Result.succeed(data);
    }

    /**
     * 获取所有会话的循环任务列表。
     * <p>每个任务额外包含 sessionId，供后续删除、启停和手动触发使用。</p>
     */
    @Get
    @Mapping("/web/chat/loop/all")
    public Result<List<Map>> loopAll() {
        LoopScheduler loopScheduler = loopScheduler();
        loopScheduler.restoreAll();
        Map<String, List<LoopTask>> tasksBySession = loopScheduler.listAll();
        List<Map> data = new ArrayList<>();

        for (Map.Entry<String, List<LoopTask>> entry : tasksBySession.entrySet()) {
            if (entry.getKey().startsWith("auto-")) {
                continue;
            }
            for (LoopTask task : entry.getValue()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", entry.getKey());
                item.putAll(buildTaskMap(task));
                data.add(item);
            }
        }

        return Result.succeed(data);
    }

    /**
     * 构建任务 Map（通用方法，供 list/get 复用）
     */
    private Map<String, Object> buildTaskMap(LoopTask t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", t.getId());
        item.put("type", t.getType().name());  // 任务类型（LOOP / GOAL）
        item.put("prompt", t.getPrompt());
        item.put("intervalMinutes", t.getIntervalMinutes());
        if (t.getCron() != null) item.put("cron", t.getCron());
        item.put("enabled", t.isEnabled());
        item.put("cancelled", t.isCancelled());
        item.put("running", t.isRunning());
        item.put("currentIteration", t.getCurrentIteration());
        if (t.getLastResult() != null) item.put("lastResult", t.getLastResult());
        if (t.getLastExecutedAt() != null) item.put("lastExecutedAt", t.getLastExecutedAt().toString());


        item.put("runNow", t.isRunNow());

        // ★ P1: 预算字段
        if (t.getMaxTokens() != null) item.put("maxTokens", t.getMaxTokens());
        if (t.getMaxDurationMs() != null) item.put("maxDurationMs", t.getMaxDurationMs());

        // ★ P0: Goal 状态信息
        if (t.isGoalMode()) {
            GoalState gs = t.getGoalState();
            Map<String, Object> goalMap = new LinkedHashMap<>();
            goalMap.put("condition", gs.getCondition());
            goalMap.put("status", gs.getStatus().name());
            goalMap.put("iteration", t.getCurrentIteration());
            goalMap.put("consumedTokens", gs.getConsumedTokens());
            goalMap.put("maxTokens", gs.getMaxTokens());
            if (gs.getStartEpochMs() > 0) {
                goalMap.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
            }

            item.put("goal", goalMap);
        }

        return item;
    }

    /**
     * 获取单个循环任务详情（用于编辑回填）。
     */
    @Get
    @Mapping("/web/chat/loop/get")
    public Result<Map> loopGet(@Param("sessionId") String sessionId,
                               @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.trim().isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        List<LoopTask> tasks = loopScheduler().listAll(sessionId);
        for (LoopTask t : tasks) {
            if (t.getId().equals(taskId)) {
                return Result.succeed(buildTaskMap(t));
            }
        }
        return Result.failure(404, "Task not found");
    }

    /**
     * 新增循环任务。
     */
    @Post
    @Mapping("/web/chat/loop/add")
    public Result loopAdd(@Param("sessionId") String sessionId,
                          @Param("prompt") String prompt,
                          @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                          @Param(value = "cron", required = false) String cron,
                          @Param(value = "type", required = false) String type,
                          @Param(value = "runNow", required = false) Boolean runNow,
                          @Param(value = "maxTokens", required = false) Long maxTokens,
                          @Param(value = "maxDurationMs", required = false) Long maxDurationMs) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            return Result.failure(400, "prompt is required");
        }


        LoopTask.TaskType taskType = (type != null && "GOAL".equalsIgnoreCase(type))
                ? LoopTask.TaskType.GOAL
                : LoopTask.TaskType.HEARTBEAT;

        // 初始化状态目录
        int interval = intervalMinutes != null ? intervalMinutes : 5;
        LoopTask task = new LoopTask(
                prompt, interval, cron,
                taskType,
                runNow != null && runNow
        );
        // ★ P1: 预算字段
        if (maxTokens != null) task.setMaxTokens(maxTokens);
        if (maxDurationMs != null) task.setMaxDurationMs(maxDurationMs);

        try {
            loopScheduler().schedule(sessionId, task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(400, e.getMessage());
        }

        return Result.succeed(task.getId());
    }

    /**
     * 更新循环任务定义。
     */
    @Post
    @Mapping("/web/chat/loop/update")
    public Result loopUpdate(@Param("sessionId") String sessionId,
                             @Param("taskId") String taskId,
                             @Param(value = "prompt", required = false) String prompt,
                             @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                             @Param(value = "cron", required = false) String cron,
                             @Param(value = "type", required = false) String type,
                             @Param(value = "channelNotify", required = false) String channelNotify,
                             @Param(value = "runNow", required = false) Boolean runNow,
                             @Param(value = "maxTokens", required = false) Long maxTokens,
                             @Param(value = "maxDurationMs", required = false) Long maxDurationMs) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask existing = loopScheduler.getTaskById(sessionId, taskId);
        if (existing == null) {
            return Result.failure(404, "Task not found");
        }

        // 基于现有任务构建新任务（保留 id、createdAt、expireAt 等）
        int interval = intervalMinutes != null ? intervalMinutes : existing.getIntervalMinutes();
        String effectiveCron = cron != null ? cron : existing.getCron();
        String effectivePrompt = (prompt != null && !prompt.trim().isEmpty()) ? prompt.trim() : existing.getPrompt();
        LoopTask.TaskType newType = (type != null) ? LoopTask.TaskType.valueOf(type.toUpperCase()) : null;

        LoopTask newTask = existing.copyWithUpdate(
                effectivePrompt, interval, effectiveCron,
                newType,
                runNow != null ? runNow : existing.isRunNow(),
                maxTokens,
                maxDurationMs
        );

        // 保留 enabled
        newTask.setEnabled(existing.isEnabled());

        loopScheduler.update(sessionId, taskId, newTask);
        return Result.succeed();
    }

    // ==================== 自动任务内部实现（供 /web/tasks/* 统一门面调用，不单独暴露 HTTP） ====================

    private Result automationUpdate(@Param("id") String id,
                                    @Param(value = "name", required = false) String name,
                                    @Param(value = "prompt", required = false) String prompt,
                                    @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                                    @Param(value = "cron", required = false) String cron,
                                    @Param(value = "type", required = false) String type,
                                    @Param(value = "runNow", required = false) Boolean runNow,
                                    @Param(value = "maxTokens", required = false) Long maxTokens,
                                    @Param(value = "maxDurationMs", required = false) Long maxDurationMs,
                                    @Param(value = "modelName", required = false) String modelName,
                                    @Param(value = "agentName", required = false) String agentName) {
        try {
            automationManager().update(id, name, prompt, intervalMinutes, cron, type, runNow, maxTokens, maxDurationMs, modelName, agentName);
            return Result.succeed();
        } catch (IllegalArgumentException e) {
            return Result.failure(404, e.getMessage());
        } catch (IllegalStateException | IOException e) {
            return Result.failure(400, e.getMessage());
        }
    }

    private Result automationToggle(@Param("id") String id) {
        try {
            automationManager().toggle(id);
            return Result.succeed();
        } catch (IllegalArgumentException e) {
            return Result.failure(404, e.getMessage());
        } catch (IOException | IllegalStateException e) {
            return Result.failure(400, e.getMessage());
        }
    }

    private Result automationTrigger(@Param("id") String id) {
        try {
            automationManager().trigger(id);
            return Result.succeed();
        } catch (IllegalArgumentException e) {
            return Result.failure(404, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.failure(400, e.getMessage());
        }
    }

    private Result automationDelete(@Param("id") String id) {
        try {
            automationManager().remove(id);
            return Result.succeed();
        } catch (IllegalArgumentException e) {
            return Result.failure(404, e.getMessage());
        } catch (IOException e) {
            return Result.failure(400, e.getMessage());
        }
    }

    private Result<String> automationSession(@Param("id") String id) {
        String sessionId = automationManager().sessionId(id);
        return sessionId == null ? Result.failure(404, "Automation not found") : Result.succeed(sessionId);
    }

    // ==================== 统一任务门面 ====================

    /**
     * 统一读取任务。type=AUTOMATION 时按工作区任务读取，否则按 session 读取循环任务。
     * 会话内循环任务仍可用旧 /web/chat/loop/* 接口。
     */
    @Get
    @Mapping("/web/tasks/list")
    public Result taskList(@Param(value = "type", required = false) String type,
                           @Param(value = "sessionId", required = false) String sessionId) {
        if (isAutomationType(type)) {
            return Result.succeed(automationManager().list());
        }
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "sessionId is required for SESSION_LOOP");
        }
        return loopList(sessionId);
    }

    @Get
    @Mapping("/web/tasks/get")
    public Result taskGet(@Param("type") String type,
                          @Param("id") String id,
                          @Param(value = "sessionId", required = false) String sessionId) {
        if (isAutomationType(type)) {
            Map<String, Object> data = automationManager().get(id);
            return data == null ? Result.failure(404, "Task not found") : Result.succeed(data);
        }
        return loopGet(sessionId, id);
    }

    @Post
    @Mapping("/web/tasks/create")
    public Result taskCreate(@Param("type") String type,
                             @Param("prompt") String prompt,
                             @Param(value = "name", required = false) String name,
                             @Param(value = "sessionId", required = false) String sessionId,
                             @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                             @Param(value = "cron", required = false) String cron,
                             @Param(value = "taskType", required = false) String taskType,
                             @Param(value = "runNow", required = false) Boolean runNow,
                             @Param(value = "maxTokens", required = false) Long maxTokens,
                             @Param(value = "maxDurationMs", required = false) Long maxDurationMs,
                             @Param(value = "modelName", required = false) String modelName,
                             @Param(value = "agentName", required = false) String agentName) {
        if (isAutomationType(type)) {
            try {
                String taskId = automationManager().create(
                        name, prompt, intervalMinutes, cron, taskType, runNow, maxTokens, maxDurationMs, modelName, agentName);
                return Result.succeed(taskId);
            } catch (IllegalArgumentException | IllegalStateException | IOException e) {
                return Result.failure(400, e.getMessage());
            }
        }
        return loopAdd(sessionId, prompt, intervalMinutes, cron, taskType, runNow, maxTokens, maxDurationMs);
    }

    @Post
    @Mapping("/web/tasks/update")
    public Result taskUpdate(@Param("type") String type,
                             @Param("id") String id,
                             @Param(value = "sessionId", required = false) String sessionId,
                             @Param(value = "name", required = false) String name,
                             @Param(value = "prompt", required = false) String prompt,
                             @Param(value = "intervalMinutes", required = false) Integer intervalMinutes,
                             @Param(value = "cron", required = false) String cron,
                             @Param(value = "taskType", required = false) String taskType,
                             @Param(value = "runNow", required = false) Boolean runNow,
                             @Param(value = "maxTokens", required = false) Long maxTokens,
                             @Param(value = "maxDurationMs", required = false) Long maxDurationMs,
                             @Param(value = "modelName", required = false) String modelName,
                             @Param(value = "agentName", required = false) String agentName) {
        if (isAutomationType(type)) {
            return automationUpdate(id, name, prompt, intervalMinutes, cron, taskType, runNow, maxTokens, maxDurationMs, modelName, agentName);
        }
        return loopUpdate(sessionId, id, prompt, intervalMinutes, cron, taskType, null, runNow, maxTokens, maxDurationMs);
    }

    @Post
    @Mapping("/web/tasks/toggle")
    public Result taskToggle(@Param("type") String type,
                             @Param("id") String id,
                             @Param(value = "sessionId", required = false) String sessionId) {
        if (isAutomationType(type)) return automationToggle(id);
        return loopToggle(sessionId, id);
    }

    @Post
    @Mapping("/web/tasks/trigger")
    public Result taskTrigger(@Param("type") String type,
                              @Param("id") String id,
                              @Param(value = "sessionId", required = false) String sessionId) {
        if (isAutomationType(type)) return automationTrigger(id);
        return loopTrigger(sessionId, id);
    }

    @Post
    @Mapping("/web/tasks/delete")
    public Result taskDelete(@Param("type") String type,
                             @Param("id") String id,
                             @Param(value = "sessionId", required = false) String sessionId) {
        if (isAutomationType(type)) return automationDelete(id);
        return loopRemove(sessionId, id);
    }

    @Get
    @Mapping("/web/tasks/session")
    public Result<String> taskSession(@Param("type") String type,
                                      @Param("id") String id,
                                      @Param(value = "sessionId", required = false) String sessionId) {
        if (isAutomationType(type)) return automationSession(id);
        if (!isValidSessionId(sessionId)) return Result.failure(400, "sessionId is required for SESSION_LOOP");
        return Result.succeed(sessionId);
    }

    private static boolean isAutomationType(String type) {
        return "AUTOMATION".equalsIgnoreCase(type);
    }

    // ==================== Goal 管理端点 (P0) ====================

    /**
     * 暂停 goal 调度
     */
    @Post
    @Mapping("/web/chat/loop/goal-pause")
    public Result loopGoalPause(@Param("sessionId") String sessionId,
                                @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        if (gs.getStatus() != GoalState.Status.PURSUING) {
            return Result.failure(400, "Goal cannot be paused in state: " + gs.getStatus());
        }

        loopScheduler.pauseGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 恢复 goal 调度
     */
    @Post
    @Mapping("/web/chat/loop/goal-resume")
    public Result loopGoalResume(@Param("sessionId") String sessionId,
                                 @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        if (gs.getStatus() != GoalState.Status.PAUSED) {
            return Result.failure(400, "Goal cannot be resumed in state: " + gs.getStatus()
                    + " (only PAUSED or BLOCKED can be resumed)");
        }

        loopScheduler.resumeGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 清除 goal（任务保留，仅清除 goal 标记）
     */
    @Post
    @Mapping("/web/chat/loop/goal-clear")
    public Result loopGoalClear(@Param("sessionId") String sessionId,
                                @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        loopScheduler.clearGoal(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 获取 goal 详细状态（含完整评估历史）
     */
    @Post
    @Mapping("/web/chat/loop/goal-status")
    public Result<Map> loopGoalStatus(@Param("sessionId") String sessionId,
                                      @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopTask task = loopScheduler().getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        if (!task.isGoalMode()) {
            return Result.failure(400, "Task has no goal");
        }

        GoalState gs = task.getGoalState();
        Map<String, Object> goalMap = new LinkedHashMap<>();
        goalMap.put("condition", gs.getCondition());
        goalMap.put("status", gs.getStatus().name());
        goalMap.put("iteration", task.getCurrentIteration());
        goalMap.put("consumedTokens", gs.getConsumedTokens());
        goalMap.put("maxTokens", gs.getMaxTokens());

        if (gs.getStartEpochMs() > 0) {
            goalMap.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
        }

        return Result.succeed(goalMap);
    }

    /**
     * 删除循环任务。
     */
    @Post
    @Mapping("/web/chat/loop/remove")
    public Result loopRemove(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        LoopScheduler loopScheduler = loopScheduler();
        LoopTask task = loopScheduler.getTaskById(sessionId, taskId);
        if (task == null) {
            return Result.failure(400, "the task does not exist.");
        }

        loopScheduler.remove(sessionId, task);
        return Result.succeed();
    }

    /**
     * 启用/停用循环任务（toggle）。
     */
    @Post
    @Mapping("/web/chat/loop/toggle")
    public Result loopToggle(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        loopScheduler().toggle(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 手动触发一次循环任务执行。
     */
    @Post
    @Mapping("/web/chat/loop/trigger")
    public Result loopTrigger(@Param("sessionId") String sessionId, @Param("taskId") String taskId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        loopScheduler().trigger(sessionId, taskId);
        return Result.succeed();
    }
}
