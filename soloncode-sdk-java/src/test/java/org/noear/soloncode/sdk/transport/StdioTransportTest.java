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
import org.noear.soloncode.sdk.config.PermissionMode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.noear.soloncode.sdk.config.PluginConfig;
import org.noear.soloncode.sdk.util.SdkCollections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for StdioTransport command building and configuration. Note: Full integration
 * tests with actual CLI are in the integration test suite.
 *
 * <p>
 * Important: In bidirectional mode, the prompt is NOT passed as a command-line argument.
 * Instead, it is sent via stdin as a JSON user message after the process starts. This
 * matches the CLI behavior where --input-format stream-json mode expects input via stdin.
 * </p>
 */
class StdioTransportTest {

	@TempDir
	Path tempDir;

	@Nested
	@DisplayName("Command Building Tests")
	class CommandBuildingTests {

		@Test
		@DisplayName("Should build command with required bidirectional flags")
		void buildCommandWithBidirectionalFlags() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder()
				.model("sonnet")
				.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
				.build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - verify soloncode run command shape
			// soloncode run does not support --input-format; prompt is plain text via stdin
			assertThat(command).contains("run");
			assertThat(command).doesNotContain("--input-format");
			assertThat(command).contains("--output-format", "stream-json");
			// NOTE: --permission-prompt-tool is NOT added by default (matches Python SDK)
			assertThat(command).contains("--verbose");
			// Should NOT contain --print
			assertThat(command).doesNotContain("--print");
		}

