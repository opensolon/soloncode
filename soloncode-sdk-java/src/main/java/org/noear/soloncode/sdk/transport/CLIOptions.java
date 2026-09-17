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

import org.noear.soloncode.sdk.config.OutputFormat;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.config.PluginConfig;
import org.noear.soloncode.sdk.mcp.McpServerConfig;

import org.noear.soloncode.sdk.util.SdkCollections;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration options for SolonCode CLI commands. Corresponds to SolonCodeAgentOptions in
 * Python SDK.
 */
public final class CLIOptions {

	private final String model;

	private final String systemPrompt;

	private final Integer maxTokens;

	private final Integer maxThinkingTokens;

	private final Duration timeout;

	private final List<String> tools;

	private final List<String> allowedTools;

	private final List<String> disallowedTools;

	private final PermissionMode permissionMode;

	private final boolean interactive;

	private final OutputFormat outputFormat;

	private final List<String> settingSources;

	private final String agents;

	private final boolean forkSession;

	private final boolean includePartialMessages;

	private final Map<String, Object> jsonSchema;

	private final Map<String, McpServerConfig> mcpServers;

	private final Integer maxTurns;

	private final Double maxBudgetUsd;

	private final String fallbackModel;

	private final String appendSystemPrompt;

	// Session resume options
	private final boolean continueConversation;

	private final String resume;

	// Advanced options for full Python SDK parity
	private final List<Path> addDirs;

	private final String settings;

	private final String permissionPromptToolName;

	private final Map<String, String> extraArgs;

	private final List<PluginConfig> plugins;

	private final Map<String, String> env;

	private final Integer maxBufferSize;

	private final String user;

	// soloncode CLI specific options
	private final String sessionId;

	private final boolean bare;

	private final StderrHandler stderrHandler;

	private final ToolPermissionCallback toolPermissionCallback;

	/** Default maximum buffer size for JSON parsing (1MB). */
	public static final int DEFAULT_MAX_BUFFER_SIZE = 1024 * 1024;

	// 不提供模型 ID 常量：soloncode 的 --model 取工作区配置里已注册的模型名或别名
	// （如 sonnet / haiku / opus），由部署方决定，SDK 无从预知。不传则用引擎默认模型。

