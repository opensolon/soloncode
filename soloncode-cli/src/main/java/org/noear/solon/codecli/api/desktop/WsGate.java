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
package org.noear.solon.codecli.api.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.loop.GoalTalent;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.codecli.loop.LoopTask;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Code CLI 桌面端 WebSocket 网关（组合根）。
 *
 * <p>原 1384 行的 {@code WsGate} 已按职责拆分，本类保留 WebSocket 生命周期、
 * Goal 流的启动/执行/中断与全部公开契约，具体职责由协作组件承担：</p>
 * <ul>
 *   <li>{@link DesktopInputRouter}：聊天输入解析、Agent 归一、命令分发、HITL 审批；</li>
 *   <li>{@link DesktopStreamTurn}：流回合订阅与 done/error/hitl 终态收尾；</li>
 *   <li>{@link DesktopEventConverter}：ReAct 事件 → WS 协议 JSON 转换；</li>
 *   <li>{@link DesktopGoalBroadcaster}：Goal 生命周期事件广播；</li>
 *   <li>{@link DesktopConfigHandler}：config 消息热更新与 YAML 持久化；</li>
 *   <li>{@link DesktopRunModes}：运行模式纯函数规则。</li>
 * </ul>
 * <p>WS 协议（type/text/sequence/done/error 字段）与 resume 语义零变化。</p>
 *
 * @author bai
 * @since 3.9.1
 */
public class WsGate extends SimpleWebSocketListener {
    private static final Logger LOG = LoggerFactory.getLogger(WsGate.class);
    private static final String SESSION_ID_DESKTOP = "desktop";
    static final String SESSION_ATTR_SELECTED_AGENT = "_agent_selected_tmp";

    /** 桌面请求级审批器：按当前模式决定文件修改和命令是否在执行前暂停。 */
    final HITLInterceptor desktopHitlInterceptor = new HITLInterceptor()
            .onTool("write", (trace, args) -> desktopApprovalReason(trace, "write"))
            .onTool("edit", (trace, args) -> desktopApprovalReason(trace, "edit"))
            .onTool("bash", (trace, args) -> desktopApprovalReason(trace, "bash"));

    final HarnessEngine engine;
    final AgentSettings agentSettings;
    final LoopScheduler loopScheduler;
    final DesktopStreamHub streamHub = new DesktopStreamHub();
    private final DesktopGoalBroadcaster goalBroadcaster;
    private final DesktopEventConverter eventConverter;
    final DesktopStreamTurn streamTurn;
    private final DesktopConfigHandler configHandler;
    private final DesktopInputRouter inputRouter;

    public WsGate(HarnessEngine engine, AgentSettings agentSettings, LoopScheduler loopScheduler) {
        this.engine = engine;
        this.agentSettings = agentSettings;
        this.loopScheduler = loopScheduler;
        this.goalBroadcaster = new DesktopGoalBroadcaster(engine, streamHub);
        this.eventConverter = new DesktopEventConverter(engine);
        this.streamTurn = new DesktopStreamTurn(streamHub, eventConverter);
        this.configHandler = new DesktopConfigHandler(engine);
        this.inputRouter = new DesktopInputRouter(this);
        if (loopScheduler != null) {
            loopScheduler.addGoalListener(goalBroadcaster::onGoalChanged);
        }
    }

