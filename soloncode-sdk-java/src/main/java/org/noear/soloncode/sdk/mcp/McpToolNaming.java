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

/**
 * Utility class for MCP tool naming conventions.
 * <p>
 * MCP tools follow the naming convention: {@code mcp__{server_name}__{tool_name}}
 * <p>
 * For example, a tool named "add" from a server named "calc" would be:
 * {@code mcp__calc__add}
 */
public final class McpToolNaming {

	private static final String MCP_PREFIX = "mcp__";

	private static final String SEPARATOR = "__";

	private McpToolNaming() {
		// Utility class
	}

	/**
	 * Formats a tool name with the MCP naming convention.
	 * @param serverName the MCP server name
	 * @param toolName the tool name within the server
	 * @return the formatted tool name (e.g., "mcp__calc__add")
	 */
	public static String formatToolName(String serverName, String toolName) {
		if (serverName == null || serverName.trim().isEmpty()) {
			throw new IllegalArgumentException("Server name cannot be null or blank");
		}
		if (toolName == null || toolName.trim().isEmpty()) {
			throw new IllegalArgumentException("Tool name cannot be null or blank");
		}
		return MCP_PREFIX + serverName + SEPARATOR + toolName;
	}

	/**
	 * Parses an MCP tool name into its server and tool components.
	 * @param fullName the full tool name (e.g., "mcp__calc__add")
	 * @return a two-element array [serverName, toolName], or null if not a valid MCP tool
	 * name
	 */
	public static String[] parseToolName(String fullName) {
		if (fullName == null || !fullName.startsWith(MCP_PREFIX)) {
			return null;
		}
		String remainder = fullName.substring(MCP_PREFIX.length());
		int separatorIndex = remainder.indexOf(SEPARATOR);
		if (separatorIndex <= 0 || separatorIndex == remainder.length() - SEPARATOR.length()) {
			return null;
		}
		String serverName = remainder.substring(0, separatorIndex);
		String toolName = remainder.substring(separatorIndex + SEPARATOR.length());
		if (serverName.trim().isEmpty() || toolName.trim().isEmpty()) {
			return null;
		}
		return new String[] { serverName, toolName };
	}

	/**
	 * Checks if a tool name follows the MCP naming convention.
	 * @param toolName the tool name to check
	 * @return true if the tool name starts with "mcp__" and contains a valid server/tool
	 * separator
	 */
	public static boolean isMcpToolName(String toolName) {
		return parseToolName(toolName) != null;
	}

	/**
	 * Extracts the server name from an MCP tool name.
	 * @param fullName the full tool name (e.g., "mcp__calc__add")
	 * @return the server name, or null if not a valid MCP tool name
	 */
	public static String extractServerName(String fullName) {
		String[] parts = parseToolName(fullName);
		return parts != null ? parts[0] : null;
	}

	/**
	 * Extracts the tool name (without server prefix) from an MCP tool name.
	 * @param fullName the full tool name (e.g., "mcp__calc__add")
	 * @return the tool name, or null if not a valid MCP tool name
	 */
	public static String extractToolName(String fullName) {
		String[] parts = parseToolName(fullName);
		return parts != null ? parts[1] : null;
	}

}
