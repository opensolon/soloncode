package org.noear.solon.codecli.auth;

import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;

/**
 * 用户登录/登出/状态 API 控制器
 * 独立于已有的管理员 Basic Auth 认证
 * 
 * @author noear 2026/8/23 created
 */
//@Controller //ps: 不能用注解，否则不是 web 或 serve 都会启动
public class UserLoginController {
    private static final Logger LOG = LoggerFactory.getLogger(UserLoginController.class);
    
    private final UserStore userStore;
    private final UserSessionManager sessionManager;
    private final UserAuthConfig config;
    
    public UserLoginController(UserStore userStore, UserSessionManager sessionManager, UserAuthConfig config) {
        this.userStore = userStore;
        this.sessionManager = sessionManager;
        this.config = config;
    }

    private static final String TOKEN_COOKIE = "user_token";
    private static final AdminLoginLimiter LOGIN_LIMITER = new AdminLoginLimiter();

    /** 统一登录入口：工作台和管理台共享同一套用户会话。 */
    @Post
    @Mapping("/web/login")
    public Result<Map<String, Object>> login(Context ctx, String username, String password) {
        if (!config.isEnabled()) return Result.failure("用户认证未启用");
        if (hasNoUsers()) return Result.failure("当前没有用户记录，无需登录");
        return authenticate(username, password, ctx);
    }

    // 保留无 Context 的调用入口，供非 HTTP 单测和内部调用使用。
    public Result<Map<String, Object>> login(String username, String password) {
        if (!config.isEnabled()) return Result.failure("用户认证未启用");
        if (hasNoUsers()) return Result.failure("当前没有用户记录，无需登录");
        return authenticate(username, password, null);
    }

    private boolean hasNoUsers() {
        return AuthRuntimeState.hasNoLocalUsers(config, userStore);
    }

    private Result<Map<String, Object>> authenticate(String username, String password, Context ctx) {
        String ip = ctx == null ? "unknown" : ctx.remoteIp();
        if (!LOGIN_LIMITER.allow(username, ip)) return Result.failure("登录尝试过于频繁，请稍后重试");
        if (username == null || username.isEmpty()) {
            recordFailure(username, ctx);
            return Result.failure("用户名不能为空");
        }
        String passwordError = PasswordPolicy.validate(password);
        if (passwordError != null) {
            recordFailure(username, ctx);
            return Result.failure(passwordError);
        }

        UserEntity user = userStore.authenticate(username, password);
        if (user == null || !user.isEnabled()) {
            recordFailure(username, ctx);
            return Result.failure("用户名或密码错误");
        }

        LOGIN_LIMITER.success(username, ip);
        UserSessionManager.UserSession session = sessionManager.createSession(user);
        if (ctx != null) setSessionCookie(ctx, session.getToken());
        if ("admin".equals(user.getRole())) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("userId", user.getId());
            audit.put("username", user.getUsername());
            AdminAuditStore.record("admin.login.success", audit);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        // 浏览器使用 HttpOnly Cookie，返回 token 以兼容 Bearer/X-User-Token 客户端。
        data.put("token", session.getToken());
        data.put("userId", session.getUserId());
        data.put("username", session.getUsername());
        data.put("displayName", session.getDisplayName());
        data.put("role", session.getRole());
        data.put("expiresAt", session.getExpiresAt());
        return Result.succeed(data);
    }

    private static void recordFailure(String username, Context ctx) {
        String ip = ctx == null ? "unknown" : ctx.remoteIp();
        LOGIN_LIMITER.failure(username, ip);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("username", username == null ? "" : username);
        AdminAuditStore.record("login.failure", audit);
    }

    private void setSessionCookie(Context ctx, String token) {
        StringBuilder value = new StringBuilder(TOKEN_COOKIE).append('=').append(token)
                .append("; Path=/; HttpOnly; SameSite=Lax");
        if (config.getSessionTimeoutMinutes() == 0) value.append("; Max-Age=2147483647");
        if (ctx.isSecure()) value.append("; Secure");
        ctx.headerSet("Set-Cookie", value.toString());
    }

    static final class AdminLoginLimiter {
        private static final long WINDOW_MS = 60_000L;
        private static final int MAX_FAILURES = 5;
        private final Map<String, Attempt> attempts = new java.util.concurrent.ConcurrentHashMap<>();

        boolean allow(String username, String ip) {
            long now = System.currentTimeMillis();
            return allowed(key("u", username), now) && allowed(key("i", ip), now);
        }
        void failure(String username, String ip) { record(key("u", username)); record(key("i", ip)); }
        void success(String username, String ip) { attempts.remove(key("u", username)); attempts.remove(key("i", ip)); }
        private boolean allowed(String key, long now) {
            Attempt a = attempts.get(key);
            return a == null || now - a.startedAt >= WINDOW_MS || a.failures < MAX_FAILURES;
        }
        private void record(String key) {
            long now = System.currentTimeMillis();
            attempts.compute(key, (k, old) -> old == null || now - old.startedAt >= WINDOW_MS
                    ? new Attempt(now, 1) : new Attempt(old.startedAt, old.failures + 1));
        }
        private static String key(String type, String value) { return type + ':' + String.valueOf(value).toLowerCase(java.util.Locale.ROOT); }
        private static final class Attempt { final long startedAt; final int failures; Attempt(long startedAt, int failures) { this.startedAt = startedAt; this.failures = failures; } }
    }
    
