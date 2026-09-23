package org.noear.solon.codecli.auth;

import java.nio.file.Files;

/**
 * 将旧版通用设置中的 Basic Auth 凭据一次性迁移为本地管理员用户。
 * 迁移只负责认证仓库，不负责写回普通 settings.json；调用方在成功后清理旧字段。
 */
public final class LegacyWebAuthMigrationService {
    private LegacyWebAuthMigrationService() {
    }

    public static Result migrateIfNeeded(String username, String password) throws Exception {
        if (isBlank(username) || isBlank(password)) {
            return Result.NOT_NEEDED;
        }
        username = username.trim();

        AuthConfigRepository.BootstrapState state = AuthConfigRepository.loadState();
        if (state.isInitialized()) {
            return Result.ALREADY_MANAGED;
        }

        UserAuthConfig config = new UserAuthConfig();
        if (Files.exists(AuthConfigRepository.path())) {
            config = AuthConfigRepository.load();
            // 已配置 LDAP 或已开启其它认证身份源时，旧 Basic Auth 不能覆盖它。
            if (config.isEnabled() || "ldap".equals(UserStoreFactory.normalizeMode(config.getMode()))) {
                return Result.ALREADY_MANAGED;
            }
        }

        PasswordPolicy.requireValid(password);
        if (username.isEmpty()) {
            throw new IllegalArgumentException("旧版安全访问用户名不能为空");
        }

        config.setMode("file");
        config.setEnabled(false);
        FileUserStore store = new FileUserStore();
        store.init(config);
        if (!store.listUsers().isEmpty()) {
            return Result.ALREADY_MANAGED;
        }

        UserEntity admin = new UserEntity();
        admin.setUsername(username);
        admin.setDisplayName(username);
        admin.setRole("admin");
        admin.setEnabled(true);
        admin.setPasswordHash(FileUserStore.hashPassword(password));

        try {
            store.createUser(admin);
            config.setEnabled(true);
            AuthConfigRepository.save(config);
            AuthConfigRepository.markInitialized();
            return Result.MIGRATED;
        } catch (Exception failure) {
            try {
                store.deleteUser(admin.getId());
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw new IllegalStateException("旧版安全访问迁移失败，旧配置仍可重试", failure);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public enum Result {
        NOT_NEEDED,
        ALREADY_MANAGED,
        MIGRATED
    }
}
