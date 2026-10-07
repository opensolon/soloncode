package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImBindingRegistryTest {
    private static final String CHANNEL = "feishu";
    private static final ImBindingRegistry.Identity IDENTITY =
            new ImBindingRegistry.Identity("app-1", "key-1", "token-1");

    @Test
    void sameIdentityMigratesOnlyWithForce() {
        ImBindingRegistry registry = new ImBindingRegistry();
        registry.adopt(CHANNEL, "user-1", IDENTITY, "ws-a", "session-a", false);

        ImBindingRegistry.Result rejected = registry.adopt(
                CHANNEL, "user-2", IDENTITY, "ws-b", "session-b", false);
        assertFalse(rejected.isAccepted());
        assertEquals("user-1", rejected.getConflict().getUserKey());
        assertEquals("user-1", registry.find(CHANNEL, IDENTITY, "user-1").getUserKey());
        assertNull(registry.find(CHANNEL, IDENTITY, "user-2"));

        ImBindingRegistry.Result migrated = registry.adopt(
                CHANNEL, "user-2", IDENTITY, "ws-b", "session-b", true);
        assertTrue(migrated.isAccepted());
        assertEquals("user-1", migrated.getPrevious().getUserKey());
        assertNull(registry.find(CHANNEL, IDENTITY, "user-1"));
        assertEquals("session-b", registry.find(CHANNEL, IDENTITY, "user-2").getSessionId());
    }

    @Test
    void differentIdentitiesCanCoexist() {
        ImBindingRegistry registry = new ImBindingRegistry();
        ImBindingRegistry.Identity another =
                new ImBindingRegistry.Identity("app-2", "key-2", "token-2");

        assertTrue(registry.adopt(CHANNEL, "user-1", IDENTITY,
                "ws-a", "session-a", false).isAccepted());
        assertTrue(registry.adopt(CHANNEL, "user-2", another,
                "ws-b", "session-b", false).isAccepted());

        Map<String, ImBindingRegistry.Binding> snapshot = registry.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals("app-1",
                snapshot.get(ImBindingRegistry.compositeKey(CHANNEL, "app-1", "user-1")).getIdentity().getAppId());
        assertEquals("app-2",
                snapshot.get(ImBindingRegistry.compositeKey(CHANNEL, "app-2", "user-2")).getIdentity().getAppId());
    }

    /**
     * 回归点：一个 bot 只能绑一个对话，但多个 bot 可以共存。
     *
     * <p>钉钉 staffId 是组织维度的——同一个人用 bot1 / bot2 发消息会拿到<b>相同</b>的
     * userKey。若登记主键不含 bot 身份，第二条绑定会覆盖第一条，真机表现为
     * 「绑了 bot2，对话1 的绑定消失」。本用例锁死该缺陷不再复现。</p>
     */
    @Test
    void sameUserKeyAcrossDifferentBotsCoexist() {
        ImBindingRegistry registry = new ImBindingRegistry();
        ImBindingRegistry.Identity bot1 = new ImBindingRegistry.Identity(null, "appKey-1", null);
        ImBindingRegistry.Identity bot2 = new ImBindingRegistry.Identity(null, "appKey-2", null);

        assertTrue(registry.adopt("dingtalk", "staff-9", bot1,
                "ws-a", "session-1", false).isAccepted());
        assertTrue(registry.adopt("dingtalk", "staff-9", bot2,
                "ws-b", "session-2", false).isAccepted());

        assertEquals(2, registry.snapshot().size());
        assertEquals("session-1", registry.find("dingtalk", bot1, "staff-9").getSessionId());
        assertEquals("session-2", registry.find("dingtalk", bot2, "staff-9").getSessionId());
    }

    /** 同一个 bot 被第二个用户抢绑时，非 force 应被拒——这才是「一个 bot 一个对话」。 */
    @Test
    void oneBotRejectsSecondUserWithoutForce() {
        ImBindingRegistry registry = new ImBindingRegistry();
        ImBindingRegistry.Identity bot1 = new ImBindingRegistry.Identity(null, "appKey-1", null);
        registry.adopt("dingtalk", "staff-1", bot1, "ws-a", "session-1", false);

        ImBindingRegistry.Result rejected = registry.adopt(
                "dingtalk", "staff-2", bot1, "ws-b", "session-2", false);
        assertFalse(rejected.isAccepted());
        assertEquals("staff-1", rejected.getConflict().getUserKey());
        assertEquals(1, registry.snapshot().size());
    }

    @Test
    void sameUserKeyCanCoexistAcrossChannelsAndRemoveIsExact() {
        ImBindingRegistry registry = new ImBindingRegistry();
        ImBindingRegistry.Identity another = new ImBindingRegistry.Identity("app-2", "key-2", "token-2");
        registry.adopt("feishu", "same", IDENTITY, "ws-f", "s-f", false);
        registry.adopt("wechat", "same", another, "ws-w", "s-w", false);

        assertEquals(2, registry.snapshot().size());
        assertEquals("app-1", registry.find("feishu", IDENTITY, "same").getIdentity().getAppId());
        assertEquals("app-2", registry.find("wechat", another, "same").getIdentity().getAppId());
        assertTrue(registry.remove("feishu", IDENTITY, "same", "ws-f", "s-f"));
        assertNull(registry.find("feishu", IDENTITY, "same"));
        assertNotNull(registry.find("wechat", another, "same"));
    }

    @Test
    void removeDoesNotDeleteAReplacedBinding() {
        ImBindingRegistry registry = new ImBindingRegistry();
        registry.adopt(CHANNEL, "user-1", IDENTITY, "ws-a", "session-a", false);
        registry.adopt(CHANNEL, "user-1", IDENTITY, "ws-b", "session-b", false);

        assertFalse(registry.remove(CHANNEL, IDENTITY, "user-1", "ws-a", "session-a"));
        assertNotNull(registry.find(CHANNEL, IDENTITY, "user-1"));
        assertTrue(registry.remove(CHANNEL, IDENTITY, "user-1", "ws-b", "session-b"));
        assertNull(registry.find(CHANNEL, IDENTITY, "user-1"));
    }

    @Test
    void statusDistinguishesLocalAndElsewhereBindings() {
        ImBindingRegistry registry = new ImBindingRegistry();
        registry.adopt(CHANNEL, "user-1", IDENTITY, "ws-a", "session-a", false);

        ImBindingRegistry.SessionStatus elsewhere =
                registry.statusForSession(CHANNEL, "ws-b", "session-a");
        assertFalse(elsewhere.isBound());
        assertTrue(elsewhere.isBoundElsewhere());
        assertEquals("ws-a", elsewhere.getWorkspaceId());
        assertEquals("session-a", elsewhere.getSessionId());

        ImBindingRegistry.SessionStatus local =
                registry.statusForSession(CHANNEL, "ws-a", "session-a");
        assertTrue(local.isBound());
        assertFalse(local.isBoundElsewhere());
    }

    @Test
    void unclaimedBindingIsNotBoundElsewhereAndIsAdoptedOnRebind() {
        ImBindingRegistry registry = new ImBindingRegistry();
        // 历史无归属条目（v1 数据：只有 channel + identity + userKey，没有 workspaceId）
        registry.put(new ImBindingRegistry.Binding(
                CHANNEL, "user-1", IDENTITY, null, "session-a", 0L));

        // 未归属不等于「已绑定在别处」：否则前端会显示「已绑定到工作区 （空）」
        ImBindingRegistry.SessionStatus status =
                registry.statusForSession(CHANNEL, "ws-b", "session-a");
        assertFalse(status.isBound());
        assertFalse(status.isBoundElsewhere());
        assertNull(status.getWorkspaceId());

        // 重新绑定即认领（与微信通道一致），不依赖任何会话目录探测
        ImBindingRegistry.Result adopted = registry.adopt(
                CHANNEL, "user-1", IDENTITY, "ws-b", "session-a", false);
        assertTrue(adopted.isAccepted());
        assertEquals("ws-b", registry.find(CHANNEL, IDENTITY, "user-1").getWorkspaceId());
        assertTrue(registry.statusForSession(CHANNEL, "ws-b", "session-a").isBound());
    }
}
