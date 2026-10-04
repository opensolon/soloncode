package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.channel.ImMessages;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
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

    @Test
    void busyCommandHintIsTaughtOncePerRoundAndResetsAfterTerminal() {
        // 同一轮忙态连发多条：命令引导（/steer、/interrupt）只随第一条下发，后续只报位次；
        // 终态收尾后标记复位，下一轮忙态重新教学一次。
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
        org.junit.jupiter.api.Assertions.assertTrue(builder.claimCommandHint(session));
        org.junit.jupiter.api.Assertions.assertFalse(builder.claimCommandHint(session));

        builder.replyToBoundChannel(context, "s1", "done", true);
        org.junit.jupiter.api.Assertions.assertTrue(builder.claimCommandHint(session));
    }

    @Test
    void queuedTextStatesQueueSemanticsAndCarriesCommandHint() {
        // 文案已国际化，这里固定简体中文断言正文语义（英文解析见 messagesResolveByLocale）
        ImMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
        try {
            // 忙态直发的默认语义是「新任务排队」，不是「插话」；插话入口（/steer）要一并给出。
            String withHint = ImMessages.queued(2, true);
            org.junit.jupiter.api.Assertions.assertTrue(withHint.contains("新任务排队"), withHint);
            org.junit.jupiter.api.Assertions.assertTrue(withHint.contains("前面还有 2 条"), withHint);
            org.junit.jupiter.api.Assertions.assertTrue(withHint.contains("/steer"), withHint);
            org.junit.jupiter.api.Assertions.assertTrue(withHint.contains("/interrupt"), withHint);

            // 后续入队只报位次，不重复教学；且不引导 /queue（忙态直发即排队）。
            String brief = ImMessages.queued(2, false);
            org.junit.jupiter.api.Assertions.assertTrue(brief.contains("前面还有 2 条"), brief);
            org.junit.jupiter.api.Assertions.assertFalse(brief.contains("/steer"), brief);
            org.junit.jupiter.api.Assertions.assertFalse(brief.contains("/queue"), brief);

            // 位次为 0 时不说「前面还有 0 条」；且须点明「当前任务完成后才轮到」，
            // 不能让用户误以为马上开始（当前任务仍在执行）
            org.junit.jupiter.api.Assertions.assertEquals("收到，已作为新任务排队，当前任务完成后就轮到你。", ImMessages.queued(0, false));
        } finally {
            ImMessages.setLocale(null);
        }
    }

    @Test
    void messagesResolveByLocale() {
        // 国际化：同一组消息按地区解析
        ImMessages.setLocale(Locale.ENGLISH);
        try {
            String en = ImMessages.queued(2, true);
            org.junit.jupiter.api.Assertions.assertTrue(en.contains("queued as a new task"), en);
            org.junit.jupiter.api.Assertions.assertTrue(en.contains("2 ahead of you"), en);
            org.junit.jupiter.api.Assertions.assertTrue(en.contains("/steer"), en);
            org.junit.jupiter.api.Assertions.assertFalse(en.contains("新任务排队"), en);

            // 占位符被替换为空串时不留首尾空白；英文同样点明「当前任务结束后才开始」
            org.junit.jupiter.api.Assertions.assertEquals("Got it, queued as a new task. It will start as soon as the current task finishes.",
                    ImMessages.queued(0, false));

            // 无参文案同样按地区解析；英文回执须交代消息未被接收
            org.junit.jupiter.api.Assertions.assertTrue(ImMessages.REJECTED().contains("was not queued"), ImMessages.REJECTED());
            org.junit.jupiter.api.Assertions.assertFalse(ImMessages.REJECTED().contains("没排上队"), ImMessages.REJECTED());
        } finally {
            ImMessages.setLocale(Locale.FRENCH);
        }

        try {
            // 前端已有法语资源，后端应使用对应语言，而不是回退到默认中文
            org.junit.jupiter.api.Assertions.assertTrue(ImMessages.REJECTED().contains("n'a pas été mis en file"), ImMessages.REJECTED());
        } finally {
            ImMessages.setLocale(null);
        }
    }

    @Test
    void messagesHaveFrontendLocaleBundles() {
        String[] locales = {"ar", "bn", "br", "bs", "da", "de", "en", "es", "fr", "gr", "it", "ja", "ko", "nl", "no", "pl", "ru", "th", "tr", "uk", "vi"};
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String locale : locales) {
            org.junit.jupiter.api.Assertions.assertNotNull(
                    loader.getResource("i18n/im-messages_" + locale + ".properties"),
                    "Missing IM bundle for frontend locale: " + locale);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(loader.getResource("i18n/im-messages.properties"));
    }
}
