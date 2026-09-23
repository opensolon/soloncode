package org.noear.solon.codecli.auth;

import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作台认证与独立的管理台管理员门禁。
 * 仅全新、空的 file 实例允许匿名自举；关闭工作台认证不关闭管理台门禁。
 */
public class UserAuthFilter implements Filter {
    @Inject
    private UserAuthConfig userAuthConfig;

    @Inject
    private UserSessionManager sessionManager;

    @Inject
    private UserStore userStore;

    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
            "/web/user/login", "/web/user/logout", "/web/user/me", "/login", "/web/chat/meta"));

    private static final Set<String> STATIC_PREFIXES = new HashSet<>(Arrays.asList(
            "/css/", "/js/", "/layui/", "/highlight/", "/img/", "/skin/", "/favicon.ico"));

    static boolean isPublicPath(String path) {
        if (path == null) return false;
        if (PUBLIC_PATHS.contains(path)) return true;
        for (String prefix : STATIC_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    static boolean isAdminPath(String path) {
        return path != null && (isAdminPage(path) || path.startsWith("/web/admin/"));
    }

    private static boolean isAdminPage(String path) {
        // 旧静态页面仍受保护，旧 /admin 不再注册页面路由。
        return "/web/admin".equals(path) || "/admin.html".equals(path);
    }

    static boolean isAdminRole(String role) {
        return "admin".equals(role);
    }

    /** 仅未初始化、拥有未消费令牌且确实为空的 file 实例允许引导页/自举路由。 */
    private boolean canBootstrap() {
        if (userAuthConfig == null || userAuthConfig.isEnabled()
                || !"file".equals(UserStoreFactory.normalizeMode(userAuthConfig.getMode()))
                || userStore == null || !userStore.supportsLocalUserManagement()) return false;
        try {
            AuthConfigRepository.BootstrapState state = AuthConfigRepository.loadState();
            List<UserEntity> users = userStore.listUsers();
            return !state.isInitialized() && state.getBootstrapTokenHash() != null
                    && users != null && users.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLocalRequest(Context ctx) {
        if (ctx == null) return false;
        String ip = ctx.remoteIp();
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)
                || "localhost".equalsIgnoreCase(ip);
    }

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        String path = ctx.path();
        if (isAdminPath(path)) {
            // 豁免必须同时精确匹配路径与方法；前缀相似的接口一律仍需管理员。
            if ("POST".equalsIgnoreCase(ctx.method()) && "/web/admin/session/login".equals(path)) {
                chain.doFilter(ctx);
                return;
            }
            if (canBootstrap() && isLocalRequest(ctx) && isAdminPage(path)) {
                chain.doFilter(ctx);
                return;
            }
            if ("POST".equalsIgnoreCase(ctx.method()) && "/web/admin/bootstrap".equals(path)
                    && canBootstrap() && isLocalRequest(ctx)) {
                // 自举不使用管理员会话，但 AdminController 仍必须校验本机和一次性 token。
                chain.doFilter(ctx);
                return;
            }
            String token = UserLoginController.extractToken(ctx, false);
            UserSessionManager.UserSession session = sessionManager.getSession(token);
            if (session == null) {
                if (isAdminPage(path)) ctx.redirect("/login?scope=admin&returnUrl=%2Fweb%2Fadmin");
                else responseUnauthorized(ctx);
                return;
            }
            if (!isAdminRole(session.getRole())) {
                if (isAdminPage(path)) ctx.redirect("/");
                else responseForbidden(ctx);
                return;
            }
            if ("POST".equalsIgnoreCase(ctx.method()) && isCookieAuthentication(ctx)
                    && !isSameOrigin(ctx)) {
                responseCsrf(ctx);
                return;
            }
            attachSession(ctx, session);
            chain.doFilter(ctx);
            return;
        }

        if (!userAuthConfig.isEnabled() || isPublicPath(path)) {
            chain.doFilter(ctx);
            return;
        }

        UserSessionManager.UserSession session = sessionManager.getSession(UserLoginController.extractToken(ctx));
        if (session == null) {
            if (path != null && path.startsWith("/web/")) responseUnauthorized(ctx);
            else ctx.redirect("/login");
            return;
        }
        attachSession(ctx, session);
        chain.doFilter(ctx);
    }

    private static void attachSession(Context ctx, UserSessionManager.UserSession session) {
        ctx.attrSet("user_session", session);
        ctx.attrSet("user_id", session.getUserId());
        ctx.attrSet("user_name", session.getUsername());
        ctx.attrSet("user_role", session.getRole());
    }

    private void responseForbidden(Context ctx) throws IOException {
        ctx.status(403);
        ctx.headerSet("Content-Type", "application/json");
        ctx.output("{\"code\":403,\"message\":\"需要管理员权限\"}");
    }

    private void responseUnauthorized(Context ctx) throws IOException {
        ctx.status(401);
        ctx.headerSet("Content-Type", "application/json");
        ctx.output("{\"code\":401,\"message\":\"未登录或会话已过期\"}");
    }

    /** Cookie 会话需要 CSRF 校验；显式 Bearer/X-User-Token API 请求保持兼容。 */
    static boolean isCookieAuthentication(Context ctx) {
        if (ctx == null) return false;
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.trim().regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String headerToken = ctx.header("X-User-Token");
        if (headerToken != null && !headerToken.trim().isEmpty()) return false;
        String cookieToken = ctx.cookie("user_token");
        if (cookieToken != null && !cookieToken.isEmpty()) return true;
        String cookie = ctx.header("Cookie");
        return cookie != null && cookie.matches("(?i).*([; ]|^)user_token=[^; ]+.*");
    }

    static boolean isSameOrigin(Context ctx) {
        if (ctx == null) return false;
        String origin = ctx.header("Origin");
        if (origin != null && !origin.trim().isEmpty()) return sameOrigin(ctx, origin.trim());
        String referer = ctx.header("Referer");
        return referer != null && sameOrigin(ctx, referer.trim());
    }

    private static boolean sameOrigin(Context ctx, String candidate) {
        try {
            URI expected = new URI(ctx.url());
            URI actual = new URI(candidate);
            return expected.getScheme() != null && actual.getScheme() != null
                    && expected.getScheme().equalsIgnoreCase(actual.getScheme())
                    && expected.getHost() != null && expected.getHost().equalsIgnoreCase(actual.getHost())
                    && effectivePort(expected) == effectivePort(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void responseCsrf(Context ctx) throws IOException {
        ctx.status(403);
        ctx.headerSet("Content-Type", "application/json");
        ctx.output("{\"code\":403,\"message\":\"请求来源校验失败\"}");
    }
}
