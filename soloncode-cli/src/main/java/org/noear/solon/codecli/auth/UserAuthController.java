package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 用户管理设置控制器 - 管理用户认证配置和用户 CRUD
 * 在设置面板的"用户管理"标签页中使用
 * 
 * @author noear 2026/8/23 created
 */
//@Controller //ps: 不能用注解，否则不是 web 或 serve 都会启动
public class UserAuthController {
    private static final Logger LOG = LoggerFactory.getLogger(UserAuthController.class);
    
    private final UserStore userStore;
    private final UserSessionManager sessionManager;
    private final UserAuthConfig userAuthConfig;
    private final AgentSettings settings;
    
    public UserAuthController(UserStore userStore, UserSessionManager sessionManager, 
                              UserAuthConfig userAuthConfig, AgentSettings settings) {
        this.userStore = userStore;
        this.sessionManager = sessionManager;
        this.userAuthConfig = userAuthConfig;
        this.settings = settings;
    }
    
    // ========== 认证配置 ==========
    
    /**
     * 获取用户认证配置
     */
    @Get
    @Mapping("/web/settings/user-auth/config")
    public Result<Map<String, Object>> getConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", userAuthConfig.isEnabled());
        data.put("mode", userAuthConfig.getMode());
        data.put("sessionTimeoutMinutes", userAuthConfig.getSessionTimeoutMinutes());
        
        // 数据库配置（不返回密码）
        Map<String, Object> dbConfig = new LinkedHashMap<>();
        dbConfig.put("dbUrl", userAuthConfig.getDbUrl());
        dbConfig.put("dbUser", userAuthConfig.getDbUser());
        dbConfig.put("dbDriverClass", userAuthConfig.getDbDriverClass());
        data.put("database", dbConfig);
        
        // LDAP 配置（不返回密码）
        Map<String, Object> ldapConfig = new LinkedHashMap<>();
        ldapConfig.put("ldapUrl", userAuthConfig.getLdapUrl());
        ldapConfig.put("ldapAdminDn", userAuthConfig.getLdapAdminDn());
        ldapConfig.put("ldapBaseDn", userAuthConfig.getLdapBaseDn());
        ldapConfig.put("ldapUserFilter", userAuthConfig.getLdapUserFilter());
        ldapConfig.put("ldapSsl", userAuthConfig.isLdapSsl());
        data.put("ldap", ldapConfig);
        
        data.put("storeType", userStore.getType());
        
