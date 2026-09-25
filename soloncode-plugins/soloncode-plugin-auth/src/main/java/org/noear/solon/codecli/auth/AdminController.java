package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理台接口。所有管理 API 由 {@link UserAuthFilter} 独立于工作台认证开关鉴权；
 * 唯独全新空 file 实例可匿名自举。
 *
 * @author noear 2026 created
 */
//@Controller //ps: 不能用注解，否则不是 web 或 serve 都会启动
public class AdminController {
    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    private final UserStore userStore;
    private final UserSessionManager sessionManager;
    private final UserAuthConfig userAuthConfig;

    public AdminController(UserStore userStore, UserSessionManager sessionManager,
                           UserAuthConfig userAuthConfig) {
        this.userStore = userStore;
        this.sessionManager = sessionManager;
        this.userAuthConfig = userAuthConfig;
    }

    /**
     * 管理员面板首屏概览：用户总数、活跃会话数、认证模式、存储类型等。
     */
    @Get
    @Mapping("/web/admin/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new LinkedHashMap<>();

        int userCount = 0;
        try {
            List<UserEntity> list = userStore.listUsers();
            userCount = list != null ? list.size() : 0;
        } catch (Exception e) {
            LOG.warn("[Admin] Failed to count users: {}", e.getMessage());
        }
        data.put("userCount", userCount);

        int sessionCount = 0;
        try {
            sessionCount = sessionManager.getActiveSessionCount();
        } catch (Exception e) {
            LOG.warn("[Admin] Failed to count sessions: {}", e.getMessage());
        }
        data.put("activeSessionCount", sessionCount);

        data.put("authEnabled", userAuthConfig.isEnabled());
        data.put("authMode", userAuthConfig.getMode());
        data.put("storeType", userStore.getType());

        return Result.succeed(data);
    }

    /**
     * 管理模块清单：导航按「访问控制 → 认证配置 → 用户管理 → 配置备份」排列。
     * LDAP 用户由目录服务管理，因此不显示本地用户 CRUD 模块。
     * 概览、审计、会话模块已从导航中移除；其后端接口仍保留，供后续重新开放。
     */
    @Get
    @Mapping("/web/admin/modules")
    public Result<List<String>> modules() {
        List<String> keys = new java.util.ArrayList<>();
        keys.add("access-control");
        keys.add("auth");
        keys.add("backup");
        if (userStore == null || userStore.supportsLocalUserManagement()) {
            keys.add("users");
        }
        return Result.succeed(keys);
    }

    /** 全新空 file 实例一次性创建首个管理员并开启工作台认证。 */
    @Post
    @Mapping("/web/admin/bootstrap")
    public synchronized Result<Void> bootstrap(Context ctx, @Body String json) {
        if (!isLocalRequest(ctx)) return Result.failure("初始化仅允许本机请求");
        if (userAuthConfig.isEnabled()) return Result.failure("用户认证已启用，无需重复初始化");
        if (!"file".equals(UserStoreFactory.normalizeMode(userAuthConfig.getMode()))
                || !userStore.supportsLocalUserManagement()) return Result.failure("LDAP 模式不支持创建本地管理员");
        try {
            AuthConfigRepository.BootstrapState state = AuthConfigRepository.loadState();
            if (state.isInitialized()) return Result.failure("实例未处于可初始化状态");
            List<UserEntity> users = userStore.listUsers();
            if (users == null || !users.isEmpty()) return Result.failure("实例已存在用户，不能重复初始化");
            ONode root = ONode.ofJson(json == null ? "{}" : json);
            String username = root.get("username").getString();
            String password = root.get("password").getString();
            String displayName = root.get("displayName").getString();
            String email = root.get("email").getString();

            if (Assert.isEmpty(username)) return Result.failure("管理员用户名不能为空");
            String passwordError = PasswordPolicy.validate(password);
            if (passwordError != null) return Result.failure(passwordError);

            UserEntity admin = new UserEntity(UUID.randomUUID().toString(), username,
                    Assert.isEmpty(displayName) ? username : displayName);
            admin.setPasswordHash(FileUserStore.hashPassword(password));
            admin.setEmail(email);
            admin.setRole("admin");
            admin.setEnabled(true);
            userStore.createUser(admin);

            // 先保存配置，再提交不可逆的 initialized 状态；任一步失败都恢复为空实例。
            UserAuthConfig oldConfig = UserAuthController.copyConfig(userAuthConfig);
            userAuthConfig.setEnabled(true);
            try {
                AuthConfigRepository.save(userAuthConfig);
                AuthConfigRepository.markInitialized();
            } catch (Exception e) {
                UserAuthController.applyConfig(userAuthConfig, oldConfig);
                try {
                    AuthConfigRepository.save(oldConfig);
                } catch (Exception restoreConfig) {
                    e.addSuppressed(restoreConfig);
                }
                try { userStore.deleteUser(admin.getId()); } catch (Exception rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }

            LOG.info("[Admin] Bootstrap completed: auth enabled, admin '{}' ready", username);
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("username", username);
            audit.put("mode", userAuthConfig.getMode());
            AdminAuditStore.record("admin.bootstrap.completed", audit);
            return Result.succeed();
        } catch (Exception e) {
            LOG.warn("[Admin] Bootstrap failed: {}", e.getMessage());
            AdminAuditStore.record("admin.bootstrap.failed");
            return Result.failure("初始化失败: " + e.getMessage());
        }
    }

    private static boolean isLocalRequest(Context ctx) {
        if (ctx == null) return false;
        String ip = ctx.remoteIp();
        return "127.0.0.1".equals(ip) || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }
}
