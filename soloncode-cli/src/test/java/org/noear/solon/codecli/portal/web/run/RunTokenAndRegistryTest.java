package org.noear.solon.codecli.portal.web.run;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RunTokenService / RunSessionRegistry 测试
 *
 * @author noear 2026/8/28 created
 */
class RunTokenAndRegistryTest {

    // ===== token =====

    @Test
    void generatedTokenIsUrlSafeBase64Of32Bytes() {
        String token = RunTokenService.generateToken();
        assertEquals(43, token.length()); // 32 bytes → 256 bits → 43 chars (no padding)
        assertTrue(token.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void sha256IsDeterministic() {
        assertArrayEquals(RunTokenService.sha256("abc"), RunTokenService.sha256("abc"));
        assertFalse(Arrays.equals(RunTokenService.sha256("abc"), RunTokenService.sha256("abd")));
    }

    // ===== registry =====

    @Test
    void registerPreventsDuplicateSession() {
        RunSessionRegistry reg = RunSessionRegistry.getInstance();
        String sid = "test-" + System.nanoTime();

        RunSessionRegistry.RunHandle h1 = reg.tryRegister(sid);
        assertNotNull(h1);
        assertNull(reg.tryRegister(sid)); // 二次登记被拒 → 409

        // 旧句柄完成时不得移除随后登记的新一轮任务。
        RunSessionRegistry.RunHandle stale = new RunSessionRegistry.RunHandle(sid);
        reg.unregister(sid, stale);
        assertTrue(reg.isActive(sid));

        reg.unregister(sid, h1);
        assertFalse(reg.isActive(sid));
        RunSessionRegistry.RunHandle h2 = reg.tryRegister(sid);
        assertNotNull(h2); // 注销后可再登记
        reg.unregister(sid, h2);
    }

    @Test
    void killPendingBeforeAttach() {
        RunSessionRegistry reg = RunSessionRegistry.getInstance();
        String sid = "test-" + System.nanoTime();

        RunSessionRegistry.RunHandle h = reg.tryRegister(sid);
        assertNotNull(h);

        // attach 前到达的 interrupt：置 killPending（此处 cancel 走 handle 内部，不经 registry 查找）
        h.cancel();
        assertTrue(h.isKillPending());

        // 模拟回填：假 Process（destroy 是空操作）
        Process fake = new Process() {
            @Override public OutputStream getOutputStream() { return new OutputStream() { @Override public void write(int b) {} }; }
            @Override public InputStream getInputStream() { return new InputStream() { @Override public int read() { return -1; } }; }
            @Override public InputStream getErrorStream() { return new InputStream() { @Override public int read() { return -1; } }; }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() {}
        };
        h.attach(fake); // 不应抛异常；真实进程场景下会立即 destroy

        reg.unregister(sid, h);
    }

    @Test
    void interruptUnknownSessionReturnsFalse() {
        assertFalse(RunSessionRegistry.getInstance().interrupt("no-such-session"));
    }
}
