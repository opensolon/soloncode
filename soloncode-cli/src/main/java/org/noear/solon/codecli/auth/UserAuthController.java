package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户管理控制器 - 管理用户认证配置和本地用户 CRUD。
 */
public class UserAuthController {
    private static final Logger LOG = LoggerFactory.getLogger(UserAuthController.class);

    private final UserStore userStore;
    private final UserSessionManager sessionManager;
    private final UserAuthConfig userAuthConfig;
    private final AgentSettings settings;
    private final Object configLock = new Object();
    private final Object userLock = new Object();

    public UserAuthController(UserStore userStore, UserSessionManager sessionManager,
                              UserAuthConfig userAuthConfig, AgentSettings settings) {
        this.userStore = userStore;
        this.sessionManager = sessionManager;
        this.userAuthConfig = userAuthConfig;
        this.settings = settings;
    }

    // ========== 认证配置 ==========

    @Get
    @Mapping("/web/settings/user-auth/config")
    public Result<Map<String, Object>> getConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", userAuthConfig.isEnabled());
        data.put("mode", UserStoreFactory.normalizeMode(userAuthConfig.getMode()));
        data.put("sessionTimeoutMinutes", userAuthConfig.getSessionTimeoutMinutes());
        data.put("storeType", userStore.getType());
        data.put("localUserManagement", userStore.supportsLocalUserManagement());

        Map<String, Object> ldapConfig = new LinkedHashMap<>();
        ldapConfig.put("ldapUrl", userAuthConfig.getLdapUrl());
        ldapConfig.put("ldapAdminDn", userAuthConfig.getLdapAdminDn());
        ldapConfig.put("ldapBaseDn", userAuthConfig.getLdapBaseDn());
        ldapConfig.put("ldapUserFilter", userAuthConfig.getLdapUserFilter());
        ldapConfig.put("ldapSsl", userAuthConfig.isLdapSsl());
        ldapConfig.put("ldapDisplayNameAttribute", userAuthConfig.getLdapDisplayNameAttribute());
        ldapConfig.put("ldapEmailAttribute", userAuthConfig.getLdapEmailAttribute());
        ldapConfig.put("ldapGroupAttribute", userAuthConfig.getLdapGroupAttribute());
        ldapConfig.put("ldapAdminGroupDn", userAuthConfig.getLdapAdminGroupDn());
        ldapConfig.put("ldapConnectTimeoutMillis", userAuthConfig.getLdapConnectTimeoutMillis());
        ldapConfig.put("ldapReadTimeoutMillis", userAuthConfig.getLdapReadTimeoutMillis());
        data.put("ldap", ldapConfig);

