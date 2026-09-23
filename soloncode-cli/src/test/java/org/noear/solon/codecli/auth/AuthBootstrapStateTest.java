package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** bootstrap 状态、令牌和历史数据迁移的安全回归测试。 */
class AuthBootstrapStateTest {
    @TempDir Path temp;

    @Test
    void freshStoreCreatesHashedTokenAndSuccessfulStateIsOneWay() throws Exception {
        withHome(() -> {
            String token = AuthConfigRepository.ensureBootstrapToken();
            assertNotNull(token);
            AuthConfigRepository.BootstrapState fresh = AuthConfigRepository.loadState();
            assertFalse(fresh.isInitialized());
            assertNotNull(fresh.getBootstrapTokenHash());
            assertEquals(64, fresh.getBootstrapTokenHash().length());
            assertTrue(AuthConfigRepository.verifyBootstrapToken(token));
            assertNull(AuthConfigRepository.ensureBootstrapToken(), "启动后不能再次取得明文令牌");
            AuthConfigRepository.markInitialized();
            assertTrue(AuthConfigRepository.loadState().isInitialized());
            assertFalse(AuthConfigRepository.verifyBootstrapToken(token));
            assertFalse(new String(Files.readAllBytes(AuthConfigRepository.statePath()), StandardCharsets.UTF_8)
                    .contains("bootstrapTokenHash"), "初始化完成后不应继续保存一次性令牌摘要");
        });
    }

    @Test
    void validHistoricalUserMarksStateInitializedAndMissingUsersStayClosed() throws Exception {
        withHome(() -> {
            FileUserStore store = new FileUserStore();
            store.init(new UserAuthConfig());
            UserEntity user = new UserEntity("u1", "admin", "Admin");
            user.setPasswordHash(FileUserStore.hashPassword("secret"));
            user.setRole("admin");
            user.setEnabled(true);
            store.createUser(user);
            // 模拟进程重启：已落盘的历史用户会使实例永久标记为已初始化。
            new FileUserStore().init(new UserAuthConfig());
            assertTrue(AuthConfigRepository.loadState().isInitialized());

            Files.delete(temp.resolve(".soloncode/auth/users.json"));
            assertTrue(AuthConfigRepository.loadState().isInitialized(),
                    "用户文件丢失不能重新开放匿名自举");
        });
    }

    @Test
    void damagedStateFailsClosed() throws Exception {
        withHome(() -> {
            Files.createDirectories(temp.resolve(".soloncode/auth"));
            Files.write(AuthConfigRepository.statePath(), "{\"initialized\":\"no\"}".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, AuthConfigRepository::loadState);
        });
    }

    private void withHome(Checked action) throws Exception {
        String old = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            action.run();
        } finally {
            if (old == null) System.clearProperty("user.home");
            else System.setProperty("user.home", old);
        }
    }

    private interface Checked { void run() throws Exception; }
}