	public CLIOptions(String model, String systemPrompt, Integer maxTokens, Integer maxThinkingTokens,
			Duration timeout, List<String> tools, List<String> allowedTools, List<String> disallowedTools,
			PermissionMode permissionMode, boolean interactive, OutputFormat outputFormat, List<String> settingSources,
			String agents, boolean forkSession, boolean includePartialMessages, Map<String, Object> jsonSchema,
			Map<String, McpServerConfig> mcpServers, Integer maxTurns, Double maxBudgetUsd, String fallbackModel,
			String appendSystemPrompt, boolean continueConversation, String resume, List<Path> addDirs, String settings,
			String permissionPromptToolName, Map<String, String> extraArgs, List<PluginConfig> plugins,
			Map<String, String> env, Integer maxBufferSize, String user, String sessionId, boolean bare,
			StderrHandler stderrHandler, ToolPermissionCallback toolPermissionCallback) {
		// Validation
		if (timeout == null) {
			timeout = Duration.ofMinutes(2);
		}
		// tools can be null (don't add --tools flag), empty list (--tools ""), or list of
		// tool names
		if (allowedTools == null) {
			allowedTools = SdkCollections.list();
		}
		if (disallowedTools == null) {
			disallowedTools = SdkCollections.list();
		}
		if (permissionMode == null) {
			permissionMode = PermissionMode.DEFAULT;
		}
		if (outputFormat == null) {
			outputFormat = OutputFormat.JSON; // Default to JSON for non-reactive
		}
		if (settingSources == null) {
			settingSources = SdkCollections.list(); // Default: no filesystem settings loaded
		}
		if (mcpServers == null) {
			mcpServers = SdkCollections.map();
		}
		// Advanced options defaults
		if (addDirs == null) {
			addDirs = SdkCollections.list();
		}
		if (extraArgs == null) {
			extraArgs = SdkCollections.map();
		}
		if (plugins == null) {
			plugins = SdkCollections.list();
		}
		if (env == null) {
			env = SdkCollections.map();
		}

		this.model = model;
		this.systemPrompt = systemPrompt;
		this.maxTokens = maxTokens;
		this.maxThinkingTokens = maxThinkingTokens;
		this.timeout = timeout;
		this.tools = tools == null ? null : SdkCollections.copyList(tools);
		this.allowedTools = SdkCollections.copyList(allowedTools);
		this.disallowedTools = SdkCollections.copyList(disallowedTools);
		this.permissionMode = permissionMode;
		this.interactive = interactive;
		this.outputFormat = outputFormat;
		this.settingSources = SdkCollections.copyList(settingSources);
		this.agents = agents;
		this.forkSession = forkSession;
		this.includePartialMessages = includePartialMessages;
		this.jsonSchema = jsonSchema == null ? null : SdkCollections.copyMap(jsonSchema);
		this.mcpServers = SdkCollections.copyMap(mcpServers);
		this.maxTurns = maxTurns;
		this.maxBudgetUsd = maxBudgetUsd;
		this.fallbackModel = fallbackModel;
		this.appendSystemPrompt = appendSystemPrompt;
		this.continueConversation = continueConversation;
		this.resume = resume;
		this.addDirs = SdkCollections.copyList(addDirs);
		this.settings = settings;
		this.permissionPromptToolName = permissionPromptToolName;
		this.extraArgs = SdkCollections.copyMap(extraArgs);
		this.plugins = SdkCollections.copyList(plugins);
		this.env = SdkCollections.copyMap(env);
		this.maxBufferSize = maxBufferSize;
		this.user = user;
		this.sessionId = sessionId;
		this.bare = bare;
		this.stderrHandler = stderrHandler;
		this.toolPermissionCallback = toolPermissionCallback;
	}

	// Component accessors (record-style)
	public String model() {
		return model;
	}

	public String systemPrompt() {
		return systemPrompt;
	}

	public Integer maxTokens() {
		return maxTokens;
	}

	public Integer maxThinkingTokens() {
		return maxThinkingTokens;
	}

	public Duration timeout() {
		return timeout;
	}

	public List<String> tools() {
		return tools;
	}

	public List<String> allowedTools() {
		return allowedTools;
	}

	public List<String> disallowedTools() {
		return disallowedTools;
	}

	public PermissionMode permissionMode() {
		return permissionMode;
	}

	public boolean interactive() {
		return interactive;
	}

	public OutputFormat outputFormat() {
		return outputFormat;
	}

	public List<String> settingSources() {
		return settingSources;
	}

	public String agents() {
		return agents;
	}

	public boolean forkSession() {
		return forkSession;
	}

	public boolean includePartialMessages() {
		return includePartialMessages;
	}

	public Map<String, Object> jsonSchema() {
		return jsonSchema;
	}

	public Map<String, McpServerConfig> mcpServers() {
		return mcpServers;
	}

	public Integer maxTurns() {
		return maxTurns;
	}

	public Double maxBudgetUsd() {
		return maxBudgetUsd;
	}

	public String fallbackModel() {
		return fallbackModel;
	}

	public String appendSystemPrompt() {
		return appendSystemPrompt;
	}

	public boolean continueConversation() {
		return continueConversation;
	}

	public String resume() {
		return resume;
	}

	public List<Path> addDirs() {
		return addDirs;
	}

	public String settings() {
		return settings;
	}

	public String permissionPromptToolName() {
		return permissionPromptToolName;
	}

	public Map<String, String> extraArgs() {
		return extraArgs;
	}

	public List<PluginConfig> plugins() {
		return plugins;
	}

	public Map<String, String> env() {
		return env;
	}

	public Integer maxBufferSize() {
		return maxBufferSize;
	}

