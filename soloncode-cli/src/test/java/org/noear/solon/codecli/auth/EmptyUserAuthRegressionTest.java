package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.FilterChain;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 升级后用户为空时，工作台和管理台不得陷入无法登录的循环。 */
class EmptyUserAuthRegressionTest {
    @Test
    void emptyFileStoreNeverRedirectsToLogin() throws Throwable {
        for (boolean enabled : new boolean[]{false, true}) {
            UserAuthConfig config = new UserAuthConfig();
            config.setEnabled(enabled);
            UserStore store = mock(UserStore.class);
            when(store.supportsLocalUserManagement()).thenReturn(true);
            when(store.listUsers()).thenReturn(Collections.emptyList());
            UserAuthFilter filter = filter(config, store);
            for (String path : new String[]{"/", "/web/admin", "/web/admin/modules"}) {
                Context ctx = mock(Context.class);
                FilterChain chain = mock(FilterChain.class);
                when(ctx.path()).thenReturn(path);
                when(ctx.remoteIp()).thenReturn("127.0.0.1");
                filter.doFilter(ctx, chain);
                verify(chain).doFilter(ctx);
                verify(ctx, never()).redirect(anyString());
            }
            assertFalse(AuthRuntimeState.hasNoLocalUsers(config, mockLdapStore()),
                    "LDAP 目录不能靠本地列表判断是否有用户");
            assertEquals(false, new UserLoginController(store, null, config).me(null).getData().get("authRequired"));
            assertEquals(false, new UserLoginController(store, null, config).me(null).getData().get("hasUsers"));
        }
    }

    @Test
    void existingUsersAndStoreErrorsAreNotTreatedAsEmpty() throws Throwable {
        UserAuthConfig config = new UserAuthConfig();
        config.setEnabled(true);
        UserStore store = mock(UserStore.class);
        when(store.supportsLocalUserManagement()).thenReturn(true);
        when(store.listUsers()).thenReturn(Collections.singletonList(new UserEntity("1", "admin", "admin")));
        assertFalse(AuthRuntimeState.hasNoLocalUsers(config, store));

        Context ctx = mock(Context.class);
        FilterChain chain = mock(FilterChain.class);
        when(ctx.path()).thenReturn("/");
        when(ctx.remoteIp()).thenReturn("127.0.0.1");
        filter(config, store).doFilter(ctx, chain);
        verify(ctx).redirect("/login");
        verify(chain, never()).doFilter(ctx);

        when(store.listUsers()).thenThrow(new IllegalStateException("broken users"));
        assertFalse(AuthRuntimeState.hasNoLocalUsers(config, store));
    }

    private static UserStore mockLdapStore() {
        UserStore store = mock(UserStore.class);
        when(store.supportsLocalUserManagement()).thenReturn(false);
        return store;
    }

    private static UserAuthFilter filter(UserAuthConfig config, UserStore store) throws Exception {
        UserAuthFilter filter = new UserAuthFilter();
        inject(filter, "userAuthConfig", config);
        inject(filter, "userStore", store);
        inject(filter, "sessionManager", mock(UserSessionManager.class));
        return filter;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
