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
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自定义请求头：既验证 HttpOptions 的构建/校验语义，也用内嵌 HttpServer 验证头真的
 * 落到了 /web/run 与 /web/run/interrupt 两条链路上。
 */
class HttpTransportHeaderTest {

	private HttpServer server;

	private String baseUrl;

	/** 每次请求收到的完整请求头快照（键为小写） */
	private final List<Map<String, String>> runHeaders = new CopyOnWriteArrayList<>();

	private final List<Map<String, String>> interruptHeaders = new CopyOnWriteArrayList<>();

	private final CountDownLatch interruptHit = new CountDownLatch(1);

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/web/run";

		server.createContext("/web/run/interrupt", exchange -> {
			interruptHeaders.add(snapshot(exchange));
			drain(exchange);
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
			interruptHit.countDown();
		});

		server.createContext("/web/run", exchange -> {
			runHeaders.add(snapshot(exchange));
			drain(exchange);
			try {
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
				exchange.sendResponseHeaders(200, 0);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(("event: message\ndata: "
							+ "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"h1\",\"model\":\"sonnet\"}"
							+ "\n\n").getBytes(StandardCharsets.UTF_8));
					os.flush();
					os.write(("event: message\ndata: "
							+ "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
							+ "\"result\":\"ok\",\"session_id\":\"h1\"}" + "\n\n")
									.getBytes(StandardCharsets.UTF_8));
					os.flush();
				}
			}
			catch (Exception ignored) {
				// client closed mid-stream
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private static Map<String, String> snapshot(HttpExchange exchange) {
		Map<String, String> map = new LinkedHashMap<>();
		exchange.getRequestHeaders()
			.forEach((k, v) -> map.put(k.toLowerCase(), v.isEmpty() ? null : v.get(0)));
		return map;
	}

	private static void drain(HttpExchange exchange) throws IOException {
		try (InputStream is = exchange.getRequestBody()) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] chunk = new byte[512];
			int n;
			while ((n = is.read(chunk)) != -1) {
				buf.write(chunk, 0, n);
			}
		}
	}

	private void runOnce(HttpTransport transport) throws SolonCodeSDKException {
		List<ParsedMessage> received = new CopyOnWriteArrayList<>();
		transport.startSession("hi", CLIOptions.builder().build(), received::add, null, null);
		assertThat(transport.waitForCompletion(Duration.ofSeconds(10))).isTrue();
		transport.close();
	}

	// ---------- 落到线上的头 ----------

	@Test
	void customHeadersAreSentOnRunRequest() throws Exception {
		HttpOptions options = HttpOptions.create()
			.header("X-Tenant-Id", "t-1024")
			.header("X-Trace-Id", "trace-abc");

		runOnce(new HttpTransport(baseUrl, "secret-token", null, options, Duration.ofMinutes(1)));

		assertThat(runHeaders).hasSize(1);
		Map<String, String> headers = runHeaders.get(0);
		assertThat(headers).containsEntry("x-tenant-id", "t-1024");
		assertThat(headers).containsEntry("x-trace-id", "trace-abc");
		// 自定义头不影响 SDK 自管的协议头
		assertThat(headers).containsEntry("authorization", "Bearer secret-token");
		assertThat(headers.get("content-type")).contains("application/json");
		assertThat(headers).containsEntry("accept", "text/event-stream");
	}

	@Test
	void customHeadersAreSentOnInterruptRequest() throws Exception {
		HttpOptions options = HttpOptions.create().header("X-Tenant-Id", "t-1024");
		HttpTransport transport = new HttpTransport(baseUrl, "secret-token", null, options,
				Duration.ofMinutes(1));
		transport.setTurnSession("sdk-job-9", null);

		transport.startSession("long task", CLIOptions.builder().build(), m -> {
		}, null, null);
		transport.interrupt();
		assertThat(interruptHit.await(10, TimeUnit.SECONDS)).isTrue();
		transport.waitForCompletion(Duration.ofSeconds(10));
		transport.close();

		assertThat(interruptHeaders).isNotEmpty();
		assertThat(interruptHeaders.get(0)).containsEntry("x-tenant-id", "t-1024");
		assertThat(interruptHeaders.get(0)).containsEntry("authorization", "Bearer secret-token");
	}

	@Test
	void noCustomHeadersMeansNoExtraHeaders() throws Exception {
		runOnce(new HttpTransport(baseUrl, "secret-token", null, Duration.ofMinutes(1)));

		assertThat(runHeaders).hasSize(1);
		assertThat(runHeaders.get(0)).doesNotContainKey("x-tenant-id");
	}

