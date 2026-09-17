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

package org.noear.soloncode.sdk.hooks;

import org.noear.soloncode.sdk.util.SdkJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.transport.StdioTransport;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.types.control.HookOutput;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for hook execution with real SolonCode CLI.
 *
 * <p>
 * Tests hook callbacks for:
 * <ul>
 * <li>PreToolUse hooks with permissionDecision and reason fields</li>
 * <li>PostToolUse hooks with continue=false and stopReason</li>
 * <li>hookSpecificOutput for additional context</li>
 * </ul>
 *
 * <p>
 * Key patterns:
 * <ul>
 * <li>Uses DEFAULT permission mode to receive hook callbacks</li>
 * <li>Uses tool-forcing prompts like "Run: echo 'hello'"</li>
 * <li>Asserts specific tools are invoked (e.g., "Bash" in hook_invocations)</li>
 * </ul>
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@Disabled("soloncode run 不支持 --input-format stream-json 的双向控制协议：hooks 由 CLI 侧工作区配置驱动，SDK 无 stdin 回调通道")
class HookIntegrationIT extends SolonCodeCliTestBase {


	/**
	 * Tests that PreToolUse hooks are called when registered.
	 */
	@Test
	@DisplayName("PreToolUse hook is invoked via StdioTransport")
	void preToolUseHookInvoked() throws Exception {
		// Given - track hook invocations
		List<String> hookInvocations = new CopyOnWriteArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		// Use DEFAULT mode to receive hook callbacks (not BYPASS)
		CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.DEFAULT).build();

