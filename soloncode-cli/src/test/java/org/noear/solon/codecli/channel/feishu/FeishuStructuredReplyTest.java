package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.ChannelReply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.noear.solon.codecli.channel.feishu.FeishuImageFixtures.*;

class FeishuStructuredReplyTest {
    @Test
    void imagesReplaceReferencesInPlaceWithoutAppendingCaptions() {
        RecordingLink link = new RecordingLink();
        PublishedImage one = image(1, "private/one.png", "不得追加的 caption");
        PublishedImage two = image(2, "private/two.png", "另一 caption");
        ChannelReply reply = inPlaceReply("前文 {{1}} 中间 **保留** {{2}} 后文", one, two);
        link.sendReply("s", reply, null, null, null);
        assertTrue(link.events.isEmpty());
        link.flush();
        assertEquals(Arrays.asList("upload:1:image.png", "upload:2:image.png",
                "md:前文 ![图1](img_1) 中间 **保留** ![图2](img_2) 后文"), link.events);
        assertArrayEquals(one.getBytes(), link.uploadedBytes.get(0));
        assertEquals(1, link.bindingReads);
    }

    @Test
    void pureImageUsesMarkdownRatherThanEmptyTextOrSeparateImageMessage() {
        RecordingLink link = new RecordingLink();
        link.sendReply("s", reply("", image(1)), null, null, null);
        link.flush();
        assertEquals(Arrays.asList("upload:1:image.png", "md:![图1](img_1)"), link.events);
    }

    @Test
    void duplicateReferencesUploadOncePerReplyButNotAcrossReplies() {
        RecordingLink link = new RecordingLink();
        PublishedImage image = image(1);
        ChannelReply reply = inPlaceReply("前 {{1}} 中 {{2}} 后", image, image);
        assertEquals(1, reply.getImages().size());
        assertSame(reply.getImageReferences().get(0).getImage(), reply.getImageReferences().get(1).getImage());
        link.sendReply("s", reply, null, null, null);
        link.sendReply("s", reply, null, null, null);
        link.flush();
        assertEquals(Arrays.asList("upload:1:image.png", "md:前 ![图1](img_1) 中 ![图2](img_1) 后",
                "upload:1:image.png", "md:前 ![图1](img_1) 中 ![图2](img_1) 后"), link.events);
    }

    @Test
    void failedUploadIsCachedForDuplicateReferencesAndOtherImagesStillRender() {
        RecordingLink link = new RecordingLink();
        link.failUpload = 1;
        PublishedImage failed = image(1);
        ChannelReply reply = inPlaceReply("前 {{1}} 中 {{2}} 再 {{3}} 后", failed, failed, image(2));
        link.sendReply("s", reply, null, null, null);
        link.flush();
        assertEquals(2, link.uploadedBytes.size());
        assertEquals("upload:1:image.png", link.events.get(0));
        assertEquals("upload:2:image.png", link.events.get(1));
        String expected = "前 （图片不可用：图1；上传失败或渠道不支持） 中 "
                + "（图片不可用：图2；上传失败或渠道不支持） 再 ![图3](img_2) 后";
        assertEquals("md:" + expected, link.events.get(2));
        assertFalse(link.events.get(2).contains("private"));
        assertFalse(link.events.get(2).contains("raw server error"));
    }

    @Test
    void unresolvedImageReferenceUsesSharedSafeFallbackWithoutUploading() {
        RecordingLink link = new RecordingLink();
        ChannelReply reply = reply("正文", (PublishedImage) null);
        link.sendReply("s", reply, null, null, null);
        link.flush();
        assertEquals(Collections.singletonList("md:" + reply.renderImages(img -> null)), link.events);
    }

    @Test
    void fiveUniqueImagesPerReplyIncludingFailedUploads() {
        RecordingLink link = new RecordingLink();
        link.failUpload = 2;
        ChannelReply reply = reply("正文", image(1), image(2), image(3), image(4), image(5), image(6));
        link.sendReply("s", reply, null, null, null);
        link.flush();
        assertEquals(5, link.uploadedBytes.size());
        assertEquals("md:" + reply.renderImages(img -> {
            int id = imageId(img.getBytes());
            return id == 2 || id == 6 ? null : "img_" + id;
        }), link.events.get(5));
    }

