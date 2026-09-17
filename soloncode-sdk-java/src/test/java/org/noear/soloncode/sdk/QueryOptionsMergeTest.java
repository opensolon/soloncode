/*
 * Copyright 2025 soloncode
 *
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.soloncode.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.transport.CLIOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryOptionsMergeTest {

	@TempDir
	Path tempDir;

	@Test
	void defaultsAreNotExplicitRequestOverrides() {
		QueryOptions defaults = QueryOptions.defaults();

		assertThat(defaults.explicitFields()).isEmpty();
		assertThat(defaults.timeout()).isEqualTo(QueryOptions.DEFAULT_TIMEOUT);
		assertThat(defaults.workingDirectory()).isEqualTo(QueryOptions.DEFAULT_WORKING_DIRECTORY);
	}

	@Test
	void requestPatchOverridesOnlyExplicitFields() {
		Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();
		mcpServers.put("local", new McpServerConfig.McpStdioServerConfig("tool", Arrays.asList("serve")));
		CLIOptions base = CLIOptions.builder()
				.model("base-model")
				.systemPrompt("base-system")
				.allowedTools(Arrays.asList("Read"))
				.permissionMode(PermissionMode.DEFAULT)
				.mcpServers(mcpServers)
				.maxBudgetUsd(3.0)
				.build();

		CLIOptions effective = QueryOptions.builder()
				.model("request-model")
				.maxTurns(4)
				.build()
				.mergeInto(base);

		assertThat(effective.model()).isEqualTo("request-model");
		assertThat(effective.maxTurns()).isEqualTo(4);
		assertThat(effective.systemPrompt()).isEqualTo("base-system");
		assertThat(effective.allowedTools()).containsExactly("Read");
		assertThat(effective.permissionMode()).isEqualTo(PermissionMode.DEFAULT);
		assertThat(effective.mcpServers()).containsOnlyKeys("local");
		assertThat(effective.maxBudgetUsd()).isEqualTo(3.0);
	}

	@Test
	void unifiedBuilderExposesConfiguredDefaults() {
		try (SolonCodeClient client = SolonCodeClient.builder()
				.workingDirectory(tempDir)
				.model("base-model")
				.systemPrompt("base-system")
				.permissionMode(PermissionMode.DEFAULT)
				.timeout(Duration.ofMinutes(9))
				.build()) {
			assertThat(client.getOptions().model()).isEqualTo("base-model");
			assertThat(client.getOptions().systemPrompt()).isEqualTo("base-system");
			assertThat(client.getOptions().permissionMode()).isEqualTo(PermissionMode.DEFAULT);
		}
	}

	@Test
	void queryOptionsDefensivelyCopiesListsAndNestedSchema() {
		List<String> tools = new ArrayList<>(Arrays.asList("Read"));
		List<Object> required = new ArrayList<Object>(Arrays.<Object>asList("name"));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("required", required);

		QueryOptions options = QueryOptions.builder().allowedTools(tools).jsonSchema(schema).build();
		int hash = options.hashCode();
		tools.add("Edit");
		required.add("age");
		schema.put("type", "object");

		assertThat(options.allowedTools()).containsExactly("Read");
		assertThat((List<?>) options.jsonSchema().get("required")).containsExactly("name");
		assertThat(options.hashCode()).isEqualTo(hash);
		assertThatThrownBy(() -> options.allowedTools().add("Bash"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> ((List<Object>) options.jsonSchema().get("required")).add("x"))
				.isInstanceOf(UnsupportedOperationException.class);
	}
}
