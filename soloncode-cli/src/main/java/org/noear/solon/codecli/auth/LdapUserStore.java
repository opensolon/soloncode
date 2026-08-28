package org.noear.solon.codecli.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.text.MessageFormat;
import java.util.*;

/**
 * LDAP 用户存储 - 通过 LDAP 服务器进行用户认证
 * 
 * @author noear 2026/8/23 created
 */
public class LdapUserStore implements UserStore {
    private static final Logger LOG = LoggerFactory.getLogger(LdapUserStore.class);
    
    private UserAuthConfig config;
    private final Map<String, UserEntity> cacheMap = new LinkedHashMap<>();
    
    @Override
    public void init(UserAuthConfig config) throws Exception {
        this.config = config;
        if (config.getLdapUrl() == null || config.getLdapUrl().isEmpty()) {
            throw new IllegalArgumentException("LDAP URL 未配置");
        }
        LOG.info("[LdapUserStore] Initialized with URL: {}", config.getLdapUrl());
    }
    
    @Override
    public UserEntity authenticate(String username, String password) {
        if (username == null || password == null || password.isEmpty()) return null;
        
        try {
            // 1. 先用管理员账号绑定，搜索用户 DN
            Hashtable<String, String> env = createEnv(config.getLdapAdminDn(), config.getLdapAdminPassword());
            LdapContext adminCtx = new InitialLdapContext(env, null);
            
            String userDn = findUserDn(adminCtx, username);
            adminCtx.close();
            
            if (userDn == null) {
                LOG.warn("[LdapUserStore] User not found: {}", username);
                return null;
            }
            
            // 2. 用用户 DN 绑定验证密码
            Hashtable<String, String> userEnv = createEnv(userDn, password);
            LdapContext userCtx = new InitialLdapContext(userEnv, null);
            userCtx.close();
            
            // 3. 认证成功，创建用户实体
            UserEntity user = new UserEntity(UUID.randomUUID().toString(), username, username);
            user.setRole("user");
            user.setEnabled(true);
            
            // 缓存查询结果
            cacheMap.put(username, user);
            
            return user;
        } catch (NamingException e) {
            LOG.warn("[LdapUserStore] Authentication failed for {}: {}", username, e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.warn("[LdapUserStore] Error authenticating {}: {}", username, e.getMessage());
            return null;
        }
    }
    
    @Override
    public UserEntity findByUsername(String username) {
        if (cacheMap.containsKey(username)) {
            return cacheMap.get(username);
        }
        
        // 从 LDAP 搜索
        try {
            Hashtable<String, String> env = createEnv(config.getLdapAdminDn(), config.getLdapAdminPassword());
            LdapContext ctx = new InitialLdapContext(env, null);
            String userDn = findUserDn(ctx, username);
            ctx.close();
            
            if (userDn != null) {
                UserEntity user = new UserEntity(UUID.randomUUID().toString(), username, username);
                user.setRole("user");
                user.setEnabled(true);
                cacheMap.put(username, user);
                return user;
            }
        } catch (Exception e) {
            LOG.warn("[LdapUserStore] Error finding user {}: {}", username, e.getMessage());
        }
        return null;
    }
    
    @Override
    public UserEntity findById(String id) {
        for (UserEntity user : cacheMap.values()) {
            if (user.getId().equals(id)) return user;
        }
        return null;
    }
    
    @Override
    public List<UserEntity> listUsers() {
        return new ArrayList<>(cacheMap.values());
    }
    
    @Override
    public UserEntity createUser(UserEntity user) throws Exception {
        throw new UnsupportedOperationException("LDAP 模式不支持创建用户");
    }
    
    @Override
    public UserEntity updateUser(UserEntity user) throws Exception {
        throw new UnsupportedOperationException("LDAP 模式不支持更新用户");
    }
    
    @Override
    public void deleteUser(String id) throws Exception {
        throw new UnsupportedOperationException("LDAP 模式不支持删除用户");
    }
    
    @Override
    public String getType() {
        return "ldap";
    }
    
    private Hashtable<String, String> createEnv(String principal, String credentials) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.getLdapUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        
        if (principal != null) env.put(Context.SECURITY_PRINCIPAL, principal);
        if (credentials != null) env.put(Context.SECURITY_CREDENTIALS, credentials);
        
        if (config.isLdapSsl()) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
        }
        
        return env;
    }
    
    private String findUserDn(LdapContext ctx, String username) throws NamingException {
        String filter = MessageFormat.format(
            config.getLdapUserFilter() != null ? config.getLdapUserFilter() : "(uid={0})",
            username
        );
        
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{"dn"});
        
        NamingEnumeration<SearchResult> results = ctx.search(
            config.getLdapBaseDn() != null ? config.getLdapBaseDn() : "",
            filter,
            controls
        );
        
        if (results.hasMore()) {
            SearchResult result = results.next();
            return result.getNameInNamespace();
        }
        
        return null;
    }
}
