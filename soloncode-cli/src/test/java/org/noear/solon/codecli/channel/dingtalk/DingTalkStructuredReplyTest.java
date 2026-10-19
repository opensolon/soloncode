package org.noear.solon.codecli.channel.dingtalk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.api.web.WebStreamBuilder;
import org.noear.solon.codecli.api.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.artifact.ImageReplyTurn;

import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.workspace.WorkspaceContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.ChannelReply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 Markdown 图片快照验证钉钉适配，不启动连接、不请求外网。 */
class DingTalkStructuredReplyTest {
    @TempDir
    Path workspace;
    @Test
    void imagesStayBetweenSurroundingTextOnCapturedBinding() {
        RecordingLink link = new RecordingLink();
        link.changeBinding = true;
        PublishedImage one = image("one");
        PublishedImage two = image("two");
        ChannelReply reply = reply("前![说明](one)中![第二](two)后", one, two);
        link.sendReply("session", reply, "source", "target", "message");
        assertEquals(Arrays.asList("upload:image.png", "upload:image.png",
                "text:前![说明](media_1)中![第二](media_2)后"), link.events);
        assertEquals(1, link.bindingReads);
        assertEquals(1, link.tokenCalls);
        for (String route : link.routes) assertEquals("app|secret|robot|target", route);
        assertArrayEquals(two.getBytes(), link.uploadedBytes);
        assertEquals("前![说明](one)中![第二](two)后", reply.getText());
        assertEquals("说明", reply.getImages().get(0).getCaption());
    }

    @Test
    void uploadFilenameUsesByteMagicInsteadOfUserFilename() {
        byte[][] payloads = { imageBytes("jpeg"), imageBytes("png") };
        for (int i = 0; i < payloads.length; i++) {
            RecordingLink link = new RecordingLink();
            String path = i == 0 ? "private/user.png" : "private/user.jpg";
            PublishedImage image = new PublishedImage(payloads[i], path, null);
            link.sendReply("s", reply("![图](" + path + ")", image), null, null, null);
            assertEquals(Arrays.asList(i == 0 ? "upload:image.jpg" : "upload:image.png",
                    "text:![图](media_1)"), link.events);
            assertArrayEquals(payloads[i], link.uploadedBytes);
        }
    }

    @Test
    void disabledImagesDoNotUploadAndKeepFallbackInPlace() throws IOException {
        Path root = workspace.toRealPath();
        Files.write(root.resolve("private.png"), imageBytes("png"));
        ChannelReply reply = ChannelReply.fromMarkdown("前![图](private.png)后", true, "run",
                root.toString(), root.toString(), false);
        RecordingLink link = new RecordingLink();
        link.sendReply("s", reply, null, null, null);
        assertTrue(reply.hasImageReferences());
        assertTrue(reply.getImages().isEmpty());
        assertEquals(Collections.singletonList("text:前（图片不可用：图；图片发送已禁用）后"), link.events);
        assertEquals(0, link.tokenCalls);
        assertEquals(0, link.uploadCalls);
    }

    @Test
    void externalHttpsStaysInPlaceWhileHttpUsesSafePlaceholder() {
        RecordingLink link = new RecordingLink();
        String text = "前![安全](https://example.com/image.png)中![普通](http://example.com/image.png)后";
        ChannelReply reply = reply(text);
        assertEquals(2, reply.getImageReferences().size());
        assertTrue(reply.getImages().isEmpty());
        link.sendReply("s", reply, null, null, null);
        assertEquals(Collections.singletonList("text:前![安全](https://example.com/image.png)"
                + "中（图片不可用：普通；当前渠道未启用外链图片）后"), link.events);
        assertEquals("前（图片不可用：安全；当前渠道未启用外链图片）"
                + "中（图片不可用：普通；当前渠道未启用外链图片）后", link.fallback);
        assertEquals(0, link.uploadCalls);
        assertEquals(0, link.tokenCalls);
        assertEquals(text, reply.getText());
    }

