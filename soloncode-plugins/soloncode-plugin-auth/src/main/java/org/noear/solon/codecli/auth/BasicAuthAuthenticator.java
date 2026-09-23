package org.noear.solon.codecli.auth;

import org.noear.solon.core.handle.Context;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户管理体系下的无状态 Basic Auth 兼容入口。
 * 不创建服务端会话，只在当前请求中返回已认证用户。
 */
public final class BasicAuthAuthenticator {
    private static final String PREFIX = "Basic ";
    private static final LoginLimiter LIMITER = new LoginLimiter();

    private BasicAuthAuthenticator() {
    }

    public static boolean isBasic(Context ctx) {
        String value = ctx == null ? null : ctx.header("Authorization");
        return value != null && value.trim().regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * 认证 Basic 请求。没有 Basic 头时返回 absent；有头但格式或凭据错误时返回 failure。
     */
    public static AuthResult authenticate(Context ctx, UserStore userStore) {
        String header = ctx == null ? null : ctx.header("Authorization");
        String ip = ctx == null || ctx.remoteIp() == null ? "unknown" : ctx.remoteIp();
        return authenticate(header, ip, userStore);
    }

    static AuthResult authenticate(String header, String ip, UserStore userStore) {
        if (header == null || !header.trim().regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return AuthResult.absent();
        }

        String encoded = header.trim().substring(PREFIX.length()).trim();
        String username = null;
        String password = null;
        try {
            String credentials = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            if (separator >= 0) {
                username = credentials.substring(0, separator);
                password = credentials.substring(separator + 1);
            }
        } catch (IllegalArgumentException ignored) {
            // 统一返回认证失败，不暴露头部格式细节。
        }

        if (!LIMITER.allow(username, ip)) return AuthResult.failure();
        if (username == null || username.isEmpty() || password == null
                || PasswordPolicy.validate(password) != null || userStore == null) {
            LIMITER.failure(username, ip);
            return AuthResult.failure();
        }

        UserEntity user = userStore.authenticate(username, password);
        if (user == null || !user.isEnabled()) {
            LIMITER.failure(username, ip);
            return AuthResult.failure();
        }

        LIMITER.success(username, ip);
        return AuthResult.success(user);
    }

    public static final class AuthResult {
        private final boolean present;
        private final UserEntity user;

        private AuthResult(boolean present, UserEntity user) {
            this.present = present;
            this.user = user;
        }

        public static AuthResult absent() { return new AuthResult(false, null); }
        public static AuthResult failure() { return new AuthResult(true, null); }
        public static AuthResult success(UserEntity user) { return new AuthResult(true, user); }
        public boolean isPresent() { return present; }
        public boolean isSuccess() { return user != null; }
        public UserEntity getUser() { return user; }
    }

    static final class LoginLimiter {
        private static final long WINDOW_MS = 60_000L;
        private static final int MAX_FAILURES = 5;
        private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

        boolean allow(String username, String ip) {
            long now = System.currentTimeMillis();
            return allowed(key("u", username), now) && allowed(key("i", ip), now);
        }

        void failure(String username, String ip) {
            record(key("u", username));
            record(key("i", ip));
        }

        void success(String username, String ip) {
            attempts.remove(key("u", username));
            attempts.remove(key("i", ip));
        }

        private boolean allowed(String key, long now) {
            Attempt attempt = attempts.get(key);
            return attempt == null || now - attempt.startedAt >= WINDOW_MS
                    || attempt.failures < MAX_FAILURES;
        }

        private void record(String key) {
            long now = System.currentTimeMillis();
            attempts.compute(key, (ignored, old) -> old == null || now - old.startedAt >= WINDOW_MS
                    ? new Attempt(now, 1) : new Attempt(old.startedAt, old.failures + 1));
        }

        private static String key(String type, String value) {
            return type + ':' + String.valueOf(value).toLowerCase(Locale.ROOT);
        }

        private static final class Attempt {
            private final long startedAt;
            private final int failures;

            private Attempt(long startedAt, int failures) {
                this.startedAt = startedAt;
                this.failures = failures;
            }
        }
    }
}
