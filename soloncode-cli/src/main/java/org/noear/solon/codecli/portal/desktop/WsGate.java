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
package org.noear.solon.codecli.portal.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ObservationChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.codecli.command.builtin.GoalState;
import org.noear.solon.codecli.command.builtin.GoalTalent;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.command.builtin.LoopTask;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Code CLI WebSocket 网关
 * <p>基于 WebSocket 的流式通信接口</p>
 *
 * @author bai
 * @since 3.9.1
 */

public class WsGate extends SimpleWebSocketListener {
    private static final Logger LOG = LoggerFactory.getLogger(WsGate.class);
    private static final String SESSION_ID_DESKTOP = "desktop";
    private static final String SESSION_ATTR_SELECTED_AGENT = "_agent_selected_tmp";
    private static final String SESSION_ATTR_RUN_MODE = "_desktop_run_mode";

    /** 桌面请求级审批器：按当前模式决定文件修改和命令是否在执行前暂停。 */
    private final HITLInterceptor desktopHitlInterceptor = new HITLInterceptor()
            .onTool("write", (trace, args) -> desktopApprovalReason(trace, "write"))
            .onTool("edit", (trace, args) -> desktopApprovalReason(trace, "edit"))
            .onTool("bash", (trace, args) -> desktopApprovalReason(trace, "bash"));

    private final HarnessEngine engine;
    private final AgentSettings agentSettings;
    private final LoopScheduler loopScheduler;
    private final DesktopStreamHub streamHub = new DesktopStreamHub();
    private final Set<String> completedGoalStreams = ConcurrentHashMap.newKeySet();

    public WsGate(HarnessEngine engine, AgentSettings agentSettings, LoopScheduler loopScheduler) {
        this.engine = engine;
        this.agentSettings = agentSettings;
        this.loopScheduler = loopScheduler;
        if (loopScheduler != null) {
            loopScheduler.addGoalListener(this::onGoalChanged);
        }
    }

    boolean isSessionBusy(String sessionId) {
        if (Assert.isEmpty(sessionId)) {
            return false;
        }
        try {
            AgentSession session = engine.getSession(sessionId);
            Object value = session.attrs().get("disposable");
            return (value instanceof Disposable && !((Disposable) value).isDisposed()) || HITL.isHitl(session);
        } catch (Throwable ignored) {
            return false;
        }
    }

