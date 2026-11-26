package org.noear.solon.codecli.api.web.pipeline;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunStartEvent;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.codecli.api.web.WebStreamBuilder;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
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
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.index = 1;
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

    @Test
    void missingParentCallIdStaysNull() {
        List<WebEvent<?>> events = newMapper().mapEvent(wrap(null));

        assertEquals(1, events.size());
        assertNull(events.get(0).getParentCallId(), "取不到时不下发，前端退回按 taskId 分组");
    }
}
