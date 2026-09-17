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

package org.noear.soloncode.sdk.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for MCP tool naming convention utilities.
 */
class McpToolNamingTest {

	@Test
	void formatToolNameCreatesCorrectFormat() {
		String formatted = McpToolNaming.formatToolName("calc", "add");
		assertThat(formatted).isEqualTo("mcp__calc__add");
	}

	@Test
	void formatToolNameWithComplexNames() {
		String formatted = McpToolNaming.formatToolName("file-system", "read_file");
		assertThat(formatted).isEqualTo("mcp__file-system__read_file");
	}

	@Test
	void formatToolNameThrowsOnNullServerName() {
		assertThatThrownBy(() -> McpToolNaming.formatToolName(null, "add")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Server name");
	}

	@Test
	void formatToolNameThrowsOnBlankServerName() {
		assertThatThrownBy(() -> McpToolNaming.formatToolName("  ", "add")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Server name");
	}

	@Test
	void formatToolNameThrowsOnNullToolName() {
		assertThatThrownBy(() -> McpToolNaming.formatToolName("calc", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Tool name");
	}

	@Test
	void formatToolNameThrowsOnBlankToolName() {
		assertThatThrownBy(() -> McpToolNaming.formatToolName("calc", "")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Tool name");
	}

	@Test
	void parseToolNameReturnsServerAndTool() {
		String[] parts = McpToolNaming.parseToolName("mcp__calc__add");
		assertThat(parts).isNotNull();
		assertThat(parts).hasSize(2);
		assertThat(parts[0]).isEqualTo("calc");
		assertThat(parts[1]).isEqualTo("add");
	}

	@Test
	void parseToolNameWithComplexNames() {
		String[] parts = McpToolNaming.parseToolName("mcp__file-system__read_file");
		assertThat(parts).isNotNull();
		assertThat(parts[0]).isEqualTo("file-system");
		assertThat(parts[1]).isEqualTo("read_file");
	}

	@Test
	void parseToolNameWithToolContainingDoubleUnderscore() {
		// Tool name containing __ should still work (only first __ after mcp__ is
		// separator)
		String[] parts = McpToolNaming.parseToolName("mcp__server__tool__with__underscores");
		assertThat(parts).isNotNull();
		assertThat(parts[0]).isEqualTo("server");
		assertThat(parts[1]).isEqualTo("tool__with__underscores");
	}

	@Test
	void parseToolNameReturnsNullForNonMcpName() {
		String[] parts = McpToolNaming.parseToolName("read_file");
		assertThat(parts).isNull();
	}

	@Test
	void parseToolNameReturnsNullForNullInput() {
		String[] parts = McpToolNaming.parseToolName(null);
		assertThat(parts).isNull();
	}

	@Test
	void parseToolNameReturnsNullForMissingServerName() {
		String[] parts = McpToolNaming.parseToolName("mcp____tool");
		assertThat(parts).isNull();
	}

	@Test
	void parseToolNameReturnsNullForMissingToolName() {
		String[] parts = McpToolNaming.parseToolName("mcp__server__");
		assertThat(parts).isNull();
	}

	@Test
	void parseToolNameReturnsNullForOnlyPrefix() {
		String[] parts = McpToolNaming.parseToolName("mcp__");
		assertThat(parts).isNull();
	}

	@Test
	void isMcpToolNameReturnsTrueForValidMcpName() {
		assertThat(McpToolNaming.isMcpToolName("mcp__calc__add")).isTrue();
		assertThat(McpToolNaming.isMcpToolName("mcp__file-system__read_file")).isTrue();
	}

	@Test
	void isMcpToolNameReturnsFalseForNonMcpName() {
		assertThat(McpToolNaming.isMcpToolName("read_file")).isFalse();
		assertThat(McpToolNaming.isMcpToolName("Bash")).isFalse();
		assertThat(McpToolNaming.isMcpToolName(null)).isFalse();
	}

	@Test
	void extractServerNameReturnsServerName() {
		assertThat(McpToolNaming.extractServerName("mcp__calc__add")).isEqualTo("calc");
		assertThat(McpToolNaming.extractServerName("mcp__file-system__read_file")).isEqualTo("file-system");
	}

	@Test
	void extractServerNameReturnsNullForNonMcpName() {
		assertThat(McpToolNaming.extractServerName("read_file")).isNull();
		assertThat(McpToolNaming.extractServerName(null)).isNull();
	}

	@Test
	void extractToolNameReturnsToolName() {
		assertThat(McpToolNaming.extractToolName("mcp__calc__add")).isEqualTo("add");
		assertThat(McpToolNaming.extractToolName("mcp__file-system__read_file")).isEqualTo("read_file");
	}

	@Test
	void extractToolNameReturnsNullForNonMcpName() {
		assertThat(McpToolNaming.extractToolName("read_file")).isNull();
		assertThat(McpToolNaming.extractToolName(null)).isNull();
	}

}
