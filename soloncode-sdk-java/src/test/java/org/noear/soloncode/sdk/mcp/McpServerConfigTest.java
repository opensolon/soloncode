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

import org.noear.soloncode.sdk.util.SdkCollections;

import org.noear.snack4.ONode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MCP server configuration serialization.
 */
class McpServerConfigTest {


	@BeforeEach
	void setUp() {
	}

	@Test
	void stdioConfigSerialization() throws Exception {
		McpServerConfig.McpStdioServerConfig config = new McpServerConfig.McpStdioServerConfig("npx",
				SdkCollections.list("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), SdkCollections.map("NODE_ENV", "production"));

		String json = ONode.serialize(config);

		assertThat(json).contains("\"type\":\"stdio\"");
		assertThat(json).contains("\"command\":\"npx\"");
		assertThat(json).contains("\"args\"");
		assertThat(json).contains("\"env\"");

		// Deserialize and verify
		McpServerConfig deserialized = ONode.deserialize(json, McpServerConfig.class);
		assertThat(deserialized).isInstanceOf(McpServerConfig.McpStdioServerConfig.class);
		McpServerConfig.McpStdioServerConfig stdio = (McpServerConfig.McpStdioServerConfig) deserialized;
		assertThat(stdio.command()).isEqualTo("npx");
		assertThat(stdio.args()).containsExactly("-y", "@modelcontextprotocol/server-filesystem", "/tmp");
	}

	@Test
	void stdioConfigConvenienceConstructor() {
		McpServerConfig.McpStdioServerConfig config = new McpServerConfig.McpStdioServerConfig("python",
				SdkCollections.list("server.py"));

		assertThat(config.type()).isEqualTo("stdio");
		assertThat(config.command()).isEqualTo("python");
		assertThat(config.args()).containsExactly("server.py");
		assertThat(config.env()).isEmpty();
	}

	@Test
	void sseConfigSerialization() throws Exception {
		McpServerConfig.McpSseServerConfig config = new McpServerConfig.McpSseServerConfig("http://localhost:8080/sse",
				SdkCollections.map("Authorization", "Bearer token123"));

		String json = ONode.serialize(config);

		assertThat(json).contains("\"type\":\"sse\"");
		assertThat(json).contains("\"url\":\"http://localhost:8080/sse\"");
		assertThat(json).contains("\"headers\"");

		// Deserialize and verify
		McpServerConfig deserialized = ONode.deserialize(json, McpServerConfig.class);
		assertThat(deserialized).isInstanceOf(McpServerConfig.McpSseServerConfig.class);
		McpServerConfig.McpSseServerConfig sse = (McpServerConfig.McpSseServerConfig) deserialized;
		assertThat(sse.url()).isEqualTo("http://localhost:8080/sse");
		assertThat(sse.headers()).containsEntry("Authorization", "Bearer token123");
	}

	@Test
	void httpConfigSerialization() throws Exception {
		McpServerConfig.McpHttpServerConfig config = new McpServerConfig.McpHttpServerConfig(
				"http://localhost:8080/mcp");

		String json = ONode.serialize(config);

		assertThat(json).contains("\"type\":\"http\"");
		assertThat(json).contains("\"url\":\"http://localhost:8080/mcp\"");

		// Deserialize and verify
		McpServerConfig deserialized = ONode.deserialize(json, McpServerConfig.class);
		assertThat(deserialized).isInstanceOf(McpServerConfig.McpHttpServerConfig.class);
		McpServerConfig.McpHttpServerConfig http = (McpServerConfig.McpHttpServerConfig) deserialized;
		assertThat(http.url()).isEqualTo("http://localhost:8080/mcp");
	}

	@Test
	void sdkConfigDoesNotSerializeInstance() throws Exception {
		// SDK config with null instance (instance is @ONodeAttr(ignore = true))
		McpServerConfig.McpSdkServerConfig config = new McpServerConfig.McpSdkServerConfig("calculator", null);

		String json = ONode.serialize(config);

		assertThat(json).contains("\"type\":\"sdk\"");
		assertThat(json).contains("\"name\":\"calculator\"");
		// Instance should not be serialized
		assertThat(json).doesNotContain("\"instance\"");
	}

	@Test
	void isSdkServerReturnsTrueOnlyForSdkConfig() {
		McpServerConfig stdio = new McpServerConfig.McpStdioServerConfig("npx", SdkCollections.list());
		McpServerConfig sse = new McpServerConfig.McpSseServerConfig("http://localhost");
		McpServerConfig http = new McpServerConfig.McpHttpServerConfig("http://localhost");
		McpServerConfig sdk = new McpServerConfig.McpSdkServerConfig("test", null);

		assertThat(stdio.isSdkServer()).isFalse();
		assertThat(sse.isSdkServer()).isFalse();
		assertThat(http.isSdkServer()).isFalse();
		assertThat(sdk.isSdkServer()).isTrue();
	}

	@Test
	void typeMethodReturnsCorrectType() {
		assertThat(new McpServerConfig.McpStdioServerConfig("cmd").type()).isEqualTo("stdio");
		assertThat(new McpServerConfig.McpSseServerConfig("http://localhost").type()).isEqualTo("sse");
		assertThat(new McpServerConfig.McpHttpServerConfig("http://localhost").type()).isEqualTo("http");
		assertThat(new McpServerConfig.McpSdkServerConfig("test", null).type()).isEqualTo("sdk");
	}

}
