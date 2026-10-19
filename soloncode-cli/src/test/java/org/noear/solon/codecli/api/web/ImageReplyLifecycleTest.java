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
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.event.WebEventNames;
import org.noear.solon.codecli.api.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.artifact.ImageReplyTurn;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.artifact.PublishImageTalent;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.channel.ChannelReply;
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
        assertFalse(f.attrs.containsValue(turn.getImages()));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        assertNull(turn.getImages().getRunId());

        turn.onReasonStart(trace(mock(AgentSession.class), "foreign"), new StringBuilder());
        assertNull(turn.getImages().getRunId());
        f.bind(turn, "run-1");
        f.bind(turn, "run-2");
        assertEquals("run-1", turn.getImages().getRunId());
        turn.cleanup();
        assertDoesNotThrow(() -> f.bind(turn, "late"));
        assertFalse(turn.isCurrent());
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
        PublishedImage image = publish(turn);
        byte[] bytes = image.getBytes();
        Files.delete(temp.resolve("image.png"));

        f.builder.replyRunEnd(f.context, f.session, turn, "run", text, false);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "重复", false);
        turn.cleanup();
        ChannelReply reply = f.captureStructured();
        assertEquals(WebStreamBuilder.stripAnsi(text), reply.getText());
        assertTrue(reply.isFinal());
        assertEquals("run", reply.getRunId());
        assertEquals(1, reply.getImages().size());
        assertSame(image, reply.getImages().get(0));
        assertArrayEquals(bytes, reply.getImages().get(0).getBytes());
        assertThrows(UnsupportedOperationException.class, () -> reply.getImages().clear());
        assertTrue(turn.getImages().finish("run", true).isEmpty());
        f.assertCleaned();
    }

    @Test
    void abnormalRunEndDiscardsImagesAndNeverLeaksAnswer() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        publish(turn);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "不应作为成功答复", true);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "迟到成功", false);
        ChannelReply reply = f.captureStructured();
        assertEquals("任务执行异常结束。", reply.getText());
        assertTrue(reply.isFinal());
        assertEquals("run", reply.getRunId());
        assertTrue(reply.getImages().isEmpty());
        assertTrue(turn.getImages().finish("run", true).isEmpty());
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void canceledTerminalDiscardsImagesAndBlocksLateRunEnd() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        publish(turn);
        f.builder.replyCanceledToBoundChannels(f.context, f.session);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "迟到", false);
        f.builder.replyErrorToBoundChannels(f.context, f.session, new IOException("boom"));
        verify(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
        verify(f.other).sendReply("s1", "用户已取消任务。", true);
        f.assertNoStructured();
        assertTrue(turn.getImages().finish("run", true).isEmpty());
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void errorTerminalDiscardsImagesAndBlocksLateRunEnd() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        publish(turn);
        f.builder.replyErrorToBoundChannels(f.context, f.session, new IOException("boom"));
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "迟到", false);
        f.builder.replyCanceledToBoundChannels(f.context, f.session);
        verify(f.origin).sendReply("s1", "任务执行失败：boom", true, "user", "target", "message");
        verify(f.other).sendReply("s1", "任务执行失败：boom", true);
        f.assertNoStructured();
        assertTrue(turn.getImages().finish("run", true).isEmpty());
        turn.cleanup();
        f.assertCleaned();
    }

    @Test
    void cleanupWithoutRunEndOnlyDiscardsAndDoesNotSend() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        publish(turn);
        turn.cleanup();
        turn.cleanup();
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "迟到", false);
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertTrue(turn.getImages().finish("run", true).isEmpty());
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        f.assertCleaned();
    }

    @Test
    void oldCleanupAndLateRunEndCannotTouchNewTurnEvenWithEqualRouteMaps() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn old = f.newTurn();
        f.bind(old, "old");
        publish(old);
        // 值相等但身份不同，防止 remove(key, value) 误删新路由。
        Map<String, String> newRoute = new HashMap<>(f.route);
        f.attrs.put("session.replyRoute", newRoute);
        ImageReplyTurn current = f.newTurn();
        f.bind(current, "new");
        PublishedImage newImage = publish(current);
        assertTrue(old.getImages().finish("old", true).isEmpty());
        old.cleanup();
        f.builder.replyRunEnd(f.context, f.session, old, "old", "旧答复", false);
        assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
        assertFalse(f.attrs.containsValue(current.getImages()));
        assertSame(newRoute, f.attrs.get("session.replyRoute"));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        verifyNoInteractions(f.origin, f.other, f.unbound);

        f.builder.replyRunEnd(f.context, f.session, current, "new", "新答复", false);
        old.cleanup();
        ChannelReply reply = f.captureStructured();
        assertEquals("new", reply.getRunId());
        assertSame(newImage, reply.getImages().get(0));
        assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
        current.cleanup();
        f.assertCleaned();
    }

    @Test
    void mismatchedOrUnboundRunEndCannotClaimTurn() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        PublishedImage image = publish(turn);
        f.builder.replyRunEnd(f.context, f.session, turn, null, "无身份", false);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "未绑定", false);
        f.bind(turn, "run");
        f.builder.replyRunEnd(f.context, f.session, turn, "other", "错误身份", false);
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertSame(f.route, f.attrs.get("session.replyRoute"));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "正确", false);
        assertSame(image, f.captureStructured().getImages().get(0));
        turn.cleanup();
    }

    @Test
    void successfulTerminalBlocksSubsequentErrorAndCancellation() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        publish(turn);
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "成功", false);
        f.builder.replyErrorToBoundChannels(f.context, f.session, new IOException("boom"));
        f.builder.replyCanceledToBoundChannels(f.context, f.session);
        assertEquals("成功", f.captureStructured().getText());
        assertEquals(Boolean.TRUE, f.attrs.get(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        turn.cleanup();
    }

    @Test
    void routeIsImmutableSnapshotEvenIfOriginalMapIsMutatedAndReplaced() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        publish(turn);
        f.route.clear();
        f.route.put("source", "other");
        Map<String, String> replacement = new HashMap<>(f.route);
        f.attrs.put("session.replyRoute", replacement);
        assertThrows(UnsupportedOperationException.class, () -> turn.getRoute().clear());
        f.builder.replyRunEnd(f.context, f.session, turn, "run", "完成", false);
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
        PublishedImage image = publish(turn);
        WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, turn);
        List<WebEvent<?>> events = mapper.mapEvent(runEnd(f.session, "run", "最终答复"));
        assertEquals(1, events.size());
        assertEquals(WebEventNames.SYSTEM_TRACE, events.get(0).getEvent());
        ChannelReply reply = f.captureStructured();
        assertEquals("最终答复", reply.getText());
        assertSame(image, reply.getImages().get(0));
        turn.cleanup();
    }

    @Test
    void mapperTaskWrappedRunEndDoesNotSendOrConsumeParentImages() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        PublishedImage image = publish(turn);
        TaskWrapEvent task = mock(TaskWrapEvent.class);
        // 即使子事件碰巧携带父 runId，也必须先按 task 分支隔离。
        RunEndEvent childEnd = runEnd(f.session, "run", "子任务答复");
        when(task.getRealEvent()).thenReturn(childEnd);
        when(task.getTaskId()).thenReturn("task-1");
        when(task.getParentRunId()).thenReturn("run");
        when(task.getTaskAgentName()).thenReturn("worker");
        WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, turn);
        List<WebEvent<?>> events = mapper.mapEvent(task);
        assertEquals(1, events.size());
        assertEquals(WebEventNames.TASK_DONE, events.get(0).getEvent());
        assertEquals("task-1", events.get(0).getTaskId());
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        mapper.mapEvent(runEnd(f.session, "run", "父任务答复"));
        assertSame(image, f.captureStructured().getImages().get(0));
        turn.cleanup();
    }

    @Test
    void hitlImmediatelyClosesImagesEvenIfNormalRunEndFollows() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        publish(turn);
        HITLPendingEvent pending = mock(HITLPendingEvent.class);
        HITLTask task = mock(HITLTask.class);
        when(task.getToolName()).thenReturn("approval");
        when(pending.getPendingTasks()).thenReturn(Collections.singletonList(task));
        when(pending.getRunId()).thenReturn("run");
        WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, turn);

        assertEquals(1, mapper.mapEvent(pending).size());
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertThrows(IOException.class, () -> publish(turn));
        mapper.mapEvent(runEnd(f.session, "run", "等待确认后结束"));
        ChannelReply reply = f.captureStructured();
        assertEquals("等待确认后结束", reply.getText());
        assertTrue(reply.isFinal());
        assertTrue(reply.getImages().isEmpty());
        assertThrows(IOException.class, () -> publish(turn));
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
        assertThrows(IOException.class, () -> publish(captured[0]));
        assertTrue(captured[0].getImages().finish("run", true).isEmpty());
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
        assertThrows(IOException.class, () -> publish(turn));
        assertTrue(turn.getImages().finish("run", true).isEmpty());
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
            assertThrows(IOException.class, () -> publish(turn));
            f.builder.replyRunEnd(f.context, f.session, turn, "run", "迟到成功", false);
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
        publish(turn);
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
                f.builder.replyRunEnd(f.context, f.session, turn, "run", "迟到成功", false);
            });
            assertTrue(finalStarted.await(5, TimeUnit.SECONDS));
            // 取消发送仍阻塞时，RunEnd 必须能完成终态检查。
            ended.get(5, TimeUnit.SECONDS);
            releaseCancel.countDown();
            canceled.get(5, TimeUnit.SECONDS);
            verify(f.origin).sendReply("s1", "用户已取消任务。", true, "user", "target", "message");
            verify(f.other).sendReply("s1", "用户已取消任务。", true);
            f.assertNoStructured();
            assertTrue(turn.getImages().finish("run", true).isEmpty());
            assertThrows(IOException.class, () -> publish(turn));
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
            assertThrows(IOException.class, () -> publish(old));
            WebEventMapper mapper = new WebEventMapper(f.builder, f.context, f.session, null, old);
            Future<?> ended = workers.submit(() -> mapper.mapEvent(runEnd(f.session, "run", "窗口内成功")));
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
            PublishedImage newImage = null;
            if (replaceTurn) {
                current = f.newTurn();
                f.bind(current, "new");
                newImage = publish(current);
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
            mapper.mapEvent(runEnd(f.session, "run", "迟到成功"));
            assertSame(replacement, f.attrs.get("session.replyRoute"));
            if (replaceTurn) {
                assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
                assertFalse(f.attrs.containsValue(current.getImages()));
                assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
                f.builder.replyRunEnd(f.context, f.session, current, "new", "新轮成功", false);
                ArgumentCaptor<ChannelReply> reply = ArgumentCaptor.forClass(ChannelReply.class);
                verify(f.origin).sendReply(eq("s1"), reply.capture(), eq("new-user"),
                        eq("new-target"), eq("new-message"));
                assertEquals("新轮成功", reply.getValue().getText());
                assertSame(newImage, reply.getValue().getImages().get(0));
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
        PublishedImage image = publish(current);
        assertEquals(1, oldStream.collectList().block(Duration.ofSeconds(5)).size());
        verifyNoInteractions(f.origin, f.other, f.unbound);
        assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
        assertSame(replacement, f.attrs.get("session.replyRoute"));
        assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
        assertThrows(IOException.class, () -> publish(old));
        f.builder.replyRunEnd(f.context, f.session, current, "new", "新答复", false);
        assertSame(image, f.captureStructured().getImages().get(0));
        current.cleanup();
    }

    @Test
    void normalSendDoesNotBlockCancellationOrClearReplacementRoute() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.newTurn();
        f.bind(turn, "run");
        PublishedImage image = publish(turn);
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
                    f.builder.replyRunEnd(f.context, f.session, turn, "run", "完成", false));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            workers.submit(() -> f.builder.replyCanceledToBoundChannels(f.context, f.session))
                    .get(5, TimeUnit.SECONDS);
            Map<String, String> replacement = new HashMap<>(f.route);
            f.attrs.put("session.replyRoute", replacement);
            current = f.newTurn();
            f.bind(current, "new");
            release.countDown();
            ended.get(5, TimeUnit.SECONDS);
            assertSame(image, f.captureStructured().getImages().get(0));
            assertSame(replacement, f.attrs.get("session.replyRoute"));
            assertSame(current, f.attrs.get(ImageReplyTurn.ATTR));
            assertFalse(f.attrs.containsKey(WebStreamBuilder.ATTR_IM_TERMINAL_SENT));
            assertDoesNotThrow(() -> publish((ImageReplyTurn) f.attrs.get(ImageReplyTurn.ATTR)));
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            turn.cleanup();
            if (current != null) current.cleanup();
        }
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

    private PublishedImage publish(ImageReplyTurn turn) throws IOException {
        BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try {
            assertTrue(ImageIO.write(pixels, "png", temp.resolve("image.png").toFile()));
        } finally {
            pixels.flush();
        }
        return turn.getImages().publish("image.png", "图片说明");
    }

    private class Fixture {
        final WorkspaceContext context = mock(WorkspaceContext.class);
        final AgentSession session = mock(AgentSession.class);
        final Channel origin = channelWithoutSessionLock();
        final Channel other = channelWithoutSessionLock();
        final Channel unbound = channelWithoutSessionLock();
        final Map<String, Object> attrs = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, String> route = new HashMap<>();
        final WebStreamBuilder builder = new WebStreamBuilder();

        Fixture() {
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

        private Channel channelWithoutSessionLock() {
            // 所有 sendReply 重载及所有绑定端均在实际调用时检查；覆盖正常、取消和错误回执。
            return mock(Channel.class, invocation -> {
                if ("sendReply".equals(invocation.getMethod().getName())) {
                    assertFalse(Thread.holdsLock(session.attrs()), "Channel 发送不得持有 session.attrs 锁");
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
        }

        ReActAgent agent;
        ReActOptions options;

        ReActRequest mockRequest() {
            agent = mock(ReActAgent.class);
            ReActRequest request = mock(ReActRequest.class);
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

        Flux<WebEvent<?>> buildStream() {
            return builder.buildStreamFlux(context, session, agent, null, temp.toString(), Prompt.of());
        }

        ImageReplyTurn currentTurnWithImage() throws IOException {
            ImageReplyTurn turn = (ImageReplyTurn) attrs.get(ImageReplyTurn.ATTR);
            assertNotNull(turn);
            assertFalse(attrs.containsValue(turn.getImages()));
            assertNotNull(turn.getToken());
            assertFalse(turn.getToken().isEmpty());
            assertEquals(turn.getToken(), options.getToolContext().get(PublishImageTalent.CONTEXT_KEY));
            bind(turn, "run");
            publish(turn);
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
