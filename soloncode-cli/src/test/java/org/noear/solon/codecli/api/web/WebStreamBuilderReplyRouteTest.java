package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class WebStreamBuilderReplyRouteTest {
    @Test
    void webTurnBroadcastsFinalToBoundIm() {
        // 多终端（web/im）同步：web 发起的轮次，终态也要广播给绑定的 IM（退回绑定用户）
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
        verify(im).sendReply("s1", "web answer", true);
    }

    @Test
    void webTurnDoesNotPushPartialToBoundIm() {
        // 过程消息只回来源端：web 发起时 route 为空，不得下发任何 IM
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
        new WebStreamBuilder().replyPartialToOriginChannel(context, "s1", "过程叙述");
        verifyNoInteractions(im);
    }

    @Test
    void terminalMessageIsDeliveredOnlyOncePerTurn() {
        // 终态去重门：final / error / canceled 共用，IM 每轮只收一条终态
        WorkspaceContext context = mock(WorkspaceContext.class);
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = mock(AgentSession.class);
        ChannelHub hub = mock(ChannelHub.class);
        Channel im = mock(Channel.class);
        Map<String, Object> attrs = new HashMap<>();
        when(context.getEngine()).thenReturn(engine);
        when(engine.getSession("s1")).thenReturn(session);
        when(session.attrs()).thenReturn(attrs);
        when(context.getChannelHub()).thenReturn(hub);
        when(hub.getImLinks()).thenReturn(Arrays.asList(im));
        when(im.isBound("s1")).thenReturn(true);

        WebStreamBuilder builder = new WebStreamBuilder();
        builder.replyToBoundChannel(context, "s1", "最终答复", true);
        builder.replyToBoundChannel(context, "s1", "任务执行失败：boom", true);

        verify(im, times(1)).sendReply("s1", "最终答复", true);
        verify(im, never()).sendReply("s1", "任务执行失败：boom", true);
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
        // 非来源端走无定向参数广播（多终端同步），退回到绑定用户
        verify(feishu).sendReply("s1", "answer", true);
        org.junit.jupiter.api.Assertions.assertFalse(attrs.containsKey("session.replyRoute"));
    }

    @Test
    void statusSignalGoesOnlyToOriginChannel() {
        // 交互状态信号（排队/长任务/拒收）只投来源端，不跨端广播
        WorkspaceContext context = mock(WorkspaceContext.class);
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = mock(AgentSession.class);
        ChannelHub hub = mock(ChannelHub.class);
        Channel wechat = mock(Channel.class);
        Channel feishu = mock(Channel.class);
        when(context.getEngine()).thenReturn(engine);
        when(engine.getSession("s1")).thenReturn(session);
        when(session.attrs()).thenReturn(new HashMap<>());
        when(context.getChannelHub()).thenReturn(hub);
        when(hub.getImLinks()).thenReturn(Arrays.asList(wechat, feishu));
        when(wechat.isBound("s1")).thenReturn(true);
        when(wechat.getChannelName()).thenReturn("wechat");
        when(feishu.isBound("s1")).thenReturn(true);
        when(feishu.getChannelName()).thenReturn("feishu");

        new WebStreamBuilder().signalOriginChannel(context, "s1", ImStatus.QUEUED,
                "已加入队列，前面还有 2 条", "WeChat", "u1", "t1", "m1");

        verify(wechat).sendStatus("s1", ImStatus.QUEUED, "已加入队列，前面还有 2 条", "u1", "t1", "m1");
        verify(feishu, never()).sendStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void webSourceStatusSignalDoesNotReachAnyIm() {
        // WEB/Loop 来源没有匹配通道，状态信号不得下发任何 IM
        WorkspaceContext context = mock(WorkspaceContext.class);
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = mock(AgentSession.class);
        ChannelHub hub = mock(ChannelHub.class);
        Channel wechat = mock(Channel.class);
        Channel feishu = mock(Channel.class);
        when(context.getEngine()).thenReturn(engine);
        when(engine.getSession("s1")).thenReturn(session);
        when(session.attrs()).thenReturn(new HashMap<>());
        when(context.getChannelHub()).thenReturn(hub);
        when(hub.getImLinks()).thenReturn(Arrays.asList(wechat, feishu));
        when(wechat.isBound("s1")).thenReturn(true);
        when(wechat.getChannelName()).thenReturn("wechat");
        when(feishu.isBound("s1")).thenReturn(true);
        when(feishu.getChannelName()).thenReturn("feishu");

        new WebStreamBuilder().signalOriginChannel(context, "s1", ImStatus.ACCEPTED,
                null, "WEB", null, null, null);

        verify(wechat, never()).sendStatus(any(), any(), any(), any(), any(), any());
        verify(feishu, never()).sendStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedStartsLongRunningWatchAndFinalCancelsIt() {
        WorkspaceContext context = mock(WorkspaceContext.class);
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = mock(AgentSession.class);
        ChannelHub hub = mock(ChannelHub.class);
        Channel feishu = mock(Channel.class);
        Map<String, Object> attrs = new HashMap<>();
        when(context.getEngine()).thenReturn(engine);
        when(engine.getSession("s1")).thenReturn(session);
        when(session.attrs()).thenReturn(attrs);
        when(context.getChannelHub()).thenReturn(hub);
        when(hub.getImLinks()).thenReturn(Arrays.asList(feishu));
        when(feishu.isBound("s1")).thenReturn(true);
        when(feishu.getChannelName()).thenReturn("feishu");

        WebStreamBuilder builder = new WebStreamBuilder();
        builder.signalOriginChannel(context, "s1", ImStatus.ACCEPTED, null, "Feishu", "u1", null, "m1");
        org.junit.jupiter.api.Assertions.assertTrue(attrs.containsKey("session.im.heartbeat"));

        builder.replyToBoundChannel(context, "s1", "done", true);
        org.junit.jupiter.api.Assertions.assertFalse(attrs.containsKey("session.im.heartbeat"));
    }
}
