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
    
    /** 无需认证的路径 */
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
        "/web/user/login",
        "/web/user/logout",
        "/web/user/me",
        "/login.html",
        "/web.html",
        "/web/chat/meta"
    ));
    
    /** 静态资源前缀 */
    private static final Set<String> STATIC_PREFIXES = new HashSet<>(Arrays.asList(
        "/css/", "/js/", "/layui/", "/highlight/", "/img/", "/skin/", "/favicon.ico"
    ));
    
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        // 如果用户认证未启用，直接放行
        if (!userAuthConfig.isEnabled()) {
            chain.doFilter(ctx);
            return;
        }
        
        String path = ctx.path();
        
        // 放行公开路径
        if (PUBLIC_PATHS.contains(path) || path.equals("/")) {
            chain.doFilter(ctx);
            return;
        }
        
        // 放行静态资源
        for (String prefix : STATIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                chain.doFilter(ctx);
                return;
            }
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
            ctx.redirect("/login.html");
            return;
        }
        
        // 将会话信息存入上下文属性，供后续处理使用
        ctx.attrSet("user_session", session);
        ctx.attrSet("user_id", session.getUserId());
        ctx.attrSet("user_name", session.getUsername());
        ctx.attrSet("user_role", session.getRole());
        
        chain.doFilter(ctx);
    }
    
    private void responseUnauthorized(Context ctx) throws IOException {
        ctx.status(401);
        ctx.headerSet("Content-Type", "application/json");
        ctx.output("{\"code\":401,\"message\":\"未登录或会话已过期\"}");
    }
}