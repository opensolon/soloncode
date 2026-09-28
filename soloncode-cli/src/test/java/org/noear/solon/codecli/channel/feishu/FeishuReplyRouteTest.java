package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeishuReplyRouteTest {
    @Test
    void queuedReplyKeepsInboundRecipientAfterBindingChanges() {
        FeishuLink.FeishuBinding binding = new FeishuLink.FeishuBinding();
        binding.openId = "user-b";
        binding.appId = "app-a";
        binding.appSecret = "secret-a";

        FeishuLink.FeishuBinding reply = FeishuLink.replyBinding(binding, "user-a", null);
        binding.openId = "user-c";
        binding.appSecret = "secret-c";

        assertEquals("user-a", reply.openId);
        assertEquals("app-a", reply.appId);
        assertEquals("secret-a", reply.appSecret);
    }

    @Test
    void explicitTargetWinsAndLegacyFallsBackToBinding() {
        FeishuLink.FeishuBinding binding = new FeishuLink.FeishuBinding();
        binding.openId = "current";

        assertEquals("target", FeishuLink.replyBinding(binding, "sender", "target").openId);
        assertEquals("current", FeishuLink.replyBinding(binding, null, null).openId);
        assertEquals("current", FeishuLink.replyBinding(binding, "", "").openId);
        assertNull(FeishuLink.replyBinding(null, "sender", "target"));
    }
}
