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

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.talents.mount.catalog.SkillDescriptor;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.api.web.WebGate;
import org.noear.solon.codecli.session.steer.SteerMessage;
import org.noear.solon.codecli.session.steer.SteerInterceptor;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.config.entity.GeneralGroupDo;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.server.io.LimitedInputException;
import org.noear.solon.server.io.LimitedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聊天交互 Controller（原 WebController 的 chat/model/steer 域）。
 *
 * <p>职责：模型与子代理查询/切换、命令提示（hints）、聊天输入、UI 动作回传、运行中插话（steer）。</p>
 */
public class ChatWebController extends AbstractWebController {
    private static final Logger LOG = LoggerFactory.getLogger(ChatWebController.class);

    public ChatWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    /**
     * 查询可用 AI 模型列表及当前选中模型、推理水平。
     * <p>从引擎配置中获取所有可用模型，若指定了 sessionId 则返回该会话当前选中的模型，
     * 否则返回引擎默认主模型。每项附带 supportsReasoning / reasoningEfforts 等能力字段。</p>
     *
     * @param sessionId 可选的会话 ID，用于获取该会话当前选中的模型
     * @return 包含 list、selected、reasoningEffort 的结果对象
     * @throws Exception 会话查询异常
     */
    @Get
    @Mapping("/web/chat/models")
    public Result<Map> models(@Param(value = "sessionId", required = false) String sessionId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map> list = new ArrayList<>();

        HarnessEngine currentEngine = engine();
        for (org.noear.solon.ai.chat.ChatConfig config : currentEngine.getModels()) {
            if (config.isEnabled()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("model", config.getModel());
                item.put("name", config.getNameOrModel());
                item.put("description", config.getDescriptionOrModel());
                item.put("contextLength", config.getContextLength());
                item.put("standard", config.getStandardOrProvider());
                ReasoningSupportUtil.ModelCapability cap = ReasoningSupportUtil.resolveCapability(config);
                item.putAll(ReasoningSupportUtil.toCapabilityMap(cap));
                list.add(item);
            }
        }
        list.sort((a, b) -> {
            String nameA = (String) a.getOrDefault("name", "");
            String nameB = (String) b.getOrDefault("name", "");
            return nameA.compareToIgnoreCase(nameB);
        });

        data.put("list", list);

        String selected = "";
        String reasoningEffort = null;
        String thinkingMode = null;

        //全局默认（设置→通用）：新会话、以及未显式表态的老会话都回填到这一层，
        //让 UI 的 pill 直接画成选中态（否则用户仍会看到 auto，觉得“没记住”）。
        GeneralGroupDo general = currentContext().getSettings() == null
                ? null : currentContext().getSettings().getGeneral();
        String globalThinking = general == null ? null : general.getDefaultThinkingMode();
        String globalEffort = general == null ? null : general.getDefaultReasoningEffort();

        if (Assert.isNotEmpty(list)) {
            if (Assert.isNotEmpty(sessionId)) {
                org.noear.solon.ai.agent.AgentSession session = currentEngine.getSession(sessionId);
                selected = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);

                if (selected != null) {
                    selected = currentEngine.getModelOrDef(selected).getNameOrModel();
                } else {
                    selected = currentEngine.getModelOrDef(null).getNameOrModel();
                }

                reasoningEffort = ReasoningSupportUtil.hasExplicitEffort(session)
                        ? ReasoningSupportUtil.getSessionEffort(session)
                        : ReasoningSupportUtil.normalizeEffort(globalEffort);
                thinkingMode = ReasoningSupportUtil.hasExplicitThinkingMode(session)
                        ? ReasoningSupportUtil.getSessionThinkingMode(session)
                        : ReasoningSupportUtil.normalizeThinkingMode(globalThinking);
            } else {
                selected = currentEngine.getModelOrDef(null).getNameOrModel();
                reasoningEffort = ReasoningSupportUtil.normalizeEffort(globalEffort);
                thinkingMode = ReasoningSupportUtil.normalizeThinkingMode(globalThinking);
            }

            // 防御：默认模型可能被禁用（getModelOrDef 不校验 isEnabled），导致 selected
            // 不在启用列表 list 中，前端 getCurrentModelMeta() 返回 null 后会把
            // 思考模式/推理强度面板隐藏。此处确保 selected 一定落在 list 内。
            if (!containsModelName(list, selected)) {
                selected = (String) list.get(0).get("name");
            }

            //按选中模型能力 clamp：全局设 max 碰上只支持三档的模型时，不能把错档位发给 UI
            reasoningEffort = ReasoningSupportUtil.clampEffort(reasoningEffort, capabilityOf(list, selected));
        }