    @Test
    void unsafeExternalUrlsNeverReachMarkdownOrFallback() {
        for (String url : Arrays.asList("https://user:pass@example.com/image.png",
                "https://example.com/image.png?token=secret123",
                "https://example.com/image.png#secret123",
                "https:///image.png", "https://exa_mple.com/image.png")) {
            RecordingLink link = new RecordingLink();
            ChannelReply reply = reply("前![图](" + url + ")后");
            assertEquals(1, reply.getImageReferences().size(), url);
            assertNull(reply.getImageReferences().get(0).getExternalUrl(), url);
            assertTrue(reply.getImages().isEmpty(), url);
            link.sendReply("s", reply, null, null, null);
            String safe = "前（图片不可用：图；外链不安全）后";
            assertEquals(Collections.singletonList("text:" + safe), link.events, url);
            assertEquals(safe, link.fallback, url);
            assertEquals(0, link.uploadCalls, url);
            assertEquals(0, link.tokenCalls, url);
        }
    }

    @Test
    void invalidImageFallsBackWithoutUploadingOrLeakingItsPath() {
        RecordingLink link = new RecordingLink();
        ChannelReply reply = reply("前![坏图](private/broken.png)后",
                new PublishedImage(new byte[]{1, 2, 3}, "broken.png", null));
        link.sendReply("s", reply, null, null, null);
        assertEquals(Collections.singletonList("text:前（图片不可用：坏图；格式不支持或图片损坏）后"), link.events);
        assertEquals(0, link.uploadCalls);
        assertEquals(0, link.tokenCalls);
        assertSafe(link.fallback);
    }

    @Test
    void pureImageUsesMarkdownAndPreservesRouteFallbacks() {
        RecordingLink source = new RecordingLink();
        source.sendReply("session", reply("![图](one)", image("one")), "source", "", null);
        assertEquals(Arrays.asList("upload:image.png", "text:![图](media_1)"), source.events);
        assertEquals("app|secret|robot|source", source.routes.get(0));
        RecordingLink bound = new RecordingLink();
        bound.sendReply("session", reply("![图](one)", image("one")), null, null, null);
        assertEquals("app|secret|robot|bound-user", bound.routes.get(0));
    }

