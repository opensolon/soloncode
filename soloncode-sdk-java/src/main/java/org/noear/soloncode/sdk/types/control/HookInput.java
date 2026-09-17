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

package org.noear.soloncode.sdk.types.control;

import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.annotation.ONodeAttr;
import org.noear.snack4.codec.CodecException;
import org.noear.snack4.codec.ObjectCreator;
import org.noear.soloncode.sdk.util.PrimitiveSafeCreator;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Base interface for all hook input types. Each hook event receives a specific
 * input type with relevant data.
 *
 * <p>
 * 多态入向由 {@link HookInputCreator} 按判别字段 {@code hook_event_name} 手工分派（替代
 * Jackson 的 {@code @JsonTypeInfo(property = "hook_event_name", visible = true)}）；各实现类
 * 自带 {@code hook_event_name} 字段，所以判别字段同样会被回填（等价 visible = true）。
 * </p>
 */
@ONodeAttr(creator = HookInput.HookInputCreator.class)
public interface HookInput {

	/**
	 * {@link HookInput} 的多态解码分派器。判别字段：{@code hook_event_name}。
	 * 映射表与原 Jackson {@code @JsonSubTypes} 逐项对齐。
	 */
	final class HookInputCreator implements ObjectCreator<HookInput> {

		@Override
		public HookInput create(Options opts, ONode node, Class<?> clazz) {
			if (node == null || !node.isObject()) {
				return null;
			}

			String eventName = node.get("hook_event_name").getString();
			if (eventName == null) {
				throw new CodecException("Missing 'hook_event_name' in hook input");
			}

			switch (eventName) {
				case "PreToolUse":
					return node.toBean(PreToolUseInput.class);
				case "PostToolUse":
					return node.toBean(PostToolUseInput.class);
				case "UserPromptSubmit":
					return node.toBean(UserPromptSubmitInput.class);
				case "Stop":
					return node.toBean(StopInput.class);
				case "SubagentStop":
					return node.toBean(SubagentStopInput.class);
				case "PreCompact":
					return node.toBean(PreCompactInput.class);
				default:
					throw new CodecException("Unknown hook_event_name: " + eventName);
			}
		}

	}

	/**
	 * Get the hook event name.
	 */
	String hookEventName();

	/**
	 * Get the session ID.
	 */
	String sessionId();

	/**
	 * Get the transcript path.
	 */
	String transcriptPath();

	/**
	 * Get the current working directory.
	 */
	String cwd();

	/**
	 * Get the permission mode (optional).
	 */
	Optional<String> permissionMode();

	/**
	 * Input for PreToolUse hook - called before a tool is executed.
	 */
	final class PreToolUseInput implements HookInput {

		@ONodeAttr(name = "hook_event_name")
		private final String hookEventName;

		@ONodeAttr(name = "session_id")
		private final String sessionId;

		@ONodeAttr(name = "transcript_path")
		private final String transcriptPath;

		@ONodeAttr(name = "cwd")
		private final String cwd;

		@ONodeAttr(name = "permission_mode")
		private final String permissionModeValue;

		@ONodeAttr(name = "tool_name")
		private final String toolName;

		@ONodeAttr(name = "tool_use_id")
		private final String toolUseId;

		@ONodeAttr(name = "tool_input")
		private final Map<String, Object> toolInput;

		public PreToolUseInput(@ONodeAttr(name = "hook_event_name") String hookEventName,
				@ONodeAttr(name = "session_id") String sessionId, @ONodeAttr(name = "transcript_path") String transcriptPath,
				@ONodeAttr(name = "cwd") String cwd, @ONodeAttr(name = "permission_mode") String permissionModeValue,
				@ONodeAttr(name = "tool_name") String toolName, @ONodeAttr(name = "tool_use_id") String toolUseId,
				@ONodeAttr(name = "tool_input") Map<String, Object> toolInput) {
			this.hookEventName = hookEventName;
			this.sessionId = sessionId;
			this.transcriptPath = transcriptPath;
			this.cwd = cwd;
			this.permissionModeValue = permissionModeValue;
			this.toolName = toolName;
			this.toolUseId = toolUseId;
			this.toolInput = toolInput;
		}

		@Override
		public String hookEventName() {
			return hookEventName;
		}

