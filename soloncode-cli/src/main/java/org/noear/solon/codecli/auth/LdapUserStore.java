package org.noear.solon.codecli.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * LDAP 用户存储 - 通过 LDAP 服务器进行用户认证。
 */
public class LdapUserStore implements UserStore {
    private static final Logger LOG = LoggerFactory.getLogger(LdapUserStore.class);

    private UserAuthConfig config;
    private final ConcurrentMap<String, UserEntity> cacheMap = new ConcurrentHashMap<>();

    @Override
    public void init(UserAuthConfig config) {
        validateConfig(config);
        this.config = config;
        LOG.info("[LdapUserStore] Initialized with URL: {}", config.getLdapUrl());
    }

    public void testConnection() throws NamingException {
        LdapContext ctx = null;
        NamingEnumeration<SearchResult> results = null;
        try {
            ctx = openContext(config.getLdapAdminDn(), config.getLdapAdminPassword());
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.OBJECT_SCOPE);
            controls.setCountLimit(1);
            controls.setTimeLimit(config.getLdapReadTimeoutMillis());
            controls.setReturningAttributes(new String[]{"objectClass"});
            results = ctx.search(defaultString(config.getLdapBaseDn()), "(objectClass=*)", controls);
            if (results.hasMore()) {
                results.next();
            }
        } finally {
            closeQuietly(results);
            closeQuietly(ctx);
        }
    }

    @Override
    public UserEntity authenticate(String username, String password) {
        if (isEmpty(username) || isEmpty(password)) {
            return null;
        }

        LdapContext adminCtx = null;
        LdapContext userCtx = null;
        try {
            adminCtx = openContext(config.getLdapAdminDn(), config.getLdapAdminPassword());
            LdapUserRecord record = findUser(adminCtx, username);
            if (record == null) {
                LOG.warn("[LdapUserStore] User not found or not unique: {}", username);
                return null;
            }

            userCtx = openContext(record.dn, password);
            UserEntity user = toUserEntity(username, record);
            cacheMap.put(normalizeUsername(username), user);
            return user;
        } catch (NamingException e) {
            LOG.warn("[LdapUserStore] Authentication failed for {}: {}", username, e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.warn("[LdapUserStore] Error authenticating {}: {}", username, e.getMessage());
            return null;
        } finally {
            closeQuietly(userCtx);
            closeQuietly(adminCtx);
        }
    }

    @Override
    public UserEntity findByUsername(String username) {
        if (isEmpty(username)) {
            return null;
        }
        String key = normalizeUsername(username);
        UserEntity cached = cacheMap.get(key);
        if (cached != null) {
            return cached;
        }

        LdapContext ctx = null;
        try {
            ctx = openContext(config.getLdapAdminDn(), config.getLdapAdminPassword());
            LdapUserRecord record = findUser(ctx, username);
            if (record == null) {
                return null;
            }
            UserEntity user = toUserEntity(username, record);
            UserEntity existing = cacheMap.putIfAbsent(key, user);
            return existing == null ? user : existing;
        } catch (Exception e) {
            LOG.warn("[LdapUserStore] Error finding user {}: {}", username, e.getMessage());
            return null;
        } finally {
            closeQuietly(ctx);
        }
    }

    @Override
    public UserEntity findById(String id) {
        if (id == null) {
            return null;
        }
        for (UserEntity user : cacheMap.values()) {
            if (id.equals(user.getId())) {
                return user;
            }
        }
        return null;
    }

    @Override
    public List<UserEntity> listUsers() {
        return new ArrayList<>(cacheMap.values());
    }

    @Override
    public UserEntity createUser(UserEntity user) {
        throw new UnsupportedOperationException("LDAP 模式下用户由目录服务管理");
    }

    @Override
    public UserEntity updateUser(UserEntity user) {
        throw new UnsupportedOperationException("LDAP 模式下用户由目录服务管理");
    }

    @Override
    public void deleteUser(String id) {
        throw new UnsupportedOperationException("LDAP 模式下用户由目录服务管理");
    }

    @Override
    public String getType() {
        return "ldap";
    }

    @Override
    public boolean supportsLocalUserManagement() {
        return false;
    }

    static void validateConfig(UserAuthConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("LDAP 配置不能为空");
        }
        if (isEmpty(config.getLdapUrl())) {
            throw new IllegalArgumentException("LDAP URL 未配置");
        }
        if (isEmpty(config.getLdapBaseDn())) {
            throw new IllegalArgumentException("LDAP 搜索基 DN 未配置");
        }
        if (isEmpty(config.getLdapUserFilter()) || !config.getLdapUserFilter().contains("{0}")) {
            throw new IllegalArgumentException("LDAP 用户过滤器必须包含 {0} 占位符");
        }
        if (isEmpty(config.getLdapAdminGroupDn())) {
            throw new IllegalArgumentException("LDAP 管理员组 DN 未配置");
        }
        if (config.getLdapConnectTimeoutMillis() <= 0 || config.getLdapReadTimeoutMillis() <= 0) {
            throw new IllegalArgumentException("LDAP 超时时间必须大于 0");
        }
    }

    static String escapeFilterValue(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': out.append("\\5c"); break;
                case '*': out.append("\\2a"); break;
                case '(': out.append("\\28"); break;
                case ')': out.append("\\29"); break;
                case '\u0000': out.append("\\00"); break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }

    private LdapContext openContext(String principal, String credentials) throws NamingException {
        return new InitialLdapContext(createEnv(principal, credentials), null);
    }

    private Hashtable<String, Object> createEnv(String principal, String credentials) {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.getLdapUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(config.getLdapConnectTimeoutMillis()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(config.getLdapReadTimeoutMillis()));

        if (!isEmpty(principal)) {
            env.put(Context.SECURITY_PRINCIPAL, principal);
        }
        if (credentials != null) {
            env.put(Context.SECURITY_CREDENTIALS, credentials);
        }
        if (config.isLdapSsl()) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
        }
        return env;
    }

    private LdapUserRecord findUser(LdapContext ctx, String username) throws NamingException {
        String filter = config.getLdapUserFilter().replace("{0}", escapeFilterValue(username));
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(2);
        controls.setTimeLimit(config.getLdapReadTimeoutMillis());
        controls.setReturningAttributes(returningAttributes());

        NamingEnumeration<SearchResult> results = null;
        try {
            results = ctx.search(config.getLdapBaseDn(), filter, controls);
            if (!results.hasMore()) {
                return null;
            }
            SearchResult result = results.next();
            if (results.hasMore()) {
                LOG.warn("[LdapUserStore] LDAP filter returned multiple users for {}", username);
                return null;
            }
            return new LdapUserRecord(result.getNameInNamespace(), result.getAttributes());
        } finally {
            closeQuietly(results);
        }
    }

    private String[] returningAttributes() {
        List<String> names = new ArrayList<>();
        addAttribute(names, config.getLdapDisplayNameAttribute());
        addAttribute(names, config.getLdapEmailAttribute());
        addAttribute(names, config.getLdapGroupAttribute());
        return names.toArray(new String[names.size()]);
    }

    private void addAttribute(List<String> names, String value) {
        if (!isEmpty(value) && !names.contains(value)) {
            names.add(value);
        }
    }

    private UserEntity toUserEntity(String username, LdapUserRecord record) throws NamingException {
        String normalizedDn = record.dn.trim().toLowerCase(Locale.ROOT);
        String id = UUID.nameUUIDFromBytes(normalizedDn.getBytes(StandardCharsets.UTF_8)).toString();
        String displayName = attributeValue(record.attributes, config.getLdapDisplayNameAttribute());
        UserEntity user = new UserEntity(id, username, isEmpty(displayName) ? username : displayName);
        user.setEmail(attributeValue(record.attributes, config.getLdapEmailAttribute()));
        user.setRole(hasAdminGroup(record.attributes) ? "admin" : "user");
        user.setEnabled(true);
        user.getAttributes().put("ldapDn", record.dn);
        return user;
    }

    private boolean hasAdminGroup(Attributes attributes) throws NamingException {
        Attribute groupAttribute = attributes.get(config.getLdapGroupAttribute());
        if (groupAttribute == null) {
            return false;
        }
        String expected = config.getLdapAdminGroupDn().trim();
        NamingEnumeration<?> values = null;
        try {
            values = groupAttribute.getAll();
            while (values.hasMore()) {
                Object value = values.next();
                if (value != null && expected.equalsIgnoreCase(String.valueOf(value).trim())) {
                    return true;
                }
            }
            return false;
        } finally {
            closeQuietly(values);
        }
    }

    private String attributeValue(Attributes attributes, String attributeName) throws NamingException {
        if (isEmpty(attributeName)) {
            return null;
        }
        Attribute attribute = attributes.get(attributeName);
        if (attribute == null || attribute.size() == 0) {
            return null;
        }
        Object value = attribute.get();
        return value == null ? null : String.valueOf(value);
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void closeQuietly(NamingEnumeration<?> values) {
        if (values != null) {
            try {
                values.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(LdapContext context) {
        if (context != null) {
            try {
                context.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class LdapUserRecord {
        private final String dn;
        private final Attributes attributes;

        private LdapUserRecord(String dn, Attributes attributes) {
            this.dn = dn;
            this.attributes = attributes;
        }
    }
}
