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

import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.annotation.ONodeAttr;
import org.noear.snack4.codec.ObjectCreator;
import org.noear.snack4.codec.TypeRef;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP server configuration matching Python SDK types. Supports: stdio, sse, http, sdk
 * (in-process).
 * <p>
 * External servers (stdio, sse, http) are passed to the SolonCode CLI via --mcp-config.
 * In-process SDK servers are managed by the Java SDK and communicate via the mcp_message
 * control protocol.
 * <p>
 * 多态入向由 {@link ConfigCreator} 按判别字段 {@code type} 分派（替代 Jackson 的
 * {@code @JsonTypeInfo/@JsonSubTypes}）；{@code type} 缺失或不认识时回退到 stdio，
 * 与原 {@code defaultImpl = McpStdioServerConfig.class} 等价。
 */
@ONodeAttr(creator = McpServerConfig.ConfigCreator.class)
public interface McpServerConfig {

	/**
	 * {@link McpServerConfig} 的多态解码分派器。判别字段：{@code type}。
	 *
	 * <p>
	 * 字段是逐个手工取的，没有走 {@code node.toBean(子类)}：这几个配置类各自有多个
	 * 便利构造器，snack4 4.0.59 在“多构造器 + 集合/Map 字段”的组合下会先用构造器填一次、
	 * 再按字段深度填一次，导致 {@code args}/{@code headers} 内容重复追加。
	 * </p>
	 */
	final class ConfigCreator implements ObjectCreator<McpServerConfig> {

		private static final Type STRING_LIST = new TypeRef<List<String>>() {
		}.getType();

		private static final Type STRING_MAP = new TypeRef<Map<String, String>>() {
		}.getType();

		@Override
		public McpServerConfig create(Options opts, ONode node, Class<?> clazz) {
			if (node == null || !node.isObject()) {
				return null;
			}

			String type = node.get("type").getString();
			if ("sse".equals(type)) {
				return new McpSseServerConfig(type, node.get("url").getString(), map(node, "headers"));
			}
			if ("http".equals(type)) {
				return new McpHttpServerConfig(type, node.get("url").getString(), map(node, "headers"));
			}
			if ("sdk".equals(type)) {
				// instance 不可序列化，与原 @JsonIgnore 一致：入向永远为 null
				return new McpSdkServerConfig(type, node.get("name").getString(), null);
			}
			// defaultImpl 语义：type 缺失/不认识一律归 stdio
			return new McpStdioServerConfig(type == null ? "stdio" : type, node.get("command").getString(),
					list(node, "args"), map(node, "env"));
		}

		private static List<String> list(ONode node, String name) {
			ONode field = node.getOrNull(name);
			return (field == null || field.isNull()) ? null : field.toBean(STRING_LIST);
		}

		private static Map<String, String> map(ONode node, String name) {
			ONode field = node.getOrNull(name);
			return (field == null || field.isNull()) ? null : field.toBean(STRING_MAP);
		}

	}

	/**
	 * Returns the server type identifier.
	 * @return the type string ("stdio", "sse", "http", or "sdk")
	 */
	String type();

	/**
	 * Returns true if this is an in-process SDK server that requires mcp_message control
	 * protocol handling.
	 * @return true for SDK servers, false for external servers
	 */
	@ONodeAttr(ignore = true)
	default boolean isSdkServer() {
		return false;
	}

	/**
	 * Stdio-based MCP server configuration. The server is started as a subprocess with
	 * the specified command and arguments.
	 *
	 * @param command the command to execute (e.g., "npx", "node", "python")
	 * @param args command arguments
	 * @param env environment variables to set for the process
	 */
	public static final class McpStdioServerConfig implements McpServerConfig {

		@ONodeAttr(name = "type")
		private final String type;

		@ONodeAttr(name = "command")
		private final String command;

		@ONodeAttr(name = "args")
		private final List<String> args;

		@ONodeAttr(name = "env")
		private final Map<String, String> env;

		public McpStdioServerConfig(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "command") String command,
				@ONodeAttr(name = "args") List<String> args, @ONodeAttr(name = "env") Map<String, String> env) {
			this.type = type;
			this.command = command;
			this.args = args;
			this.env = env;
		}

		public McpStdioServerConfig(String command, List<String> args, Map<String, String> env) {
			this("stdio", command, args, env);
		}

		public McpStdioServerConfig(String command, List<String> args) {
			this(command, args, SdkCollections.map());
		}

		public McpStdioServerConfig(String command) {
			this(command, SdkCollections.list(), SdkCollections.map());
		}

		public String type() {
			return "stdio";
		}

		public String command() {
			return command;
		}

		public List<String> args() {
			return args;
		}

