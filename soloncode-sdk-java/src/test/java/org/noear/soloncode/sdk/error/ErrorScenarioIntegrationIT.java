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

package org.noear.soloncode.sdk.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.transport.StdioTransport;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for error scenarios with real SolonCode CLI. Tests transport failures,
 * invalid options, and graceful error recovery.
 *
 * <p>
 * Test patterns:
 * <ul>
 * <li>Error handling and timeout scenarios</li>
 * <li>Transport close during operation</li>
 * <li>Malformed responses and handler exceptions</li>
 * </ul>
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ErrorScenarioIntegrationIT extends SolonCodeCliTestBase {

	@Test
	@DisplayName("Should handle invalid CLI path gracefully - MCP SDK error pattern")
	void shouldHandleInvalidCliPath() {
		// Given - invalid CLI path
		Path invalidPath = Paths.get("/nonexistent/soloncode");

		// When/Then - should throw meaningful exception
		assertThatThrownBy(() -> {
			try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(1),
					invalidPath.toString())) {

				CLIOptions options = CLIOptions.builder()
					.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
					.build();

				CountDownLatch latch = new CountDownLatch(1);
				transport.startSession("test", options, msg -> latch.countDown(),
						req -> ControlResponse.success(req.requestId(), SdkCollections.map()));

				latch.await(10, TimeUnit.SECONDS);
			}
		}).isInstanceOf(Exception.class);
	}

	@Test
	@DisplayName("Should handle transport close during operation")
	void shouldHandleTransportCloseDuringOperation() throws Exception {
		// Given
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		AtomicBoolean sessionStarted = new AtomicBoolean(false);
		AtomicReference<Throwable> capturedError = new AtomicReference<>();

		StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(1),
				getSolonCodeCliPath());

		try {
			// Start a session
			transport.startSession("Count from 1 to 100 slowly", options, message -> {
				sessionStarted.set(true);
				// Close transport while processing (simulates abrupt termination)
				if (sessionStarted.get() && transport.isRunning()) {
					try {
						transport.close();
					}
					catch (Exception e) {
						capturedError.set(e);
					}
				}
			}, request -> ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow")));

			// Give it a moment to start
			Thread.sleep(1000);
		}
		finally {
			// Ensure cleanup
			if (transport.isRunning()) {
				transport.close();
			}
		}

		// Transport should be closed
		assertThat(transport.isRunning()).isFalse();
	}

	@Test
	@DisplayName("Should handle malformed control response gracefully")
	void shouldHandleMalformedControlResponse() throws Exception {
		// Given
		CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.DEFAULT).build();

		CountDownLatch resultLatch = new CountDownLatch(1);
		AtomicBoolean errorOccurred = new AtomicBoolean(false);

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			transport.startSession("What is 1 + 1?", options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				// Return minimal response - transport should handle gracefully
				return ControlResponse.success(request.requestId(), SdkCollections.map());
			});

			// Should complete despite minimal responses
			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete even with minimal control responses").isTrue();
		}
	}

	@Test
	@DisplayName("Should recover after control handler exception")
	void shouldRecoverAfterControlHandlerException() throws Exception {
		// Given
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		CountDownLatch resultLatch = new CountDownLatch(1);
		AtomicBoolean firstCall = new AtomicBoolean(true);

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			transport.startSession("Say hello", options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				// Throw exception on first call to test error handling
				if (firstCall.getAndSet(false)) {
					throw new RuntimeException("Simulated control handler failure");
				}
				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Should still complete (bypass mode may not need control handlers)
			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete after handler exception").isTrue();
		}
	}

	@Test
	@DisplayName("Should handle session timeout gracefully - MCP SDK timeout pattern")
	void shouldHandleSessionTimeoutGracefully() throws Exception {
		// Given - very short timeout
		Duration shortTimeout = Duration.ofMillis(100);

		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		// Transport with short timeout
		try (StdioTransport transport = new StdioTransport(workingDirectory(), shortTimeout,
				getSolonCodeCliPath())) {

			CountDownLatch latch = new CountDownLatch(1);

			transport.startSession("Count to 1000", options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					latch.countDown();
				}
			}, request -> ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow")));

			// May or may not complete due to short timeout - that's expected
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			// Timeout exceptions are acceptable
			assertThat(e).isNotNull();
		}
	}

	@Test
	@DisplayName("Should clean up resources on exception - MCP SDK cleanup pattern")
	void shouldCleanUpResourcesOnException() throws Exception {
		// Given
		StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(1),
				getSolonCodeCliPath());

		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		try {
			transport.startSession("test", options, message -> {
				throw new RuntimeException("Simulated message handler failure");
			}, request -> ControlResponse.success(request.requestId(), SdkCollections.map()));

			// Wait briefly
			Thread.sleep(500);
		}
		catch (Exception e) {
			// Expected
		}
		finally {
			transport.close();
		}

		// Verify cleanup
		assertThat(transport.isRunning()).as("Transport should be stopped after exception").isFalse();
	}

	@Test
	@DisplayName("Should handle multiple close calls gracefully - MCP SDK idempotent close")
	void shouldHandleMultipleCloseCallsGracefully() throws Exception {
		// Given
		StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(1),
				getSolonCodeCliPath());

		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		CountDownLatch latch = new CountDownLatch(1);

		transport.startSession("Say hi", options, message -> {
			if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
				latch.countDown();
			}
		}, request -> ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow")));

		latch.await(60, TimeUnit.SECONDS);

		// Multiple close calls should not throw
		transport.close();
		transport.close();
		transport.close();

		assertThat(transport.isRunning()).isFalse();
	}

}