    @Test
    void jpegUsesSafeUploadNameInsteadOfOriginalPath() {
        RecordingLink link = new RecordingLink();
        PublishedImage jpeg = jpeg(1, "private/unsafe\r\n.jpg");
        link.sendReply("s", reply("", jpeg), null, null, null);
        link.flush();
        assertEquals(Arrays.asList("upload:1:image.jpg", "md:![图1](img_1)"), link.events);
        assertArrayEquals(jpeg.getBytes(), link.uploadedBytes.get(0));
    }

    @Test
    void routeAndCredentialsAreCapturedBeforeBindingChanges() {
        RecordingLink link = new RecordingLink();
        link.sendReply("s", reply("正文", image(1)), "source", "target", null);
        link.binding.openId = "changed";
        link.binding.appId = "changed";
        link.binding.appSecret = "changed";
        link.flush();
        assertEquals(Arrays.asList("app|secret|target", "app|secret|target"), link.routes);
        assertEquals(Collections.singletonList("target"), link.recipients);
    }

    @Test
    void sourceAndBindingRemainRouteFallbacks() {
        for (String source : Arrays.asList("source", null)) {
            RecordingLink link = new RecordingLink();
            link.sendReply("s", reply("正文", image(1)), source, "", null);
            link.flush();
            assertEquals(Collections.singletonList(source == null ? "bound-user" : source), link.recipients);
        }
    }

    @Test
    void noImageStructuredAndLegacyEntrypointsKeepMarkdownRetry() {
        for (boolean structured : Arrays.asList(false, true)) {
            RecordingLink link = new RecordingLink();
            link.mdFailures = 1;
            if (structured) {
                link.sendReply("s", ChannelReply.fromMarkdown("**旧正文**", true, "run", ".", ""), null, null, null);
            } else {
                link.sendReply("s", "**旧正文**", true);
            }
            link.flush();
            assertEquals(Arrays.asList("md:**旧正文**", "md:**旧正文**"), link.events);
            assertTrue(link.uploadedBytes.isEmpty());
        }
    }

    @Test
    void markdownRetryDoesNotUploadAgain() {
        RecordingLink link = new RecordingLink();
        link.mdFailures = 1;
        link.sendReply("s", reply("正文", image(1)), null, null, null);
        link.flush();
        assertEquals(Arrays.asList("upload:1:image.png", "md:正文\n![图1](img_1)",
                "md:正文\n![图1](img_1)"), link.events);
    }

    @Test
    void outerTokenRetryDoesNotUploadAgain() {
        RecordingLink link = new RecordingLink();
        link.tokenFailures = 1;
        link.sendReply("s", reply("正文", image(1)), null, null, null);
        link.flush();
        assertEquals(2, link.tokenCalls);
        assertEquals(1, link.uploadedBytes.size());
        assertEquals(2, link.events.size());
    }