    /** 统一登出入口：销毁当前用户会话并清除浏览器 Cookie。 */
    @Post
    @Mapping("/web/logout")
    public Result<Void> logout(Context ctx) {
        String token = extractToken(ctx);
        if (token != null) sessionManager.destroySession(token);
        if (ctx != null) {
            ctx.headerSet("Set-Cookie", TOKEN_COOKIE + "=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"
                    + (ctx.isSecure() ? "; Secure" : ""));
        }
        return Result.succeed();
    }
    
    /**
     * 获取当前登录用户信息
     */
    @Get
    @Mapping("/web/user/me")
    public Result<Map<String, Object>> me(Context ctx) {
        boolean noUsers = hasNoUsers();
        // WebView 注入 Basic 头时无持久会话，使用过滤器已校验的本次请求身份。
        if (config.isEnabled() && !noUsers && ctx != null && "basic".equals(ctx.attr("auth_scheme"))) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authEnabled", true);
            data.put("authenticated", true);
            data.put("bootstrapRequired", false);
            data.put("userId", ctx.attr("user_id"));
            data.put("username", ctx.attr("user_name"));
            data.put("role", ctx.attr("user_role"));
            return Result.succeed(data);
        }
        String token = extractToken(ctx);
        UserSessionManager.UserSession session = token == null ? null : sessionManager.getSession(token);
        boolean bootstrapRequired = bootstrapRequired();
        if (noUsers) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authEnabled", config.isEnabled());
            data.put("authRequired", false);
            data.put("hasUsers", false);
            data.put("authenticated", false);
            data.put("bootstrapRequired", bootstrapRequired);
            return Result.succeed(data);
        }
        if (!config.isEnabled()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authEnabled", false);
            data.put("authRequired", false);
            data.put("hasUsers", true);
            data.put("authenticated", session != null);
            // 认证关闭不意味着管理台匿名可访问；仍向管理台提供管理员会话身份。
            if (session != null) {
                data.put("userId", session.getUserId());
                data.put("username", session.getUsername());
                data.put("displayName", session.getDisplayName());
                data.put("role", session.getRole());
            }
            data.put("bootstrapRequired", bootstrapRequired);
            return Result.succeed(data);
        }
        
        if (session == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authEnabled", true);
            data.put("authRequired", true);
            data.put("hasUsers", true);
            data.put("authenticated", false);
            data.put("bootstrapRequired", bootstrapRequired);
            return Result.succeed(data);
        }
        
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authEnabled", true);
        data.put("authRequired", true);
        data.put("hasUsers", true);
        data.put("authenticated", true);
        data.put("bootstrapRequired", bootstrapRequired);
        data.put("userId", session.getUserId());
        data.put("username", session.getUsername());
        data.put("displayName", session.getDisplayName());
        data.put("role", session.getRole());
        return Result.succeed(data);
    }

    private boolean bootstrapRequired() {
        if (!"file".equals(UserStoreFactory.normalizeMode(config.getMode()))
                || userStore == null || !userStore.supportsLocalUserManagement()) return false;
        try {
            AuthConfigRepository.BootstrapState state = AuthConfigRepository.loadState();
            List<UserEntity> users = userStore.listUsers();
            return !state.isInitialized() && users != null && users.isEmpty()
                    && (state.getBootstrapTokenHash() != null || !Files.exists(AuthConfigRepository.statePath()));
        } catch (Exception e) {
            // 状态或用户数据不可判定时宁可隐藏向导，不能将损坏实例当作新实例。
            LOG.warn("读取 bootstrap 状态失败: {}", e.getMessage());
            return false;
        }
    }
    
    /** 提取请求令牌；管理台调用方禁止使用查询参数令牌。 */
    public static String extractToken(Context ctx) {
        return extractToken(ctx, true);
    }

    public static String extractToken(Context ctx, boolean allowQueryToken) {
        if (ctx == null) return null;
        String auth = ctx.header("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String token = ctx.header("X-User-Token");
        if (token != null && !token.isEmpty()) return token;
        token = ctx.cookie(TOKEN_COOKIE);
        if (token != null && !token.isEmpty()) return token;
        String cookie = ctx.header("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                part = part.trim();
                if (part.startsWith(TOKEN_COOKIE + "=")) return part.substring(TOKEN_COOKIE.length() + 1);
            }
        }
        return allowQueryToken ? ctx.param("user_token") : null;
    }
}
