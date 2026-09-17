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
 *
 * Adapted from claude-agent-sdk-java (Apache License 2.0).
 */

package org.noear.soloncode.sdk.transport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI flag parity: {@code soloncode run --help} is the golden standard.
 *
 * <p>
 * Extracts every option from the CLI's own help output and verifies the SDK can express
 * it. When the CLI grows a new {@code run} option this test fails, which is the reminder
 * to add SDK support.
 * </p>
 *
 * <p>
 * The reverse direction matters just as much: the SDK deliberately ignores a set of
 * claude-inherited options because soloncode has no equivalent (the transport logs a
 * warning for each). {@link #sdkIgnoredOptionsShouldStillBeAbsentFromCli()} fails once
 * the CLI starts supporting one of them, so the SDK stops silently dropping a now-valid
 * option.
 * </p>
 *
 * <p>
 * Requires soloncode CLI v2026.8.30 or newer, which is the first version with
 * {@code help} / {@code --help} / {@code run --help}.
 * </p>
 */
@DisplayName("CLI Flag Parity IT")
class CLIFlagParityIT extends SolonCodeCliTestBase {

	private static Set<String> cliFlags;

	private static String cliHelpOutput;

	/**
	 * Options present in the CLI help that the SDK intentionally does not expose as a
	 * builder method. Every entry needs a reason.
	 */
	private static final Set<String> EXCLUDED_FLAGS = SdkCollections.set(
			// Help/version: process-level queries, never part of a query
			"help", "h", "version", "v",

			// Always added by the SDK itself when stream-json is used
			"verbose");

	/**
	 * CLI option name to CLIOptions.Builder method name, for the cases where the names do
	 * not match after kebab-to-camel conversion.
	 */
	private static final java.util.Map<String, String> FLAG_TO_METHOD = SdkCollections.map(
			"continue", "continueConversation",
			"add-dir", "addDirs",
			"output-format", "outputFormat");

	/**
	 * Options the SDK accepts for claude compatibility but drops with a warning, because
	 * soloncode has no such flag. Kept in sync with CLIOptions/StdioTransport.
	 */
	private static final Set<String> SDK_IGNORED_OPTIONS = SdkCollections.set("system-prompt", "append-system-prompt",
			"agents", "mcp-config", "settings", "setting-sources", "fork-session", "include-partial-messages",
			"max-thinking-tokens", "permission-prompt-tool", "input-format", "plugin-dir");

	@BeforeAll
	static void extractCliFlagsFromHelp() throws Exception {
		cliHelpOutput = runHelp(getSolonCodeCliPath(), "run", "--help");
		cliFlags = parseFlags(cliHelpOutput);

		assertThat(cliFlags)
			.as("`soloncode run --help` produced no parseable options. "
					+ "Help support landed in CLI v2026.8.30 - upgrade the CLI. Raw output: " + cliHelpOutput)
			.isNotEmpty();
	}

	private static String runHelp(String cliPath, String... args) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(cliPath);
		command.addAll(Arrays.asList(args));

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		Process process = pb.start();

		String output;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			output = reader.lines().collect(Collectors.joining("\n"));
		}

		process.waitFor();
		return output == null ? "" : output;
	}

	/**
	 * Parses option names out of help text: {@code --model <model>}, {@code -h,}.
	 */
	private static Set<String> parseFlags(String helpOutput) {
		Set<String> flags = new HashSet<>();

		Matcher matcher = Pattern.compile("--([a-zA-Z][a-zA-Z0-9-]*)").matcher(helpOutput);
		while (matcher.find()) {
			flags.add(matcher.group(1));
		}

		Matcher shortMatcher = Pattern.compile("\\s-([a-zA-Z]),").matcher(helpOutput);
		while (shortMatcher.find()) {
			flags.add(shortMatcher.group(1));
		}

		return flags;
	}

	@Test
	@DisplayName("All CLI run options should have SDK support or be explicitly excluded")
	void allCliFlagsShouldHaveSdkSupport() {
		Set<String> builderMethods = getBuilderMethodNames();
		Set<String> unsupportedFlags = new HashSet<>();

		for (String flag : cliFlags) {
			if (EXCLUDED_FLAGS.contains(flag)) {
				continue;
			}

			String methodName = FLAG_TO_METHOD.getOrDefault(flag, toCamelCase(flag));

			if (!builderMethods.contains(methodName)) {
				unsupportedFlags.add(flag);
			}
		}

		assertThat(unsupportedFlags)
			.as("Every CLI run option needs a CLIOptions.Builder method: add support, "
					+ "or add it to EXCLUDED_FLAGS with a justification. Unsupported: " + unsupportedFlags)
			.isEmpty();
	}

	@Test
	@DisplayName("CLI help should be parseable")
	void cliHelpShouldBeParseable() {
		assertThat(cliHelpOutput).contains("Usage: soloncode run");
		assertThat(cliFlags).contains("output-format", "model", "resume", "continue", "json-schema");
	}

	@Test
	@DisplayName("Options the SDK relies on should still exist in the CLI")
	void criticalSdkFlagsShouldBeInCli() {
		// Everything the SDK maps onto a real soloncode flag. A miss here means the CLI
		// renamed or dropped an option and the SDK would emit an argument it rejects.
		List<String> criticalFlags = SdkCollections.list("output-format", "model", "fallback-model", "max-turns",
				"max-budget-usd", "json-schema", "session-id", "resume", "continue", "add-dir", "allowedTools",
				"disallowedTools", "permission-mode", "bare");

		for (String flag : criticalFlags) {
			assertThat(cliFlags).as("CLI should still support flag: " + flag).contains(flag);
		}
	}

	@Test
	@DisplayName("Permission modes accepted by the SDK should be documented by the CLI")
	void permissionModesShouldMatchCli() {
		// PermissionMode enum values are passed through verbatim; the CLI must accept them
		for (String mode : new String[] { "default", "acceptEdits", "plan", "dontAsk", "bypassPermissions" }) {
			assertThat(cliHelpOutput).as("CLI should document permission mode: " + mode).contains(mode);
		}
	}

	@Test
	@DisplayName("Output formats accepted by the SDK should be documented by the CLI")
	void outputFormatsShouldMatchCli() {
		for (String format : new String[] { "text", "json", "stream-json" }) {
			assertThat(cliHelpOutput).as("CLI should document output format: " + format).contains(format);
		}
	}

	@Test
	@DisplayName("Options the SDK silently ignores should still be absent from the CLI")
	void sdkIgnoredOptionsShouldStillBeAbsentFromCli() {
		// These are accepted by CLIOptions for claude compatibility and dropped with a
		// warning. If the CLI ever implements one, the SDK must forward it instead.
		Set<String> nowSupported = new HashSet<>(SDK_IGNORED_OPTIONS);
		nowSupported.retainAll(cliFlags);

		assertThat(nowSupported)
			.as("The CLI now supports options the SDK still drops with a warning; "
					+ "wire them through in CLIOptions/StdioTransport: " + nowSupported)
			.isEmpty();
	}

	@Test
	@DisplayName("Report CLI flags found for documentation")
	void reportCliFlagsFound() {
		System.out.println("=== soloncode run flags ===");
		cliFlags.stream().sorted().forEach(flag -> {
			String status = EXCLUDED_FLAGS.contains(flag) ? " [EXCLUDED]" : "";
			String methodName = FLAG_TO_METHOD.getOrDefault(flag, toCamelCase(flag));
			System.out.printf("  --%s -> %s%s%n", flag, methodName, status);
		});
		System.out.println("Total: " + cliFlags.size() + " flags, excluded: " + EXCLUDED_FLAGS.size());
	}

	/**
	 * Gets all builder method names from CLIOptions.Builder.
	 */
	private Set<String> getBuilderMethodNames() {
		Set<String> methods = new HashSet<>();
		for (Method method : CLIOptions.Builder.class.getDeclaredMethods()) {
			if (method.getReturnType().equals(CLIOptions.Builder.class)) {
				methods.add(method.getName());
			}
		}
		return methods;
	}

	/**
	 * Converts kebab-case to camelCase.
	 */
	private String toCamelCase(String kebab) {
		StringBuilder sb = new StringBuilder();
		boolean capitalizeNext = false;
		for (char c : kebab.toCharArray()) {
			if (c == '-') {
				capitalizeNext = true;
			}
			else if (capitalizeNext) {
				sb.append(Character.toUpperCase(c));
				capitalizeNext = false;
			}
			else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

}
