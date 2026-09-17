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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 通道连通性：对已部署的 {@code soloncode web} 实例的 {@code /web/run} 端点
 * 跑通完整链路（SSE + stream-json 事件 + 会话贯通）。
 *
 * <p>与 {@code SolonCodeRealCliIT} 同一断言口径：只验证 SDK ↔ /web/run 的协议契约
 * （鉴权、SSE 事件解析、session_id 贯通、result 事件），不断言模型答案。</p>
 *
 * <p>门控（Assumptions 跳过而非失败）：</p>
 * <ul>
 * <li>服务地址：系统属性 {@code soloncode.http.url}，默认 {@code http://localhost:1212/web/run}</li>
 * <li>token：系统属性 {@code soloncode.http.token}，缺省读 {@code ~/.soloncode/run.token}；无 token 文件则跳过</li>
 * <li>服务不可达（连接拒绝）则跳过 —— 部署是前置条件，不是 SDK 的故障</li>
 * </ul>
 *
 * <p>本机验证：{@code mvn -o verify -DskipITs=false -Dit.test=HttpRunIT}</p>
 */
@DisplayName("真实 /web/run HTTP 通道连通性")
class HttpRunIT {

	/** 真实执行冷启动（子进程 JVM + 模型调用）较慢，给足时间。 */
	private static final Duration RUN_TIMEOUT = Duration.ofMinutes(3);

	private static final long AWAIT_SECONDS = 180;

	static String runUrl;

	static String token;

	@BeforeAll
	static void requireLiveService() throws IOException {
		runUrl = System.getProperty("soloncode.http.url", "http://localhost:1212/web/run");
		token = resolveToken();
		if (token == null) {
			Assumptions.assumeTrue(false, "no run.token available; skip");
		}
		if (!reachable(runUrl)) {
			Assumptions.assumeTrue(false, "no live /web/run at " + runUrl + "; skip");
		}
	}

	private static String resolveToken() {
		String explicit = System.getProperty("soloncode.http.token");
		if (explicit != null && !explicit.isEmpty()) {
			return explicit;
		}
		Path tokenFile = Paths.get(System.getProperty("user.home"), ".soloncode", "run.token");
		try {
			if (Files.exists(tokenFile)) {
				String v = new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).trim();
				return v.isEmpty() ? null : v;
			}
		}
		catch (IOException ignored) {
		}
		return null;
	}

	private static boolean reachable(String url) {
		try {
			HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setRequestMethod("POST");
			conn.setConnectTimeout(3_000);
			conn.setReadTimeout(3_000);
			conn.setDoOutput(true);
			conn.setRequestProperty("Authorization", "Bearer " + token);
			try (java.io.OutputStream os = conn.getOutputStream()) {
				os.write("{\"prompt\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
			}
			int status = conn.getResponseCode();
			conn.disconnect();
			// 401/403/4xx/200 都证明服务在线（有响应即活）；只有连不上才算不可达
			return status > 0;
		}
		catch (IOException e) {
			return false;
		}
	}

	@Test
	@DisplayName("单轮：SSE 事件解析 + session_id 贯通 + result 事件")
	void singleTurnDeliversInitAssistantResultOverSse() throws Exception {
		HttpTransport transport = new HttpTransport(runUrl, token, null, RUN_TIMEOUT);
		List<Message> received = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(1);

		try {
			transport.startSession("回复两个字：收到", CLIOptions.builder()
				.timeout(RUN_TIMEOUT)
				.bare(true)
				.maxTurns(1)
				.build(), parsed -> {
					if (parsed instanceof ParsedMessage.EndOfStream) {
						done.countDown();
						return;
					}
					if (parsed.isRegularMessage()) {
						received.add(parsed.asMessage());
					}
				}, null);

			assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS))
				.describedAs("SSE 流应在 %ss 内终结", AWAIT_SECONDS)
				.isTrue();
		}
		finally {
			transport.close();
		}

		// 1) system(init)：证明子进程起来了、请求体被 /web/run 接受。
		// 只认 subtype=init，避免服务端补发的 error 事件（也是 SystemMessage）造成空泛通过
		List<SystemMessage> systems = filter(received, SystemMessage.class);
		assertThat(systems).describedAs("必须收到 system 事件").isNotEmpty();
		SystemMessage init = systems.get(0);
		assertThat(init.subtype()).describedAs("首个 system 事件应为 init").isEqualTo("init");
		assertThat(init.data()).containsKey("session_id");

		// 2) result：HTTP 通道的执行结论事件
		List<ResultMessage> results = filter(received, ResultMessage.class);
		assertThat(results).describedAs("必须收到 result 事件").hasSize(1);

		// 3) session_id 贯通：init 与 result 的 session 一致，且 SDK 侧可取到
		ResultMessage result = results.get(0);
		assertThat(result.sessionId()).isNotNull().isNotEmpty();
		assertThat(transport.getSessionId()).isEqualTo(result.sessionId());
	}

	@Test
	@DisplayName("错误 token 被服务端拒绝（401），抛 TransportException")
	void invalidTokenIsRejected() {
		HttpTransport transport = new HttpTransport(runUrl, "definitely-wrong-token", null, RUN_TIMEOUT);
		try {
			try {
				transport.startSession("hi", CLIOptions.builder().build(), m -> {
				}, null);
				throw new AssertionError("401 should have thrown");
			}
			catch (TransportException expected) {
				assertThat(expected.getMessage()).containsIgnoringCase("unauthorized");
			}
		}
		finally {
			transport.close();
		}
	}

	private static <T extends Message> List<T> filter(List<Message> messages, Class<T> type) {
		List<T> out = new java.util.ArrayList<>();
		for (Message m : messages) {
			if (type.isInstance(m)) {
				out.add(type.cast(m));
			}
		}
		return out;
	}
}
