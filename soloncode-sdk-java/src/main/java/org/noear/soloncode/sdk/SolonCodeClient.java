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

package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.hooks.HookRegistry;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.transport.HttpOptions;
import org.noear.soloncode.sdk.transport.TransportSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for the SolonCode Java SDK.
 *
 * <p>The execution mode is selected per request: {@link Request#call()} blocks and
 * aggregates one turn, while {@link Request#stream()} returns a demand-aware Reactor
 * stream.</p>
 *
 * <pre>{@code
 * try (SolonCodeClient client = SolonCodeClient.builder()
 *         .workingDirectory(java.nio.file.Paths.get("."))
 *         .model("sonnet")
 *         .timeout(Duration.ofMinutes(5))
 *         .build()) {
 *     QueryResult result = client.prompt("Summarize this project").call();
 *     Flux<Message> messages = client.prompt("Explain the result").stream();
 * }
 * }</pre>
 *
 * @see SolonCodeRequestDesc
 */
public interface SolonCodeClient extends AutoCloseable {

	/** Creates a unified request-oriented client builder. */
	static Builder builder() {
		return new Builder();
	}

	/** One request that can be completed either synchronously or reactively. */
	interface Request {
		/**
		 * Applies request-scoped options. On an existing persistent session only options
		 * supported by the runtime control protocol may be changed.
		 */
		Request options(QueryOptions options);

		/** Executes the turn synchronously and returns its aggregated result. */
		org.noear.soloncode.sdk.types.QueryResult call()
				throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;

		/** Executes the turn as a demand-aware reactive message stream. */
		reactor.core.publisher.Flux<org.noear.soloncode.sdk.types.Message> stream();

		/** Executes the turn as a stream and emits the aggregated terminal result. */
		reactor.core.publisher.Mono<org.noear.soloncode.sdk.types.QueryResult> streamResult();
	}

	/**
	 * Unified client builder. Successive requests on the built client are successive
	 * turns in the same session.
	 */
	class Builder {
		private Path workingDirectory;
		private Duration timeout = Duration.ofMinutes(10);
		private TransportSpec transportSpec = TransportSpec.stdio();
		private String authToken;
		private String httpWorkspace;
		private HttpOptions httpOptions;
		private HookRegistry hookRegistry;
		private String model;
		private String systemPrompt;
		private String appendSystemPrompt;
		private Integer maxTokens;
		private Integer maxThinkingTokens;
		private List<String> tools;
		private List<String> allowedTools = new ArrayList<>();
		private List<String> disallowedTools = new ArrayList<>();
		private PermissionMode permissionMode = PermissionMode.DEFAULT;
		private Integer maxTurns;
		private Double maxBudgetUsd;
		private String sessionId;
		private boolean bare;
		private String fallbackModel;
		private Map<String, McpServerConfig> mcpServers = new HashMap<>();

		public Builder workingDirectory(Path value) {
			this.workingDirectory = value;
			return this;
		}

		public Builder timeout(Duration value) {
			this.timeout = value;
			return this;
		}

		/** Uses the default persistent {@code soloncode stream} stdio transport. */
		public Builder stdio() {
			this.transportSpec = TransportSpec.stdio();
			return this;
		}

		/** Uses a persistent stdio transport with the specified CLI path. */
		public Builder stdio(String cliPath) {
			this.transportSpec = TransportSpec.stdio(cliPath);
			return this;
		}

		/** Uses the compatibility one-process-per-turn {@code soloncode run} transport. */
		public Builder stdioOneShot(String cliPath) {
			this.transportSpec = TransportSpec.stdioOneShot(cliPath);
			return this;
		}

		/** Uses the HTTP {@code /web/run} SSE transport. */
		public Builder http(String url) {
			this.transportSpec = TransportSpec.http(url);
			return this;
		}

		public Builder authToken(String value) {
			this.authToken = value;
			return this;
		}

		/** Sets the server-side workspace identifier used by the HTTP transport. */
		public Builder workspace(String value) {
			this.httpWorkspace = value;
			return this;
		}

		public Builder httpOptions(HttpOptions value) {
			this.httpOptions = value;
			return this;
		}

		public Builder hookRegistry(HookRegistry value) {
			this.hookRegistry = value;
			return this;
		}

		public Builder model(String value) {
			this.model = value;
			return this;
		}

		public Builder systemPrompt(String value) {
			this.systemPrompt = value;
			return this;
		}

		public Builder appendSystemPrompt(String value) {
			this.appendSystemPrompt = value;
			return this;
		}

		public Builder maxTokens(Integer value) {
			this.maxTokens = value;
			return this;
		}

		public Builder maxThinkingTokens(Integer value) {
			this.maxThinkingTokens = value;
			return this;
		}

		public Builder tools(List<String> value) {
			this.tools = value == null ? null : new ArrayList<>(value);
			return this;
		}

		public Builder allowedTools(List<String> value) {
			this.allowedTools = value == null ? new ArrayList<String>() : new ArrayList<>(value);
			return this;
		}

		public Builder disallowedTools(List<String> value) {
			this.disallowedTools = value == null ? new ArrayList<String>() : new ArrayList<>(value);
			return this;
		}

		public Builder permissionMode(PermissionMode value) {
			this.permissionMode = value;
			return this;
		}

		public Builder maxTurns(Integer value) {
			this.maxTurns = value;
			return this;
		}

		public Builder maxBudgetUsd(Double value) {
			this.maxBudgetUsd = value;
			return this;
		}

		public Builder sessionId(String value) {
			this.sessionId = value;
			return this;
		}

		public Builder bare(boolean value) {
			this.bare = value;
			return this;
		}

		public Builder fallbackModel(String value) {
			this.fallbackModel = value;
			return this;
		}

		public Builder mcpServer(String name, McpServerConfig value) {
			this.mcpServers.put(name, value);
			return this;
		}

		public Builder mcpServers(Map<String, McpServerConfig> value) {
			this.mcpServers = value == null ? new HashMap<String, McpServerConfig>() : new HashMap<>(value);
			return this;
		}

		/** Builds a reusable client; no process or HTTP request is started until execution. */
		public SolonCodeClient build() {
			return new DefaultSolonCodeClient(this::buildSession);
		}

		private SolonCodeSession buildSession(QueryOptions requestOptions) {
			CLIOptions base = buildOptions();
			CLIOptions effectiveOptions = requestOptions == null ? base : requestOptions.mergeInto(base);
			Path effectiveDirectory = workingDirectory;
			Duration effectiveTimeout = timeout;
			if (requestOptions != null) {
				if (requestOptions.isExplicit("workingDirectory")) {
					effectiveDirectory = requestOptions.workingDirectory();
				}
				if (requestOptions.isExplicit("timeout")) {
					effectiveTimeout = requestOptions.timeout();
				}
			}
			TransportSpec effectiveTransport = resolveTransport(effectiveDirectory);
			return new DefaultSolonCodeSession(effectiveDirectory, effectiveOptions, effectiveTimeout,
					effectiveTransport, hookRegistry);
		}

		private CLIOptions buildOptions() {
			return CLIOptions.builder()
					.model(model)
					.systemPrompt(systemPrompt)
					.appendSystemPrompt(appendSystemPrompt)
					.maxTokens(maxTokens)
					.maxThinkingTokens(maxThinkingTokens)
					.tools(tools)
					.allowedTools(allowedTools)
					.disallowedTools(disallowedTools)
					.permissionMode(permissionMode)
					.maxTurns(maxTurns)
					.maxBudgetUsd(maxBudgetUsd)
					.mcpServers(mcpServers)
					.sessionId(sessionId)
					.bare(bare)
					.fallbackModel(fallbackModel)
					.build();
		}

		private TransportSpec resolveTransport(Path effectiveDirectory) {
			TransportSpec effectiveTransport = transportSpec;
			if (transportSpec.isHttp()) {
				if (effectiveDirectory != null) {
					throw new IllegalArgumentException(
							"workingDirectory is not applicable to the http transport; use workspace(String) instead");
				}
				if (authToken != null || httpWorkspace != null) {
					effectiveTransport = effectiveTransport.withHttpCredentials(authToken, httpWorkspace);
				}
				if (httpOptions != null) {
					effectiveTransport = effectiveTransport.withHttpOptions(httpOptions);
				}
			}
			else {
				if (httpOptions != null) {
					throw new IllegalArgumentException("httpOptions is only applicable to the http transport");
				}
				if (effectiveDirectory == null) {
					throw new IllegalArgumentException("workingDirectory is required");
				}
			}
			return effectiveTransport;
		}
	}

	Request prompt(String prompt);

	void interrupt() throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;

	void setModel(String model) throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;

	void setPermissionMode(String mode) throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;

	CLIOptions getOptions();

	String getCurrentModel();

	String getCurrentPermissionMode();

	Map<String, Object> getServerInfo();

	boolean isConnected();

	void setToolPermissionCallback(org.noear.soloncode.sdk.permission.ToolPermissionCallback callback);

	org.noear.soloncode.sdk.permission.ToolPermissionCallback getToolPermissionCallback();

	@Override
	void close();
}
