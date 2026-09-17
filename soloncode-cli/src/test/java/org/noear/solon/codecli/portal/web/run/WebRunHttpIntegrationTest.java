package org.noear.solon.codecli.portal.web.run;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.SimpleSolonApp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过真实 HTTP 监听端口验证 /web/run 的 SSE 与取消契约。
 */
class WebRunHttpIntegrationTest {
    private static SimpleSolonApp app;
    private static int port;
    private HttpURLConnection activeConnection;
    private InputStream activeStream;

    @BeforeAll
    static void startServer() throws Throwable {
        app = WebRunTestApp.startServer();
        port = WebRunTestApp.port;
        assertTrue(port > 0);
    }

    @AfterAll
    static void stopServer() {
        if (app != null) {
            app.stop();
        }
        System.clearProperty("soloncode.run.token");
        System.clearProperty("soloncode.wskey");
        WebRunTestApp.PROCESSES.clear();
    }

    @AfterEach
    void closeConnection() throws Exception {
        if (activeStream != null) {
            activeStream.close();
        }
        if (activeConnection != null) {
            activeConnection.disconnect();
        }
        activeStream = null;
        activeConnection = null;
        waitUntil(() -> RunSessionRegistry.getInstance().activeCount() == 0, 3000L);
    }

    @Test
    void firstSseEventArrivesBeforeChildCompletes() throws Exception {
        String sessionId = "http-latency-" + System.nanoTime();
        long startedAt = System.nanoTime();
        BufferedReader reader = openStream(sessionId);

        String data = readNextData(reader);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertNotNull(data);
        assertTrue(data.contains("\"type\":\"system\""));
        assertTrue(elapsedMillis < 1000L,
                "首条 SSE 应逐行抵达，而不是等待 1.5 秒的子进程终态，实际 " + elapsedMillis + "ms");
        assertTrue(RunSessionRegistry.getInstance().isActive(sessionId));
    }

    @Test
    void interruptEndpointDestroysActiveChildAndReleasesSession() throws Exception {
        String sessionId = "http-interrupt-" + System.nanoTime();
        BufferedReader reader = openStream(sessionId);
        assertNotNull(readNextData(reader));

        WebRunTestApp.StreamingFakeProcess process = WebRunTestApp.process(sessionId);
        assertNotNull(process);
        assertEquals(202, postInterrupt(sessionId));
        assertTrue(process.awaitDestroyed(2, TimeUnit.SECONDS));
        waitUntil(() -> !RunSessionRegistry.getInstance().isActive(sessionId), 3000L);
        assertFalse(RunSessionRegistry.getInstance().isActive(sessionId));
    }

    @Test
    void clientDisconnectDestroysChildAndReleasesSession() throws Exception {
        String sessionId = "http-disconnect-" + System.nanoTime();
        BufferedReader reader = openStream(sessionId);
        assertNotNull(readNextData(reader));

        WebRunTestApp.StreamingFakeProcess process = WebRunTestApp.process(sessionId);
        assertNotNull(process);
        activeStream.close();
        activeConnection.disconnect();
        activeStream = null;
        activeConnection = null;

        assertTrue(process.awaitDestroyed(3, TimeUnit.SECONDS),
                "客户端断开后，下一次 SSE 写入失败必须销毁子进程");
        waitUntil(() -> !RunSessionRegistry.getInstance().isActive(sessionId), 3000L);
        assertFalse(RunSessionRegistry.getInstance().isActive(sessionId));
    }

    private BufferedReader openStream(String sessionId) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/web/run");
        activeConnection = (HttpURLConnection) url.openConnection();
        activeConnection.setRequestMethod("POST");
        activeConnection.setRequestProperty("Authorization", "Bearer " + WebRunTestApp.TOKEN);
        activeConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        activeConnection.setRequestProperty("Accept", "text/event-stream");
        activeConnection.setDoOutput(true);
        activeConnection.setReadTimeout(5000);
        String body = "{\"prompt\":\"test\",\"options\":{\"output_format\":\"stream-json\","
                + "\"session_id\":\"" + sessionId + "\"}}";
        try (OutputStream output = activeConnection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, activeConnection.getResponseCode());
        assertTrue(activeConnection.getContentType().startsWith("text/event-stream"));
        activeStream = activeConnection.getInputStream();
        return new BufferedReader(new InputStreamReader(activeStream, StandardCharsets.UTF_8));
    }

    private int postInterrupt(String sessionId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/web/run/interrupt").openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + WebRunTestApp.TOKEN);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(("{\"session_id\":\"" + sessionId + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        consume(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        return status;
    }

    private static String readNextData(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                return line.substring("data:".length()).trim();
            }
        }
        return null;
    }

    private static void consume(InputStream input) throws IOException {
        if (input == null) {
            return;
        }
        byte[] buffer = new byte[256];
        try (InputStream stream = input) {
            while (stream.read(buffer) >= 0) {
                // 完整消费响应，确保连接可回收。
            }
        }
    }

    private static void waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!check.done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean done();
    }
}