    @Test
    void noReferencesDelegateToLegacyWithAllArgumentsEvenWhenInterrupted() {
        RecordingLink link = new RecordingLink() {
            @Override
            public void sendReply(String sessionId, String text, boolean isFinal,
                                  String source, String target, String message) {
                assertEquals("session", sessionId);
                assertEquals("answer", text);
                assertFalse(isFinal);
                assertEquals("source", source);
                assertEquals("target", target);
                assertEquals("message", message);
                events.add("legacy");
            }
        };
        try {
            Thread.currentThread().interrupt();
            link.sendReply("session", ChannelReply.fromMarkdown("answer", false, "run",
                    null, null), "source", "target", "message");
            assertEquals(Collections.singletonList("legacy"), link.events);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, link.bindingReads);
            assertEquals(0, link.tokenCalls);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void repeatedImageUploadsOnceAndEachReferenceKeepsItsAlt() {
        RecordingLink link = new RecordingLink();
        PublishedImage one = image("one");
        link.sendReply("s", reply("前![一](one)中![二](one)后", one, one), null, null, null);
        assertEquals(Arrays.asList("upload:image.png",
                "text:前![一](media_1)中![二](media_1)后"), link.events);
    }

    @Test
    void failedUploadIsCachedAndReplacedAtEveryOriginalPosition() {
        RecordingLink link = new RecordingLink();
        link.failUploadAt = 1;
        PublishedImage one = image("one");
        ChannelReply reply = reply("前![一](one)中![二](one)后![三](two)", one, one, image("two"));
        link.sendReply("s", reply, null, null, null);
        assertEquals(3, link.events.size());
        assertEquals("upload:image.png", link.events.get(0));
        assertEquals("upload:image.png", link.events.get(1));
        assertEquals("text:前（图片不可用：一；上传失败或渠道不支持）中（图片不可用：二；上传失败或渠道不支持）后![三](media_2)", link.events.get(2));
        assertSafe(link.fallback);
        assertFalse(link.events.toString().contains("private-error"));
    }

    @Test
    void secondUploadFailureDoesNotReplaceFirstImage() {
        RecordingLink link = new RecordingLink();
        link.failUploadAt = 2;
        link.sendReply("s", reply("前![一](one)中![二](two)后", image("one"), image("two")),
                null, null, null);
        assertEquals(Arrays.asList("upload:image.png", "upload:image.png",
                "text:前![一](media_1)中（图片不可用：二；上传失败或渠道不支持）后"), link.events);
        assertEquals(2, link.uploadCalls);
        assertSafe(link.fallback);
    }

    @Test
    void missingImageStillUsesInlineFallbackInsteadOfLegacyRawPath() {
        RecordingLink link = new RecordingLink();
        ChannelReply reply = reply("前![失败](private/path.png)后", (PublishedImage) null);
        link.sendReply("s", reply, null, null, null);
        assertEquals(Collections.singletonList("text:前（图片不可用：失败；读取失败或目标不安全）后"), link.events);
        assertEquals(0, link.tokenCalls);
        assertSafe(link.fallback);
    }

    @Test
    void tokenFailureIsAttemptedOnceAndAllReferencesFallback() {
        for (String mode : Arrays.asList("null", "empty", "throw")) {
            RecordingLink link = new RecordingLink();
            link.tokenMode = mode;
            ChannelReply reply = reply("前![一](one)中![二](two)后", image("one"), image("two"));
            link.sendReply("s", reply, null, null, null);
            assertEquals(1, link.tokenCalls);
            assertEquals(Collections.singletonList("text:前（图片不可用：一；上传失败或渠道不支持）中（图片不可用：二；上传失败或渠道不支持）后"), link.events);
        }
    }

    @Test
    void firstFiveUniqueImagesOnlyAndOneMiBProductLimitIsInclusive() {
        RecordingLink link = new RecordingLink();
        PublishedImage exact = new PublishedImage(Arrays.copyOf(imageBytes("png"), 1024 * 1024), "exact.png", null);
        PublishedImage large = new PublishedImage(Arrays.copyOf(imageBytes("png"), 1024 * 1024 + 1), "large.png", null);
        ChannelReply reply = reply("![1](a)![2](b)![3](c)![4](d)![5](e)![6](f)",
                exact, large, image("three"), image("four"), image("five"), image("six"));
        link.sendReply("s", reply, null, null, null);
        assertEquals(Arrays.asList("upload:image.png", "upload:image.png", "upload:image.png", "upload:image.png",
                "text:![1](media_1)（图片不可用：2；上传失败或渠道不支持）![3](media_2)![4](media_3)![5](media_4)（图片不可用：6；超过图片限制）"), link.events);
    }

    @Test
    void repeatedReferencesDoNotConsumeFiveImageBudget() {
        RecordingLink link = new RecordingLink();
        PublishedImage one = image("one");
        link.sendReply("s", reply("![1](a)![2](a)![3](a)![4](a)![5](a)![6](a)",
                one, one, one, one, one, one), null, null, null);
        assertEquals(2, link.events.size());
        assertFalse(link.events.get(1).contains("图片不可用"));
    }

    @Test
    void nullReplyAndMissingRecipientHaveNoNetworkEffects() {
        RecordingLink link = new RecordingLink();
        link.sendReply("s", (ChannelReply) null, null, null, null);
        assertEquals(0, link.bindingReads);
        link.binding.userId = "";
        link.sendReply("s", reply("![图](one)", image("one")), null, null, null);
        assertEquals(0, link.tokenCalls);
        assertTrue(link.events.isEmpty());
    }

    @Test
    void interruptionBeforeTokenAfterTokenOrUploadStopsAndKeepsFlag() {
        for (String stage : Arrays.asList("before", "token", "upload", "uploadThrow", "text")) {
            RecordingLink link = new RecordingLink();
            link.interruptAt = stage;
            ChannelReply prepared = reply("前![一](one)后![二](two)", image("one"), image("two"));
            try {
                if ("before".equals(stage)) Thread.currentThread().interrupt();
                link.sendReply("s", prepared, null, null, null);
                assertTrue(Thread.currentThread().isInterrupted(), stage);
                if ("before".equals(stage)) {
                    assertEquals(0, link.bindingReads);
                    assertTrue(link.events.isEmpty());
                } else if ("token".equals(stage)) {
                    assertTrue(link.events.isEmpty());
                } else if (!"text".equals(stage)) {
                    assertEquals(Collections.singletonList("upload:image.png"), link.events);
                }
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void bodyRetryUsesSameUploadedTargetAndSafeNotificationTitle() {
        SendingLink link = new SendingLink();
        link.failures = 1;
        link.sendReply("s", reply("![图](one)", image("one")), null, null, null);
        assertEquals(1, Collections.frequency(link.events, "upload:image.png"));
        assertEquals(Arrays.asList("![图](media_1)", "![图](media_1)"), link.markdown);
        assertEquals(Arrays.asList("SolonCode", "SolonCode"), link.titles);
    }

    @Test
    void exhaustedBodyRetriesDegradeWithoutMediaIdOrLocalPath() {
        SendingLink link = new SendingLink();
        link.failures = 9; // 三轮外层重试，每片三次；随后仅发送安全替代正文。
        ChannelReply reply = reply("前![图](private/path.png)后", image("one"));
        link.sendReply("s", reply, null, null, null);
        assertEquals(1, Collections.frequency(link.events, "upload:image.png"));
        assertEquals(10, link.markdown.size());
        assertEquals("前（图片不可用：图；上传失败或渠道不支持）后", link.markdown.get(9));
        assertSafe(link.markdown.get(9));
    }

    @Test
    void exhaustedHttpsBodyRetriesSendOnlySafeFallbackThroughProductionPath() {
        SendingLink link = new SendingLink();
        link.failures = 9; // 单片正文三轮、每轮三次，最后发送单参数渲染的安全降级正文。
        String text = "前![图](https://example.com/image.png)后";
        link.sendReply("s", reply(text), null, null, null);
        assertEquals(0, link.uploadCalls);
        assertTrue(link.events.isEmpty());
        assertEquals(10, link.markdown.size());
        for (int i = 0; i < 9; i++) {
            assertEquals(text, link.markdown.get(i));
        }
        assertEquals("前（图片不可用：图；当前渠道未启用外链图片）后", link.markdown.get(9));
        assertFalse(link.markdown.get(9).contains("https://"));
        assertEquals(Collections.nCopies(10, "SolonCode"), link.titles);
    }

    @Test
    void interruptionDuringBodySendPreventsRetryOrFallback() {
        SendingLink link = new SendingLink();
        link.interruptSend = true;
        try {
            link.sendReply("s", reply("![图](one)", image("one")), null, null, null);
            assertEquals(1, link.markdown.size());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void httpsImageCrossing5000BoundaryIsNeverSplit() {
        SendingLink link = new SendingLink();
        String token = "![图](https://example.com/a.png)";
        String before = String.join("", Collections.nCopies(4990, "x"));
        link.sendReply("s", reply(before + token + "tail"), null, null, null);
        assertEquals(Arrays.asList(before, "(2) " + token + "tail"), link.markdown);
        assertEquals(0, link.uploadCalls);
    }

    @Test
    void oversizedHttpsNodeDegradesLocallyBeforeAnyBodyRetry() {
        SendingLink link = new SendingLink();
        String before = String.join("", Collections.nCopies(5000, "x"));
        String longNode = "![long](https://example.com/" + before + ")";
        String shortNode = "![ok](https://example.com/a.png)";
        ChannelReply prepared = reply(before + longNode + shortNode);
        link.sendReply("s", prepared, null, null, null);
        assertEquals(Arrays.asList(before, "(2) " + prepared.getImageReferences().get(0).getFallback() + shortNode),
                link.markdown);
        assertEquals(0, link.uploadCalls);
    }

    @Test
    void longPlatformImageCodeExampleDoesNotBecomeProtectedNode() {
        SendingLink link = new SendingLink();
        String example = "```markdown\n![demo](media_" + String.join("", Collections.nCopies(5100, "x")) + ")\n```\n";
        String token = "![图](https://example.com/a.png)";
        ChannelReply prepared = reply(example + token);
        assertEquals(1, prepared.getImageReferences().size());
        link.sendReply("s", prepared, null, null, null);
        StringBuilder restored = new StringBuilder();
        for (int i = 0; i < link.markdown.size(); i++) {
            String chunk = link.markdown.get(i);
            assertTrue(chunk.length() <= 5000);
            restored.append(chunk.substring(i == 0 ? 0 : ("(" + (i + 1) + ") ").length()));
        }
        assertEquals(example + token, restored.toString());
        assertEquals(1, link.markdown.stream().filter(chunk -> chunk.contains(token)).count());
    }

    @Test
    void markdownSnapshotUploadsOnceThroughRealRunEndPipeline(@TempDir Path temp) throws Exception {
        // RunEnd 准备最终 Markdown 时文件仍在，冻结字节后才删除文件。
        Path root = temp.toRealPath();
        Path file = root.resolve("image.png");
        BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try {
            assertTrue(ImageIO.write(pixels, "png", file.toFile()));
        } finally {
            pixels.flush();
        }
        byte[] expected = Files.readAllBytes(file);
        AgentSession session = InMemoryAgentSession.of("session");
        HarnessEngine engine = mock(HarnessEngine.class);
        WorkspaceContext context = mock(WorkspaceContext.class);
        ChannelHub hub = mock(ChannelHub.class);
        when(engine.getSession("session")).thenReturn(session);
        when(context.getEngine()).thenReturn(engine);
        when(context.getChannelHub()).thenReturn(hub);
        List<ChannelReply> delivered = new ArrayList<>();
        RecordingLink link = new RecordingLink() {
            @Override
            public boolean isBound(String sessionId) {
                return "session".equals(sessionId);
            }

            @Override
            public void sendReply(String sessionId, ChannelReply reply,
                                  String source, String target, String message) {
                assertFalse(Thread.holdsLock(session.attrs()));
                assertEquals("source", source);
                assertEquals("target", target);
                assertEquals("message", message);
                delivered.add(reply);
                super.sendReply(sessionId, reply, source, target, message);
            }
        };
        when(hub.getImLinks()).thenReturn(Collections.<Channel>singletonList(link));
        Map<String, String> route = new HashMap<>();
        route.put("source", "DingTalk");
        route.put("sourceUserId", "source");
        route.put("replyTarget", "target");
        route.put("messageId", "message");
        session.attrs().put("session.replyRoute", route);
        ImageReplyTurn turn = new ImageReplyTurn(session, root.toString(), root.toString());
        try {
            ReActTrace trace = mock(ReActTrace.class);
            when(trace.getSession()).thenReturn(session);
            when(trace.getRunId()).thenReturn("run");
            when(trace.getAgentName()).thenReturn("main");
            when(trace.getFinalAnswer()).thenReturn("完成前![图片说明](image.png)完成后");
            turn.onReasonStart(trace, new StringBuilder());
            assertTrue(turn.canClaim("run"));
            assertTrue(link.events.isEmpty());
            WebStreamBuilder builder = new WebStreamBuilder() {
                @Override
                protected ChannelReply prepareRunEndReply(ImageReplyTurn current, String text, String runId) {
                    assertFalse(Thread.holdsLock(session.attrs()));
                    assertTrue(Files.exists(file));
                    ChannelReply prepared = super.prepareRunEndReply(current, text, runId);
                    assertEquals(1, prepared.getImages().size());
                    assertArrayEquals(expected, prepared.getImages().get(0).getBytes());
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return prepared;
                }
            };
            WebEventMapper mapper = new WebEventMapper(builder, context, session, null, turn);
            RunEndEvent end = mock(RunEndEvent.class);
            when(end.getRunId()).thenReturn("run");
            when(end.getTrace()).thenReturn(trace);
            mapper.mapEvent(end);
            mapper.mapEvent(end);
            builder.replyCanceledToBoundChannels(context, session);
            assertEquals(1, delivered.size());
            ChannelReply sent = delivered.get(0);
            assertTrue(sent.isFinal());
            assertEquals("run", sent.getRunId());
            assertTrue(sent.hasImageReferences());
            assertFalse(Files.exists(file));
            assertEquals(1, sent.getImages().size());
            assertArrayEquals(expected, sent.getImages().get(0).getBytes());
            assertArrayEquals(expected, link.uploadedBytes);
            assertThrows(UnsupportedOperationException.class, () -> sent.getImages().clear());
            assertEquals(Arrays.asList("upload:image.png",
                    "text:完成前![图片说明](media_1)完成后"), link.events);
            assertEquals(1, link.tokenCalls);
        } finally {
            turn.cleanup();
        }
        assertFalse(session.attrs().containsKey(ImageReplyTurn.ATTR));
    }

    private static void assertSafe(String text) {
        assertFalse(text.contains("media_"));
        assertFalse(text.contains("private/"));
        assertFalse(text.contains("private-error"));
    }

    private ChannelReply reply(String text, PublishedImage... images) {
        // 这里只布置磁盘文件；引用识别、去重、预算与快照全部由生产工厂完成。
        try {
            Path root = workspace.toRealPath();
            int pos = 0;
            for (PublishedImage image : images) {
                int start = text.indexOf("![", pos);
                int labelEnd = text.indexOf("](", start);
                int end = text.indexOf(')', labelEnd);
                Path file = root.resolve(text.substring(labelEnd + 2, end));
                if (image != null) {
                    Files.createDirectories(file.getParent());
                    Files.write(file, image.getBytes());
                } else {
                    Files.deleteIfExists(file);
                }
                pos = end + 1;
            }
            return ChannelReply.fromMarkdown(text, true, "run", root.toString(), root.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PublishedImage image(String name) {
        return new PublishedImage(imageBytes("png"), name + ".png", "不得追加caption");
    }

    private static byte[] imageBytes(String format) {
        BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(pixels, format, out));
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            pixels.flush();
        }
    }

    private static class RecordingLink extends DingTalkLink {
        final DingTalkBinding binding = new DingTalkBinding();
        final List<String> events = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        int bindingReads;
        int tokenCalls;
        int uploadCalls;
        int failUploadAt;
        String tokenMode;
        String interruptAt;
        String fallback;
        boolean changeBinding;
        byte[] uploadedBytes;

        RecordingLink() {
            super("workspace", null);
            binding.userId = "bound-user";
            binding.appKey = "app";
            binding.appSecret = "secret";
            binding.robotCode = "robot";
        }

        @Override
        protected DingTalkBinding currentBinding(String sessionId) {
            bindingReads++;
            return binding;
        }

        @Override
        protected void sendReplyViaApi(DingTalkBinding snapshot, String text, String safeFallback,
                                       ChannelReply.RenderedImages nodes) {
            record(snapshot);
            fallback = safeFallback;
            events.add("text:" + text);
            if ("text".equals(interruptAt)) Thread.currentThread().interrupt();
        }

        @Override
        protected String getReplyAccessToken(DingTalkBinding snapshot) {
            record(snapshot);
            tokenCalls++;
            if (changeBinding) {
                binding.userId = "changed-user";
                binding.appKey = "changed-app";
                binding.appSecret = "changed-secret";
                binding.robotCode = "changed-robot";
            }
            if ("token".equals(interruptAt)) Thread.currentThread().interrupt();
            if ("throw".equals(tokenMode)) throw new IllegalStateException("private-error");
            if ("null".equals(tokenMode)) return null;
            if ("empty".equals(tokenMode)) return "";
            return "token";
        }

        @Override
        protected String uploadReplyImage(String token, byte[] bytes, String filename) {
            assertEquals("token", token);
            uploadCalls++;
            uploadedBytes = bytes;
            events.add("upload:" + filename);
            if ("upload".equals(interruptAt) || "uploadThrow".equals(interruptAt)) {
                Thread.currentThread().interrupt();
            }
            if (uploadCalls == failUploadAt || "uploadThrow".equals(interruptAt)) {
                throw new IllegalStateException("private-error");
            }
            return "media_" + uploadCalls;
        }

        private void record(DingTalkBinding snapshot) {
            routes.add(snapshot.appKey + "|" + snapshot.appSecret + "|" + snapshot.robotCode + "|" + snapshot.userId);
        }
    }

    private static class SendingLink extends RecordingLink {
        final List<String> markdown = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        int failures;
        boolean interruptSend;

        @Override
        protected void sendReplyViaApi(DingTalkBinding binding, String text, String fallback,
                                       ChannelReply.RenderedImages nodes) {
            // 调用生产分片与重试流程，而不是 RecordingLink 的记录实现。
            sendUsingProduction(binding, text, fallback, nodes);
        }

        private void sendUsingProduction(DingTalkBinding binding, String text, String fallback,
                                         ChannelReply.RenderedImages nodes) {
            new DingTalkLink("workspace", null) {
                @Override
                protected String getReplyAccessToken(DingTalkBinding snapshot) {
                    return SendingLink.this.getReplyAccessToken(snapshot);
                }

                @Override
                protected boolean sendReplyMarkdown(String token, DingTalkBinding snapshot, String title, String chunk) {
                    markdown.add(chunk);
                    titles.add(title);
                    if (interruptSend) Thread.currentThread().interrupt();
                    return !interruptSend && failures-- <= 0;
                }
            }.sendReplyViaApi(binding, text, fallback, nodes);
        }
    }
}