		@Override
		public String sessionId() {
			return sessionId;
		}

		@Override
		public String transcriptPath() {
			return transcriptPath;
		}

		@Override
		public String cwd() {
			return cwd;
		}

		public String permissionModeValue() {
			return permissionModeValue;
		}

		public String toolName() {
			return toolName;
		}

		public String toolUseId() {
			return toolUseId;
		}

		public Map<String, Object> toolInput() {
			return toolInput;
		}

		@Override
		public Optional<String> permissionMode() {
			return Optional.ofNullable(permissionModeValue);
		}

		/**
		 * Get a typed argument from tool input.
		 */
		@SuppressWarnings("unchecked")
		public <T> Optional<T> getArgument(String name, Class<T> type) {
			Object value = toolInput.get(name);
			if (value != null && type.isInstance(value)) {
				return Optional.of((T) value);
			}
			return Optional.empty();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PreToolUseInput)) {
				return false;
			}
			PreToolUseInput that = (PreToolUseInput) o;
			return Objects.equals(hookEventName, that.hookEventName) && Objects.equals(sessionId, that.sessionId)
					&& Objects.equals(transcriptPath, that.transcriptPath) && Objects.equals(cwd, that.cwd)
					&& Objects.equals(permissionModeValue, that.permissionModeValue)
					&& Objects.equals(toolName, that.toolName) && Objects.equals(toolUseId, that.toolUseId)
					&& Objects.equals(toolInput, that.toolInput);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookEventName, sessionId, transcriptPath, cwd, permissionModeValue, toolName,
					toolUseId, toolInput);
		}

		@Override
		public String toString() {
			return "PreToolUseInput[hookEventName=" + hookEventName + ", sessionId=" + sessionId
					+ ", transcriptPath=" + transcriptPath + ", cwd=" + cwd + ", permissionModeValue="
					+ permissionModeValue + ", toolName=" + toolName + ", toolUseId=" + toolUseId + ", toolInput="
					+ toolInput + "]";
		}
	}

	/**
	 * Input for PostToolUse hook - called after a tool is executed.
	 */
	final class PostToolUseInput implements HookInput {

		@ONodeAttr(name = "hook_event_name")
		private final String hookEventName;

		@ONodeAttr(name = "session_id")
		private final String sessionId;

		@ONodeAttr(name = "transcript_path")
		private final String transcriptPath;

		@ONodeAttr(name = "cwd")
		private final String cwd;

		@ONodeAttr(name = "permission_mode")
		private final String permissionModeValue;

		@ONodeAttr(name = "tool_name")
		private final String toolName;

		@ONodeAttr(name = "tool_use_id")
		private final String toolUseId;

		@ONodeAttr(name = "tool_input")
		private final Map<String, Object> toolInput;

		@ONodeAttr(name = "tool_response")
		private final Object toolResponse;

		public PostToolUseInput(@ONodeAttr(name = "hook_event_name") String hookEventName,
				@ONodeAttr(name = "session_id") String sessionId, @ONodeAttr(name = "transcript_path") String transcriptPath,
				@ONodeAttr(name = "cwd") String cwd, @ONodeAttr(name = "permission_mode") String permissionModeValue,
				@ONodeAttr(name = "tool_name") String toolName, @ONodeAttr(name = "tool_use_id") String toolUseId,
				@ONodeAttr(name = "tool_input") Map<String, Object> toolInput,
				@ONodeAttr(name = "tool_response") Object toolResponse) {
			this.hookEventName = hookEventName;
			this.sessionId = sessionId;
			this.transcriptPath = transcriptPath;
			this.cwd = cwd;
			this.permissionModeValue = permissionModeValue;
			this.toolName = toolName;
			this.toolUseId = toolUseId;
			this.toolInput = toolInput;
			this.toolResponse = toolResponse;
		}

		@Override
		public String hookEventName() {
			return hookEventName;
		}

		@Override
		public String sessionId() {
			return sessionId;
		}

		@Override
		public String transcriptPath() {
			return transcriptPath;
		}

		@Override
		public String cwd() {
			return cwd;
		}

		public String permissionModeValue() {
			return permissionModeValue;
		}

		public String toolName() {
			return toolName;
		}

		public String toolUseId() {
			return toolUseId;
		}

		public Map<String, Object> toolInput() {
			return toolInput;
		}

		public Object toolResponse() {
			return toolResponse;
		}

		@Override
		public Optional<String> permissionMode() {
			return Optional.ofNullable(permissionModeValue);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PostToolUseInput)) {
				return false;
			}
			PostToolUseInput that = (PostToolUseInput) o;
			return Objects.equals(hookEventName, that.hookEventName) && Objects.equals(sessionId, that.sessionId)
					&& Objects.equals(transcriptPath, that.transcriptPath) && Objects.equals(cwd, that.cwd)
					&& Objects.equals(permissionModeValue, that.permissionModeValue)
					&& Objects.equals(toolName, that.toolName) && Objects.equals(toolUseId, that.toolUseId)
					&& Objects.equals(toolInput, that.toolInput) && Objects.equals(toolResponse, that.toolResponse);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookEventName, sessionId, transcriptPath, cwd, permissionModeValue, toolName,
					toolUseId, toolInput, toolResponse);
		}

		@Override
		public String toString() {
			return "PostToolUseInput[hookEventName=" + hookEventName + ", sessionId=" + sessionId
					+ ", transcriptPath=" + transcriptPath + ", cwd=" + cwd + ", permissionModeValue="
					+ permissionModeValue + ", toolName=" + toolName + ", toolUseId=" + toolUseId + ", toolInput="
					+ toolInput + ", toolResponse=" + toolResponse + "]";
		}
	}

	/**
	 * Input for UserPromptSubmit hook - called before user prompt is sent.
	 */
	final class UserPromptSubmitInput implements HookInput {

		@ONodeAttr(name = "hook_event_name")
		private final String hookEventName;

		@ONodeAttr(name = "session_id")
		private final String sessionId;

		@ONodeAttr(name = "transcript_path")
		private final String transcriptPath;

		@ONodeAttr(name = "cwd")
		private final String cwd;

		@ONodeAttr(name = "permission_mode")
		private final String permissionModeValue;

		@ONodeAttr(name = "prompt")
		private final String prompt;

		public UserPromptSubmitInput(@ONodeAttr(name = "hook_event_name") String hookEventName,
				@ONodeAttr(name = "session_id") String sessionId, @ONodeAttr(name = "transcript_path") String transcriptPath,
				@ONodeAttr(name = "cwd") String cwd, @ONodeAttr(name = "permission_mode") String permissionModeValue,
				@ONodeAttr(name = "prompt") String prompt) {
			this.hookEventName = hookEventName;
			this.sessionId = sessionId;
			this.transcriptPath = transcriptPath;
			this.cwd = cwd;
			this.permissionModeValue = permissionModeValue;
			this.prompt = prompt;
		}

		@Override
		public String hookEventName() {
			return hookEventName;
		}

		@Override
		public String sessionId() {
			return sessionId;
		}

		@Override
		public String transcriptPath() {
			return transcriptPath;
		}

		@Override
		public String cwd() {
			return cwd;
		}

		public String permissionModeValue() {
			return permissionModeValue;
		}

		public String prompt() {
			return prompt;
		}

		@Override
		public Optional<String> permissionMode() {
			return Optional.ofNullable(permissionModeValue);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof UserPromptSubmitInput)) {
				return false;
			}
			UserPromptSubmitInput that = (UserPromptSubmitInput) o;
			return Objects.equals(hookEventName, that.hookEventName) && Objects.equals(sessionId, that.sessionId)
					&& Objects.equals(transcriptPath, that.transcriptPath) && Objects.equals(cwd, that.cwd)
					&& Objects.equals(permissionModeValue, that.permissionModeValue)
					&& Objects.equals(prompt, that.prompt);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookEventName, sessionId, transcriptPath, cwd, permissionModeValue, prompt);
		}

		@Override
		public String toString() {
			return "UserPromptSubmitInput[hookEventName=" + hookEventName + ", sessionId=" + sessionId
					+ ", transcriptPath=" + transcriptPath + ", cwd=" + cwd + ", permissionModeValue="
					+ permissionModeValue + ", prompt=" + prompt + "]";
		}
	}

	/**
	 * Input for Stop hook - called when agent stops.
	 */
	@ONodeAttr(creator = PrimitiveSafeCreator.class)
	final class StopInput implements HookInput {

		@ONodeAttr(name = "hook_event_name")
		private final String hookEventName;

		@ONodeAttr(name = "session_id")
		private final String sessionId;

		@ONodeAttr(name = "transcript_path")
		private final String transcriptPath;

		@ONodeAttr(name = "cwd")
		private final String cwd;

		@ONodeAttr(name = "permission_mode")
		private final String permissionModeValue;

		@ONodeAttr(name = "stop_hook_active")
		private final boolean stopHookActive;

		public StopInput(@ONodeAttr(name = "hook_event_name") String hookEventName,
				@ONodeAttr(name = "session_id") String sessionId, @ONodeAttr(name = "transcript_path") String transcriptPath,
				@ONodeAttr(name = "cwd") String cwd, @ONodeAttr(name = "permission_mode") String permissionModeValue,
				@ONodeAttr(name = "stop_hook_active") boolean stopHookActive) {
			this.hookEventName = hookEventName;
			this.sessionId = sessionId;
			this.transcriptPath = transcriptPath;
			this.cwd = cwd;
			this.permissionModeValue = permissionModeValue;
			this.stopHookActive = stopHookActive;
		}

		@Override
		public String hookEventName() {
			return hookEventName;
		}

		@Override
		public String sessionId() {
			return sessionId;
		}

		@Override
		public String transcriptPath() {
			return transcriptPath;
		}

		@Override
		public String cwd() {
			return cwd;
		}

		public String permissionModeValue() {
			return permissionModeValue;
		}

		public boolean stopHookActive() {
			return stopHookActive;
		}

		@Override
		public Optional<String> permissionMode() {
			return Optional.ofNullable(permissionModeValue);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof StopInput)) {
				return false;
			}
			StopInput that = (StopInput) o;
			return stopHookActive == that.stopHookActive && Objects.equals(hookEventName, that.hookEventName)
					&& Objects.equals(sessionId, that.sessionId)
					&& Objects.equals(transcriptPath, that.transcriptPath) && Objects.equals(cwd, that.cwd)
					&& Objects.equals(permissionModeValue, that.permissionModeValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookEventName, sessionId, transcriptPath, cwd, permissionModeValue, stopHookActive);
		}

		@Override
		public String toString() {
			return "StopInput[hookEventName=" + hookEventName + ", sessionId=" + sessionId + ", transcriptPath="
					+ transcriptPath + ", cwd=" + cwd + ", permissionModeValue=" + permissionModeValue
					+ ", stopHookActive=" + stopHookActive + "]";
		}
	}

	/**
	 * Input for SubagentStop hook - called when subagent stops.
	 */
	@ONodeAttr(creator = PrimitiveSafeCreator.class)
	final class SubagentStopInput implements HookInput {

		@ONodeAttr(name = "hook_event_name")
		private final String hookEventName;

		@ONodeAttr(name = "session_id")
		private final String sessionId;

		@ONodeAttr(name = "transcript_path")
		private final String transcriptPath;

		@ONodeAttr(name = "cwd")
		private final String cwd;

		@ONodeAttr(name = "permission_mode")
		private final String permissionModeValue;

		@ONodeAttr(name = "stop_hook_active")
		private final boolean stopHookActive;

		public SubagentStopInput(@ONodeAttr(name = "hook_event_name") String hookEventName,
				@ONodeAttr(name = "session_id") String sessionId, @ONodeAttr(name = "transcript_path") String transcriptPath,
				@ONodeAttr(name = "cwd") String cwd, @ONodeAttr(name = "permission_mode") String permissionModeValue,
				@ONodeAttr(name = "stop_hook_active") boolean stopHookActive) {
			this.hookEventName = hookEventName;
			this.sessionId = sessionId;
			this.transcriptPath = transcriptPath;
			this.cwd = cwd;
			this.permissionModeValue = permissionModeValue;
			this.stopHookActive = stopHookActive;
		}

		@Override
		public String hookEventName() {
			return hookEventName;
		}

		@Override
		public String sessionId() {
			return sessionId;
		}

		@Override
		public String transcriptPath() {
			return transcriptPath;
		}

		@Override
		public String cwd() {
			return cwd;
		}

		public String permissionModeValue() {
			return permissionModeValue;
		}

		public boolean stopHookActive() {
			return stopHookActive;
		}

		@Override
		public Optional<String> permissionMode() {
			return Optional.ofNullable(permissionModeValue);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SubagentStopInput)) {
				return false;
			}
			SubagentStopInput that = (SubagentStopInput) o;
			return stopHookActive == that.stopHookActive && Objects.equals(hookEventName, that.hookEventName)
					&& Objects.equals(sessionId, that.sessionId)
					&& Objects.equals(transcriptPath, that.transcriptPath) && Objects.equals(cwd, that.cwd)
					&& Objects.equals(permissionModeValue, that.permissionModeValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookEventName, sessionId, transcriptPath, cwd, permissionModeValue, stopHookActive);
		}

		@Override
		public String toString() {
			return "SubagentStopInput[hookEventName=" + hookEventName + ", sessionId=" + sessionId
					+ ", transcriptPath=" + transcriptPath + ", cwd=" + cwd + ", permissionModeValue="
					+ permissionModeValue + ", stopHookActive=" + stopHookActive + "]";
		}
	}

	/**
	 * Input for PreCompact hook - called before context compaction.
	 */
	final class PreCompactInput implements HookInput {

		@ONodeAttr(name = "hook_event_name")
		private final String hookEventName;

		@ONodeAttr(name = "session_id")
		private final String sessionId;

		@ONodeAttr(name = "transcript_path")
		private final String transcriptPath;

		@ONodeAttr(name = "cwd")
		private final String cwd;

		@ONodeAttr(name = "permission_mode")
		private final String permissionModeValue;

		@ONodeAttr(name = "trigger")
		private final String trigger;

		@ONodeAttr(name = "custom_instructions")
		private final String customInstructions;

		public PreCompactInput(@ONodeAttr(name = "hook_event_name") String hookEventName,
				@ONodeAttr(name = "session_id") String sessionId, @ONodeAttr(name = "transcript_path") String transcriptPath,
				@ONodeAttr(name = "cwd") String cwd, @ONodeAttr(name = "permission_mode") String permissionModeValue,
				@ONodeAttr(name = "trigger") String trigger,
				@ONodeAttr(name = "custom_instructions") String customInstructions) {
			this.hookEventName = hookEventName;
			this.sessionId = sessionId;
			this.transcriptPath = transcriptPath;
			this.cwd = cwd;
			this.permissionModeValue = permissionModeValue;
			this.trigger = trigger;
			this.customInstructions = customInstructions;
		}

		@Override
		public String hookEventName() {
			return hookEventName;
		}

		@Override
		public String sessionId() {
			return sessionId;
		}

		@Override
		public String transcriptPath() {
			return transcriptPath;
		}

		@Override
		public String cwd() {
			return cwd;
		}

		public String permissionModeValue() {
			return permissionModeValue;
		}

		public String trigger() {
			return trigger;
		}

		public String customInstructions() {
			return customInstructions;
		}

		@Override
		public Optional<String> permissionMode() {
			return Optional.ofNullable(permissionModeValue);
		}

		/**
		 * Check if this was a manual trigger.
		 */
		public boolean isManualTrigger() {
			return "manual".equals(trigger);
		}

		/**
		 * Check if this was an automatic trigger.
		 */
		public boolean isAutoTrigger() {
			return "auto".equals(trigger);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PreCompactInput)) {
				return false;
			}
			PreCompactInput that = (PreCompactInput) o;
			return Objects.equals(hookEventName, that.hookEventName) && Objects.equals(sessionId, that.sessionId)
					&& Objects.equals(transcriptPath, that.transcriptPath) && Objects.equals(cwd, that.cwd)
					&& Objects.equals(permissionModeValue, that.permissionModeValue)
					&& Objects.equals(trigger, that.trigger)
					&& Objects.equals(customInstructions, that.customInstructions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookEventName, sessionId, transcriptPath, cwd, permissionModeValue, trigger,
					customInstructions);
		}

		@Override
		public String toString() {
			return "PreCompactInput[hookEventName=" + hookEventName + ", sessionId=" + sessionId
					+ ", transcriptPath=" + transcriptPath + ", cwd=" + cwd + ", permissionModeValue="
					+ permissionModeValue + ", trigger=" + trigger + ", customInstructions=" + customInstructions
					+ "]";
		}
	}

}
