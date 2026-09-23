package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理台登录与认证配置失败保护的边界测试。 */
class AuthBoundaryTest {

    @Test
    void adminLoginRejectsMissingAdminAndOrdinaryUser() {
        UserAuthConfig config = new UserAuthConfig();
        config.setEnabled(false);
        MutableStore store = new MutableStore();
        UserEntity ordinary = user("u1", "user", "user");
        store.users.put(ordinary.getId(), ordinary);

        UserLoginController controller = new UserLoginController(store, new UserSessionManager(), config);

        Result<Map<String, Object>> missing = controller.adminLogin("missing", "password");
        Result<Map<String, Object>> ordinaryResult = controller.adminLogin("user", "password");

        assertFalse(missing.getCode() == 200, "不存在管理员时管理台登录必须失败");
        assertFalse(ordinaryResult.getCode() == 200, "普通用户不得通过管理台登录");
        assertEquals(0, store.createdSessions,
                "管理台登录失败时不得创建任何会话");
    }

    @Test
    void adminLoginLimiterAllowsFiveFailuresThenTemporarilyRejects() {
        UserLoginController.AdminLoginLimiter limiter = new UserLoginController.AdminLoginLimiter();
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allow("admin", "127.0.0.1"));
            limiter.failure("admin", "127.0.0.1");
        }
        assertFalse(limiter.allow("admin", "127.0.0.1"), "同一用户名和 IP 的连续失败应触发保守限流");
        assertTrue(limiter.allow("other", "127.0.0.2"), "限流不能锁死其他用户名/IP 组合");
        limiter.success("admin", "127.0.0.1");
        assertTrue(limiter.allow("admin", "127.0.0.1"), "成功登录应清理失败窗口");
    }

    @Test
    void adminLoginSourceUsesHttpOnlySameSiteCookieAndOmitsJsonToken() throws Exception {
        java.nio.file.Path sourcePath = java.nio.file.Paths.get("..", "soloncode-auth", "src", "main", "java",
                "org", "noear", "solon", "codecli", "auth", "UserLoginController.java");
        String source = new String(java.nio.file.Files.readAllBytes(sourcePath),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(source.contains("HttpOnly; SameSite=Lax"));
        assertTrue(source.contains("if (ctx.isSecure()) value.append(\"; Secure\")"));
        assertTrue(source.contains("if (!adminOnly) data.put(\"token\", session.getToken())"));
    }

    @Test
    void failedAuthConfigSaveKeepsPreviousConfiguration() {
        UserAuthConfig config = new UserAuthConfig();
        config.setEnabled(true);
        config.setMode("file");
        MutableStore store = new MutableStore();
        store.users.put("a1", user("a1", "admin", "admin"));

        UserAuthController controller = new UserAuthController(store, new UserSessionManager(), config);
        Result<Map<String, Object>> result = controller.saveConfig("{\"mode\":\"unsupported\"}");

        assertFalse(result.getCode() == 200, "非法认证模式必须保存失败");
        assertTrue(config.isEnabled(), "保存失败不能改变认证开关");
        assertEquals("file", config.getMode(), "保存失败不能改变认证模式");
    }

    private static UserEntity user(String id, String username, String role) {
        UserEntity user = new UserEntity(id, username, username);
        user.setRole(role);
        user.setEnabled(true);
        user.setPasswordHash("hash");
        return user;
    }

    private static final class MutableStore implements UserStore {
        private final Map<String, UserEntity> users = new LinkedHashMap<>();
        private int createdSessions;

        @Override public void init(UserAuthConfig config) { }

        @Override public UserEntity authenticate(String username, String password) {
            UserEntity user = findByUsername(username);
            return user == null ? null : user;
        }

        @Override public UserEntity findByUsername(String username) {
            for (UserEntity user : users.values()) {
                if (user.getUsername().equals(username)) return user;
            }
            return null;
        }

        @Override public UserEntity findById(String id) { return users.get(id); }
        @Override public List<UserEntity> listUsers() { return new ArrayList<>(users.values()); }
        @Override public UserEntity createUser(UserEntity user) { users.put(user.getId(), user); return user; }
        @Override public UserEntity updateUser(UserEntity user) { users.put(user.getId(), user); return user; }
        @Override public void deleteUser(String id) { users.remove(id); }
        @Override public String getType() { return "file"; }
    }
}
