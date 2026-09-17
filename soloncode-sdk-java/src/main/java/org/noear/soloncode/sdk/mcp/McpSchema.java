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

package org.noear.soloncode.sdk.mcp;


/**
 * Minimal inlined replacement for the MCP Java SDK types that were previously provided by
 * io.modelcontextprotocol.sdk:mcp (which requires Java 17). Only the members actually used by
 * this SDK are provided; semantics mirror the original records.
 */
public final class McpSchema {

	private McpSchema() {
	}

	public interface Tool {
		String name();

		String description();

		Object inputSchema();
	}

	public interface Resource {
		String uri();

		String name();

		String description();

		String mimeType();
	}

	public interface Prompt {
		String name();

		String description();

		java.util.List<PromptArgument> arguments();
	}

	public interface PromptArgument {
		String name();

		String description();

		boolean required();
	}

	public interface ServerCapabilities {
		ToolCapabilities tools();

		ResourceCapabilities resources();

		PromptCapabilities prompts();
	}

	public interface ToolCapabilities {
		boolean listChanged();
	}

	public interface ResourceCapabilities {
		boolean subscribe();

		boolean listChanged();
	}

	public interface PromptCapabilities {
		boolean listChanged();
	}

	public interface Implementation {
		String name();

		String version();
	}
}
