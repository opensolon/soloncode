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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.goal.GoalEngine;
import org.noear.solon.ai.harness.session.goal.GoalState;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.codecli.loop.LoopTask;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.RunUtil;

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

        List<Map> data = new ArrayList<>();

        // Goal 属于会话（单槽），恒为第一条；其余为定时任务（可多条）
        Map<String, Object> goalItem = buildGoalMap(sessionId);
        if (goalItem != null) {
            data.add(goalItem);
        }

        for (LoopTask t : loopScheduler().listAll(sessionId)) {
            data.add(buildTaskMap(t));
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
     * 构建会话 goal 的展示 Map（与 loop 任务同构，供前端合并渲染）。
     *
     * <p>字段名与定时任务保持一致，唯一区别是 {@code type=GOAL}、
     * 以及附带 {@code goal} 子对象（前端据此显示状态徽章与预算进度）。</p>
     *
     * @return 无 goal 时返回 null
     */
    private Map<String, Object> buildGoalMap(String sessionId) {
        GoalEngine engine = currentContext().getGoalEngine();
        if (engine == null) {
            return null;
        }
        AgentSession session = engine.sessionOf(sessionId);
        if (session == null) {
            return null;
        }
        GoalState gs = engine.state(session);
        if (gs == null) {
            return null;
        }

        // 与定时任务同构的展示 Map 由 GoalEngine 统一产出（字段契约单点维护）
        Map<String, Object> item = engine.toTaskMap(session, gs);
        item.put("intervalMinutes", 0);
        item.put("enabled", gs.getStatus().isActive());
        item.put("cancelled", gs.getStatus().isTerminal());
        item.put("runNow", true);
        return item;
    }

    /**
     * Web 端创建会话 goal（与桌面版 /desktop/chat/goals/add 同语义）。
     *
     * <p>保留在 loop/add 内部分流而非新开端点，因为前端表单与列表渲染是
     * 一套 loop 契约（同构 Map），新开端点需要同步改表单提交与列表刷新两处。</p>
     */
    private Result createSessionGoal(String sessionId, String prompt, Long maxTokens, Long maxDurationMs) {
        GoalEngine engine = currentContext().getGoalEngine();
        if (engine == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        AgentSession session = engine.sessionOf(sessionId);
        if (session == null) {
            return Result.failure(404, "Session not found");
        }
        try {
            GoalState gs = engine.attach(session, prompt.trim(),
                    maxTokens != null ? maxTokens : 0L,
                    maxDurationMs != null ? maxDurationMs : 0L,
                    0);
            if (currentContext().getGoalScheduler() != null) {
                currentContext().getGoalScheduler().schedule(sessionId, true);
            }
            return Result.succeed(gs.getId());
        } catch (IllegalStateException e) {
            // 单槽语义：已有活跃/可恢复 goal
            return Result.failure(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.failure(400, e.getMessage());
        }
    }

    /**
     * Web 端编辑会话 goal（目标正文与预算）。仅非终态可编辑，语义同桌面版 goals/update。
     */
    private Result updateSessionGoal(AgentSession session, GoalState gs,
                                     String prompt, Long maxTokens, Long maxDurationMs) {
        if (gs.getStatus().isTerminal()) {
            return Result.failure(409, "已完成的 Goal 不能编辑，请先清除");
        }
        // 编辑前先停住旧轮次，对齐桌面版 pause → interrupt → 等停 → update → resume 的时序
        boolean resumeAfterUpdate = gs.getStatus().isActive();
        GoalEngine engine = currentContext().getGoalEngine();
        if (resumeAfterUpdate) {
            engine.pause(session, gs);
            if (currentContext().getGoalScheduler() != null) {
                currentContext().getGoalScheduler().unschedule(session.getSessionId());
            }
            // 中断在跑的轮次，否则旧目标会继续跑并回写覆盖刚改的配置
            if (runtimePort() != null) {
                runtimePort().interruptSession(currentContext(), session.getSessionId());
            }
            if (gs.getStatus() != GoalState.Status.PAUSED) {
                return Result.failure(409, "Goal state changed before settings could be applied");
            }
            for (int attempt = 0; engine.isRunning(session) && attempt < 200; attempt++) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        try {
            String objective = (prompt != null && !prompt.trim().isEmpty())
                    ? prompt.trim() : gs.getCondition();
            engine.update(session, objective,
                    maxTokens != null ? maxTokens : gs.getMaxTokens(),
                    gs.getMaxIterations());
            if (maxDurationMs != null) {
                gs.setMaxDurationMs(maxDurationMs);
                engine.commit(session, gs);
            }
        } catch (IllegalStateException e) {
            return Result.failure(409, e.getMessage());
        } finally {
            if (resumeAfterUpdate) {
                engine.resume(session, gs);
                if (currentContext().getGoalScheduler() != null) {
                    currentContext().getGoalScheduler().schedule(session.getSessionId(), true);
                }
            }
        }
        return Result.succeed();
    }

    /**
     * 构建任务 Map（通用方法，供 list/get 复用）
     */
    private Map<String, Object> buildTaskMap(LoopTask t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", t.getId());
        item.put("type", "HEARTBEAT");
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

        // goal 行分流：单槽语义，id 即 GoalState.id
        Map<String, Object> goalItem = buildGoalMap(sessionId);
        if (goalItem != null && taskId.equals(goalItem.get("id"))) {
            return Result.succeed(goalItem);
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

        // goal 已属会话级独立能力（/goal 或 /desktop/chat/goals/add），定时任务页不再创建 GOAL
        if (type != null && "GOAL".equalsIgnoreCase(type)) {
            return createSessionGoal(sessionId, prompt, maxTokens, maxDurationMs);
        }

        // 初始化状态目录
        int interval = intervalMinutes != null ? intervalMinutes : 5;
        LoopTask task = new LoopTask(
                prompt, interval, cron,
                runNow != null && runNow
        );
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

        // goal 行分流：必须在 LoopTask 查找之前，goal 的 taskId 是 GoalState.id，
        // 不在 loop 任务表中，否则会在 getTaskById 处先返回 404。
        GoalEngine updGoalEngine = currentContext().getGoalEngine();
        if (updGoalEngine != null) {
            AgentSession gsSession = updGoalEngine.sessionOf(sessionId);
            GoalState gs = gsSession == null ? null : updGoalEngine.state(gsSession);
            if (gs != null && taskId.equals(gs.getId())) {
                return updateSessionGoal(gsSession, gs, prompt, maxTokens, maxDurationMs);
            }
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
        if (type != null && "GOAL".equalsIgnoreCase(type)) {
            return Result.failure(400, "GOAL is a session-scoped capability, use /goal or goal endpoints");
        }

        LoopTask newTask = existing.copyWithUpdate(
                effectivePrompt, interval, effectiveCron,
                runNow != null ? runNow : existing.isRunNow()
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

        GoalEngine engine = currentContext().getGoalEngine();
        AgentSession session = engine == null ? null : engine.sessionOf(sessionId);
        GoalState gs = session == null ? null : engine.state(session);
        if (gs == null || !taskId.equals(gs.getId())) {
            return Result.failure(404, "Task not found");
        }
        if (gs.getStatus() != GoalState.Status.PURSUING) {
            return Result.failure(400, "Goal cannot be paused in state: " + gs.getStatus());
        }

        engine.pause(session, gs);
        if (currentContext().getGoalScheduler() != null) {
            currentContext().getGoalScheduler().unschedule(sessionId);
        }
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

        GoalEngine engine = currentContext().getGoalEngine();
        AgentSession session = engine == null ? null : engine.sessionOf(sessionId);
        GoalState gs = session == null ? null : engine.state(session);
        if (gs == null || !taskId.equals(gs.getId())) {
            return Result.failure(404, "Task not found");
        }
        if (gs.getStatus() != GoalState.Status.PAUSED && gs.getStatus() != GoalState.Status.BLOCKED) {
            return Result.failure(400, "Goal cannot be resumed in state: " + gs.getStatus()
                    + " (only PAUSED or BLOCKED can be resumed)");
        }

        engine.resume(session, gs);
        if (currentContext().getGoalScheduler() != null) {
            currentContext().getGoalScheduler().schedule(sessionId, true);
        }
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

        GoalEngine engine = currentContext().getGoalEngine();
        AgentSession session = engine == null ? null : engine.sessionOf(sessionId);
        GoalState gs = session == null ? null : engine.state(session);
        if (gs == null || !taskId.equals(gs.getId())) {
            return Result.failure(404, "Task not found");
        }

        if (currentContext().getGoalScheduler() != null) {
            currentContext().getGoalScheduler().unschedule(sessionId);
        }
        engine.clear(session);
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

        GoalEngine engine = currentContext().getGoalEngine();
        AgentSession session = engine == null ? null : engine.sessionOf(sessionId);
        GoalState gs = session == null ? null : engine.state(session);
        if (gs == null || !taskId.equals(gs.getId())) {
            return Result.failure(404, "Task not found");
        }

        Map<String, Object> goalMap = new LinkedHashMap<>();
        goalMap.put("condition", gs.getCondition());
        goalMap.put("status", gs.getStatus().name());
        goalMap.put("iteration", gs.getCurrentIteration());
        goalMap.put("consumedTokens", gs.getConsumedTokens());
        goalMap.put("maxTokens", gs.getMaxTokens());

        if (gs.getStartEpochMs() > 0) {
            goalMap.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
        }

        return Result.succeed(goalMap);
    }

    /**
     * 删除循环任务。goal 行（type=GOAL）分流到 GoalEngine 清除单槽。
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

        // goal 分流：列表中 goal 行的 id 即 GoalState.id
        GoalEngine goalEngine = currentContext().getGoalEngine();
        if (goalEngine != null) {
            AgentSession gsSession = goalEngine.sessionOf(sessionId);
            GoalState gs = gsSession == null ? null : goalEngine.state(gsSession);
            if (gs != null && taskId.equals(gs.getId())) {
                if (currentContext().getGoalScheduler() != null) {
                    currentContext().getGoalScheduler().unschedule(sessionId);
                }
                goalEngine.clear(gsSession);
                return Result.succeed();
            }
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
     * 启用/停用循环任务（toggle）。goal 行分流：PURSUING → pause，
     * PAUSED/BLOCKED → resume（与 goal-pause/goal-resume 端点同口径）。
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

        GoalEngine goalEngine = currentContext().getGoalEngine();
        if (goalEngine != null) {
            AgentSession gsSession = goalEngine.sessionOf(sessionId);
            GoalState gs = gsSession == null ? null : goalEngine.state(gsSession);
            if (gs != null && taskId.equals(gs.getId())) {
                if (gs.getStatus() == GoalState.Status.PURSUING) {
                    if (!goalEngine.pause(gsSession, gs)) {
                        return Result.failure(400, "Goal cannot be paused in state: " + gs.getStatus());
                    }
                    if (currentContext().getGoalScheduler() != null) {
                        currentContext().getGoalScheduler().unschedule(sessionId);
                    }
                } else if (gs.getStatus().isResumable()) {
                    if (!goalEngine.resume(gsSession, gs)) {
                        return Result.failure(400, "Goal cannot be resumed in state: " + gs.getStatus());
                    }
                    if (currentContext().getGoalScheduler() != null) {
                        currentContext().getGoalScheduler().schedule(sessionId, true);
                    }
                } else {
                    return Result.failure(400, "Goal is terminal: " + gs.getStatus()
                            + " (use remove to clear)");
                }
                return Result.succeed();
            }
        }

        loopScheduler().toggle(sessionId, taskId);
        return Result.succeed();
    }

    /**
     * 手动触发一次循环任务执行。goal 行分流：立即跑一轮（仅活跃态）。
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

        GoalEngine goalEngine = currentContext().getGoalEngine();
        if (goalEngine != null) {
            AgentSession gsSession = goalEngine.sessionOf(sessionId);
            GoalState gs = gsSession == null ? null : goalEngine.state(gsSession);
            if (gs != null && taskId.equals(gs.getId())) {
                if (!gs.getStatus().isActive()) {
                    return Result.failure(400, "Goal is not active: " + gs.getStatus());
                }
                RunUtil.async(() -> goalEngine.runRound(gsSession));
                return Result.succeed();
            }
        }

        loopScheduler().trigger(sessionId, taskId);
        return Result.succeed();
    }
}
