package org.noear.solon.codecli.api.web.pipeline;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.harness.extension.steer.SteerAppliedEvent;
import org.noear.solon.ai.harness.extension.steer.SteerDroppedEvent;
import org.noear.solon.ai.harness.extension.steer.SteerMessage;
import org.noear.solon.codecli.api.web.WebStreamBuilder;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.event.payload.SteerPayload;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * steer 事件映射验证：harness 推出的 {@link SteerAppliedEvent} / {@link SteerDroppedEvent}
 * 必须稳定映射为 {@code system.steer_applied} / {@code system.steer_dropped}，并穿过 Web 管线的三个过滤器。
 *
 * <p>这是「steer 输出改为 AgentEvent」后的唯一宿主侧契约：Web 只加类型分支，
 * 不实现任何端口接口。</p>
 */
class WebEventMapperSteerTest {

    private AgentSession session;
    private ReActTrace trace;

    private WebEventMapper newMapper() {
        WebStreamBuilder streamBuilder = mock(WebStreamBuilder.class);
        WorkspaceContext wsContext = mock(WorkspaceContext.class);

        session = mock(AgentSession.class);
        Map<String, Object> attrs = new HashMap<>();
        when(session.getSessionId()).thenReturn("s1");
        when(session.attrs()).thenReturn(attrs);

        trace = mock(ReActTrace.class);
        when(trace.getRunId()).thenReturn("run-1");
        when(trace.getAgentName()).thenReturn("main");
        when(trace.getSession()).thenReturn(session);

        return new WebEventMapper(streamBuilder, wsContext, session, null);
    }

    private static List<SteerMessage> steers(String... texts) {
        List<SteerMessage> items = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            items.add(new SteerMessage("steer-" + i, texts[i]));
        }
        return items;
    }

    @Test
    void steerAppliedMapsToSystemSteerApplied() {
        WebEventMapper mapper = newMapper();

        List<WebEvent<?>> events = mapper.mapEvent(new SteerAppliedEvent(trace, steers("改用方案B", "注意性能")));

        assertEquals(1, events.size());
        WebEvent<?> evt = events.get(0);
        assertEquals(WebEventNames.SYSTEM_STEER_APPLIED, evt.getEvent());
        assertEquals("run-1", evt.getRunId());
        assertEquals("s1", evt.getSessionId());

        SteerPayload payload = (SteerPayload) evt.getPayload();
        assertEquals("run-1", payload.getRunId());
        assertEquals(Arrays.asList("改用方案B", "注意性能"), payload.getTexts());
    }

    @Test
    void steerDroppedMapsQueuedOnly() {
        WebEventMapper mapper = newMapper();

        List<WebEvent<?>> events = mapper.mapEvent(
                new SteerDroppedEvent(trace, steers("未消费1", "未消费2"), Collections.<SteerMessage>emptyList()));

        assertEquals(1, events.size(), "只有 queued 时不应产生额外 error");
        WebEvent<?> evt = events.get(0);
        assertEquals(WebEventNames.SYSTEM_STEER_DROPPED, evt.getEvent());
        assertEquals(2, ((SteerPayload) evt.getPayload()).getTexts().size());
    }

    @Test
    void steerDroppedFailedEmitsError() {
        WebEventMapper mapper = newMapper();

        List<WebEvent<?>> events = mapper.mapEvent(
                new SteerDroppedEvent(trace, steers("已排队"), steers("尚未排队")));

        assertEquals(2, events.size(), "queued 与 failed 必须分别呈现");
        assertEquals(WebEventNames.SYSTEM_STEER_DROPPED, events.get(0).getEvent());
        assertEquals(WebEventNames.SYSTEM_ERROR, events.get(1).getEvent());
    }

    @Test
    void steerDroppedWithNothingIsNotBroadcast() {
        WebEventMapper mapper = newMapper();

        List<WebEvent<?>> events = mapper.mapEvent(
                new SteerDroppedEvent(trace, Collections.<SteerMessage>emptyList(), Collections.<SteerMessage>emptyList()));

        assertTrue(events.isEmpty(), "空 dropped 不得产生噪音事件");
    }

    /**
     * 新事件必须能穿过 WebStreamBuilder 管线的三个过滤器，否则会被静默丢弃。
     * 三个过滤器都是纯函数，此处直接按管线顺序调用。
     */
    @Test
    void steerEventsPassPipelineFilters() {
        WebEventMapper mapper = newMapper();
        UiRenderFilter uiRenderFilter = new UiRenderFilter();

        List<WebEvent<?>> mapped = mapper.mapEvent(new SteerAppliedEvent(trace, steers("生效")));
        mapped.addAll(mapper.mapEvent(
                new SteerDroppedEvent(trace, steers("排队"), steers("失败"))));

        assertEquals(3, mapped.size());
        for (WebEvent<?> evt : mapped) {
            assertTrue(WebEvent.isNotEmpty(evt), "isNotEmpty 不得过滤 steer 事件");
            assertNotNull(uiRenderFilter.apply(evt), "UiRenderFilter 不得吞掉 steer 事件");
        }
    }
}