    @Test
    void interruptedUploadStopsRemainingUploadsAndBody() {
        RecordingLink link = new RecordingLink();
        link.interruptUpload = true;
        link.sendReply("s", reply("正文", image(1), image(2)), null, null, null);
        try {
            link.flush();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(Collections.singletonList("upload:1:image.png"), link.events);
            assertEquals(0, link.tokenCalls);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionDuringMarkdownDoesNotStartFallbackOrRetry() {
        RecordingLink link = new RecordingLink();
        link.interruptMarkdown = true;
        link.sendReply("s", reply("正文", image(1)), null, null, null);
        try {
            link.flush();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, link.events.size());
            assertEquals(1, link.tokenCalls);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectedSubmissionNeverSendsOutsideQueueAndLaterReplyCanRun() {
        RecordingLink link = new RecordingLink();
        link.rejectSubmission = true;
        RejectedExecutionException rejected = assertThrows(RejectedExecutionException.class,
                () -> link.sendReply("s", reply("正文", image(1)), null, null, null));
        assertEquals("test rejection", rejected.getMessage());
        assertTrue(link.events.isEmpty());
        assertTrue(link.tasks.isEmpty());
        link.rejectSubmission = false;
        link.sendReply("s", "next", true);
        link.flush();
        assertEquals(Collections.singletonList("md:next"), link.events);
    }

    @Test
    void absentBindingCredentialsAndEmptyRepliesDoNotQueueWork() {
        RecordingLink absent = new RecordingLink();
        absent.binding = null;
        absent.sendReply("s", reply("正文", image(1)), null, null, null);
        assertTrue(absent.tasks.isEmpty());
        RecordingLink missing = new RecordingLink();
        missing.binding.appSecret = null;
        missing.sendReply("s", reply("正文", image(1)), null, null, null);
        assertTrue(missing.tasks.isEmpty());
        RecordingLink empty = new RecordingLink();
        empty.sendReply("s", (ChannelReply) null, null, null, null);
        empty.sendReply("s", reply(""), null, null, null);
        assertTrue(empty.tasks.isEmpty());
        assertEquals(0, empty.bindingReads);
    }

    @Test
    void externalHttpAndHttpsImagesBecomeClickableLinksWithoutUploading() {
        RecordingLink link = new RecordingLink();
        ChannelReply reply = ChannelReply.fromMarkdown(
                "前 ![明文](http://images.example.invalid/a.png) 中 "
                        + "![加密](https://images.example.invalid/b.png) 后", true, "run", ".", "");
        assertEquals(2, reply.getImageReferences().size());
        assertTrue(reply.getImages().isEmpty());
        link.sendReply("s", reply, null, null, null);
        assertTrue(link.events.isEmpty());
        link.flush();
        assertEquals(Collections.singletonList("md:前 [明文](http://images.example.invalid/a.png) 中 "
                + "[加密](https://images.example.invalid/b.png) 后"), link.events);
        assertTrue(link.uploadedBytes.isEmpty());
    }

    @Test
    void httpsExternalImageLinkRemainsWholeAcross4000Boundary() {
        RecordingLink link = new RecordingLink();
        String prefix = String.join("", Collections.nCopies(3990, "前"));
        String url = "https://images.example.invalid/boundary.png";
        String node = "[边界](" + url + ")";
        ChannelReply reply = ChannelReply.fromMarkdown(prefix + "![边界](" + url + ") 后",
                true, "run", ".", "");
        assertEquals(1, reply.getImageReferences().size());
        assertTrue(reply.getImages().isEmpty());
        assertTrue(prefix.length() + node.length() > 4000);

        link.sendReply("s", reply, null, null, null);
        assertTrue(link.events.isEmpty());
        link.flush();

        assertEquals(Arrays.asList("md:" + prefix, "md:(2) " + node + " 后"), link.events);
        assertTrue(link.uploadedBytes.isEmpty());
        assertEquals(1, link.tokenCalls);
    }

    @Test
    void oversizedHttpsExternalImageFallsBackInPlaceWithoutResendingLongPrefix() {
        RecordingLink link = new RecordingLink();
        String prefix = String.join("", Collections.nCopies(8000, "前"));
        String url = "https://images.example.invalid/" + String.join("", Collections.nCopies(4100, "a"));
        String shortUrl = "https://images.example.invalid/short.png";
        ChannelReply reply = ChannelReply.fromMarkdown(prefix + "![超长](" + url + ") 中 "
                + "![保留](" + shortUrl + ") 后", true, "run", ".", "");
        assertEquals(2, reply.getImageReferences().size());
        assertTrue(reply.getImages().isEmpty());

        link.sendReply("s", reply, null, null, null);
        link.flush();

        String fallback = "（图片不可用：超长；当前渠道未启用外链图片）";
        assertEquals(Arrays.asList("md:" + prefix.substring(0, 4000),
                "md:(2) " + prefix.substring(4000, 7996),
                "md:(3) " + prefix.substring(7996),
                "md:(4) " + fallback + " 中 [保留](" + shortUrl + ") 后"), link.events);
        assertTrue(link.uploadedBytes.isEmpty());
        assertEquals(1, link.tokenCalls);
    }

    @Test
    void arbitraryUploadedImageKeyRemainsWholeAcross4000BoundaryWithoutReuploading() {
        RecordingLink link = new RecordingLink();
        // 不符合旧 img_ 正则的 key 仍应由真实节点范围保护。
        link.uploadKey = "opaque-key.v2:AbC-123";
        String prefix = String.join("", Collections.nCopies(3990, "前"));
        PublishedImage image = image(1);
        ChannelReply reply = inPlaceReply(prefix + "{{1}} 中 {{2}} 后", image, image);

        link.sendReply("s", reply, null, null, null);
        link.flush();

        assertEquals(Arrays.asList("upload:1:image.png", "md:" + prefix,
                "md:(2) ![图1](" + link.uploadKey + ") 中 ![图2](" + link.uploadKey + ") 后"), link.events);
        assertEquals(1, link.uploadedBytes.size());
        assertEquals(1, link.tokenCalls);
    }

    @Test
    void referenceStyleExternalImageRemovesDefinitionAndNormalizesUrl() {
        RecordingLink link = new RecordingLink();
        ChannelReply reply = ChannelReply.fromMarkdown(
                "![示例][remote]\n\n[remote]: HTTPS://images.example.invalid/a(b).png\n",
                true, "run", ".", "");
        assertEquals(1, reply.getImageReferences().size());
        link.sendReply("s", reply, null, null, null);
        link.flush();
        assertEquals(1, link.events.size());
        assertEquals("md:[示例](https://images.example.invalid/a%28b%29.png)", link.events.get(0).trim());
        assertTrue(link.uploadedBytes.isEmpty());
    }

    @Test
    void externalLinksDoNotPreventLocalImageUploadAndMarkdownRetryReusesBoth() throws IOException {
        RecordingLink link = new RecordingLink();
        link.mdFailures = 1;
        ChannelReply reply = mixedExternalAndLocalReply();
        link.sendReply("s", reply, null, null, null);
        link.flush();
        String markdown = "md:前 [明文](http://images.example.invalid/a.png) 中 ![本地](img_1) 后 "
                + "[加密](https://images.example.invalid/b.png)";
        assertEquals(Arrays.asList("upload:1:image.png", markdown, markdown), link.events);
        assertEquals(1, link.uploadedBytes.size());
    }

    @Test
    void plainTextFallbackBlocksExternalLinksAndReplacesLocalImagesWithoutReuploading() throws IOException {
        // 同时覆盖仅外链与外链、本地快照混合两种降级路径。
        for (boolean mixed : Arrays.asList(false, true)) {
            RecordingLink link = new RecordingLink();
            link.mdFailures = 3;
            link.allowPlainText = true;
            ChannelReply reply = mixed ? mixedExternalAndLocalReply() : ChannelReply.fromMarkdown(
                    "前 ![明文](http://images.example.invalid/a.png) 中 "
                            + "![加密](https://images.example.invalid/b.png) 后", true, "run", ".", "");
            link.sendReply("s", reply, null, null, null);
            link.flush();
            int uploads = mixed ? 1 : 0;
            String markdown = mixed
                    ? "前 [明文](http://images.example.invalid/a.png) 中 ![本地](img_1) 后 "
                            + "[加密](https://images.example.invalid/b.png)"
                    : "前 [明文](http://images.example.invalid/a.png) 中 "
                            + "[加密](https://images.example.invalid/b.png) 后";
            assertEquals(uploads + 4, link.events.size());
            assertEquals(Collections.nCopies(3, "md:" + markdown), link.events.subList(uploads, uploads + 3));
            String fallback = link.events.get(uploads + 3);
            String blockedHttp = "（图片不可用：明文；当前渠道未启用外链图片）";
            String blockedHttps = "（图片不可用：加密；当前渠道未启用外链图片）";
            String expected = mixed
                    ? "前 " + blockedHttp + " 中 （图片不可用：本地；上传失败或渠道不支持） 后 " + blockedHttps
                    : "前 " + blockedHttp + " 中 " + blockedHttps + " 后";
            assertEquals("text:" + expected, fallback);
            assertFalse(fallback.contains("http"));
            assertFalse(fallback.contains("img_"));
            assertFalse(fallback.contains("private"));
            assertEquals(uploads, link.uploadedBytes.size());
            assertEquals(1, link.tokenCalls);
        }
    }

    @Test
    void disabledImagesAndRevokedSnapshotsNeverPublishExternalOrLocalTargets() throws IOException {
        String markdown = "![外链](https://images.example.invalid/a.png) ![本地](private/local.png)";
        for (ChannelReply reply : Arrays.asList(
                ChannelReply.fromMarkdown(markdown, true, "run", ".", "", false),
                mixedExternalAndLocalReply().withoutImages())) {
            RecordingLink link = new RecordingLink();
            assertTrue(reply.getImages().isEmpty());
            assertTrue(reply.hasImageReferences());
            link.sendReply("s", reply, null, null, null);
            link.flush();
            assertEquals(Collections.singletonList("md:" + reply.renderImages(img -> null)), link.events);
            String sent = link.events.get(0);
            assertTrue(sent.contains("图片发送已禁用"));
            assertFalse(sent.contains("http"));
            assertFalse(sent.contains("private"));
            assertFalse(sent.contains("!["));
            assertTrue(link.uploadedBytes.isEmpty());
        }
    }

    @Test
    void unsafeExternalImagesNeverLeakCredentialsQueryFragmentOrUnsupportedSchemes() {
        for (String target : Arrays.asList(
                "https://user:password@images.example.invalid/a.png",
                "https://images.example.invalid/a.png?token=secret",
                "http://images.example.invalid/a.png#secret",
                "https:///private.png", "javascript:alert(1)", "data:image/png;base64,secret")) {
            RecordingLink link = new RecordingLink();
            ChannelReply reply = ChannelReply.fromMarkdown("前 ![示例](" + target + ") 后",
                    true, "run", ".", "");
            assertEquals(1, reply.getImageReferences().size(), target);
            link.sendReply("s", reply, null, null, null);
            link.flush();
            assertEquals(Collections.singletonList("md:" + reply.renderImages(img -> null)), link.events);
            String sent = link.events.get(0);
            assertTrue(sent.contains("图片不可用：示例"), target);
            assertFalse(sent.contains(target), target);
            assertFalse(sent.contains("secret"), target);
            assertFalse(sent.contains("password"), target);
            assertFalse(sent.contains("!["), target);
            assertTrue(link.uploadedBytes.isEmpty(), target);
        }
    }

    private static ChannelReply mixedExternalAndLocalReply() throws IOException {
        Path root = Files.createTempDirectory(Paths.get("."), "feishu-external-images-");
        Path local = root.resolve("local.png");
        try {
            Files.write(local, image(1).getBytes());
            ChannelReply reply = ChannelReply.fromMarkdown(
                    "前 ![明文](http://images.example.invalid/a.png) 中 ![本地](local.png) 后 "
                            + "![加密](https://images.example.invalid/b.png)",
                    true, "run", root.toString(), "");
            assertEquals(3, reply.getImageReferences().size());
            assertEquals(1, reply.getImages().size());
            return reply;
        } finally {
            Files.deleteIfExists(local);
            Files.deleteIfExists(root);
        }
    }

    private static class RecordingLink extends FeishuLink {
        FeishuBinding binding = new FeishuBinding();
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        final List<String> recipients = new ArrayList<>();
        final List<byte[]> uploadedBytes = new ArrayList<>();
        int bindingReads;
        int tokenCalls;
        int mdFailures;
        int tokenFailures;
        int failUpload = -1;
        String uploadKey;
        boolean interruptUpload;
        boolean interruptMarkdown;
        boolean rejectSubmission;
        boolean allowPlainText;

        RecordingLink() {
            super("workspace", null);
            binding.openId = "bound-user";
            binding.appId = "app";
            binding.appSecret = "secret";
        }

        void flush() {
            assertEquals(1, tasks.size());
            tasks.get(0).run();
        }

        @Override protected FeishuBinding currentBinding(String session) {
            bindingReads++;
            return binding;
        }
        @Override protected void submitReplyTask(Runnable task) {
            if (rejectSubmission) throw new RejectedExecutionException("test rejection");
            tasks.add(task);
        }
        @Override protected String getReplyAccessToken(FeishuBinding snapshot) {
            routes.add(snapshot.appId + "|" + snapshot.appSecret + "|" + snapshot.openId);
            tokenCalls++;
            return tokenCalls <= tokenFailures ? null : "token";
        }
        @Override protected String uploadReplyImage(FeishuBinding snapshot, byte[] bytes, String filename) {
            routes.add(snapshot.appId + "|" + snapshot.appSecret + "|" + snapshot.openId);
            uploadedBytes.add(bytes);
            int id = imageId(bytes);
            events.add("upload:" + id + ":" + filename);
            if (interruptUpload) Thread.currentThread().interrupt();
            if (id == failUpload) throw new IllegalStateException("private/local.png raw server error");
            return uploadKey == null ? "img_" + id : uploadKey;
        }
        @Override protected boolean sendReplyTextChunk(String token, String user, String text, int part) {
            recipients.add(user);
            events.add("md:" + text);
            if (interruptMarkdown) {
                Thread.currentThread().interrupt();
                return false;
            }
            return mdFailures-- <= 0;
        }
        @Override protected boolean sendReplyPlainTextChunk(String token, String user, String text) {
            if (!allowPlainText) fail("unexpected fallback: " + text);
            recipients.add(user);
            events.add("text:" + text);
            return true;
        }
    }
}