		public Map<String, String> env() {
			return env;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpStdioServerConfig)) {
				return false;
			}
			McpStdioServerConfig that = (McpStdioServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(command, that.command)
					&& Objects.equals(args, that.args) && Objects.equals(env, that.env);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, command, args, env);
		}

		@Override
		public String toString() {
			return "McpStdioServerConfig[type=" + type + ", command=" + command + ", args=" + args + ", env=" + env
					+ "]";
		}

	}

	/**
	 * Server-Sent Events (SSE) based MCP server configuration. Connects to a remote
	 * server via HTTP SSE transport.
	 *
	 * @param url the SSE endpoint URL
	 * @param headers HTTP headers to include in requests
	 */
	public static final class McpSseServerConfig implements McpServerConfig {

		@ONodeAttr(name = "type")
		private final String type;

		@ONodeAttr(name = "url")
		private final String url;

		@ONodeAttr(name = "headers")
		private final Map<String, String> headers;

		public McpSseServerConfig(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "url") String url,
				@ONodeAttr(name = "headers") Map<String, String> headers) {
			this.type = type;
			this.url = url;
			this.headers = headers;
		}

		public McpSseServerConfig(String url, Map<String, String> headers) {
			this("sse", url, headers);
		}

		public McpSseServerConfig(String url) {
			this(url, SdkCollections.map());
		}

		public String type() {
			return "sse";
		}

		public String url() {
			return url;
		}

		public Map<String, String> headers() {
			return headers;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpSseServerConfig)) {
				return false;
			}
			McpSseServerConfig that = (McpSseServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(url, that.url)
					&& Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, url, headers);
		}

		@Override
		public String toString() {
			return "McpSseServerConfig[type=" + type + ", url=" + url + ", headers=" + headers + "]";
		}

	}

	/**
	 * HTTP-based MCP server configuration. Connects to a remote server via HTTP
	 * transport.
	 *
	 * @param url the HTTP endpoint URL
	 * @param headers HTTP headers to include in requests
	 */
	public static final class McpHttpServerConfig implements McpServerConfig {

		@ONodeAttr(name = "type")
		private final String type;

		@ONodeAttr(name = "url")
		private final String url;

		@ONodeAttr(name = "headers")
		private final Map<String, String> headers;

		public McpHttpServerConfig(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "url") String url,
				@ONodeAttr(name = "headers") Map<String, String> headers) {
			this.type = type;
			this.url = url;
			this.headers = headers;
		}

		public McpHttpServerConfig(String url, Map<String, String> headers) {
			this("http", url, headers);
		}

		public McpHttpServerConfig(String url) {
			this(url, SdkCollections.map());
		}

		public String type() {
			return "http";
		}

		public String url() {
			return url;
		}

		public Map<String, String> headers() {
			return headers;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpHttpServerConfig)) {
				return false;
			}
			McpHttpServerConfig that = (McpHttpServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(url, that.url)
					&& Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, url, headers);
		}

		@Override
		public String toString() {
			return "McpHttpServerConfig[type=" + type + ", url=" + url + ", headers=" + headers + "]";
		}

	}

	/**
	 * In-process SDK MCP server configuration. The server is managed by the Java SDK and
	 * communicates via the mcp_message control protocol.
	 * <p>
	 * The instance field is @ONodeAttr(ignore = true) because it cannot be serialized to the CLI. Only
	 * the type and name are passed to the CLI; the instance is used internally for
	 * handling mcp_message requests.
	 *
	 * @param name the server name (used in tool naming: mcp__{name}__{tool})
	 * @param instance the MCP server instance (not serialized to CLI)
	 */
	public static final class McpSdkServerConfig implements McpServerConfig {

		@ONodeAttr(name = "type")
		private final String type;

		@ONodeAttr(name = "name")
		private final String name;

		@ONodeAttr(ignore = true)
		private final McpSyncServer instance;

		public McpSdkServerConfig(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "name") String name,
				McpSyncServer instance) {
			this.type = type;
			this.name = name;
			this.instance = instance;
		}

		public McpSdkServerConfig(String name, McpSyncServer instance) {
			this("sdk", name, instance);
		}

		public String type() {
			return "sdk";
		}

		public String name() {
			return name;
		}

		@ONodeAttr(ignore = true)
		public McpSyncServer instance() {
			return instance;
		}

		@Override
		public boolean isSdkServer() {
			return true;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpSdkServerConfig)) {
				return false;
			}
			McpSdkServerConfig that = (McpSdkServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(name, that.name)
					&& Objects.equals(instance, that.instance);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, name, instance);
		}

		@Override
		public String toString() {
			return "McpSdkServerConfig[type=" + type + ", name=" + name + ", instance=" + instance + "]";
		}

	}

}
