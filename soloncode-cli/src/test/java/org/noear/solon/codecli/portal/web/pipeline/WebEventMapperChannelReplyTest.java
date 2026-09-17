package org.noear.solon.codecli.portal.web.pipeline;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.codecli.portal.web.WebStreamBuilder;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class WebEventMapperChannelReplyTest {
    @Test
    void finalReplyIsDispatchedOnceFromRunEnd() {
        WebStreamBuilder streamBuilder = mock(WebStreamBuilder.class);
        WorkspaceContext wsContext = mock(WorkspaceContext.class);
        AgentSession session = mock(AgentSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("_agent_selected_tmp", "main");
        when(session.getSessionId()).thenReturn("s1");
        when(session.attrs()).thenReturn(attrs);

        ReActTrace reasonTrace = mock(ReActTrace.class);
        when(reasonTrace.getAgentName()).thenReturn("main");
        ReasonEndEvent reasonEnd = mock(ReasonEndEvent.class);
        when(reasonEnd.getTrace()).thenReturn(reasonTrace);
        when(reasonEnd.getText()).thenReturn("最终答复");
        when(reasonEnd.isToolCalls()).thenReturn(false);

        ReActTrace runTrace = mock(ReActTrace.class);
        when(runTrace.getFinalAnswer()).thenReturn("最终答复");
        RunEndEvent runEnd = mock(RunEndEvent.class);
        when(runEnd.getTrace()).thenReturn(runTrace);
        when(runEnd.getText()).thenReturn("最终答复");

        WebEventMapper mapper = new WebEventMapper(streamBuilder, wsContext, session, null);

        List<WebEvent<?>> reasonEvents = mapper.mapEvent(reasonEnd);
        assertFalse(WebEvent.isNotEmpty(reasonEvents.get(0)));
        verifyNoInteractions(streamBuilder);

        List<WebEvent<?>> runEvents = mapper.mapEvent(runEnd);
        assertEquals(WebEventNames.SYSTEM_TRACE, runEvents.get(0).getEvent());
        verify(streamBuilder, times(1))
                .replyToBoundChannel(wsContext, "s1", "最终答复", true);
    }

    @Test
    void toolAndSubAgentReasonEndsDoNotSendProcessRepliesToIm() {
        WebStreamBuilder streamBuilder = mock(WebStreamBuilder.class);
        WorkspaceContext wsContext = mock(WorkspaceContext.class);
        AgentSession session = mock(AgentSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("_agent_selected_tmp", "main");
        when(session.getSessionId()).thenReturn("s1");
        when(session.attrs()).thenReturn(attrs);

        ReasonEndEvent toolReasonEnd = mock(ReasonEndEvent.class);
        when(toolReasonEnd.getText()).thenReturn("工具调用前的过程答复");
        when(toolReasonEnd.isToolCalls()).thenReturn(true);

        ReActTrace subAgentTrace = mock(ReActTrace.class);
        when(subAgentTrace.getAgentName()).thenReturn("sub-agent");
        ReasonEndEvent subAgentReasonEnd = mock(ReasonEndEvent.class);
        when(subAgentReasonEnd.getTrace()).thenReturn(subAgentTrace);
        when(subAgentReasonEnd.getText()).thenReturn("子代理过程答复");

        ReActTrace runTrace = mock(ReActTrace.class);
        when(runTrace.getFinalAnswer()).thenReturn("整轮最终答复");
        RunEndEvent runEnd = mock(RunEndEvent.class);
        when(runEnd.getTrace()).thenReturn(runTrace);

        WebEventMapper mapper = new WebEventMapper(streamBuilder, wsContext, session, null);

        assertFalse(WebEvent.isNotEmpty(mapper.mapEvent(toolReasonEnd).get(0)));
        assertFalse(WebEvent.isNotEmpty(mapper.mapEvent(subAgentReasonEnd).get(0)));
        verifyNoInteractions(streamBuilder);

        mapper.mapEvent(runEnd);
        verify(streamBuilder, times(1))
                .replyToBoundChannel(wsContext, "s1", "整轮最终答复", true);
    }

    @Test
    void abnormalRunDoesNotCreateASecondFinalReplyPath() {
        WebStreamBuilder streamBuilder = mock(WebStreamBuilder.class);
        WorkspaceContext wsContext = mock(WorkspaceContext.class);
        AgentSession session = mock(AgentSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("_agent_selected_tmp", "main");
        when(session.getSessionId()).thenReturn("s1");
        when(session.attrs()).thenReturn(attrs);

        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getAgentName()).thenReturn("main");
        when(trace.getFinalAnswer()).thenReturn("相同答复");

        ReasonEndEvent reasonEnd = mock(ReasonEndEvent.class);
        when(reasonEnd.getTrace()).thenReturn(trace);
        when(reasonEnd.getText()).thenReturn("相同答复");

        RunEndEvent runEnd = mock(RunEndEvent.class);
        when(runEnd.getTrace()).thenReturn(trace);
        when(runEnd.getText()).thenReturn("相同答复");
        when(runEnd.isAbnormal()).thenReturn(true);

        WebEventMapper mapper = new WebEventMapper(streamBuilder, wsContext, session, null);
        mapper.mapEvent(reasonEnd);
        mapper.mapEvent(runEnd);

        verify(streamBuilder, times(1))
                .replyToBoundChannel(wsContext, "s1", "相同答复", true);
    }
}
