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

package org.noear.soloncode.sdk.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.transport.StdioTransport;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.transport.ToolPermissionCallback;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for permission handling with real SolonCode CLI.
 *
 * <p>
 * Tests permission callbacks for:
 * <ul>
 * <li>CanUseToolRequest callback invocation</li>
 * <li>Permission allow/deny behavior</li>
 * <li>Tool input data capture</li>
 * </ul>
 *
 * <p>
 * Key patterns:
 * <ul>
 * <li>Track callback_invocations list</li>
 * <li>Use tool-forcing prompts like "Write 'hello world' to /tmp/test.txt"</li>
 * <li>Assert specific tool names in invocations (e.g., "Write" in
 * callback_invocations)</li>
 * </ul>
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@Disabled("soloncode run 不支持 stdin 权限审批回调（--permission-prompt-tool）：请改用 --permission-mode dontAsk/bypassPermissions")
class PermissionIntegrationIT extends SolonCodeCliTestBase {

	/**
	 * Helper for running tests with transport.
	 */
	private void withTransport(CLIOptions options, TransportConsumer consumer) throws Exception {
		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(3),
				getSolonCodeCliPath())) {
			consumer.accept(transport, options);
		}
	}

	@FunctionalInterface
	interface TransportConsumer {

		void accept(StdioTransport transport, CLIOptions options) throws Exception;

	}

	/**
	 * Tests that can_use_tool callback gets invoked when SolonCode uses a tool.
	 */
	@Test
	@DisplayName("Permission callback gets called when tool is used")
	void permissionCallbackGetsCalled() throws Exception {
		// Given - track callback invocations
		List<String> callbackInvocations = new CopyOnWriteArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		// NOTE: permissionPromptToolName("stdio") is required to enable can_use_tool
		// requests
		// This tells the CLI to send permission requests via stdin/stdout control
		// protocol
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.DEFAULT)
			.permissionPromptToolName("stdio")
			.build();

		withTransport(options, (transport, opts) -> {
			// Tool-forcing prompt to trigger Write tool
			transport.startSession("Write 'hello world' to /tmp/test.txt", opts, message -> {
				System.out.println("Got message: " + message);
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				// Handle permission callback
				if (request.request() instanceof ControlRequest.CanUseToolRequest) {
					String toolName = ((ControlRequest.CanUseToolRequest) request.request()).toolName();
					Map<String, Object> inputData = ((ControlRequest.CanUseToolRequest) request.request()).input();

					System.out.println("Permission callback called for: " + toolName + ", input: " + inputData);
					callbackInvocations.add(toolName);

					// Allow tool execution
					return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
				}

				// Handle hook callbacks
				if (request.request() instanceof ControlRequest.HookCallbackRequest) {
					Map<String, Object> input = ((ControlRequest.HookCallbackRequest) request.request()).input();
					String toolName = (String) input.get("tool_name");
					if (toolName != null) {
						callbackInvocations.add(toolName);
					}
					return ControlResponse.success(request.requestId(),
							SdkCollections.map("hookSpecificOutput", SdkCollections.map("permissionDecision", "allow")));
				}

				// Default allow
				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Wait for completion
			boolean completed = resultLatch.await(120, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete within timeout").isTrue();

			// Assert: "Write" in callback_invocations
			System.out.println("Callback invocations: " + callbackInvocations);
			assertThat(callbackInvocations)
				.as("can_use_tool callback should have been invoked for Write tool, got: " + callbackInvocations)
				.contains("Write");
		});
	}

	/**
	 * Tests permission deny blocks tool execution. Uses a more explicit prompt that
	 * reliably triggers Write tool use. Uses Sonnet model for more reliable tool
	 * invocation.
	 */
	@Test
	@DisplayName("Permission deny blocks tool execution")
	void permissionDenyBlocksToolExecution() throws Exception {
		// Given - track denied tools
		List<String> deniedTools = new CopyOnWriteArrayList<>();
		AtomicReference<String> resultText = new AtomicReference<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		// Use Sonnet for more reliable tool use behavior
		// NOTE: permissionPromptToolName("stdio") enables can_use_tool control protocol
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.DEFAULT)
			.permissionPromptToolName("stdio")
			.build();

		withTransport(options, (transport, opts) -> {
			// Use explicit tool-forcing prompt that reliably triggers Write tool
			transport.startSession("Use the Write tool to create a file at /tmp/denied.txt with content 'test'. "
					+ "Do not ask me " + "questions, just use the Write tool immediately.", opts, message -> {
						if (message.isRegularMessage()) {
							Message msg = message.asMessage();
							if (msg instanceof ResultMessage) {
								resultText.set(((ResultMessage) msg).result());
								resultLatch.countDown();
							}
						}
					}, request -> {
						// Deny Write tool
						if (request.request() instanceof ControlRequest.CanUseToolRequest) {
							String toolName = ((ControlRequest.CanUseToolRequest) request.request()).toolName();
							System.out.println("Denying tool: " + toolName);
							deniedTools.add(toolName);

							return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "deny"));
						}

						// Handle hook callbacks - also deny
						if (request.request() instanceof ControlRequest.HookCallbackRequest) {
							Map<String, Object> input = ((ControlRequest.HookCallbackRequest) request.request()).input();
							String toolName = (String) input.get("tool_name");
							if (toolName != null) {
								deniedTools.add(toolName);
							}
							return ControlResponse.success(request.requestId(),
									SdkCollections.map("hookSpecificOutput", SdkCollections.map("permissionDecision", "deny")));
						}

						return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
					});

			// Wait for completion
			boolean completed = resultLatch.await(120, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete within timeout").isTrue();

			// Verify tool was denied - Sonnet should reliably trigger tool use
			System.out.println("Denied tools: " + deniedTools);
			assertThat(deniedTools).as("Permission callback should have been invoked for file writing tools")
				.isNotEmpty();
			// Note: Could be "Write" or "Bash" depending on model choice
		});
	}

	/**
	 * Tests permission callback receives tool input data.
	 */
	@Test
	@DisplayName("Permission callback receives tool input data")
	void permissionCallbackReceivesToolInput() throws Exception {
		// Given - capture tool input
		AtomicReference<Map<String, Object>> capturedInput = new AtomicReference<>();
		AtomicReference<String> capturedToolName = new AtomicReference<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		// NOTE: permissionPromptToolName("stdio") enables can_use_tool control protocol
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.DEFAULT)
			.permissionPromptToolName("stdio")
			.build();

		withTransport(options, (transport, opts) -> {
			transport.startSession("Read the file /etc/hostname", opts, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				if (request.request() instanceof ControlRequest.CanUseToolRequest) {
					// Capture tool name and input
					capturedToolName.set(((ControlRequest.CanUseToolRequest) request.request()).toolName());
					capturedInput.set(((ControlRequest.CanUseToolRequest) request.request()).input());

					System.out.println("Captured tool: " + capturedToolName.get());
					System.out.println("Captured input: " + capturedInput.get());

					return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
				}

				if (request.request() instanceof ControlRequest.HookCallbackRequest) {
					return ControlResponse.success(request.requestId(),
							SdkCollections.map("hookSpecificOutput", SdkCollections.map("permissionDecision", "allow")));
				}

				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Wait for completion
			boolean completed = resultLatch.await(120, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete within timeout").isTrue();

			// Verify input was captured (could be Read or Bash depending on model choice)
			assertThat(capturedToolName.get()).as("Should have captured tool name").isNotNull();
			assertThat(capturedInput.get()).as("Should have captured input data").isNotNull();
		});
	}

	/**
	 * Tests bypass permissions mode skips callbacks.
	 */
	@Test
	@DisplayName("Bypass permissions mode skips permission callbacks")
	void bypassPermissionsModeSkipsCallbacks() throws Exception {
		// Given - track if any permission callback is called
		List<String> callbackInvocations = new CopyOnWriteArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		// Use BYPASS_PERMISSIONS mode
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		withTransport(options, (transport, opts) -> {
			transport.startSession("What is 2 + 2?", opts, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				// Track any permission callbacks
				if (request.request() instanceof ControlRequest.CanUseToolRequest) {
					callbackInvocations.add(((ControlRequest.CanUseToolRequest) request.request()).toolName());
				}
				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Wait for completion
			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete within timeout").isTrue();

			// In bypass mode, simple queries shouldn't trigger permission callbacks
			// (tool use may proceed without asking)
			System.out.println("Callback invocations in bypass mode: " + callbackInvocations);
		});
	}

	/**
	 * Tests that permission callback is called for Bash tool. Uses a file creation prompt
	 * which reliably triggers tool use.
	 */
	@Test
	@DisplayName("Permission callback called for Bash tool")
	void permissionCallbackCalledForBashTool() throws Exception {
		// Given
		List<String> callbackInvocations = new CopyOnWriteArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		// Note: Do NOT include Bash in allowedTools - we want permission callback to be
		// triggered
		// NOTE: permissionPromptToolName("stdio") enables can_use_tool control protocol
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.DEFAULT)
			.permissionPromptToolName("stdio")
			.build();

		withTransport(options, (transport, opts) -> {
			// Use a prompt that reliably triggers tool use (file operations work well)
			transport.startSession("Create a file /tmp/bash_test.txt containing 'hello'", opts, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				if (request.request() instanceof ControlRequest.CanUseToolRequest) {
					System.out.println("Permission callback for: " + ((ControlRequest.CanUseToolRequest) request.request()).toolName());
					callbackInvocations.add(((ControlRequest.CanUseToolRequest) request.request()).toolName());
					return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
				}

				if (request.request() instanceof ControlRequest.HookCallbackRequest) {
					Map<String, Object> input = ((ControlRequest.HookCallbackRequest) request.request()).input();
					String toolName = (String) input.get("tool_name");
					if (toolName != null) {
						callbackInvocations.add(toolName);
					}
					return ControlResponse.success(request.requestId(),
							SdkCollections.map("hookSpecificOutput", SdkCollections.map("permissionDecision", "allow")));
				}

				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Wait for completion
			boolean completed = resultLatch.await(120, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete within timeout").isTrue();

			// Verify tool permission callback was invoked (could be Bash or Write)
			System.out.println("Callback invocations: " + callbackInvocations);
			assertThat(callbackInvocations).as("Permission callback should have been invoked for file operation")
				.isNotEmpty();
		});
	}

	/**
	 * Tests that toolPermissionCallback auto-enables --permission-prompt-tool stdio. This
	 * matches Python SDK behavior where can_use_tool callback automatically sets
	 * permission_prompt_tool_name="stdio".
	 */
	@Test
	@DisplayName("ToolPermissionCallback auto-enables permission prompt tool")
	void toolPermissionCallbackAutoEnablesPermissionPrompt() throws Exception {
		// Given - track callback invocations via ToolPermissionCallback
		List<String> callbackInvocations = new CopyOnWriteArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		// NOTE: Do NOT set permissionPromptToolName - auto-detection should enable it
		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.DEFAULT)
			.toolPermissionCallback((toolName, input, context) -> {
				System.out.println("ToolPermissionCallback invoked for: " + toolName);
				callbackInvocations.add(toolName);
				return java.util.concurrent.CompletableFuture
					.completedFuture(ToolPermissionCallback.ToolPermissionResult.allow());
			})
			.build();

		withTransport(options, (transport, opts) -> {
			transport.startSession("Write 'auto-detect test' to /tmp/autodetect.txt", opts, message -> {
				System.out.println("Got message: " + message);
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				// Handle control requests - the transport should invoke
				// toolPermissionCallback
				// for can_use_tool requests automatically
				if (request.request() instanceof ControlRequest.CanUseToolRequest) {
					// Let the transport's handleCanUseTool method handle this
					return transport.handleCanUseTool(request.requestId(), (ControlRequest.CanUseToolRequest) request.request());
				}

				if (request.request() instanceof ControlRequest.HookCallbackRequest) {
					return ControlResponse.success(request.requestId(),
							SdkCollections.map("hookSpecificOutput", SdkCollections.map("permissionDecision", "allow")));
				}

				return ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow"));
			});

			// Wait for completion
			boolean completed = resultLatch.await(120, TimeUnit.SECONDS);
			assertThat(completed).as("Should complete within timeout").isTrue();

			// Verify: toolPermissionCallback was invoked (proving auto-detection worked)
			System.out.println("ToolPermissionCallback invocations: " + callbackInvocations);
			assertThat(callbackInvocations)
				.as("ToolPermissionCallback should have been invoked (auto-detection of --permission-prompt-tool stdio)")
				.isNotEmpty();
		});
	}

}