    void configureGoalSession(String sessionId, String modelName, String agentName,
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
        session.attrs().remove(SESSION_ATTR_RUN_MODE);
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

    private void startGoalStream(WebSocket socket, String sessionId, WsMessage req,
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

            completedGoalStreams.remove(sessionId);
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

    private void onGoalChanged(String sessionId, LoopTask task, boolean removed) {
        GoalState state = task.getGoalState();
        if (state == null) {
            return;
        }

        String status = removed || task.isCancelled() ? "STOPPED" : state.getStatus().name();
        ONode message = new ONode().set("type", "goal_status")
                .set("sessionId", sessionId)
                .set("goalId", task.getId())
                .set("objective", state.getCondition())
                .set("status", status)
                .set("running", task.isRunning())
                .set("iteration", task.getCurrentIteration())
                .set("maxIterations", state.getMaxIterations())
                .set("consumedTokens", state.getConsumedTokens())
                .set("maxTokens", state.getMaxTokens());
        if (task.getLastResult() != null) {
            message.set("lastResult", task.getLastResult());
        }
        streamHub.emit(sessionId, message.toJson());

        boolean terminal = removed || task.isCancelled() || state.getStatus().isTerminal();
        if (terminal && completedGoalStreams.add(sessionId)) {
            AgentSession session = engine.getSession(sessionId);
            String modelName = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
            if (Assert.isEmpty(modelName)) {
                modelName = engine.getMainModel().getConfig().getNameOrModel();
            }
            long elapsed = Math.max(0L, System.currentTimeMillis() - state.getStartEpochMs());
            streamHub.emit(sessionId, new ONode().set("type", "done")
                    .set("sessionId", sessionId)
                    .set("modelName", modelName)
                    .set("totalTokens", state.getConsumedTokens())
                    .set("elapsedMs", elapsed)
                    .toJson());
        }
    }

    /** LoopScheduler 的桌面 Goal 执行入口：同步等待一轮结束并返回权威最终答复。 */
    String runGoalRoundAndCapture(String sessionId, String input, String agentName) {
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
                    if (chunk instanceof ReActChunk) {
                        ReActChunk react = (ReActChunk) chunk;
                        if (Assert.isNotEmpty(react.getContent())) {
                            finalAnswer.set(react.getContent());
                        }
                        ReActTrace trace = react.getTrace();
                        if (trace != null && trace.getMetrics() != null) {
                            session.attrs().put("_loop_last_total_tokens", trace.getMetrics().getTotalTokens());
                        }
                        return;
                    }

                    String message = null;
                    if (chunk instanceof ReasonChunk) {
                        message = onReasonChunk((ReasonChunk) chunk, sessionId);
                    } else if (chunk instanceof ActionChunk) {
                        message = onActionStartChunk((ActionChunk) chunk, sessionId);
                    } else if (chunk instanceof ObservationChunk) {
                        ObservationChunk observation = (ObservationChunk) chunk;
                        if (observation.getError() == null
                                && Assert.isNotEmpty(observation.getToolName())
                                && !GoalTalent.isGoalTool(observation.getToolName())) {
                            session.attrs().put("_loop_last_has_tool_calls", true);
                        }
                        message = onObservationChunk(observation, sessionId);
                    } else if (chunk instanceof ThoughtChunk) {
                        ReActTrace trace = ((ThoughtChunk) chunk).getTrace();
                        if (trace != null && trace.getMetrics() != null) {
                            session.attrs().put("_loop_last_total_tokens", trace.getMetrics().getTotalTokens());
                        }
                        message = onThoughtChunk((ThoughtChunk) chunk, sessionId);
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

    void interruptGoalSession(String sessionId) {
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
                handleConfigMessage(socket, root);
                return;
            }

            if ("hitl_action".equals(msgType)) {
                handleHitlAction(socket, root);
                return;
            }

            // 解析请求
            WsMessage req = root.toBean(WsMessage.class);
            String sessionId = socket.paramOrDefault("sessionId", "");
            String input = req.getInput();
            String cwd = req.getCwd();

            if (Assert.isEmpty(sessionId)) {
                sessionId = "ws_" + System.currentTimeMillis();
                // 及时通知客户端自动生成的 sessionId
                socket.send(new ONode().set("type", "session")
                        .set("sessionId", sessionId)
                        .toJson());
            }

            AgentSession session = engine.getSession(sessionId);

            if ("[(sec)interrupt]".equals(req.getInput())) {
                LoopTask activeGoal = loopScheduler == null ? null : loopScheduler.findActiveGoalInSession(sessionId);
                Disposable disposable = (Disposable) session.attrs().remove("disposable");
                if (disposable != null) {
                    disposable.dispose();
                }
                if (activeGoal != null) {
                    loopScheduler.remove(sessionId, activeGoal);
                    return;
                }
                AssistantMessage cancelMessage = ChatMessage.ofAssistant("用户已取消任务.");
                ReActTrace trace = session.getContext().getAs(AgentFlags.TRACE_KEY_MAIN);
                if (trace != null) {
                    cancelMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
                }
                session.addMessage(cancelMessage);
                LOG.info("用户已取消任务.");

                String interruptModelName = req.getModel();
                if (interruptModelName == null || interruptModelName.isEmpty()) {
                    interruptModelName = engine.getMainModel().getConfig().getNameOrModel();
                }

                String interruptReason = new ONode().set("type", "reason")
                        .set("sessionId", session.getSessionId())
                        .set("text", "[Task interrupted]")
                        .toJson();
                if (!streamHub.emit(sessionId, interruptReason)) {
                    socket.send(interruptReason);
                }

                String interruptDone = new ONode().set("type", "done")
                        .set("sessionId", session.getSessionId())
                        .set("modelName", interruptModelName)
                        .set("totalTokens", 0)
                        .set("elapsedMs", 0).toJson();
                if (!streamHub.emit(sessionId, interruptDone)) {
                    socket.send(interruptDone);
                }
                return;
            }


            if (Assert.isEmpty(req.getCwd())) {
                cwd = session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, ".").toString();
            }


            // 验证 sessionId
            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                socket.send(new ONode().set("type", "error")
                        .set("text", "Invalid Session ID").toJson());
                return;
            }

            // 验证 cwd
            if (Assert.isNotEmpty(cwd)) {
                if (cwd.contains("..")) {
                    socket.send(new ONode().set("type", "error")
                            .set("text", "Invalid Session Cwd").toJson());
                    return;
                }
                // 桌面端切换项目或继续已有会话时，以本次请求的 cwd 覆盖旧会话工作区。
                session.attrs().put(HarnessEngine.ATTR_CWD, cwd);
            }

            if (Assert.isEmpty(input)) {
                return;
            }

            String agentName = null;
            String currentInput = input;

            String requestedAgent = req.getAgent();
            if (Assert.isNotEmpty(requestedAgent) && !"default".equals(requestedAgent)) {
                requestedAgent = requestedAgent.trim();
                if (!isValidAgentName(requestedAgent) || !engine.getAgentManager().hasAgent(requestedAgent)) {
                    socket.send(new ONode().set("type", "error")
                            .set("sessionId", sessionId)
                            .set("text", "Agent 不可用或已禁用")
                            .toJson());
                    return;
                }
                agentName = requestedAgent;
                currentInput = removeLeadingAgentMention(currentInput, agentName);
            } else if (input.startsWith("@")) {
                int agentNameIdx = firstWhitespaceIndex(input);
                if (agentNameIdx > 1) {
                    String mentionedAgent = input.substring(1, agentNameIdx);
                    if (isValidAgentName(mentionedAgent) && engine.getAgentManager().hasAgent(mentionedAgent)) {
                        agentName = mentionedAgent;
                        currentInput = input.substring(agentNameIdx).trim();
                    }
                }
            }

            if (Assert.isEmpty(currentInput)) {
                socket.send(new ONode().set("type", "error")
                        .set("sessionId", sessionId)
                        .set("text", "请输入发送给 Agent 的内容")
                        .toJson());
                return;
            }

            // 根据前端指定的 model 选择对应 ChatModel
            String modelName = req.getModel();
            ChatModel chatModel = engine.getModelOrDefInstance(modelName);
            
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, modelName);
            if (req.getReasoningEffort() != null) {
                ReasoningSupportUtil.putSessionEffort(session,
                        req.getReasoningEffort(), true);
            }
            // 请求显式 effort 优先；否则用会话 context
            final String reasoningEffort = ReasoningSupportUtil.resolveEffectiveEffort(
                    req.getReasoningEffort(),
                    ReasoningSupportUtil.getSessionEffort(session),
                    null,
                    req.getReasoningEffort() != null);

            // 模式处理：根据前端 mode 字段配置 session 行为
            String mode = normalizeDesktopRunMode(req.getMode());
            session.attrs().put(SESSION_ATTR_RUN_MODE, mode);
            if ("plan".equals(mode)) {
                // 规划模式：只读分析，不执行文件/命令操作
                session.attrs().put("_plan_mode", true);
                if (!currentInput.contains("不要执行") && !currentInput.contains("只分析")) {
                    currentInput = "[规划模式 - 仅分析不执行任何操作] " + currentInput;
                }
            } else if ("auto".equals(mode)) {
                // 自动编辑模式：文件编辑自动放行，shell 命令仍需审批
                session.attrs().remove("_plan_mode");
            } else {
                session.attrs().remove("_plan_mode");
            }
            // default 模式：write/edit/bash 都在真正执行前进入 HITL 审批。

            final ReActAgent agent = engine.getAgentOrMain(agentName);
            // 与 WebStreamBuilder 保持一致：记录本轮真正的源 Agent，供 HITL 恢复继续使用。
            session.attrs().put(SESSION_ATTR_SELECTED_AGENT, agent.name());

            // Goal 是对话持续流，不再交给斜杠命令返回“任务已注册”回执。
            String goalObjective = extractGoalObjective(currentInput, mode);
            if (goalObjective != null) {
                startGoalStream(socket, sessionId, req, goalObjective, cwd, agent.name());
                return;
            }

            // 命令处理：以 / 开头的输入走命令分发
            if (currentInput.startsWith("/")) {
                handleCommand(socket, session, agent, chatModel, cwd, currentInput, sessionId, reasoningEffort);
                return;
            }

            // 流式处理
            final String finalSessionId = sessionId;

            // 处理附件：图片构建 ImageBlock，文件拼入文本前缀
            List<WsMessage.WsAttachment> attachments = req.getAttachments();
            List<ImageBlock> imageBlocks = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();

            if (attachments != null && !attachments.isEmpty()) {
                if (attachments.size() > DesktopAttachmentSupport.MAX_ATTACHMENTS) {
                    throw new IllegalArgumentException("附件数量不能超过 10 个");
                }
                int totalAttachmentBytes = 0;
                for (WsMessage.WsAttachment att : attachments) {
                    if (att == null || (!("image".equals(att.getType())) && !("file".equals(att.getType())))) {
                        throw new IllegalArgumentException("附件类型无效");
                    }
                    byte[] bytes = DesktopAttachmentSupport.decode(att);
                    totalAttachmentBytes += bytes.length;
                    if (totalAttachmentBytes > DesktopAttachmentSupport.MAX_TOTAL_ATTACHMENT_BYTES) {
                        throw new IllegalArgumentException("附件总大小不能超过 50 MB");
                    }
                    String savedName = DesktopAttachmentSupport.save(Paths.get(cwd), att.getName(), bytes);
                    if (DesktopAttachmentSupport.isMultimodalImage(att)) {
                        imageBlocks.add(ImageBlock.ofBase64(
                                Base64.getEncoder().encodeToString(bytes), att.getMimeType()));
                    } else {
                        fileNames.add(savedName);
                    }
                }
            }

            // 文件附件拼入输入文本前缀
            if (!fileNames.isEmpty()) {
                String filePrefix = fileNames.stream()
                        .map(f -> "[附件: " + f + "]")
                        .collect(java.util.stream.Collectors.joining("\n"));
                currentInput = filePrefix + "\n" + currentInput;
            }

            // 构建 Prompt（含图片时用 Contents）
            Prompt prompt;
            if (!imageBlocks.isEmpty()) {
                Contents contents = new Contents();
                contents.addBlock(TextBlock.of(currentInput));
                for (ImageBlock block : imageBlocks) {
                    contents.addBlock(block);
                }
                prompt = Prompt.of(new UserMessage(contents)).attrPut("start_time", System.currentTimeMillis());
            } else {
                prompt = Prompt.of(currentInput).attrPut("start_time", System.currentTimeMillis());
            }
            applyReasoningEffort(prompt, reasoningEffort);

            String finalCwd = cwd;
            AtomicBoolean terminalSent = new AtomicBoolean(false);
            streamHub.begin(finalSessionId, socket);
            Disposable disposable = agent.prompt(prompt)
                    .session(session)
                    .options(o -> {
                        o.chatModel(chatModel);
                        applyRunMode(o, session);
                        o.toolContextPut(HarnessEngine.ATTR_CWD, finalCwd);
                        applyReasoningEffort(o, reasoningEffort);
                    })
                    .stream()
                    .doFinally(signal -> {
                        session.attrs().remove("disposable");
                    })
                    .doOnNext(chunk -> {
                        // ReActChunk 需要优先处理 metrics 收集（无论 hasContent 状态）
                        String msg = null;
                        if (chunk instanceof ReActChunk) {
                            onReActChunk((ReActChunk) chunk, session, finalSessionId, terminalSent);
                            return;
                        } else if (chunk instanceof ReasonChunk) {
                            msg = onReasonChunk((ReasonChunk) chunk, finalSessionId);
                        } else if (chunk instanceof ActionChunk) {
                            msg = onActionStartChunk((ActionChunk) chunk, finalSessionId);
                        } else if (chunk instanceof ObservationChunk) {
                            msg = onObservationChunk((ObservationChunk) chunk, finalSessionId);
                        } else if (chunk instanceof ThoughtChunk) {
                            msg = onThoughtChunk((ThoughtChunk) chunk, finalSessionId);
                        }

                        if (Assert.isNotEmpty(msg)) {
                            streamHub.emit(finalSessionId, msg);
                        }
                    })
                    .doOnComplete(() -> sendDoneIfNeeded(terminalSent, finalSessionId,
                            chatModel.getConfig().getNameOrModel(), 0, 0))
                    .doOnError(err -> sendErrorIfNeeded(terminalSent, finalSessionId, err))
                    .subscribe();

            Disposable old = (Disposable) session.attrs().put("disposable", disposable);
            if (old != null && !old.isDisposed()) {
                old.dispose();
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            socket.send(new ONode().set("type", "error")
                    .set("text", errorMsg).toJson());
        }
    }

