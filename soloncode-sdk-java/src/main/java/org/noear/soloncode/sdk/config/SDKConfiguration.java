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

package org.noear.soloncode.sdk.config;

import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Comprehensive SDK configuration with builder pattern. Corresponds to SolonCodeOptions
 * in Python SDK with Java-specific enhancements.
 */
public final class SDKConfiguration {

	private final String model;

	private final String systemPrompt;

	private final String appendSystemPrompt;

	private final Integer maxTokens;

	private final Integer maxThinkingTokens;

	private final Duration timeout;

	private final Path workingDirectory;

	private final List<String> allowedTools;

	private final List<String> disallowedTools;

	private final PermissionMode permissionMode;

	private final boolean continueConversation;

	private final String resumeFromSession;

	private final Integer maxTurns;

	private final Map<String, Object> additionalSettings;

	public SDKConfiguration(String model, String systemPrompt, String appendSystemPrompt, Integer maxTokens,
			Integer maxThinkingTokens, Duration timeout, Path workingDirectory, List<String> allowedTools,
			List<String> disallowedTools, PermissionMode permissionMode, boolean continueConversation,
			String resumeFromSession, Integer maxTurns, Map<String, Object> additionalSettings) {
		// Validation and defaults
		if (timeout == null) {
			timeout = Duration.ofMinutes(2);
		}
		if (workingDirectory == null) {
			workingDirectory = Paths.get(System.getProperty("user.dir"));
		}
		if (allowedTools == null) {
			allowedTools = SdkCollections.list();
		}
		if (disallowedTools == null) {
			disallowedTools = SdkCollections.list();
		}
		if (permissionMode == null) {
			permissionMode = PermissionMode.DEFAULT;
		}
		if (additionalSettings == null) {
			additionalSettings = SdkCollections.map();
		}
		if (maxThinkingTokens == null) {
			maxThinkingTokens = 8000;
		}
		this.model = model;
		this.systemPrompt = systemPrompt;
		this.appendSystemPrompt = appendSystemPrompt;
		this.maxTokens = maxTokens;
		this.maxThinkingTokens = maxThinkingTokens;
		this.timeout = timeout;
		this.workingDirectory = workingDirectory;
		this.allowedTools = allowedTools;
		this.disallowedTools = disallowedTools;
		this.permissionMode = permissionMode;
		this.continueConversation = continueConversation;
		this.resumeFromSession = resumeFromSession;
		this.maxTurns = maxTurns;
		this.additionalSettings = additionalSettings;
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

	public Integer maxTokens() {
		return maxTokens;
	}

	public Integer maxThinkingTokens() {
		return maxThinkingTokens;
	}

	public Duration timeout() {
		return timeout;
	}

	public Path workingDirectory() {
		return workingDirectory;
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

	public boolean continueConversation() {
		return continueConversation;
	}

	public String resumeFromSession() {
		return resumeFromSession;
	}

	public Integer maxTurns() {
		return maxTurns;
	}

	public Map<String, Object> additionalSettings() {
		return additionalSettings;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof SDKConfiguration)) {
			return false;
		}
		SDKConfiguration that = (SDKConfiguration) o;
		return continueConversation == that.continueConversation && Objects.equals(model, that.model)
				&& Objects.equals(systemPrompt, that.systemPrompt)
				&& Objects.equals(appendSystemPrompt, that.appendSystemPrompt)
				&& Objects.equals(maxTokens, that.maxTokens) && Objects.equals(maxThinkingTokens, that.maxThinkingTokens)
				&& Objects.equals(timeout, that.timeout) && Objects.equals(workingDirectory, that.workingDirectory)
				&& Objects.equals(allowedTools, that.allowedTools)
				&& Objects.equals(disallowedTools, that.disallowedTools)
				&& permissionMode == that.permissionMode && Objects.equals(resumeFromSession, that.resumeFromSession)
				&& Objects.equals(maxTurns, that.maxTurns)
				&& Objects.equals(additionalSettings, that.additionalSettings);
	}

	@Override
	public int hashCode() {
		return Objects.hash(model, systemPrompt, appendSystemPrompt, maxTokens, maxThinkingTokens, timeout,
				workingDirectory, allowedTools, disallowedTools, permissionMode, continueConversation,
				resumeFromSession, maxTurns, additionalSettings);
	}

