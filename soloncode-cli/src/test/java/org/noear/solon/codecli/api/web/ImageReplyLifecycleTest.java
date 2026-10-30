package org.noear.solon.codecli.api.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.intercept.HITLPendingEvent;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.artifact.ImageReplyTurn;

import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.channel.ChannelReply;
import org.noear.solon.codecli.channel.feishu.FeishuLink;
import org.noear.solon.codecli.channel.dingtalk.DingTalkLink;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageReplyLifecycleTest {
    private static final String IMAGE_MARKDOWN = "![图片说明](image.png)";

    private String withImage(String text) {
        return text.isEmpty() ? IMAGE_MARKDOWN : text + "\n\n" + IMAGE_MARKDOWN;
    }
    @TempDir
    Path temp;

    @BeforeEach
    void resolveTempDirectory() throws IOException {
        // macOS 临时目录的祖先可能是系统链接，fixture 使用真实基址，保留完整安全校验。
        temp = temp.toRealPath();
    }

    @Test
    void constructorInstallsAttrsAndBindsOnlyFirstReasonOfItsOwnSession() {
        Fixture f = new Fixture();
        f.attrs.put(WebStreamBuilder.ATTR_IM_TERMINAL_SENT, Boolean.TRUE);
        ImageReplyTurn turn = f.newTurn();
        assertSame(turn, f.attrs.get(ImageReplyTurn.ATTR));
        assertTrue(turn.isImagesAllowed());
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        assertFalse(turn.canClaim("run-1"));

        turn.onReasonStart(trace(mock(AgentSession.class), "foreign"), new StringBuilder());
        assertFalse(turn.canClaim("run-1"));
        f.bind(turn, "run-1");
        f.bind(turn, "run-2");
        assertTrue(turn.canClaim("run-1"));
        assertFalse(turn.canClaim("run-2"));
        turn.cleanup();
        assertDoesNotThrow(() -> f.bind(turn, "late"));
        assertFalse(turn.isCurrent());
    }

    @Test
    void pureWebRunEndSkipsImagePreparation() throws Exception {
        Fixture f = new Fixture();
        when(f.context.getChannelHub().getImLinks()).thenReturn(Collections.emptyList());
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();

        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("完成"), false);

        assertEquals(0, f.prepareCalls, "纯 Web 不得进入真实图片准备 hook");
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        assertFalse(turn.canClaim("run"));
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void unboundImageChannelsDoNotEnablePreparation() throws Exception {
        Fixture f = new Fixture();
        when(f.origin.isBound("s1")).thenReturn(false);
        when(f.other.isBound("s1")).thenReturn(false);

        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();

        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("完成"), false);

        assertEquals(0, f.prepareCalls, "未绑定图片 IM 不得触发本地准备");

        f.assertNoStructured();
        assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void boundTextOnlyChannelsReceiveSafePlaceholdersWithoutPreparation() throws Exception {
        // 普通 Channel 是文本端；fixture 中仍注册一个未绑定的图片渠道。
        Fixture f = new Fixture(Channel.class, Channel.class);
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();

        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("完成"), false);

        assertEquals(0, f.prepareCalls, "绑定文本 IM 不得进入真实图片准备 hook");
        ChannelReply reply = f.captureStructured();
        assertTrue(reply.getImages().isEmpty());
        assertEquals("run", reply.getRunId());
        assertTrue(reply.isFinal());
        assertTrue(reply.hasImageReferences());
        assertNull(reply.getImageReferences().get(0).getImage());
        assertEquals("完成\n\n（图片不可用：图片说明；图片发送已禁用）", reply.renderImages(image -> {
            fail("禁图占位不得调用图片发布器");
            return null;
        }));
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void boundImageChannelEnablesPreparationEvenWhenOriginIsTextOnly() throws Exception {
        Fixture f = new Fixture(Channel.class, DingTalkLink.class);
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        Path image = createImage();

        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("完成"), false);

        assertEquals(1, f.prepareCalls, "绑定的非来源图片 IM 同样允许准备快照");
        assertSnapshot(image, f.captureStructured());
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void imageOnlyRunEndSendsOneStructuredSnapshot() throws Exception {
        assertSuccessfulSnapshot("");
    }

    @Test
    void textAndImageRunEndSendsOneStructuredSnapshot() throws Exception {
        assertSuccessfulSnapshot("\u001B[32m图片已完成\u001B[0m");
    }

    private void assertSuccessfulSnapshot(String text) throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        Path image = createImage();
        assertEquals(0, f.prepareCalls);
        verifyNoInteractions(f.origin, f.other, f.unbound);
        // fixture 只写文件；直到最终 Markdown 的 RunEnd 才允许读取图片。
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage(text), false);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "重复", false);
        turn.cleanup();
        ChannelReply reply = f.captureStructured();
        assertEquals(WebStreamBuilder.stripAnsi(withImage(text)), reply.getText());
        assertTrue(reply.isFinal());
        assertEquals("run", reply.getRunId());
        assertEquals(1, reply.getImages().size());
        assertSnapshot(image, reply);
        byte[] bytes = reply.getImages().get(0).getBytes();
        Files.delete(image);
        assertArrayEquals(bytes, reply.getImages().get(0).getBytes());
        assertEquals(withImage(WebStreamBuilder.stripAnsi(text)).replace(IMAGE_MARKDOWN,
                "![图片说明](https://example.test/snapshot.png)"),
                reply.renderImages(snapshot -> {
                    assertArrayEquals(bytes, snapshot.getBytes());
                    return "https://example.test/snapshot.png";
                }));
        assertThrows(UnsupportedOperationException.class, () -> reply.getImages().clear());
        assertFalse(turn.canClaim("run"));
        f.assertCleaned();
    }

    @Test
    void abnormalRunEndDiscardsImagesAndNeverLeaksAnswer() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("不应作为成功答复"), true);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("迟到成功"), false);
        ChannelReply reply = f.captureStructured();
        assertEquals("任务执行异常结束。", reply.getText());
        assertTrue(reply.isFinal());
        assertEquals("run", reply.getRunId());
        assertTrue(reply.getImages().isEmpty());
        assertEquals(0, f.prepareCalls, "异常终态不得准备成功答复图片");
        assertFalse(turn.canClaim("run"));
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void canceledTerminalDiscardsImagesAndBlocksLateRunEnd() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        f.builder.replyCanceledToBoundChannels(f.context, f.session);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("迟到"), false);
        f.builder.replyErrorToBoundChannels(f.context, f.session, new IOException("boom"));
        verify(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
        verify(f.other).sendReply("s1", "用户已取消任务。", true);
        f.assertNoStructured();
        assertFalse(turn.canClaim("run"));
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void errorTerminalDiscardsImagesAndBlocksLateRunEnd() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        f.builder.replyErrorToBoundChannels(f.context, f.session, new IOException("boom"));
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("迟到"), false);
        f.builder.replyCanceledToBoundChannels(f.context, f.session);
        verify(f.origin).sendReply("s1", "任务执行失败：boom", true, "user", "target", "message");
        verify(f.other).sendReply("s1", "任务执行失败：boom", true);
        f.assertNoStructured();
        assertFalse(turn.canClaim("run"));
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void cleanupWithoutRunEndOnlyDiscardsAndDoesNotSend() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        turn.cleanup();
        turn.cleanup();
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("迟到"), false);
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertFalse(turn.canClaim("run"));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        f.assertCleaned();
    }

    @Test
    void oldCleanupAndLateRunEndCannotTouchNewTurnEvenWithEqualRouteMaps() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn old = f.newTurn();
        f.bind(old, "old");
        createImage();
        // 值相等但身份不同，防止 remove(key, value) 误删新路由。
        Map<String, String> newRoute = new HashMap<>(f.route);
        f.attrs.put("session.replyRoute", newRoute);
        ImageReplyTurn current = f.newTurn();
        f.bind(current, "new");
        Path newImage = createImage();
        assertFalse(old.canClaim("old"));
        old.cleanup();
        f.builder.replyRunEnd(f.context, f.session, old, "old", withImage("旧答复"), false);
        assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
        assertTrue(current.isImagesAllowed());
        assertSame(newRoute, f.attrs.get("session.replyRoute"));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        verifyNoInteractions(f.origin, f.other, f.unbound);

        f.builder.replyRunEnd(f.context, f.session, current, "new", withImage("新答复"), false);
        old.cleanup();
        ChannelReply reply = f.captureStructured();
        assertEquals("new", reply.getRunId());
        assertSnapshot(newImage, reply);
        assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
        current.cleanup();
        f.assertCleaned();
    }

    @Test
    void mismatchedOrUnboundRunEndCannotClaimTurn() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        Path image = createImage();
        f.builder.replyRunEnd(f.context, f.session, turn, null, withImage("无身份"), false);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("未绑定"), false);
        f.bind(turn, "run");
        f.builder.replyRunEnd(f.context, f.session, turn, "other", withImage("错误身份"), false);
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertSame(f.route, f.attrs.get("session.replyRoute"));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        assertEquals(0, f.prepareCalls, "身份预检失败不得进入 Markdown 解析与图片读取");
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("正确"), false);
        assertEquals(1, f.prepareCalls);
        assertSnapshot(image, f.captureStructured());
        turn.cleanup();
    }

    @Test
    void successfulTerminalBlocksSubsequentErrorAndCancellation() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("成功"), false);
        f.builder.replyErrorToBoundChannels(f.context, f.session, new IOException("boom"));
        f.builder.replyCanceledToBoundChannels(f.context, f.session);
        assertEquals(withImage("成功"), f.captureStructured().getText());
        assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        turn.cleanup();
    }

    @Test
    void routeIsImmutableSnapshotEvenIfOriginalMapIsMutatedAndReplaced() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        f.route.clear();
        f.route.put("source", "other");
        Map<String, String> replacement = new HashMap<>(f.route);
        f.attrs.put("session.replyRoute", replacement);
        assertThrows(UnsupportedOperationException.class, () -> turn.getRoute().clear());
        f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("完成"), false);
        f.captureStructured();
        assertSame(replacement, f.attrs.get("session.replyRoute"));
        turn.cleanup();
        assertSame(replacement, f.attrs.get("session.replyRoute"));
    }

    @Test
    void mapperSendsTopLevelNormalRunEndThroughImageLifecycle() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        Path image = createImage();
        WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, turn);
        List<WebEvent<?>> events = mapper.mapEvent(runEnd(f.session, "run", withImage("最终答复")));
        assertEquals(1, events.size());
        assertEquals(WebEventNames.SYSTEM_TRACE, events.get(0).getEvent());
        ChannelReply reply = f.captureStructured();
        assertEquals(withImage("最终答复"), reply.getText());
        assertSnapshot(image, reply);
        turn.cleanup();
    }

    @Test
    void mapperTaskWrappedRunEndDoesNotSendOrConsumeParentImages() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        Path image = createImage();
        TaskWrapEvent task = mock(TaskWrapEvent.class);
        // 即使子事件碰巧携带父 runId，也必须先按 task 分支隔离。
        RunEndEvent childEnd = runEnd(f.session, "run", withImage("子任务答复"));
        when(task.getRealEvent()).thenReturn(childEnd);
        when(task.getTaskId()).thenReturn("task-1");
        when(task.getParentRunId()).thenReturn("run");
        when(task.getTaskAgentName()).thenReturn("worker");
        WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, turn);
        List<WebEvent<?>> events = mapper.mapEvent(task);
        assertEquals(1, events.size());
        assertEquals(WebEventNames.TASK_DONE, events.get(0).getEvent());
        assertEquals("task-1", events.get(0).getTaskId());
        assertEquals(0, f.prepareCalls, "子任务 RunEnd 不得解析父轮最终图片");
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        mapper.mapEvent(runEnd(f.session, "run", withImage("父任务答复")));
        assertSnapshot(image, f.captureStructured());
        turn.cleanup();
    }

    @Test
    void hitlImmediatelyClosesImagesEvenIfNormalRunEndFollows() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        HITLPendingEvent pending = mock(HITLPendingEvent.class);
        HITLTask task = mock(HITLTask.class);
        when(task.getToolName()).thenReturn("approval");
        when(pending.getPendingTasks()).thenReturn(Collections.singletonList(task));
        when(pending.getRunId()).thenReturn("run");
        WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, turn);

        assertEquals(1, mapper.mapEvent(pending).size());
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertEquals(0, f.prepareCalls, "HITL 之前的生命周期不得解析最终图片");
        assertFalse(turn.isImagesAllowed());
        Files.delete(temp.resolve("image.png"));
        mapper.mapEvent(runEnd(f.session, "run", withImage("等待确认后结束")));
        ChannelReply reply = f.captureStructured();
        assertEquals(withImage("等待确认后结束"), reply.getText());
        assertTrue(reply.isFinal());
        assertTrue(reply.getImages().isEmpty());
        assertTrue(reply.hasImageReferences());
        assertEquals("等待确认后结束\n\n（图片不可用：图片说明；图片发送已禁用）", reply.renderImages(snapshot -> {
            fail("禁图降级不得调用图片发布器");
            return null;
        }));
        assertFalse(turn.isImagesAllowed());
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void synchronousStreamAssemblyFailureSendsErrorAndCleansOwnedState() throws Exception {
        Fixture f = new Fixture();
        ReActRequest request = f.mockRequest();
        IllegalStateException failure = new IllegalStateException("assembly boom");
        ImageReplyTurn[] captured = new ImageReplyTurn[1];
        when(request.stream()).thenAnswer(invocation -> {
            captured[0] = f.currentTurnWithImage();
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, f::buildStream));
        verify(f.origin).sendReply("s1", "任务执行失败：assembly boom", true, "user", "target", "message");
        verify(f.other).sendReply("s1", "任务执行失败：assembly boom", true);
        f.assertNoStructured();
        f.assertCleaned();
        assertFalse(captured[0].isImagesAllowed());
        assertFalse(captured[0].canClaim("run"));
    }

    @Test
    void fluxErrorRecoversWithWebErrorAndFinallyCleansImagesAndRoute() throws Exception {
        Fixture f = new Fixture();
        ReActRequest request = f.mockRequest();
        when(request.stream()).thenReturn(Flux.error(new IOException("flux boom")));
        Flux<WebEvent<?>> stream = f.buildStream();
        ImageReplyTurn turn = f.currentTurnWithImage();

        List<WebEvent<?>> events = stream.collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals(WebEvent.ofError(new IOException("flux boom")).getEvent(), events.get(0).getEvent());
        verify(f.origin).sendReply("s1", "任务执行失败：flux boom", true, "user", "target", "message");
        verify(f.other).sendReply("s1", "任务执行失败：flux boom", true);
        f.assertNoStructured();
        f.assertCleaned();
        assertFalse(turn.isImagesAllowed());
        assertFalse(turn.canClaim("run"));
    }

    @Test
    void subscriptionCancelRunsFinallyWithoutSendingImagesOrInventingSuccess() throws Exception {
        Fixture f = new Fixture();
        ReActRequest request = f.mockRequest();
        CountDownLatch upstreamCanceled = new CountDownLatch(1);
        when(request.stream()).thenReturn(Flux.<AgentEvent>never()
                .doOnCancel(upstreamCanceled::countDown));
        Flux<WebEvent<?>> stream = f.buildStream();
        ImageReplyTurn turn = f.currentTurnWithImage();
        Disposable subscription = stream.subscribe(event -> fail("取消不应产生事件"));
        try {
            assertSame(turn, f.attrs.get(ImageReplyTurn.ATTR));
            subscription.dispose();
            assertTrue(upstreamCanceled.await(5, TimeUnit.SECONDS));
            f.assertCleaned();
            assertFalse(turn.isImagesAllowed());
            f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("迟到成功"), false);
            verifyNoInteractions(f.origin, f.other, f.unbound);
            assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void cancellationWinningConcurrentRunEndSendsOnlyOneTextTerminal() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        createImage();
        CountDownLatch cancelSending = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        CountDownLatch finalStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(f.session.attrs()), "Channel 发送不得持有终态锁");
            cancelSending.countDown();
            assertTrue(releaseCancel.await(5, TimeUnit.SECONDS));
            return null;
        }).when(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> canceled = workers.submit(() -> f.builder.replyCanceledToBoundChannels(f.context, f.session));
            assertTrue(cancelSending.await(5, TimeUnit.SECONDS));
            Future<?> ended = workers.submit(() -> {
                finalStarted.countDown();
                f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("迟到成功"), false);
            });
            assertTrue(finalStarted.await(5, TimeUnit.SECONDS));
            // 取消发送仍阻塞时，RunEnd 必须能完成终态检查。
            ended.get(5, TimeUnit.SECONDS);
            releaseCancel.countDown();
            canceled.get(5, TimeUnit.SECONDS);
            verify(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
            verify(f.other).sendReply("s1", "用户已取消任务。", true);
            f.assertNoStructured();
            assertFalse(turn.canClaim("run"));
            assertFalse(turn.isImagesAllowed());
            assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        } finally {
            releaseCancel.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            turn.cleanup();
        }
        f.assertCleaned();
    }

    @Test
    void realInterruptReservesCancellationBeforeBlockedSnapshotAndConcurrentRunEnd() throws Exception {
        assertRealInterruptWindow(false);
    }

    @Test
    void delayedRealInterruptReplyAndOldCleanupCannotAffectNewTurn() throws Exception {
        assertRealInterruptWindow(true);
    }

    private void assertRealInterruptWindow(boolean replaceTurn) throws Exception {
        Fixture f = new Fixture();
        org.noear.solon.codecli.session.SessionManager manager =
                mock(org.noear.solon.codecli.session.SessionManager.class);
        when(f.context.getSessionManager()).thenReturn(manager);
        when(manager.getSession("s1")).thenReturn(f.session);
        ReActRequest request = f.mockRequest();
        when(request.stream()).thenReturn(Flux.never());
        Flux<WebEvent<?>> stream = f.buildStream();
        ImageReplyTurn old = f.currentTurnWithImage();
        Disposable.Composite composite = reactor.core.Disposables.composite();
        composite.add(stream.subscribe());
        f.attrs.put("disposable", composite);
        WebGate gate = new WebGate(null) {
            @Override
            public void emitToClient(WorkspaceContext context, String sessionId, WebEvent<?> event) {
                // 保留真实取消入口和 done 门，仅替换 WebSocket 出口。
            }
        };
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean snapshotReturned = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(f.session.attrs()));
            assertTrue(snapshotReturned.get(), "取消回执必须在持久化完成之后发送");
            return null;
        }).when(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(f.attrs), "持久化不能扩大 attrs 临界区");
            snapshotEntered.countDown();
            assertTrue(releaseSnapshot.await(5, TimeUnit.SECONDS));
            snapshotReturned.set(true);
            return null;
        }).when(f.session).updateSnapshot();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ImageReplyTurn current = null;
        try {
            Future<Boolean> canceled = workers.submit(() -> gate.interruptSession(f.context, "s1"));
            assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
            assertFalse(old.isImagesAllowed());
            WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, old);
            Future<?> ended = workers.submit(() -> mapper.mapEvent(runEnd(f.session, "run", withImage("窗口内成功"))));
            ended.get(5, TimeUnit.SECONDS);
            // RunEnd 必须在持久化仍阻塞时返回，且不能投递成功文本或图片。
            f.assertNoStructured();
            verifyNoInteractions(f.origin, f.other, f.unbound);

            // 重复 Stop 不应再写取消历史或重复领取 IM 终态。
            assertTrue(gate.interruptSession(f.context, "s1"));
            verify(f.session).updateSnapshot();
            Map<String, String> replacement = new HashMap<>(f.route);
            replacement.put("sourceUserId", "new-user");
            replacement.put("replyTarget", "new-target");
            replacement.put("messageId", "new-message");
            f.route.clear();
            f.attrs.put("session.replyRoute", replacement);
            Path newImage = null;
            if (replaceTurn) {
                current = f.newTurn();
                f.bind(current, "new");
                newImage = createImage();
                assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
            }
            releaseSnapshot.countDown();
            assertTrue(canceled.get(5, TimeUnit.SECONDS));
            assertTrue(composite.isDisposed());
            verify(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
            verify(f.other).sendReply("s1", "用户已取消任务。", true);
            verify(f.origin, never()).sendReply("s1", "用户已取消任务。", true,
                    "new-user", "new-target", "new-message");
            f.assertNoStructured();
            old.cleanup();
            mapper.mapEvent(runEnd(f.session, "run", withImage("迟到成功")));
            assertSame(replacement, f.attrs.get("session.replyRoute"));
            if (replaceTurn) {
                assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
                assertTrue(current.isImagesAllowed());
                assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
                f.builder.replyRunEnd(f.context, f.session, current, "new", withImage("新轮成功"), false);
                ArgumentCaptor<ChannelReply> reply = ArgumentCaptor.forClass(ChannelReply.class);
                verify(f.origin).sendReply(eq("s1"), reply.capture(), eq("new-user"),
                        eq("new-target"), eq("new-message"));
                assertEquals(withImage("新轮成功"), reply.getValue().getText());
                assertSnapshot(newImage, reply.getValue());
                verify(f.other).sendReply(eq("s1"), same(reply.getValue()), isNull(), isNull(), isNull());
            } else {
                assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
                f.assertNoStructured();
            }
            verify(f.session).updateSnapshot();
        } finally {
            releaseSnapshot.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            composite.dispose();
            old.cleanup();
            if (current != null) current.cleanup();
        }
    }

    @Test
    void realInterruptDisposesCompositeWhenCanceledDeliveryThrows() throws Exception {
        Fixture f = new Fixture();
        org.noear.solon.codecli.session.SessionManager manager =
                mock(org.noear.solon.codecli.session.SessionManager.class);
        when(f.context.getSessionManager()).thenReturn(manager);
        when(manager.getSession("s1")).thenReturn(f.session);
        ReActRequest request = f.mockRequest();
        CountDownLatch upstreamCanceled = new CountDownLatch(1);
        when(request.stream()).thenReturn(Flux.<AgentEvent>never().doOnCancel(upstreamCanceled::countDown));
        Flux<WebEvent<?>> stream = f.buildStream();
        ImageReplyTurn turn = f.currentTurnWithImage();
        Disposable.Composite composite = reactor.core.Disposables.composite();
        composite.add(stream.subscribe());
        f.attrs.put("disposable", composite);
        WebStreamBuilder failingBuilder = new WebStreamBuilder() {
            @Override
            Runnable prepareCanceledReply(WorkspaceContext context, AgentSession session) {
                // 先真实领取终态，再模拟回执动作整体失败；渠道异常会被广播层隔离，不能覆盖此路径。
                Runnable delivery = super.prepareCanceledReply(context, session);
                return () -> {
                    delivery.run();
                    throw new IllegalStateException("canceled delivery failed");
                };
            }
        };
        WebGate gate = new WebGate(null) {
            @Override
            public WebStreamBuilder getStreamBuilder() {
                return failingBuilder;
            }

            @Override
            public void emitToClient(WorkspaceContext context, String sessionId, WebEvent<?> event) {
                // 仅替换 WebSocket 出口，保留真实取消入口。
            }
        };
        try {
            assertFalse(gate.interruptSession(f.context, "s1"));
            // 必须在测试 finally 主动清理之前断言，避免清理掩盖生产取消失效。
            assertTrue(composite.isDisposed());
            assertTrue(upstreamCanceled.await(5, TimeUnit.SECONDS));
            assertFalse(f.attrs.containsKey("disposable"));
            f.assertCleaned();
            assertFalse(turn.isImagesAllowed());
            assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
            f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("迟到成功"), false);
            f.assertNoStructured();
            verify(f.session).updateSnapshot();
            verify(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
            verify(f.other).sendReply("s1", "用户已取消任务。", true);
        } finally {
            composite.dispose();
            turn.cleanup();
        }
    }

    @Test
    void preparedCancellationIsOneShotAndKeepsFallbackRouteSnapshot() {
        Fixture f = new Fixture();
        Runnable delivery = f.builder.prepareCanceledReply(f.context, f.session);
        Map<String, String> replacement = new HashMap<>(f.route);
        f.route.clear();
        f.attrs.put("session.replyRoute", replacement);
        delivery.run();
        delivery.run();
        verify(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
        verify(f.other).sendReply("s1", "用户已取消任务。", true);
        assertSame(replacement, f.attrs.get("session.replyRoute"));
    }

    @Test
    void oldFluxErrorCannotCloseOrSendForReplacementTurn() throws Exception {
        Fixture f = new Fixture();
        ReActRequest request = f.mockRequest();
        when(request.stream()).thenReturn(Flux.error(new IOException("old error")));
        Flux<WebEvent<?>> oldStream = f.buildStream();
        ImageReplyTurn old = f.currentTurnWithImage();
        Map<String, String> replacement = new HashMap<>(f.route);
        f.attrs.put("session.replyRoute", replacement);
        ImageReplyTurn current = f.newTurn();
        f.bind(current, "new");
        Path image = createImage();
        assertEquals(1, oldStream.collectList().block(Duration.ofSeconds(5)).size());
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
        assertSame(replacement, f.attrs.get("session.replyRoute"));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        assertFalse(old.isImagesAllowed());
        f.builder.replyRunEnd(f.context, f.session, current, "new", withImage("新答复"), false);
        assertSnapshot(image, f.captureStructured());
        current.cleanup();
    }

    @Test
    void normalSendDoesNotBlockCancellationOrClearReplacementRoute() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        Path image = createImage();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(f.session.attrs()));
            sending.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(f.origin).sendReply(eq("s1"), any(ChannelReply.class), any(), any(), any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ImageReplyTurn current = null;
        try {
            Future<?> ended = workers.submit(() ->
                    f.builder.replyRunEnd(f.context, f.session, turn, "run", withImage("完成"), false));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            // 成功终态已提交交付：后续取消不得撤销快照，尚未发送的 other 也继续接收图片。
            workers.submit(() -> f.builder.replyCanceledToBoundChannels(f.context, f.session))
                    .get(5, TimeUnit.SECONDS);
            Map<String, String> replacement = new HashMap<>(f.route);
            f.attrs.put("session.replyRoute", replacement);
            current = f.newTurn();
            f.bind(current, "new");
            release.countDown();
            ended.get(5, TimeUnit.SECONDS);
            assertSnapshot(image, f.captureStructured());
            assertSame(replacement, f.attrs.get("session.replyRoute"));
            assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
            assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
            assertTrue(((ImageReplyTurn) f.attrs.get(ImageReplyTurn.ATTR)).isImagesAllowed());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            turn.cleanup();
            if (current != null) current.cleanup();
        }
    }

    @Test
    void cancellationDuringPrepareRejectsAlreadyPreparedImagesAndAnswer() throws Exception {
        assertPrepareWindow("cancel");
    }

    @Test
    void replacementDuringPrepareRejectsOldReplyWithoutTouchingNewRoute() throws Exception {
        assertPrepareWindow("replace");
    }

    @Test
    void hitlDuringPrepareDowngradesAlreadyPreparedSnapshotAtClaim() throws Exception {
        assertPrepareWindow("hitl");
    }

    @Test
    void errorDuringPrepareRejectsAlreadyPreparedImagesAndAnswer() throws Exception {
        assertPrepareWindow("error");
    }

    private void assertPrepareWindow(String action) throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn old = f.newTurn();
        f.bind(old, "old");
        Path image = createImage();
        f.blockedPrepareTurn = old;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ImageReplyTurn current = null;
        try {
            Future<?> ended = workers.submit(() ->
                    f.builder.replyRunEnd(f.context, f.session, old, "old", withImage("旧轮完成"), false));
            assertTrue(f.prepareEntered.await(5, TimeUnit.SECONDS));
            assertSnapshot(image, f.preparedReply);
            byte[] preparedBytes = f.preparedReply.getImages().get(0).getBytes();
            assertTrue(old.canClaim("old"), "prepare 不能提前领取终态");
            // 第二线程必须在 prepare 仍暂停时完成，防止锁内解析或读取扩大临界区。
            Future<?> changed = workers.submit(() -> {
                if ("cancel".equals(action)) {
                    f.builder.replyCanceledToBoundChannels(f.context, f.session);
                } else if ("error".equals(action)) {
                    f.builder.replyErrorToBoundChannels(f.context, f.session, new IOException("prepare boom"));
                } else if ("hitl".equals(action)) {
                    new WebEventMapper(f.builder, f.context, f.session, null, old).mapEvent(pending("old"));
                } else {
                    Map<String, String> replacement = new HashMap<>(f.route);
                    f.attrs.put("session.replyRoute", replacement);
                    ImageReplyTurn replacementTurn = f.newTurn();
                    f.bind(replacementTurn, "new");
                }
            });
            changed.get(5, TimeUnit.SECONDS);
            assertFalse(old.isImagesAllowed());
            Files.delete(image);
            if ("replace".equals(action)) {
                current = (ImageReplyTurn) f.attrs.get(ImageReplyTurn.ATTR);
                assertNotSame(old, current);
            }
            f.releasePrepare.countDown();
            ended.get(5, TimeUnit.SECONDS);
            assertArrayEquals(preparedBytes, f.preparedReply.getImages().get(0).getBytes());
            if ("hitl".equals(action)) {
                ChannelReply reply = f.captureStructured();
                assertEquals(withImage("旧轮完成"), reply.getText());
                assertEquals("old", reply.getRunId());
                assertTrue(reply.isFinal());
                assertTrue(reply.getImages().isEmpty());
                assertEquals("旧轮完成\n\n（图片不可用：图片说明；图片发送已禁用）", reply.renderImages(snapshot -> {
                    fail("HITL 后不得上传 prepare 阶段的图片");
                    return null;
                }));
                assertEquals(1, reply.getImageReferences().size());
                assertNull(reply.getImageReferences().get(0).getImage());
                assertFalse(old.canClaim("old"));
            } else if ("replace".equals(action)) {
                // 准备前已有绑定与能力查询，但旧轮不得向任何渠道交付消息。
                f.assertNoStructured();
                verify(f.origin, never()).sendReply(anyString(), anyString(), anyBoolean(), any(), any(), any());
                verify(f.other, never()).sendReply(anyString(), anyString(), anyBoolean());
                Object replacement = f.attrs.get("session.replyRoute");
                old.cleanup();
                assertSame(replacement, f.attrs.get("session.replyRoute"));
                assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
                assertTrue(current.canClaim("new"));
                assertTrue(current.isImagesAllowed());
                assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
                Path newImage = createImage();
                f.builder.replyRunEnd(f.context, f.session, current, "new", withImage("新轮完成"), false);
                ChannelReply reply = f.captureStructured();
                assertEquals("new", reply.getRunId());
                assertSnapshot(newImage, reply);
            } else {
                String expected = "cancel".equals(action) ? "用户已取消任务。" : "任务执行失败：prepare boom";
                verify(f.origin).sendReply("s1", expected, true, "user", "target", "message");
                verify(f.other).sendReply("s1", expected, true);
                f.assertNoStructured();
                assertFalse(old.canClaim("old"));
                assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
            }
        } finally {
            f.releasePrepare.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            old.cleanup();
            if (current != null) current.cleanup();
        }
        f.assertCleaned();
    }

    private HITLPendingEvent pending(String runId) {
        HITLPendingEvent pending = mock(HITLPendingEvent.class);
        HITLTask task = mock(HITLTask.class);
        when(task.getToolName()).thenReturn("approval");
        when(pending.getPendingTasks()).thenReturn(Collections.singletonList(task));
        when(pending.getRunId()).thenReturn(runId);
        return pending;
    }

    private ReActTrace trace(AgentSession session, String runId) {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getSession()).thenReturn(session);
        when(trace.getRunId()).thenReturn(runId);
        return trace;
    }

    private RunEndEvent runEnd(AgentSession session, String runId, String answer) {
        ReActTrace trace = trace(session, runId);
        when(trace.getAgentName()).thenReturn("main");
        when(trace.getFinalAnswer()).thenReturn(answer);
        RunEndEvent event = mock(RunEndEvent.class);
        when(event.getRunId()).thenReturn(runId);
        when(event.getTrace()).thenReturn(trace);
        return event;
    }

    private void assertSnapshot(Path source, ChannelReply reply) throws IOException {
        assertEquals(1, reply.getImages().size());
        assertEquals(1, reply.getImageReferences().size());
        assertEquals("图片说明", reply.getImageReferences().get(0).getAlt());
        assertSame(reply.getImages().get(0), reply.getImageReferences().get(0).getImage());
        assertArrayEquals(Files.readAllBytes(source), reply.getImages().get(0).getBytes());
    }

    private Path createImage() throws IOException {
        BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try {
            assertTrue(ImageIO.write(pixels, "png", temp.resolve("image.png").toFile()));
        } finally {
            pixels.flush();
        }
        return temp.resolve("image.png");
    }

    private class Fixture {
        final WorkspaceContext context = mock(WorkspaceContext.class);
        final AgentSession session = mock(AgentSession.class);
        final Channel origin;
        final Channel other;
        final Channel unbound;
        final Map<String, Object> attrs = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, String> route = new HashMap<>();
        volatile ImageReplyTurn blockedPrepareTurn;
        volatile ChannelReply preparedReply;
        final CountDownLatch prepareEntered = new CountDownLatch(1);
        final CountDownLatch releasePrepare = new CountDownLatch(1);
        int prepareCalls;
        final WebStreamBuilder builder = new WebStreamBuilder() {
            @Override
            protected ChannelReply prepareRunEndReply(ImageReplyTurn turn, String text, String runId) {
                assertFalse(Thread.holdsLock(session.attrs()),
                        "Markdown 解析与图片读取不得持有 session.attrs 锁");
                prepareCalls++;
                ChannelReply reply = super.prepareRunEndReply(turn, text, runId);
                if (turn == blockedPrepareTurn) {
                    preparedReply = reply;
                    prepareEntered.countDown();
                    try {
                        assertTrue(releasePrepare.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return reply;
            }
        };

        Fixture() {
            // hook 竞态测试必须使用图片实现类型，确保进入真实快照准备流程。
            this(FeishuLink.class, DingTalkLink.class);
        }

        Fixture(Class<? extends Channel> originType, Class<? extends Channel> otherType) {
            origin = channelWithoutSessionLock(originType);
            other = channelWithoutSessionLock(otherType);
            unbound = channelWithoutSessionLock(FeishuLink.class);
            HarnessEngine engine = mock(HarnessEngine.class);
            ChannelHub hub = mock(ChannelHub.class);
            when(context.getEngine()).thenReturn(engine);
            when(engine.getSession("s1")).thenReturn(session);
            when(session.getSessionId()).thenReturn("s1");
            when(session.attrs()).thenReturn(attrs);
            when(context.getChannelHub()).thenReturn(hub);
            when(hub.getImLinks()).thenReturn(Arrays.asList(origin, other, unbound));
            when(origin.isBound("s1")).thenReturn(true);
            when(other.isBound("s1")).thenReturn(true);

            when(origin.getChannelName()).thenReturn("origin");
            when(other.getChannelName()).thenReturn("other");
            route.put("source", "Origin");
            route.put("sourceUserId", "user");
            route.put("replyTarget", "target");
            route.put("messageId", "message");
            attrs.put("session.replyRoute", route);
        }

        private Channel channelWithoutSessionLock(Class<? extends Channel> type) {
            // 发送在实际调用时检查；图片能力由明确的实现类型判断，无需调用渠道 API。
            return mock(type, invocation -> {
                String method = invocation.getMethod().getName();
                if ("sendReply".equals(method)) {
                    assertFalse(Thread.holdsLock(session.attrs()), "Channel 发送不得持有 session.attrs 锁");
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
        }

        ReActAgent agent;
        ReActOptions options;
        ReActRequest request;

        ReActRequest mockRequest() {
            if (request != null) {
                // 同一 fixture 内复用：测试体对 request.stream() 的打桩必须作用在
                // buildStream() 实际使用的同一实例上。
                return request;
            }
            agent = mock(ReActAgent.class);
            request = mock(ReActRequest.class);
            WorkspaceMeta meta = new WorkspaceMeta();
            meta.setPath(temp.toString());
            when(context.getMeta()).thenReturn(meta);
            when(session.getContext()).thenReturn(mock(org.noear.solon.flow.FlowContext.class));
            when(agent.name()).thenReturn("main");
            when(agent.prompt(any(Prompt.class))).thenReturn(request);
            when(request.session(session)).thenReturn(request);
            options = new ReActOptions(null);
            doAnswer(invocation -> {
                Consumer<ReActOptionsAmend> configure = invocation.getArgument(0);
                configure.accept(new ReActOptionsAmend(options));
                return request;
            }).when(request).options(any());
            return request;
        }

        /** 模拟引擎装配结果：request 由 mock 链提供，outcome 只需携带 request/agent 名。 */
        InputOutcome outcome(ReActRequest request) {
            return InputOutcome.task(Prompt.of(), "main", null, request);
        }

        Flux<WebEvent<?>> buildStream() {
            return builder.buildStreamFlux(context, session, outcome(mockRequest()), null, temp.toString());
        }

        ImageReplyTurn currentTurnWithImage() throws IOException {
            ImageReplyTurn turn = (ImageReplyTurn) attrs.get(ImageReplyTurn.ATTR);
            assertNotNull(turn);
            assertTrue(turn.isImagesAllowed());
            assertFalse(turn.canClaim("run"));
            bind(turn, "run");
            createImage();
            return turn;
        }

        ImageReplyTurn newTurn() {
            return new ImageReplyTurn(session, temp.toString(), temp.toString());
        }

        void bind(ImageReplyTurn turn, String runId) {
            turn.onReasonStart(trace(session, runId), new StringBuilder());
        }

        ChannelReply captureStructured() {
            ArgumentCaptor<ChannelReply> captor = ArgumentCaptor.forClass(ChannelReply.class);
            verify(origin).sendReply(eq("s1"), captor.capture(), eq("user"), eq("target"), eq("message"));
            ChannelReply reply = captor.getValue();
            verify(other).sendReply(eq("s1"), same(reply), isNull(), isNull(), isNull());
            // 查询绑定状态不计入消息断言；其余调用必须都已核对。
            verify(origin, atLeastOnce()).isBound("s1");
            verify(origin, atLeastOnce()).getChannelName();
            verify(other, atLeastOnce()).isBound("s1");
            verify(other, atLeastOnce()).getChannelName();
            verify(unbound, atLeastOnce()).isBound("s1");
            verifyNoMoreInteractions(origin, other, unbound);
            return reply;
        }

        void assertNoStructured() {
            verify(origin, never()).sendReply(anyString(), any(ChannelReply.class), any(), any(), any());
            verify(other, never()).sendReply(anyString(), any(ChannelReply.class), any(), any(), any());
            verify(unbound, never()).sendReply(anyString(), any(ChannelReply.class), any(), any(), any());
        }

        void assertCleaned() {
            assertFalse(attrs.containsKey(ImageReplyTurn.ATTR));
            
            assertFalse(attrs.containsKey("session.replyRoute"));
        }
    }
}
