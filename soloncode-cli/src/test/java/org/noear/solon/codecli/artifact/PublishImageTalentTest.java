package org.noear.solon.codecli.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;


import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishImageTalentTest {
    @TempDir
    Path temp;

    @org.junit.jupiter.api.BeforeEach
    void realTemporaryRoot() throws IOException {
        temp = temp.toRealPath();
    }

    @Test
    void requiresTrustedTokenAndBoundRunId() throws Exception {
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = InMemoryAgentSession.of("session");
        when(engine.getSession("session")).thenReturn(session);
        PublishImageTalent talent = new PublishImageTalent(engine);
        writeImage();
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, null, "token"));
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, "missing", "token"));
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, "session", "token"));
        ImageReplyTurn turn = new ImageReplyTurn(session, temp.toString(), temp.toString());
        String token = turn.getToken();
        assertNotNull(token);
        assertFalse(token.trim().isEmpty());
        assertEquals(token, turn.getToken());
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, "session", null));
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, "session", " "));
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, "session", "wrong"));
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, "session", token));
        turn.getImages().bindRunId("run");
        ONode result = ONode.ofJson(talent.publish_image("a.png", "说明", "session", token));
        assertTrue(result.get("registered").getBoolean());
        assertEquals("a.png", result.get("filename").getString());
        PublishedImage image = turn.getImages().finish("run", true).get(0);
        assertEquals(result.get("uuid").getString(), image.getUuid());
        assertEquals("说明", image.getCaption());
        assertThrows(IOException.class, () -> talent.publish_image("a.png", null, "session", token));
    }

    @Test
    void lateToolFromOldRequestCannotRegisterInNewTurnOfSameSession() throws Exception {
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = InMemoryAgentSession.of("session");
        when(engine.getSession("session")).thenReturn(session);
        PublishImageTalent talent = new PublishImageTalent(engine);
        writeImage();
        ImageReplyTurn old = new ImageReplyTurn(session, temp.toString(), temp.toString());
        old.getImages().bindRunId("old-run");
        ImageReplyTurn current = new ImageReplyTurn(session, temp.toString(), temp.toString());
        current.getImages().bindRunId("new-run");
        assertNotEquals(old.getToken(), current.getToken());
        assertThrows(IOException.class,
                () -> talent.publish_image("a.png", null, "session", old.getToken()));
        assertTrue(current.getImages().finish("new-run", true).isEmpty());
        assertTrue(old.getImages().finish("old-run", true).isEmpty());
    }

    @Test
    void sessionStoresOnlyCurrentReplyTurnAndOwnsItsRegistry() throws Exception {
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = InMemoryAgentSession.of("session");
        when(engine.getSession("session")).thenReturn(session);
        PublishImageTalent talent = new PublishImageTalent(engine);
        writeImage();
        ImageReplyTurn old = new ImageReplyTurn(session, temp.toString(), temp.toString());
        ImageReplyTurn current = new ImageReplyTurn(session, temp.toString(), temp.toString());
        current.getImages().bindRunId("run");
        assertSame(current, session.attrs().get(ImageReplyTurn.ATTR));
        assertFalse(session.attrs().containsValue(current.getImages()));
        old.cleanup();
        assertSame(current, session.attrs().get(ImageReplyTurn.ATTR));
        assertThrows(IOException.class,
                () -> talent.publish_image("a.png", null, "session", old.getToken()));
        assertTrue(ONode.ofJson(talent.publish_image("a.png", null, "session", current.getToken()))
                .get("registered").getBoolean());
        assertEquals(1, current.getImages().finish("run", true).size());
        current.cleanup();
        assertThrows(IOException.class,
                () -> talent.publish_image("a.png", null, "session", current.getToken()));
    }

    @Test
    void cleanupCompletesDuringPreparationAndRejectsLateCommit() throws Exception {
        assertClosingDuringPreparationRejectsLateCommit(true);
    }

    @Test
    void discardCompletesDuringPreparationAndRejectsLateCommit() throws Exception {
        assertClosingDuringPreparationRejectsLateCommit(false);
    }

    private void assertClosingDuringPreparationRejectsLateCommit(boolean cleanup) throws Exception {
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = InMemoryAgentSession.of("session");
        when(engine.getSession("session")).thenReturn(session);
        writeImage();
        ImageReplyTurn turn = new ImageReplyTurn(session, temp.toString(), temp.toString());
        turn.getImages().bindRunId("run");
        PausingPublishImageTalent talent = new PausingPublishImageTalent(engine, session);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> publishing = executor.submit(
                    () -> talent.publish_image("a.png", null, "session", turn.getToken()));
            assertTrue(talent.entered.await(5, TimeUnit.SECONDS), "图片准备应到达暂停点");
            Future<?> closing = executor.submit(() -> {
                if (cleanup) {
                    turn.cleanup();
                } else {
                    turn.discardImages();
                }
            });
            // 必须在准备线程仍被暂停时完成，不能依赖释放 latch 才获得锁。
            closing.get(5, TimeUnit.SECONDS);
            if (cleanup) {
                assertNull(session.attrs().get(ImageReplyTurn.ATTR));
                assertFalse(session.attrs().containsValue(turn.getImages()));
            } else {
                assertSame(turn, session.attrs().get(ImageReplyTurn.ATTR));
                assertFalse(session.attrs().containsValue(turn.getImages()));
            }
            talent.release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> publishing.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertTrue(turn.getImages().finish("run", true).isEmpty());
        } finally {
            talent.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发测试线程应已结束");
        }
    }

    @Test
    void replacingTurnDuringPreparationRejectsLateCommitWithoutPollutingNewTurn() throws Exception {
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = InMemoryAgentSession.of("session");
        when(engine.getSession("session")).thenReturn(session);
        writeImage();
        ImageReplyTurn old = new ImageReplyTurn(session, temp.toString(), temp.toString());
        old.getImages().bindRunId("old-run");
        PausingPublishImageTalent talent = new PausingPublishImageTalent(engine, session);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> publishing = executor.submit(
                    () -> talent.publish_image("a.png", "旧轮图片", "session", old.getToken()));
            assertTrue(talent.entered.await(5, TimeUnit.SECONDS), "图片准备应到达暂停点");
            Future<ImageReplyTurn> replacing = executor.submit(() -> {
                ImageReplyTurn current = new ImageReplyTurn(session, temp.toString(), temp.toString());
                current.getImages().bindRunId("new-run");
                return current;
            });
            ImageReplyTurn current = replacing.get(5, TimeUnit.SECONDS);
            assertNotEquals(old.getToken(), current.getToken());
            // 新轮在旧轮准备尚未返回时仍可正常登记。
            ONode registered = ONode.ofJson(new PublishImageTalent(engine)
                    .publish_image("a.png", "新轮图片", "session", current.getToken()));
            assertTrue(registered.get("registered").getBoolean());
            talent.release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> publishing.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertSame(current, session.attrs().get(ImageReplyTurn.ATTR));
            assertFalse(session.attrs().containsValue(current.getImages()));
            assertTrue(old.getImages().finish("old-run", true).isEmpty());
            java.util.List<PublishedImage> images = current.getImages().finish("new-run", true);
            assertEquals(1, images.size());
            assertEquals(registered.get("uuid").getString(), images.get(0).getUuid());
            assertEquals("新轮图片", images.get(0).getCaption());
        } finally {
            talent.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发测试线程应已结束");
        }
    }

    private static class PausingPublishImageTalent extends PublishImageTalent {
        private final AgentSession session;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private PausingPublishImageTalent(HarnessEngine engine, AgentSession session) {
            super(engine);
            this.session = session;
        }

        @Override
        PublishedImage prepare(ImagePublishTurn images, String path, String caption) throws IOException {
            assertFalse(Thread.holdsLock(session.attrs()), "图片准备不能持有会话锁");
            assertFalse(Thread.holdsLock(images), "图片准备不能持有登记簿锁");
            PublishedImage image = super.prepare(images, path, caption);
            // 已得到合法图片，再暂停返回，确保关闭/换轮后验证的是迟到提交。
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("图片准备等待被中断", e);
            }
            return image;
        }
    }

    @Test
    void actualToolSchemaExposesOnlyPathAndCaption() {
        FunctionTool tool = mappedTool(new PublishImageTalent(mock(HarnessEngine.class)));
        assertEquals("publish_image", tool.name());
        ONode schema = ONode.ofJson(tool.inputSchema());
        assertEquals(2, schema.get("properties").getObject().size());
        assertTrue(schema.get("properties").hasKey("path"));
        assertTrue(schema.get("properties").hasKey("caption"));
        assertFalse(schema.get("properties").hasKey("__sessionId"));
        assertFalse(schema.get("properties").hasKey(PublishImageTalent.CONTEXT_KEY));
        assertEquals(1, schema.get("required").getArray().size());
        assertEquals("path", schema.get("required").get(0).getString());
    }

    @Test
    void actualToolCallInjectsContextAndOverridesForgedModelArguments() throws Throwable {
        HarnessEngine engine = mock(HarnessEngine.class);
        AgentSession session = InMemoryAgentSession.of("session");
        when(engine.getSession("session")).thenReturn(session);
        writeImage();
        ImageReplyTurn old = new ImageReplyTurn(session, temp.toString(), temp.toString());
        ImageReplyTurn current = new ImageReplyTurn(session, temp.toString(), temp.toString());
        current.getImages().bindRunId("run");
        FunctionTool tool = mappedTool(new PublishImageTalent(engine));
        Map<String, Object> args = new HashMap<>();
        args.put("path", "a.png");
        // 模型试图伪造当前 token，但请求上下文仍是迟到的旧请求。
        args.put("__sessionId", "forged-session");
        args.put(PublishImageTalent.CONTEXT_KEY, current.getToken());
        Map<String, Object> context = new HashMap<>();
        context.put("__sessionId", "session");
        context.put(PublishImageTalent.CONTEXT_KEY, old.getToken());
        ToolRequest stale = new ToolRequest(null, context, args);
        assertEquals(old.getToken(), stale.getArgs().get(PublishImageTalent.CONTEXT_KEY));
        assertThrows(Throwable.class, () -> new ToolChain<>(Collections.emptyList(), tool).doIntercept(stale));
        assertTrue(current.getImages().finish("run", true).isEmpty());

        ImageReplyTurn next = new ImageReplyTurn(session, temp.toString(), temp.toString());
        next.getImages().bindRunId("next-run");
        context.put(PublishImageTalent.CONTEXT_KEY, next.getToken());
        args.put(PublishImageTalent.CONTEXT_KEY, "forged-token");
        ToolRequest trusted = new ToolRequest(null, context, args);
        new ToolChain<>(Collections.emptyList(), tool).doIntercept(trusted);
        assertEquals(1, next.getImages().finish("next-run", true).size());
        verify(engine, never()).getSession("forged-session");
    }

    private FunctionTool mappedTool(PublishImageTalent talent) {
        return new MethodToolProvider(talent).includeProvide(false).getTools().iterator().next();
    }

    private void writeImage() throws IOException {
        BufferedImage pixels = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try {
            assertTrue(ImageIO.write(pixels, "png", temp.resolve("a.png").toFile()));
        } finally {
            pixels.flush();
        }
    }

    @Test
    void registersOnlyOnMainAgent() {
        HarnessEngine engine = mock(HarnessEngine.class);
        ReActAgent.Builder builder = mock(ReActAgent.Builder.class);
        PublishImageExtension extension = new PublishImageExtension();
        extension.configure(engine, "explore", builder);
        extension.configure(engine, "general", builder);
        verifyNoInteractions(builder);
        extension.configure(engine, AgentDefinition.AGENT_MAIN, builder);
        verify(builder).defaultTalentAdd(any(PublishImageTalent.class));
        verifyNoMoreInteractions(builder);
    }
}