        return Result.succeed(data);
    }
    
    /**
     * 保存用户认证配置
     */
    @Post
    @Mapping("/web/settings/user-auth/config/save")
    public Result<Void> saveConfig(@Body String json) {
        try {
            ONode root = ONode.ofJson(json);
            
            boolean wasEnabled = userAuthConfig.isEnabled();
            String oldMode = userAuthConfig.getMode();
            
            userAuthConfig.setEnabled(root.get("enabled").getBoolean());
            userAuthConfig.setMode(root.get("mode").getString());
            if (root.get("sessionTimeoutMinutes").getInt() > 0) {
                userAuthConfig.setSessionTimeoutMinutes(root.get("sessionTimeoutMinutes").getInt());
            }
            
            // 数据库配置
            ONode dbNode = root.get("database");
            if (dbNode.isObject()) {
                userAuthConfig.setDbUrl(dbNode.get("dbUrl").getString());
                userAuthConfig.setDbUser(dbNode.get("dbUser").getString());
                String dbPassword = dbNode.get("dbPassword").getString();
                if (dbPassword != null && !dbPassword.isEmpty()) {
                    userAuthConfig.setDbPassword(dbPassword);
                }
                userAuthConfig.setDbDriverClass(dbNode.get("dbDriverClass").getString());
            }
            
            // LDAP 配置
            ONode ldapNode = root.get("ldap");
            if (ldapNode.isObject()) {
                userAuthConfig.setLdapUrl(ldapNode.get("ldapUrl").getString());
                userAuthConfig.setLdapAdminDn(ldapNode.get("ldapAdminDn").getString());
                String ldapPassword = ldapNode.get("ldapAdminPassword").getString();
                if (ldapPassword != null && !ldapPassword.isEmpty()) {
                    userAuthConfig.setLdapAdminPassword(ldapPassword);
                }
                userAuthConfig.setLdapBaseDn(ldapNode.get("ldapBaseDn").getString());
                userAuthConfig.setLdapUserFilter(ldapNode.get("ldapUserFilter").getString());
                userAuthConfig.setLdapSsl(ldapNode.get("ldapSsl").getBoolean());
            }
            
            // 保存到 settings.json
            settings.saveToFile();
            
            // 如果模式变了，重新初始化存储
            if (!oldMode.equals(userAuthConfig.getMode()) || wasEnabled != userAuthConfig.isEnabled()) {
                // 在实际应用中，这里应该重新初始化 UserStore
                LOG.info("[UserAuth] Auth config changed: enabled={}, mode={}", 
                         userAuthConfig.isEnabled(), userAuthConfig.getMode());
            }
            
            return Result.succeed();
        } catch (Exception e) {
            LOG.warn("[UserAuth] Failed to save config: {}", e.getMessage());
            return Result.failure("保存失败: " + e.getMessage());
        }
    }
    
    // ========== 用户管理 ==========
    
    /**
     * 获取用户列表
     */
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
    
    /**
     * 创建用户
     */
    @Post
    @Mapping("/web/settings/user-auth/users/create")
    public Result<Void> createUser(@Body String json) {
        try {
            ONode root = ONode.ofJson(json);
            String username = root.get("username").getString();
            String password = root.get("password").getString();
            String displayName = root.get("displayName").getString();
            String email = root.get("email").getString();
            String role = root.get("role").getString();
            
            if (Assert.isEmpty(username)) return Result.failure("用户名不能为空");
            if (Assert.isEmpty(password)) return Result.failure("密码不能为空");
            
            // 检查用户名是否已存在
            if (userStore.findByUsername(username) != null) {
                return Result.failure("用户名已存在");
            }
            
            UserEntity user = new UserEntity(UUID.randomUUID().toString(), username, 
                displayName != null ? displayName : username);
            user.setPasswordHash(FileUserStore.hashPassword(password));
            user.setEmail(email);
            user.setRole(role != null ? role : "user");
            user.setEnabled(true);
            
            userStore.createUser(user);
            LOG.info("[UserAuth] Created user: {}", username);
            return Result.succeed();
        } catch (Exception e) {
            LOG.warn("[UserAuth] Failed to create user: {}", e.getMessage());
            return Result.failure("创建失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新用户
     */
    @Post
    @Mapping("/web/settings/user-auth/users/update")
    public Result<Void> updateUser(@Body String json) {
        try {
            ONode root = ONode.ofJson(json);
            String id = root.get("id").getString();
            String password = root.get("password").getString();
            String displayName = root.get("displayName").getString();
            String email = root.get("email").getString();
            String role = root.get("role").getString();
            boolean enabled = root.get("enabled").getBoolean();
            
            if (Assert.isEmpty(id)) return Result.failure("用户 ID 不能为空");
            
            UserEntity user = userStore.findById(id);
            if (user == null) return Result.failure("用户不存在");
            
            if (displayName != null) user.setDisplayName(displayName);
            if (email != null) user.setEmail(email);
            if (role != null) user.setRole(role);
            user.setEnabled(enabled);
            if (password != null && !password.isEmpty()) {
                user.setPasswordHash(FileUserStore.hashPassword(password));
            }
            
            userStore.updateUser(user);
            return Result.succeed();
        } catch (Exception e) {
            LOG.warn("[UserAuth] Failed to update user: {}", e.getMessage());
            return Result.failure("更新失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除用户
     */
    @Post
    @Mapping("/web/settings/user-auth/users/delete")
    public Result<Void> deleteUser(@Body String json) {
        try {
            ONode root = ONode.ofJson(json);
            String id = root.get("id").getString();
            
            if (Assert.isEmpty(id)) return Result.failure("用户 ID 不能为空");
            
            UserEntity user = userStore.findById(id);
            if (user == null) return Result.failure("用户不存在");
            if ("admin".equals(user.getUsername())) {
                return Result.failure("不能删除管理员账户");
            }
            
            userStore.deleteUser(id);
            return Result.succeed();
        } catch (Exception e) {
            LOG.warn("[UserAuth] Failed to delete user: {}", e.getMessage());
            return Result.failure("删除失败: " + e.getMessage());
        }
    }
}
