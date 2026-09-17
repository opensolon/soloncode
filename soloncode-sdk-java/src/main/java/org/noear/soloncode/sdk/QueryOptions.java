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

package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.transport.CLIOptions;

import org.noear.soloncode.sdk.util.SdkCollections;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Simplified configuration options for one-shot queries. This class provides a minimal
 * set of options needed for typical query use cases.
 *
 * <p>For reusable multi-turn sessions, configure a {@link SolonCodeClient} once and
 * choose {@code call()} or {@code stream()} on each request.</p>
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * // Simple query with defaults
 * String answer = Query.text("What is 2+2?");
 *
 * // With options
 * QueryResult result = Query.execute("Explain recursion",
 *     QueryOptions.builder()
 *         .model("sonnet")
 *         .systemPrompt("Be concise")
 *         .timeout(Duration.ofMinutes(5))
 *         .build());
 * }</pre>
 *
 * @see Query
 * @see CLIOptions
 */
public final class QueryOptions {

	/** Default timeout for queries. */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

	/** Default working directory (current directory). */
	public static final Path DEFAULT_WORKING_DIRECTORY = Paths.get(System.getProperty("user.dir"));

	private final String model;

	private final String systemPrompt;

	private final String appendSystemPrompt;

	private final Duration timeout;

	private final List<String> allowedTools;

	private final List<String> disallowedTools;

	private final Integer maxTurns;

	private final Double maxBudgetUsd;

	private final Path workingDirectory;

	private final Integer maxTokens;

	private final Integer maxThinkingTokens;

	private final String fallbackModel;

	private final Map<String, Object> jsonSchema;

	private final String sessionId;

	private final boolean bare;

	/** Fields explicitly supplied through the builder; defaults are not request overrides. */
	private final Set<String> explicitFields;

	public QueryOptions(String model, String systemPrompt, String appendSystemPrompt, Duration timeout,
			List<String> allowedTools, List<String> disallowedTools, Integer maxTurns, Double maxBudgetUsd,
			Path workingDirectory, Integer maxTokens, Integer maxThinkingTokens, String fallbackModel,
			Map<String, Object> jsonSchema, String sessionId, boolean bare) {
		this(model, systemPrompt, appendSystemPrompt, timeout, allowedTools, disallowedTools, maxTurns, maxBudgetUsd,
				workingDirectory, maxTokens, maxThinkingTokens, fallbackModel, jsonSchema, sessionId, bare,
				allFieldNames());
	}

	private QueryOptions(String model, String systemPrompt, String appendSystemPrompt, Duration timeout,
			List<String> allowedTools, List<String> disallowedTools, Integer maxTurns, Double maxBudgetUsd,
			Path workingDirectory, Integer maxTokens, Integer maxThinkingTokens, String fallbackModel,
			Map<String, Object> jsonSchema, String sessionId, boolean bare, Set<String> explicitFields) {
		if (timeout == null) {
			timeout = DEFAULT_TIMEOUT;
		}
		if (allowedTools == null) {
			allowedTools = SdkCollections.list();
		}
		if (disallowedTools == null) {
			disallowedTools = SdkCollections.list();
		}
		if (workingDirectory == null) {
			workingDirectory = DEFAULT_WORKING_DIRECTORY;
		}
		this.model = model;
		this.systemPrompt = systemPrompt;
		this.appendSystemPrompt = appendSystemPrompt;
		this.timeout = timeout;
		this.allowedTools = SdkCollections.copyList(allowedTools);
		this.disallowedTools = SdkCollections.copyList(disallowedTools);
		this.maxTurns = maxTurns;
		this.maxBudgetUsd = maxBudgetUsd;
		this.workingDirectory = workingDirectory;
		this.maxTokens = maxTokens;
		this.maxThinkingTokens = maxThinkingTokens;
		this.fallbackModel = fallbackModel;
		this.jsonSchema = immutableJsonMap(jsonSchema);
		this.sessionId = sessionId;
		this.bare = bare;
		this.explicitFields = Collections.unmodifiableSet(new LinkedHashSet<>(explicitFields));
	}

	public String model() {
		return model;
	}

	public String systemPrompt() {
		return systemPrompt;
	}

	public String appendSystemPrompt() {
		return appendSystemPrompt;
	}

	public Duration timeout() {
		return timeout;
	}

	public List<String> allowedTools() {
		return allowedTools;
	}

	public List<String> disallowedTools() {
		return disallowedTools;
	}

