package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.handle.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdminSessionManagementTest {
    @TempDir Path temp;

    private UserSessionManager sessions() {
        UserSessionManager manager = new UserSessionManager(temp);
        manager.init(new UserAuthConfig());
        return manager;
    }

    private static UserEntity user(String id) {
        UserEntity user = new UserEntity(id, id, id);
        user.setRole("user");
        return user;
    }

    @Test
    void pagesOnlyActiveSafeSummariesAndValidatesBounds() {
        UserSessionManager manager = sessions();
        UserSessionManager.UserSession first = manager.createSession(user("u1"));
        UserSessionManager.UserSession second = manager.createSession(user("u2"));
        UserSessionManager.UserSession expired = manager.createSession(user("u3"));
        expired.setExpiresAt(0);
        AdminSessionManagementController controller = new AdminSessionManagementController(manager);

        Result<Map<String, Object>> page = controller.list(1, 1);
        assertEquals(200, page.getCode());
        assertEquals(2, page.getData().get("total"));
        assertEquals(1, ((List<?>) page.getData().get("items")).size());
        assertEquals(1, ((List<?>) controller.list(2, 1).getData().get("items")).size());
        assertTrue(((List<?>) controller.list(Integer.MAX_VALUE, 100).getData().get("items")).isEmpty());
        for (UserSessionManager.SessionSummary summary : manager.listActiveSessions()) {
            assertEquals(64, summary.getId().length());
            assertNull(manager.getSession(summary.getId()), "摘要不是登录凭证");
            assertNotEquals(first.getToken(), summary.getId());
            assertNotEquals(second.getToken(), summary.getId());
        }
        assertEquals(2, manager.getActiveSessionCount());
        assertNotEquals(200, controller.list(0, 10).getCode());
        assertNotEquals(200, controller.list(1, 101).getCode());
        assertNotEquals(200, controller.list(1, 0).getCode());
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/sessions"));
        assertTrue(UserAuthFilter.isAdminPath("/web/admin/sessions/revoke-user"));
        assertFalse(UserAuthFilter.isPublicPath("/web/admin/sessions"));
    }

    @Test
    void revokesIndividualAndWholeUserWithoutLeakingTokensIntoAudit() throws Exception {
        new AdminAuditStore(temp.resolve("audit"));
        UserSessionManager manager = sessions();
        UserSessionManager.UserSession first = manager.createSession(user("u1"));
        UserSessionManager.UserSession second = manager.createSession(user("u1"));
        UserSessionManager.UserSession other = manager.createSession(user("u2"));
        AdminSessionManagementController controller = new AdminSessionManagementController(manager);
        String id = manager.listActiveSessions().stream().filter(s -> "u1".equals(s.getUserId()))
                .findFirst().get().getId();
        assertNotEquals(200, controller.revoke(null, "{").getCode());
        assertNotEquals(200, controller.revoke(null, "{\"id\":\"" + first.getToken() + "\"}").getCode());
        assertEquals(200, controller.revoke(null, "{\"id\":\"" + id + "\"}").getCode());
        assertNotEquals(200, controller.revoke(null, "{\"id\":\"" + id + "\"}").getCode());
        assertEquals(1, manager.getUserSessions("u1").size());
        assertNotEquals(200, controller.revokeUser(null, "{\"userId\":\"  \"}").getCode());
        assertNotEquals(200, controller.revokeUser(null, "{\"userId\":\" u1 \"}").getCode());
        assertEquals(1, controller.revokeUser(null, "{\"userId\":\"u1\"}").getData().get("revokedCount"));
        assertNull(manager.getSession(first.getToken()));
        assertNull(manager.getSession(second.getToken()));
        assertNotNull(manager.getSession(other.getToken()));
        assertEquals(0, controller.revokeUser(null, "{\"userId\":\"unknown\"}").getData().get("revokedCount"));

        Path auditFile = Files.list(temp.resolve("audit")).findFirst().get();
        String audit = new String(Files.readAllBytes(auditFile), StandardCharsets.UTF_8);
        assertTrue(audit.contains("admin.session.revoke.success"));
        assertTrue(audit.contains("admin.session.revoke_user.success"));
        assertFalse(audit.contains(first.getToken()));
        assertFalse(audit.contains(second.getToken()));
        assertFalse(audit.contains(id));
    }
}
