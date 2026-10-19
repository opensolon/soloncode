package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.channel.ChannelReply;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FeishuTextCompatibilityTest {
    @Test
    void legacyAndNoImageReplyBothRetryMarkdownThenDegradeToCleanPlainText() {
        for (boolean structured : Arrays.asList(false, true)) {
            TextLink link = new TextLink();
            link.mdFailures = 3;
            send(link, structured, "**answer**");
            assertEquals(Arrays.asList("md:**answer**", "md:**answer**", "md:**answer**", "plain:answer"), link.events);
            assertEquals(1, link.tokenCalls);
        }
    }

    @Test
    void legacyAndNoImageReplyRetryFailedTokenAndDoNotDegradeRecoveredMarkdown() {
        for (boolean structured : Arrays.asList(false, true)) {
            TextLink link = new TextLink();
            link.tokenFailures = 1;
            link.mdFailures = 1;
            send(link, structured, "**answer**");
            assertEquals(2, link.tokenCalls);
            assertEquals(Arrays.asList("md:**answer**", "md:**answer**"), link.events);
        }
    }

    @Test
    void noImageReplyKeepsOuterRetryBoundAndEmptyCleanFallback() {
        TextLink link = new TextLink();
        link.mdFailures = 100;
        link.plainFailures = 100;
        send(link, true, "```code```");
        assertEquals(3, link.tokenCalls);
        assertEquals(9, Collections.frequency(link.events, "md:```code```"));
        assertEquals(9, Collections.frequency(link.events, "plain:```code```"));
    }

    @Test
    void noImageReplyKeepsPartialMarkdownFailureWithoutWholeReplyFallback() {
        TextLink link = new TextLink();
        link.mdFailures = 3;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) text.append("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz\n");
        send(link, true, text.toString());
        assertTrue(link.events.size() > 3);
        assertTrue(link.events.stream().allMatch(e -> e.startsWith("md:")));
        assertEquals(1, link.tokenCalls);
    }

    private static void send(TextLink link, boolean structured, String text) {
        if (structured) {
            link.sendReply("session", new ChannelReply(text, true, "run", Collections.emptyList()), null, null, null);
        } else {
            link.sendReply("session", text, true);
        }
    }

    private static class TextLink extends FeishuLink {
        final FeishuBinding binding = new FeishuBinding();
        final List<String> events = new ArrayList<>();
        int tokenCalls;
        int tokenFailures;
        int mdFailures;
        int plainFailures;

        TextLink() {
            super("workspace", null);
            binding.appId = UUID.randomUUID().toString();
            binding.appSecret = "secret";
            binding.openId = "user";
        }
        @Override protected FeishuBinding currentBinding(String session) { return binding; }
        // 同步消费者仍走真实队列与真实 sendReplyDo，只有网络调用被替换。
        @Override protected void submitReplyTask(Runnable task) { task.run(); }
        @Override protected String getReplyAccessToken(FeishuBinding snapshot) {
            tokenCalls++;
            return tokenFailures-- > 0 ? null : "token";
        }
        @Override protected boolean sendReplyTextChunk(String token, String user, String text, int part) {
            events.add("md:" + text);
            return mdFailures-- <= 0;
        }
        @Override protected boolean sendReplyPlainTextChunk(String token, String user, String text) {
            events.add("plain:" + text);
            return plainFailures-- <= 0;
        }
    }
}