	public String user() {
		return user;
	}

	public String sessionId() {
		return sessionId;
	}

	public boolean bare() {
		return bare;
	}

	public StderrHandler stderrHandler() {
		return stderrHandler;
	}

	public ToolPermissionCallback toolPermissionCallback() {
		return toolPermissionCallback;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CLIOptions)) {
			return false;
		}
		CLIOptions other = (CLIOptions) o;
		return interactive == other.interactive && forkSession == other.forkSession
				&& includePartialMessages == other.includePartialMessages
				&& continueConversation == other.continueConversation
				&& Objects.equals(model, other.model) && Objects.equals(systemPrompt, other.systemPrompt)
				&& Objects.equals(maxTokens, other.maxTokens) && Objects.equals(maxThinkingTokens, other.maxThinkingTokens)
				&& Objects.equals(timeout, other.timeout) && Objects.equals(tools, other.tools)
				&& Objects.equals(allowedTools, other.allowedTools)
				&& Objects.equals(disallowedTools, other.disallowedTools)
				&& Objects.equals(permissionMode, other.permissionMode)
				&& Objects.equals(outputFormat, other.outputFormat)
				&& Objects.equals(settingSources, other.settingSources) && Objects.equals(agents, other.agents)
				&& Objects.equals(jsonSchema, other.jsonSchema) && Objects.equals(mcpServers, other.mcpServers)
				&& Objects.equals(maxTurns, other.maxTurns) && Objects.equals(maxBudgetUsd, other.maxBudgetUsd)
				&& Objects.equals(fallbackModel, other.fallbackModel)
				&& Objects.equals(appendSystemPrompt, other.appendSystemPrompt)
				&& Objects.equals(resume, other.resume) && Objects.equals(addDirs, other.addDirs)
				&& Objects.equals(settings, other.settings)
				&& Objects.equals(permissionPromptToolName, other.permissionPromptToolName)
				&& Objects.equals(extraArgs, other.extraArgs) && Objects.equals(plugins, other.plugins)
				&& Objects.equals(env, other.env) && Objects.equals(maxBufferSize, other.maxBufferSize)
				&& Objects.equals(user, other.user) && Objects.equals(sessionId, other.sessionId) && bare == other.bare
				&& Objects.equals(stderrHandler, other.stderrHandler)
				&& Objects.equals(toolPermissionCallback, other.toolPermissionCallback);
	}

	@Override
	public int hashCode() {
		return Objects.hash(model, systemPrompt, maxTokens, maxThinkingTokens, timeout, tools, allowedTools,
				disallowedTools, permissionMode, interactive, outputFormat, settingSources, agents, forkSession,
				includePartialMessages, jsonSchema, mcpServers, maxTurns, maxBudgetUsd, fallbackModel, appendSystemPrompt,
				continueConversation, resume, addDirs, settings, permissionPromptToolName, extraArgs, plugins, env,
				maxBufferSize, user, sessionId, bare, stderrHandler, toolPermissionCallback);
	}

	@Override
	public String toString() {
		return "CLIOptions[model=" + model + ", maxTokens=" + maxTokens
				+ ", maxThinkingTokens=" + maxThinkingTokens + ", timeout=" + timeout
				+ ", tools=" + (tools == null ? null : tools.size())
				+ ", allowedTools=" + allowedTools.size() + ", disallowedTools=" + disallowedTools.size()
				+ ", permissionMode=" + permissionMode + ", interactive=" + interactive
				+ ", outputFormat=" + outputFormat + ", settingSources=" + settingSources
				+ ", agents=" + agents + ", forkSession=" + forkSession
				+ ", includePartialMessages=" + includePartialMessages
				+ ", jsonSchema=" + (jsonSchema == null ? null : "<" + jsonSchema.size() + " entries>")
				+ ", mcpServers=" + mcpServers.keySet() + ", maxTurns=" + maxTurns
				+ ", maxBudgetUsd=" + maxBudgetUsd + ", fallbackModel=" + fallbackModel
				+ ", appendSystemPrompt=" + (appendSystemPrompt == null ? null : "<set>")
				+ ", continueConversation=" + continueConversation + ", resume=" + (resume == null ? null : "<set>")
				+ ", addDirs=" + addDirs + ", settings=" + (settings == null ? null : "<set>")
				+ ", permissionPromptToolName=" + permissionPromptToolName
				+ ", extraArgs=" + extraArgs.keySet() + ", plugins=" + plugins.size()
				+ ", envKeys=" + env.keySet() + ", maxBufferSize=" + maxBufferSize
				+ ", user=" + user + ", sessionId=" + sessionId + ", bare=" + bare
				+ ", stderrHandler=" + stderrHandler + "]";
	}

	public static Builder builder() {
		return new Builder();
	}

	public static CLIOptions defaultOptions() {
		return new CLIOptions(null, null, null, null, Duration.ofMinutes(2), null, SdkCollections.list(),
				SdkCollections.list(), PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS, false, OutputFormat.JSON,
				SdkCollections.list(), null, false, false, null, SdkCollections.map(), null, null, null, null, false,
				null, SdkCollections.list(), null, null, SdkCollections.map(), SdkCollections.list(),
				SdkCollections.map(), null, null, null, false, null, null);
	}

	// Convenience getters
	public Duration getTimeout() {
		return timeout;
	}

	public String getModel() {
		return model;
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public Integer getMaxTokens() {
		return maxTokens;
	}

	public Integer getMaxThinkingTokens() {
		return maxThinkingTokens;
	}

	public List<String> getTools() {
		return tools;
	}

	public List<String> getAllowedTools() {
		return allowedTools;
	}

	public List<String> getDisallowedTools() {
		return disallowedTools;
	}

	public PermissionMode getPermissionMode() {
		return permissionMode;
	}

	public boolean isInteractive() {
		return interactive;
	}

	public OutputFormat getOutputFormat() {
		return outputFormat;
	}

	public List<String> getSettingSources() {
		return settingSources;
	}

	public String getAgents() {
		return agents;
	}

	public boolean isForkSession() {
		return forkSession;
	}

	public boolean isIncludePartialMessages() {
		return includePartialMessages;
	}

	public Map<String, Object> getJsonSchema() {
		return jsonSchema;
	}

	public Map<String, McpServerConfig> getMcpServers() {
		return mcpServers;
	}

	public Integer getMaxTurns() {
		return maxTurns;
	}

	public Double getMaxBudgetUsd() {
		return maxBudgetUsd;
	}

	public String getFallbackModel() {
		return fallbackModel;
	}

	public String getAppendSystemPrompt() {
		return appendSystemPrompt;
	}

	public boolean isContinueConversation() {
		return continueConversation;
	}

	public String getResume() {
		return resume;
	}

	// Advanced options getters
	public List<Path> getAddDirs() {
		return addDirs;
	}

	public String getSettings() {
		return settings;
	}

	public String getPermissionPromptToolName() {
		return permissionPromptToolName;
	}

	public Map<String, String> getExtraArgs() {
		return extraArgs;
	}

	public List<PluginConfig> getPlugins() {
		return plugins;
	}

	public Map<String, String> getEnv() {
		return env;
	}

	public Integer getMaxBufferSize() {
		return maxBufferSize;
	}

	public int getEffectiveMaxBufferSize() {
		return maxBufferSize != null ? maxBufferSize : DEFAULT_MAX_BUFFER_SIZE;
	}

	public String getUser() {
		return user;
	}

	public String getSessionId() {
		return sessionId;
	}

	public boolean isBare() {
		return bare;
	}

	public StderrHandler getStderrHandler() {
		return stderrHandler;
	}

	public ToolPermissionCallback getToolPermissionCallback() {
		return toolPermissionCallback;
	}

	public static class Builder {

		private String model;

		private String systemPrompt;

		private Integer maxTokens;

		private Integer maxThinkingTokens;

		private Duration timeout = Duration.ofMinutes(2);

		private List<String> tools; // null = don't add --tools, empty = --tools "",
									// non-empty = --tools "Read,Edit"

		private List<String> allowedTools = SdkCollections.list();

		private List<String> disallowedTools = SdkCollections.list();

		private PermissionMode permissionMode = PermissionMode.BYPASS_PERMISSIONS;

		private boolean interactive = false;

		private OutputFormat outputFormat = OutputFormat.JSON;

		private List<String> settingSources = SdkCollections.list();

		private String agents;

		private boolean forkSession = false;

		private boolean includePartialMessages = false;

		private Map<String, Object> jsonSchema;

		private Map<String, McpServerConfig> mcpServers = SdkCollections.map();

		private Integer maxTurns;

		private Double maxBudgetUsd;

		private String fallbackModel;

		private String appendSystemPrompt;

		private boolean continueConversation = false;

		private String resume;

		// Advanced options for full Python SDK parity
		private List<Path> addDirs = SdkCollections.list();

		private String settings;

		private String permissionPromptToolName;

		private Map<String, String> extraArgs = SdkCollections.map();

		private List<PluginConfig> plugins = SdkCollections.list();

		private Map<String, String> env = SdkCollections.map();

		private Integer maxBufferSize;

		private String user;

		// soloncode CLI specific options
		private String sessionId;

		private boolean bare = false;

		private StderrHandler stderrHandler;

		private ToolPermissionCallback toolPermissionCallback;

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder maxTokens(Integer maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		public Builder maxThinkingTokens(Integer maxThinkingTokens) {
			this.maxThinkingTokens = maxThinkingTokens;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Sets the base set of tools available. This is different from allowedTools which
		 * filters the available tools.
		 * @param tools list of tool names, empty list for no tools, or null to use
		 * defaults
		 * @return this builder
		 */
		public Builder tools(List<String> tools) {
			this.tools = tools != null ? SdkCollections.copyList(tools) : null;
			return this;
		}

		public Builder allowedTools(List<String> allowedTools) {
			this.allowedTools = allowedTools != null ? SdkCollections.copyList(allowedTools) : SdkCollections.list();
			return this;
		}

		public Builder disallowedTools(List<String> disallowedTools) {
			this.disallowedTools = disallowedTools != null ? SdkCollections.copyList(disallowedTools) : SdkCollections.list();
			return this;
		}

		public Builder permissionMode(PermissionMode permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		public Builder interactive(boolean interactive) {
			this.interactive = interactive;
			return this;
		}

		public Builder outputFormat(OutputFormat outputFormat) {
			this.outputFormat = outputFormat;
			return this;
		}

		public Builder settingSources(List<String> settingSources) {
			this.settingSources = settingSources != null ? SdkCollections.copyList(settingSources) : SdkCollections.list();
			return this;
		}

		public Builder agents(String agents) {
			this.agents = agents;
			return this;
		}

		public Builder forkSession(boolean forkSession) {
			this.forkSession = forkSession;
			return this;
		}

		public Builder includePartialMessages(boolean includePartialMessages) {
			this.includePartialMessages = includePartialMessages;
			return this;
		}

		public Builder jsonSchema(Map<String, Object> jsonSchema) {
			this.jsonSchema = jsonSchema != null ? SdkCollections.copyMap(jsonSchema) : null;
			return this;
		}

		/**
		 * Sets all MCP servers for this session.
		 * @param mcpServers map of server name to configuration
		 * @return this builder
		 */
		public Builder mcpServers(Map<String, McpServerConfig> mcpServers) {
			this.mcpServers = mcpServers != null ? SdkCollections.copyMap(mcpServers) : SdkCollections.map();
			return this;
		}

		/**
		 * Adds a single MCP server to this session.
		 * @param name the server name (used in tool naming: mcp__{name}__{tool})
		 * @param config the server configuration
		 * @return this builder
		 */
		public Builder mcpServer(String name, McpServerConfig config) {
			if (this.mcpServers.isEmpty()) {
				this.mcpServers = new HashMap<>();
			}
			else if (!(this.mcpServers instanceof HashMap)) {
				this.mcpServers = new HashMap<>(this.mcpServers);
			}
			this.mcpServers.put(name, config);
			return this;
		}

		/**
		 * Sets the maximum number of agentic turns for this session.
		 * @param maxTurns maximum turns before stopping
		 * @return this builder
		 */
		public Builder maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		/**
		 * Sets the maximum budget in USD for this session.
		 * @param maxBudgetUsd maximum cost before stopping
		 * @return this builder
		 */
		public Builder maxBudgetUsd(Double maxBudgetUsd) {
			this.maxBudgetUsd = maxBudgetUsd;
			return this;
		}

		/**
		 * Sets the fallback model to use if the primary model is unavailable.
		 * @param fallbackModel the fallback model ID
		 * @return this builder
		 */
		public Builder fallbackModel(String fallbackModel) {
			this.fallbackModel = fallbackModel;
			return this;
		}

		/**
		 * Sets additional text to append to the system prompt (uses preset with append).
		 * @param appendSystemPrompt text to append to the default system prompt
		 * @return this builder
		 */
		public Builder appendSystemPrompt(String appendSystemPrompt) {
			this.appendSystemPrompt = appendSystemPrompt;
			return this;
		}

		/**
		 * Sets whether to continue the most recent conversation (--continue flag).
		 * @param continueConversation true to continue most recent session
		 * @return this builder
		 */
		public Builder continueConversation(boolean continueConversation) {
			this.continueConversation = continueConversation;
			return this;
		}

		/**
		 * Sets a session ID to resume (--resume flag).
		 * @param resume the session ID to resume
		 * @return this builder
		 */
		public Builder resume(String resume) {
			this.resume = resume;
			return this;
		}

		// ============================================================
		// Advanced options for full Python SDK parity
		// ============================================================

		/**
		 * Sets additional directories to include in SolonCode's context.
		 * @param addDirs list of directory paths to add
		 * @return this builder
		 */
		public Builder addDirs(List<Path> addDirs) {
			this.addDirs = addDirs != null ? SdkCollections.copyList(addDirs) : SdkCollections.list();
			return this;
		}

		/**
		 * Adds a single directory to SolonCode's context.
		 * @param dir directory path to add
		 * @return this builder
		 */
		public Builder addDir(Path dir) {
			if (this.addDirs.isEmpty()) {
				this.addDirs = new ArrayList<>();
			}
			else if (!(this.addDirs instanceof ArrayList)) {
				this.addDirs = new ArrayList<>(this.addDirs);
			}
			this.addDirs.add(dir);
			return this;
		}

		/**
		 * Sets a custom settings file path.
		 * @param settings path to the settings file
		 * @return this builder
		 */
		public Builder settings(String settings) {
			this.settings = settings;
			return this;
		}

		/**
		 * Sets the permission prompt tool name for interactive permission handling.
		 * @param permissionPromptToolName the tool name (e.g., "stdio")
		 * @return this builder
		 */
		public Builder permissionPromptToolName(String permissionPromptToolName) {
			this.permissionPromptToolName = permissionPromptToolName;
			return this;
		}

		/**
		 * Sets arbitrary extra CLI arguments.
		 * @param extraArgs map of flag name to value (null value for boolean flags)
		 * @return this builder
		 */
		public Builder extraArgs(Map<String, String> extraArgs) {
			// Note: Cannot use Map.copyOf() because it doesn't allow null values,
			// but null values are used for boolean flags (--flag without value)
			this.extraArgs = extraArgs != null ? new HashMap<>(extraArgs) : SdkCollections.map();
			return this;
		}

		/**
		 * Adds a single extra CLI argument.
		 * @param flag the flag name (without --)
		 * @param value the flag value (null for boolean flags)
		 * @return this builder
		 */
		public Builder extraArg(String flag, String value) {
			if (this.extraArgs.isEmpty()) {
				this.extraArgs = new HashMap<>();
			}
			else if (!(this.extraArgs instanceof HashMap)) {
				this.extraArgs = new HashMap<>(this.extraArgs);
			}
			this.extraArgs.put(flag, value);
			return this;
		}

		/**
		 * Sets plugin configurations.
		 * @param plugins list of plugin configs
		 * @return this builder
		 */
		public Builder plugins(List<PluginConfig> plugins) {
			this.plugins = plugins != null ? SdkCollections.copyList(plugins) : SdkCollections.list();
			return this;
		}

		/**
		 * Adds a single plugin.
		 * @param plugin the plugin configuration
		 * @return this builder
		 */
		public Builder plugin(PluginConfig plugin) {
			if (this.plugins.isEmpty()) {
				this.plugins = new ArrayList<>();
			}
			else if (!(this.plugins instanceof ArrayList)) {
				this.plugins = new ArrayList<>(this.plugins);
			}
			this.plugins.add(plugin);
			return this;
		}

		/**
		 * Sets custom environment variables for the CLI process.
		 * @param env map of environment variable name to value
		 * @return this builder
		 */
		public Builder env(Map<String, String> env) {
			this.env = env != null ? SdkCollections.copyMap(env) : SdkCollections.map();
			return this;
		}

		/**
		 * Adds a single environment variable.
		 * @param name the environment variable name
		 * @param value the environment variable value
		 * @return this builder
		 */
		public Builder env(String name, String value) {
			if (this.env.isEmpty()) {
				this.env = new HashMap<>();
			}
			else if (!(this.env instanceof HashMap)) {
				this.env = new HashMap<>(this.env);
			}
			this.env.put(name, value);
			return this;
		}

		/**
		 * Sets the maximum buffer size for JSON parsing.
		 * @param maxBufferSize maximum bytes (default 1MB)
		 * @return this builder
		 */
		public Builder maxBufferSize(Integer maxBufferSize) {
			this.maxBufferSize = maxBufferSize;
			return this;
		}

		/**
		 * Sets the Unix user to run the CLI process as.
		 * @param user the Unix username (requires sudo configuration)
		 * @return this builder
		 */
		public Builder user(String user) {
			this.user = user;
			return this;
		}

		/**
		 * Sets a fixed session ID for this execution (--session-id flag).
		 * @param sessionId the session ID to use
		 * @return this builder
		 */
		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/**
		 * Skips skills/agents mounts, MCP services and memory auto-discovery (--bare flag).
		 * @param bare true to run in bare/isolated mode
		 * @return this builder
		 */
		public Builder bare(boolean bare) {
			this.bare = bare;
			return this;
		}

		/**
		 * Sets the stderr handler for capturing CLI diagnostic output.
		 * @param stderrHandler handler for stderr lines
		 * @return this builder
		 */
		public Builder stderrHandler(StderrHandler stderrHandler) {
			this.stderrHandler = stderrHandler;
			return this;
		}

		/**
		 * Sets the tool permission callback for dynamic permission decisions.
		 * @param toolPermissionCallback callback for tool permission checks
		 * @return this builder
		 */
		public Builder toolPermissionCallback(ToolPermissionCallback toolPermissionCallback) {
			this.toolPermissionCallback = toolPermissionCallback;
			return this;
		}

		public CLIOptions build() {
			return new CLIOptions(model, systemPrompt, maxTokens, maxThinkingTokens, timeout, tools, allowedTools,
					disallowedTools, permissionMode, interactive, outputFormat, settingSources, agents, forkSession,
					includePartialMessages, jsonSchema, mcpServers, maxTurns, maxBudgetUsd, fallbackModel,
					appendSystemPrompt, continueConversation, resume, addDirs, settings, permissionPromptToolName,
					extraArgs, plugins, env, maxBufferSize, user, sessionId, bare, stderrHandler, toolPermissionCallback);
		}

	}
}