/*
 * Copyright 2025 soloncode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.noear.soloncode.sdk.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.exceptions.TransportException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代理路由测试：用 JDK HttpServer 起两个端口——一个当「代理」，一个当「真实 /web/run 服务」。
 *
 * <p>关键事实：对 http:// 目标，HttpURLConnection 使用 HTTP 代理时会以<b>绝对 URI</b>
 * 发起请求（不是 CONNECT 隧道，CONNECT 只用于 https）。所以「代理」端口收到
 * {@code POST http://host:port/web/run ...} 即证明流量确实走了代理；「真实服务」端口
 * 若也收到请求，说明请求绕过了代理（用于反向断言）。</p>
 */
class HttpTransportProxyTest {

	/** 模拟代理端口（只记录请求，回 SSE） */
	private HttpServer proxyServer;

	/** 模拟真实 /web/run 服务端口（正常情况下不应收到请求） */
	private HttpServer originServer;

	private String proxyHost;

	private int proxyPort;

	private String originUrl;

	/** 代理收到的请求行（如 POST http://127.0.0.1:x/web/run HTTP/1.1） */
	private final List<String> proxyRequestLines = new CopyOnWriteArrayList<>();

	/** 代理收到的 Proxy-Authorization 头 */
	private final List<String> proxyAuthHeaders = new CopyOnWriteArrayList<>();

	/** 真实服务收到的请求数（应恒为 0） */
	private final List<String> originHits = new CopyOnWriteArrayList<>();

	@BeforeEach
	void startServers() throws IOException {
		proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

		InetSocketAddress proxyAddr = proxyServer.getAddress();
		proxyHost = proxyAddr.getHostName();
		proxyPort = proxyAddr.getPort();
		originUrl = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/web/run";

		// 「代理」：记录请求行与 Proxy-Authorization，回一条 result SSE 后关流
		proxyServer.createContext("/", exchange -> {
			proxyRequestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			proxyAuthHeaders.add(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
			readBody(exchange);
			if (exchange.getRequestURI().getPath().endsWith("/interrupt")) {
				exchange.sendResponseHeaders(202, -1);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write("data: {\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"via-proxy\",\"session_id\":\"px1\"}\n\n"
						.getBytes(StandardCharsets.UTF_8));
				os.flush();
			}
			catch (Exception ignored) {
			}
		});

		// 「真实服务」：记录命中（不应发生）
		originServer.createContext("/", exchange -> {
			originHits.add(exchange.getRequestURI().toString());
			readBody(exchange);
			exchange.sendResponseHeaders(200, -1);
		});

		proxyServer.start();
		originServer.start();
	}

	@AfterEach
	void stopServers() {
		proxyServer.stop(0);
		originServer.stop(0);
	}

	private static String readBody(HttpExchange exchange) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (InputStream in = exchange.getRequestBody()) {
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) != -1) {
				buffer.write(chunk, 0, n);
			}
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

	private HttpTransport transportWithProxy(HttpOptions options) {
		return new HttpTransport(originUrl, null, null, options, Duration.ofMinutes(10));
	}

	@Test
	void httpProxyRoutesAbsoluteUriThroughProxy() throws Exception {
		HttpTransport transport = transportWithProxy(HttpOptions.proxy(proxyHost, proxyPort));
		transport.startSession("hi", CLIOptions.builder().build(), m -> {
		}, null, null);
		assertThat(transport.waitForCompletion(Duration.ofSeconds(10))).isTrue();
		transport.close();

		// 请求以绝对 URI 打到了代理端口，真实端口零命中
		assertThat(proxyRequestLines).hasSize(1);
		assertThat(proxyRequestLines.get(0)).startsWith("POST http://");
		assertThat(proxyRequestLines.get(0)).endsWith("/web/run");
		assertThat(originHits).isEmpty();
		// 事件流被正常解析（代理回的 SSE）
		assertThat(transport.getSessionId()).isEqualTo("px1");
	}

	@Test
	void proxyAuthHeaderIsSentOnEveryRequest() throws Exception {
		HttpOptions options = HttpOptions.proxy(proxyHost, proxyPort).proxyAuth("corp-user", "corp-pass");
		HttpTransport transport = transportWithProxy(options);
		transport.setTurnSession("sess-proxy-1", null);
		transport.startSession("hi", CLIOptions.builder().build(), m -> {
		}, null, null);
		transport.waitForCompletion(Duration.ofSeconds(10));
		transport.interrupt(); // /interrupt 也走代理
		transport.close();

		String expected = "Basic " + java.util.Base64.getEncoder()
				.encodeToString("corp-user:corp-pass".getBytes(StandardCharsets.UTF_8));
		// 至少两条请求（run + interrupt）都带了认证头
		assertThat(proxyAuthHeaders.size()).isGreaterThanOrEqualTo(2);
		assertThat(proxyAuthHeaders).containsOnly(expected);
	}

	@Test
	void interruptAlsoGoesThroughProxy() throws Exception {
		HttpTransport transport = transportWithProxy(HttpOptions.proxy(proxyHost, proxyPort));
		transport.setTurnSession("sess-px2", null);
		transport.interrupt();
		transport.close();

		assertThat(proxyRequestLines).anyMatch(l -> l.endsWith("/web/run/interrupt"));
		assertThat(originHits).isEmpty();
	}

	@Test
	void noProxyOptionMeansDirectConnection() throws Exception {
		// 反向对照：不配代理 → 请求直达真实端口
		HttpTransport transport = new HttpTransport(originUrl, null, null, Duration.ofMinutes(10));
		try {
			transport.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			transport.waitForCompletion(Duration.ofSeconds(5));
		}
		catch (Exception directSseMayFail) {
			// 真实服务回 200 空 body -1，流立即结束，属正常
		}
		transport.close();

		assertThat(proxyRequestLines).isEmpty();
		assertThat(originHits).hasSize(1);
	}

	@Test
	void unreachableProxySurfacesConnectError() {
		// 端口 1 上没有代理 → startSession 抛 TransportException（不无限等）
		HttpTransport transport = new HttpTransport(originUrl, null, null,
				HttpOptions.proxy("127.0.0.1", 1), Duration.ofSeconds(5));
		try {
			org.assertj.core.api.Assertions
					.assertThatThrownBy(() -> transport.startSession("hi", CLIOptions.builder().build(), m -> {
					}, null, null)).isInstanceOf(TransportException.class);
		}
		finally {
			transport.close();
		}
	}

}
