package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.ChannelReply;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FeishuFailureHintTest {
    @Test
    void actualHintIsSafeSingleAttemptAndFinishesBeforeNextReplyOnSameSnapshot() {
        for (boolean throwHint : Arrays.asList(false, true)) {
            HintLink link = new HintLink();
            link.throwHint = throwHint;
            link.queueImages();
            link.sendReply("s", "next", true, null, "target", null);
            link.binding.openId = "changed";
            link.binding.appSecret = "changed";
            assertEquals(1, link.consumers.size());
            link.consumers.get(0).run();
            assertEquals(Arrays.asList("upload:one.png", "upload:two.png", "image:two.png",
                    "hint:图片发送失败（2/2 张），请在工作区查看图片或稍后重试。", "next"), link.events);
            assertEquals(2, link.tokenCalls); // 提示一次，后续普通文本一次。
            assertEquals(1, link.hintCalls);
            assertEquals(Arrays.asList("secret|target", "secret|target"), link.routes);
        }
    }

    @Test
    void failedHintTokenDoesNotRetryOrSendPlainText() {
        HintLink link = new HintLink();
        link.failToken = true;
        link.queueImages();
        link.consumers.get(0).run();
        assertEquals(1, link.tokenCalls);
        assertEquals(0, link.hintCalls);
        assertEquals(Arrays.asList("upload:one.png", "upload:two.png", "image:two.png"), link.events);
    }

    private static class HintLink extends FeishuLink {
        final FeishuBinding binding = new FeishuBinding();
        final List<Runnable> consumers = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        boolean throwHint;
        boolean failToken;
        int tokenCalls;
        int hintCalls;

        HintLink() {
            super("workspace", null);
            binding.appId = UUID.randomUUID().toString();
            binding.appSecret = "secret";
            binding.openId = "user";
        }
        void queueImages() {
            sendReply("s", new ChannelReply(null, true, "run", Arrays.asList(
                    new PublishedImage(new byte[]{1}, "one.png", null),
                    new PublishedImage(new byte[]{2}, "two.png", null))), null, "target", null);
        }
        @Override protected FeishuBinding currentBinding(String session) { return binding; }
        @Override protected void submitReplyTask(Runnable task) { consumers.add(task); }
        @Override protected String getReplyAccessToken(FeishuBinding snapshot) {
            tokenCalls++;
            routes.add(snapshot.appSecret + "|" + snapshot.openId);
            return failToken ? null : "token";
        }
        @Override protected String uploadReplyImage(FeishuBinding snapshot, byte[] bytes, String filename) {
            events.add("upload:" + filename);
            return filename.equals("one.png") ? null : filename;
        }
        @Override protected String sendReplyImage(FeishuBinding snapshot, String key, String uuid) {
            events.add("image:" + key);
            return "";
        }
        @Override protected boolean sendReplyPlainTextChunk(String token, String user, String text) {
            assertEquals("target", user);
            hintCalls++;
            events.add("hint:" + text);
            assertFalse(text.contains("raw server response"));
            if (throwHint) throw new IllegalStateException("raw server response");
            return false;
        }
        @Override protected boolean sendReplyTextChunk(String token, String user, String text, int part) {
            assertEquals("target", user);
            events.add(text);
            return true;
        }
    }
}