	@Test
	void authorizationHeaderCanOverrideAuthToken() throws Exception {
		// 对接前置网关时需要换一套 Authorization；SDK 允许覆盖（会打 WARN）
		HttpOptions options = HttpOptions.create().header("Authorization", "Bearer gateway-token");

		runOnce(new HttpTransport(baseUrl, "secret-token", null, options, Duration.ofMinutes(1)));

		assertThat(runHeaders.get(0)).containsEntry("authorization", "Bearer gateway-token");
	}

	// ---------- HttpOptions 构建语义 ----------

	@Test
	void headersAreCaseInsensitiveAndLastWriteWins() {
		HttpOptions options = HttpOptions.create()
			.header("X-Tenant-Id", "first")
			.header("x-TENANT-id", "second");

		assertThat(options.headers()).hasSize(1);
		assertThat(options.headers().get("X-Tenant-Id")).isEqualTo("second");
		// 任意大小写都能取到
		assertThat(options.headers().get("x-tenant-id")).isEqualTo("second");
	}

	@Test
	void headersMapIsImmutableSnapshot() {
		Map<String, String> src = new HashMap<>();
		src.put("X-A", "1");
		HttpOptions options = HttpOptions.create().headers(src);

		// 源 Map 后续改动不影响已构建的选项
		src.put("X-B", "2");
		assertThat(options.headers()).hasSize(1);

		assertThatThrownBy(() -> options.headers().put("X-C", "3"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void withersReturnNewInstanceAndDoNotMutate() {
		HttpOptions base = HttpOptions.create().header("X-A", "1");
		HttpOptions derived = base.header("X-B", "2");

		assertThat(base.headers()).hasSize(1);
		assertThat(derived.headers()).hasSize(2);
		assertThat(derived).isNotSameAs(base);
	}

	@Test
	void headersComposeWithProxyAndTls() {
		HttpOptions options = HttpOptions.proxy("proxy.example", 3128)
			.proxyAuth("u", "p")
			.header("X-Tenant-Id", "t-1");

		assertThat(options.proxyHost()).isEqualTo("proxy.example");
		assertThat(options.proxyAuthHeader()).startsWith("Basic ");
		assertThat(options.headers()).containsEntry("X-Tenant-Id", "t-1");
	}

	@Test
	void reservedHeadersAreRejected() {
		// 协议关键头：覆盖会破坏 SSE 契约
		assertThatThrownBy(() -> HttpOptions.create().header("Content-Type", "text/plain"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("managed by the SDK");
		assertThatThrownBy(() -> HttpOptions.create().header("accept", "application/json"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> HttpOptions.create().header("Content-Length", "10"))
			.isInstanceOf(IllegalArgumentException.class);
		// 代理认证由 proxyAuth() 管理
		assertThatThrownBy(() -> HttpOptions.create().header("Proxy-Authorization", "Basic x"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidHeaderNameOrValueIsRejected() {
		assertThatThrownBy(() -> HttpOptions.create().header(null, "v"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("header name");
		assertThatThrownBy(() -> HttpOptions.create().header("  ", "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> HttpOptions.create().header("X-A", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("header value");
	}

	@Test
	void headerNameIsTrimmed() {
		HttpOptions options = HttpOptions.create().header("  X-A  ", "1");
		assertThat(options.headers()).containsEntry("X-A", "1");
	}

	@Test
	void nullHeadersMapIsTreatedAsEmpty() {
		assertThat(HttpOptions.create().headers(null).headers()).isEmpty();
	}

	@Test
	void headersParticipateInEqualityButSensitiveValuesAreMasked() {
		HttpOptions a = HttpOptions.create().header("X-A", "1");
		HttpOptions b = HttpOptions.create().header("X-A", "1");
		HttpOptions c = HttpOptions.create().header("X-A", "2");

		assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
		assertThat(a).isNotEqualTo(c);

		// 普通头输出明文便于排查；敏感头只留存在性
		assertThat(a.toString()).contains("X-A=1");
		String masked = HttpOptions.create().header("Authorization", "Bearer super-secret").toString();
		assertThat(masked).contains("Authorization=***").doesNotContain("super-secret");
		assertThat(HttpOptions.create().header("Cookie", "sid=abc").toString())
			.contains("Cookie=***")
			.doesNotContain("sid=abc");
	}

	@Test
	void headersMakeOptionsNonDefault() {
		assertThat(HttpOptions.create().isDefault()).isTrue();
		assertThat(HttpOptions.create().header("X-A", "1").isDefault()).isFalse();
	}
}
