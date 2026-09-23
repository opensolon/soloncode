package org.noear.solon.codecli.auth;

/**
 * 用户存储工厂。仅支持 file 与 ldap；历史 database 配置按 file 迁移。
 */
public final class UserStoreFactory {
    private UserStoreFactory() {
    }

    public static String normalizeMode(String mode) {
        return "ldap".equals(mode) ? "ldap" : "file";
    }

    public static UserStore create(UserAuthConfig config) throws Exception {
        String mode = normalizeMode(config == null ? null : config.getMode());
        UserStore store = "ldap".equals(mode) ? new LdapUserStore() : new FileUserStore();
        store.init(config);
        return store;
    }
}
