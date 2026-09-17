package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.RunStartEvent;
import org.noear.solon.ai.agent.react.intercept.ContextSizeEvent;
import org.noear.solon.ai.agent.react.intercept.HITLPendingEvent;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.command.builtin.GoalTalent;
import org.noear.solon.codecli.portal.web.WebStreamBuilder;
import org.noear.solon.codecli.portal.web.event.UiPatchEvent;
import org.noear.solon.codecli.portal.web.event.UiRenderEvent;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.SystemContextPayload;
import org.noear.solon.codecli.portal.web.event.payload.TaskDonePayload;
import org.noear.solon.codecli.portal.web.event.payload.TaskStartPayload;
import org.noear.solon.codecli.portal.web.event.payload.ToolEndPayload;
import org.noear.solon.codecli.portal.web.event.payload.ToolStartPayload;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 ReAct Agent 底层事件映射为标准 SAEP 2.0 WebEvent 的纯函数映射器
 *
 * @author noear
 */
public class WebEventMapper {
    private final WebStreamBuilder streamBuilder;
    private final WorkspaceContext wsContext;
    private final AgentSession session;
    private final ChatModel chatModel;

    public WebEventMapper(WebStreamBuilder streamBuilder, WorkspaceContext wsContext, AgentSession session, ChatModel chatModel) {
        this.streamBuilder = streamBuilder;
        this.wsContext = wsContext;
        this.session = session;
        this.chatModel = chatModel;
    }

