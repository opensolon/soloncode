package org.noear.solon.codecli.portal.web.run;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /web/run 流式子进程生命周期测试。
 */
class RunControllerStreamTest {

    @Test
    void consumerFailureDestroysProcessAndStopsStreaming() {
        FakeProcess process = new FakeProcess("first\nsecond\n", 143);
        RunController.ProcessAndOutput out = new RunController.ProcessAndOutput();
        AtomicInteger consumed = new AtomicInteger();

        Exception error = assertThrows(Exception.class, () ->
                RunController.consumeProcess(process, null, line -> {
                    consumed.incrementAndGet();
                    throw new Exception("client disconnected");
                }, out, new StringBuilder()));

        assertEquals("client disconnected", error.getMessage());
        assertEquals(1, consumed.get(), "发送失败后不得继续消费后续 JSONL 行");
        assertTrue(process.destroyed.get(), "SSE 断开必须销毁仍在运行的子进程");
        assertEquals(143, out.exitCode);
    }

    @Test
    void linesAreForwardedImmediatelyAndTerminalIsDetected() throws Exception {
        String init = "{\"type\":\"system\",\"session_id\":\"sdk-1\"}";
        String result = "{\"type\":\"result\",\"session_id\":\"sdk-1\"}";
        FakeProcess process = new FakeProcess(init + "\n" + result + "\n", 0);
        RunController.ProcessAndOutput out = new RunController.ProcessAndOutput();
        StringBuilder forwarded = new StringBuilder();

        RunController.consumeProcess(process, null,
                line -> forwarded.append(line).append('\n'), out, new StringBuilder());

        assertEquals(init + "\n" + result + "\n", forwarded.toString());
        assertEquals("sdk-1", out.lastSessionId);
        assertTrue(out.resultSent);
        assertEquals(0, out.exitCode);
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final int exitCode;
        private final AtomicBoolean destroyed = new AtomicBoolean();

        private FakeProcess(String stdout, int exitCode) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }
    }
}