	public Integer maxTurns() {
		return maxTurns;
	}

	public Double maxBudgetUsd() {
		return maxBudgetUsd;
	}

	public Path workingDirectory() {
		return workingDirectory;
	}

	public Integer maxTokens() {
		return maxTokens;
	}

	public Integer maxThinkingTokens() {
		return maxThinkingTokens;
	}

	public String fallbackModel() {
		return fallbackModel;
	}

	public Map<String, Object> jsonSchema() {
		return jsonSchema;
	}

	public String sessionId() {
		return sessionId;
	}

	public boolean bare() {
		return bare;
	}

	boolean isExplicit(String field) {
		return explicitFields.contains(field);
	}

	Set<String> explicitFields() {
		return explicitFields;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof QueryOptions)) {
			return false;
		}
		QueryOptions that = (QueryOptions) o;
		return Objects.equals(model, that.model)
				&& Objects.equals(systemPrompt, that.systemPrompt)
				&& Objects.equals(appendSystemPrompt, that.appendSystemPrompt)
				&& Objects.equals(timeout, that.timeout)
				&& Objects.equals(allowedTools, that.allowedTools)
				&& Objects.equals(disallowedTools, that.disallowedTools)
				&& Objects.equals(maxTurns, that.maxTurns)
				&& Objects.equals(maxBudgetUsd, that.maxBudgetUsd)
				&& Objects.equals(workingDirectory, that.workingDirectory)
				&& Objects.equals(maxTokens, that.maxTokens)
				&& Objects.equals(maxThinkingTokens, that.maxThinkingTokens)
				&& Objects.equals(fallbackModel, that.fallbackModel)
				&& Objects.equals(jsonSchema, that.jsonSchema) && Objects.equals(sessionId, that.sessionId)
				&& Objects.equals(explicitFields, that.explicitFields)
			&& bare == that.bare;
	}

	@Override
	public int hashCode() {
		return Objects.hash(model, systemPrompt, appendSystemPrompt, timeout, allowedTools, disallowedTools,
				maxTurns, maxBudgetUsd, workingDirectory, maxTokens, maxThinkingTokens, fallbackModel, jsonSchema,
				sessionId, bare, explicitFields);
	}

	@Override
	public String toString() {
		return "QueryOptions[model=" + model + ", systemPrompt=" + systemPrompt + ", appendSystemPrompt="
				+ appendSystemPrompt + ", timeout=" + timeout + ", allowedTools=" + allowedTools
				+ ", disallowedTools=" + disallowedTools + ", maxTurns=" + maxTurns + ", maxBudgetUsd=" + maxBudgetUsd
				+ ", workingDirectory=" + workingDirectory + ", maxTokens=" + maxTokens + ", maxThinkingTokens="
				+ maxThinkingTokens + ", fallbackModel=" + fallbackModel + ", jsonSchema=" + jsonSchema + ", sessionId="
				+ sessionId + ", bare=" + bare + "]";
	}

	/**
	 * Returns default options suitable for most queries.
	 */
	public static QueryOptions defaults() {
		return builder().build();
	}

	/**
	 * Creates a new builder for QueryOptions.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Converts these simplified options to full CLIOptions for internal use.
	 */
	public CLIOptions toCLIOptions() {
		CLIOptions.Builder builder = CLIOptions.builder()
			.model(model)
			.timeout(timeout)
			.allowedTools(allowedTools)
			.disallowedTools(disallowedTools)
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS);

		if (systemPrompt != null) {
			builder.systemPrompt(systemPrompt);
		}

		if (appendSystemPrompt != null) {
			builder.appendSystemPrompt(appendSystemPrompt);
		}

		if (maxTurns != null) {
			builder.maxTurns(maxTurns);
		}

		if (maxBudgetUsd != null) {
			builder.maxBudgetUsd(maxBudgetUsd);
		}

		if (maxTokens != null) {
			builder.maxTokens(maxTokens);
		}

		if (maxThinkingTokens != null) {
			builder.maxThinkingTokens(maxThinkingTokens);
		}

		if (fallbackModel != null) {
			builder.fallbackModel(fallbackModel);
		}

		if (jsonSchema != null) {
			builder.jsonSchema(jsonSchema);
		}

		if (sessionId != null) {
			builder.sessionId(sessionId);
		}

		builder.bare(bare);

		return builder.build();
	}

	/** Applies only explicitly configured request fields to the supplied client defaults. */
	CLIOptions mergeInto(CLIOptions base) {
		Objects.requireNonNull(base, "base");
		CLIOptions.Builder builder = copyOf(base);
		if (isExplicit("model")) {
			builder.model(model);
		}
		if (isExplicit("systemPrompt")) {
			builder.systemPrompt(systemPrompt);
		}
		if (isExplicit("appendSystemPrompt")) {
			builder.appendSystemPrompt(appendSystemPrompt);
		}
		if (isExplicit("timeout")) {
			builder.timeout(timeout);
		}
		if (isExplicit("allowedTools")) {
			builder.allowedTools(allowedTools);
		}
		if (isExplicit("disallowedTools")) {
			builder.disallowedTools(disallowedTools);
		}
		if (isExplicit("maxTurns")) {
			builder.maxTurns(maxTurns);
		}
		if (isExplicit("maxBudgetUsd")) {
			builder.maxBudgetUsd(maxBudgetUsd);
		}
		if (isExplicit("maxTokens")) {
			builder.maxTokens(maxTokens);
		}
		if (isExplicit("maxThinkingTokens")) {
			builder.maxThinkingTokens(maxThinkingTokens);
		}
		if (isExplicit("fallbackModel")) {
			builder.fallbackModel(fallbackModel);
		}
		if (isExplicit("jsonSchema")) {
			builder.jsonSchema(jsonSchema);
		}
		if (isExplicit("sessionId")) {
			builder.sessionId(sessionId);
		}
		if (isExplicit("bare")) {
			builder.bare(bare);
		}
		return builder.build();
	}

	private static CLIOptions.Builder copyOf(CLIOptions base) {
		return CLIOptions.builder()
				.model(base.model())
				.systemPrompt(base.systemPrompt())
				.maxTokens(base.maxTokens())
				.maxThinkingTokens(base.maxThinkingTokens())
				.timeout(base.timeout())
				.tools(base.tools())
				.allowedTools(base.allowedTools())
				.disallowedTools(base.disallowedTools())
				.permissionMode(base.permissionMode())
				.interactive(base.interactive())
				.outputFormat(base.outputFormat())
				.settingSources(base.settingSources())
				.agents(base.agents())
				.forkSession(base.forkSession())
				.includePartialMessages(base.includePartialMessages())
				.jsonSchema(base.jsonSchema())
				.mcpServers(base.mcpServers())
				.maxTurns(base.maxTurns())
				.maxBudgetUsd(base.maxBudgetUsd())
				.fallbackModel(base.fallbackModel())
				.appendSystemPrompt(base.appendSystemPrompt())
				.continueConversation(base.continueConversation())
				.resume(base.resume())
				.addDirs(base.addDirs())
				.settings(base.settings())
				.permissionPromptToolName(base.permissionPromptToolName())
				.extraArgs(base.extraArgs())
				.plugins(base.plugins())
				.env(base.env())
				.maxBufferSize(base.maxBufferSize())
				.user(base.user())
				.sessionId(base.sessionId())
				.bare(base.bare())
				.stderrHandler(base.stderrHandler())
				.toolPermissionCallback(base.toolPermissionCallback());
	}

	public static class Builder {

		private String model;

		private String systemPrompt;

		private String appendSystemPrompt;

		private Duration timeout = DEFAULT_TIMEOUT;

		private List<String> allowedTools = SdkCollections.list();

		private List<String> disallowedTools = SdkCollections.list();

		private Integer maxTurns;

		private Double maxBudgetUsd;

		private Path workingDirectory = DEFAULT_WORKING_DIRECTORY;

		private Integer maxTokens;

		private Integer maxThinkingTokens;

		private String fallbackModel;

		private Map<String, Object> jsonSchema;

		// soloncode CLI specific options
		private String sessionId;

		private boolean bare = false;

		private final Set<String> explicitFields = new LinkedHashSet<>();

		/**
		 * Sets the model to use. Accepts a model name or alias registered in the
		 * soloncode workspace configuration (commonly "sonnet", "opus", "haiku").
		 * <p>
		 * Leave unset to use the engine default model. An unregistered name falls back
		 * to the default model rather than failing.
		 */
		public Builder model(String model) {
			this.model = model;
			this.explicitFields.add("model");
			return this;
		}

		/**
		 * Sets a custom system prompt that replaces the default.
		 */
		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			this.explicitFields.add("systemPrompt");
			return this;
		}

		/**
		 * Sets text to append to the default system prompt.
		 */
		public Builder appendSystemPrompt(String appendSystemPrompt) {
			this.appendSystemPrompt = appendSystemPrompt;
			this.explicitFields.add("appendSystemPrompt");
			return this;
		}

		/**
		 * Sets the timeout for the query. Default is 2 minutes.
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			this.explicitFields.add("timeout");
			return this;
		}

		/**
		 * Sets the list of allowed tools. If empty, all tools are allowed.
		 */
		public Builder allowedTools(List<String> allowedTools) {
			this.allowedTools = allowedTools != null ? SdkCollections.copyList(allowedTools) : SdkCollections.list();
			this.explicitFields.add("allowedTools");
			return this;
		}

		/**
		 * Sets the list of disallowed tools.
		 */
		public Builder disallowedTools(List<String> disallowedTools) {
			this.disallowedTools = disallowedTools != null ? SdkCollections.copyList(disallowedTools) : SdkCollections.list();
			this.explicitFields.add("disallowedTools");
			return this;
		}

		/**
		 * Sets the maximum number of agentic turns.
		 */
		public Builder maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			this.explicitFields.add("maxTurns");
			return this;
		}

		/**
		 * Sets the maximum budget in USD.
		 */
		public Builder maxBudgetUsd(Double maxBudgetUsd) {
			this.maxBudgetUsd = maxBudgetUsd;
			this.explicitFields.add("maxBudgetUsd");
			return this;
		}

		/**
		 * Sets the working directory for the query.
		 */
		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			this.explicitFields.add("workingDirectory");
			return this;
		}

		/**
		 * Sets the maximum number of output tokens.
		 */
		public Builder maxTokens(Integer maxTokens) {
			this.maxTokens = maxTokens;
			this.explicitFields.add("maxTokens");
			return this;
		}

		/**
		 * Sets the maximum number of thinking tokens for extended thinking.
		 */
		public Builder maxThinkingTokens(Integer maxThinkingTokens) {
			this.maxThinkingTokens = maxThinkingTokens;
			this.explicitFields.add("maxThinkingTokens");
			return this;
		}

		/**
		 * Sets the fallback model to use if the primary model is unavailable.
		 */
		public Builder fallbackModel(String fallbackModel) {
			this.fallbackModel = fallbackModel;
			this.explicitFields.add("fallbackModel");
			return this;
		}

		/**
		 * Sets the JSON schema for structured output.
		 */
		public Builder jsonSchema(Map<String, Object> jsonSchema) {
			this.jsonSchema = immutableJsonMap(jsonSchema);
			this.explicitFields.add("jsonSchema");
			return this;
		}

		/**
		 * Sets a fixed session ID for this execution (--session-id flag).
		 */
		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			this.explicitFields.add("sessionId");
			return this;
		}

		/**
		 * Skips skills/agents mounts, MCP services and memory auto-discovery (--bare flag).
		 */
		public Builder bare(boolean bare) {
			this.bare = bare;
			this.explicitFields.add("bare");
			return this;
		}

		public QueryOptions build() {
			return new QueryOptions(model, systemPrompt, appendSystemPrompt, timeout, allowedTools, disallowedTools,
					maxTurns, maxBudgetUsd, workingDirectory, maxTokens, maxThinkingTokens, fallbackModel, jsonSchema,
					sessionId, bare, explicitFields);
		}

	}

	private static Set<String> allFieldNames() {
		Set<String> fields = new LinkedHashSet<>();
		Collections.addAll(fields, "model", "systemPrompt", "appendSystemPrompt", "timeout", "allowedTools",
				"disallowedTools", "maxTurns", "maxBudgetUsd", "workingDirectory", "maxTokens",
				"maxThinkingTokens", "fallbackModel", "jsonSchema", "sessionId", "bare");
		return fields;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> immutableJsonMap(Map<String, Object> source) {
		if (source == null) {
			return null;
		}
		Map<String, Object> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			copy.put(Objects.requireNonNull(entry.getKey()), immutableJsonValue(entry.getValue()));
		}
		return Collections.unmodifiableMap(copy);
	}

	@SuppressWarnings("unchecked")
	private static Object immutableJsonValue(Object value) {
		if (value instanceof Map) {
			return immutableJsonMap((Map<String, Object>) value);
		}
		if (value instanceof List) {
			List<Object> copy = new ArrayList<>();
			for (Object item : (List<?>) value) {
				copy.add(immutableJsonValue(item));
			}
			return Collections.unmodifiableList(copy);
		}
		return value;
	}

}
