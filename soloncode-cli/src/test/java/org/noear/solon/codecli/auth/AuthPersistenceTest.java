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

    @Test void migratesUsersAndRejectsDamagedOldAndNewFiles() throws Exception {
        withHome(() -> {
            Path root = temp.resolve(".soloncode");
            Files.createDirectories(root);
            Path old = root.resolve("users.json");
            Files.write(old, "broken".getBytes(StandardCharsets.UTF_8));
            assertThrows(Exception.class, () -> new FileUserStore().init(new UserAuthConfig()));
            assertFalse(Files.exists(root.resolve("auth/users.json")));
            Files.write(old, ("[{\"id\":\"1\",\"username\":\"admin\",\"passwordHash\":\"hash\","
                    + "\"role\":\"admin\",\"enabled\":true,\"createdAt\":1,\"updatedAt\":1}]").getBytes(StandardCharsets.UTF_8));
            FileUserStore store = new FileUserStore();
            store.init(new UserAuthConfig());
            assertEquals(1, store.listUsers().size());
            Path current = root.resolve("auth/users.json");
            assertTrue(Files.exists(current));
            Files.write(current, "corrupt".getBytes(StandardCharsets.UTF_8));
            assertThrows(Exception.class, () -> new FileUserStore().init(new UserAuthConfig()));
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
