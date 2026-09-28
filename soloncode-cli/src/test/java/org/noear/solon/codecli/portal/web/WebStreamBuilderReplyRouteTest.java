package org.noear.solon.codecli.portal.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class WebStreamBuilderReplyRouteTest {
    @Test
    void webTurnNeverBroadcastsToBoundIm() {
        WorkspaceContext context = mock(WorkspaceContext.class);
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = mock(AgentSession.class);
        ChannelHub hub = mock(ChannelHub.class);
        Channel im = mock(Channel.class);
        when(context.getEngine()).thenReturn(engine);
        when(engine.getSession("s1")).thenReturn(session);
        when(session.attrs()).thenReturn(new HashMap<>());
        when(context.getChannelHub()).thenReturn(hub);
        when(hub.getImLinks()).thenReturn(Arrays.asList(im));
        when(im.isBound("s1")).thenReturn(true);
        new WebStreamBuilder().replyToBoundChannel(context, "s1", "web answer", true);
        verifyNoInteractions(im);
    }

    @Test
    void replyUsesOriginalImRouteAndClearsItAfterFinal() {
        WorkspaceContext context = mock(WorkspaceContext.class);
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = mock(AgentSession.class);
        ChannelHub hub = mock(ChannelHub.class);
        Channel wechat = mock(Channel.class);
        Channel feishu = mock(Channel.class);
        Map<String, Object> attrs = new HashMap<>();
        Map<String, String> route = new HashMap<>();
        route.put("source", "WeChat");
        route.put("sourceUserId", "user-a");
        route.put("replyTarget", "token-a");
        attrs.put("session.replyRoute", route);
        when(context.getEngine()).thenReturn(engine);
        when(engine.getSession("s1")).thenReturn(session);
        when(session.attrs()).thenReturn(attrs);
        when(context.getChannelHub()).thenReturn(hub);
        when(hub.getImLinks()).thenReturn(Arrays.asList(wechat, feishu));
        when(wechat.isBound("s1")).thenReturn(true);
        when(wechat.getChannelName()).thenReturn("wechat");
        when(feishu.isBound("s1")).thenReturn(true);
        when(feishu.getChannelName()).thenReturn("feishu");
        new WebStreamBuilder().replyToBoundChannel(context, "s1", "answer", true);
        verify(wechat).sendReply("s1", "answer", true, "user-a", "token-a", null);
        verify(feishu, never()).sendReply(eq("s1"), anyString(), anyBoolean(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertFalse(attrs.containsKey("session.replyRoute"));
    }
}