	@Override
	public String toString() {
		return "SDKConfiguration[model=" + model + ", systemPrompt=" + systemPrompt + ", appendSystemPrompt="
				+ appendSystemPrompt + ", maxTokens=" + maxTokens + ", maxThinkingTokens=" + maxThinkingTokens
				+ ", timeout=" + timeout + ", workingDirectory=" + workingDirectory + ", allowedTools=" + allowedTools
				+ ", disallowedTools=" + disallowedTools + ", permissionMode=" + permissionMode
				+ ", continueConversation=" + continueConversation + ", resumeFromSession=" + resumeFromSession
				+ ", maxTurns=" + maxTurns + ", additionalSettings=" + additionalSettings + "]";
	}

	/**
	 * Converts to CLIOptions for transport layer.
	 */
	public CLIOptions toCliOptions() {
		return CLIOptions.builder()
			.model(model)
			.systemPrompt(buildSystemPrompt())
			.maxTokens(maxTokens)
			.maxThinkingTokens(maxThinkingTokens)
			.timeout(timeout)
			.allowedTools(allowedTools)
			.disallowedTools(disallowedTools)
			.build();
	}

	private String buildSystemPrompt() {
		if (systemPrompt == null && appendSystemPrompt == null) {
			return null;
		}

		if (systemPrompt == null) {
			return appendSystemPrompt;
		}

		if (appendSystemPrompt == null) {
			return systemPrompt;
		}

		return systemPrompt + "\n\n" + appendSystemPrompt;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static SDKConfiguration defaultConfiguration() {
		return new SDKConfiguration(null, null, null, null, 8000, Duration.ofMinutes(2),
				Paths.get(System.getProperty("user.dir")), SdkCollections.list(), SdkCollections.list(),
				PermissionMode.BYPASS_PERMISSIONS, false, null, null, SdkCollections.map());
	}

	// Convenience getters
	public Duration getTimeout() {
		return timeout;
	}

	public Path getWorkingDirectory() {
		return workingDirectory;
	}

	public String getModel() {
		return model;
	}

	public String getSystemPrompt() {
		return buildSystemPrompt();
	}

	public Integer getMaxTokens() {
		return maxTokens;
	}

	public Integer getMaxThinkingTokens() {
		return maxThinkingTokens;
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

	public boolean isContinueConversation() {
		return continueConversation;
	}

	public String getResumeFromSession() {
		return resumeFromSession;
	}

	public Integer getMaxTurns() {
		return maxTurns;
	}

	public Map<String, Object> getAdditionalSettings() {
		return additionalSettings;
	}

	public static class Builder {

		private String model;

		private String systemPrompt;

		private String appendSystemPrompt;

		private Integer maxTokens;

		private Integer maxThinkingTokens = 8000;

		private Duration timeout = Duration.ofMinutes(2);

		private Path workingDirectory = Paths.get(System.getProperty("user.dir"));

		private List<String> allowedTools = SdkCollections.list();

		private List<String> disallowedTools = SdkCollections.list();

		private PermissionMode permissionMode = PermissionMode.BYPASS_PERMISSIONS;

		private boolean continueConversation = false;

		private String resumeFromSession;

		private Integer maxTurns;

		private Map<String, Object> additionalSettings = SdkCollections.map();

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder appendSystemPrompt(String appendSystemPrompt) {
			this.appendSystemPrompt = appendSystemPrompt;
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

		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder allowedTools(List<String> allowedTools) {
			this.allowedTools = allowedTools != null ? SdkCollections.copyList(allowedTools) : SdkCollections.list();
			return this;
		}

		public Builder disallowedTools(List<String> disallowedTools) {
			this.disallowedTools = disallowedTools != null ? SdkCollections.copyList(disallowedTools)
					: SdkCollections.list();
			return this;
		}

		public Builder permissionMode(PermissionMode permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		public Builder continueConversation(boolean continueConversation) {
			this.continueConversation = continueConversation;
			return this;
		}

		public Builder resumeFromSession(String resumeFromSession) {
			this.resumeFromSession = resumeFromSession;
			return this;
		}

		public Builder maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		public Builder additionalSettings(Map<String, Object> additionalSettings) {
			this.additionalSettings = additionalSettings != null ? SdkCollections.copyMap(additionalSettings)
					: SdkCollections.map();
			return this;
		}

		public SDKConfiguration build() {
			return new SDKConfiguration(model, systemPrompt, appendSystemPrompt, maxTokens, maxThinkingTokens, timeout,
					workingDirectory, allowedTools, disallowedTools, permissionMode, continueConversation,
					resumeFromSession, maxTurns, additionalSettings);
		}

	}
}