    public boolean isSessionBusy(String sessionId) {
        if (Assert.isEmpty(sessionId)) {
            return false;
        }
        try {
            AgentSession session = engine.getSession(sessionId);
            Object value = session.attrs().get("disposable");
            return (value instanceof Disposable && !((Disposable) value).isDisposed()) || org.noear.solon.ai.agent.react.intercept.HITL.isHitl(session);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void configureGoalSession(String sessionId, String modelName, String agentName,
                              String workspace, String reasoningEffort) {
        if (Assert.isEmpty(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }
        AgentSession session = engine.getSession(sessionId);

        if (Assert.isNotEmpty(modelName)) {
            if (modelName.length() > 256 || engine.getModelOrNil(modelName) == null) {
                throw new IllegalArgumentException("Goal model is unavailable");
            }
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, modelName);
        }

        if (Assert.isNotEmpty(agentName)) {
            String normalizedAgent = agentName.trim();
            if (!isValidAgentName(normalizedAgent) || !engine.getAgentManager().hasAgent(normalizedAgent)) {
                throw new IllegalArgumentException("Goal Agent is unavailable");
            }
            session.attrs().put(SESSION_ATTR_SELECTED_AGENT, normalizedAgent);
        } else {
            session.attrs().remove(SESSION_ATTR_SELECTED_AGENT);
        }

        if (Assert.isNotEmpty(workspace)) {
            try {
                Path root = Paths.get(workspace);
                if (!root.isAbsolute()) {
                    throw new IllegalArgumentException("Goal workspace must be absolute");
                }
                root = root.toRealPath().normalize();
                if (!Files.isDirectory(root)) {
                    throw new IllegalArgumentException("Goal workspace not found");
                }
                session.attrs().put(HarnessEngine.ATTR_CWD, root.toString());
            } catch (IOException error) {
                throw new IllegalArgumentException("Goal workspace is invalid", error);
            }
        }

        if (Assert.isNotEmpty(reasoningEffort)
                && ReasoningSupportUtil.normalizeEffort(reasoningEffort) == null) {
            throw new IllegalArgumentException("Invalid reasoning effort");
        }
        ReasoningSupportUtil.putSessionEffort(session, reasoningEffort, true);
        session.attrs().remove("_plan_mode");
        session.attrs().remove(DesktopStreamTurn.SESSION_ATTR_RUN_MODE);
    }

    static String extractGoalObjective(String input, String mode) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        boolean command = trimmed.equalsIgnoreCase("/goal")
                || (trimmed.length() > 5 && trimmed.regionMatches(true, 0, "/goal", 0, 5)
                && Character.isWhitespace(trimmed.charAt(5)));
        if (command) {
            return trimmed.length() == 5 ? "" : trimmed.substring(5).trim();
        }
        return "goal".equalsIgnoreCase(mode) ? trimmed : null;
    }

    void startGoalStream(WebSocket socket, String sessionId, WsMessage req,
                         String objective, String cwd, String agentName) throws IOException {
        if (loopScheduler == null) {
            throw new IllegalStateException("Goal service is unavailable");
        }
        if (Assert.isEmpty(sessionId) || !sessionId.matches("[0-9]{1,18}")) {
            throw new IllegalArgumentException("Invalid Session ID");
        }
        String displayedObjective = Assert.isNotEmpty(req.getGoalObjective())
                ? req.getGoalObjective().trim() : objective;
        if (Assert.isEmpty(displayedObjective) || displayedObjective.length() > 20_000) {
            throw new IllegalArgumentException(Assert.isEmpty(displayedObjective)
                    ? "请输入 Goal 目标" : "Goal 内容不能超过 20000 个字符");
        }

        Long maxTokens = normalizeGoalBudget(req.getGoalMaxTokens(), 1_000_000_000L, "Token");
        Long maxDurationMinutes = normalizeGoalBudget(req.getGoalMaxDurationMinutes(), 525_600L, "时长");
        Integer maxIterations = normalizeGoalIterations(req.getGoalMaxIterations());
        String effectiveObjective = appendGoalAttachments(objective, req.getAttachments(), cwd);

        String goalWorkspace = Assert.isNotEmpty(cwd) && Paths.get(cwd).isAbsolute() ? cwd : null;
        configureGoalSession(sessionId, req.getModel(), agentName, goalWorkspace, req.getReasoningEffort());
        loopScheduler.restore(sessionId);

        synchronized (loopScheduler) {
            LoopTask active = loopScheduler.findActiveGoalInSession(sessionId);
            if (active != null) {
                throw new IllegalStateException("当前对话已有正在执行或可恢复的 Goal");
            }

            goalBroadcaster.clearCompleted(sessionId);
            streamHub.begin(sessionId, socket);
            // 先完成持久化再显式触发，避免 runNow 与任务注册并发竞速。
            LoopTask task = new LoopTask(effectiveObjective, 0, null, LoopTask.TaskType.GOAL, false);
            task.getGoalState().setCondition(displayedObjective);
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
            loopScheduler.trigger(sessionId, task.getId());
        }
    }

    private Long normalizeGoalBudget(Long value, long maximum, String label) {
        if (value == null || value == 0L) {
            return null;
        }
        if (value < 0L || value > maximum) {
            throw new IllegalArgumentException("Goal " + label + "预算无效");
        }
        return value;
    }

    private Integer normalizeGoalIterations(Integer value) {
        if (value == null || value == 0) {
            return null;
        }
        if (value < 0 || value > 10_000) {
            throw new IllegalArgumentException("Goal 轮次限制无效");
        }
        return value;
    }

    private String appendGoalAttachments(String objective, List<WsMessage.WsAttachment> attachments,
                                         String cwd) throws IOException {
        if (attachments == null || attachments.isEmpty()) {
            return objective;
        }
        if (attachments.size() > DesktopAttachmentSupport.MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("附件数量不能超过 10 个");
        }

        int totalAttachmentBytes = 0;
        List<String> names = new ArrayList<>();
        for (WsMessage.WsAttachment attachment : attachments) {
            if (attachment == null || (!("image".equals(attachment.getType()))
                    && !("file".equals(attachment.getType())))) {
                throw new IllegalArgumentException("附件类型无效");
            }
            byte[] bytes = DesktopAttachmentSupport.decode(attachment);
            totalAttachmentBytes += bytes.length;
            if (totalAttachmentBytes > DesktopAttachmentSupport.MAX_TOTAL_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("附件总大小不能超过 50 MB");
            }
            names.add(DesktopAttachmentSupport.save(Paths.get(cwd), attachment.getName(), bytes));
        }

        StringBuilder input = new StringBuilder();
        for (String name : names) {
            input.append("[附件: ").append(name).append("]\n");
        }
        return input.append(objective).toString();
    }

    /** LoopScheduler 的桌面 Goal 执行入口：同步等待一轮结束并返回权威最终答复。 */
    public String runGoalRoundAndCapture(String sessionId, String input, String agentName) {
        AgentSession session;
        try {
            session = engine.getSession(sessionId);
            if (isSessionBusy(sessionId)) {
                return null;
            }
        } catch (Throwable error) {
            LOG.warn("[Desktop] Goal session check failed for {}: {}", sessionId, error.getMessage());
            return null;
        }

        String selectedAgent = agentName;
        if (Assert.isEmpty(selectedAgent)) {
            Object configuredAgent = session.attrs().get(SESSION_ATTR_SELECTED_AGENT);
            selectedAgent = configuredAgent == null ? null : String.valueOf(configuredAgent);
        }
        String selectedModel = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        ChatModel chatModel = engine.getModelOrDefInstance(selectedModel);
        ReActAgent agent = engine.getAgentOrMain(selectedAgent);
        String sessionCwd = String.valueOf(session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, "."));
        String reasoningEffort = ReasoningSupportUtil.getSessionEffort(session);

        Prompt prompt = Prompt.of(input).attrPut("start_time", System.currentTimeMillis());
        applyReasoningEffort(prompt, reasoningEffort);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> finalAnswer = new AtomicReference<>("");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        session.attrs().put("_loop_last_has_tool_calls", false);

        streamHub.emit(sessionId, new ONode().set("type", "goal_round")
                .set("sessionId", sessionId)
                .toJson());

        Disposable disposable = agent.prompt(prompt)
                .session(session)
                .options(options -> {
                    options.chatModel(chatModel);
                    options.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    applyReasoningEffort(options, reasoningEffort);
                })
                .stream()
                .doOnNext(chunk -> {
                    if (chunk instanceof RunEndEvent) {
                        RunEndEvent runEndEvent = (RunEndEvent) chunk;
                        if (Assert.isNotEmpty(runEndEvent.getText())) {
                            finalAnswer.set(runEndEvent.getText());
                        }
                        org.noear.solon.ai.agent.react.ReActTrace trace = runEndEvent.getTrace();
                        if (trace != null && trace.getMetrics() != null) {
                            session.attrs().put("_loop_last_total_tokens", trace.getMetrics().getTotalTokens());
                        }
                        return;
                    }

                    String message = null;
                    if (chunk instanceof ReasonDeltaEvent) {
                        message = eventConverter.onReasonDeltaEvent((ReasonDeltaEvent) chunk, sessionId);
                    } else if (chunk instanceof ToolCallStartEvent) {
                        message = eventConverter.onToolCallStartEvent((ToolCallStartEvent) chunk, sessionId);
                    } else if (chunk instanceof ToolCallEndEvent) {
                        ToolCallEndEvent toolCallEndEvent = (ToolCallEndEvent) chunk;
                        if (toolCallEndEvent.getError() == null
                                && Assert.isNotEmpty(toolCallEndEvent.getToolName())
                                && !GoalTalent.isGoalTool(toolCallEndEvent.getToolName())) {
                            session.attrs().put("_loop_last_has_tool_calls", true);
                        }
                        message = eventConverter.onToolCallEndEvent(toolCallEndEvent, sessionId);
                    } else if (chunk instanceof ReasonEndEvent) {
                        org.noear.solon.ai.agent.react.ReActTrace trace = ((ReasonEndEvent) chunk).getTrace();
                        if (trace != null && trace.getMetrics() != null) {
                            session.attrs().put("_loop_last_total_tokens", trace.getMetrics().getTotalTokens());
                        }
                        message = eventConverter.onReasonEndEvent((ReasonEndEvent) chunk, sessionId);
                    }
                    if (Assert.isNotEmpty(message)) {
                        streamHub.emit(sessionId, message);
                    }
                })
                .doOnError(failure::set)
                .doFinally(signal -> {
                    session.attrs().remove("disposable");
                    completed.countDown();
                })
                .subscribe();

        Disposable previous = (Disposable) session.attrs().put("disposable", disposable);
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
        }

