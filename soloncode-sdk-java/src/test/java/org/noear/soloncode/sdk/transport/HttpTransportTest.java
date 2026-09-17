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

import org.noear.snack4.ONode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HttpTransport 单测：JDK 内嵌 HttpServer 模拟 /web/run 的 SSE/错误码/interrupt 行为。
 *
 * <p>事件体使用与 CLI stream-json 相同的 JSONL（result 事件含 session_id/is_error），
 * 验证「一个 SSE data: 行 = CLI 的一行 JSONL、解析层零改动」的契约。</p>
 */
class HttpTransportTest {

	private HttpServer server;

	private String baseUrl;

	@TempDir
	Path tempDir;

	/** 服务端收到的请求记录（JSON 根节点） */
	private final List<ONode> receivedRequests = new CopyOnWriteArrayList<>();

	/** 服务端收到的 Authorization 头 */
	private final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();

	/** interrupt 请求收到的 session_id */
	private final List<String> interruptRequests = new CopyOnWriteArrayList<>();

	/** interrupt 请求收到的 Content-Type */
	private final List<String> interruptContentTypes = new CopyOnWriteArrayList<>();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		InetSocketAddress address = server.getAddress();
		baseUrl = "http://127.0.0.1:" + address.getPort() + "/web/run";

        server.createContext("/web/run/interrupt", exchange -> {
            receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = readBody(exchange);
            interruptContentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            try {
                String sid = ONode.ofJson(body).get("session_id").getString();
                if (sid != null) {
                    interruptRequests.add(sid);
                }
            }
            catch (Exception ignored) {
                for (String pair : body.split("&")) {
                    if (pair.startsWith("session_id=")) {
                        interruptRequests.add(java.net.URLDecoder.decode(pair.substring(11), "UTF-8"));
                    }
                }
            }
			respond(exchange, 202, "{\"code\":0,\"data\":\"ok\"}");
		});

		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	// ---------- helpers ----------

	/** Java 8 兼容读取请求体（readAllBytes 是 Java 9+） */
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

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** 注册一个 SSE /web/run handler：记录请求、回放给定 JSONL 行 */
	private void registerSseHandler(String... jsonLines) {
		server.createContext("/web/run", exchange -> {
			try {
				String body = readBody(exchange);
				receivedRequests.add(ONode.ofJson(body));
				receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

				exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
				exchange.sendResponseHeaders(200, 0);
				try (OutputStream os = exchange.getResponseBody()) {
					for (String line : jsonLines) {
						os.write(("event: message\ndata: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
						os.flush();
					}
				}
			}
			catch (Exception ignored) {
				// client closed mid-stream
			}
		});
	}

	private void registerStatusHandler(int status, String body) {
		server.createContext("/web/run", exchange -> {
			String requestBody = readBody(exchange);
			receivedRequests.add(ONode.ofJson(requestBody));
			respond(exchange, status, body);
		});
	}

	private static CLIOptions options() {
		return CLIOptions.builder().build();
	}

	private List<ParsedMessage> runToCompletion(HttpTransport transport, String prompt) throws SolonCodeSDKException {
		// 过滤 EndOfStream 哨兵：SSE 流终结时传输层向 handler 投递它（与 StdioTransport 行为对齐），
		// 它不对应任何 CLI 事件，不应计入消息数
		List<ParsedMessage> received = new CopyOnWriteArrayList<>();
		transport.startSession(prompt, options(), m -> {
			if (m.isRegularMessage() || m.isResultMessage()) {
				received.add(m);
			}
		}, null, null);
		assertThat(transport.waitForCompletion(Duration.ofSeconds(10))).isTrue();
		return received;
	}

	// ---------- 请求体构造 ----------

	@Test
	void buildRequestBodyMapsCliOptionsToSnakeCaseFields() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, "secret-token", "my-project",
				Duration.ofMinutes(10));

		String json = transport.buildRequestBody("分析这个模块", CLIOptions.builder()
				.model("sonnet")
				.allowedTools(Arrays.asList("Read", "Grep"))
				.disallowedTools(Collections.singletonList("Bash(rm *)"))
				.permissionMode(PermissionMode.DONT_ASK)
				.maxTurns(15)
				.maxBudgetUsd(2.0)
				.fallbackModel("haiku")
				.sessionId("my-task-001")
				.bare(true)
				.build());

		ONode root = ONode.ofJson(json);
		assertThat(root.get("prompt").getString()).isEqualTo("分析这个模块");
		assertThat(root.get("workspace").getString()).isEqualTo("my-project");

		ONode options = root.get("options");
		assertThat(options.get("output_format").getString()).isEqualTo("stream-json");
		assertThat(options.get("model").getString()).isEqualTo("sonnet");
		assertThat(options.hasKey("allowed_tools")).isTrue();
		assertThat(options.get("allowed_tools").size()).isEqualTo(2);
		assertThat(options.get("disallowed_tools").get(0).getString()).isEqualTo("Bash(rm *)");
		assertThat(options.get("permission_mode").getString()).isEqualTo("dontAsk");
		assertThat(options.get("max_turns").getInt()).isEqualTo(15);
		assertThat(options.get("max_budget_usd").getDouble()).isEqualTo(2.0);
		assertThat(options.get("fallback_model").getString()).isEqualTo("haiku");
		assertThat(options.get("session_id").getString()).isEqualTo("my-task-001");
		assertThat(options.get("bare").getBoolean()).isTrue();
		// 未设置的字段不出现（null 不序列化，避免服务端 400 未识别字段）
		assertThat(options.hasKey("resume")).isFalse();
		assertThat(options.hasKey("continue")).isFalse();
	}

