/*
 * Copyright 2025 soloncode
 * Licensed under the Apache License, Version 2.0
 */
package org.noear.soloncode.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.transport.HttpOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that unified-client HTTP options reach the actual connection. */
class SolonCodeClientHttpOptionsTest {
    private HttpServer proxyServer;
    private HttpServer originServer;
    private String proxyHost;
    private int proxyPort;
    private String originUrl;
    private final List<String> proxyRequestLines = new CopyOnWriteArrayList<>();
    private final List<String> proxyAuthHeaders = new CopyOnWriteArrayList<>();
    private final List<String> tenantHeaders = new CopyOnWriteArrayList<>();
    private final List<String> originHits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServers() throws IOException {
        proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxyHost = proxyServer.getAddress().getHostName();
        proxyPort = proxyServer.getAddress().getPort();
        originUrl = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/web/run";
        proxyServer.createContext("/", exchange -> {
            proxyRequestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            proxyAuthHeaders.add(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            tenantHeaders.add(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            readBody(exchange);
            respondWithResultSse(exchange, "via-proxy", "px-client-1");
        });
        originServer.createContext("/", exchange -> {
            originHits.add(exchange.getRequestURI().toString());
            tenantHeaders.add(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            readBody(exchange);
            respondWithResultSse(exchange, "direct", "direct-1");
        });
        proxyServer.start();
        originServer.start();
    }

    @AfterEach
    void stopServers() {
        proxyServer.stop(0);
        originServer.stop(0);
    }

    @Test
    void callAppliesProxyAuthenticationAndCustomHeader() {
        HttpOptions options = HttpOptions.proxy(proxyHost, proxyPort)
                .proxyAuth("corp-user", "corp-pass")
                .header("X-Tenant-Id", "t-1024");
        try (SolonCodeClient client = httpClient(options)) {
            assertThat(client.prompt("hi").call().messages()).hasSize(1);
        }
        assertThat(proxyRequestLines).singleElement().asString().startsWith("POST http://").endsWith("/web/run");
        assertThat(originHits).isEmpty();
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("corp-user:corp-pass".getBytes(StandardCharsets.UTF_8));
        assertThat(proxyAuthHeaders).containsOnly(expected);
        assertThat(tenantHeaders).containsOnly("t-1024");
    }

    @Test
    void streamWithoutHttpOptionsConnectsDirectly() {
        try (SolonCodeClient client = httpClient(null)) {
            client.prompt("hi").stream().blockLast(Duration.ofSeconds(20));
        }
        assertThat(proxyRequestLines).isEmpty();
        assertThat(originHits).hasSize(1);
        assertThat(tenantHeaders).containsOnlyNulls();
    }

    @Test
    void rejectsHttpOptionsOnStdioTransportWhenSessionIsCreated() {
        try (SolonCodeClient client = SolonCodeClient.builder()
                .workingDirectory(Paths.get("."))
                .httpOptions(HttpOptions.proxy("proxy.invalid", 3128))
                .build()) {
            assertThatThrownBy(client::getOptions)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http transport");
        }
    }

    private SolonCodeClient httpClient(HttpOptions options) {
        SolonCodeClient.Builder builder = SolonCodeClient.builder()
                .http(originUrl).authToken("tok").workspace("ws1")
                .timeout(Duration.ofSeconds(20));
        if (options != null) {
            builder.httpOptions(options);
        }
        return builder.build();
    }

    private static void respondWithResultSse(HttpExchange exchange, String result, String sessionId) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("data: {\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\""
                        + result + "\",\"session_id\":\"" + sessionId + "\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        catch (Exception ignored) {
            // A cancelled client may close the exchange before the server finishes writing.
        }
    }

    private static void readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] chunk = new byte[4096];
            while (input.read(chunk) != -1) {
                // Drain the request body.
            }
        }
    }
}
