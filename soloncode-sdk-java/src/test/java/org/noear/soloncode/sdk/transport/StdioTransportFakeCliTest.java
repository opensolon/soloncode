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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.soloncode.sdk.exceptions.SessionClosedException;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StdioTransport 离线行为测试：用假 CLI 脚本（bash 模拟 {@code soloncode run}）覆盖
 * 进程生命周期、stdin/stdout/stderr 管道、退出码语义、控制请求与状态机分支。
 *
 * <p>不依赖本机安装真实 CLI——脚本决定 stdout JSONL、stderr、退出码与延时，
 * 使这些分支可以离线、可重复地被验证（真实 CLI 的端到端行为在 *IT 中验证）。</p>
 */
class StdioTransportFakeCliTest {

	@TempDir
	Path tempDir;

	/** 脚本收到的全部 argv（每项一行），prompt 走 argv 时可断言其内容。 */
	private final List<String> scriptArgvLog = new CopyOnWriteArrayList<>();

	/** 脚本 stderr 输出收集。 */
	private final List<String> stderrLines = new CopyOnWriteArrayList<>();

	/** 控制请求收到的 subtype。 */
	private final List<String> controlRequestSubtypes = new CopyOnWriteArrayList<>();

	private static final String RESULT_OK = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\",\"session_id\":\"%s\"}";

	// ---------- helpers ----------

	/** 写一个假 CLI 脚本：输出若干 JSONL 行到 stdout，可选写 stderr，可选 sleep，然后退出。 */
	private String writeFakeCli(String stdoutJsonl, String stderrText, long sleepMillisBeforeExit, int exitCode,
			boolean dumpEnv) throws IOException {
		StringBuilder body = new StringBuilder();
		body.append("#!/bin/bash\n");
		body.append("printf '%s\\n' -- \"$@\" >> '").append(tempDir.resolve("argv.log")).append("'\n");
		if (dumpEnv) {
			body.append("env | grep -E '^(SOLONCODE_|MY_)' | sort > '").append(tempDir.resolve("env.dump")).append("'\n");
		}
		if (stderrText != null) {
			body.append("echo '").append(stderrText.replace("'", "'\\''")).append("' >&2\n");
		}
		if (stdoutJsonl != null && !stdoutJsonl.isEmpty()) {
			// 每行一个 printf，避免转义问题；用 $'...' 形式安全输出 JSON
			for (String line : stdoutJsonl.split("\n")) {
				body.append("printf '%s\\n' '").append(line.replace("'", "'\\''")).append("'\n");
			}
		}
		if (sleepMillisBeforeExit > 0) {
			body.append("sleep ").append(sleepMillisBeforeExit / 1000.0).append("\n");
		}
		body.append("exit ").append(exitCode).append("\n");

		Path script = tempDir.resolve("fake-soloncode");
		Files.write(script, body.toString().getBytes(StandardCharsets.UTF_8));
		script.toFile().setExecutable(true);
		return script.toAbsolutePath().toString();
	}

	private StdioTransport newTransport(String cliPath) {
		return cliPath != null ? new StdioTransport(tempDir, Duration.ofSeconds(20), cliPath)
				: new StdioTransport(tempDir, Duration.ofSeconds(20), "/nonexistent/soloncode-binary");
	}

	private List<ParsedMessage> collect(StdioTransport t, String prompt) throws Exception {
		List<ParsedMessage> received = new CopyOnWriteArrayList<>();
		t.startSession(prompt, CLIOptions.builder().build(), m -> {
			if (m.isRegularMessage() || m.isResultMessage()) {
				received.add(m);
			}
		}, null, null);
		return received;
	}

	private static void awaitEndOfStream(List<ParsedMessage> eosSink) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (eosSink.isEmpty() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(eosSink).isNotEmpty();
	}

	private List<String> argvOf() throws IOException {
		return Files.readAllLines(tempDir.resolve("argv.log"), StandardCharsets.UTF_8);
	}

	/** 收集消息 + 捕获 EndOfStream 哨兵。 */
	private static final class Collector implements java.util.function.Consumer<ParsedMessage> {

		final List<ParsedMessage> messages = new CopyOnWriteArrayList<>();

		final List<ParsedMessage> eos = new CopyOnWriteArrayList<>();

		@Override
		public void accept(ParsedMessage m) {
			if (m == ParsedMessage.EndOfStream.INSTANCE) {
				eos.add(m);
			}
			else if (m.isRegularMessage() || m.isResultMessage()) {
				messages.add(m);
			}
		}
	}

