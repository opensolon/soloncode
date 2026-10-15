package org.noear.solon.codecli.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSessionConcurrencyTest {
    @TempDir Path temp;

    @Test
    void frequentValidationDoesNotRewriteSessionFile() throws Exception {
        UserAuthConfig config = new UserAuthConfig();
        UserSessionManager manager = new UserSessionManager(temp);
        manager.init(config);
        String token = manager.createSession(new UserEntity("1", "admin", "Admin")).getToken();
        Path sessions = temp.resolve(".soloncode/auth/sessions.json");
        byte[] before = Files.readAllBytes(sessions);

        assertNotNull(manager.getSession(token));

        assertArrayEquals(before, Files.readAllBytes(sessions));
    }

    @Test
    void concurrentInstancesDoNotOverwriteEachOthersSessions() throws Exception {
        UserAuthConfig config = new UserAuthConfig();
        UserSessionManager first = new UserSessionManager(temp);
        UserSessionManager second = new UserSessionManager(temp);
        first.init(config);
        second.init(config);
        UserEntity user = new UserEntity("1", "admin", "Admin");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<String> tokens = new ArrayList<>();
        Object tokensLock = new Object();
        try {
            for (int i = 0; i < 20; i++) {
                final UserSessionManager manager = i % 2 == 0 ? first : second;
                executor.submit(() -> {
                    try {
                        start.await();
                        String token = manager.createSession(user).getToken();
                        synchronized (tokensLock) {
                            tokens.add(token);
                        }
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(20, first.getActiveSessionCount());
        for (String token : tokens) assertNotNull(second.getSession(token));
        assertEquals(20, second.listActiveSessions().size());
    }
}
