package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.handle.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAuthEnhancementTest {

    @Test
    void legacyDatabaseModeMigratesToFile() {
        assertEquals("file", UserStoreFactory.normalizeMode("database"));
        assertEquals("file", UserStoreFactory.normalizeMode("unknown"));
        assertEquals("file", UserStoreFactory.normalizeMode(null));
        assertEquals("ldap", UserStoreFactory.normalizeMode("ldap"));
    }

    @Test
    void databaseFieldsAreRemovedFromConfigContract() {
        assertThrows(NoSuchFieldException.class, () -> UserAuthConfig.class.getDeclaredField("dbUrl"));
        assertThrows(NoSuchFieldException.class, () -> UserAuthConfig.class.getDeclaredField("dbUser"));
        assertThrows(NoSuchFieldException.class, () -> UserAuthConfig.class.getDeclaredField("dbPassword"));
        assertThrows(NoSuchFieldException.class, () -> UserAuthConfig.class.getDeclaredField("dbDriverClass"));
    }

    @Test
    void ldapFilterValuesAreEscaped() {
        assertEquals("user\\2a\\28x\\29\\5c\\00",
                LdapUserStore.escapeFilterValue("user*(x)\\" + ((char) 0)));
    }

    @Test
    void ldapConfigRequiresAdminMappingAndPlaceholder() {
        UserAuthConfig valid = validLdapConfig();
        LdapUserStore.validateConfig(valid);

        UserAuthConfig missingAdminGroup = validLdapConfig();
        missingAdminGroup.setLdapAdminGroupDn(null);
        assertThrows(IllegalArgumentException.class, () -> LdapUserStore.validateConfig(missingAdminGroup));

        UserAuthConfig invalidFilter = validLdapConfig();
        invalidFilter.setLdapUserFilter("(uid=test)");
        assertThrows(IllegalArgumentException.class, () -> LdapUserStore.validateConfig(invalidFilter));
    }

    @Test
    void reloadableStoreSwitchesCapabilitiesImmediately() throws Exception {
        MutableStore file = new MutableStore(true, "file");
        ReloadableUserStore reloadable = new ReloadableUserStore(file);
        assertTrue(reloadable.supportsLocalUserManagement());
        assertEquals("file", reloadable.getType());

        reloadable.replace(new MutableStore(false, "ldap"));
        assertFalse(reloadable.supportsLocalUserManagement());
        assertEquals("ldap", reloadable.getType());
    }

    @Test
    void ldapStoreRejectsLocalCrudAtControllerBoundary() {
        MutableStore ldap = new MutableStore(false, "ldap");
        UserAuthController controller = new UserAuthController(ldap, new UserSessionManager(),
                validLdapConfig(), null);
        Result<Void> result = controller.createUser("{\"username\":\"test\",\"password\":\"secret\",\"role\":\"user\"}");
        assertFalse(result.getCode() == 200);
        assertTrue(result.getDescription().contains("LDAP"));
    }

    @Test
    void lastEnabledAdminCannotBeDisabledOrDemoted() {
        MutableStore store = new MutableStore(true, "file");
        UserEntity admin = user("a1", "root", "admin", true);
        store.users.put(admin.getId(), admin);
        UserAuthController controller = new UserAuthController(store, new UserSessionManager(),
                new UserAuthConfig(), null);

        Result<Void> disable = controller.toggleUser("{\"id\":\"a1\",\"enabled\":false}");
        assertFalse(disable.getCode() == 200);
        assertTrue(disable.getDescription().contains("最后一个管理员"));

        Result<Void> demote = controller.updateUser("{\"id\":\"a1\",\"role\":\"user\"}");
        assertFalse(demote.getCode() == 200);
        assertTrue(demote.getDescription().contains("至少保留"));
    }

    @Test
    void securityChangesRevokeUserSessions(@TempDir Path tempDir) {
        UserAuthConfig config = new UserAuthConfig();
        UserSessionManager sessions = new UserSessionManager();
        setSessionStorage(sessions, config, tempDir.resolve("sessions.json"));
        UserEntity admin = user("a1", "root", "admin", true);
        sessions.createSession(admin);

        MutableStore store = new MutableStore(true, "file");
        store.users.put(admin.getId(), admin);
        store.users.put("a2", user("a2", "other", "admin", true));
        UserAuthController controller = new UserAuthController(store, sessions, config, null);

        assertEquals(1, sessions.getUserSessions("a1").size());
        Result<Void> result = controller.updateUser("{\"id\":\"a1\",\"role\":\"user\"}");
        assertEquals(200, result.getCode());
        assertTrue(sessions.getUserSessions("a1").isEmpty());
    }

    private static void setSessionStorage(UserSessionManager manager, UserAuthConfig config, Path path) {
        try {
            java.lang.reflect.Field configField = UserSessionManager.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(manager, config);
            java.lang.reflect.Field pathField = UserSessionManager.class.getDeclaredField("sessionsFilePath");
            pathField.setAccessible(true);
            pathField.set(manager, path);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static UserAuthConfig validLdapConfig() {
        UserAuthConfig config = new UserAuthConfig();
        config.setMode("ldap");
        config.setLdapUrl("ldap://localhost:389");
        config.setLdapBaseDn("ou=users,dc=example,dc=com");
        config.setLdapUserFilter("(uid={0})");
        config.setLdapAdminGroupDn("cn=admins,ou=groups,dc=example,dc=com");
        return config;
    }

    private static UserEntity user(String id, String username, String role, boolean enabled) {
        UserEntity user = new UserEntity(id, username, username);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setPasswordHash("hash");
        return user;
    }

    private static final class MutableStore implements UserStore {
        private final boolean localManagement;
        private final String type;
        private final Map<String, UserEntity> users = new LinkedHashMap<>();

        private MutableStore(boolean localManagement, String type) {
            this.localManagement = localManagement;
            this.type = type;
        }

        @Override public void init(UserAuthConfig config) { }
        @Override public UserEntity authenticate(String username, String password) { return findByUsername(username); }
        @Override public UserEntity findByUsername(String username) {
            for (UserEntity user : users.values()) if (user.getUsername().equals(username)) return user;
            return null;
        }
        @Override public UserEntity findById(String id) { return users.get(id); }
        @Override public List<UserEntity> listUsers() { return new ArrayList<>(users.values()); }
        @Override public UserEntity createUser(UserEntity user) { users.put(user.getId(), user); return user; }
        @Override public UserEntity updateUser(UserEntity user) { users.put(user.getId(), user); return user; }
        @Override public void deleteUser(String id) { users.remove(id); }
        @Override public String getType() { return type; }
        @Override public boolean supportsLocalUserManagement() { return localManagement; }
    }
}