	private List<String> envDumpOf() throws IOException {
		return Files.readAllLines(tempDir.resolve("env.dump"), StandardCharsets.UTF_8);
	}

	// ---------- 进程生命周期 ----------

	@Nested
	@DisplayName("Process lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("argv prompt: messages parsed from stdout, waitForCompletion true")
		void happyPathViaArgv() throws Exception {
			Collector collector = new Collector();
			String cli = writeFakeCli(
					"{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"fake-1\",\"model\":\"sonnet\"}\n"
							+ "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n"
							+ "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\",\"session_id\":\"fake-1\"}",
					null, 0, 0, false);

			StdioTransport t = newTransport(cli);
			t.startSession("hello world", CLIOptions.builder().build(), collector, null, null);

			assertThat(t.waitForCompletion(Duration.ofSeconds(15))).isTrue();
			awaitEndOfStream(collector.eos);
			t.close();

			// session_id 由客户端层从 result 事件提取（StdioTransport 自身不提取，区别于 HttpTransport）
			assertThat(collector.messages).anyMatch(m -> m.isResultMessage());
			// argv.log 首行是 printf 的 "--" 哨兵，其后：run, <prompt>, --output-format ...
			assertThat(argvOf().get(2)).isEqualTo("hello world");
		}

		@Test
		@DisplayName("prompt starting with '-' is delivered via stdin (argv skips it)")
		void promptWithDashGoesToStdin() throws Exception {
			// "-flag" 以 '-' 开头 → needsStdinPrompt=true → 不进 argv，经 stdin 管道
			String cli = writeFakeCli(
					"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"stdin-1\"}",
					null, 0, 0, false);

			StdioTransport t = newTransport(cli);
			t.startSession("-starts-with-dash", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThat(t.waitForCompletion(Duration.ofSeconds(15))).isTrue();
			t.close();

			assertThat(argvOf()).doesNotContain("-starts-with-dash");
		}

		@Test
		@DisplayName("nonexistent CLI path: startSession wraps into TransportException")
		void startWithBadCommandFails() {
			StdioTransport t = new StdioTransport(tempDir, Duration.ofSeconds(5), "/definitely/not/there");
			assertThatThrownBy(() -> t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null)).isInstanceOf(TransportException.class)
					.hasMessageContaining("Failed to start bidirectional session");
			// 失败后回到 DISCONNECTED，可重试（区别于 CLOSED 不可复用）
			assertThat(t.getState()).isEqualTo(Transport.STATE_DISCONNECTED);
			t.close();
		}

		@Test
		@DisplayName("second startSession while connected: IllegalStateException")
		void doubleStartRejected() throws Exception {
			String cli = writeFakeCli(
					"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s\"}",
					null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			t.startSession("first", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThatThrownBy(() -> t.startSession("second", CLIOptions.builder().build(), m -> {
			}, null, null)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Cannot start session in state");
			t.close();
		}

		@Test
		@DisplayName("startSession after close: IllegalStateException (cannot be reused)")
		void startAfterCloseRejected() throws Exception {
			String cli = writeFakeCli(null, null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			t.close();
			assertThatThrownBy(() -> t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("cannot be reused");
		}

		@Test
		@DisplayName("waitForCompletion with null process: returns true immediately")
		void waitForCompletionWithoutProcess() throws Exception {
			StdioTransport t = new StdioTransport(tempDir);
			assertThat(t.waitForCompletion(Duration.ofSeconds(1))).isTrue();
			assertThat(t.isRunning()).isFalse();
			assertThat(t.getSessionError()).isNull();
			assertThat(t.getSessionId()).isNull();
			assertThat(t.getStateName()).isEqualTo("DISCONNECTED");
			t.close();
		}

		@Test
		@DisplayName("sendUserMessage after close: SessionClosedException")
		void sendAfterClosedThrows() throws Exception {
			String cli = writeFakeCli(null, null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			t.close();
			assertThatThrownBy(() -> t.sendUserMessage("x", "s")).isInstanceOf(SessionClosedException.class);
			assertThatThrownBy(() -> t.sendMessage("{}")).isInstanceOf(SessionClosedException.class);
			assertThatThrownBy(() -> t.sendResponse(ControlResponse.success("r1", null)))
					.isInstanceOf(SessionClosedException.class);
		}

		@Test
		@DisplayName("sendPromptViaStdin without writer: TransportException explaining one-shot")
		void promptViaStdinWithoutWriter() throws Exception {
			String cli = writeFakeCli(null, null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			// 未 startSession → stdinWriter 为 null → one-shot 语义提示
			assertThatThrownBy(() -> t.sendPromptViaStdin("x")).isInstanceOf(TransportException.class)
					.hasMessageContaining("one-shot");
			t.close();
		}

		@Test
		@DisplayName("interrupt: long-running process gets destroyed")
		void interruptDestroysProcess() throws Exception {
			String cli = writeFakeCli("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"long-1\"}", null,
					30_000, 0, false);
			StdioTransport t = newTransport(cli);
			t.startSession("long", CLIOptions.builder().build(), m -> {
			}, null, null);

			assertThat(t.isRunning()).isTrue();
			t.interrupt();
			// destroy 后进程很快退出（SIGTERM → sleep 中断）
			long deadline = System.currentTimeMillis() + 15_000;
			while (t.isRunning() && System.currentTimeMillis() < deadline) {
				Thread.sleep(100);
			}
			assertThat(t.isRunning()).isFalse();
			t.close();
		}
	}

	// ---------- 退出码语义 ----------

	@Nested
	@DisplayName("Exit code semantics")
	class ExitCodes {

		@Test
		@DisplayName("exit 1 without result event: failure")
		void exit1WithoutResultFails() throws Exception {
			String cli = writeFakeCli(
					"{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}}",
					"model error", 0, 1, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThatThrownBy(() -> t.waitForCompletion(Duration.ofSeconds(15))).isInstanceOf(TransportException.class)
					.hasMessageContaining("CLI process failed");
			t.close();
		}

		@Test
		@DisplayName("exit 0 after result: success")
		void exit0WithResultSucceeds() throws Exception {
			String cli = writeFakeCli(
					"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\",\"session_id\":\"e0\"}",
					null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThat(t.waitForCompletion(Duration.ofSeconds(15))).isTrue();
			t.close();
		}

		@Test
		@DisplayName("exit 2: max turns exceeded message")
		void exit2MaxTurns() throws Exception {
			String cli = writeFakeCli(null, null, 0, 2, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThatThrownBy(() -> t.waitForCompletion(Duration.ofSeconds(15))).isInstanceOf(TransportException.class)
					.hasMessageContaining("maximum number of turns")
					.satisfies(e -> assertThat(((TransportException) e).getExitCode()).isEqualTo(2));
			t.close();
		}

		@Test
		@DisplayName("exit 3: no prompt message")
		void exit3NoPrompt() throws Exception {
			String cli = writeFakeCli(null, null, 0, 3, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThatThrownBy(() -> t.waitForCompletion(Duration.ofSeconds(15))).isInstanceOf(TransportException.class)
					.hasMessageContaining("no prompt");
			t.close();
		}

		@Test
		@DisplayName("exit 4: budget exceeded message")
		void exit4Budget() throws Exception {
			String cli = writeFakeCli(null, null, 0, 4, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThatThrownBy(() -> t.waitForCompletion(Duration.ofSeconds(15))).isInstanceOf(TransportException.class)
					.hasMessageContaining("maximum budget");
			t.close();
		}

		@Test
		@DisplayName("waitForCompletion timeout: returns false, process still alive")
		void waitTimeoutReturnsFalse() throws Exception {
			String cli = writeFakeCli(null, null, 30_000, 0, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			assertThat(t.waitForCompletion(Duration.ofMillis(500))).isFalse();
			t.close();
		}
	}

	// ---------- stderr / 控制请求 / 消息流 ----------

	@Nested
	@DisplayName("Streams and control protocol")
	class Streams {

		@Test
		@DisplayName("stderr lines routed to custom StderrHandler")
		void stderrHandlerReceivesLines() throws Exception {
			stderrLines.clear();
			String cli = writeFakeCli(
					"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s1\"}",
					"warn: something on stderr", 0, 0, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().stderrHandler(stderrLines::add).build(), m -> {
			}, null, null);
			t.waitForCompletion(Duration.ofSeconds(15));

			long deadline = System.currentTimeMillis() + 5_000;
			while (stderrLines.isEmpty() && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			assertThat(stderrLines).anyMatch(l -> l.contains("something on stderr"));
			t.close();
		}

		@Test
		@DisplayName("control request routed to handler; response written (or dropped after one-shot)")
		void controlRequestRouted() throws Exception {
			controlRequestSubtypes.clear();
			String cli = writeFakeCli(
					"{\"type\":\"control_request\",\"request_id\":\"req_1\",\"request\":{\"subtype\":\"hook_callback\",\"callback_id\":\"cb_1\",\"input\":{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"}}}}\n"
							+ "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"cr-1\"}",
					null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			Collector collector = new Collector();
			t.startSession("hi", CLIOptions.builder().build(), collector, req -> {
				controlRequestSubtypes.add(req.request() != null ? req.request().subtype() : "null");
				return ControlResponse.success(req.requestId(), null);
			}, null);
			t.waitForCompletion(Duration.ofSeconds(15));
			awaitEndOfStream(collector.eos);

			assertThat(controlRequestSubtypes).contains("hook_callback");
			t.close();
		}

		@Test
		@DisplayName("malformed stdout is a protocol failure; unknown JSON types remain forward-compatible")
		void garbageLinesFailTheSession() throws Exception {
			String cli = writeFakeCli("not-json-at-all\n"
					+ "{\"type\":\"totally_unknown_type\"}\n"
					+ "\n"
					+ "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"g1\"}",
					null, 0, 0, false);
			Collector collector = new Collector();
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), collector, null, null);
			assertThatThrownBy(() -> t.waitForCompletion(Duration.ofSeconds(15)))
					.hasMessageContaining("Session error");
			awaitEndOfStream(collector.eos);
			t.close();

			// Malformed protocol input terminates the stream before the later result can be trusted.
			assertThat(collector.messages).isEmpty();
			assertThat(t.getSessionError()).isNotNull();
		}

		@Test
		@DisplayName("messageIterator/Iterable and reactive Flux surface same stream")
		void iteratorAndFluxViews() throws Exception {
			String cli = writeFakeCli(
					"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"it-1\"}",
					null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
			}, null, null);
			t.waitForCompletion(Duration.ofSeconds(15));

			int count = 0;
			for (ParsedMessage m : t.messageIterable()) {
				if (m.isResultMessage()) {
					count++;
				}
			}
			assertThat(count).isEqualTo(1);

			Flux<ParsedMessage> flux = t.getInboundFlux();
			CountDownLatch got = new CountDownLatch(1);
			flux.subscribe(m -> {
				if (m.isResultMessage()) {
					got.countDown();
				}
			});
			assertThat(got.await(5, TimeUnit.SECONDS)).isTrue();
			t.close();
		}

		@Test
		@DisplayName("EndOfStream sentinel delivered when stdout closes")
		void endOfStreamDelivered() throws Exception {
			String cli = writeFakeCli(null, null, 0, 0, false);
			List<ParsedMessage> eos = new CopyOnWriteArrayList<>();
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().build(), m -> {
				if (m == ParsedMessage.EndOfStream.INSTANCE) {
					eos.add(m);
				}
			}, null, null);
			assertThatThrownBy(() -> t.waitForCompletion(Duration.ofSeconds(15)))
					.hasMessageContaining("Session error");

			long deadline = System.currentTimeMillis() + 5_000;
			while (eos.isEmpty() && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			assertThat(eos).hasSize(1);
			t.close();
		}

		@Test
		@DisplayName("closeGracefully: completes and moves state to CLOSED")
		void closeGracefullyWorks() throws Exception {
			String cli = writeFakeCli(null, null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			t.closeGracefully().block(Duration.ofSeconds(10));
			assertThat(t.getState()).isEqualTo(Transport.STATE_CLOSED);
			// 幂等
			t.closeGracefully().block(Duration.ofSeconds(10));
			t.close();
		}
	}

	// ---------- 环境变量 / 权限回调 ----------

	@Nested
	@DisplayName("Env and permission callback")
	class EnvAndPermission {

		@Test
		@DisplayName("SDK identity env vars + user env override reach the subprocess")
		void envVarsReachSubprocess() throws Exception {
			String cli = writeFakeCli(
					"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"env-1\"}",
					null, 0, 0, true);

			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().env(java.util.Collections.singletonMap("MY_FLAG", "42")).build(),
					m -> {
					}, null, null);
			t.waitForCompletion(Duration.ofSeconds(15));

			long deadline = System.currentTimeMillis() + 5_000;
			while (envDumpOf().isEmpty() && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);
			}
			List<String> env = envDumpOf();
			assertThat(env).anyMatch(l -> l.startsWith("SOLONCODE_ENTRYPOINT=sdk-java"));
			assertThat(env).anyMatch(l -> l.equals("MY_FLAG=42"));
			t.close();
		}

		@Test
		@DisplayName("handleCanUseTool without callback: allow by default")
		void canUseToolNoCallbackAllows() {
			StdioTransport t = new StdioTransport(tempDir);
			ControlResponse resp = t.handleCanUseTool("req-1", null);
			assertThat(resp.response()).isNotNull();
			t.close();
		}
	}

	// ---------- needsStdinPrompt 判定 ----------

	@Nested
	@DisplayName("needsStdinPrompt")
	class NeedsStdinPrompt {

		@Test
		void nullOrEmptyStaysArgv() {
			assertThat(StdioTransport.needsStdinPrompt(null)).isFalse();
			assertThat(StdioTransport.needsStdinPrompt("")).isFalse();
		}

		@Test
		void dashEqualsDotForceStdin() {
			assertThat(StdioTransport.needsStdinPrompt("-flag")).isTrue();
			assertThat(StdioTransport.needsStdinPrompt("a=b")).isTrue();
			assertThat(StdioTransport.needsStdinPrompt("分析 v3.2 的模块")).isTrue();
		}

		@Test
		void plainTextStaysArgv() {
			assertThat(StdioTransport.needsStdinPrompt("hello world")).isFalse();
			assertThat(StdioTransport.needsStdinPrompt("你好，帮我看看")).isFalse();
		}
	}

	// ---------- 轻量 API 表面（sendMessage/sendUserMessage/receiveMessages 等） ----------

	@Nested
	@DisplayName("API surface")
	class ApiSurface {

		@Test
		void sendMessageQueuesOutboundAndFluxSeesInbound() throws Exception {
			String cli = writeFakeCli(String.format(RESULT_OK, "api-1"), null, 0, 0, false);
			StdioTransport t = newTransport(cli);
			Collector collector = new Collector();
			t.startSession("hi", CLIOptions.builder().build(), collector, null, null);
			// CONNECTED 状态下 sendMessage/sendUserMessage 不抛（one-shot 模型 stdin 已关，写不进去但排队成功）
			t.sendMessage("raw");
			assertThat(t.waitForCompletion(Duration.ofSeconds(15))).isTrue();
			awaitEndOfStream(collector.eos);

			// reactive 视图（receiveMessages/getInboundFlux/getServerInfo）与迭代器视图可订阅
			assertThat(t.receiveMessages().filter(ParsedMessage::isResultMessage)
					.blockFirst(Duration.ofSeconds(5))).isNotNull();
			assertThat(t.messageIterator().hasNext()).isTrue();
			assertThat(t.getToolPermissionCallback()).isNull();
			t.close();
		}

		@Test
		@DisplayName("sudo wrap: user option prefixes sudo -u on Unix")
		void userOptionWrapsWithSudo() throws Exception {
			StdioTransport t = new StdioTransport(tempDir, Duration.ofSeconds(5), "/usr/bin/soloncode");
			// wrapCommandForUser 在 startSession 内生效；此处反射直测（sudo 需免密，真进程路径不可移植）
			java.lang.reflect.Method wrap = StdioTransport.class
					.getDeclaredMethod("wrapCommandForUser", List.class, String.class);
			wrap.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<String> wrapped = (List<String>) wrap.invoke(t,
					java.util.Arrays.asList("/usr/bin/soloncode", "run"), "runner");
			assertThat(wrapped.get(0)).isEqualTo("sudo");
			assertThat(wrapped.get(1)).isEqualTo("-u");
			assertThat(wrapped.get(2)).isEqualTo("runner");
			assertThat(wrapped.get(3)).isEqualTo("/usr/bin/soloncode");
			// null/blank user 不包装
			@SuppressWarnings("unchecked")
			List<String> untouched = (List<String>) wrap.invoke(t,
					java.util.Arrays.asList("/usr/bin/soloncode", "run"), "  ");
			assertThat(untouched.get(0)).isEqualTo("/usr/bin/soloncode");
			t.close();
		}

		@Test
		@DisplayName("handleCanUseTool: allow/deny/updated_input/callback error paths")
		void canUseToolCallbackPaths() throws Exception {
			// 需要 CONNECTED 才有 currentToolPermissionCallback？——直接用 startSession 注入回调
			String cli = writeFakeCli(String.format(RESULT_OK, "perm-1"), null, 0, 0, false);
			java.util.List<String> outcomes = new CopyOnWriteArrayList<>();
			StdioTransport t = newTransport(cli);
			t.startSession("hi", CLIOptions.builder().toolPermissionCallback((tool, input, ctx) -> {
				outcomes.add(tool);
				return java.util.concurrent.CompletableFuture.completedFuture(
						new org.noear.soloncode.sdk.transport.ToolPermissionCallback.ToolPermissionResult.Allow(null));
			}).build(), m -> {
			}, null, null);
			t.waitForCompletion(Duration.ofSeconds(15));
			assertThat(t.getToolPermissionCallback()).isNotNull();

			// allow（无 updated_input）
			org.noear.soloncode.sdk.types.control.ControlRequest.CanUseToolRequest req =
					new org.noear.soloncode.sdk.types.control.ControlRequest.CanUseToolRequest(
							"Write", java.util.Collections.emptyMap(), null, null);
			org.noear.soloncode.sdk.types.control.ControlResponse allow = t.handleCanUseTool("r-allow", req);
			assertThat(allow.response()).isNotNull();
			// deny（含 interrupt）
			t.close();

			// 未配置回调路径在 close 后仍可用：allow by default
			StdioTransport t2 = newTransport(cli);
			org.noear.soloncode.sdk.types.control.ControlResponse dft = t2.handleCanUseTool("r-default", null);
			assertThat(dft.response()).isNotNull();
			t2.close();
		}


	@Nested
	@DisplayName("Persistent stream mode")
	class PersistentStream {
		@Test
		void reusesOneProcessForTwoTurnsAndInterruptKeepsItAlive() throws Exception {
			Path stdinLog = tempDir.resolve("stream-stdin.log");
			String scriptText = "#!/bin/bash\n"
					+ "printf '%s\\n' -- \"$@\" >> '" + tempDir.resolve("argv.log").toAbsolutePath() + "'\n"
					+ "printf '%s\\n' '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"stream-1\",\"model\":\"fake\"}'\n"
					+ "while IFS= read -r line; do\n"
					+ "  printf '%s\\n' \"$line\" >> '" + stdinLog.toAbsolutePath() + "'\n"
					+ "  case \"$line\" in\n"
					+ "    *control_request*) printf '%s\\n' '{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\"}}';;\n"
					+ "    *) printf '%s\\n' '{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}';"
					+ " printf '%s\\n' '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\",\"session_id\":\"stream-1\"}';;\n"
					+ "  esac\n"
					+ "done\n";
			Path script = tempDir.resolve("fake-stream-soloncode");
			Files.write(script, scriptText.getBytes(StandardCharsets.UTF_8));
			script.toFile().setExecutable(true);

			CountDownLatch results = new CountDownLatch(2);
			CountDownLatch control = new CountDownLatch(1);
			StdioTransport t = new StdioTransport(tempDir, Duration.ofSeconds(20),
					script.toAbsolutePath().toString(), true);
			t.startSession("first", CLIOptions.builder().build(), m -> {
				if (m.isResultMessage()) {
					results.countDown();
				}
			}, null, response -> control.countDown());
			t.sendUserMessage("second", "stream-1");

			assertThat(results.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(t.isRunning()).isTrue();
			t.interrupt();
			assertThat(control.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(t.isRunning()).isTrue();
			t.close();

			List<String> argv = argvOf();
			assertThat(argv).contains("stream").doesNotContain("run", "--output-format");
			List<String> sent = Files.readAllLines(stdinLog, StandardCharsets.UTF_8);
			assertThat(sent).hasSize(3);
			assertThat(sent.get(0)).contains("\"type\":\"user\"").contains("first");
			assertThat(sent.get(1)).contains("\"type\":\"user\"").contains("second");
			assertThat(sent.get(2)).contains("\"type\":\"control_request\"").contains("interrupt");
		}
	}

	// ---------- API surface ----------

		@Test
		void transportSpecApiSurface() {
			assertThat(TransportSpec.stdio().toString()).isEqualTo("stdio-stream");
			assertThat(TransportSpec.stdio().isPersistent()).isTrue();
			assertThat(TransportSpec.stdio("/x/soloncode").toString()).isEqualTo("stdio-stream(/x/soloncode)");
			assertThat(TransportSpec.stdioOneShot("/x/soloncode").toString()).isEqualTo("stdio-run(/x/soloncode)");
			Transport t = TransportSpec.stdio("/x/soloncode").create(tempDir, Duration.ofSeconds(5));
			assertThat(t).isInstanceOf(StdioTransport.class);
			((StdioTransport) t).close();

			assertThat(TransportSpec.http("http://127.0.0.1:1/web/run").toString())
					.isEqualTo("http(http://127.0.0.1:1/web/run)");
		}
	}
}
