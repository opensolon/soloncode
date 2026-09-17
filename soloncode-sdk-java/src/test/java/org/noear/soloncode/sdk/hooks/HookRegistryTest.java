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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.HookEvent;
import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.types.control.HookOutput;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for HookRegistry - registration, execution, and configuration building.
 */
class HookRegistryTest {

	private HookRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new HookRegistry();
	}

	@Nested
	@DisplayName("Hook Registration")
	class HookRegistrationTests {

		@Test
		@DisplayName("Should register PreToolUse hook with pattern")
		void registerPreToolUseWithPattern() {
			// When
			String id = registry.registerPreToolUse("Bash|Write", input -> HookOutput.allow());

			// Then
			assertThat(id).startsWith("hook_");
			assertThat(registry.hasHooks()).isTrue();
			assertThat(registry.getById(id)).isNotNull();
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.PRE_TOOL_USE);
		}

		@Test
		@DisplayName("Should register PreToolUse hook for all tools")
		void registerPreToolUseForAllTools() {
			// When
			String id = registry.registerPreToolUse(input -> HookOutput.allow());

			// Then
			HookRegistration reg = registry.getById(id);
			assertThat(reg.toolPattern()).isNull();
			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Read")).isTrue();
		}

		@Test
		@DisplayName("Should register PostToolUse hook")
		void registerPostToolUse() {
			// When
			String id = registry.registerPostToolUse("Read", input -> HookOutput.allow());

			// Then
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.POST_TOOL_USE);
		}

		@Test
		@DisplayName("Should register UserPromptSubmit hook")
		void registerUserPromptSubmit() {
			// When
			String id = registry.registerUserPromptSubmit(input -> HookOutput.allow());

			// Then
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.USER_PROMPT_SUBMIT);
		}

		@Test
		@DisplayName("Should register Stop hook")
		void registerStop() {
			// When
			String id = registry.registerStop(input -> HookOutput.allow());

			// Then
			assertThat(registry.getById(id).event()).isEqualTo(HookEvent.STOP);
		}

		@Test
		@DisplayName("Should reject duplicate hook IDs")
		void rejectDuplicateId() {
			// Given
			HookRegistration reg1 = HookRegistration.preToolUse("my_hook", input -> HookOutput.allow());
			registry.register(reg1);

			// When/Then
			HookRegistration reg2 = HookRegistration.preToolUse("my_hook", input -> HookOutput.block("test"));
			assertThatThrownBy(() -> registry.register(reg2)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("already registered");
		}

		@Test
		@DisplayName("Should unregister hook by ID")
		void unregisterById() {
			// Given
			String id = registry.registerPreToolUse(input -> HookOutput.allow());

			// When
			boolean removed = registry.unregister(id);

			// Then
			assertThat(removed).isTrue();
			assertThat(registry.getById(id)).isNull();
			assertThat(registry.hasHooks()).isFalse();
		}

		@Test
		@DisplayName("Should return false when unregistering non-existent hook")
		void unregisterNonExistent() {
			assertThat(registry.unregister("non_existent")).isFalse();
		}

		@Test
		@DisplayName("Should clear all hooks")
		void clearAllHooks() {
			// Given
			registry.registerPreToolUse(input -> HookOutput.allow());
			registry.registerPostToolUse(input -> HookOutput.allow());
			registry.registerStop(input -> HookOutput.allow());

			// When
			registry.clear();

			// Then
			assertThat(registry.hasHooks()).isFalse();
			assertThat(registry.getRegisteredIds()).isEmpty();
		}

		@Test
		@DisplayName("Should get hooks by event type")
		void getByEvent() {
			// Given
			registry.registerPreToolUse("Bash", input -> HookOutput.allow());
			registry.registerPreToolUse("Write", input -> HookOutput.allow());
			registry.registerPostToolUse(input -> HookOutput.allow());

			// When
			List<HookRegistration> preToolUseHooks = registry.getByEvent(HookEvent.PRE_TOOL_USE);
			List<HookRegistration> postToolUseHooks = registry.getByEvent(HookEvent.POST_TOOL_USE);
			List<HookRegistration> stopHooks = registry.getByEvent(HookEvent.STOP);

			// Then
			assertThat(preToolUseHooks).hasSize(2);
			assertThat(postToolUseHooks).hasSize(1);
			assertThat(stopHooks).isEmpty();
		}

	}

	@Nested
	@DisplayName("Hook Execution")
	class HookExecutionTests {

		@Test
		@DisplayName("Should execute hook callback")
		void executeCallback() {
			// Given
			AtomicBoolean called = new AtomicBoolean(false);
			String id = registry.registerPreToolUse(input -> {
				called.set(true);
				return HookOutput.allow();
			});

			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home", null, "Bash",
					"tool_123", SdkCollections.map("command", "ls"));

			// When
			HookOutput output = registry.executeHook(id, input);

			// Then
			assertThat(called.get()).isTrue();
			assertThat(output.continueExecution()).isTrue();
		}

		@Test
		@DisplayName("Should return null for non-existent hook")
		void executeNonExistentHook() {
			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home", null, "Bash",
					"tool_123", SdkCollections.map());

			HookOutput output = registry.executeHook("non_existent", input);

			assertThat(output).isNull();
		}

		@Test
		@DisplayName("Should handle callback exception gracefully")
		void handleCallbackException() {
			// Given
			String id = registry.registerPreToolUse(input -> {
				throw new RuntimeException("Test error");
			});

			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home", null, "Bash",
					"tool_123", SdkCollections.map());

			// When
			HookOutput output = registry.executeHook(id, input);

			// Then - should return block with error message
			assertThat(output.continueExecution()).isFalse();
			assertThat(output.reason()).contains("Hook execution failed");
		}

		@Test
		@DisplayName("Should pass input to callback")
		void passInputToCallback() {
			// Given
			AtomicReference<HookInput> capturedInput = new AtomicReference<>();
			String id = registry.registerPreToolUse(input -> {
				capturedInput.set(input);
				return HookOutput.allow();
			});

			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp/t.md", "/home/user",
					"default", "Write", "tool_123", SdkCollections.map("path", "/tmp/file.txt"));

			// When
			registry.executeHook(id, input);

			// Then
			HookInput.PreToolUseInput captured = (HookInput.PreToolUseInput) capturedInput.get();
			assertThat(captured.toolName()).isEqualTo("Write");
			assertThat(captured.cwd()).isEqualTo("/home/user");
			assertThat(captured.getArgument("path", String.class)).contains("/tmp/file.txt");
		}

	}

	@Nested
	@DisplayName("Hook Configuration Building")
	class HookConfigBuildingTests {

		@Test
		@DisplayName("Should build empty config when no hooks")
		void buildEmptyConfig() {
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			assertThat(config).isEmpty();
		}

		@Test
		@DisplayName("Should build config with PreToolUse hooks")
		void buildPreToolUseConfig() {
			// Given
			registry.registerPreToolUse("Bash|Write", input -> HookOutput.allow());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config).containsKey("PreToolUse");
			List<ControlRequest.HookMatcherConfig> matchers = config.get("PreToolUse");
			assertThat(matchers).hasSize(1);
			assertThat(matchers.get(0).matcher()).isEqualTo("Bash|Write");
			assertThat(matchers.get(0).hookCallbackIds()).hasSize(1);
		}

		@Test
		@DisplayName("Should group hooks by pattern")
		void groupHooksByPattern() {
			// Given - two hooks with same pattern
			registry.register(HookRegistration.preToolUse("hook_1", "Bash", input -> HookOutput.allow()));
			registry.register(HookRegistration.preToolUse("hook_2", "Bash", input -> HookOutput.allow()));

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then - should be grouped into one matcher
			List<ControlRequest.HookMatcherConfig> matchers = config.get("PreToolUse");
			assertThat(matchers).hasSize(1);
			assertThat(matchers.get(0).hookCallbackIds()).containsExactlyInAnyOrder("hook_1", "hook_2");
		}

		@Test
		@DisplayName("Should separate hooks with different patterns")
		void separateHooksByPattern() {
			// Given - hooks with different patterns
			registry.register(HookRegistration.preToolUse("hook_bash", "Bash", input -> HookOutput.allow()));
			registry.register(HookRegistration.preToolUse("hook_write", "Write", input -> HookOutput.allow()));

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then - should be separate matchers
			List<ControlRequest.HookMatcherConfig> matchers = config.get("PreToolUse");
			assertThat(matchers).hasSize(2);
		}

		@Test
		@DisplayName("Should use wildcard pattern for all-tools hooks")
		void wildcardPatternForAllTools() {
			// Given
			registry.registerPreToolUse(input -> HookOutput.allow());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config.get("PreToolUse").get(0).matcher()).isEqualTo(".*");
		}

		@Test
		@DisplayName("Should use max timeout from grouped hooks")
		void maxTimeoutFromGroup() {
			// Given
			registry.register(HookRegistration.builder()
				.id("hook_1")
				.event(HookEvent.PRE_TOOL_USE)
				.toolPattern("Bash")
				.callback(input -> HookOutput.allow())
				.timeout(30)
				.build());

			registry.register(HookRegistration.builder()
				.id("hook_2")
				.event(HookEvent.PRE_TOOL_USE)
				.toolPattern("Bash")
				.callback(input -> HookOutput.allow())
				.timeout(120)
				.build());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config.get("PreToolUse").get(0).timeout()).isEqualTo(120);
		}

		@Test
		@DisplayName("Should build multi-event config")
		void buildMultiEventConfig() {
			// Given
			registry.registerPreToolUse(input -> HookOutput.allow());
			registry.registerPostToolUse(input -> HookOutput.allow());
			registry.registerUserPromptSubmit(input -> HookOutput.allow());

			// When
			Map<String, List<ControlRequest.HookMatcherConfig>> config = registry.buildHookConfig();

			// Then
			assertThat(config).containsKeys("PreToolUse", "PostToolUse", "UserPromptSubmit");
		}

	}

	@Nested
	@DisplayName("Initialize Request Creation")
	class InitializeRequestTests {

		@Test
		@DisplayName("Should create initialize request")
		void createInitializeRequest() {
			// Given
			registry.registerPreToolUse("Bash", input -> HookOutput.allow());

			// When
			ControlRequest request = registry.createInitializeRequest("req_init_1");

			// Then
			assertThat(request.type()).isEqualTo("control_request");
			assertThat(request.requestId()).isEqualTo("req_init_1");
			assertThat(request.request()).isInstanceOf(ControlRequest.InitializeRequest.class);

			ControlRequest.InitializeRequest initRequest = (ControlRequest.InitializeRequest) request.request();
			assertThat(initRequest.hooks()).containsKey("PreToolUse");
		}

	}

	@Nested
	@DisplayName("Pattern Matching")
	class PatternMatchingTests {

		@Test
		@DisplayName("Should match exact tool name")
		void matchExactToolName() {
			HookRegistration reg = HookRegistration.preToolUse("test", "Bash", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isFalse();
		}

		@Test
		@DisplayName("Should match pattern with OR")
		void matchPatternWithOr() {
			HookRegistration reg = HookRegistration.preToolUse("test", "Bash|Write|Read", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isTrue();
			assertThat(reg.matchesTool("Read")).isTrue();
			assertThat(reg.matchesTool("Edit")).isFalse();
		}

		@Test
		@DisplayName("Should match wildcard pattern")
		void matchWildcardPattern() {
			HookRegistration reg = HookRegistration.preToolUse("test", ".*", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isTrue();
			assertThat(reg.matchesTool("AnyTool")).isTrue();
		}

		@Test
		@DisplayName("Null pattern should match all tools")
		void nullPatternMatchesAll() {
			HookRegistration reg = HookRegistration.preToolUse("test", input -> HookOutput.allow());

			assertThat(reg.matchesTool("Bash")).isTrue();
			assertThat(reg.matchesTool("Write")).isTrue();
			// Null pattern matches everything including null toolName
			assertThat(reg.matchesTool(null)).isTrue();
		}

	}

	@Nested
	@DisplayName("HookCallback Static Methods")
	class HookCallbackStaticMethodsTests {

		@Test
		@DisplayName("HookCallback.allow() should always return allow")
		void allowCallback() {
			HookCallback callback = HookCallback.allow();
			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp", "/home", null, "Bash",
					"tool_123", SdkCollections.map());

			HookOutput output = callback.handle(input);

			assertThat(output.continueExecution()).isTrue();
		}

		@Test
		@DisplayName("HookCallback.block() should always return block")
		void blockCallback() {
			HookCallback callback = HookCallback.block("Not allowed");
			HookInput input = new HookInput.PreToolUseInput("PreToolUse", "sess_1", "/tmp", "/home", null, "Bash",
					"tool_123", SdkCollections.map());

			HookOutput output = callback.handle(input);

			assertThat(output.continueExecution()).isFalse();
			assertThat(output.reason()).isEqualTo("Not allowed");
		}

	}

}