        data.put("selected", selected);
        data.put("reasoningEffort", reasoningEffort == null ? "" : reasoningEffort);
        data.put("thinkingMode", thinkingMode == null ? "" : thinkingMode);

        // 读取该会话已选中的子代理
        String selectedAgent = "";
        if (Assert.isNotEmpty(sessionId)) {
            try {
                org.noear.solon.ai.agent.AgentSession session = currentEngine.getSession(sessionId);
                String agentVal = session.getContext().getAs(HarnessEngine.CTX_AGENT_SELECTED);
                selectedAgent = (agentVal != null) ? agentVal : "";
            } catch (Exception ignored) {
                // 会话不存在或已过期
            }
        }
        data.put("selectedAgent", selectedAgent);

        return Result.succeed(data);
    }

    /**
     * 从已组装的模型列表里取回指定模型的推理能力（避免二次 resolveCapability）。
     */
    @SuppressWarnings("unchecked")
    private static ReasoningSupportUtil.ModelCapability capabilityOf(List<Map> list, String name) {
        ReasoningSupportUtil.ModelCapability cap = new ReasoningSupportUtil.ModelCapability();
        if (name == null || list == null) {
            return cap;
        }
        for (Map item : list) {
            if (name.equals(item.get("name"))) {
                Object supports = item.get("supportsReasoning");
                cap.supportsReasoning = supports instanceof Boolean && (Boolean) supports;
                Object efforts = item.get("reasoningEfforts");
                if (efforts instanceof List) {
                    cap.reasoningEfforts = (List<String>) efforts;
                }
                Object def = item.get("defaultReasoningEffort");
                if (def != null) {
                    cap.defaultReasoningEffort = String.valueOf(def);
                }
                break;
            }
        }
        return cap;
    }

    /**
     * 判断 selected 模型名是否存在于已过滤 enabled 的模型列表中。
     */
    private static boolean containsModelName(List<Map> list, String name) {
        if (name == null || list == null || list.isEmpty()) {
            return false;
        }
        for (Map item : list) {
            if (name.equals(item.get("name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 切换指定会话的 AI 模型 / 推理水平 / 思考模式。
     * <p>将选项写入会话上下文并更新快照，后续该会话的 AI 交互将使用新配置。
     * 思考模式（thinkingMode）与推理强度（reasoningEffort）是独立维度。</p>
     *
     * @param sessionId       会话 ID
     * @param modelName       目标模型名称（可选，仅改 effort 时可省略）
     * @param reasoningEffort 推理水平 low|medium|high|max|auto（可选）
     * @param thinkingMode    思考模式 on|off|auto（可选，独立于推理强度）
     * @return 操作结果
     * @throws Exception 会话操作异常
     */
    @Post
    @Mapping("/web/chat/models/select")
    public Result models_select(@Param("sessionId") String sessionId,
                                @Param(value = "modelName", required = false) String modelName,
                                @Param(value = "reasoningEffort", required = false) String reasoningEffort,
                                @Param(value = "thinkingMode", required = false) String thinkingMode) throws Exception {
        String userId = getCurrentUserId();
        org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId, userId);

        if (Assert.isNotEmpty(modelName)) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, modelName);
        }

        // reasoningEffort 参数出现即写入（含空串表示 auto 清除）
        boolean effortProvided = reasoningEffort != null;
        ReasoningSupportUtil.putSessionEffort(session, reasoningEffort, effortProvided);

        // thinkingMode 参数出现即写入（含空串表示不干预/清除）
        boolean modeProvided = thinkingMode != null;
        ReasoningSupportUtil.putSessionThinkingMode(session, thinkingMode, modeProvided);

        session.updateSnapshot();

        return Result.succeed();
    }

    /**
     * 切换指定会话的子代理选择器状态。
     * <p>将选择写入会话上下文并更新快照，后续请求将使用新配置。</p>
     *
     * @param sessionId 会话 ID
     * @param agentName 目标子代理名称（空值或无效值表示使用主 Agent）
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/agents/select")
    public Result agents_select(@Param("sessionId") String sessionId,
                                @Param(value = "agentName", required = false) String agentName) throws Exception {
        String userId = getCurrentUserId();
        org.noear.solon.ai.agent.AgentSession session = sessionManager().getSession(sessionId, userId);
        session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED, agentName != null ? agentName : "");
        session.updateSnapshot();
        return Result.succeed();
    }

    /**
     * 获取可用的命令和子代理列表。
     * <p>从引擎的命令注册表中获取所有非 CLI-Only 的命令，
     * 以及所有已注册的子代理（Agent），合并返回给前端用于命令补全和展示。</p>
     *
     * @return 命令/子代理列表，每项包含 name、description、type（command 或 subagent）
     */
    @Get
    @Mapping("/web/chat/hints")
    public Result<List<Map>> hints() {
        List<Map> data = new ArrayList<>();
        HarnessEngine currentEngine = engine();
        for (Command cmd : currentEngine.getCommandRegistry().all()) {
            if (cmd.cliOnly()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", cmd.name());
            item.put("description", cmd.description());
            item.put("type", "command");
            data.add(item);
        }

        for (AgentDefinition definition : currentEngine.getAgentManager().getAgents()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", definition.getName());
            item.put("description", definition.getDescription());
            item.put("type", "subagent");
            data.add(item);
        }

        Set<String> added = new HashSet<>();
        for (SkillDescriptor skill : currentEngine.getSkills()) {
            if (added.contains(skill.getName())) {
                continue;
            } else {
                added.add(skill.getName());
            }

            String desc = skill.getDescription();
            if (desc != null) {
                // 取第一行，并限制最大长度
                int newlineIdx = desc.indexOf('\n');
                if (newlineIdx > 0) {
                    desc = desc.substring(0, newlineIdx);
                }
                if (desc.length() > 30) {
                    desc = desc.substring(0, 30) + "...";
                }
            }

            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", skill.getName());
            item.put("description", desc);
            String skillId = skill.getId();
            int slash = skillId.indexOf('/');
            item.put("mountAlias", slash > 0 ? skillId.substring(0, slash) : "");
            item.put("type", "skill");
            data.add(item);
        }

        return Result.succeed(data);
    }

    /**
     * 聊天输入入口：解析请求参数后路由到 WebGate 处理。
     * <p>接收用户输入的文本消息、附件文件、模型选择、推理选项和会话标识，
     * 经安全校验后委派给 {@link WebGate#onChatInput} 进行异步 AI 处理。
     * AI 处理结果通过 WebSocket 实时推送到前端，本接口仅返回简单成功响应。</p>
     *
     * @param ctx             Solon 请求上下文，用于读取请求头
     * @param input           用户输入的文本消息
     * @param attachments     上传的附件文件数组，可为 null
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应
     * @param model           指定的 AI 模型名称，可为 null（使用默认模型）
     * @param sessionId       会话 ID，若为空则从请求头 X-Session-Id 获取
     * @param selectedAgent   子代理选择器指定的名称，可为 null 或空（使用主 Agent）
     * @return 操作结果（AI 结果通过 WebSocket 推送）
     */
    @Mapping("/web/chat/input")
    public Result chat_input(Context ctx, String input, UploadedFile inputPayload,
                             UploadedFile[] attachments, String attachmentTypes[],
                             String model, String sessionId,
                             @Param(value = "reasoningEffort", required = false) String reasoningEffort,
                             @Param(value = "thinkingMode", required = false) String thinkingMode,
                             @Param(value = "selectedAgent", required = false) String selectedAgent) {
        try {
            // 新版 Web 将长文本作为文件 part 发送，绕开 multipart 普通字段受 readBuffer
            // 大小限制的问题；保留 input 参数以兼容旧页面和其他调用方。
            if (inputPayload != null) {
                try {
                    //不能超过 100k
                    input = IoUtil.transferToString(new LimitedInputStream(inputPayload.getContent(), 100_000));
                } catch (LimitedInputException e) {
                    ctx.status(413);
                    ctx.output(e.getMessage());
                    return null;
                }
            }

            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.headerOrDefault("X-Session-Id", "web");
            }
            String sessionCwd = ctx.header("X-Session-Cwd");

            if (!isValidSessionId(sessionId)) {
                ctx.status(400);
                ctx.output("Invalid Session ID");
                return null;
            }

            if (Assert.isNotEmpty(sessionCwd)) {
                if (sessionCwd.contains("..")) {
                    ctx.status(400);
                    ctx.output("Invalid Session Cwd");
                    return null;
                }
            }

            if (!canWriteSession(sessionId)) {
                ctx.status(404);
                ctx.output("Session not found");
                return null;
            }
            String hitlAction = ctx.param("hitlAction");
            String hitlCallId = ctx.param("hitlCallId");

            // HITL 审批时，将前端回传的 callUuid 写入 session context，供 WebGate 精确定位决策
            if (Assert.isNotEmpty(hitlAction) && Assert.isNotEmpty(hitlCallId)) {
                String userId = getCurrentUserId();
                sessionManager().getSession(sessionId, userId).getContext().put(WebGate.CTX_HITL_CALL_ID, hitlCallId);
            }

            // 路由到 WebGate 处理（AI 结果通过 WebSocket 推送到前端）
            runtimePort().onChatInput(currentContext(), sessionId, sessionCwd, input, model, attachments, attachmentTypes, hitlAction, null,
                    reasoningEffort, thinkingMode, selectedAgent);

            // 返回简单 JSON，前端通过 WebSocket 接收 AI 结果
            return Result.succeed();
        } catch (Throwable e) {
            LOG.error("[Web] chat_input error: {}", e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }

    /**
     * UI 动作回传入口（对应 SAEP 2.0 {@code ui.action}）。
     *
     * <p>前端在 UI 块（{@code ui.render} 渲染）上点击动作时调用本接口，将动作封装为
     * {@code {"__ui_action__":{blockId, actionId, formData}}} 的标准回传结构，并复用既有聊天
     * 输入通道（{@link WebGate#onChatInput}）作为一条用户消息下发，使 Agent 在新一轮中响应该动作。
     * 与 HITL 不同，UI 动作不阻塞原工具：它作为独立的用户回合进入，由 LLM 决定后续行为。</p>
     *
     * @param sessionId  会话 ID，若为空则从请求头 X-Session-Id 获取
     * @param blockId    UI 块实例稳定 ID（与 ui.render 的 blockId 对应），必填
     * @param actionId   动作 ID（与 ui.render 的 actions[].id 对应），必填
     * @param formData   动作附带的表单数据，JSON 对象字符串，可为空
     * @param model      指定的 AI 模型名称，可为 null（使用默认模型）
     * @param selectedAgent 子代理选择器指定的名称，可为 null 或空（使用主 Agent）
     * @return 操作结果（Agent 响应通过 WebSocket 推送）
     */
    @Mapping("/web/chat/ui_action")
    public Result chat_ui_action(Context ctx, String sessionId, String blockId, String actionId,
                                 @Param(value = "formData", required = false) String formData,
                                 String model,
                                 @Param(value = "selectedAgent", required = false) String selectedAgent) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.headerOrDefault("X-Session-Id", "web");
            }
            String sessionCwd = ctx.header("X-Session-Cwd");

            if (!isValidSessionId(sessionId)) {
                ctx.status(400);
                ctx.output("Invalid Session ID");
                return null;
            }
            if (Assert.isNotEmpty(sessionCwd) && sessionCwd.contains("..")) {
                ctx.status(400);
                ctx.output("Invalid Session Cwd");
                return null;
            }
            if (Assert.isEmpty(blockId) || Assert.isEmpty(actionId)) {
                ctx.status(400);
                ctx.output("blockId and actionId are required");
                return null;
            }

            if (!canWriteSession(sessionId)) {
                ctx.status(404);
                ctx.output("Session not found");
                return null;
            }

            ONode action = new ONode();
            action.set("blockId", blockId);
            action.set("actionId", actionId);
            if (Assert.isNotEmpty(formData)) {
                try {
                    action.set("formData", ONode.ofJson(formData));
                } catch (Throwable ex) {
                    action.set("formData", new ONode());
                }
            } else {
                action.set("formData", new ONode());
            }
            ONode payload = new ONode();
            payload.set("__ui_action__", action);
            String input = payload.toJson();

            // 复用既有输入通道：作为一条来源为 web 的用户消息下发
            runtimePort().onChatInput(currentContext(), sessionId, sessionCwd, input, model, null, null, null, "web",
                    null, null, selectedAgent);

            return Result.succeed();
        } catch (Throwable e) {
            LOG.error("[Web] chat_ui_action error: {}", e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }

    /**
     * 运行中插话（steer）：向正在运行的会话任务插入一条用户消息。
     *
     * <p>消息存入会话级邮箱（transient，不落快照），由 SteerInterceptor 在下一个推理回合
     * 开始时（onReasonStart 采样边界）注入工作记忆，不打断进行中的模型流与工具调用。
     * 参考方案：docs/steering-inject-plan.md（对齐 Codex steering 三件套）。</p>
     *
     * <p>应答契约：200 STEERED=已接受（下一步生效）；409 NOT_RUNNING=会话空闲，前端回落为普通发送；
     * 409 TURN_CHANGED=runId 与当前运行不符，前端转为排队；409 BOX_FULL=邮箱满；
     * 400 EMPTY_TEXT / TEXT_TOO_LONG=参数非法。</p>
     *
     * @param sessionId 会话 ID
     * @param runId     前端所见的当前运行 ID（可选；来自事件信封 runId，防跨任务错投）
     * @param steerId   前端生成的稳定 ID（可选；旧客户端未传时由后端生成）
     * @param text      插话文本
     * @return 操作结果
     */
    @Post
    @Mapping("/web/chat/steer")
    public Result steerSession(@Param("sessionId") String sessionId,
                               @Param(value = "runId", required = false) String runId,
                               @Param(value = "steerId", required = false) String steerId,
                               @Param("text") String text) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (!canWriteSession(sessionId)) return Result.failure(404, "Session not found");
        if (text == null || text.trim().isEmpty()) {
            return Result.failure(400, "EMPTY_TEXT");
        }
        if (text.trim().length() > SteerInterceptor.MAX_TEXT_LENGTH) {
            return Result.failure(400, "TEXT_TOO_LONG");
        }

        if (steerId != null && !steerId.trim().isEmpty()
                && steerId.trim().length() > SteerInterceptor.MAX_ID_LENGTH) {
            return Result.failure(400, "INVALID_STEER_ID");
        }
        if (!runtimePort().isSessionBusy(engine(), sessionId)) {
            return Result.failure(409, "NOT_RUNNING");
        }

        org.noear.solon.ai.agent.AgentSession session = engine().getSession(sessionId);
        if (session == null) {
            return Result.failure(409, "NOT_RUNNING");
        }

        // Web 端必须绑定明确的 runId。任务首个事件到达前尚不能确认归属，此时让前端转普通排队，
        // 避免无 runId 请求落入任务结束/切换窄窗后成为无人消费的孤儿插话。
        if (runId == null || runId.trim().isEmpty()) {
            return Result.failure(409, "TURN_CHANGED");
        }

        // 核心入队逻辑与 IM 端共用（activeRunId 绑定校验 + offer + offer 后按 busy 复查回滚）。
        // source 传 null：Web 插话被 dropped 时由前端转排队，无需后端兑底。
        SteerInterceptor.SteerResult result = SteerInterceptor.steer(
                session, runId, steerId, text, null,
                () -> runtimePort().isSessionBusy(engine(), sessionId));

        switch (result.getStatusCode()) {
            case "STEERED": {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", "STEERED");
                data.put("steerId", result.getSteerId());
                data.put("queued", result.getQueued());
                return Result.succeed(data);
            }
            case "EMPTY_TEXT":
            case "TEXT_TOO_LONG":
            case "INVALID_STEER_ID":
                return Result.failure(400, result.getStatusCode());
            default:
                // NOT_RUNNING / TURN_CHANGED / BOX_FULL / DUPLICATE_STEER_ID
                return Result.failure(409, result.getStatusCode());
        }
    }

    /**
     * 撤销一条尚未到达采样边界的运行中插话。
     *
     * <p>只有消息仍在会话邮箱中时才返回成功；若消费线程已将其取出，则返回 NOT_PENDING，
     * 前端保留待生效项，等待 applied/dropped 终态事件，避免把“已经生效”伪装成“删除成功”。</p>
     */
    @Post
    @Mapping("/web/chat/steer/cancel")
    public Result cancelSteerSession(@Param("sessionId") String sessionId,
                                     @Param(value = "runId", required = false) String runId,
                                     @Param("steerId") String steerId) {
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }
        if (!canWriteSession(sessionId)) return Result.failure(404, "Session not found");
        if (steerId == null || steerId.trim().isEmpty()
                || steerId.length() > SteerInterceptor.MAX_ID_LENGTH) {
            return Result.failure(400, "INVALID_STEER_ID");
        }

        org.noear.solon.ai.agent.AgentSession session = engine().getSession(sessionId);
        if (session == null) {
            return Result.failure(409, "NOT_PENDING");
        }
        java.util.Queue<SteerMessage> box;
        synchronized (session.attrs()) {
            String activeRunId = (String) session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID);
            if (runId == null || !runId.equals(activeRunId)) {
                return Result.failure(409, "TURN_CHANGED");
            }

            box = SteerInterceptor.steerBox(session);
            if (!SteerInterceptor.cancel(box, steerId.trim())) {
                return Result.failure(409, "NOT_PENDING");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "CANCELED");
        data.put("steerId", steerId.trim());
        data.put("queued", box.size());
        return Result.succeed(data);
    }
}
