package org.noear.solon.codecli.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.codecli.channel.ChannelReply;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageReplyTurnTest {
    @TempDir
    Path temp;

    private static final String MARKDOWN = "正文\n![说明](a.png)";

    private ImageReplyTurn turn(AgentSession session, String runId) {
        ImageReplyTurn turn = new ImageReplyTurn(session, temp.toString(), temp.toString());
        bind(turn, session, runId);
        return turn;
    }

    private void bind(ImageReplyTurn turn, AgentSession session, String runId) {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getSession()).thenReturn(session);
        when(trace.getRunId()).thenReturn(runId);
        turn.onReasonStart(trace, new StringBuilder());
    }

    @Test
    void bindsOnlyRealSessionAndFirstNonBlankRunId() {
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = new ImageReplyTurn(session, temp.toString(), temp.toString());
        assertFalse(turn.canClaim("run"));
        assertFalse(turn.claim(null));
        bind(turn, InMemoryAgentSession.of("session"), "forged");
        assertFalse(turn.canClaim("forged"));
        bind(turn, null, "missing-session");
        assertFalse(turn.canClaim("missing-session"));
        bind(turn, session, null);
        bind(turn, session, " ");
        assertFalse(turn.canClaim(" "));
        bind(turn, session, "run");
        bind(turn, session, "other");
        assertTrue(turn.canClaim("run"));
        assertFalse(turn.canClaim("other"));
        assertFalse(turn.claim("other"));
        assertTrue(turn.claim("run"));
    }

    @Test
    void preparesNormalImageBeforeClaimAndKeepsPermissionAfterClaim() throws Exception {
        writeImage(temp);
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = turn(session, "run");
        ChannelReply reply = turn.prepare(MARKDOWN, "run");
        assertTrue(reply.isFinal());
        assertEquals("run", reply.getRunId());
        assertEquals(1, reply.getImages().size());
        assertEquals("a.png", reply.getImages().get(0).getFilename());
        assertTrue(turn.canClaim("run"), "准备不是领取终态");
        assertTrue(turn.claim("run"));
        assertTrue(turn.isImagesAllowed(), "正常领取后不得丢弃已准备的图片");
        assertFalse(turn.canClaim("run"));
        assertFalse(turn.claim("run"));
        assertTrue(turn.prepare(MARKDOWN, "run").getImages().isEmpty(), "不可领取时重新准备必须禁图");
        turn.cleanup();
        assertFalse(turn.isImagesAllowed());
    }

    @Test
    void prepareUsesSavedRootAndRelativeCwd() throws Exception {
        Path cwd = Files.createDirectory(temp.resolve("nested"));
        writeImage(cwd);
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = new ImageReplyTurn(session, temp.toString(), "nested");
        bind(turn, session, "run");
        assertEquals(1, turn.prepare(MARKDOWN, "run").getImages().size());
    }

    @Test
    void unboundAndWrongRunCannotPrepareImagesOrClaim() throws Exception {
        writeImage(temp);
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = new ImageReplyTurn(session, temp.toString(), temp.toString());
        assertTrue(turn.prepare(MARKDOWN, null).getImages().isEmpty());
        assertTrue(turn.prepare(MARKDOWN, "run").getImages().isEmpty());
        bind(turn, session, "run");
        assertTrue(turn.prepare(MARKDOWN, "wrong").getImages().isEmpty());
        assertFalse(turn.claim("wrong"));
        assertTrue(turn.canClaim("run"));
    }

    @Test
    void hitlDisablesImagesButAllowsSafeTextFallback() throws Exception {
        writeImage(temp);
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = turn(session, "run");
        turn.discardImages();
        turn.discardImages();
        assertFalse(turn.isImagesAllowed());
        assertTrue(turn.canClaim("run"));
        ChannelReply fallback = turn.prepare("安全提示", "run");
        assertEquals("安全提示", fallback.getText());
        assertTrue(fallback.getImages().isEmpty());
        assertTrue(turn.prepare(MARKDOWN, "run").getImages().isEmpty());
        bind(turn, session, "run");
        assertFalse(turn.isImagesAllowed(), "后续 ReasonStart 不得恢复 HITL 图片许可");
        assertTrue(turn.claim("run"));
        assertFalse(turn.isImagesAllowed());
    }

    @Test
    void cancellationAndCleanupRejectLateRunEndAndCannotRebind() throws Exception {
        writeImage(temp);
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = turn(session, "run");
        turn.closeTerminal();
        bind(turn, session, "late");
        assertTrue(turn.isCurrent());
        assertFalse(turn.canClaim("run"));
        assertFalse(turn.claim("run"));
        assertFalse(turn.claim("late"));
        assertFalse(turn.isImagesAllowed());
        assertTrue(turn.prepare(MARKDOWN, "run").getImages().isEmpty());
        turn.cleanup();
        turn.cleanup();
        assertNull(session.attrs().get(ImageReplyTurn.ATTR));
        assertFalse(turn.isCurrent());
        assertFalse(turn.isImagesAllowed());
    }

    @Test
    void oldTurnCannotClaimPrepareOrCleanNewTurn() throws Exception {
        writeImage(temp);
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn old = turn(session, "old");
        session.attrs().put(ImageReplyTurn.ATTR_IM_TERMINAL_SENT, true);
        ImageReplyTurn current = turn(session, "new");
        assertNull(session.attrs().get(ImageReplyTurn.ATTR_IM_TERMINAL_SENT));
        assertSame(current, session.attrs().get(ImageReplyTurn.ATTR));
        assertFalse(old.isCurrent());
        assertFalse(old.isImagesAllowed());
        assertFalse(old.canClaim("old"));
        assertFalse(old.claim("old"));
        assertTrue(old.prepare(MARKDOWN, "old").getImages().isEmpty());
        bind(old, session, "new");
        old.clearRoute();
        old.cleanup();
        assertSame(current, session.attrs().get(ImageReplyTurn.ATTR));
        assertTrue(current.isImagesAllowed());
        assertEquals(1, current.prepare(MARKDOWN, "new").getImages().size());
        assertTrue(current.claim("new"));
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = turn(session, "run");
        assertTrue(turn.canClaim("run"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> { start.await(); return turn.claim("run"); });
            Future<Boolean> second = executor.submit(() -> { start.await(); return turn.claim("run"); });
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertTrue(turn.isImagesAllowed());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void routeIsImmutableSnapshotAndClearUsesIdentity() {
        AgentSession session = InMemoryAgentSession.of("session");
        Map<String, String> original = new HashMap<>();
        original.put("channel", "feishu");
        session.attrs().put("session.replyRoute", original);
        ImageReplyTurn turn = turn(session, "run");
        original.put("channel", "changed");
        assertEquals("feishu", turn.getRoute().get("channel"));
        assertThrows(UnsupportedOperationException.class, () -> turn.getRoute().put("x", "y"));
        Map<String, String> replacement = new HashMap<>(original);
        session.attrs().put("session.replyRoute", replacement);
        turn.clearRoute();
        assertSame(replacement, session.attrs().get("session.replyRoute"));
        turn.cleanup();
        assertSame(replacement, session.attrs().get("session.replyRoute"));
    }

    @Test
    void oldCleanupDoesNotClearRouteReusedByCurrentTurn() {
        AgentSession session = InMemoryAgentSession.of("session");
        Map<String, String> route = new HashMap<>();
        session.attrs().put("session.replyRoute", route);
        ImageReplyTurn old = turn(session, "old");
        ImageReplyTurn current = turn(session, "new");
        old.clearRoute();
        old.cleanup();
        assertSame(route, session.attrs().get("session.replyRoute"));
        current.clearRoute();
        assertNull(session.attrs().get("session.replyRoute"));
        assertTrue(current.getRoute().isEmpty());
        current.cleanup();
    }

    @Test
    void missingRouteUsesEmptySnapshot() {
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn turn = turn(session, "run");
        assertTrue(turn.getRoute().isEmpty());
        turn.cleanup();
        assertNull(session.attrs().get(ImageReplyTurn.ATTR));
    }

    @Test
    void cleanupCanFinishDuringImagePreparation() throws Exception {
        assertClosingDuringPreparation("cleanup");
    }

    @Test
    void hitlCanDisableImagesDuringPreparation() throws Exception {
        assertClosingDuringPreparation("discard");
    }

    @Test
    void cancellationCanFinishDuringImagePreparation() throws Exception {
        assertClosingDuringPreparation("close");
    }

    @Test
    void replacementCanFinishDuringImagePreparation() throws Exception {
        assertClosingDuringPreparation("replace");
    }

    private void assertClosingDuringPreparation(String action) throws Exception {
        writeImage(temp);
        AgentSession session = InMemoryAgentSession.of("session");
        ImageReplyTurn old = turn(session, "old");
        IIORegistry registry = IIORegistry.getDefaultInstance();
        ImageReader standard = ImageIO.getImageReadersByFormatName("png").next();
        PausingPngProvider provider = new PausingPngProvider(standard.getOriginatingProvider(), session);
        standard.dispose();
        List<ImageReaderSpi> existing = new ArrayList<>();
        registry.getServiceProviders(ImageReaderSpi.class, true).forEachRemaining(existing::add);
        registry.registerServiceProvider(provider);
        for (ImageReaderSpi spi : existing) {
            registry.setOrdering(ImageReaderSpi.class, provider, spi);
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ChannelReply> preparing = executor.submit(() -> old.prepare(MARKDOWN, "old"));
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS), "必须进入真实图片解码");
            Future<ImageReplyTurn> closing = executor.submit(() -> {
                if ("cleanup".equals(action)) old.cleanup();
                else if ("discard".equals(action)) old.discardImages();
                else if ("close".equals(action)) old.closeTerminal();
                else return turn(session, "new");
                return null;
            });
            // 准备线程仍暂停时，关闭或换轮就应完成。
            ImageReplyTurn current = closing.get(5, TimeUnit.SECONDS);
            assertFalse(old.isImagesAllowed());
            provider.release.countDown();
            preparing.get(5, TimeUnit.SECONDS);
            // prepare 是快照，不承担提交；发送方必须 claim 并重新检查图片许可。
            if ("discard".equals(action)) {
                assertTrue(old.claim("old"));
            } else {
                assertFalse(old.claim("old"));
            }
            assertFalse(old.isImagesAllowed());
            if (current != null) {
                old.cleanup();
                assertSame(current, session.attrs().get(ImageReplyTurn.ATTR));
                assertTrue(current.canClaim("new"));
                assertTrue(current.isImagesAllowed());
            }
        } finally {
            provider.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            registry.deregisterServiceProvider(provider);
        }
    }

    /** 复用标准 PNG 解码器，在真实读图阶段暂停，无需生产代码测试钩子。 */
    private static final class PausingPngProvider extends ImageReaderSpi {
        private final ImageReaderSpi delegate;
        private final AgentSession session;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        PausingPngProvider(ImageReaderSpi delegate, AgentSession session) {
            super("test", "1", new String[]{"png"}, new String[]{"png"}, new String[]{"image/png"},
                    PausingReader.class.getName(), new Class<?>[]{ImageInputStream.class}, null,
                    false, null, null, null, null, false, null, null, null, null);
            this.delegate = delegate;
            this.session = session;
        }

        @Override
        public boolean canDecodeInput(Object source) throws IOException { return delegate.canDecodeInput(source); }
        @Override
        public ImageReader createReaderInstance(Object extension) throws IOException {
            return new PausingReader(this, delegate.createReaderInstance());
        }
        @Override
        public String getDescription(java.util.Locale locale) { return "暂停 PNG 解码"; }
    }

    private static final class PausingReader extends ImageReader {
        private final PausingPngProvider provider;
        private final ImageReader delegate;

        PausingReader(PausingPngProvider provider, ImageReader delegate) {
            super(provider);
            this.provider = provider;
            this.delegate = delegate;
        }

        @Override
        public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
            super.setInput(input, seekForwardOnly, ignoreMetadata);
            delegate.setInput(input, seekForwardOnly, ignoreMetadata);
        }
        @Override
        public int getWidth(int index) throws IOException {
            assertFalse(Thread.holdsLock(provider.session.attrs()), "图片解码不得持有会话锁");
            provider.entered.countDown();
            try {
                if (!provider.release.await(10, TimeUnit.SECONDS)) throw new IOException("等待关闭操作超时");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return delegate.getWidth(index);
        }
        @Override
        public int getHeight(int index) throws IOException { return delegate.getHeight(index); }
        @Override
        public int getNumImages(boolean search) throws IOException { return delegate.getNumImages(search); }
        @Override
        public Iterator<ImageTypeSpecifier> getImageTypes(int index) throws IOException { return delegate.getImageTypes(index); }
        @Override
        public IIOMetadata getStreamMetadata() throws IOException { return delegate.getStreamMetadata(); }
        @Override
        public IIOMetadata getImageMetadata(int index) throws IOException { return delegate.getImageMetadata(index); }
        @Override
        public BufferedImage read(int index, ImageReadParam param) throws IOException { return delegate.read(index, param); }
        @Override
        public void dispose() { delegate.dispose(); super.dispose(); }
    }

    private void writeImage(Path directory) throws IOException {
        // macOS 临时目录可能经过链接；使用真实工作区根路径。
        temp = temp.toRealPath();
        BufferedImage pixels = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try {
            assertTrue(ImageIO.write(pixels, "png", directory.resolve("a.png").toFile()));
        } finally {
            pixels.flush();
        }
    }
}