        return Result.succeed(data);
    }

    @Post
    @Mapping("/web/settings/user-auth/ldap/test")
    public Result<Map<String, Object>> testLdap(@Body String json) {
        try {
            ONode root = ONode.ofJson(json);
            UserAuthConfig candidate = buildCandidate(root, true);
            candidate.setMode("ldap");
            LdapUserStore store = new LdapUserStore();
            store.init(candidate);
            store.testConnection();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connected", true);
            String username = root.get("ldapTestUsername").getString();
            String password = root.get("ldapTestPassword").getString();
            if (!Assert.isEmpty(username) || !Assert.isEmpty(password)) {
                if (Assert.isEmpty(username) || Assert.isEmpty(password)) {
                    return Result.failure("测试用户名和密码必须同时填写");
                }
                UserEntity user = store.authenticate(username, password);
                if (user == null) {
                    return Result.failure("LDAP 连接成功，但测试用户认证失败");
                }
                data.put("username", user.getUsername());
                data.put("displayName", user.getDisplayName());
                data.put("email", user.getEmail());
                data.put("role", user.getRole());
            }
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.warn("[UserAuth] LDAP test failed: {}", e.getMessage());
            return Result.failure("LDAP 测试失败: " + safeMessage(e));
        }
    }

    @Post
    @Mapping("/web/settings/user-auth/config/save")
    public Result<Map<String, Object>> saveConfig(@Body String json) {
        synchronized (configLock) {
            UserAuthConfig oldConfig = copyConfig(userAuthConfig);
            try {
                ONode root = ONode.ofJson(json);
                UserAuthConfig candidate = buildCandidate(root, true);
                validateCandidate(candidate);

                UserStore nextStore = UserStoreFactory.create(candidate);
                boolean ldapProofRequired = requiresLdapAdminProof(oldConfig, candidate);
                if (candidate.isEnabled() && "ldap".equals(candidate.getMode())) {
                    LdapUserStore ldapStore = (LdapUserStore) nextStore;
                    ldapStore.testConnection();
                    if (ldapProofRequired) {
                        String username = root.get("ldapTestUsername").getString();
                        String password = root.get("ldapTestPassword").getString();
                        if (Assert.isEmpty(username) || Assert.isEmpty(password)) {
                            return Result.failure("启用 LDAP 前，请填写测试用户和密码以验证管理员权限");
                        }
                        UserEntity testUser = ldapStore.authenticate(username, password);
                        if (testUser == null) {
                            return Result.failure("LDAP 测试用户认证失败");
                        }
                        if (!"admin".equals(testUser.getRole())) {
                            return Result.failure("测试用户未命中管理员组，无法启用 LDAP");
                        }
                    }
                }

                boolean identityChanged = identityConfigChanged(oldConfig, candidate);
                applyConfig(userAuthConfig, candidate);
                try {
                    settings.saveToFileStrict();
                } catch (Exception e) {
                    applyConfig(userAuthConfig, oldConfig);
                    throw e;
                }

                if (userStore instanceof ReloadableUserStore) {
                    ((ReloadableUserStore) userStore).replace(nextStore);
                }
                if (identityChanged || oldConfig.isEnabled() != candidate.isEnabled()) {
                    sessionManager.revokeAllSessions();
                }

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("storeType", userStore.getType());
                data.put("reloginRequired", oldConfig.isEnabled() && candidate.isEnabled() && identityChanged);
                LOG.info("[UserAuth] Auth config applied: enabled={}, mode={}", candidate.isEnabled(), candidate.getMode());
                return Result.succeed(data);
            } catch (Exception e) {
                LOG.warn("[UserAuth] Failed to save config: {}", e.getMessage());
                return Result.failure("保存失败: " + safeMessage(e));
            }
        }
    }

    // ========== 用户管理 ==========

    @Get
    @Mapping("/web/settings/user-auth/users")
    public Result<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserEntity user : userStore.listUsers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("displayName", user.getDisplayName());
            item.put("email", user.getEmail());
            item.put("role", user.getRole());
            item.put("enabled", user.isEnabled());
            item.put("createdAt", user.getCreatedAt());
            result.add(item);
        }
        return Result.succeed(result);
    }

    @Post
    @Mapping("/web/settings/user-auth/users/create")
    public Result<Void> createUser(@Body String json) {
        synchronized (userLock) {
            Result<Void> unsupported = ensureLocalManagement();
            if (unsupported != null) return unsupported;
            try {
                ONode root = ONode.ofJson(json);
                String username = trim(root.get("username").getString());
                String password = root.get("password").getString();
                String displayName = trim(root.get("displayName").getString());
                String email = trim(root.get("email").getString());
                String role = normalizeRole(root.get("role").getString());

                if (Assert.isEmpty(username)) return Result.failure("用户名不能为空");
                if (Assert.isEmpty(password)) return Result.failure("密码不能为空");
                if (role == null) return Result.failure("角色只能是管理员或普通用户");
                if (userStore.findByUsername(username) != null) return Result.failure("用户名已存在");

                UserEntity user = new UserEntity(UUID.randomUUID().toString(), username,
                        Assert.isEmpty(displayName) ? username : displayName);
                user.setPasswordHash(FileUserStore.hashPassword(password));
                user.setEmail(email);
                user.setRole(role);
                user.setEnabled(true);
                userStore.createUser(user);
                LOG.info("[UserAuth] Created user: {}", username);
                return Result.succeed();
            } catch (Exception e) {
                LOG.warn("[UserAuth] Failed to create user: {}", e.getMessage());
                return Result.failure("创建失败: " + safeMessage(e));
            }
        }
    }

    @Post
    @Mapping("/web/settings/user-auth/users/update")
    public Result<Void> updateUser(@Body String json) {
        synchronized (userLock) {
            Result<Void> unsupported = ensureLocalManagement();
            if (unsupported != null) return unsupported;
            try {
                ONode root = ONode.ofJson(json);
                String id = root.get("id").getString();
                if (Assert.isEmpty(id)) return Result.failure("用户 ID 不能为空");

                UserEntity existing = userStore.findById(id);
                if (existing == null) return Result.failure("用户不存在");
                UserEntity updated = copyUser(existing);

                String role = root.hasKey("role") ? normalizeRole(root.get("role").getString()) : existing.getRole();
                if (role == null) return Result.failure("角色只能是管理员或普通用户");
                if (wouldRemoveLastAdmin(existing, role, root.hasKey("enabled")
                        ? root.get("enabled").getBoolean() : existing.isEnabled())) {
                    return Result.failure("必须至少保留一个已启用的管理员");
                }

                if (root.hasKey("displayName")) updated.setDisplayName(trim(root.get("displayName").getString()));
                if (root.hasKey("email")) updated.setEmail(trim(root.get("email").getString()));
                updated.setRole(role);
                if (root.hasKey("enabled")) updated.setEnabled(root.get("enabled").getBoolean());
                String password = root.get("password").getString();
                boolean securityChanged = !Objects.equals(existing.getRole(), updated.getRole())
                        || existing.isEnabled() != updated.isEnabled();
                if (!Assert.isEmpty(password)) {
                    updated.setPasswordHash(FileUserStore.hashPassword(password));
                    securityChanged = true;
                }

                userStore.updateUser(updated);
                if (securityChanged) sessionManager.revokeUserSessions(id);
                return Result.succeed();
            } catch (Exception e) {
                LOG.warn("[UserAuth] Failed to update user: {}", e.getMessage());
                return Result.failure("更新失败: " + safeMessage(e));
            }
        }
    }

    @Post
    @Mapping("/web/settings/user-auth/users/toggle")
    public Result<Void> toggleUser(@Body String json) {
        synchronized (userLock) {
            Result<Void> unsupported = ensureLocalManagement();
            if (unsupported != null) return unsupported;
            try {
                ONode root = ONode.ofJson(json);
                String id = root.get("id").getString();
                if (Assert.isEmpty(id)) return Result.failure("用户 ID 不能为空");
                if (!root.hasKey("enabled")) return Result.failure("enabled 不能为空");

                UserEntity existing = userStore.findById(id);
                if (existing == null) return Result.failure("用户不存在");
                boolean enabled = root.get("enabled").getBoolean();
                if (wouldRemoveLastAdmin(existing, existing.getRole(), enabled)) {
                    return Result.failure("不能停用最后一个管理员");
                }

                UserEntity updated = copyUser(existing);
                updated.setEnabled(enabled);
                userStore.updateUser(updated);
                if (!enabled) sessionManager.revokeUserSessions(id);
                return Result.succeed();
            } catch (Exception e) {
                LOG.warn("[UserAuth] Failed to toggle user: {}", e.getMessage());
                return Result.failure("操作失败: " + safeMessage(e));
            }
        }
    }

    @Post
    @Mapping("/web/settings/user-auth/users/delete")
    public Result<Void> deleteUser(@Body String json) {
        synchronized (userLock) {
            Result<Void> unsupported = ensureLocalManagement();
            if (unsupported != null) return unsupported;
            try {
                String id = ONode.ofJson(json).get("id").getString();
                if (Assert.isEmpty(id)) return Result.failure("用户 ID 不能为空");

                UserEntity user = userStore.findById(id);
                if (user == null) return Result.failure("用户不存在");
                if (isActiveAdmin(user) && activeAdminCount() <= 1) {
                    return Result.failure("不能删除最后一个管理员");
                }

                userStore.deleteUser(id);
                sessionManager.revokeUserSessions(id);
                return Result.succeed();
            } catch (Exception e) {
                LOG.warn("[UserAuth] Failed to delete user: {}", e.getMessage());
                return Result.failure("删除失败: " + safeMessage(e));
            }
        }
    }

    private Result<Void> ensureLocalManagement() {
        return userStore.supportsLocalUserManagement()
                ? null : Result.failure("LDAP 模式下用户、密码和角色由目录服务统一管理");
    }

    private boolean wouldRemoveLastAdmin(UserEntity existing, String nextRole, boolean nextEnabled) {
        return isActiveAdmin(existing) && (!"admin".equals(nextRole) || !nextEnabled) && activeAdminCount() <= 1;
    }

    private int activeAdminCount() {
        int count = 0;
        for (UserEntity user : userStore.listUsers()) {
            if (isActiveAdmin(user)) count++;
        }
        return count;
    }

    private boolean isActiveAdmin(UserEntity user) {
        return user != null && user.isEnabled() && "admin".equals(user.getRole());
    }

    private UserAuthConfig buildCandidate(ONode root, boolean preservePassword) {
        UserAuthConfig candidate = copyConfig(userAuthConfig);
        if (root.hasKey("enabled")) candidate.setEnabled(root.get("enabled").getBoolean());
        if (root.hasKey("mode")) candidate.setMode(root.get("mode").getString());
        if (root.hasKey("sessionTimeoutMinutes")) {
            candidate.setSessionTimeoutMinutes(root.get("sessionTimeoutMinutes").getInt());
        }

        ONode ldap = root.get("ldap");
        if (ldap.isObject()) {
            if (ldap.hasKey("ldapUrl")) candidate.setLdapUrl(trim(ldap.get("ldapUrl").getString()));
            if (ldap.hasKey("ldapAdminDn")) candidate.setLdapAdminDn(trim(ldap.get("ldapAdminDn").getString()));
            String password = ldap.get("ldapAdminPassword").getString();
            if (!preservePassword || !Assert.isEmpty(password)) candidate.setLdapAdminPassword(password);
            if (ldap.hasKey("ldapBaseDn")) candidate.setLdapBaseDn(trim(ldap.get("ldapBaseDn").getString()));
            if (ldap.hasKey("ldapUserFilter")) candidate.setLdapUserFilter(trim(ldap.get("ldapUserFilter").getString()));
            if (ldap.hasKey("ldapSsl")) candidate.setLdapSsl(ldap.get("ldapSsl").getBoolean());
            if (ldap.hasKey("ldapDisplayNameAttribute")) candidate.setLdapDisplayNameAttribute(trim(ldap.get("ldapDisplayNameAttribute").getString()));
            if (ldap.hasKey("ldapEmailAttribute")) candidate.setLdapEmailAttribute(trim(ldap.get("ldapEmailAttribute").getString()));
            if (ldap.hasKey("ldapGroupAttribute")) candidate.setLdapGroupAttribute(trim(ldap.get("ldapGroupAttribute").getString()));
            if (ldap.hasKey("ldapAdminGroupDn")) candidate.setLdapAdminGroupDn(trim(ldap.get("ldapAdminGroupDn").getString()));
            if (ldap.hasKey("ldapConnectTimeoutMillis")) candidate.setLdapConnectTimeoutMillis(ldap.get("ldapConnectTimeoutMillis").getInt());
            if (ldap.hasKey("ldapReadTimeoutMillis")) candidate.setLdapReadTimeoutMillis(ldap.get("ldapReadTimeoutMillis").getInt());
        }
        return candidate;
    }

    private void validateCandidate(UserAuthConfig candidate) {
        String mode = candidate.getMode();
        if (!"file".equals(mode) && !"ldap".equals(mode)) {
            throw new IllegalArgumentException("认证模式只能是 file 或 ldap");
        }
        if (candidate.getSessionTimeoutMinutes() < 5 || candidate.getSessionTimeoutMinutes() > 10080) {
            throw new IllegalArgumentException("会话有效期必须在 5 到 10080 分钟之间");
        }
        if ("ldap".equals(mode)) LdapUserStore.validateConfig(candidate);
    }

    private boolean requiresLdapAdminProof(UserAuthConfig oldConfig, UserAuthConfig candidate) {
        if (!candidate.isEnabled() || !"ldap".equals(candidate.getMode())) return false;
        return !oldConfig.isEnabled() || !"ldap".equals(oldConfig.getMode())
                || !Objects.equals(oldConfig.getLdapAdminGroupDn(), candidate.getLdapAdminGroupDn())
                || !Objects.equals(oldConfig.getLdapGroupAttribute(), candidate.getLdapGroupAttribute())
                || !Objects.equals(oldConfig.getLdapUserFilter(), candidate.getLdapUserFilter())
                || !Objects.equals(oldConfig.getLdapBaseDn(), candidate.getLdapBaseDn());
    }

    private boolean identityConfigChanged(UserAuthConfig oldConfig, UserAuthConfig candidate) {
        if (!Objects.equals(oldConfig.getMode(), candidate.getMode())) return true;
        if (!"ldap".equals(candidate.getMode())) return false;
        return !Objects.equals(oldConfig.getLdapUrl(), candidate.getLdapUrl())
                || !Objects.equals(oldConfig.getLdapAdminDn(), candidate.getLdapAdminDn())
                || !Objects.equals(oldConfig.getLdapAdminPassword(), candidate.getLdapAdminPassword())
                || !Objects.equals(oldConfig.getLdapBaseDn(), candidate.getLdapBaseDn())
                || !Objects.equals(oldConfig.getLdapUserFilter(), candidate.getLdapUserFilter())
                || oldConfig.isLdapSsl() != candidate.isLdapSsl()
                || !Objects.equals(oldConfig.getLdapGroupAttribute(), candidate.getLdapGroupAttribute())
                || !Objects.equals(oldConfig.getLdapAdminGroupDn(), candidate.getLdapAdminGroupDn());
    }

    static UserAuthConfig copyConfig(UserAuthConfig source) {
        UserAuthConfig target = new UserAuthConfig();
        applyConfig(target, source);
        return target;
    }

    static void applyConfig(UserAuthConfig target, UserAuthConfig source) {
        target.setEnabled(source.isEnabled());
        target.setMode(UserStoreFactory.normalizeMode(source.getMode()));
        target.setLdapUrl(source.getLdapUrl());
        target.setLdapAdminDn(source.getLdapAdminDn());
        target.setLdapAdminPassword(source.getLdapAdminPassword());
        target.setLdapBaseDn(source.getLdapBaseDn());
        target.setLdapUserFilter(source.getLdapUserFilter());
        target.setLdapSsl(source.isLdapSsl());
        target.setLdapDisplayNameAttribute(source.getLdapDisplayNameAttribute());
        target.setLdapEmailAttribute(source.getLdapEmailAttribute());
        target.setLdapGroupAttribute(source.getLdapGroupAttribute());
        target.setLdapAdminGroupDn(source.getLdapAdminGroupDn());
        target.setLdapConnectTimeoutMillis(source.getLdapConnectTimeoutMillis());
        target.setLdapReadTimeoutMillis(source.getLdapReadTimeoutMillis());
        target.setSessionTimeoutMinutes(source.getSessionTimeoutMinutes());
        target.setSessionTokenLength(source.getSessionTokenLength());
    }

    private static UserEntity copyUser(UserEntity source) {
        UserEntity target = new UserEntity();
        target.setId(source.getId());
        target.setUsername(source.getUsername());
        target.setDisplayName(source.getDisplayName());
        target.setPasswordHash(source.getPasswordHash());
        target.setEmail(source.getEmail());
        target.setRole(source.getRole());
        target.setEnabled(source.isEnabled());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        target.setAttributes(new LinkedHashMap<>(source.getAttributes()));
        return target;
    }

    private static String normalizeRole(String role) {
        if (Assert.isEmpty(role)) return "user";
        return "admin".equals(role) || "user".equals(role) ? role : null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String safeMessage(Exception e) {
        return Assert.isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
    }
}
