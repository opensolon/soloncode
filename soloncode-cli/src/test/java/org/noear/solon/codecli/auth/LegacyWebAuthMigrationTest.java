package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyWebAuthMigrationTest {
    @TempDir
    Path temp;

    @Test
    void migratesLegacyCredentialsToAdminAndIsIdempotent() throws Exception {
        withHome(() -> {
            assertEquals(LegacyWebAuthMigrationService.Result.MIGRATED,
                    LegacyWebAuthMigrationService.migrateIfNeeded("legacy-admin", "1234"));

            UserAuthConfig config = AuthConfigRepository.load();
            assertTrue(config.isEnabled());
            assertEquals("file", config.getMode());
            assertTrue(AuthConfigRepository.loadState().isInitialized());

            FileUserStore store = new FileUserStore();
            store.init(config);
            assertEquals(1, store.listUsers().size());
            UserEntity admin = store.authenticate("legacy-admin", "1234");
            assertNotNull(admin);
            assertEquals("admin", admin.getRole());
            assertTrue(admin.getPasswordHash().startsWith("pbkdf2-sha256$"));
            assertEquals(LegacyWebAuthMigrationService.Result.ALREADY_MANAGED,
                    LegacyWebAuthMigrationService.migrateIfNeeded("legacy-admin", "different"));
            assertEquals(1, store.listUsers().size());
        });
    }

    @Test
    void rejectsLegacyPasswordOutsidePolicyWithoutCreatingUser() throws Exception {
        withHome(() -> {
            assertThrows(IllegalArgumentException.class,
                    () -> LegacyWebAuthMigrationService.migrateIfNeeded("legacy-admin", "123"));
            assertFalse(Files.exists(temp.resolve(".soloncode/auth/users.json")));
            assertFalse(Files.exists(AuthConfigRepository.path()));
        });
    }

    @Test
    void emptyLegacyCredentialsDoNothing() throws Exception {
        withHome(() -> {
            assertEquals(LegacyWebAuthMigrationService.Result.NOT_NEEDED,
                    LegacyWebAuthMigrationService.migrateIfNeeded(null, null));
            assertFalse(Files.exists(AuthConfigRepository.path()));
        });
    }

    private void withHome(Checked action) throws Exception {
        String old = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            action.run();
        } finally {
            if (old == null) System.clearProperty("user.home");
            else System.setProperty("user.home", old);
        }
    }

    private interface Checked {
        void run() throws Exception;
    }
}
