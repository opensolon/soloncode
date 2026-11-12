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
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalSpec;
import org.noear.solon.ai.harness.extension.goal.GoalPatch;
import org.noear.solon.ai.harness.extension.goal.GoalStatus;
import org.noear.solon.ai.harness.extension.goal.GoalSnapshot;
import org.noear.solon.ai.harness.extension.loop.LoopPatch;
import org.noear.solon.ai.harness.extension.loop.LoopSpec;
import org.noear.solon.ai.harness.extension.loop.LoopTask;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.codecli.util.HarnessResultUtil;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

        for (LoopTask t : loop().list(sessionId)) {
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
        // 任务已在插件挂接时全量加载，此处只读快照
        Map<String, List<LoopTask>> tasksBySession = loop().listAll();
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
        GoalExtension goal = currentContext().getGoalExtension();
        if (goal == null) {
            return null;
        }
        GoalSnapshot task = goal.get(sessionId);
        if (task == null) {
            return null;
        }
        // goal 自己的字段由插件产出；与 loop 行混排所需的列表字段由宿主补齐
        Map<String, Object> item = task.toMap();
        item.put("enabled", task.getStatus().isActive());
        item.put("cancelled", false);
        item.put("intervalMinutes", 0);
        item.put("runNow", true);
        return item;
    }

    /**
     * 列表行分流：这一行是不是本会话的 goal（每个会话只有一个 goal，门面按 sessionId 定位；
     * 行 id 只用来区分 goal 行与 loop 行）。
     *
     * @return 是 goal 行时返回其快照，否则 null
     */
    private GoalSnapshot goalRow(GoalExtension goal, String sessionId, String taskId) {
        if (goal == null || taskId == null) {
            return null;
        }
        GoalSnapshot snapshot = goal.get(sessionId);
        return snapshot != null && taskId.equals(snapshot.getId()) ? snapshot : null;
    }

    /**
     * Web 端创建会话 goal（与桌面版 /desktop/chat/goals/add 同语义）。
     *
     * <p>保留在 loop/add 内部分流而非新开端点，因为前端表单与列表渲染是
     * 一套 loop 契约（同构 Map），新开端点需要同步改表单提交与列表刷新两处。</p>
     *
     * @param locale 创建者的界面语言（null = 跟随引擎）：目标记下它，之后每一轮都用它
     */
    private Result createSessionGoal(String sessionId, String prompt, Long maxTokens, Long maxDurationMs, Locale locale) {
        GoalExtension goal = currentContext().getGoalExtension();
        if (goal == null) {
            return Result.failure(503, "Goal service is unavailable");
        }
        try {
            HarnessResult<GoalSnapshot> created = goal.create(sessionId, GoalSpec.of(prompt)
                    .maxTokens(maxTokens != null ? maxTokens : 0L)
                    .maxDurationMs(maxDurationMs != null ? maxDurationMs : 0L)
                    .locale(locale));
            if (!created.isOk()) {
                // 单槽语义：已有活跃/可恢复 goal 时为 INVALID（409）
                return HarnessResultUtil.failure(created, "Goal cannot be created");
            }
            return Result.succeed(created.getValue().getId());
        } catch (IllegalArgumentException e) {
            return Result.failure(400, e.getMessage());
        }
    }

    /**
     * Web 端编辑会话 goal（目标正文与预算）。仅非终态可编辑，语义同桌面版 goals/update。
     *
     * @param locale 编辑者的界面语言（null 或空串 = 不改）
     */
    private Result updateSessionGoal(GoalExtension goal, String sessionId,
                                     String prompt, Long maxTokens, Long maxDurationMs, String locale) {
        // 暂停 → 停流 → 停等 → 改写 → 恢复由门面一步完成；停流走 Web 渠道（清积压、推终态帧）
        WorkspaceContext ctx = currentContext();
        Runnable stopper = runtimePort() == null ? null
                : () -> runtimePort().interruptSession(ctx, sessionId);
        GoalPatch patch = GoalPatch.create()
                .objective(prompt)
                .maxTokens(maxTokens)
                .maxDurationMs(maxDurationMs);
        if (locale != null && !locale.isEmpty()) {
            // 未传（或传空串）= 保持目标现有的语言
            patch.locale(AiI18n.toLocale(locale));
        }
        // 终态不可编辑 / 旧轮次未及时停稳（可重试）都是 INVALID（409）
        HarnessResult<GoalSnapshot> updated = goal.update(sessionId, patch, stopper);
        return HarnessResultUtil.toResult(updated, "Goal cannot be updated");
    }

    /**
     * 构建任务 Map（插件标准视图 + 前端分栏用的 type）
     */
    private Map<String, Object> buildTaskMap(LoopTask t) {
        Map<String, Object> item = t.toMap();
        item.put("type", "HEARTBEAT");
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

        // goal 行分流：单槽语义，id 即 GoalSnapshot.id
        Map<String, Object> goalItem = buildGoalMap(sessionId);
        if (goalItem != null && taskId.equals(goalItem.get("id"))) {
            return Result.succeed(goalItem);
        }

        LoopTask t = loop().get(sessionId, taskId);
        return t == null ? Result.failure(404, "Task not found") : Result.succeed(buildTaskMap(t));
    }

    /**
     * 新增循环任务。
     *
     * @param locale 创建者的界面语言（可空 = 跟随引擎）：任务记下它，之后的定时轮次都用它
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
                          @Param(value = "maxDurationMs", required = false) Long maxDurationMs,
                          @Param(value = "locale", required = false) String locale) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            return Result.failure(400, "prompt is required");
        }

        // goal 已属会话级独立能力（/goal 或 /desktop/chat/goals/add），定时任务页不再创建 GOAL
        Locale taskLocale = AiI18n.toLocale(locale);
        if (type != null && "GOAL".equalsIgnoreCase(type)) {
            return createSessionGoal(sessionId, prompt, maxTokens, maxDurationMs, taskLocale);
        }

        // 请求参数原样交给规格（null = 未设置），校验由插件统一完成
        HarnessResult<LoopTask> scheduled = loop().schedule(sessionId, LoopSpec.of(prompt)
                .everyMinutes(intervalMinutes)
                .cron(cron)
                .runNow(runNow)
                .locale(taskLocale));
        if (!scheduled.isOk()) {
            // 规格不合法历来回 400
            return scheduled.getCode() == HarnessResult.Code.INVALID
                    ? Result.failure(400, scheduled.getDetail())
                    : HarnessResultUtil.failure(scheduled, "Task cannot be scheduled");
        }
        return Result.succeed(scheduled.getValue().getId());
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
                             @Param(value = "runNow", required = false) Boolean runNow,
                             @Param(value = "maxTokens", required = false) Long maxTokens,
                             @Param(value = "maxDurationMs", required = false) Long maxDurationMs,
                             @Param(value = "locale", required = false) String locale) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (taskId == null || taskId.isEmpty()) {
            return Result.failure(400, "taskId is required");
        }

        // goal 行分流：必须在 LoopTask 查找之前，goal 的 taskId 是 GoalSnapshot.id，
        // 不在 loop 任务表中，否则会先返回 404。
        GoalExtension updGoal = currentContext().getGoalExtension();
        if (goalRow(updGoal, sessionId, taskId) != null) {
            return updateSessionGoal(updGoal, sessionId, prompt, maxTokens, maxDurationMs, locale);
        }

        if (type != null && "GOAL".equalsIgnoreCase(type)) {
            return Result.failure(400, "GOAL is a session-scoped capability, use /goal or goal endpoints");
        }

        // 补丁语义由插件承担：null = 保持原值（启停、运行状态、身份均保留），宿主不再手工合并
        LoopPatch patch = LoopPatch.create()
                .prompt(prompt)
                .everyMinutes(intervalMinutes)
                .cron(cron)
                .runNow(runNow);
        if (locale != null && !locale.isEmpty()) {
            // 未传（或传空串）= 保持任务现有的语言
            patch.locale(AiI18n.toLocale(locale));
        }
        HarnessResult<LoopTask> updated = loop().update(sessionId, taskId, patch);
        if (!updated.isOk() && updated.getCode() == HarnessResult.Code.INVALID) {
            return Result.failure(400, updated.getDetail());
        }
        return HarnessResultUtil.toResult(updated, "Task not found");
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
                                    @Param(value = "agentName", required = false) String agentName,
                                    @Param(value = "locale", required = false) String locale) {
        try {
            automationManager().update(id, name, prompt, intervalMinutes, cron, type, runNow, maxTokens, maxDurationMs, modelName, agentName, locale);
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
                             @Param(value = "agentName", required = false) String agentName,
                             @Param(value = "locale", required = false) String locale) {
        if (isAutomationType(type)) {
            try {
                // 任务记下创建者的界面语言：定时轮次由后台调度发起，没有输入可取语言
                String taskId = automationManager().create(name, prompt, intervalMinutes, cron, taskType, runNow,
                        maxTokens, maxDurationMs, modelName, agentName, AiI18n.toLocale(locale));
                return Result.succeed(taskId);
            } catch (IllegalArgumentException | IllegalStateException | IOException e) {
                return Result.failure(400, e.getMessage());
            }
        }
        return loopAdd(sessionId, prompt, intervalMinutes, cron, taskType, runNow, maxTokens, maxDurationMs, locale);
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
                             @Param(value = "agentName", required = false) String agentName,
                             @Param(value = "locale", required = false) String locale) {
        if (isAutomationType(type)) {
            return automationUpdate(id, name, prompt, intervalMinutes, cron, taskType, runNow, maxTokens, maxDurationMs, modelName, agentName, locale);
        }
        return loopUpdate(sessionId, id, prompt, intervalMinutes, cron, taskType, runNow, maxTokens, maxDurationMs, locale);
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

        GoalExtension goal = currentContext().getGoalExtension();
        GoalSnapshot task = goalRow(goal, sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        // pause 门面内含摘调度配对；仅 PURSUING 可暂停
        if (!goal.pause(sessionId).isOk()) {
            return Result.failure(400, "Goal cannot be paused in state: " + task.getStatus());
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

        GoalExtension goal = currentContext().getGoalExtension();
        GoalSnapshot task = goalRow(goal, sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        // resume 门面内含挂调度并立即跑一轮；仅 PAUSED/BLOCKED 可恢复
        if (!goal.resume(sessionId).isOk()) {
            return Result.failure(400, "Goal cannot be resumed in state: " + task.getStatus()
                    + " (only PAUSED or BLOCKED can be resumed)");
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

        GoalExtension goal = currentContext().getGoalExtension();
        // remove 门面内含「先摘调度再清除」
        if (goalRow(goal, sessionId, taskId) == null || !goal.remove(sessionId).isOk()) {
            return Result.failure(404, "Task not found");
        }
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

        GoalExtension goal = currentContext().getGoalExtension();
        GoalSnapshot gs = goalRow(goal, sessionId, taskId);
        if (gs == null) {
            return Result.failure(404, "Task not found");
        }

        Map<String, Object> goalMap = new LinkedHashMap<>();
        goalMap.put("condition", gs.getObjective());
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
     * 删除循环任务。goal 行（type=GOAL）分流到 GoalRuntime 清除单槽。
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

        // goal 分流：列表中 goal 行的 id 即 GoalSnapshot.id
        GoalExtension goal = currentContext().getGoalExtension();
        if (goalRow(goal, sessionId, taskId) != null && goal.remove(sessionId).isOk()) {
            return Result.succeed();
        }

        HarnessResult<Void> removed = loop().remove(sessionId, taskId);
        if (removed.getCode() == HarnessResult.Code.NOT_FOUND) {
            return Result.failure(400, "the task does not exist.");
        }
        return HarnessResultUtil.toResult(removed, "the task does not exist.");
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

        GoalExtension goal = currentContext().getGoalExtension();
        GoalSnapshot gs = goalRow(goal, sessionId, taskId);
        if (gs != null) {
            if (gs.getStatus() == GoalStatus.PURSUING) {
                // pause 门面内含摘调度配对
                if (!goal.pause(sessionId).isOk()) {
                    return Result.failure(400, "Goal cannot be paused in state: " + gs.getStatus());
                }
            } else if (gs.getStatus().isResumable()) {
                // resume 门面内含挂调度并立即跑一轮
                if (!goal.resume(sessionId).isOk()) {
                    return Result.failure(400, "Goal cannot be resumed in state: " + gs.getStatus());
                }
            } else {
                return Result.failure(400, "Goal is terminal: " + gs.getStatus()
                        + " (use remove to clear)");
            }
            return Result.succeed();
        }

        // 门面已收敛为 pause / resume：按当前状态二选一
        LoopTask task = loop().get(sessionId, taskId);
        if (task == null) {
            return Result.failure(404, "Task not found");
        }
        HarnessResult<Void> toggled = task.isEnabled()
                ? loop().pause(sessionId, taskId)
                : loop().resume(sessionId, taskId);
        return HarnessResultUtil.toResult(toggled, "Task cannot be toggled");
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

        GoalExtension goal = currentContext().getGoalExtension();
        GoalSnapshot gs = goalRow(goal, sessionId, taskId);
        if (gs != null) {
            if (!goal.trigger(sessionId).isOk()) {
                return Result.failure(400, "Goal is not active: " + gs.getStatus());
            }
            return Result.succeed();
        }

        return HarnessResultUtil.toResult(loop().trigger(sessionId, taskId), "Task not found");
    }
}