	@Test
	void buildRequestBodyTurnResumeOverridesOptionsSessionId() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		transport.setTurnSession("sdk-abc123", "sdk-abc123"); // 第二轮：resume 续接

		String json = transport.buildRequestBody("继续", CLIOptions.builder()
				.sessionId("sdk-abc123")
				.build());

		ONode options = ONode.ofJson(json).get("options");
		assertThat(options.get("resume").getString()).isEqualTo("sdk-abc123");
		assertThat(options.hasKey("session_id")).isFalse(); // resume 优先，不再传 session_id
	}

	@Test
	void buildRequestBodyContinueWithoutResume() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		String json = transport.buildRequestBody("继续上次", CLIOptions.builder()
				.continueConversation(true)
				.build());

		ONode options = ONode.ofJson(json).get("options");
		assertThat(options.get("continue").getBoolean()).isTrue();
	}

	@Test
	void buildRequestBodyBypassPermissionsIsOmittedNotSent() throws Exception {
		// 服务端收口：bypass 系一律不接受；SDK 侧前置回落 default，避免调用方吃 403。
		// builder 默认值即 BYPASS_PERMISSIONS，所以这条路径是 HTTP 通道的常态路径。
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		String json = transport.buildRequestBody("test", CLIOptions.builder()
				.permissionMode(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS)
				.build());

		assertThat(ONode.ofJson(json).get("options").get("permission_mode").getString()).isEqualTo("default");
		// 默认 BYPASS 同样回落（不再吃 403）
		String jsonDefault = transport.buildRequestBody("test", CLIOptions.builder().build());
		assertThat(ONode.ofJson(jsonDefault).get("options").get("permission_mode").getString())
				.isEqualTo("default");
	}

	@Test
	void buildRequestBodyKeepsNullPromptField() throws Exception {
		// 迁移到 snack4 后的回归防线：snack4 默认不写 null，而 Jackson 会写。
		// connect() 不带 prompt 时请求体原本就带 "prompt":null，这个字段不能凭空消失。
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		String json = transport.buildRequestBody(null, options());

		assertThat(json).contains("\"prompt\":null");
		assertThat(ONode.ofJson(json).hasKey("prompt")).isTrue();
		transport.close();
	}

	// ---------- SSE 流 ----------

	@Test
	void sseLinesAreParsedLikeCliJsonl() throws Exception {
		registerSseHandler(
				"{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"print-a1b2c3d4\",\"model\":\"sonnet\"}",
				"{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"分析中...\"}]}}",
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"完成\",\"session_id\":\"print-a1b2c3d4\",\"num_turns\":2,\"duration_ms\":100,\"usage\":{}}");

		HttpTransport transport = new HttpTransport(baseUrl, "secret-token", "my-project",
				Duration.ofMinutes(10));
		List<ParsedMessage> received = runToCompletion(transport, "分析这个模块");
		transport.close();

		assertThat(received.size()).isEqualTo(3);
		assertThat(transport.getSessionId()).isEqualTo("print-a1b2c3d4");

		// 服务端收到了正确的请求体与鉴权头
		assertThat(receivedRequests).hasSize(1);
		ONode request = receivedRequests.get(0);
		assertThat(request.get("prompt").getString()).isEqualTo("分析这个模块");
		assertThat(request.get("workspace").getString()).isEqualTo("my-project");
		assertThat(request.get("options").get("output_format").getString()).isEqualTo("stream-json");
		assertThat(receivedAuthHeaders.get(0)).isEqualTo("Bearer secret-token");
	}

	@Test
	void errorEventTypeIsDeliveredAsMessage() throws Exception {
		registerSseHandler(
				"{\"type\":\"error\",\"message\":\"Run failed with exit code 1\",\"code\":\"ERR_SUBPROCESS\"}");

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		List<ParsedMessage> received = runToCompletion(transport, "boom");
		transport.close();

		// error 事件由解析层分类为普通消息（向前兼容），流以 EndOfStream 收尾
		assertThat(received.size()).isGreaterThanOrEqualTo(1);
	}

	// ---------- 错误码 ----------

	@Test
	void unauthorizedMapsToTransportExceptionWithStatus() {
		registerStatusHandler(401, "{\"code\":401,\"message\":\"Missing or invalid bearer token\"}");

		HttpTransport transport = new HttpTransport(baseUrl, "wrong-token", null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", options(), m -> {
		}, null, null)).isInstanceOf(TransportException.class)
				.hasMessageContaining("Unauthorized");
		transport.close();
	}

	@Test
	void sessionConflictMapsTo409() {
		registerStatusHandler(409, "{\"code\":409,\"message\":\"Session 's1' already has an active run\"}");

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", options(), m -> {
		}, null, null)).isInstanceOf(TransportException.class)
				.hasMessageContaining("active run");
		transport.close();
	}

	@Test
	void workspaceNotFoundMapsTo404() {
		registerStatusHandler(404, "{\"code\":404,\"message\":\"Workspace not found: nope\"}");

		HttpTransport transport = new HttpTransport(baseUrl, null, "nope", Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", options(), m -> {
		}, null, null)).isInstanceOf(TransportException.class)
				.hasMessageContaining("Workspace not found");
		transport.close();
	}

	// ---------- one-shot 语义 ----------

	@Test
	void sendUserMessageIsRejectedAfterStart() throws Exception {
		registerSseHandler("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s\"}");

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		runToCompletion(transport, "hi");

		assertThatThrownBy(() -> transport.sendUserMessage("another", "s"))
				.isInstanceOf(TransportException.class)
				.hasMessageContaining("one-shot");
		transport.close();
	}

	@Test
	void closedTransportCannotBeReused() throws Exception {
		registerSseHandler("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s\"}");

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		runToCompletion(transport, "hi");
		transport.close();

		assertThatThrownBy(() -> transport.startSession("again", options(), m -> {
		}, null, null)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("cannot be reused");
	}

	// ---------- interrupt ----------

	@Test
	void interruptPostsToInterruptEndpointWithTurnSessionId() throws Exception {
		registerSseHandler(
				"{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"print-x1\",\"model\":\"sonnet\"}",
				"{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"thinking\"}]}}",
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\",\"session_id\":\"print-x1\"}");

		HttpTransport transport = new HttpTransport(baseUrl, "secret-token", null, Duration.ofMinutes(10));
		transport.setTurnSession("sdk-job-42", null);

		CountDownLatch streamDone = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		transport.startSession("long task", options(), m -> {
		}, null, null);
		// 异步等待完成（流很短，马上结束）
		Thread waiter = new Thread(() -> {
			try {
				transport.waitForCompletion(Duration.ofSeconds(10));
			}
			catch (Exception e) {
				error.set(e);
			}
			streamDone.countDown();
		});
		waiter.start();

		transport.interrupt();
		assertThat(streamDone.await(10, TimeUnit.SECONDS)).isTrue();
		transport.close();

		assertThat(interruptRequests).containsExactly("sdk-job-42");
		// interrupt 请求同样带鉴权头
		assertThat(receivedAuthHeaders.stream().skip(1).findFirst()).contains("Bearer secret-token");
	}

	// ---------- TransportSpec ----------

	@Test
	void transportSpecDescribeAndWithCredentials() {
		TransportSpec spec = TransportSpec.http("http://127.0.0.1:18080/web/run");
		assertThat(spec.isHttp()).isTrue();
		assertThat(spec.describe()).isEqualTo("http(http://127.0.0.1:18080/web/run)");

		TransportSpec withCreds = spec.withHttpCredentials("tok", "ws1");
		assertThat(withCreds.describe()).isEqualTo("http(http://127.0.0.1:18080/web/run)");

		// stdio spec 不接受 http 凭证
		TransportSpec stdio = TransportSpec.stdio();
		assertThat(stdio.isHttp()).isFalse();
		assertThatThrownBy(() -> stdio.withHttpCredentials("tok", null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void httpSpecRequiresUrl() {
		assertThatThrownBy(() -> TransportSpec.http(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TransportSpec.http("  ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void stateMachineTransitions() throws Exception {
		registerSseHandler("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s1\"}");

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThat(transport.getState()).isEqualTo(Transport.STATE_DISCONNECTED);

		runToCompletion(transport, "hi");
		assertThat(transport.getStateName()).isIn("CONNECTED", "CLOSING", "CLOSED");
		transport.close();
		assertThat(transport.getState()).isEqualTo(Transport.STATE_CLOSED);
		assertThat(transport.isRunning()).isFalse();
	}

	@Test
	void waitForCompletionWithSessionErrorThrows() throws Exception {
		registerStatusHandler(500, "boom");
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", options(), m -> {
		}, null, null)).isInstanceOf(TransportException.class);
		transport.close();
	}

	@Test
	void emptyDataLinesAreIgnored() throws Exception {
		// event: 行/注释/空行不应产生消息
		server.createContext("/web/run", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(": keep-alive comment\n\n".getBytes(StandardCharsets.UTF_8));
				os.write("event: message\n".getBytes(StandardCharsets.UTF_8));
				os.write("\n".getBytes(StandardCharsets.UTF_8));
				os.write("data: {\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s9\"}\n\n"
						.getBytes(StandardCharsets.UTF_8));
				os.write("data:\n\n".getBytes(StandardCharsets.UTF_8));
				os.flush();
			}
		});

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		List<ParsedMessage> received = new CopyOnWriteArrayList<>();
		transport.startSession("hi", options(), m -> {
			if (m.isRegularMessage() || m.isResultMessage()) {
				received.add(m);
			}
		}, null, null);
		transport.waitForCompletion(Duration.ofSeconds(10));
		transport.close();

		assertThat(received.size()).isEqualTo(1);
	}

	@Test
	void noWorkspaceMeansNoWorkspaceField() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		String json = transport.buildRequestBody("hi", options());
		assertThat(ONode.ofJson(json).hasKey("workspace")).isFalse();
		transport.close();
	}


	// ---------- 补充：错误码与状态分支 ----------

	@Test
	void serverError500MapsToTransportException() {
		registerStatusHandler(500, "internal");
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", options(), m -> {
		}, null, null)).isInstanceOf(TransportException.class).hasMessageContaining("HTTP 500");
		transport.close();
	}

	@Test
	void forbiddenMapsTo403() {
		registerStatusHandler(403, "bypass rejected");
		HttpTransport transport = new HttpTransport(baseUrl, "tok", null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", options(), m -> {
		}, null, null)).isInstanceOf(TransportException.class).hasMessageContaining("Forbidden");
		transport.close();
	}

	@Test
	void constructorRejectsBlankUrlAndNullTimeout() {
		assertThatThrownBy(() -> new HttpTransport(null, null, null, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HttpTransport("  ", null, null, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HttpTransport(baseUrl, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sendMessageAndSendResponseAreOneShotUnsupported() {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.sendMessage("{}")).isInstanceOf(TransportException.class)
				.hasMessageContaining("one-shot");
		assertThatThrownBy(() -> transport.sendResponse(null)).isInstanceOf(TransportException.class)
				.hasMessageContaining("one-shot");
		transport.close();
	}

	@Test
	void dataLineWithoutSpaceIsParsed() throws Exception {
		// SSE 规范允许 data:后无空格（payload 紧跟冒号）
		server.createContext("/web/run", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write("data:{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"nospace\"}\n\n"
						.getBytes(StandardCharsets.UTF_8));
				os.flush();
			}
		});

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		List<ParsedMessage> received = new CopyOnWriteArrayList<>();
		transport.startSession("hi", options(), m -> {
			if (m.isRegularMessage() || m.isResultMessage()) {
				received.add(m);
			}
		}, null, null);
		assertThat(transport.waitForCompletion(Duration.ofSeconds(10))).isTrue();
		transport.close();

		assertThat(received).hasSize(1);
		assertThat(transport.getSessionId()).isEqualTo("nospace");
	}

	@Test
	void interruptedSseStreamSurfacesSessionError() throws Exception {
		// 服务端发一半后 handler 抛异常 → JDK HttpServer 直接断连（客户端读到 IOException，非正常 EOF）
		// → waitForCompletion 报 Session error
		server.createContext("/web/run", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
			OutputStream os = exchange.getResponseBody();
			os.write("data: {\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}}\n\n"
					.getBytes(StandardCharsets.UTF_8));
			os.flush();
			throw new RuntimeException("simulated mid-stream server crash");
		});

		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		List<ParsedMessage> received = new CopyOnWriteArrayList<>();
		transport.startSession("hi", options(), m -> {
			if (m.isRegularMessage() || m.isResultMessage()) {
				received.add(m);
			}
		}, null, null);
		assertThatThrownBy(() -> transport.waitForCompletion(Duration.ofSeconds(10)))
				.hasMessageContaining("Session error");
		transport.close();
		assertThat(received).hasSize(1);
	}

	@Test
	void malformedDataLineFailsFast() throws Exception {
		registerSseHandler("{{{not json",
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"m1\"}");
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		List<ParsedMessage> received = new CopyOnWriteArrayList<>();
		transport.startSession("hi", options(), m -> {
			if (m.isRegularMessage() || m.isResultMessage()) {
				received.add(m);
			}
		}, null, null);
		assertThatThrownBy(() -> transport.waitForCompletion(Duration.ofSeconds(10)))
				.hasMessageContaining("Session error");
		transport.close();
		// 已声明为 SSE JSON 事件但无法解析时，必须失败，不能把截断响应误报成功。
		assertThat(received).isEmpty();
	}

	@Test
	void interruptUsesJsonRequestContract() {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		transport.setTurnSession("session/with-quote\"", null);
		transport.interrupt();
		assertThat(interruptRequests).containsExactly("session/with-quote\"");
		assertThat(interruptContentTypes.get(0)).startsWith("application/json");
		transport.close();
	}

	@Test
	void interruptWithoutSessionIdFailsFast() {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThatThrownBy(transport::interrupt).hasMessageContaining("requires a session id");
		assertThat(transport.isRunning()).isFalse();
		transport.close();
	}

	@Test
	void interruptServer404LogsAndIgnores() throws Exception {
		server.removeContext("/web/run/interrupt");
		server.createContext("/web/run/interrupt", exchange -> {
			readBody(exchange);
			respond(exchange, 404, "{\"code\":404}");
		});
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		transport.setTurnSession("no-such-session", null);
		transport.interrupt(); // 404 → warn 不抛
		transport.close();
	}

	@Test
	void interruptToUnreachableUrlPropagatesFailure() {
		HttpTransport transport = new HttpTransport("http://127.0.0.1:1/web/run", null, null,
				Duration.ofMinutes(10));
		transport.setTurnSession("s1", null);
		assertThatThrownBy(transport::interrupt).hasMessageContaining("Failed to interrupt session");
		transport.close();
	}

	@Test
	void closeGracefullyCompletesAndCloses() throws Exception {
		registerSseHandler(String.format(
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"cg\"}"));
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		transport.startSession("hi", options(), m -> {
		}, null, null);
		transport.waitForCompletion(Duration.ofSeconds(10));
		transport.closeGracefully().block(Duration.ofSeconds(10));
		assertThat(transport.getState()).isEqualTo(Transport.STATE_CLOSED);
	}

	@Test
	void doubleCloseIsIdempotent() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		transport.close();
		transport.close(); // 幂等
		assertThat(transport.getState()).isEqualTo(Transport.STATE_CLOSED);
	}

	@Test
	void getStateNameCoversAllStates() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThat(transport.getStateName()).isEqualTo("DISCONNECTED");
		transport.close();
		assertThat(transport.getStateName()).isEqualTo("CLOSED");
	}

	@Test
	void resultWithoutSessionIdKeepsInitSessionId() throws Exception {
		// init 事件会尽早发布 session_id；缺少 session_id 的 result 不应清空它。
		registerSseHandler(
				"{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"init-1\"}",
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\"}");
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		runToCompletion(transport, "hi");
		transport.close();
		assertThat(transport.getSessionId()).isEqualTo("init-1");
	}

	@Test
	void responseHeaderWaitHonorsTurnTimeout() {
		server.createContext("/web/run", exchange -> {
			try {
				readBody(exchange);
				Thread.sleep(1_000L);
				respond(exchange, 200, "");
			}
			catch (Exception ignored) {
			}
		});
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMillis(100));
		long started = System.nanoTime();
		assertThatThrownBy(() -> transport.startSession("hi", CLIOptions.builder().build(), m -> {
		}, null, null)).hasMessageContaining("Failed to start HTTP session");
		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(900L);
		transport.close();
	}

	@Test
	void loadsBothPkcs12AndJksStoresIndependentOfJvmDefault() throws Exception {
		for (String type : new String[] { "PKCS12", "JKS" }) {
			String extension = "PKCS12".equals(type) ? ".p12" : ".jks";
			Path path = tempDir.resolve("trust" + extension);
			KeyStore store = KeyStore.getInstance(type);
			store.load(null, "pw".toCharArray());
			try (OutputStream output = Files.newOutputStream(path)) {
				store.store(output, "pw".toCharArray());
			}
			HttpOptions httpOptions = HttpOptions.tls().trustStore(path, "pw");
			HttpTransport transport = new HttpTransport(baseUrl, null, null, httpOptions, Duration.ofSeconds(1));
			transport.close();
		}
	}

	@Test
	void maxTokensIsRejectedInsteadOfSilentlyIgnored() {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", CLIOptions.builder().maxTokens(10).build(), m -> {
		}, null, null))
				.hasMessageContaining("Failed to start HTTP session")
				.hasRootCauseMessage("maxTokens is not supported by the current /web/run protocol");
		transport.close();
	}

	@Test
	void buildRequestBodySkipsEmptyFieldsAndWarnsUnsupported() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		String json = transport.buildRequestBody("p", CLIOptions.builder()
				.systemPrompt("sp")
				.appendSystemPrompt("asp")
				.tools(java.util.Collections.singletonList("Read"))
				.mcpServers(java.util.Collections.singletonMap("m",
				new org.noear.soloncode.sdk.mcp.McpServerConfig.McpStdioServerConfig("echo")))
				.maxThinkingTokens(1024)
				.extraArgs(java.util.Collections.singletonMap("f", "v"))
				.addDirs(java.util.Collections.singletonList(java.nio.file.Paths.get("/srv/data")))
				.build());
		ONode options = ONode.ofJson(json).get("options");
		// 不支持的选项全部省略，add_dirs 告警透传
		assertThat(options.hasKey("system_prompt")).isFalse();
		assertThat(options.hasKey("append_system_prompt")).isFalse();
		assertThat(options.hasKey("tools")).isFalse();
		assertThat(options.hasKey("mcp_servers")).isFalse();
		assertThat(options.hasKey("max_thinking_tokens")).isFalse();
		assertThat(options.hasKey("extra_args")).isFalse();
		assertThat(options.get("add_dirs").get(0).getString()).isEqualTo("/srv/data");
		transport.close();
	}

	@Test
	void buildRequestBodyResumeFromOptionsWithoutTurn() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		String json = transport.buildRequestBody("p", CLIOptions.builder().resume("opt-resume-1").build());
		assertThat(ONode.ofJson(json).get("options").get("resume").getString()).isEqualTo("opt-resume-1");
		transport.close();
	}

	@Test
	void buildRequestBodyTurnSessionIdPreferredOverOptions() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		transport.setTurnSession("turn-sid", null);
		String json = transport.buildRequestBody("p", CLIOptions.builder().sessionId("opt-sid").build());
		assertThat(ONode.ofJson(json).get("options").get("session_id").getString()).isEqualTo("turn-sid");
		transport.close();
	}

	@Test
	void httpUrlWithTrailingSlashInterruptStillWorks() throws Exception {
		HttpTransport transport = new HttpTransport(baseUrl + "/", null, null, Duration.ofMinutes(10));
		transport.setTurnSession("slash-1", null);
		transport.interrupt();
		transport.close();
		assertThat(interruptRequests).contains("slash-1");
	}

}
