package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImGatewayTest {
    @TempDir
    Path tempDir;

    private ImGateway gateway() {
        return new ImGateway(new ImBindingStore(tempDir.resolve("im-bindings.json")));
    }

    @Test
    void adoptConflictsAcrossWorkspacesAndForceMigrates() {
        ImGateway gw = gateway();
        assertTrue(gw.adoptFeishu("open-1", "app-1", "secret-1", "ws-a", "session-a", false).isAccepted());

        // 同一 appId 已被其它工作区绑定：未 force 时拒绝，冲突信息指向占用者
        ImGateway.AdoptResult conflict =
                gw.adoptFeishu("open-2", "app-1", "secret-1", "ws-b", "session-b", false);
        assertFalse(conflict.isAccepted());
        assertEquals("open-1", conflict.getConflict().getUserKey());
        assertEquals("session-a", gw.findFeishuByAppId("app-1").getSessionId());

        // force 时原子迁移，并暴露被迁移的旧归属（供旧 owner 降级）
        ImGateway.AdoptResult migrated =
                gw.adoptFeishu("open-2", "app-1", "secret-1", "ws-b", "session-b", true);
        assertTrue(migrated.isAccepted());
        assertNotNull(migrated.getPrevious());
        assertEquals("ws-a", migrated.getPrevious().getWorkspaceId());
        assertEquals("open-1", migrated.getPrevious().getUserKey());
        assertNull(gw.findFeishuByOpenId("open-1"));
        assertEquals("session-b", gw.findFeishuByAppId("app-1").getSessionId());
    }

    /**
     * 真机回归：同一企业同一个人（钉钉 staffId 相同）用两个 bot 分别绑定两个对话，
     * 两条绑定必须并存——修复前后绑的会顶掉先绑的。
     */
    @Test
    void dingtalkTwoBotsWithSameStaffIdCoexist() {
        ImGateway gw = gateway();
        assertTrue(gw.adoptDingTalk("staff-9", "appKey-1", "s1", "ws-1", "session-1", false).isAccepted());
        assertTrue(gw.adoptDingTalk("staff-9", "appKey-2", "s2", "ws-2", "session-2", false).isAccepted());

        assertEquals("session-1", gw.findDingTalkByUserId("appKey-1", "staff-9").getSessionId());
        assertEquals("session-2", gw.findDingTalkByUserId("appKey-2", "staff-9").getSessionId());
        assertEquals(1, gw.listDingTalk("ws-1").size());
        assertEquals(1, gw.listDingTalk("ws-2").size());
    }

    /** 反例：同一个 bot 只能绑一个对话，第二个用户未 force 时被拒。 */
    @Test
    void dingtalkOneBotStaysWithSingleSession() {
        ImGateway gw = gateway();
        gw.adoptDingTalk("staff-1", "appKey-1", "s1", "ws-1", "session-1", false);

        ImGateway.AdoptResult conflict =
                gw.adoptDingTalk("staff-2", "appKey-1", "s1", "ws-2", "session-2", false);
        assertFalse(conflict.isAccepted());
        assertEquals("staff-1", conflict.getConflict().getUserKey());
        assertEquals("session-1", gw.findDingTalkByUserId("appKey-1", "staff-1").getSessionId());
    }

    /** 持久化后仍能区分两个 bot 的相同 userId（真机重启后的形态）。 */
    @Test
    void dingtalkTwoBotsSurviveReload() {
        Path file = tempDir.resolve("im-bindings.json");
        ImGateway first = new ImGateway(new ImBindingStore(file));
        first.adoptDingTalk("staff-9", "appKey-1", "s1", "ws-1", "session-1", false);
        first.adoptDingTalk("staff-9", "appKey-2", "s2", "ws-2", "session-2", false);

        ImGateway reloaded = new ImGateway(new ImBindingStore(file));
        reloaded.reload();
        assertEquals("session-1", reloaded.findDingTalkByUserId("appKey-1", "staff-9").getSessionId());
        assertEquals("session-2", reloaded.findDingTalkByUserId("appKey-2", "staff-9").getSessionId());
    }

    @Test
    void statusIsPrecisePerSessionAndWorkspace() {
        ImGateway gw = gateway();
        gw.adoptFeishu("open-1", "app-1", "s", "ws-a", "session-a", false);

        assertTrue(gw.feishuStatus("ws-a", "session-a").isBound());
        assertFalse(gw.feishuStatus("ws-b", "session-a").isBound());
        assertTrue(gw.feishuStatus("ws-b", "session-a").isBoundElsewhere());

        // 关键修复：其它工作区的活跃绑定不再让“无关会话”误报异地绑定
        ImBindingRegistry.SessionStatus unrelated = gw.feishuStatus("ws-b", "session-z");
        assertFalse(unrelated.isBound());
        assertFalse(unrelated.isBoundElsewhere());
    }

    @Test
    void removeFeishuOnlyRemovesOwningWorkspace() {
        ImGateway gw = gateway();
        gw.adoptFeishu("open-1", "app-1", "s", "ws-a", "session-a", false);

        assertNull(gw.removeFeishu("ws-b", "session-a"));
        assertNotNull(gw.findFeishuByOpenId("open-1"));
        assertNotNull(gw.removeFeishu("ws-a", "session-a"));
        assertNull(gw.findFeishuByOpenId("open-1"));
    }

    @Test
    void persistsSecretAndReloads() {
        Path file = tempDir.resolve("im-bindings.json");
        new ImGateway(new ImBindingStore(file))
                .adoptFeishu("open-1", "app-1", "secret-1", "ws-a", "session-a", false);

        ImGateway reloaded = new ImGateway(new ImBindingStore(file));
        reloaded.reload();
        ImBindingRegistry.Binding binding = reloaded.findFeishuByOpenId("open-1");
        assertNotNull(binding);
        assertEquals("app-1", binding.getIdentity().getAppId());
        assertEquals("secret-1", binding.getSecret());
        assertEquals("ws-a", binding.getWorkspaceId());
        assertEquals("session-a", binding.getSessionId());
    }

    // ==================== 钉钉（与飞书对称） ====================

    @Test
    void dingtalkAdoptConflictsAcrossWorkspacesAndForceMigrates() {
        ImGateway gw = gateway();
        assertTrue(gw.adoptDingTalk("user-1", "key-1", "secret-1", "ws-a", "session-a", false).isAccepted());

        ImGateway.AdoptResult conflict =
                gw.adoptDingTalk("user-2", "key-1", "secret-1", "ws-b", "session-b", false);
        assertFalse(conflict.isAccepted());
        assertEquals("user-1", conflict.getConflict().getUserKey());
        assertEquals("session-a", gw.findDingTalkByAppKey("key-1").getSessionId());

        ImGateway.AdoptResult migrated =
                gw.adoptDingTalk("user-2", "key-1", "secret-1", "ws-b", "session-b", true);
        assertTrue(migrated.isAccepted());
        assertNotNull(migrated.getPrevious());
        assertEquals("ws-a", migrated.getPrevious().getWorkspaceId());
        assertNull(gw.findDingTalkByUserId("user-1"));
        assertEquals("session-b", gw.findDingTalkByAppKey("key-1").getSessionId());
    }

    @Test
    void dingtalkStatusIsPrecisePerSessionAndWorkspace() {
        ImGateway gw = gateway();
        gw.adoptDingTalk("user-1", "key-1", "s", "ws-a", "session-a", false);

        assertTrue(gw.dingtalkStatus("ws-a", "session-a").isBound());
        assertFalse(gw.dingtalkStatus("ws-b", "session-a").isBound());
        assertTrue(gw.dingtalkStatus("ws-b", "session-a").isBoundElsewhere());

        ImBindingRegistry.SessionStatus unrelated = gw.dingtalkStatus("ws-b", "session-z");
        assertFalse(unrelated.isBound());
        assertFalse(unrelated.isBoundElsewhere());
    }

    @Test
    void dingtalkAndFeishuBindingsAreIndependent() {
        ImGateway gw = gateway();
        // 同一 userKey 在两个渠道互不干扰（登记主键为 channel + userKey）
        gw.adoptFeishu("shared", "app-1", "s1", "ws-a", "session-a", false);
        gw.adoptDingTalk("shared", "key-1", "s2", "ws-b", "session-b", false);

        assertEquals("session-a", gw.findFeishuByOpenId("shared").getSessionId());
        assertEquals("session-b", gw.findDingTalkByUserId("shared").getSessionId());

        // 移除其中一个渠道的绑定不影响另一个
        gw.removeDingTalk("ws-b", "session-b");
        assertNull(gw.findDingTalkByUserId("shared"));
        assertNotNull(gw.findFeishuByOpenId("shared"));
    }

    @Test
    void dingtalkPendingAndRemovalAreWorkspaceScoped() {
        ImGateway gw = gateway();
        gw.setPendingDingTalk("key-1", "ws-a", "session-a");
        assertTrue(gw.isPendingDingTalk("ws-a", "session-a"));
        assertFalse(gw.isPendingDingTalk("ws-b", "session-a"));
        gw.clearPendingDingTalk("key-1");
        assertFalse(gw.isPendingDingTalk("ws-a", "session-a"));

        gw.adoptDingTalk("user-1", "key-1", "s", "ws-a", "session-a", false);
        assertNull(gw.removeDingTalk("ws-b", "session-a"));
        assertNotNull(gw.removeDingTalk("ws-a", "session-a"));
    }

    // ==================== 会话删除主动清理（方案 3.3 的 onSessionRemoved 钩子） ====================

    @Test
    void onSessionRemovedCleansBindingsForThatSessionOnly() {
        ImGateway gw = gateway();
        gw.adoptFeishu("open-1", "app-1", "s1", "ws-a", "session-a", false);
        gw.adoptDingTalk("user-1", "key-1", "s2", "ws-a", "session-a", false);
        // 同工作区的另一个会话：不得被误伤
        gw.adoptFeishu("open-2", "app-2", "s3", "ws-a", "session-b", false);

        gw.onSessionRemoved("ws-a", "session-a");

        assertNull(gw.findFeishuByOpenId("open-1"));
        assertNull(gw.findDingTalkByUserId("user-1"));
        assertNotNull(gw.findFeishuByOpenId("open-2"));
    }

    @Test
    void onSessionRemovedIsWorkspaceScopedAndNullSafe() {
        ImGateway gw = gateway();
        gw.adoptFeishu("open-1", "app-1", "s1", "ws-a", "session-a", false);

        // 归属工作区不匹配：不清理（避免同一 sessionId 在不同工作区间碰撞时误删）
        gw.onSessionRemoved("ws-b", "session-a");
        assertNotNull(gw.findFeishuByOpenId("open-1"));

        // null 入参安全（无工作区解析结果时调用方可能传 null）
        gw.onSessionRemoved(null, "session-a");
        gw.onSessionRemoved("ws-a", null);
        assertNotNull(gw.findFeishuByOpenId("open-1"));

        gw.onSessionRemoved("ws-a", "session-a");
        assertNull(gw.findFeishuByOpenId("open-1"));
    }
}
