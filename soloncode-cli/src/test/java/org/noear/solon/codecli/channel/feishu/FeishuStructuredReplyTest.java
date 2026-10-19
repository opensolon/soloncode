package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.ChannelReply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeishuStructuredReplyTest {
    @Test
    void pureImageSendsNoEmptyTextAndPassesBytesFilenameAndStableUuid() {
        RecordingLink link = new RecordingLink();
        PublishedImage image = image("stable-uuid");
        link.sendReply("session", reply(null, image), null, null, null);
        assertEquals(1, link.tasks.size());
        assertTrue(link.events.isEmpty());
        link.flush();

        assertEquals(Arrays.asList("upload:stable-uuid.png", "image:" + image.getUuid()), link.events);
        assertArrayEquals(image.getBytes(), link.uploadedBytes);
        assertEquals(Collections.singletonList(image.getUuid()), link.uuids);
        assertEquals(1, link.bindingReads);
    }

    @Test
    void taskUsesTargetAndCredentialsCapturedBeforeBindingChangesAndTextPrecedesImages() {
        RecordingLink link = new RecordingLink();
        PublishedImage one = image("one");
        PublishedImage two = image("two");
        link.sendReply("session", reply("answer", one, two),
                "source", "target", "message");
        link.binding.openId = "changed-user";
        link.binding.appId = "changed-app";
        link.binding.appSecret = "changed-secret";
        link.flush();

        assertEquals(Arrays.asList("text:answer", "upload:one.png", "image:" + one.getUuid(),
                "upload:two.png", "image:" + two.getUuid()),
                link.events);
        assertEquals(1, link.bindingReads);
        assertEquals(5, link.routes.size());
        for (String route : link.routes) {
            assertEquals("app|secret|target", route);
        }
    }

    @Test
    void sourceAndCurrentBindingRemainRouteFallbacks() {
        RecordingLink source = new RecordingLink();
        source.sendReply("session", reply(null, image("one")), "source", "", null);
        source.flush();
        assertEquals("app|secret|source", source.routes.get(0));

        RecordingLink bound = new RecordingLink();
        bound.sendReply("session", reply(null, image("one")), null, null, null);
        bound.binding.openId = "later";
        bound.flush();
        assertEquals("app|secret|bound-user", bound.routes.get(0));
    }

    @Test
    void failedUploadsAndSendsAreAttemptedOnceAndDoNotResendSuccessfulImages() {
        RecordingLink link = new RecordingLink();
        PublishedImage one = image("one");
        PublishedImage two = image("two");
        PublishedImage three = image("three");
        PublishedImage four = image("four");
        link.failUpload = "two.png";
        link.failSend = three.getUuid();
        link.sendReply("session", reply("answer", one, two, three, four),
                null, null, null);
        link.flush();

        assertEquals(Arrays.asList("text:answer", "upload:one.png", "image:" + one.getUuid(), "upload:two.png",
                "upload:three.png", "image:" + three.getUuid(), "upload:four.png", "image:" + four.getUuid(),
                "hint:图片发送失败（2/4 张），请在工作区查看图片或稍后重试。"), link.events);
        assertEquals(Arrays.asList(one.getUuid(), three.getUuid(), four.getUuid()), link.uuids);
        assertEquals(1, link.tasks.size());
    }

    @Test
    void textFailureDoesNotRetryOrPreventImages() {
        RecordingLink link = new RecordingLink();
        link.failText = true;
        PublishedImage image = image("one");
        link.sendReply("session", reply("answer", image), null, null, null);
        link.flush();
        assertEquals(Arrays.asList("text:answer", "upload:one.png", "image:" + image.getUuid()), link.events);
    }

    @Test
    void actualTextChunkFailureIsNotRetriedAndStillPrecedesImages() {
        RecordingLink link = new RecordingLink();
        link.useActualText = true;
        link.failTextChunk = true;
        PublishedImage image = image("one");
        link.sendReply("session", reply("answer", image), null, null, null);
        link.flush();
        assertEquals(Arrays.asList("text:answer", "chunk:1", "upload:one.png", "image:" + image.getUuid()), link.events);
        assertEquals(1, link.tokenCalls);
    }

    @Test
    void tokenFailureIsNotRetriedAndDoesNotPreventImages() {
        RecordingLink link = new RecordingLink();
        link.useActualText = true;
        link.failToken = true;
        PublishedImage image = image("one");
        link.sendReply("session", reply("answer", image), null, null, null);
        link.flush();
        assertEquals(Arrays.asList("text:answer", "upload:one.png", "image:" + image.getUuid()), link.events);
        assertEquals(1, link.tokenCalls);
    }

    @Test
    void limitsReplyToFiveImagesAndCopiesCallerListBeforeQueuing() {
        RecordingLink link = new RecordingLink();
        List<PublishedImage> images = new ArrayList<>();
        List<String> expectedUuids = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            PublishedImage image = image("id-" + i);
            images.add(image);
            if (i <= 5) {
                expectedUuids.add(image.getUuid());
            }
        }
        link.sendReply("session", new ChannelReply("", true, "run", images), null, null, null);
        images.clear();
        link.flush();
        assertEquals(expectedUuids, link.uuids);
        assertEquals(10, link.events.size());
    }

    @Test
    void absentBindingCredentialsAndEmptyRepliesDoNotQueueWork() {
        RecordingLink missingBinding = new RecordingLink();
        missingBinding.binding = null;
        missingBinding.sendReply("session", reply(null, image("one")), "source", "target", null);
        assertTrue(missingBinding.tasks.isEmpty());

        RecordingLink missingCredentials = new RecordingLink();
        missingCredentials.binding.appSecret = null;
        missingCredentials.sendReply("session", reply(null, image("one")), null, null, null);
        assertTrue(missingCredentials.tasks.isEmpty());

        RecordingLink empty = new RecordingLink();
        empty.sendReply("session", (ChannelReply) null, null, null, null);
        empty.sendReply("session", reply(""), null, null, null);
        assertTrue(empty.tasks.isEmpty());
        assertEquals(0, empty.bindingReads);
    }

    @Test
    void captionsAppendInRegistrationOrderBeforeImagesWithoutScanningMarkdown() {
        RecordingLink link = new RecordingLink();
        String text = "**answer**\n![原有图片](https://example.com/image.png)";
        PublishedImage first = new PublishedImage(new byte[]{1}, "first.png", "第一张说明");
        PublishedImage empty = new PublishedImage(new byte[]{2}, "empty.png", "");
        PublishedImage absent = image("absent");
        PublishedImage last = new PublishedImage(new byte[]{3}, "last.png", "**末张说明**");
        link.sendReply("session", reply(text, first, empty, absent, last), null, null, null);
        assertTrue(link.events.isEmpty());
        link.flush();

        assertEquals(Arrays.asList("text:" + text + "\n第一张说明\n**末张说明**",
                "upload:first.png", "image:" + first.getUuid(),
                "upload:empty.png", "image:" + empty.getUuid(),
                "upload:absent.png", "image:" + absent.getUuid(),
                "upload:last.png", "image:" + last.getUuid()), link.events);
        assertEquals(Arrays.asList(first.getUuid(), empty.getUuid(), absent.getUuid(), last.getUuid()), link.uuids);
    }

    @Test
    void captionIsSentBeforeImageWithNullOrEmptyText() {
        for (String text : Arrays.asList(null, "")) {
            RecordingLink link = new RecordingLink();
            PublishedImage image = new PublishedImage(new byte[]{1}, "caption.png", "图片说明");
            link.sendReply("session", reply(text, image), null, null, null);
            link.flush();

            assertEquals(Arrays.asList("text:图片说明", "upload:caption.png", "image:" + image.getUuid()), link.events);
            assertEquals(Collections.singletonList(image.getUuid()), link.uuids);
        }
    }

    @Test
    void emptyCaptionDoesNotSendEmptyText() {
        RecordingLink link = new RecordingLink();
        PublishedImage image = new PublishedImage(new byte[]{1}, "empty.png", "");
        link.sendReply("session", reply("", image), null, null, null);
        link.flush();

        assertEquals(Arrays.asList("upload:empty.png", "image:" + image.getUuid()), link.events);
    }

    @Test
    void pureImageAllFailuresGetOneSafeHintOnCapturedBindingEvenIfHintFails() {
        RecordingLink link = new RecordingLink();
        link.failUpload = "one.png";
        link.failHint = true;
        link.sendReply("session", reply(null, image("one")), "source", "target", null);
        link.binding.appSecret = "changed";
        link.binding.openId = "changed";
        link.flush();
        assertEquals(Arrays.asList("upload:one.png",
                "hint:图片发送失败（1/1 张），请在工作区查看图片或稍后重试。"), link.events);
        assertEquals(Arrays.asList("app|secret|target", "app|secret|target"), link.routes);
        assertFalse(link.events.get(1).contains("upload failure"));
    }
    
    private static ChannelReply reply(String text, PublishedImage... images) {
        return new ChannelReply(text, true, "run", Arrays.asList(images));
    }

    private static PublishedImage image(String filename) {
        return new PublishedImage(new byte[]{1, 2, 3}, filename + ".png", null);
    }

    private static class RecordingLink extends FeishuLink {
        FeishuBinding binding = new FeishuBinding();
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> routes = new ArrayList<>();
        final List<String> uuids = new ArrayList<>();
        byte[] uploadedBytes;
        String uploadedFilename;
        int bindingReads;
        String failUpload;
        String failSend;
        boolean failText;
        boolean useActualText;
        boolean failHint;
        boolean failTextChunk;
        boolean failToken;
        int tokenCalls;

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

        @Override
        protected FeishuBinding currentBinding(String sessionId) {
            bindingReads++;
            return binding;
        }

        @Override
        protected void submitReplyTask(Runnable task) {
            tasks.add(task);
        }

        @Override
        protected void sendTextOnce(FeishuBinding snapshot, String text) {
            recordRoute(snapshot);
            events.add("text:" + text);
            if (failText) {
                throw new IllegalStateException("text failure");
            }
            if (useActualText) {
                super.sendTextOnce(snapshot, text);
            }
        }

        @Override
        protected String getReplyAccessToken(FeishuBinding snapshot) {
            tokenCalls++;
            return failToken ? null : "token";
        }

        @Override
        protected boolean sendReplyTextChunk(String token, String openId, String chunk, int part) {
            assertEquals("token", token);
            assertEquals("bound-user", openId);
            events.add("chunk:" + part);
            return !failTextChunk;
        }

        @Override
        protected String uploadReplyImage(FeishuBinding snapshot, byte[] bytes, String filename) {
            recordRoute(snapshot);
            uploadedBytes = bytes;
            uploadedFilename = filename;
            events.add("upload:" + filename);
            if (filename.equals(failUpload)) {
                throw new IllegalStateException("upload failure");
            }
            return "key:" + filename;
        }

        @Override
        protected String sendReplyImage(FeishuBinding snapshot, String imageKey, String uuid) {
            recordRoute(snapshot);
            assertEquals("key:" + uploadedFilename, imageKey);
            assertNotNull(uuid);
            events.add("image:" + uuid);
            uuids.add(uuid);
            if (uuid.equals(failSend)) {
                throw new IllegalStateException("send failure");
            }
            return "message:" + uuid;
        }

        @Override
        protected void sendImageFailureHint(FeishuBinding snapshot, String text) {
            recordRoute(snapshot);
            events.add("hint:" + text);
            if (failHint) throw new IllegalStateException("hint failure");
        }
        
        private void recordRoute(FeishuBinding snapshot) {
            routes.add(snapshot.appId + "|" + snapshot.appSecret + "|" + snapshot.openId);
        }
    }
}
