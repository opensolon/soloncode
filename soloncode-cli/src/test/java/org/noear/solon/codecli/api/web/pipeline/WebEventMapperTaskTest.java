package org.noear.solon.codecli.api.web.pipeline;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.LlmErrorEvent;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.RunStartEvent;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.codecli.api.web.WebStreamBuilder;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.event.payload.TaskDonePayload;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子代理事件信封：parentCallId（发起本次委派的父工具调用）与 taskId 平级下发，
 * 前端据此给同一 taskId 的每次调用（续跑）各建一个 task 组。
 */
class WebEventMapperTaskTest {

    private WebEventMapper newMapper() {
        AgentSession session = mock(AgentSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(session.getSessionId()).thenReturn("s1");
        when(session.attrs()).thenReturn(attrs);
        return new WebEventMapper(mock(WebStreamBuilder.class), mock(WorkspaceContext.class), session, null);
    }

    private static TaskWrapEvent wrap(String parentCallId) {
        ReActTrace parent = mock(ReActTrace.class);
        when(parent.getRunId()).thenReturn("run-1");
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "查代码";
        return new TaskWrapEvent(parent, parentCallId, "task-1", op, false, mock(RunStartEvent.class));
    }

    @Test
    void taskStartCarriesParentCallId() {
        List<WebEvent<?>> events = newMapper().mapEvent(wrap("call-B"));

        assertEquals(1, events.size());
        WebEvent<?> evt = events.get(0);
        assertEquals(WebEventNames.TASK_START, evt.getEvent());
        assertEquals("task-1", evt.getTaskId());
        assertEquals("call-B", evt.getParentCallId());
        assertEquals("run-1", evt.getRunId(), "子代理事件归父轮次");
    }

    /** 子代理结束事件的 task.done 状态：被停止为 interrupted（实时即显示被中断，不是绿勾），模型服务失败为 error，其余 done */
    @Test
    void taskDoneStatusFollowsFailureKind() {
        assertEquals("interrupted", doneStatusOf("_stopKind", "cancelled"));
        assertEquals("interrupted", doneStatusOf("_stopKind", "interrupted"));
        assertEquals("error", doneStatusOf(LlmErrorEvent.TRACE_ERROR_TYPE_KEY, LlmErrorEvent.Type.RATE_LIMIT));
        assertEquals("done", doneStatusOf(null, null));
    }

    /** @param extraKey 子 trace 上的标记（_stopKind 即 TaskTalent 被停止时记的停止类型） */
    private String doneStatusOf(String extraKey, Object value) {
        ReActTrace sub = new ReActTrace();
        if (extraKey != null) {
            sub.setExtra(extraKey, value);
        }
        RunEndEvent end = mock(RunEndEvent.class);
        when(end.getTrace()).thenReturn(sub);
        when(end.getLlmErrorType()).thenAnswer(i -> sub.getExtraAs(LlmErrorEvent.TRACE_ERROR_TYPE_KEY));

        ReActTrace parent = mock(ReActTrace.class);
        when(parent.getRunId()).thenReturn("run-1");
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "查代码";
        List<WebEvent<?>> events = newMapper().mapEvent(new TaskWrapEvent(parent, "call-A", "task-1", op, true, end));

        assertEquals(1, events.size());
        assertEquals(WebEventNames.TASK_DONE, events.get(0).getEvent());
        return ((TaskDonePayload) events.get(0).getPayload()).getStatus();
    }

    @Test
    void missingParentCallIdStaysNull() {
        List<WebEvent<?>> events = newMapper().mapEvent(wrap(null));

        assertEquals(1, events.size());
        assertNull(events.get(0).getParentCallId(), "取不到时不下发，前端退回按 taskId 分组");
    }
}
