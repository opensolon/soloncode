package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.codecli.config.AgentSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthPersistenceTest {
    @TempDir Path temp;

    private void withHome(Checked runnable) throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            runnable.run();
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    interface Checked { void run() throws Exception; }

    @Test void migratesOnlyGlobalLegacyConfigAndNeverWritesSettingsAuth() throws Exception {
        withHome(() -> {
            Path root = temp.resolve(".soloncode");
            Path workspace = temp.resolve("workspace");
            Files.createDirectories(root);
            Files.createDirectories(workspace.resolve(".soloncode"));
            Files.write(root.resolve("settings.json"), "{\"userAuth\":{\"enabled\":true,\"mode\":\"database\"}}".getBytes(StandardCharsets.UTF_8));
            Files.write(workspace.resolve(".soloncode/settings.json"), "{\"userAuth\":{\"enabled\":false,\"mode\":\"ldap\"}}".getBytes(StandardCharsets.UTF_8));
            AgentSettings settings = AgentSettings.loadForWorkspace(workspace.toString());
            assertTrue(settings.getUserAuth().isEnabled());
            assertEquals("file", settings.getUserAuth().getMode());
            assertTrue(Files.exists(root.resolve("auth/config.json")));
            settings.saveToFile();
            assertFalse(new String(Files.readAllBytes(root.resolve("settings.json")), StandardCharsets.UTF_8).contains("userAuth"));
            assertFalse(settings.getLocalJson().contains("userAuth"));
        });
    }

    @Test void zeroSessionTimeoutPersistsAsNeverExpires() throws Exception {
        withHome(() -> {
            UserAuthConfig config = new UserAuthConfig();
            config.setSessionTimeoutMinutes(0);
            AuthConfigRepository.save(config);
            assertEquals(0, AuthConfigRepository.load().getSessionTimeoutMinutes());
        });
    }

    @Test void conversationIsolationDefaultsToSharedAndPersistsWhenEnabled() throws Exception {
        withHome(() -> {
            UserAuthConfig defaults = new UserAuthConfig();
            assertFalse(defaults.isConversationIsolationEnabled());

            defaults.setMode("file");
            defaults.setConversationIsolationEnabled(true);
            AuthConfigRepository.save(defaults);
            assertTrue(AuthConfigRepository.load().isConversationIsolationEnabled());

            Path config = AuthConfigRepository.path();
            Files.write(config, "{\"enabled\":true,\"mode\":\"file\",\"sessionTimeoutMinutes\":60,\"sessionTokenLength\":32}"
                    .getBytes(StandardCharsets.UTF_8));
            assertFalse(AuthConfigRepository.load().isConversationIsolationEnabled(),
                    "旧配置缺少隔离字段时必须保持共享对话");
        });
    }

    @Test void damagedNewConfigFailsClosed() throws Exception {
        withHome(() -> {
            Path file = AuthConfigRepository.path();
            Files.createDirectories(file.getParent());
            Files.write(file, "not-json".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, () -> AgentSettings.loadForWorkspace(temp.resolve("workspace").toString()));
            assertThrows(Exception.class, () -> AuthConfigRepository.save(new UserAuthConfig()));
            assertEquals("not-json", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            Files.write(file, "{}".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, () -> AgentSettings.loadFromFile());
        });
    }

    @Test void malformedLegacyAuthCannotSilentlyDisableAuthentication() throws Exception {
        withHome(() -> {
            Path root = temp.resolve(".soloncode");
            Files.createDirectories(root);
            Files.write(root.resolve("settings.json"), "{\"userAuth\":{\"enabled\":".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, AgentSettings::loadFromFile);
            assertFalse(Files.exists(root.resolve("auth/config.json")));
        });
    }

    @Test void ignoresAndDeletesLegacyUsersFileAndRejectsDamagedCurrentFile() throws Exception {
        withHome(() -> {
            Path root = temp.resolve(".soloncode");
            Files.createDirectories(root);
            Path legacy = root.resolve("users.json");
            Files.write(legacy, "broken".getBytes(StandardCharsets.UTF_8));

            FileUserStore store = new FileUserStore(temp);
            store.init(new UserAuthConfig());

            assertTrue(store.listUsers().isEmpty(), "早期占位数据不能迁移为用户");
            assertFalse(Files.exists(legacy), "启动时应删除早期占位文件");
            assertFalse(Files.exists(root.resolve("auth/users.json")), "占位数据不能生成新的用户文件");

            Path current = root.resolve("auth/users.json");
            Files.createDirectories(current.getParent());
            Files.write(current, "corrupt".getBytes(StandardCharsets.UTF_8));
            assertThrows(Exception.class, () -> new FileUserStore(temp).init(new UserAuthConfig()));
        });
    }

    @Test void ignoresLegacyUsersFileEvenWhenItLooksLikeAUserList() throws Exception {
        withHome(() -> {
            Path root = temp.resolve(".soloncode");
            Files.createDirectories(root);
            Files.write(root.resolve("users.json"), ("[{\"id\":\"1\",\"username\":\"fake\",\"passwordHash\":\"hash\","
                    + "\"role\":\"admin\",\"enabled\":true,\"createdAt\":1,\"updatedAt\":1}]")
                    .getBytes(StandardCharsets.UTF_8));

            FileUserStore store = new FileUserStore(temp);
            store.init(new UserAuthConfig());

            assertTrue(store.listUsers().isEmpty(), "旧 users.json 中的数据不能迁移为用户");
            assertFalse(Files.exists(root.resolve("users.json")));
            assertFalse(Files.exists(root.resolve("auth/users.json")));
        });
    }

    @Test void sessionsAreEphemeralAndOldTokensRemoved() throws Exception {
        withHome(() -> {
            Path old = temp.resolve(".soloncode/user-sessions.json");
            Files.createDirectories(old.getParent());
            Files.write(old, "raw-token".getBytes(StandardCharsets.UTF_8));
            UserSessionManager sessions = new UserSessionManager();
            sessions.init(new UserAuthConfig());
            assertFalse(Files.exists(old));
            UserSessionManager.UserSession created = sessions.createSession(new UserEntity("1", "admin", "Admin"));
            assertNotNull(sessions.getSession(created.getToken()));
            assertFalse(Files.exists(old));
            UserSessionManager restarted = new UserSessionManager();
            restarted.init(new UserAuthConfig());
            assertNull(restarted.getSession(created.getToken()));
        });
    }
}