    public List<WebEvent<?>> mapEvent(AgentEvent event) {
        String parentRunId = null;
        String taskAgentName = null;
        String taskId = null;
        String taskDescription = null;
        boolean isMultitask = false;
        int taskIndex = 0;

        if (event instanceof TaskWrapEvent) {
            TaskWrapEvent twc = (TaskWrapEvent) event;
            if (twc.getRealEvent() instanceof ContextSizeEvent ||
                    twc.getRealEvent() instanceof ToolCallStartEvent ||
                    twc.getRealEvent() instanceof ToolCallEndEvent ||
                    twc.getRealEvent() instanceof ReasonDeltaEvent ||
                    twc.getRealEvent() instanceof ReasonEndEvent ||
                    twc.getRealEvent() instanceof RunStartEvent ||
                    twc.getRealEvent() instanceof RunEndEvent) {
                // RunStartEvent → task.start：子代理 ReAct 一启动就发（早于首个 thought.delta）

                parentRunId = twc.getParentRunId();
                taskId = twc.getTaskId();
                taskAgentName = twc.getTaskAgentName();
                taskDescription = twc.getTaskDescription();
                isMultitask = twc.isMultitask();
                taskIndex = twc.getTaskIndex();
                event = twc.getRealEvent();
            }
        }

        // 推理轮次标识：仅 Reason/Action 类事件携带，统一提取后注入信封层（与 taskId 平级），
        // 前端据此将同一轮的思考/正文/工具事件归入同一 reason 组。
        String reasonId = extractReasonId(event);

        List<WebEvent<?>> result = new ArrayList<>();

        if (event instanceof UiRenderEvent) {
            UiRenderEvent uiEvent = (UiRenderEvent) event;
            WebEvent<?> evt = WebEvent.ofUiRender(uiEvent.getPayload());
            fillMeta(evt, session, parentRunId, taskId, uiEvent.getReasonId(), taskAgentName, taskDescription, event.getRunId());
            result.add(evt);
        } else if (event instanceof UiPatchEvent) {
            UiPatchEvent uiPatch = (UiPatchEvent) event;
            WebEvent<?> evt = WebEvent.ofUiPatch(uiPatch.getPayload());
            fillMeta(evt, session, parentRunId, taskId, uiPatch.getReasonId(), taskAgentName, taskDescription, event.getRunId());
            result.add(evt);
        } else if (event instanceof ContextSizeEvent) {
            WebEvent<?> evt = onContextSizeEvent(chatModel, (ContextSizeEvent) event);
            fillMeta(evt, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
            result.add(evt);
        } else if (event instanceof ReasonDeltaEvent) {
            WebEvent<?> evt = onReasonDeltaEvent((ReasonDeltaEvent) event, taskAgentName);
            fillMeta(evt, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
            result.add(evt);
        } else if (event instanceof ReasonEndEvent) {
            WebEvent<?> evt = onReasonEndEvent(session, (ReasonEndEvent) event, taskAgentName, isMultitask);
            fillMeta(evt, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
            result.add(evt);
        } else if (event instanceof HITLPendingEvent) {
            List<WebEvent<?>> hitlEvents = onHITLPendingEvent(session, (HITLPendingEvent) event);
            for (WebEvent<?> evt : hitlEvents) {
                fillMeta(evt, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
                result.add(evt);
            }
        } else if (event instanceof ToolCallStartEvent) {
            WebEvent<?> evt = onToolCallStartEvent((ToolCallStartEvent) event, taskAgentName);
            fillMeta(evt, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
            result.add(evt);
        } else if (event instanceof ToolCallEndEvent) {
            WebEvent<?> evt = onToolCallEndEvent((ToolCallEndEvent) event, taskAgentName);
            fillMeta(evt, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
            result.add(evt);
        } else if (event instanceof RunStartEvent) {
            if (taskId != null) {
                // 子代理 ReAct 启动：发 task.start 让前端先建 task-group 占位。
                // 主代理的 RunStartEvent 不外发（整轮开始由 HTTP 响应自身表达，无需事件）。
                WebEvent<?> taskStart = onTaskStartEvent(parentRunId, taskId, taskIndex, taskAgentName, taskDescription, isMultitask);
                fillMeta(taskStart, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
                result.add(taskStart);
            }
        } else if (event instanceof RunEndEvent) {
            if (taskId != null) {
                // 子代理 ReAct 结束：仅发 task.done 让前端结算对应 task-group，
                // 绝不输出 system.trace —— trace 是整轮主代理的收尾统计，子代理输出会导致
                // 前端在 task-group 外多渲染一条 trace 徽标（“子代理任务完成后也输出 trace”）。
                WebEvent<?> taskDone = onTaskDoneEvent((RunEndEvent) event, parentRunId, taskId, taskAgentName, taskDescription, isMultitask);
                fillMeta(taskDone, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
                result.add(taskDone);
            } else {
                // 主代理整轮结束：输出 system.trace（模型/token/耗时/最终答案）
                WebEvent<?> trace = onRunEndEvent(session, (RunEndEvent) event);
                fillMeta(trace, session, parentRunId, taskId, reasonId, taskAgentName, taskDescription, event.getRunId());
                result.add(trace);
            }
        }

        return result;
    }

    /**
     * 统一提取事件的 reasonId（仅 Reason 与 Action 类事件携带）。
     */
    private String extractReasonId(AgentEvent event) {
        if (event instanceof ReasonDeltaEvent) {
            return ((ReasonDeltaEvent) event).getReasonId();
        } else if (event instanceof ReasonEndEvent) {
            return ((ReasonEndEvent) event).getReasonId();
        } else if (event instanceof ToolCallStartEvent) {
            return ((ToolCallStartEvent) event).getReasonId();
        } else if (event instanceof ToolCallEndEvent) {
            return ((ToolCallEndEvent) event).getReasonId();
        }
        return null;
    }

    private void fillMeta(WebEvent<?> evt, AgentSession session, String parentRunId, String taskId,
                          String reasonId, String agentName, String taskDescription, String fallbackRunId) {
        if (evt == null) {
            return;
        }

        if (session != null && evt.getSessionId() == null) {
            evt.setSessionId(session.getSessionId());
        }

        if (parentRunId != null) {
            evt.setRunId(parentRunId);
        } else if (fallbackRunId != null && evt.getRunId() == null) {
            evt.setRunId(fallbackRunId);
        }

        if (taskId != null && evt.getTaskId() == null) {
            evt.setTaskId(taskId);
        }

        if (reasonId != null && evt.getReasonId() == null) {
            evt.setReasonId(reasonId);
        }

        if (agentName != null && evt.getAgentName() == null) {
            evt.setAgentName(agentName);
        }

        // 任务描述随每个子代理事件下发：前端第一个 thought.delta 就能拿到标题；
        // 若只靠 task.done，流式全程只能回退显示代理名。
        if (taskDescription != null && evt.getTaskDescription() == null) {
            evt.setTaskDescription(taskDescription);
        }
    }

    private WebEvent<?> onContextSizeEvent(ChatModel chatModel, ContextSizeEvent event) {
        long limit = 0;
        if (chatModel != null && chatModel.getConfig() != null) {
            limit = chatModel.getConfig().getContextLength();
        }
        if (limit <= 0) {
            limit = event.getContextLength(); // 默认上下文窗口，避免前端展示 "/ 0 (0%)"
        }
        if (limit <= 0) {
            limit = 128_000L;
        }

        Double cacheRate = null;
        if (event.getTrace() != null && event.getTrace().getMetrics() != null) {
            double cr = event.getTrace().getMetrics().getCacheRate();
            if (cr > 0) {
                cacheRate = cr;
            }
        }

        return WebEvent.of(WebEventNames.SYSTEM_CONTEXT, SystemContextPayload.builder()
                .tokens(event.getTokenCount())
                .count(event.getMessageCount())
                .contextLimit(limit)
                .cacheRate(cacheRate)
                .build());
    }

    private WebEvent<?> onReasonDeltaEvent(ReasonDeltaEvent event, String taskAgentName) {
        if (event.getChatEvent() != null) {
            if (event.getChatEvent().is(ChatEventType.THINKING_END)) {
                //思考结束：外发 thought.done，前端据此立即结束该 reasonId 的思考块（停转转）。
                //思考之后同轮可能再无正文/工具事件（如 steer 截断、仅思考的轮次），
                //此前只能等 system.done 兑底收尾，spinner 会空转到整轮结束。
                return WebEvent.ofThoughtDone(event.getReasonId(), event.getText());
            } else if (event.getChatEvent().is(ChatEventType.TEXT_END)) {
                //文本结束
                //WebEventNames.MESSAGE_DONE;
            }
        }

        if (event.isThinking()) {
            return WebEvent.ofReason(event.getReasonId(), event.getText());
        } else {
            // 正文必须携带 reasonId，前端据此将同一轮正文分组；
            // 丢失后多轮正文会塔缩到同一 __default__ 分组，导致最终消息错接到前一组。
            return WebEvent.ofText(event.getReasonId(), event.getText());
        }
    }

    private List<WebEvent<?>> onHITLPendingEvent(AgentSession session, HITLPendingEvent event) {
        List<WebEvent<?>> result = new ArrayList<>();
        if (event == null || event.getPendingTasks() == null || event.getPendingTasks().isEmpty()) {
            return result;
        }
        for (HITLTask task : event.getPendingTasks()) {
            if (task == null) continue;
            result.add(buildHitlEvent(session, task));
        }
        return result;
    }

    private WebEvent<?> buildHitlEvent(AgentSession session, HITLTask task) {
        String toolName = task.getToolName();
        Map<String, Object> args = task.getArgs();
        String command = null;
        if (args != null) {
            command = (String) args.get("command");
        }
        return WebEvent.ofHitl(toolName, toolName, args, command, task.getCallUuid(), task.getComment());
    }

    private WebEvent<?> onToolCallStartEvent(ToolCallStartEvent event, String taskAgentName) {
        String toolName = event.getToolName();
        if (ToolViewUtil.isInternalTool(toolName)) {
            return WebEvent.EMPTY;
        }

        String toolTitle = ToolViewUtil.buildToolTitle(toolName, taskAgentName, event.getAgentName());

        Map<String, Object> args = event.getArgs();
        WebEvent<ToolStartPayload> evt = WebEvent.ofToolCallStart(toolName, toolTitle, args);
        evt.getPayload().setCallId(event.getCallId());
        return evt;
    }

    private WebEvent<?> onToolCallEndEvent(ToolCallEndEvent event, String taskAgentName) {
        String toolName = event.getToolName();
        if (ToolViewUtil.isInternalTool(toolName)) {
            return WebEvent.EMPTY;
        }

        String toolTitle = ToolViewUtil.buildToolTitle(toolName, taskAgentName, event.getAgentName());

        return WebEvent.of(WebEventNames.TOOL_END, ToolEndPayload.builder()
                .callId(event.getCallId())
                .name(toolName)
                .title(toolTitle)
                .result(event.getText())
                .isError(false)
                .args(event.getArgs())
                .build());
    }

    /**
     * 专门向 im 推行执行过程（其它地方不用发了）
     */
    private WebEvent<?> onReasonEndEvent(AgentSession session, ReasonEndEvent event, String taskAgentName, boolean isMultitask) {
        //如果流已取消，则不输出

        String sessionId = session.getSessionId();
        String resultContent = event.getText();

        if (Assert.isNotEmpty(resultContent)) {
            // 过程消息仍可同步到 IM；主代理最终答复统一由 RunEndEvent 分发。
            // ReasonEndEvent 与 RunEndEvent 都携带最终正文，若两处都发送，异常收尾时
            // 同一答案会在微信出现两次，而 Web 只能等 RunEndEvent 的 system.trace 才收尾。
            if (event.isToolCalls()) {
                streamBuilder.replyToBoundChannel(wsContext, sessionId, resultContent, false);
            } else {
                String agentSelectedTmp = (String) session.attrs().get("_agent_selected_tmp");
                String eventAgentName = event.getTrace() == null ? null : event.getTrace().getAgentName();

                if (eventAgentName == null || !eventAgentName.equals(agentSelectedTmp)) {
                    // 次代理结果属于过程消息；源代理结果留给 RunEndEvent 统一广播。
                    streamBuilder.replyToBoundChannel(wsContext, sessionId, resultContent, false);
                }
            }
        }

//        ReActTrace trace = event.getTrace();
//        Metrics metrics = trace.getMetrics();
//        if (metrics != null) {
//            double cacheRate = metrics.getCacheRate();
//            if (cacheRate > 0) {
//                return WebEvent.of(WebEventNames.SYSTEM_CONTEXT, SystemContextPayload.builder()
//                        .tokens(null)
//                        .count(null)
//                        .contextLimit(null)
//                        .cacheRate(cacheRate)
//                        .build());
//            }
//        }

        return WebEvent.EMPTY;
    }

    private WebEvent<?> onTaskStartEvent(String runId, String taskId, int taskIndex,
                                         String taskAgentName, String taskDescription, boolean isMultitask) {
        return WebEvent.of(WebEventNames.TASK_START, TaskStartPayload.builder()
                .taskId(taskId)
                .parentTaskId(runId)
                .title(taskDescription)
                .agentName(taskAgentName)
                .taskIndex(taskIndex)
                .isMultitask(isMultitask)
                .build());
    }

    private WebEvent<?> onTaskDoneEvent(RunEndEvent event, String runId, String taskId,
                                        String taskAgentName, String taskDescription, boolean isMultitask) {
        boolean abnormal = (event != null && event.isAbnormal());
        return WebEvent.of(WebEventNames.TASK_DONE, TaskDonePayload.builder()
                .taskId(taskId)
                .parentTaskId(runId)
                .title(taskDescription)
                .status(abnormal ? "error" : "done")
                .isMultitask(isMultitask)
                .build());
    }

    private WebEvent<?> onRunEndEvent(AgentSession session, RunEndEvent event) {
        if (event == null || event.getTrace() == null) {
            return WebEvent.EMPTY;
        }

        ReActTrace trace = event.getTrace();
        String model = (chatModel != null && chatModel.getConfig() != null) ? chatModel.getConfig().getModel() : null;
        Long totalTokens = (trace.getMetrics() != null) ? trace.getMetrics().getTotalTokens() : 0L;
        long elapsedSeconds = 0L;
        if (trace.getBeginTimeMs() > 0) {
            elapsedSeconds = (System.currentTimeMillis() - trace.getBeginTimeMs()) / 1000;
        }

        String finalAnswer = trace.getFinalAnswer();
        if (Assert.isEmpty(finalAnswer)) {
            finalAnswer = event.getText();
        }

        return WebEvent.ofTrace(model, totalTokens, elapsedSeconds, finalAnswer);
    }
}