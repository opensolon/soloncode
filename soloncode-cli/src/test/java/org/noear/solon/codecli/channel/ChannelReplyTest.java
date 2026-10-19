package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.artifact.PublishedImage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChannelReplyTest {
    @Test
    void replyCopiesListAndExposesImmutableSnapshot() {
        PublishedImage image = image("one");
        List<PublishedImage> images = new ArrayList<>(Collections.singletonList(image));
        ChannelReply reply = new ChannelReply("text", true, "run-1", images);
        images.clear();

        assertEquals("text", reply.getText());
        assertTrue(reply.isFinal());
        assertEquals("run-1", reply.getRunId());
        assertEquals(Collections.singletonList(image), reply.getImages());
        assertThrows(UnsupportedOperationException.class, () -> reply.getImages().clear());
        assertTrue(new ChannelReply(null, false, null, null).getImages().isEmpty());
        assertThrows(NullPointerException.class,
                () -> new ChannelReply("text", false, null, Arrays.asList(image, null)));
    }

    @Test
    void nonFeishuChannelFallsBackToRouteAwareTextWithoutChangingRouteOrFinalFlag() {
        TextChannel channel = new TextChannel();
        channel.sendReply("session", new ChannelReply("answer", true, "run", Collections.singletonList(image("one"))),
                "source", "target", "message");

        assertEquals(1, channel.calls);
        assertEquals("answer", channel.text);
        assertEquals(Arrays.asList("session", "source", "target", "message"), channel.route);
        assertTrue(channel.isFinal);
    }

    @Test
    void pureImageFallbackIsExplicitAndNeverSendsEmptyText() {
        TextChannel channel = new TextChannel();
        channel.sendReply("session", new ChannelReply(null, false, "run", Collections.singletonList(image("one"))),
                null, null, null);
        assertEquals(1, channel.calls);
        assertEquals("当前渠道不支持图片回复。", channel.text);
        assertFalse(channel.text.contains("Web"));
        assertFalse(channel.isFinal);

        channel.sendReply("session", new ChannelReply("", true, "run", Collections.emptyList()), null, null, null);
        channel.sendReply("session", (ChannelReply) null, null, null, null);
        assertEquals(1, channel.calls);
    }

    private static PublishedImage image(String id) {
        return new PublishedImage(new byte[]{1, 2, 3}, id + ".png", "caption");
    }

    private static class TextChannel implements Channel {
        int calls;
        String text;
        boolean isFinal;
        List<String> route;

        @Override
        public String getChannelName() { return "text-only"; }

        @Override
        public boolean isBound(String sessionId) { return true; }

        @Override
        public void sendReply(String sessionId, String text, boolean isFinal) {
            fail("应调用携带路由的旧文本入口");
        }

        @Override
        public void sendReply(String sessionId, String text, boolean isFinal,
                              String sourceUserId, String replyTarget, String messageId) {
            calls++;
            this.text = text;
            this.isFinal = isFinal;
            this.route = Arrays.asList(sessionId, sourceUserId, replyTarget, messageId);
        }
    }
}
