package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理员面板控制器 - 承载 /admin 面板自身所需的新接口。
 *
 * <p>用户管理相关 API 仍由 {@link UserAuthController} 提供（路由不变，向后兼容）；
 * 本控制器只提供面板骨架所需的概览与模块清单接口。</p>
 *
 * <p>多数 {@code /web/admin/} 路径由 {@link UserAuthFilter} 统一做 role=admin 鉴权，
 * 本控制器不再重复判断。<b>例外</b>：{@code /web/admin/bootstrap} 是实例自举接口，
 * 仅在认证未启用（尚无 admin 角色）时可用，由外层门禁保护，本体做幂等保护。</p>
 *
 * @author noear 2026 created
 */
//@Controller //ps: 不能用注解，否则不是 web 或 serve 都会启动
public class AdminController {
    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    private final UserStore userStore;
    private final UserSessionManager sessionManager;
    private final UserAuthConfig userAuthConfig;
    private final AgentSettings settings;

    public AdminController(UserStore userStore, UserSessionManager sessionManager,
                          UserAuthConfig userAuthConfig, AgentSettings settings) {
        this.userStore = userStore;
        this.sessionManager = sessionManager;
        this.userAuthConfig = userAuthConfig;
        this.settings = settings;
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
     * 管理模块清单：返回本实例已启用的管理模块 key，供前端过滤导航。
     *
     * <p>当前仅内置 overview + users；未来新增管理功能时在此追加即可。</p>
     */
    @Get
    @Mapping("/web/admin/modules")
    public Result<List<String>> modules() {
        // 面板骨架内置模块。前端 ADMIN_MODULES 会与此清单取交集，实现按实例能力显隐。
        List<String> keys = new java.util.ArrayList<>();
        keys.add("overview");
        keys.add("users");
        return Result.succeed(keys);
    }

    /**
     * 实例自举：在用户认证体系尚未建立时，一次性完成「开启认证 + 创建首个管理员」。
     *
     * <p>这解决了「进 /admin 要先开认证，而开认证的入口又搬进了 /admin」的自举悖论：
     * 认证未启用时系统里还没有 admin 角色，因此本接口的准入<b>不依赖</b> role=admin，
     * 而是依赖「认证当前处于未开启态」这一状态门槛。</p>
     *
     * <p>幂等保护：一旦认证已启用，本接口一律拒绝，防止绕过 role 校验重复自举、重置管理员。
     * 认证开启后，用户/配置的后续变更走既有的 /web/settings/user-auth/** 接口（受 role=admin 保护）。</p>
     */
    @Post
    @Mapping("/web/admin/bootstrap")
    public Result<Void> bootstrap(@Body String json) {
        // 幂等保护：认证已启用则拒绝（此时应走受保护的用户管理接口）
        if (userAuthConfig.isEnabled()) {
            return Result.failure("用户认证已启用，无需重复初始化");
        }

        try {
            ONode root = ONode.ofJson(json);
            String username = root.get("username").getString();
            String password = root.get("password").getString();
            String displayName = root.get("displayName").getString();
            String email = root.get("email").getString();

            if (Assert.isEmpty(username)) return Result.failure("管理员用户名不能为空");
            if (Assert.isEmpty(password)) return Result.failure("管理员密码不能为空");

            // 创建或提升首个管理员：
            // FileUserStore 初始化时可能已生成默认 admin（admin/admin123），
            // 若同名则覆盖其密码与信息，避免残留弱口令账户。
            UserEntity existing = userStore.findByUsername(username);
            if (existing != null) {
                existing.setPasswordHash(FileUserStore.hashPassword(password));
                if (!Assert.isEmpty(displayName)) existing.setDisplayName(displayName);
                if (!Assert.isEmpty(email)) existing.setEmail(email);
                existing.setRole("admin");
                existing.setEnabled(true);
                userStore.updateUser(existing);
            } else {
                UserEntity admin = new UserEntity(UUID.randomUUID().toString(), username,
                    Assert.isEmpty(displayName) ? username : displayName);
                admin.setPasswordHash(FileUserStore.hashPassword(password));
                admin.setEmail(email);
                admin.setRole("admin");
                admin.setEnabled(true);
                userStore.createUser(admin);
            }

            // 最后再开启认证：确保管理员账户先落库，避免开启后无人可登录
            userAuthConfig.setEnabled(true);
            settings.saveToFile();

            LOG.info("[Admin] Bootstrap completed: auth enabled, admin '{}' ready", username);
            return Result.succeed();
        } catch (Exception e) {
            LOG.warn("[Admin] Bootstrap failed: {}", e.getMessage());
            return Result.failure("初始化失败: " + e.getMessage());
        }
    }
}
