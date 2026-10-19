package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.codecli.api.web.WebStreamBuilder;
import org.noear.solon.codecli.artifact.ImageReplyTurn;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.channel.ChannelReply;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.ai.harness.HarnessEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实请求级终态领取、最终 Markdown 准备与飞书异步发送的组合测试。 */
class FeishuRunEndReplyTest {
    @TempDir
    Path temp;

    @Test
    void realRunEndPreparesBeforeDeletionThenQueuesImmutableImageAndRoute() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.turn("run");
        byte[] expected = Files.readAllBytes(f.image);
        WebStreamBuilder builder = new WebStreamBuilder() {
            @Override
            protected ChannelReply prepareRunEndReply(ImageReplyTurn current, String text, String runId) {
                assertFalse(Thread.holdsLock(f.attrs));
                assertTrue(Files.exists(f.image), "最终 Markdown 准备时源文件必须存在");
                ChannelReply reply = super.prepareRunEndReply(current, text, runId);
                assertEquals(1, reply.getImages().size());
                assertEquals(2, reply.getImageReferences().size());
                assertSame(reply.getImageReferences().get(0).getImage(),
                        reply.getImageReferences().get(1).getImage());
                assertArrayEquals(expected, reply.getImages().get(0).getBytes());
                try {
                    Files.delete(f.image);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
                return reply;
            }
        };
        builder.replyRunEnd(f.context, f.session, turn, "wrong", f.markdown, false);
        assertTrue(f.link.tasks.isEmpty());
        builder.replyRunEnd(f.context, f.session, turn, "run", f.markdown, false);
        builder.replyRunEnd(f.context, f.session, turn, "run", "重复", false);
        assertFalse(Files.exists(f.image));
        assertTrue(f.link.events.isEmpty());
        assertEquals(1, f.link.tasks.size());
        assertFalse(f.attrs.containsKey("session.replyRoute"));
        f.link.binding.appSecret = "changed";
        f.link.binding.openId = "changed";
        turn.cleanup();
        f.link.flush();
        assertEquals(Arrays.asList("upload", "md:前😀![甲](img_key)中![乙](img_key)后"), f.link.events);
        assertArrayEquals(expected, f.link.bytes);
        assertEquals(Arrays.asList("secret|target", "secret|target"), f.link.routes);
        assertFalse(f.attrs.containsKey(ImageReplyTurn.ATTR));
    }

    @Test
    void hitlDuringPreparationRevokesSnapshotWithoutRereadingDeletedFile() throws Exception {
        Fixture f = new Fixture();
        ImageReplyTurn turn = f.turn("run");
        WebStreamBuilder builder = new WebStreamBuilder() {
            @Override
            protected ChannelReply prepareRunEndReply(ImageReplyTurn current, String text, String runId) {
                ChannelReply prepared = super.prepareRunEndReply(current, text, runId);
                assertEquals(1, prepared.getImages().size());
                current.discardImages();
                try {
                    Files.delete(f.image);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
                return prepared;
            }
        };
        builder.replyRunEnd(f.context, f.session, turn, "run", f.markdown, false);
        f.link.flush();
        assertEquals(Collections.singletonList("md:前😀（图片不可用：甲；图片发送已禁用）中"
                + "（图片不可用：乙；图片发送已禁用）后"), f.link.events);
        assertNull(f.link.bytes);
        turn.cleanup();
    }

    @Test
    void errorCancellationAndCleanupBlockLateSuccessfulRunEnd() throws Exception {
        for (String terminal : Arrays.asList("error", "cancel", "cleanup")) {
            Fixture f = new Fixture();
            ImageReplyTurn turn = f.turn("run");
            WebStreamBuilder builder = new WebStreamBuilder();
            if ("error".equals(terminal)) {
                builder.replyErrorToBoundChannels(f.context, f.session, new IOException("boom"));
            } else if ("cancel".equals(terminal)) {
                builder.replyCanceledToBoundChannels(f.context, f.session);
            } else {
                turn.cleanup();
            }
            builder.replyRunEnd(f.context, f.session, turn, "run", f.markdown, false);
            if ("cleanup".equals(terminal)) {
                assertTrue(f.link.tasks.isEmpty());
            } else {
                f.link.flush();
                assertEquals(Collections.singletonList("md:" + ("error".equals(terminal)
                        ? "任务执行失败：boom" : "用户已取消任务。")), f.link.events);
            }
            assertNull(f.link.bytes);
            turn.cleanup();
        }
    }

    private class Fixture {
        final Map<String, Object> attrs = new ConcurrentHashMap<>();
        final AgentSession session = mock(AgentSession.class);
        final WorkspaceContext context = mock(WorkspaceContext.class);
        final Link link = new Link();
        final Path root;
        final Path image;
        final String markdown = "前😀![甲](image.png)中![乙](./image.png)后";

        Fixture() throws IOException {
            root = Files.createTempDirectory(temp.toRealPath(), "turn-").toRealPath();
            image = root.resolve("image.png");
            Files.write(image, FeishuImageFixtures.image(1).getBytes());
            when(session.attrs()).thenReturn(attrs);
            when(session.getSessionId()).thenReturn("s");
            HarnessEngine engine = mock(HarnessEngine.class);
            when(context.getEngine()).thenReturn(engine);
            when(engine.getSession("s")).thenReturn(session);
            ChannelHub hub = mock(ChannelHub.class);
            when(context.getChannelHub()).thenReturn(hub);
            when(hub.getImLinks()).thenReturn(Collections.singletonList(link));
            Map<String, String> route = new HashMap<>();
            route.put("source", "feishu");
            route.put("sourceUserId", "source");
            route.put("replyTarget", "target");
            attrs.put("session.replyRoute", route);
        }

        ImageReplyTurn turn(String runId) {
            ImageReplyTurn turn = new ImageReplyTurn(session, root.toString(), "");
            assertFalse(turn.canClaim(runId));
            ReActTrace trace = mock(ReActTrace.class);
            when(trace.getSession()).thenReturn(session);
            when(trace.getRunId()).thenReturn(runId);
            turn.onReasonStart(trace, new StringBuilder());
            assertTrue(turn.canClaim(runId));
            return turn;
        }
    }

    private static class Link extends FeishuLink {
        final FeishuBinding binding = new FeishuBinding();
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        byte[] bytes;

        Link() {
            super("workspace", null);
            binding.appId = UUID.randomUUID().toString();
            binding.appSecret = "secret";
            binding.openId = "bound";
        }
        void flush() {
            assertEquals(1, tasks.size());
            tasks.get(0).run();
        }
        @Override public boolean isBound(String sessionId) { return "s".equals(sessionId); }
        @Override protected FeishuBinding currentBinding(String sessionId) { return binding; }
        @Override protected void submitReplyTask(Runnable task) { tasks.add(task); }
        @Override protected String uploadReplyImage(FeishuBinding snapshot, byte[] data, String filename) {
            routes.add(snapshot.appSecret + "|" + snapshot.openId);
            events.add("upload");
            assertEquals("image.png", filename);
            bytes = data.clone();
            return "img_key";
        }
        @Override protected String getReplyAccessToken(FeishuBinding snapshot) {
            routes.add(snapshot.appSecret + "|" + snapshot.openId);
            return "token";
        }
        @Override protected boolean sendReplyTextChunk(String token, String user, String text, int part) {
            events.add("md:" + text);
            assertEquals("target", user);
            return true;
        }
    }
}
