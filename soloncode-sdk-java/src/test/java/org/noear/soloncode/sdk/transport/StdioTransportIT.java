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
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for StdioTransport with real SolonCode CLI.
 *
 * <p>
 * These tests verify the bidirectional communication protocol works correctly with the
 * actual SolonCode CLI executable.
 * </p>
 */
class StdioTransportIT extends SolonCodeCliTestBase {

	@Test
	@DisplayName("Should start session and receive messages")
	void shouldStartSessionAndReceiveMessages() throws Exception {
		// Given - 不传 --model，用工作区默认模型
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		List<ParsedMessage> receivedMessages = new ArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			// When
			transport.startSession("What is 2+2? Reply with just the number.", options, message -> {
				receivedMessages.add(message);
				if (message.isRegularMessage()) {
					Message msg = message.asMessage();
					if (msg instanceof ResultMessage) {
						resultLatch.countDown();
					}
				}
			}, request -> {
				// Default handler - allow all
				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Wait for result
			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);

			// Then
			assertThat(completed).as("Should receive result within timeout").isTrue();
			assertThat(receivedMessages).isNotEmpty();

			// Should have at least one assistant message
			boolean hasAssistant = receivedMessages.stream()
				.filter(ParsedMessage::isRegularMessage)
				.map(ParsedMessage::asMessage)
				.anyMatch(m -> m instanceof AssistantMessage);
			assertThat(hasAssistant).as("Should receive assistant message").isTrue();

			// Should have a result message
			boolean hasResult = receivedMessages.stream()
				.filter(ParsedMessage::isRegularMessage)
				.map(ParsedMessage::asMessage)
				.anyMatch(m -> m instanceof ResultMessage);
			assertThat(hasResult).as("Should receive result message").isTrue();
		}
	}

	@Test
	@DisplayName("Should handle control requests")
	void shouldHandleControlRequests() throws Exception {
		// Given - 不传 --model，用工作区默认模型
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		List<ControlRequest> controlRequests = new ArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			// When
			transport.startSession("What is the capital of France? Just say the city name.", options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				controlRequests.add(request);
				// Always allow
				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Wait for result
			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);

			// Then
			assertThat(completed).as("Should complete within timeout").isTrue();
			// Control requests may or may not be sent depending on CLI behavior
			// but if they are, we should have handled them
		}
	}

	@Test
	@DisplayName("Should report running state correctly")
	void shouldReportRunningState() throws Exception {
		// Given - 不传 --model，用工作区默认模型
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		CountDownLatch startedLatch = new CountDownLatch(1);
		CountDownLatch resultLatch = new CountDownLatch(1);

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			// Initially not running
			assertThat(transport.isRunning()).isFalse();

			// When started
			transport.startSession("Say hello", options, message -> {
				startedLatch.countDown();
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow")));

			// Should be running after start
			assertThat(transport.isRunning()).isTrue();

			// Wait for result
			boolean gotResult = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(gotResult).as("Should receive result within timeout").isTrue();

			// In bidirectional mode, the CLI stays alive waiting for more input
			// until explicitly closed. This is expected behavior for persistent sessions.
			// The transport is still "running" even after receiving a result.
		}
		// After close(), should not be running
		// (close is called automatically by try-with-resources)
	}

	@Test
	@DisplayName("Should handle interrupt")
	void shouldHandleInterrupt() throws Exception {
		// Given - 不传 --model，用工作区默认模型
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		AtomicBoolean messageReceived = new AtomicBoolean(false);

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			// When - start a long-running task
			transport.startSession("Count from 1 to 1000, saying each number on a new line", options, message -> {
				messageReceived.set(true);
			}, request -> ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow")));

			// Wait briefly for session to start
			Thread.sleep(2000);

			// Interrupt
			transport.interrupt();

			// Then - should stop running
			Thread.sleep(1000);
			assertThat(transport.isRunning()).isFalse();
		}
	}

	@Test
	@DisplayName("Should close cleanly")
	void shouldCloseCleanly() throws Exception {
		// Given - 不传 --model，用工作区默认模型
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath());

		// When - start and immediately close
		transport.startSession("Say hello", options, message -> {
		}, request -> ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow")));

		// Close should not throw
		transport.close();

		// Then
		assertThat(transport.isRunning()).isFalse();
	}

}