		// Create hook registry with PreToolUse hook
		HookRegistry registry = new HookRegistry();
		registry.registerPreToolUse("Bash", input -> {
			System.out.println("PreToolUse hook called for Bash!");
			hookInvocations.add("PreToolUse:Bash");
			return HookOutput.allow();
		});

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			// Start session with hook handling via control request handler
			transport.startSession(null, options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				// Handle hook callbacks
				if (request.request() instanceof ControlRequest.HookCallbackRequest) {
					String callbackId = ((ControlRequest.HookCallbackRequest) request.request()).callbackId();
					// Parse the input to HookInput
					HookInput input = SdkJson.convert(((ControlRequest.HookCallbackRequest) request.request()).input(), HookInput.class);
					HookOutput output = registry.executeHook(callbackId, input);
					return ControlResponse.success(request.requestId(), output);
				}
				return ControlResponse.success(request.requestId(), SdkCollections.map());
			});

			// Send initialize request with hook configuration
			ControlRequest initRequest = registry.createInitializeRequest("init_" + System.currentTimeMillis());
			String initJson = SdkJson.toJsonWithNulls(initRequest);
			transport.sendMessage(initJson);

			// Small delay to ensure initialize is processed
			Thread.sleep(500);

			// Send the prompt that will trigger tool use
			transport.sendUserMessage("Run this bash command: echo 'hello from hook test'", "default");

			// Wait for result
			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).as("Should receive result within timeout").isTrue();
		}

		// Assert: PreToolUse hook should have been called
		System.out.println("Hook invocations: " + hookInvocations);
		assertThat(hookInvocations).as("PreToolUse hook should have been invoked for Bash tool")
			.contains("PreToolUse:Bash");
	}

	/**
	 * Tests that PostToolUse hooks are called after tool execution.
	 */
	@Test
	@DisplayName("PostToolUse hook is invoked after tool execution")
	void postToolUseHookInvoked() throws Exception {
		// Given - track hook invocations
		List<String> hookInvocations = new CopyOnWriteArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.DEFAULT).build();

		// Create hook registry with both PreToolUse (to allow) and PostToolUse
		HookRegistry registry = new HookRegistry();
		registry.registerPreToolUse("Bash", input -> {
			hookInvocations.add("PreToolUse:Bash");
			return HookOutput.allow();
		});
		registry.registerPostToolUse("Bash", input -> {
			System.out.println("PostToolUse hook called for Bash!");
			hookInvocations.add("PostToolUse:Bash");
			return HookOutput.allow();
		});

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			transport.startSession(null, options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				if (request.request() instanceof ControlRequest.HookCallbackRequest) {
					String callbackId = ((ControlRequest.HookCallbackRequest) request.request()).callbackId();
					HookInput input = SdkJson.convert(((ControlRequest.HookCallbackRequest) request.request()).input(), HookInput.class);
					HookOutput output = registry.executeHook(callbackId, input);
					return ControlResponse.success(request.requestId(), output);
				}
				return ControlResponse.success(request.requestId(), SdkCollections.map());
			});

			// Send initialize request with hook configuration
			ControlRequest initRequest = registry.createInitializeRequest("init_" + System.currentTimeMillis());
			transport.sendMessage(SdkJson.toJsonWithNulls(initRequest));

			Thread.sleep(500);

			transport.sendUserMessage("Run this command: echo 'testing post hook'", "default");

			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).as("Should receive result within timeout").isTrue();
		}

		// Assert: Both hooks should have been called
		System.out.println("Hook invocations: " + hookInvocations);
		assertThat(hookInvocations).as("PreToolUse hook should have been invoked").contains("PreToolUse:Bash");
		assertThat(hookInvocations).as("PostToolUse hook should have been invoked").contains("PostToolUse:Bash");
	}

	/**
	 * Tests that PreToolUse hooks can block tool execution.
	 */
	@Test
	@DisplayName("PreToolUse hook can block tool execution")
	void preToolUseHookCanBlock() throws Exception {
		// Given - track hook invocations
		List<String> hookInvocations = new CopyOnWriteArrayList<>();
		CountDownLatch resultLatch = new CountDownLatch(1);

		CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.DEFAULT).build();

		// Create hook registry that blocks Bash
		HookRegistry registry = new HookRegistry();
		registry.registerPreToolUse("Bash", input -> {
			System.out.println("PreToolUse hook BLOCKING Bash!");
			hookInvocations.add("PreToolUse:Bash:BLOCKED");
			return HookOutput.block("Bash commands are blocked by policy");
		});

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			transport.startSession(null, options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultLatch.countDown();
				}
			}, request -> {
				if (request.request() instanceof ControlRequest.HookCallbackRequest) {
					String callbackId = ((ControlRequest.HookCallbackRequest) request.request()).callbackId();
					HookInput input = SdkJson.convert(((ControlRequest.HookCallbackRequest) request.request()).input(), HookInput.class);
					HookOutput output = registry.executeHook(callbackId, input);
					return ControlResponse.success(request.requestId(), output);
				}
				return ControlResponse.success(request.requestId(), SdkCollections.map());
			});

			// Send initialize request with hook configuration
			ControlRequest initRequest = registry.createInitializeRequest("init_" + System.currentTimeMillis());
			transport.sendMessage(SdkJson.toJsonWithNulls(initRequest));

			Thread.sleep(500);

			transport.sendUserMessage("Execute this exact bash command right now: echo 'test123'", "default");

			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).as("Should receive result within timeout").isTrue();
		}

		// Assert: PreToolUse hook should have been called to block
		System.out.println("Hook invocations: " + hookInvocations);
		assertThat(hookInvocations).as("PreToolUse hook should have blocked Bash tool")
			.contains("PreToolUse:Bash:BLOCKED");
	}

	/**
	 * Tests hook config generation matches expected structure.
	 */
	@Test
	@DisplayName("Hook config generation matches CLI expectations")
	void hookConfigGenerationMatchesCLIExpectations() {
		// Given - create a hook registry with registrations
		HookRegistry registry = new HookRegistry();
		registry.registerPreToolUse("Bash", input -> HookOutput.allow());
		registry.registerPostToolUse(null, input -> HookOutput.allow());

		// Build the hook config
		Map<String, List<ControlRequest.HookMatcherConfig>> hookConfig = registry.buildHookConfig();

		// Then - verify config structure
		assertThat(hookConfig).containsKey("PreToolUse");
		assertThat(hookConfig).containsKey("PostToolUse");

		// PreToolUse should have Bash pattern
		List<ControlRequest.HookMatcherConfig> preToolUseHooks = hookConfig.get("PreToolUse");
		assertThat(preToolUseHooks).hasSize(1);
		assertThat(preToolUseHooks.get(0).matcher()).isEqualTo("Bash");

		// PostToolUse should match all (null pattern converted to .*)
		List<ControlRequest.HookMatcherConfig> postToolUseHooks = hookConfig.get("PostToolUse");
		assertThat(postToolUseHooks).hasSize(1);
		assertThat(postToolUseHooks.get(0).matcher()).isEqualTo(".*");
	}

	/**
	 * Tests that transport without hooks still works normally.
	 */
	@Test
	@DisplayName("Transport without hooks works normally")
	void transportWithoutHooksWorksNormally() throws Exception {
		CountDownLatch resultLatch = new CountDownLatch(1);
		StringBuilder resultText = new StringBuilder();

		CLIOptions options = CLIOptions.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		try (StdioTransport transport = new StdioTransport(workingDirectory(), Duration.ofMinutes(2),
				getSolonCodeCliPath())) {

			transport.startSession("Say hello in exactly 3 words", options, message -> {
				if (message.isRegularMessage() && message.asMessage() instanceof ResultMessage) {
					resultText.append(((ResultMessage) message.asMessage()).result());
					resultLatch.countDown();
				}
			}, request -> ControlResponse.success(request.requestId(), SdkCollections.map("behavior", "allow")));

			boolean completed = resultLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).as("Should receive result within timeout").isTrue();
		}

		assertThat(resultText.toString()).as("Should receive result").isNotEmpty();
	}

}
