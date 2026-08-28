package org.noear.solon.codecli.auth;

import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

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
    
    /**
     * 用户登录
     */
    @Post
    @Mapping("/web/user/login")
    public Result<Map<String, Object>> login(String username, String password) {
        if (!config.isEnabled()) {
            return Result.failure("用户认证未启用");
        }
        if (username == null || username.isEmpty()) {
            return Result.failure("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            return Result.failure("密码不能为空");
        }
        
        UserEntity user = userStore.authenticate(username, password);
        if (user == null) {
            return Result.failure("用户名或密码错误");
        }
        
        UserSessionManager.UserSession session = sessionManager.createSession(user);
        
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", session.getToken());
        data.put("userId", session.getUserId());
        data.put("username", session.getUsername());
        data.put("displayName", session.getDisplayName());
        data.put("role", session.getRole());
        data.put("expiresAt", session.getExpiresAt());
        
        return Result.succeed(data);
    }
    
    /**
     * 用户登出
     */
    @Post
    @Mapping("/web/user/logout")
    public Result<Void> logout(Context ctx) {
        String token = extractToken(ctx);
        if (token != null) {
            sessionManager.destroySession(token);
        }
        return Result.succeed();
    }
    
    /**
     * 获取当前登录用户信息
     */
    @Get
    @Mapping("/web/user/me")
    public Result<Map<String, Object>> me(Context ctx) {
        if (!config.isEnabled()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authEnabled", false);
            return Result.succeed(data);
        }
        
        String token = extractToken(ctx);
        UserSessionManager.UserSession session = sessionManager.getSession(token);
        
        if (session == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authEnabled", true);
            data.put("authenticated", false);
            return Result.succeed(data);
        }
        
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authEnabled", true);
        data.put("authenticated", true);
        data.put("userId", session.getUserId());
        data.put("username", session.getUsername());
        data.put("displayName", session.getDisplayName());
        data.put("role", session.getRole());
        return Result.succeed(data);
    }
    
    /**
     * 从请求中提取 token
     * 优先从 Authorization: Bearer <token> 头获取
     * 其次从 cookie 或查询参数获取
     */
    public static String extractToken(Context ctx) {
        if (ctx == null) return null;
        
        // 1. Authorization header
        String auth = ctx.header("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        
        // 2. X-User-Token header
        String token = ctx.header("X-User-Token");
        if (token != null && !token.isEmpty()) {
            return token;
        }
        
        // 3. Cookie
        String cookie = ctx.header("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                part = part.trim();
                if (part.startsWith("user_token=")) {
                    return part.substring("user_token=".length());
                }
            }
        }
        
        // 4. Query parameter
        return ctx.param("user_token");
    }
}
