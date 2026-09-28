package org.noear.solon.codecli.channel.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DingTalkReplyRouteTest {
    @Test
    void queuedReplyKeepsInboundRecipientAfterBindingChanges() {
        DingTalkLink.DingTalkBinding binding = new DingTalkLink.DingTalkBinding();
        binding.userId = "user-b";
        binding.robotCode = "robot-a";
        binding.appKey = "app-a";
        binding.appSecret = "secret-a";

        DingTalkLink.DingTalkBinding reply = DingTalkLink.replyBinding(binding, "user-a", null);
        binding.userId = "user-c";
        binding.robotCode = "robot-c";

        assertEquals("user-a", reply.userId);
        assertEquals("robot-a", reply.robotCode);
        assertEquals("app-a", reply.appKey);
        assertEquals("secret-a", reply.appSecret);
    }

    @Test
    void explicitTargetWinsAndLegacyFallsBackToBinding() {
        DingTalkLink.DingTalkBinding binding = new DingTalkLink.DingTalkBinding();
        binding.userId = "current";

        assertEquals("target", DingTalkLink.replyBinding(binding, "sender", "target").userId);
        assertEquals("current", DingTalkLink.replyBinding(binding, null, null).userId);
        assertEquals("current", DingTalkLink.replyBinding(binding, "", "").userId);
        assertNull(DingTalkLink.replyBinding(null, "sender", "target"));
    }
}