    private void onReActChunk(ReActChunk chunk, AgentSession session, String finalSessionId,
                              AtomicBoolean terminalSent) {
        ReActTrace trace = chunk.getTrace();
        Long start_time = trace.getOriginalPrompt().attrAs("start_time");
        long elapsed = start_time != null ? System.currentTimeMillis() - start_time : 0;
        long totalTokens = trace.getMetrics() != null ? trace.getMetrics().getTotalTokens() : 0;

        if (HITL.isHitl(session)) {
            HITLTask task = HITL.getPendingTask(session);
            if (task != null && terminalSent.compareAndSet(false, true)) {
                String command = "bash".equals(task.getToolName())
                        ? String.valueOf(task.getArgs().get("command"))
                        : null;
                streamHub.emit(finalSessionId, new ONode().set("type", "hitl")
                        .set("sessionId", finalSessionId)
                        .set("callId", task.getCallUuid())
                        .set("toolName", task.getToolName())
                        .set("command", command)
                        .set("comment", task.getComment())
                        .toJson());
            }
            return;
        }

        sendDoneIfNeeded(terminalSent, finalSessionId,
                trace.getOptions().getChatModel().getNameOrModel(), totalTokens, elapsed);
    }

    private void sendDoneIfNeeded(AtomicBoolean terminalSent, String sessionId,
                                  String modelName, long totalTokens, long elapsedMs) {
        if (!terminalSent.compareAndSet(false, true)) {
            return;
        }

        streamHub.emit(sessionId, new ONode().set("type", "done")
                .set("sessionId", sessionId)
                .set("modelName", modelName)
                .set("totalTokens", totalTokens)
                .set("elapsedMs", elapsedMs)
                .toJson());
    }

