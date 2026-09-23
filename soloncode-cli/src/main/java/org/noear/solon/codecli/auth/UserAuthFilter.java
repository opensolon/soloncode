package org.noear.solon.codecli.auth;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户认证过滤器 - 用于用户会话隔离
 * 
 * 独立于 WebAuthFilter（管理员密码验证），此过滤器处理用户登录后的会话验证。
 * 当用户认证启用时，对需要认证的路径进行 token 验证。
 * 未认证的请求会被重定向到登录页面或返回 401。
 * 
 * WebSocket 路径 (/web/gate) 也经过本过滤器验证：token 从 Cookie、Header 或查询参数中提取。
 * 验证通过后将用户信息存入上下文属性，供后续处理使用。
 * 
 * @author noear 2026/8/23 created
 */
//@Component(index = -98) // 在 WebAuthFilter 之后执行 //ps: 不能用注解，否则不是 web 或 serve 都会启动
public class UserAuthFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(UserAuthFilter.class);
    
    @Inject
    private UserAuthConfig userAuthConfig;
    
    @Inject
    private UserSessionManager sessionManager;
    
    /**
     * 无需认证的路径。
     *
     * <p>注意：首页 {@code /} 与 {@code /web.html} 不在此列——认证启用后，
     * 未登录访问首页必须由服务端直接重定向到 {@code /login}，而不是先放行
     * 主界面再由前端引导跳转。登录页自身（/login）及其静态依赖（见
     * {@link #STATIC_PREFIXES}）保持公开，保证登录页能正常渲染。</p>
     */
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
        "/web/user/login",
        "/web/user/logout",
        "/web/user/me",
        "/login",
        "/web/chat/meta"
    ));
    
    /** 静态资源前缀 */
    private static final Set<String> STATIC_PREFIXES = new HashSet<>(Arrays.asList(
        "/css/", "/js/", "/layui/", "/highlight/", "/img/", "/skin/", "/favicon.ico"
    ));
    
    /**
     * 管理员专属路径前缀。命中这些前缀的请求必须由 role=admin 的会话访问，
     * 否则返回 403（API）或重定向到首页（页面）。
     * 
     * <p>这是权限地基：即使前端隐藏了入口，普通用户仍可能直接调用管理 API，
     * 因此拦截必须在服务端完成。</p>
     */
    private static final String[] ADMIN_PATH_PREFIXES = {
        "/web/settings/user-auth/", // 用户管理 API
        "/web/admin/",              // 管理员面板专属 API 命名空间
        "/admin"                    // 管理员面板页面入口
    };
    
    /** 管理员角色标识 */
    private static final String ROLE_ADMIN = "admin";

    /**
     * 判断路径是否为无需认证的公开路径（含公开接口与静态资源前缀，包后可测）。
     *
     * <p>首页 {@code /} 与 {@code /web.html} 不属于公开路径：认证启用且未登录时，
     * 它们会走到 token 校验并被服务端重定向到 {@code /login}。</p>
     */
    static boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : STATIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
    
    /** 判断路径是否属于管理员专属路径（包后可测）。 */
    static boolean isAdminPath(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : ADMIN_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
    
    /** 判断角色是否为管理员（包后可测）。 */
    static boolean isAdminRole(String role) {
        return ROLE_ADMIN.equals(role);
    }
    
    private static boolean isAdmin(UserSessionManager.UserSession session) {
        return session != null && isAdminRole(session.getRole());
    }
    
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        // 如果用户认证未启用，直接放行
        if (!userAuthConfig.isEnabled()) {
            chain.doFilter(ctx);
            return;
        }
        
        String path = ctx.path();
        
        // 放行公开路径与静态资源（首页 / 和 /web.html 不在其中，未登录时将被重定向到 /login）
        if (isPublicPath(path)) {
            chain.doFilter(ctx);
            return;
        }
        
        // 检查用户 token（WebSocket 路径也经过验证，token 从 Cookie/Header/查询参数提取）
        String token = UserLoginController.extractToken(ctx);
        UserSessionManager.UserSession session = sessionManager.getSession(token);
        
        if (session == null) {
            // API 请求返回 401
            if (path.startsWith("/web/")) {
                responseUnauthorized(ctx);
                return;
            }
            // 页面请求重定向到登录页
            ctx.redirect("/login");
            return;
        }
        
        // 管理员路径鉴权：命中管理员前缀且非 admin 会话时拒绝
        if (isAdminPath(path) && !isAdmin(session)) {
            if (path.startsWith("/web/")) {
                responseForbidden(ctx);
            } else {
                // 页面请求：无权限时回到首页
                ctx.redirect("/");
            }
            return;
        }
        
        // 将会话信息存入上下文属性，供后续处理使用
        ctx.attrSet("user_session", session);
        ctx.attrSet("user_id", session.getUserId());
        ctx.attrSet("user_name", session.getUsername());
        ctx.attrSet("user_role", session.getRole());
        
        chain.doFilter(ctx);
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
}