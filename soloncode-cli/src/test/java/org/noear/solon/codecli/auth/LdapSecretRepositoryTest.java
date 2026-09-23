package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class LdapSecretRepositoryTest {
    @TempDir Path home;

    private void inHome(Checked action) throws Exception {
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            action.run();
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }

    private interface Checked { void run() throws Exception; }

    private Path secret() {
        return AuthConfigRepository.path().getParent().resolve("secrets/ldap-admin-password");
    }

    private UserAuthConfig ldap(String password) {
        UserAuthConfig config = new UserAuthConfig();
        config.setMode("ldap");
        config.setEnabled(true);
        config.setLdapAdminDn("cn=admin");
        config.setLdapAdminPassword(password);
        return config;
    }

    private String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test void writesPrivateSecretAndNeverSerializesPassword() throws Exception {
        inHome(() -> {
            AuthConfigRepository.save(ldap("first-password"));
            assertEquals("first-password", text(secret()));
            assertFalse(text(AuthConfigRepository.path()).contains("first-password"));
            assertFalse(text(AuthConfigRepository.path()).contains("\"ldapAdminPassword\""));
            assertEquals("first-password", AuthConfigRepository.load().getLdapAdminPassword());
            if (Files.getFileStore(secret()).supportsFileAttributeView("posix")) {
                assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(secret()));
                assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(secret().getParent()));
            }
            AuthConfigRepository.save(ldap(""));
            assertEquals("first-password", AuthConfigRepository.load().getLdapAdminPassword());
            AuthConfigRepository.save(ldap("second-password"));
            assertEquals("second-password", AuthConfigRepository.load().getLdapAdminPassword());
            assertFalse(Files.exists(secret().resolveSibling("ldap-admin-password.backup")));
        });
    }

    @Test void missingOrDamagedSecretFailsClosed() throws Exception {
        inHome(() -> {
            AuthConfigRepository.save(ldap("initial"));
            Files.delete(secret());
            assertThrows(IllegalStateException.class, AuthConfigRepository::load);
            assertThrows(IllegalStateException.class, () -> AuthConfigRepository.save(ldap("replacement")));
            Files.write(secret(), "damaged".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, AuthConfigRepository::load);
        });
    }

    @Test void migratesOldConfigAndSettings() throws Exception {
        inHome(() -> {
            Path config = AuthConfigRepository.path();
            Files.createDirectories(config.getParent());
            Files.write(config, ("{\"mode\":\"ldap\",\"enabled\":true,\"ldapAdminDn\":\"cn=admin\","
                    + "\"ldapAdminPassword\":\"old-config-password\"}").getBytes(StandardCharsets.UTF_8));
            assertEquals("old-config-password", AuthConfigRepository.load().getLdapAdminPassword());
            assertFalse(text(config).contains("old-config-password"));
            assertEquals("old-config-password", text(secret()));
            Files.delete(config);
            Files.delete(secret());
            Path settings = config.getParent().getParent().resolve("settings.json");
            Files.write(settings, ("{\"userAuth\":{\"mode\":\"ldap\",\"enabled\":true,"
                    + "\"ldapAdminDn\":\"cn=admin\",\"ldapAdminPassword\":\"old-settings-password\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals("old-settings-password", AuthConfigRepository.load().getLdapAdminPassword());
            assertFalse(text(config).contains("old-settings-password"));
            assertEquals("old-settings-password", text(secret()));
        });
    }

    @Test void interruptedRotationRestoresPreviousSecret() throws Exception {
        inHome(() -> {
            AuthConfigRepository.save(ldap("previous"));
            Path backup = secret().resolveSibling("ldap-admin-password.backup");
            Files.copy(secret(), backup);
            Files.write(secret(), "uncommitted".getBytes(StandardCharsets.UTF_8));
            assertEquals("previous", AuthConfigRepository.load().getLdapAdminPassword());
            assertEquals("previous", text(secret()));
            assertFalse(Files.exists(backup));
        });
    }

    @Test void refusesEnabledBindWithoutPassword() throws Exception {
        inHome(() -> assertThrows(IllegalStateException.class, () -> AuthConfigRepository.save(ldap(null))));
    }
}