    private void sendErrorIfNeeded(AtomicBoolean terminalSent, String sessionId,
                                   Throwable error) {
        if (!terminalSent.compareAndSet(false, true)) {
            return;
        }

        String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        streamHub.emit(sessionId, new ONode().set("type", "error")
                .set("sessionId", sessionId)
                .set("text", errorMessage)
                .toJson());
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

    private String onReasonChunk(ReasonChunk chunk, String finalSessionId) {
        if (!chunk.isToolCalls() && chunk.getMessage() != null) {
            String content = chunk.getMessage().getContent();
            if (content != null && !content.isEmpty()) {
                boolean isThinking = chunk.getMessage().isThinking();
                String chunkTypeToSend = isThinking ? "think" : "text";

                ONode node = new ONode().set("type", chunkTypeToSend)
                        .set("sessionId", finalSessionId)
                        .set("text", content);

                String agentName = chunk.getTrace().getAgentName();
                if (!engine.getName().equals(agentName)) {
                    node.set("agentName", agentName);
                }

                return node.toJson();
            }
        }
        return null;
    }

    /**
     * 处理 ActionChunk（工具调用前发送）：在工具实际执行前推送 action_start，
     * 让前端提前渲染 loading 状态的工具卡片骨架，提升流式实时感。
     * 过滤规则与 onObservationChunk 保持一致，避免卡片创建后却无对应结果填充。
     */
    private String onActionStartChunk(ActionChunk chunk, String finalSessionId) {
        if (Assert.isEmpty(chunk.getToolName())) {
            return null;
        }

        if (TaskTalent.TOOL_MULTITASK.equals(chunk.getToolName()) ||
                TaskTalent.TOOL_TASK.equals(chunk.getToolName()) ||
                MemoryTalent.isMemoryTool(chunk.getToolName()) ||
                GoalTalent.isGoalTool(chunk.getToolName())) {
            return null;
        }

        // todowrite 的展示走专用通道，由 ObservationChunk 携带完整 todos 渲染，开始阶段不提前建卡
        if ("todowrite".equals(chunk.getToolName())) {
            return null;
        }

        ONode node = new ONode().set("type", "action_start")
                .set("sessionId", finalSessionId)
                .set("reasonId", chunk.getReasonId())
                .set("callId", chunk.getCallId());

        if (engine.getName().equals(chunk.getAgentName())) {
            node.set("toolName", chunk.getToolName());
        } else {
            node.set("toolName", chunk.getAgentName() + "/" + chunk.getToolName());
            node.set("agentName", chunk.getAgentName());
        }

        if (chunk.getArgs() != null) node.set("args", chunk.getArgs());

        return node.toJson();
    }

    private String onObservationChunk(ObservationChunk chunk, String finalSessionId) {
        if (chunk.getError() != null) {
            return null;
        }

        if (Assert.isEmpty(chunk.getToolName())) {
            return null;
        }

        if (TaskTalent.TOOL_MULTITASK.equals(chunk.getToolName()) ||
                TaskTalent.TOOL_TASK.equals(chunk.getToolName()) ||
                MemoryTalent.isMemoryTool(chunk.getToolName()) ||
                GoalTalent.isGoalTool(chunk.getToolName())) {
            return null;
        }

        ONode node = new ONode().set("type", "action_end")
                .set("sessionId", finalSessionId)
                .set("reasonId", chunk.getReasonId())
                .set("callId", chunk.getCallId());

        if (engine.getName().equals(chunk.getAgentName())) {
            node.set("toolName", chunk.getToolName());
        } else {
            node.set("toolName", chunk.getAgentName() + "/" + chunk.getToolName());
            node.set("agentName", chunk.getAgentName());
        }

        if (chunk.getObservation() != null && chunk.getObservation().getContent() != null) {
            node.set("text", chunk.getObservation().getContent());
        }
        if (chunk.getArgs() != null) node.set("args", chunk.getArgs());

        if ("todowrite".equals(chunk.getToolName())) {
            String todos = (String) chunk.getArgs().get("todos");
            if (Assert.isNotEmpty(todos)) {
                node.set("text", todos);
            }
        }

        return node.toJson();
    }

    /**
     * 处理 HITL 审批/拒绝操作
     * 消息格式: {"type":"hitl_action","action":"approve|reject","sessionId":"..."}
     */
    private void handleHitlAction(WebSocket socket, ONode root) {
        try {
            String sessionId = root.get("sessionId") != null ? root.get("sessionId").getString() : null;
            String action = root.get("action") != null ? root.get("action").getString() : null;
            String callId = root.get("callId") != null ? root.get("callId").getString() : null;
            String output = root.get("output") != null ? root.get("output").getString() : null;

            if (sessionId == null || action == null) {
                socket.send(new ONode().set("type", "error").set("text", "sessionId and action required").toJson());
                return;
            }

            AgentSession session = engine.getSession(sessionId);
            HITLTask task = Assert.isNotEmpty(callId)
                    ? HITL.getPendingTaskByCallUuid(session, callId)
                    : HITL.getPendingTask(session);
            if (task == null) {
                socket.send(new ONode().set("type", "error").set("text", "No pending HITL task").toJson());
                return;
            }

            if ("approve".equals(action)) {
                if (Assert.isNotEmpty(output)) {
                    HITL.approve(session, task, output);
                } else {
                    HITL.approve(session, task);
                }
            } else {
                if (Assert.isNotEmpty(output)) {
                    HITL.reject(session, task, output);
                } else {
                    HITL.reject(session, task);
                }
            }

            // 审批后恢复流执行
            String modelName = (String) session.getContext().get(HarnessEngine.CTX_MODEL_SELECTED);
            ChatModel chatModel = engine.getModelOrDefInstance(modelName);
            String selectedAgentName = (String) session.attrs().get(SESSION_ATTR_SELECTED_AGENT);
            ReActAgent selectedAgent = engine.getAgentOrMain(selectedAgentName);
            String cwd = session.attrs().getOrDefault(HarnessEngine.ATTR_CWD, ".").toString();
            String reasoningEffort = ReasoningSupportUtil.getSessionEffort(session);
            
            Prompt hitlPrompt = Prompt.of().attrPut("start_time", System.currentTimeMillis());
            applyReasoningEffort(hitlPrompt, reasoningEffort);
            
            AtomicBoolean terminalSent = new AtomicBoolean(false);
            streamHub.subscribe(sessionId, socket);
            Disposable disposable = selectedAgent.prompt(hitlPrompt)
                    .session(session)
                    .options(o -> {
                        o.chatModel(chatModel);
                        applyRunMode(o, session);
                        applyReasoningEffort(o, reasoningEffort);
                        if (Assert.isNotEmpty(cwd)) {
                            o.toolContextPut(HarnessEngine.ATTR_CWD, cwd);
                        }
                    })
                    .stream()
                    .doFinally(signal -> session.attrs().remove("disposable"))
                    .doOnNext(chunk -> {
                        if (chunk instanceof ReActChunk) {
                            onReActChunk((ReActChunk) chunk, session, sessionId, terminalSent);
                            return;
                        }
                        String msg = null;
                        if (chunk instanceof ReasonChunk) {
                            msg = onReasonChunk((ReasonChunk) chunk, sessionId);
                        } else if (chunk instanceof ActionChunk) {
                            msg = onActionStartChunk((ActionChunk) chunk, sessionId);
                        } else if (chunk instanceof ObservationChunk) {
                            msg = onObservationChunk((ObservationChunk) chunk, sessionId);
                        } else if (chunk instanceof ThoughtChunk) {
                            msg = onThoughtChunk((ThoughtChunk) chunk, sessionId);
                        }
                        if (Assert.isNotEmpty(msg)) {
                            streamHub.emit(sessionId, msg);
                        }
                    })
                    .doOnComplete(() -> sendDoneIfNeeded(terminalSent, sessionId,
                            chatModel.getConfig().getNameOrModel(), 0, 0))
                    .doOnError(err -> sendErrorIfNeeded(terminalSent, sessionId, err))
                    .subscribe();

            session.attrs().put("disposable", disposable);
        } catch (Exception e) {
            LOG.error("[WS] HITL action failed", e);
            socket.send(new ONode().set("type", "error").set("text", e.getMessage()).toJson());
        }
    }

    private String onThoughtChunk(ThoughtChunk chunk, String finalSessionId) {
        if (chunk.hasMeta(TaskTalent.TOOL_MULTITASK)) {
            String content = chunk.getAssistantMessage().getResultContent();
            if (Assert.isNotEmpty(content)) {
                ONode node = new ONode().set("type", "text")
                        .set("sessionId", finalSessionId)
                        .set("text", "\n" + content);

                String agentName = chunk.getTrace().getAgentName();
                if (!engine.getName().equals(agentName)) {
                    node.set("agentName", agentName);
                }

                return node.toJson();
            }
        }
        return null;
    }

    /**
     * 处理前端推送的配置变更
     */
    private void handleConfigMessage(WebSocket socket, ONode root) {
        try {
            ONode chatModelNode = root.get("chatModel");
            if (chatModelNode != null && !chatModelNode.isNull()) {
                String apiUrl = chatModelNode.get("apiUrl") != null ? chatModelNode.get("apiUrl").getString() : null;
                String apiKey = chatModelNode.get("apiKey") != null ? chatModelNode.get("apiKey").getString() : null;
                String model = chatModelNode.get("model") != null ? chatModelNode.get("model").getString() : null;
                String provider = chatModelNode.get("provider") != null ? chatModelNode.get("provider").getString() : null;
                    ChatConfigReadonly currentConfig = engine.getMainModel() == null ? null : engine.getMainModel().getConfig();
                String existApiUrl = currentConfig == null ? null : currentConfig.getApiUrl();
                String existApiKey = currentConfig == null ? null : currentConfig.getApiKey();
                String existModel = currentConfig == null ? null : currentConfig.getNameOrModel();
                String existProvider = currentConfig == null ? null : currentConfig.getStandardOrProvider();
                String finalApiUrlInput = apiUrl != null ? apiUrl : existApiUrl;
                String normalizedProvider = provider != null ? provider : existProvider;
                String normalizedApiUrl = finalApiUrlInput;
                String finalApiKey = apiKey != null ? apiKey : existApiKey;
                String finalModel = model != null ? model : existModel;

                if (apiUrl != null || apiKey != null || model != null || provider != null) {
                    // 更新 AgentProperties 的 chatModel 配置
                    ChatConfig chatConfig = new ChatConfig();
                    chatConfig.setApiUrl(normalizedApiUrl);
                    chatConfig.setApiKey(finalApiKey);
                    chatConfig.setModel(finalModel);
                    chatConfig.setStandard(normalizedProvider);

                    // 重建 ChatModel 并注入 kernel
                    engine.removeModel(chatConfig.getNameOrModel());
                    engine.addModel(chatConfig);
                    engine.refreshMainAgent();

                    LOG.info("[WS] Config updated: model={}, provider={}", finalModel, normalizedProvider);

                    // 持久化到 YAML 文件
                    saveConfigToFile(normalizedApiUrl, finalApiKey, finalModel, normalizedProvider);

                    socket.send(new ONode()
                            .set("type", "config")
                            .set("status", "ok")
                            .set("model", finalModel)
                            .toJson());
                }
            }
        } catch (Exception e) {
            LOG.error("[WS] Config update failed", e);
            socket.send(new ONode()
                    .set("type", "config")
                    .set("status", "error")
                    .set("text", e.getMessage())
                    .toJson());
        }
    }

    /**
     * 将 chatModel 配置持久化到 YAML 文件（~/.soloncode/chat-model.yml）
     */
    private void saveConfigToFile(String apiUrl, String apiKey, String model, String provider) {
        try {
            String home = System.getProperty("user.home");
            Path configDir = Paths.get(home, ".soloncode");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("chat-model.yml");

            // 读取已有配置，保留未更新的字段
            ChatConfigReadonly currentConfig = engine.getMainModel() == null ? null : engine.getMainModel().getConfig();
            String existApiUrl = currentConfig != null ? currentConfig.getApiUrl() : null;
            String existApiKey = currentConfig != null ? currentConfig.getApiKey() : null;
            String existModel = currentConfig != null ? currentConfig.getNameOrModel() : null;
            String existProvider = currentConfig != null ? currentConfig.getStandardOrProvider() : null;

            String finalApiUrl = apiUrl != null ? apiUrl : existApiUrl;
            String finalApiKey = apiKey != null ? apiKey : existApiKey;
            String finalModel = model != null ? model : existModel;
            String finalProvider = provider != null ? provider : existProvider;

            StringBuilder yaml = new StringBuilder();
            yaml.append("soloncode:\n");
            yaml.append("  chatModel:\n");
            if (finalApiUrl != null) yaml.append("    apiUrl: \"").append(escapeYaml(finalApiUrl)).append("\"\n");
            if (finalApiKey != null) yaml.append("    apiKey: \"").append(escapeYaml(finalApiKey)).append("\"\n");
            if (finalModel != null) yaml.append("    model: \"").append(escapeYaml(finalModel)).append("\"\n");
            if (Assert.isNotEmpty(finalProvider)) yaml.append("    provider: \"").append(escapeYaml(finalProvider)).append("\"\n");

            Files.write(configFile, yaml.toString().getBytes(StandardCharsets.UTF_8));
            LOG.info("[WS] Config persisted to: {}", configFile);
        } catch (Exception e) {
            LOG.error("[WS] Failed to persist config to YAML", e);
        }
    }

    private String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isValidAgentName(String name) {
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

    private int firstWhitespaceIndex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    private String removeLeadingAgentMention(String input, String agentName) {
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

    private void applyReasoningEffort(Prompt prompt, String reasoningEffort) {
        ReasoningSupportUtil.applyToPrompt(prompt, reasoningEffort);
    }

    private void applyReasoningEffort(ReActOptionsAmend options, String reasoningEffort) {
        ReasoningSupportUtil.applyToOptions(options, reasoningEffort);
    }

    /**
     * 处理命令输入（/ 开头），通过 CommandRegistry 分发执行
     */
    private void handleCommand(WebSocket socket, AgentSession session, ReActAgent agent, ChatModel chatModel,
                               String sessionCwd, String input, String finalSessionId, String reasoningEffort) {
        try {
            // 解析命令名和参数
            List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
            if (parts.isEmpty()) {
                return;
            }

            String cmdName = parts.get(0).toLowerCase();
            List<String> args = parts.size() > 1 ? parts.subList(1, parts.size()) : new ArrayList<>();

            // 查找命令
            Command command = engine.getCommandRegistry().find(cmdName);
            if (command == null) {
                // 不是有效命令，当作普通输入走流式处理
                handleFallbackPrompt(socket, session, agent, chatModel, sessionCwd, input, finalSessionId, reasoningEffort);
                return;
            }

            // 构建 context（注入 agentTaskRunner 回调）
            WebCommandContext ctx = new WebCommandContext(session, engine, input, cmdName, args,
                    (prompt, model) -> {
                        ChatModel selectedModel = model != null ? engine.getModelOrDefInstance(model) : chatModel;
                        handleFallbackPrompt(socket, session, agent, selectedModel, sessionCwd, prompt, finalSessionId, reasoningEffort);
                    });

            // 执行命令
            command.execute(ctx);

            if (!ctx.isAgentTask()) {
                // rewind 命令特殊处理：发送 rewind 事件让前端同步删除 DOM
                if ("rewind".equals(cmdName)) {
                    int rewindCount = 1;
                    if (!args.isEmpty()) {
                        try {
                            rewindCount = Integer.parseInt(args.get(0));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    socket.send(new ONode().set("type", "rewind")
                            .set("sessionId", finalSessionId)
                            .set("count", rewindCount + 1)
                            .toJson());
                } else {
                    String text = ctx.getOutputBuffer().length() > 0
                            ? ctx.getOutputBuffer().toString()
                            : "命令执行完成";
                    socket.send(new ONode().set("type", "command")
                            .set("sessionId", finalSessionId)
                            .set("text", text)
                            .toJson());
                }

                socket.send(new ONode().set("type", "done")
                        .set("sessionId", finalSessionId)
                        .set("modelName", chatModel.getConfig().getNameOrModel())
                        .set("totalTokens", 0)
                        .set("elapsedMs", 0).toJson());
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            socket.send(new ONode().set("type", "error")
                    .set("sessionId", finalSessionId)
                    .set("text", errorMsg).toJson());
        }
    }

    /**
     * 将输入作为普通 prompt 走流式处理
     */
    private void handleFallbackPrompt(WebSocket socket, AgentSession session, ReActAgent agent, ChatModel chatModel,
                                      String sessionCwd, String input, String finalSessionId, String reasoningEffort) {
        Prompt prompt = Prompt.of(input).attrPut("start_time", System.currentTimeMillis());
        applyReasoningEffort(prompt, reasoningEffort);
        AtomicBoolean terminalSent = new AtomicBoolean(false);
        streamHub.begin(finalSessionId, socket);
        Disposable disposable = agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);
                    applyRunMode(o, session);
                    applyReasoningEffort(o, reasoningEffort);
                    if (Assert.isNotEmpty(sessionCwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    }
                })
                .stream()
                .doFinally(signal -> session.attrs().remove("disposable"))
                .doOnNext(chunk -> {
                    if (chunk instanceof ReActChunk) {
                        onReActChunk((ReActChunk) chunk, session, finalSessionId, terminalSent);
                        return;
                    }
                    String msg = null;
                    if (chunk instanceof ReasonChunk) {
                        msg = onReasonChunk((ReasonChunk) chunk, finalSessionId);
                    } else if (chunk instanceof ActionChunk) {
                        msg = onActionStartChunk((ActionChunk) chunk, finalSessionId);
                    } else if (chunk instanceof ObservationChunk) {
                        msg = onObservationChunk((ObservationChunk) chunk, finalSessionId);
                    } else if (chunk instanceof ThoughtChunk) {
                        msg = onThoughtChunk((ThoughtChunk) chunk, finalSessionId);
                    }
                    if (Assert.isNotEmpty(msg)) {
                        streamHub.emit(finalSessionId, msg);
                    }
                })
                .doOnComplete(() -> sendDoneIfNeeded(terminalSent, finalSessionId,
                        chatModel.getConfig().getNameOrModel(), 0, 0))
                .doOnError(err -> sendErrorIfNeeded(terminalSent, finalSessionId, err))
                .subscribe();

        Disposable old = (Disposable) session.attrs().put("disposable", disposable);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
    }

    private void applyRunMode(ReActOptionsAmend options, AgentSession session) {
        Object configuredMode = session.attrs().get(SESSION_ATTR_RUN_MODE);
        String runMode = normalizeDesktopRunMode(configuredMode == null ? null : String.valueOf(configuredMode));
        boolean planning = "plan".equals(runMode);
        options.planningMode(planning);
        if (planning) {
            options.planningInstruction("只分析问题并输出可执行计划，不调用文件、命令或外部工具，不修改任何状态。");
            return;
        }

        // 请求级拦截只作用于桌面会话，不修改 HarnessEngine 的全局开关，Web 行为保持不变。
        if ("default".equals(runMode) || "auto".equals(runMode)) {
            // Interceptor 按类型去重；用一个桌面实例替换本次请求中的全局 HITL，防止重复挂起。
            options.interceptorAdd(desktopHitlInterceptor);
        }
    }

    private String desktopApprovalReason(ReActTrace trace, String toolName) {
        AgentSession session = trace == null ? null : trace.getSession();
        Object configuredMode = session == null ? null : session.attrs().get(SESSION_ATTR_RUN_MODE);
        String runMode = normalizeDesktopRunMode(configuredMode == null ? null : String.valueOf(configuredMode));
        if (!requiresDesktopApproval(runMode, toolName)) {
            return null;
        }
        if ("bash".equals(toolName)) {
            return "桌面执行模式要求批准此命令";
        }
        return "桌面审批执行模式要求批准此文件修改";
    }

    static String normalizeDesktopRunMode(String mode) {
        if ("auto".equals(mode) || "plan".equals(mode) || "goal".equals(mode) || "full".equals(mode)) {
            return mode;
        }
        // 未知或缺失模式按最严格的审批执行处理，避免客户端字段异常导致静默放行。
        return "default";
    }

    static boolean requiresDesktopApproval(String mode, String toolName) {
        String normalizedMode = normalizeDesktopRunMode(mode);
        // 完全访问模式：编辑和命令执行均无需审批
        if ("full".equals(normalizedMode)) {
            return false;
        }
        if ("bash".equals(toolName)) {
            return "default".equals(normalizedMode) || "auto".equals(normalizedMode);
        }
        return "default".equals(normalizedMode)
                && ("write".equals(toolName) || "edit".equals(toolName));
    }
}
