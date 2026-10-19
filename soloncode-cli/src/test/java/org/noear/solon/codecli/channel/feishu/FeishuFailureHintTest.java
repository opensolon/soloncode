package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.ChannelReply;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 图片失败占位及 Markdown 失败时安全纯文本降级，不再发送独立图片失败提示。 */
class FeishuFailureHintTest {
    @Test
    void failedUploadRendersSafeBodyBeforeNextReplyOnCapturedBinding() {
        FallbackLink link = new FallbackLink();
        link.failUpload = true;
        ChannelReply reply = FeishuImageFixtures.reply("正文", link.image);
        link.sendReply("s", reply, null, "target", null);
        link.sendReply("s", "next", true, null, "target", null);
        link.binding.openId = "changed";
        link.binding.appSecret = "changed";
        link.flush();
        String expectedText = "正文\n（图片不可用：图1；上传失败或渠道不支持）";
        assertEquals(Arrays.asList("upload", "md:" + expectedText, "md:next"), link.events);
        String failedBody = link.events.get(1);
        assertFalse(failedBody.contains("private"));
        assertFalse(failedBody.contains("local.png"));
        assertFalse(failedBody.contains("img_secret"));
        assertFalse(failedBody.contains("不得追加"));
        assertEquals(Arrays.asList("secret|target", "secret|target", "secret|target"), link.routes);
        assertEquals(1, link.uploads);
        assertTrue(link.plain.isEmpty());
    }

    @Test
    void markdownFailureDegradesUsingSafeRenderingNotUploadedKeyOrLocalPath() {
        FallbackLink link = new FallbackLink();
        link.failMarkdown = true;
        ChannelReply reply = FeishuImageFixtures.reply("正文", link.image, link.image);
        link.sendReply("s", reply, null, "target", null);
        link.flush();
        assertEquals(1, link.uploads);
        assertEquals(3, link.markdownCalls);
        assertEquals(Collections.singletonList(reply.renderImages(img -> null)), link.plain);
        assertFalse(link.plain.get(0).contains("img_secret"));
        assertFalse(link.plain.get(0).contains("private"));
        assertFalse(link.plain.get(0).contains("local"));
    }

    @Test
    void outerBodyRetryReusesSuccessfulAndFailedUploadCache() {
        FallbackLink link = new FallbackLink();
        link.failMarkdown = true;
        link.plainFailures = 3; // 第一轮纯文本分片重试全部失败，第二轮成功。
        ChannelReply reply = FeishuImageFixtures.reply("正文", link.image);
        link.sendReply("s", reply, null, "target", null);
        link.flush();
        assertEquals(1, link.uploads);
        assertEquals(2, link.tokenCalls);
        assertEquals(6, link.markdownCalls);
        assertEquals(4, link.plain.size());
        for (String text : link.plain) assertEquals(reply.renderImages(img -> null), text);
    }

    private static class FallbackLink extends FeishuLink {
        final FeishuBinding binding = new FeishuBinding();
        final PublishedImage image = FeishuImageFixtures.image(1, "private/local.png", "不得追加");
        final List<Runnable> consumers = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        final List<String> plain = new ArrayList<>();
        boolean failUpload;
        boolean failMarkdown;
        int plainFailures;
        int tokenCalls;
        int markdownCalls;
        int uploads;

        FallbackLink() {
            super("workspace", null);
            binding.appId = UUID.randomUUID().toString();
            binding.appSecret = "secret";
            binding.openId = "user";
        }
        void flush() {
            assertEquals(1, consumers.size());
            consumers.get(0).run();
        }
        private void route(FeishuBinding snapshot) {
            routes.add(snapshot.appSecret + "|" + snapshot.openId);
        }
        @Override protected FeishuBinding currentBinding(String session) { return binding; }
        @Override protected void submitReplyTask(Runnable task) { consumers.add(task); }
        @Override protected String getReplyAccessToken(FeishuBinding snapshot) {
            tokenCalls++;
            route(snapshot);
            return "token";
        }
        @Override protected String uploadReplyImage(FeishuBinding snapshot, byte[] bytes, String filename) {
            assertEquals(1, FeishuImageFixtures.imageId(bytes));
            uploads++;
            route(snapshot);
            events.add("upload");
            return failUpload ? null : "img_secret";
        }
        @Override protected boolean sendReplyPlainTextChunk(String token, String user, String text) {
            assertEquals("target", user);
            plain.add(text);
            events.add("plain:" + text);
            return plainFailures-- <= 0;
        }
        @Override protected boolean sendReplyTextChunk(String token, String user, String text, int part) {
            assertEquals("target", user);
            markdownCalls++;
            events.add("md:" + text);
            return !failMarkdown;
        }
    }
}