        try {
            completed.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            disposable.dispose();
            throw new IllegalStateException("Goal round interrupted", error);
        }

        if (failure.get() != null) {
            String message = failure.get().getMessage();
            throw new IllegalStateException(Assert.isEmpty(message)
                    ? failure.get().getClass().getSimpleName() : message, failure.get());
        }
        if (Assert.isEmpty(finalAnswer.get())) {
            throw new IllegalStateException("Goal round returned no final answer");
        }
        return finalAnswer.get();
    }

    public void interruptGoalSession(String sessionId) {
        try {
            AgentSession session = engine.getSession(sessionId);
            Object running = session.attrs().remove("disposable");
            if (running instanceof Disposable && !((Disposable) running).isDisposed()) {
                ((Disposable) running).dispose();
            }
        } catch (Throwable error) {
            LOG.debug("[Desktop] Goal interrupt skipped for {}: {}", sessionId, error.getMessage());
        }
    }

    @Override
    public void onOpen(WebSocket socket) {
        String sessionId = socket.paramOrDefault("sessionId", SESSION_ID_DESKTOP);
        String sessionCwd = socket.param(AgentFlags.X_SESSION_CWD);//工作区

        if (Assert.isNotEmpty(sessionId)) {
            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                socket.send("{\"type\":\"error\",\"text\":\"Invalid Session ID\"}");
                socket.close();
                return;
            }
        }

        if (Assert.isNotEmpty(sessionCwd)) {
            if (sessionCwd.contains("..")) {
                socket.send("{\"type\":\"error\",\"text\":\"Invalid Session Cwd\"}");
                socket.close();
                return;
            }

            AgentSession session = engine.getSession(sessionId);
            session.attrs().putIfAbsent(HarnessEngine.ATTR_CWD, sessionCwd);
        }

        if ("1".equals(socket.param("resume"))) {
            long afterSequence = parseSequence(socket.param("afterSequence"));
            if (!streamHub.attach(sessionId, socket, afterSequence)) {
                socket.send(new ONode().set("type", "error")
                        .set("sessionId", sessionId)
                        .set("text", "会话流已失效，无法恢复连接")
                        .toJson());
                socket.close();
            }
        }
    }

    @Override
    public void onClose(WebSocket socket) {
        streamHub.detach(socket);
    }

    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        try {
            // 先判断消息类型（config 消息结构不同于 chat 消息）
            ONode root = ONode.ofJson(text);
            String msgType = root.get("type") != null ? root.get("type").getString() : null;

            if ("config".equals(msgType)) {
                configHandler.handle(socket, root);
                return;
            }

            if ("hitl_action".equals(msgType)) {
                inputRouter.handleHitlAction(socket, root);
                return;
            }

            inputRouter.handleChat(socket, root);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            socket.send(new ONode().set("type", "error")
                    .set("text", errorMsg).toJson());
        }
    }

    private long parseSequence(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    boolean isValidAgentName(String name) {
        if (Assert.isEmpty(name)) return false;
        int codePointCount = name.codePointCount(0, name.length());
        if (codePointCount > 64) return false;

        for (int offset = 0; offset < name.length(); ) {
            int codePoint = name.codePointAt(offset);
            if (!Character.isLetterOrDigit(codePoint) && codePoint != '-' && codePoint != '_') {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    int firstWhitespaceIndex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    String removeLeadingAgentMention(String input, String agentName) {
        String mention = "@" + agentName;
        if (!input.startsWith(mention)) return input;
        if (input.length() > mention.length() && !Character.isWhitespace(input.charAt(mention.length()))) {
            return input;
        }

        int contentStart = mention.length();
        while (contentStart < input.length() && Character.isWhitespace(input.charAt(contentStart))) {
            contentStart++;
        }
        return input.substring(contentStart);
    }

    void applyReasoningEffort(Prompt prompt, String reasoningEffort) {
        ReasoningSupportUtil.applyToPrompt(prompt, reasoningEffort);
    }

    private void applyReasoningEffort(org.noear.solon.ai.agent.react.ReActOptionsAmend options, String reasoningEffort) {
        ReasoningSupportUtil.applyToOptions(options, reasoningEffort);
    }

    private String desktopApprovalReason(org.noear.solon.ai.agent.react.ReActTrace trace, String toolName) {
        org.noear.solon.ai.agent.AgentSession session = trace == null ? null : trace.getSession();
        Object configuredMode = session == null ? null : session.attrs().get(DesktopStreamTurn.SESSION_ATTR_RUN_MODE);
        String runMode = DesktopRunModes.normalize(configuredMode == null ? null : String.valueOf(configuredMode));
        if (!DesktopRunModes.requiresApproval(runMode, toolName)) {
            return null;
        }
        if ("bash".equals(toolName)) {
            return "桌面执行模式要求批准此命令";
        }
        return "桌面审批执行模式要求批准此文件修改";
    }

    /** 兼容入口：运行模式归一化（原 WsGate.normalizeDesktopRunMode），测试与既有调用仍可用。 */
    static String normalizeDesktopRunMode(String mode) {
        return DesktopRunModes.normalize(mode);
    }

    /** 兼容入口：是否需要桌面审批（原 WsGate.requiresDesktopApproval）。 */
    static boolean requiresDesktopApproval(String mode, String toolName) {
        return DesktopRunModes.requiresApproval(mode, toolName);
    }
}
