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
import org.noear.soloncode.sdk.config.PluginConfig;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI Flag Parity Tests - Ensures Java SDK passes all CLI flags that Python SDK supports.
 *
 * <p>
 * IMPORTANT: This test class exists to prevent regressions where CLI flags are missing or
 * incorrectly passed. Two bugs were caught in tutorials due to missing flag support:
 * </p>
 * <ul>
 * <li>Module 09: --json-schema flag parsing was broken (structured_output not
 * parsed)</li>
 * <li>Module 11: --resume flag was completely missing from the SDK</li>
 * </ul>
 *
 * <p>
 * Reference: Python SDK subprocess_cli.py _build_command() method
 * </p>
 *
 * @see <a href="https://github.com/anthropics/claude-agent-sdk-python">Python SDK</a>
 */
@DisplayName("CLI Flag Parity Tests")
class CLIFlagParityTest {

	@TempDir
	Path tempDir;

	/**
	 * Creates a transport for testing command building.
	 */
	private StdioTransport createTransport() {
		return new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/bin/soloncode");
	}

	// ============================================================
	// Core Bidirectional Mode Flags (Always Present)
	// ============================================================

	@Nested
	@DisplayName("Core Bidirectional Mode Flags")
	class CoreBidirectionalFlags {

		@Test
		@DisplayName("--output-format stream-json is always present")
		void outputFormatStreamJson() {
			try (StdioTransport transport = createTransport()) {
				List<String> cmd = transport.buildStreamingCommand(CLIOptions.builder().build());
				assertThat(cmd).containsSubsequence("--output-format", "stream-json");
			}
		}

		@Test
		@DisplayName("--input-format is NOT used by soloncode run")
		void inputFormatStreamJson() {
			try (StdioTransport transport = createTransport()) {
				List<String> cmd = transport.buildStreamingCommand(CLIOptions.builder().build());
				// soloncode run reads plain-text prompt from stdin; no --input-format flag
				assertThat(cmd).contains("run");
				assertThat(cmd).doesNotContain("--input-format");
			}
		}

		@Test
		@DisplayName("--verbose is always present")
		void verboseAlwaysPresent() {
			try (StdioTransport transport = createTransport()) {
				List<String> cmd = transport.buildStreamingCommand(CLIOptions.builder().build());
				assertThat(cmd).contains("--verbose");
			}
		}

	}

	// ============================================================
	// Model and Prompt Flags
	// ============================================================

	@Nested
	@DisplayName("Model and Prompt Flags")
	class ModelAndPromptFlags {

