package org.noear.solon.codecli.auth;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可热切换的用户存储代理。
 *
 * <p>控制器和登录逻辑始终持有该稳定引用，认证配置切换 file/LDAP 时只替换内部委托，
 * 避免出现配置已经保存、运行时仍使用旧认证源的问题。</p>
 */
public class ReloadableUserStore implements UserStore {
    private final AtomicReference<UserStore> delegateRef;

    public ReloadableUserStore(UserStore initialStore) {
        if (initialStore == null) {
            throw new IllegalArgumentException("初始用户存储不能为空");
        }
        this.delegateRef = new AtomicReference<>(initialStore);
    }

    public UserStore current() {
        return delegateRef.get();
    }

    public void replace(UserStore nextStore) {
        if (nextStore == null) {
            throw new IllegalArgumentException("新用户存储不能为空");
        }
        delegateRef.set(nextStore);
    }

    @Override
    public void init(UserAuthConfig config) throws Exception {
        current().init(config);
    }

    @Override
    public UserEntity authenticate(String username, String password) {
        return current().authenticate(username, password);
    }

    @Override
    public UserEntity findByUsername(String username) {
        return current().findByUsername(username);
    }

    @Override
    public UserEntity findById(String id) {
        return current().findById(id);
    }

    @Override
    public List<UserEntity> listUsers() {
        return current().listUsers();
    }

    @Override
    public UserEntity createUser(UserEntity user) throws Exception {
        return current().createUser(user);
    }

    @Override
    public UserEntity updateUser(UserEntity user) throws Exception {
        return current().updateUser(user);
    }

    @Override
    public void deleteUser(String id) throws Exception {
        current().deleteUser(id);
    }

    @Override
    public String getType() {
        return current().getType();
    }

    @Override
    public boolean supportsLocalUserManagement() {
        return current().supportsLocalUserManagement();
    }
}
