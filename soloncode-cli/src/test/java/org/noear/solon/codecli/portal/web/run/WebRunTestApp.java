package org.noear.solon.codecli.portal.web.run;

import org.noear.solon.SimpleSolonApp;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.codecli.workspace.WorkspaceMeta;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /web/run 网络集成测试的最小 Solon 应用。
 */
public class WebRunTestApp {
    static final String TOKEN = "web-run-integration-token";
    static final Map<String, StreamingFakeProcess> PROCESSES = new ConcurrentHashMap<>();
    static volatile int port;

    static SimpleSolonApp startServer() throws Throwable {
        System.setProperty("soloncode.run.token", TOKEN);
        System.setProperty("soloncode.wskey", "web-run-test");
        port = findAvailablePort();
        SimpleSolonApp app = new SimpleSolonApp(WebRunTestApp.class,
                "--server.port=" + port, "--solon.app.name=web-run-test", "--soloncode.wskey=web-run-test")
                .globalize(true);
        app.enableScanning(false);
        app.enableHttp(true);
        return app.start(current -> {
            RunRequestService requestService = new RunRequestService(null) {
                @Override
                protected java.util.List<WorkspaceMeta> listKnownWorkspaces() {
                    return Collections.singletonList(new WorkspaceMeta(
                            "test-workspace", "launch", System.getProperty("user.dir"), 0L, true));
                }
            };
            RunController controller = new RunController(null, requestService,
                    new RunTokenService(), req -> {
                StreamingFakeProcess process = new StreamingFakeProcess(req.sessionId);
                PROCESSES.put(req.sessionId, process);
                process.start();
                return process;
            });
            BeanWrap bean = current.context().wrapAndPut(RunController.class, controller);
            current.router().add(bean);
        });
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static StreamingFakeProcess process(String sessionId) {
        return PROCESSES.get(sessionId);
    }

    static final class StreamingFakeProcess extends Process {
        private final String sessionId;
        private final PipedInputStream stdout;
        private final PipedOutputStream producer;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final AtomicBoolean destroyRequested = new AtomicBoolean();
        private volatile int exitCode = 0;

        StreamingFakeProcess(String sessionId) throws IOException {
            this.sessionId = sessionId;
            this.stdout = new PipedInputStream(128 * 1024);
            this.producer = new PipedOutputStream(stdout);
        }

        void start() {
            Thread thread = new Thread(() -> {
                try {
                    writeLine("{\"type\":\"system\",\"session_id\":\"" + sessionId + "\"}");
                    long deadline = System.currentTimeMillis() + 1500L;
                    int tick = 0;
                    while (!destroyRequested.get() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100L);
                        if (!destroyRequested.get()) {
                            writeLine("{\"type\":\"assistant\",\"content\":\"tick-" + (++tick) + "\"}");
                        }
                    }
                    if (!destroyRequested.get()) {
                        writeLine("{\"type\":\"result\",\"session_id\":\"" + sessionId
                                + "\",\"result\":\"ok\"}");
                    }
                } catch (Exception ignored) {
                    // 客户端断开或 interrupt 会关闭管道。
                } finally {
                    try {
                        producer.close();
                    } catch (IOException ignored) {
                    }
                    finished.countDown();
                }
            }, "web-run-fake-child-" + sessionId);
            thread.setDaemon(true);
            thread.start();
        }

        boolean awaitDestroyed(long timeout, TimeUnit unit) throws InterruptedException {
            return destroyed.await(timeout, unit);
        }

        private void writeLine(String line) throws IOException {
            producer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            producer.flush();
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                }
            };
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
        public int waitFor() throws InterruptedException {
            finished.await();
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (finished.getCount() != 0) {
                throw new IllegalThreadStateException("process is still running");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            if (destroyRequested.compareAndSet(false, true)) {
                exitCode = 143;
                destroyed.countDown();
                try {
                    producer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
