package org.noear.solon.codecli.auth;

import java.util.List;

/**
 * 认证运行时状态。
 *
 * <p>配置开关表示用户意图，当前是否存在用户才决定是否有可执行的登录验证。
 * 两者不能混用：例如升级后可能留下 enabled=true，但 users.json 已为空。</p>
 */
final class AuthRuntimeState {
    private AuthRuntimeState() {
    }

    /**
     * 仅对本地 file 用户库判断“没有用户”。LDAP 等外部目录无法通过 listUsers
     * 判断目录是否为空，必须继续按已配置的认证策略处理。
     */
    static boolean hasNoLocalUsers(UserAuthConfig config, UserStore userStore) {
        if (config == null || userStore == null
                || !"file".equals(UserStoreFactory.normalizeMode(config.getMode()))
                || !userStore.supportsLocalUserManagement()) {
            return false;
        }
        try {
            List<UserEntity> users = userStore.listUsers();
            return users != null && users.isEmpty();
        } catch (Exception ignored) {
            // 读取失败不是“没有用户”，必须保持原有保护，避免数据损坏导致匿名绕过。
            return false;
        }
    }
}
