package org.noear.solon.codecli.channel.feishu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeishuAppLeaseRegistryTest {
    @Test
    void sameAppIdIsRejectedAcrossWorkspaces() {
        FeishuAppLeaseRegistry registry = new FeishuAppLeaseRegistry();
        assertTrue(registry.acquire("app-1", "secret", "ws-a", "session-a", false).isAcquired());

        FeishuAppLeaseRegistry.Result result = registry.acquire(
                "app-1", "secret", "ws-b", "session-b", false);
        assertFalse(result.isAcquired());
        assertEquals("ws-a", result.getConflict().getWorkspaceId());
        assertEquals("session-a", registry.find("app-1").getSessionId());
    }

    @Test
    void forceMovesLeaseAndReportsPreviousOwner() {
        FeishuAppLeaseRegistry registry = new FeishuAppLeaseRegistry();
        registry.acquire("app-1", "secret-a", "ws-a", "session-a", false);

        FeishuAppLeaseRegistry.Result result = registry.acquire(
                "app-1", "secret-b", "ws-b", "session-b", true);
        assertTrue(result.isAcquired());
        assertEquals("ws-a", result.getPrevious().getWorkspaceId());
        assertEquals("secret-b", result.getLease().getAppSecret());
        assertEquals("ws-b", registry.find("app-1").getWorkspaceId());
    }

    @Test
    void oldOwnerCannotReleaseNewLease() {
        FeishuAppLeaseRegistry registry = new FeishuAppLeaseRegistry();
        registry.acquire("app-1", "secret-a", "ws-a", "session-a", false);
        registry.acquire("app-1", "secret-b", "ws-b", "session-b", true);

        assertFalse(registry.release("app-1", "ws-a", "session-a"));
        assertNotNull(registry.find("app-1"));
        assertTrue(registry.release("app-1", "ws-b", "session-b"));
        assertNull(registry.find("app-1"));
    }

    @Test
    void differentAppIdsCoexistAndSnapshotIsImmutable() {
        FeishuAppLeaseRegistry registry = new FeishuAppLeaseRegistry();
        registry.acquire("app-1", "secret-1", "ws-a", "session-a", false);
        registry.acquire("app-2", "secret-2", "ws-b", "session-b", false);

        Map<String, FeishuAppLeaseRegistry.Lease> snapshot = registry.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals("app-1", snapshot.get("app-1").getAppId());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    }

    @Test
    void sameOwnerAcquireIsIdempotent() {
        FeishuAppLeaseRegistry registry = new FeishuAppLeaseRegistry();
        FeishuAppLeaseRegistry.Lease first = registry.acquire(
                "app-1", "secret-a", "ws-a", "session-a", false).getLease();
        FeishuAppLeaseRegistry.Result repeated = registry.acquire(
                "app-1", "secret-b", "ws-a", "session-a", false);

        assertTrue(repeated.isAcquired());
        assertNull(repeated.getPrevious());
        assertSame(first, repeated.getLease());
        assertEquals("secret-a", registry.find("app-1").getAppSecret());
    }
}