		@Test
		@DisplayName("--model flag with model ID")
		void modelFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().model("sonnet").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--model", "sonnet");
			}
		}

		@Test
		@DisplayName("--system-prompt is ignored by soloncode run")
		void systemPromptFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().systemPrompt("You are a helpful assistant").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--system-prompt");
			}
		}

		@Test
		@DisplayName("--append-system-prompt is ignored by soloncode run")
		void appendSystemPromptFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().appendSystemPrompt("Always be concise.").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--append-system-prompt");
			}
		}

		@Test
		@DisplayName("--fallback-model flag")
		void fallbackModelFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().fallbackModel("haiku").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--fallback-model", "haiku");
			}
		}

	}

	// ============================================================
	// Tool Control Flags
	// ============================================================

	@Nested
	@DisplayName("Tool Control Flags")
	class ToolControlFlags {

		@Test
		@DisplayName("--allowedTools flag with comma-separated list")
		void allowedToolsFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().allowedTools(SdkCollections.list("Bash", "Read", "Write")).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--allowedTools", "Bash,Read,Write");
			}
		}

		@Test
		@DisplayName("--disallowedTools flag with comma-separated list")
		void disallowedToolsFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().disallowedTools(SdkCollections.list("WebFetch", "WebSearch")).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--disallowedTools", "WebFetch,WebSearch");
			}
		}

		@Test
		@DisplayName("--tools is ignored by soloncode run; use allowedTools instead")
		void toolsFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().tools(SdkCollections.list("Read", "Edit")).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--tools");
			}
		}

		@Test
		@DisplayName("--tools with empty list is also ignored")
		void toolsFlagEmpty() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().tools(SdkCollections.list()).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--tools");
			}
		}

	}

	// ============================================================
	// Permission Flags
	// ============================================================

	@Nested
	@DisplayName("Permission Flags")
	class PermissionFlags {

		@Test
		@DisplayName("--permission-mode bypassPermissions")
		void permissionModeBypass() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.BYPASS_PERMISSIONS).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--permission-mode", "bypassPermissions");
			}
		}

		@Test
		@DisplayName("--dangerously-skip-permissions maps to --permission-mode bypassPermissions")
		void dangerouslySkipPermissions() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder()
					.permissionMode(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS)
					.build();
				List<String> cmd = transport.buildStreamingCommand(options);
				// soloncode run has no --dangerously-skip-permissions; mapped to permission-mode
				assertThat(cmd).doesNotContain("--dangerously-skip-permissions");
				assertThat(cmd).containsSubsequence("--permission-mode", "bypassPermissions");
			}
		}

		@Test
		@DisplayName("--permission-mode dontAsk (DONT_ASK)")
		void permissionModeDontAsk() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.DONT_ASK).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--permission-mode", "dontAsk");
			}
		}

		@Test
		@DisplayName("--permission-mode plan (PLAN)")
		void permissionModePlan() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().permissionMode(PermissionMode.PLAN).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--permission-mode", "plan");
			}
		}

		@Test
		@DisplayName("--permission-prompt-tool is ignored by soloncode run")
		void permissionPromptToolFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().permissionPromptToolName("stdio").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--permission-prompt-tool");
			}
		}

	}

	// ============================================================
	// Session Resume Flags (Bug fix: Module 11)
	// ============================================================

	@Nested
	@DisplayName("Session Resume Flags")
	class SessionResumeFlags {

		@Test
		@DisplayName("--continue flag for continuing most recent session")
		void continueFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().continueConversation(true).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).contains("--continue");
			}
		}

		@Test
		@DisplayName("--resume flag with session ID")
		void resumeFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().resume("session-abc123").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--resume", "session-abc123");
			}
		}

		@Test
		@DisplayName("--resume flag not present when null")
		void resumeFlagNotPresentWhenNull() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--resume");
			}
		}

		@Test
		@DisplayName("--continue flag not present when false")
		void continueFlagNotPresentWhenFalse() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().continueConversation(false).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--continue");
			}
		}

	}

	// ============================================================
	// Budget Control Flags
	// ============================================================

	@Nested
	@DisplayName("Budget Control Flags")
	class BudgetControlFlags {

		@Test
		@DisplayName("--max-turns flag")
		void maxTurnsFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().maxTurns(10).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--max-turns", "10");
			}
		}

		@Test
		@DisplayName("--max-budget-usd flag")
		void maxBudgetUsdFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().maxBudgetUsd(0.50).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--max-budget-usd", "0.5");
			}
		}

	}

	// ============================================================
	// Extended Thinking Flags
	// ============================================================

	@Nested
	@DisplayName("Extended Thinking Flags")
	class ExtendedThinkingFlags {

		@Test
		@DisplayName("--max-thinking-tokens is ignored by soloncode run")
		void maxThinkingTokensFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().maxThinkingTokens(10000).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--max-thinking-tokens");
			}
		}

	}

	// ============================================================
	// Structured Output Flags (Bug fix: Module 09)
	// ============================================================

	@Nested
	@DisplayName("Structured Output Flags")
	class StructuredOutputFlags {

		@Test
		@DisplayName("--json-schema flag with schema JSON")
		void jsonSchemaFlag() {
			try (StdioTransport transport = createTransport()) {
				Map<String, Object> schema = new HashMap<>();
				schema.put("type", "object");
				schema.put("properties",
						SdkCollections.map("answer", SdkCollections.map("type", "number"), "explanation", SdkCollections.map("type", "string")));
				schema.put("required", SdkCollections.list("answer", "explanation"));

				CLIOptions options = CLIOptions.builder().jsonSchema(schema).build();
				List<String> cmd = transport.buildStreamingCommand(options);

				int schemaIndex = cmd.indexOf("--json-schema");
				assertThat(schemaIndex).as("--json-schema flag should be present").isGreaterThan(-1);
				String schemaJson = cmd.get(schemaIndex + 1);
				assertThat(schemaJson).contains("\"type\":\"object\"");
				assertThat(schemaJson).contains("\"answer\"");
				assertThat(schemaJson).contains("\"explanation\"");
			}
		}

	}

	// ============================================================
	// Multi-Agent Flags
	// ============================================================

	@Nested
	@DisplayName("Multi-Agent Flags")
	class MultiAgentFlags {

		@Test
		@DisplayName("--agents is ignored by soloncode run")
		void agentsFlag() {
			try (StdioTransport transport = createTransport()) {
				String agentsJson = "{ \"researcher\": { \"description\": \"Research agent\", \"tools\": [\"WebSearch\"], \"prompt\": \"You are a researcher\" } } ";
				CLIOptions options = CLIOptions.builder().agents(agentsJson).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--agents");
			}
		}

		@Test
		@DisplayName("--agents flag not present when empty")
		void agentsFlagNotPresentWhenEmpty() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().agents("").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--agents");
			}
		}

	}

	// ============================================================
	// MCP Server Configuration Flags
	// ============================================================

	@Nested
	@DisplayName("MCP Server Configuration Flags")
	class McpServerFlags {

		@Test
		@DisplayName("--mcp-config is ignored by soloncode run")
		void mcpConfigStdioServer() throws Exception {
			try (StdioTransport transport = createTransport()) {
				McpServerConfig.McpStdioServerConfig stdioServer = new McpServerConfig.McpStdioServerConfig("npx",
						SdkCollections.list("-y", "@modelcontextprotocol/server-filesystem"), null);
				CLIOptions options = CLIOptions.builder().mcpServers(SdkCollections.map("filesystem", stdioServer)).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				// MCP servers must be registered on the CLI side; --mcp-config is not passed
				assertThat(cmd).doesNotContain("--mcp-config");
			}
		}

	}

	// ============================================================
	// Settings and Configuration Flags
	// ============================================================

	@Nested
	@DisplayName("Settings and Configuration Flags")
	class SettingsFlags {

		@Test
		@DisplayName("--settings is ignored by soloncode run")
		void settingsFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().settings("/etc/soloncode/settings.json").build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--settings");
			}
		}

		@Test
		@DisplayName("--setting-sources is ignored by soloncode run")
		void settingSourcesFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().settingSources(SdkCollections.list("project", "user")).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--setting-sources");
			}
		}

		@Test
		@DisplayName("--add-dir flag (repeated for each directory)")
		void addDirFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder()
					.addDirs(SdkCollections.list(Paths.get("/workspace/libs"), Paths.get("/workspace/docs")))
					.build();
				List<String> cmd = transport.buildStreamingCommand(options);

				// Find first --add-dir
				int firstIndex = cmd.indexOf("--add-dir");
				assertThat(firstIndex).isGreaterThan(-1);
				assertThat(cmd.get(firstIndex + 1)).isEqualTo("/workspace/libs");

				// Find second --add-dir after the first
				List<String> afterFirst = cmd.subList(firstIndex + 2, cmd.size());
				int secondPos = afterFirst.indexOf("--add-dir");
				assertThat(secondPos).isGreaterThan(-1);
				assertThat(afterFirst.get(secondPos + 1)).isEqualTo("/workspace/docs");
			}
		}

	}

	// ============================================================
	// Plugin Flags
	// ============================================================

	@Nested
	@DisplayName("Plugin Flags")
	class PluginFlags {

		@Test
		@DisplayName("--plugin-dir is ignored by soloncode run")
		void pluginDirFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder()
					.plugins(SdkCollections.list(PluginConfig.local("/opt/plugins/custom")))
					.build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--plugin-dir");
			}
		}

	}

	// ============================================================
	// Extra Args (Escape Hatch)
	// ============================================================

	@Nested
	@DisplayName("Extra Args (Escape Hatch)")
	class ExtraArgsFlags {

		@Test
		@DisplayName("Extra args with value")
		void extraArgsWithValue() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().extraArgs(SdkCollections.map("custom-flag", "custom-value")).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).containsSubsequence("--custom-flag", "custom-value");
			}
		}

		@Test
		@DisplayName("Extra args as boolean flag (null value)")
		void extraArgsBooleanFlag() {
			try (StdioTransport transport = createTransport()) {
				Map<String, String> extraArgs = new HashMap<>();
				extraArgs.put("debug-to-stderr", null);
				CLIOptions options = CLIOptions.builder().extraArgs(extraArgs).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).contains("--debug-to-stderr");
			}
		}

	}

	// ============================================================
	// Session Control Flags
	// ============================================================

	@Nested
	@DisplayName("Session Control Flags")
	class SessionControlFlags {

		@Test
		@DisplayName("--include-partial-messages is ignored by soloncode run")
		void includePartialMessagesFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().includePartialMessages(true).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--include-partial-messages");
			}
		}

		@Test
		@DisplayName("--include-partial-messages flag not present when disabled")
		void includePartialMessagesFlagNotPresent() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().includePartialMessages(false).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--include-partial-messages");
			}
		}

		@Test
		@DisplayName("--fork-session is ignored by soloncode run")
		void forkSessionFlag() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().forkSession(true).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--fork-session");
			}
		}

		@Test
		@DisplayName("--fork-session flag not present when disabled")
		void forkSessionFlagNotPresent() {
			try (StdioTransport transport = createTransport()) {
				CLIOptions options = CLIOptions.builder().forkSession(false).build();
				List<String> cmd = transport.buildStreamingCommand(options);
				assertThat(cmd).doesNotContain("--fork-session");
			}
		}

	}

	// ============================================================
	// Comprehensive Parity Test
	// ============================================================

	@Nested
	@DisplayName("Comprehensive Parity Tests")
	class ComprehensiveParityTests {

		@Test
		@DisplayName("All major flags work together")
		void allMajorFlagsTogether() {
			try (StdioTransport transport = createTransport()) {
				Map<String, Object> schema = SdkCollections.map("type", "object", "properties",
						SdkCollections.map("result", SdkCollections.map("type", "string")));

				CLIOptions options = CLIOptions.builder()
					.model("sonnet")
					.systemPrompt("You are a test assistant")
					.allowedTools(SdkCollections.list("Bash", "Read"))
					.disallowedTools(SdkCollections.list("WebFetch"))
					.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
					.maxTurns(5)
					.maxBudgetUsd(0.25)
					.maxThinkingTokens(5000)
					.jsonSchema(schema)
					.continueConversation(false)
					.resume("test-session-id")
					.fallbackModel("haiku")
					.build();

				List<String> cmd = transport.buildStreamingCommand(options);

				// Verify supported flags are present
				assertThat(cmd).contains("run");
				assertThat(cmd).doesNotContain("--input-format");
				assertThat(cmd).contains("--model");
				assertThat(cmd).doesNotContain("--system-prompt");
				assertThat(cmd).contains("--allowedTools");
				assertThat(cmd).contains("--disallowedTools");
				assertThat(cmd).containsSubsequence("--permission-mode", "bypassPermissions");
				assertThat(cmd).contains("--max-turns");
				assertThat(cmd).containsSubsequence("--max-budget-usd", "0.25");
				assertThat(cmd).doesNotContain("--max-thinking-tokens");
				assertThat(cmd).contains("--json-schema");
				assertThat(cmd).contains("--resume");
				assertThat(cmd).contains("--fallback-model");
				// --continue should NOT be present when false
				assertThat(cmd).doesNotContain("--continue");
			}
		}

	}

	// ============================================================
	// SolonCode CLI Specific Flags (--bare, --session-id)
	// ============================================================

	@Nested
	@DisplayName("SolonCode CLI Specific Flags")
	class SolonCodeSpecificFlags {

		@Test
		@DisplayName("--bare flag is absent by default and present when enabled")
		void bareFlag() {
			try (StdioTransport transport = createTransport()) {
				List<String> defaultCmd = transport.buildStreamingCommand(CLIOptions.builder().build());
				assertThat(defaultCmd).doesNotContain("--bare");

				List<String> cmd = transport.buildStreamingCommand(CLIOptions.builder().bare(true).build());
				assertThat(cmd).contains("--bare");
			}
		}

		@Test
		@DisplayName("--session-id flag is passed with value when set")
		void sessionIdFlag() {
			try (StdioTransport transport = createTransport()) {
				List<String> defaultCmd = transport.buildStreamingCommand(CLIOptions.builder().build());
				assertThat(defaultCmd).doesNotContain("--session-id");

				List<String> cmd = transport
					.buildStreamingCommand(CLIOptions.builder().sessionId("my-task-001").build());
				assertThat(cmd).containsSubsequence("--session-id", "my-task-001");
			}
		}

		@Test
		@DisplayName("--session-id is not added for blank values")
		void sessionIdBlankIgnored() {
			try (StdioTransport transport = createTransport()) {
				List<String> cmd = transport.buildStreamingCommand(CLIOptions.builder().sessionId("   ").build());
				assertThat(cmd).doesNotContain("--session-id");
			}
		}

		@Test
		@DisplayName("QueryOptions passes through bare and sessionId to CLIOptions")
		void queryOptionsPassthrough() {
			org.noear.soloncode.sdk.QueryOptions queryOptions = org.noear.soloncode.sdk.QueryOptions.builder()
				.bare(true)
				.sessionId("q-session-1")
				.build();

			CLIOptions cliOptions = queryOptions.toCLIOptions();
			assertThat(cliOptions.isBare()).isTrue();
			assertThat(cliOptions.getSessionId()).isEqualTo("q-session-1");

			try (StdioTransport transport = createTransport()) {
				List<String> cmd = transport.buildStreamingCommand(cliOptions);
				assertThat(cmd).contains("--bare");
				assertThat(cmd).containsSubsequence("--session-id", "q-session-1");
			}
		}

	}

}