		@Test
		@DisplayName("Should include model when specified")
		void buildCommandWithModel() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().model("opus").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int modelIndex = command.indexOf("--model");
			assertThat(modelIndex).isGreaterThan(-1);
			assertThat(command.get(modelIndex + 1)).isEqualTo("opus");
		}

		@Test
		@DisplayName("Should include system prompt when specified")
		void buildCommandWithSystemPrompt() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().systemPrompt("You are a helpful assistant").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - soloncode run does not support --system-prompt; it is ignored
			assertThat(command).doesNotContain("--system-prompt");
			assertThat(command).doesNotContain("You are a helpful assistant");
		}

		@Test
		@DisplayName("Should include allowed tools when specified")
		void buildCommandWithAllowedTools() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().allowedTools(SdkCollections.list("Bash", "Read", "Write")).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int toolsIndex = command.indexOf("--allowedTools");
			assertThat(toolsIndex).isGreaterThan(-1);
			assertThat(command.get(toolsIndex + 1)).isEqualTo("Bash,Read,Write");
		}

		@Test
		@DisplayName("Should include disallowed tools when specified")
		void buildCommandWithDisallowedTools() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().disallowedTools(SdkCollections.list("WebFetch")).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int toolsIndex = command.indexOf("--disallowedTools");
			assertThat(toolsIndex).isGreaterThan(-1);
			assertThat(command.get(toolsIndex + 1)).isEqualTo("WebFetch");
		}

		@Test
		@DisplayName("Should include permission mode")
		void buildCommandWithPermissionMode() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.BYPASS_PERMISSIONS).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int modeIndex = command.indexOf("--permission-mode");
			assertThat(modeIndex).isGreaterThan(-1);
			assertThat(command.get(modeIndex + 1)).isEqualTo("bypassPermissions");
		}

		@Test
		@DisplayName("Should NOT include prompt as command-line argument in bidirectional mode")
		void shouldNotIncludePromptAsArgument() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - in bidirectional mode, prompt is sent via stdin, not command line
			// Should not have the -- separator used for positional arguments
			assertThat(command).doesNotContain("--");
			// Command should not contain any user prompt text
			// (old code would have "My test prompt" or similar as last arg after --)
			assertThat(command).noneMatch(arg -> arg.contains("test prompt") || arg.contains("2+2"));
		}

		@Test
		@DisplayName("Should handle empty options gracefully")
		void buildCommandWithEmptyOptions() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - should still have core run flags
			assertThat(command).contains("run");
			assertThat(command).doesNotContain("--input-format");
			assertThat(command).contains("--output-format");
			// NOTE: --permission-prompt-tool is NOT added by default (matches Python SDK)
			// Should not have empty tool lists
			assertThat(command).doesNotContain("--allowedTools");
		}

		@Test
		@DisplayName("Should build complete command with all options")
		void buildCompleteCommand() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder()
				.model("sonnet")
				.systemPrompt("Be helpful")
				.allowedTools(SdkCollections.list("Bash", "Read"))
				.disallowedTools(SdkCollections.list("Write"))
				.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
				.build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - verify all parts
			assertThat(command.get(0)).isEqualTo("/usr/bin/soloncode");
			// Should not have --print
			assertThat(command).doesNotContain("--print");
			// soloncode run has no --input-format; prompt is plain text via stdin
			assertThat(command).doesNotContain("--input-format");
			assertThat(command).contains("--output-format", "stream-json");
			// NOTE: --permission-prompt-tool is NOT added by default (matches Python SDK)
			assertThat(command).contains("--model", "sonnet");
			// --system-prompt is not supported by soloncode run and is ignored
			assertThat(command).doesNotContain("--system-prompt");
			assertThat(command).doesNotContain("Be helpful");
		}

	}

	@Nested
	@DisplayName("Transport State Tests")
	class TransportStateTests {

		@Test
		@DisplayName("Should report not running initially")
		void notRunningInitially() {
			StdioTransport transport = new StdioTransport(tempDir);

			assertThat(transport.isRunning()).isFalse();

			transport.close();
		}

		@Test
		@DisplayName("Should close cleanly when not started")
		void closeWhenNotStarted() {
			StdioTransport transport = new StdioTransport(tempDir);

			// Should not throw
			transport.close();

			assertThat(transport.isRunning()).isFalse();
		}

		@Test
		@DisplayName("Should support custom timeout")
		void customTimeout() {
			Duration customTimeout = Duration.ofMinutes(30);
			StdioTransport transport = new StdioTransport(tempDir, customTimeout);

			// Just verify construction succeeded
			assertThat(transport).isNotNull();

			transport.close();
		}

		@Test
		@DisplayName("Should support custom CLI path")
		void customSolonCodePath() {
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5),
					"/custom/path/soloncode");

			List<String> command = transport.buildStreamingCommand(CLIOptions.builder().build());

			assertThat(command.get(0)).isEqualTo("/custom/path/soloncode");

			transport.close();
		}

	}

	@Nested
	@DisplayName("Budget Control and Advanced Options Tests")
	class BudgetControlTests {

		@Test
		@DisplayName("Should include max-turns when specified")
		void buildCommandWithMaxTurns() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().maxTurns(10).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int turnsIndex = command.indexOf("--max-turns");
			assertThat(turnsIndex).isGreaterThan(-1);
			assertThat(command.get(turnsIndex + 1)).isEqualTo("10");
		}

		@Test
		@DisplayName("Should include max-budget-usd when specified")
		void buildCommandWithMaxBudgetUsd() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().maxBudgetUsd(0.50).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int budgetIndex = command.indexOf("--max-budget-usd");
			assertThat(budgetIndex).isGreaterThan(-1);
			assertThat(command.get(budgetIndex + 1)).isEqualTo("0.5");
		}

		@Test
		@DisplayName("Should include fallback-model when specified")
		void buildCommandWithFallbackModel() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().fallbackModel("haiku").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int fallbackIndex = command.indexOf("--fallback-model");
			assertThat(fallbackIndex).isGreaterThan(-1);
			assertThat(command.get(fallbackIndex + 1)).isEqualTo("haiku");
		}

		@Test
		@DisplayName("Should include append-system-prompt when specified via builder")
		void buildCommandWithAppendSystemPrompt() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().appendSystemPrompt("Be concise and focused.").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int appendIndex = command.indexOf("--append-system-prompt");
			// soloncode run does not support --append-system-prompt; it is ignored
			assertThat(command).doesNotContain("--append-system-prompt");
			assertThat(command).doesNotContain("Be concise and focused.");
		}

		@Test
		@DisplayName("Should include all budget control options together")
		void buildCommandWithAllBudgetOptions() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder()
				.model("sonnet")
				.maxTurns(5)
				.maxBudgetUsd(0.25)
				.fallbackModel("haiku")
				.appendSystemPrompt("Be helpful")
				.build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			assertThat(command).contains("--max-turns", "5");
			assertThat(command).contains("--max-budget-usd", "0.25");
			assertThat(command).contains("--fallback-model", "haiku");
			// --append-system-prompt is not supported by soloncode run and is ignored
			assertThat(command).doesNotContain("--append-system-prompt");
		}

	}

	@Nested
	@DisplayName("Bidirectional Mode Verification")
	class BidirectionalModeVerification {

		@Test
		@DisplayName("Command should enable full bidirectional communication")
		void commandEnablesBidirectionalMode() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.defaultOptions();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - soloncode run command shape:
			// 1. run subcommand: headless agent execution
			// 2. --output-format stream-json: outputs JSON messages on stdout
			// 3. --verbose: get all message types
			// 4. no --input-format: prompt is plain text via stdin
			assertThat(command).contains("run");
			assertThat(command).doesNotContain("--input-format");
			assertThat(command).containsSubsequence("--output-format", "stream-json");

			transport.close();
		}

		@Test
		@DisplayName("Should NOT include --print in bidirectional mode")
		void shouldNotIncludePrintInBidirectionalMode() {
			// In bidirectional mode (--input-format stream-json), the CLI waits for
			// input via stdin. Using --print with a command-line prompt would conflict
			// with this mode. Instead, the prompt is sent as a JSON user message.
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");

			List<String> command = transport.buildStreamingCommand(CLIOptions.builder().build());

			// --print is NOT used in bidirectional mode
			assertThat(command).doesNotContain("--print");

			transport.close();
		}

		@Test
		@DisplayName("Should always include --verbose for control protocol")
		void alwaysIncludesVerbose() {
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");

			List<String> command = transport.buildStreamingCommand(CLIOptions.builder().build());

			// --verbose is required with stream-json to get all message types
			assertThat(command).contains("--verbose");

			transport.close();
		}

	}

	@Nested
	@DisplayName("Session Resume Options Tests")
	class SessionResumeOptionsTests {

		@Test
		@DisplayName("Should include --continue flag when continueConversation is true")
		void buildCommandWithContinueConversation() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().continueConversation(true).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			assertThat(command).contains("--continue");

			transport.close();
		}

		@Test
		@DisplayName("Should NOT include --continue flag when continueConversation is false")
		void buildCommandWithoutContinueWhenFalse() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().continueConversation(false).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			assertThat(command).doesNotContain("--continue");

			transport.close();
		}

		@Test
		@DisplayName("Should include --resume flag with session ID")
		void buildCommandWithResume() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().resume("session-abc123").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int resumeIndex = command.indexOf("--resume");
			assertThat(resumeIndex).isGreaterThan(-1);
			assertThat(command.get(resumeIndex + 1)).isEqualTo("session-abc123");

			transport.close();
		}

		@Test
		@DisplayName("Should NOT include --resume flag when resume is null")
		void buildCommandWithoutResumeWhenNull() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().resume(null).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			assertThat(command).doesNotContain("--resume");

			transport.close();
		}

		@Test
		@DisplayName("Should NOT include --resume flag when resume is empty string")
		void buildCommandWithoutResumeWhenEmpty() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().resume("").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			assertThat(command).doesNotContain("--resume");

			transport.close();
		}

		@Test
		@DisplayName("Should NOT include --resume flag when resume is blank")
		void buildCommandWithoutResumeWhenBlank() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().resume("   ").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			assertThat(command).doesNotContain("--resume");

			transport.close();
		}

		@Test
		@DisplayName("Should store session resume options in CLIOptions")
		void cliOptionsStoresResumeOptions() {
			// Given
			CLIOptions options = CLIOptions.builder().continueConversation(true).resume("session-xyz").build();

			// Then
			assertThat(options.isContinueConversation()).isTrue();
			assertThat(options.getResume()).isEqualTo("session-xyz");
		}

	}

	@Nested
	@DisplayName("Advanced Python SDK Parity Options Tests")
	class AdvancedOptionsTests {

		@Test
		@DisplayName("Should include agents JSON for multi-agent coordination")
		void buildCommandWithAgents() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			String agentsJson = "{\"researcher\":{\"description\":\"Research agent\",\"tools\":[\"WebSearch\"],\"prompt\":\"You are a researcher\",\"model\":\"haiku\"}}";
			CLIOptions options = CLIOptions.builder().agents(agentsJson).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - soloncode run does not support --agents; it is ignored
			assertThat(command).doesNotContain("--agents");

			transport.close();
		}

		@Test
		@DisplayName("Should NOT include agents flag when agents is null or empty")
		void buildCommandWithoutAgentsWhenEmpty() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().agents("").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - empty agents should not produce --agents flag
			assertThat(command).doesNotContain("--agents");

			transport.close();
		}

		@Test
		@DisplayName("Should include add-dir flags for each directory")
		void buildCommandWithAddDirs() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder()
				.addDirs(SdkCollections.list(Paths.get("/workspace/libs"), Paths.get("/workspace/docs")))
				.build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - each directory gets its own --add-dir flag
			int firstIndex = command.indexOf("--add-dir");
			assertThat(firstIndex).isGreaterThan(-1);
			assertThat(command.get(firstIndex + 1)).isEqualTo("/workspace/libs");

			int secondIndex = command.indexOf("--add-dir");
			List<String> afterFirst = command.subList(firstIndex + 2, command.size());
			int secondPos = afterFirst.indexOf("--add-dir");
			assertThat(secondPos).isGreaterThan(-1);
			assertThat(afterFirst.get(secondPos + 1)).isEqualTo("/workspace/docs");

			transport.close();
		}

		@Test
		@DisplayName("Should include settings flag when specified")
		void buildCommandWithSettings() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().settings("/etc/soloncode/settings.json").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - soloncode run does not support --settings; it is ignored
			assertThat(command).doesNotContain("--settings");

			transport.close();
		}

		@Test
		@DisplayName("Should include permission-prompt-tool-name when specified")
		void buildCommandWithPermissionPromptToolName() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().permissionPromptToolName("custom-tool").build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - soloncode run does not support --permission-prompt-tool; it is ignored
			assertThat(command).doesNotContain("--permission-prompt-tool");

			transport.close();
		}

		@Test
		@DisplayName("Should include plugin-dir flags for each plugin")
		void buildCommandWithPlugins() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder()
				.plugins(SdkCollections.list(PluginConfig.local("/opt/plugins/custom"), PluginConfig.local("/home/user/plugins")))
				.build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - soloncode run does not support --plugin-dir; it is ignored
			assertThat(command).doesNotContain("--plugin-dir");

			transport.close();
		}

		@Test
		@DisplayName("Should include extra args with values")
		void buildCommandWithExtraArgsWithValue() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder().extraArgs(SdkCollections.map("custom-flag", "custom-value")).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then
			int flagIndex = command.indexOf("--custom-flag");
			assertThat(flagIndex).isGreaterThan(-1);
			assertThat(command.get(flagIndex + 1)).isEqualTo("custom-value");

			transport.close();
		}

		@Test
		@DisplayName("Should include extra args as boolean flags when value is null")
		void buildCommandWithExtraArgsBooleanFlag() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			// Use HashMap to allow null values
			Map<String, String> extraArgs = new java.util.HashMap<>();
			extraArgs.put("debug-to-stderr", null);
			CLIOptions options = CLIOptions.builder().extraArgs(extraArgs).build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - boolean flag should be present without a value
			assertThat(command).contains("--debug-to-stderr");
			int flagIndex = command.indexOf("--debug-to-stderr");
			// Next element should not be the value (either another flag or end)
			if (flagIndex + 1 < command.size()) {
				String nextElement = command.get(flagIndex + 1);
				assertThat(nextElement).startsWith("--").describedAs("Boolean flag should not have a value");
			}

			transport.close();
		}

		@Test
		@DisplayName("Should store user option in CLIOptions")
		void buildCommandWithUser() {
			// Given - user wrapping is done at startSession time, not in
			// buildStreamingCommand
			// The buildStreamingCommand only generates CLI flags, the
			// wrapCommandForUser
			// is called separately in startSession(). This test verifies the option is
			// stored.
			CLIOptions options = CLIOptions.builder().user("soloncode-runner").build();

			// Then
			assertThat(options.getUser()).isEqualTo("soloncode-runner");
		}

		@Test
		@DisplayName("Should include all advanced options together")
		void buildCommandWithAllAdvancedOptions() {
			// Given
			StdioTransport transport = new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
			CLIOptions options = CLIOptions.builder()
				.model("sonnet")
				.addDirs(SdkCollections.list(Paths.get("/workspace")))
				.settings("/etc/soloncode/settings.json")
				.plugins(SdkCollections.list(PluginConfig.local("/opt/plugins/custom")))
				.extraArgs(SdkCollections.map("custom-flag", "value"))
				.build();

			// When
			List<String> command = transport.buildStreamingCommand(options);

			// Then - supported options present, unsupported ignored
			assertThat(command).contains("--add-dir", "/workspace");
			assertThat(command).doesNotContain("--settings");
			assertThat(command).doesNotContain("--plugin-dir");
			assertThat(command).contains("--custom-flag", "value");

			transport.close();
		}

	}

}
