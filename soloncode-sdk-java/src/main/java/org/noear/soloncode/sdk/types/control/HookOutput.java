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

import org.noear.snack4.annotation.ONodeAttr;

import java.util.Map;
import java.util.Objects;

/**
 * Output from a hook execution. Sent back to CLI as part of control response.
 *
 * <p>
 * Field naming note: Java uses camelCase but the protocol uses snake_case.
 * snack4 @ONodeAttr(name = ...) handles the conversion. Additionally, "continue" and "async" are
 * Java keywords, so we use alternative names that get serialized correctly.
 */
public final class HookOutput {

	// Control fields - note: "continue" is a Java keyword, so we use a workaround
	@ONodeAttr(name = "continue")
	private final Boolean continueExecution;

	@ONodeAttr(name = "suppressOutput")
	private final Boolean suppressOutput;

	@ONodeAttr(name = "stopReason")
	private final String stopReason;

	// Decision fields
	@ONodeAttr(name = "decision")
	private final String decision;

	@ONodeAttr(name = "systemMessage")
	private final String systemMessage;

	@ONodeAttr(name = "reason")
	private final String reason;

	// Async support - note: "async" is a Java keyword in some contexts
	@ONodeAttr(name = "async")
	private final Boolean asyncExecution;

	@ONodeAttr(name = "asyncTimeout")
	private final Integer asyncTimeout;

	// Hook-specific output
	@ONodeAttr(name = "hookSpecificOutput")
	private final HookSpecificOutput hookSpecificOutput;

	public HookOutput(@ONodeAttr(name = "continue") Boolean continueExecution,
			@ONodeAttr(name = "suppressOutput") Boolean suppressOutput, @ONodeAttr(name = "stopReason") String stopReason,
			@ONodeAttr(name = "decision") String decision, @ONodeAttr(name = "systemMessage") String systemMessage,
			@ONodeAttr(name = "reason") String reason, @ONodeAttr(name = "async") Boolean asyncExecution,
			@ONodeAttr(name = "asyncTimeout") Integer asyncTimeout,
			@ONodeAttr(name = "hookSpecificOutput") HookSpecificOutput hookSpecificOutput) {
		this.continueExecution = continueExecution;
		this.suppressOutput = suppressOutput;
		this.stopReason = stopReason;
		this.decision = decision;
		this.systemMessage = systemMessage;
		this.reason = reason;
		this.asyncExecution = asyncExecution;
		this.asyncTimeout = asyncTimeout;
		this.hookSpecificOutput = hookSpecificOutput;
	}

	public Boolean continueExecution() {
		return continueExecution;
	}

	public Boolean suppressOutput() {
		return suppressOutput;
	}

	public String stopReason() {
		return stopReason;
	}

	public String decision() {
		return decision;
	}

	public String systemMessage() {
		return systemMessage;
	}

	public String reason() {
		return reason;
	}

	public Boolean asyncExecution() {
		return asyncExecution;
	}

	public Integer asyncTimeout() {
		return asyncTimeout;
	}

	public HookSpecificOutput hookSpecificOutput() {
		return hookSpecificOutput;
	}

	/**
	 * Create a simple "continue" output that allows execution to proceed.
	 */
	public static HookOutput allow() {
		return builder().continueExecution(true).build();
	}

	/**
	 * Create a "block" output with reason.
	 */
	public static HookOutput block(String reason) {
		return builder().continueExecution(false).decision("block").reason(reason).build();
	}

	/**
	 * Create an async hook output that defers execution.
	 * <p>
	 * Async hooks allow the hook to defer its decision, letting SolonCode continue while the
	 * hook processes in the background. This is useful for long-running validations or
	 * external service calls.
	 * @return an async hook output with default timeout
	 */
	public static HookOutput async() {
		return builder().asyncExecution(true).continueExecution(true).build();
	}

	/**
	 * Create an async hook output that defers execution with a specified timeout.
	 * @param timeoutMs timeout in milliseconds for the async operation
	 * @return an async hook output with the specified timeout
	 */
	public static HookOutput async(int timeoutMs) {
		return builder().asyncExecution(true).asyncTimeout(timeoutMs).continueExecution(true).build();
	}

	/**
	 * Create builder for fluent construction.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for HookOutput.
	 */
	public static class Builder {

		private Boolean continueExecution;

		private Boolean suppressOutput;

		private String stopReason;

		private String decision;

		private String systemMessage;

		private String reason;

		private Boolean asyncExecution;

		private Integer asyncTimeout;

		private HookSpecificOutput hookSpecificOutput;

		public Builder continueExecution(Boolean continueExecution) {
			this.continueExecution = continueExecution;
			return this;
		}

		public Builder suppressOutput(Boolean suppressOutput) {
			this.suppressOutput = suppressOutput;
			return this;
		}

		public Builder stopReason(String stopReason) {
			this.stopReason = stopReason;
			return this;
		}

		public Builder decision(String decision) {
			this.decision = decision;
			return this;
		}

		public Builder systemMessage(String systemMessage) {
			this.systemMessage = systemMessage;
			return this;
		}

		public Builder reason(String reason) {
			this.reason = reason;
			return this;
		}

		public Builder asyncExecution(Boolean asyncExecution) {
			this.asyncExecution = asyncExecution;
			return this;
		}

		public Builder asyncTimeout(Integer asyncTimeout) {
			this.asyncTimeout = asyncTimeout;
			return this;
		}

		public Builder hookSpecificOutput(HookSpecificOutput hookSpecificOutput) {
			this.hookSpecificOutput = hookSpecificOutput;
			return this;
		}

		public HookOutput build() {
			return new HookOutput(continueExecution, suppressOutput, stopReason, decision, systemMessage, reason,
					asyncExecution, asyncTimeout, hookSpecificOutput);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HookOutput)) {
			return false;
		}
		HookOutput that = (HookOutput) o;
		return Objects.equals(continueExecution, that.continueExecution)
				&& Objects.equals(suppressOutput, that.suppressOutput)
				&& Objects.equals(stopReason, that.stopReason) && Objects.equals(decision, that.decision)
				&& Objects.equals(systemMessage, that.systemMessage) && Objects.equals(reason, that.reason)
				&& Objects.equals(asyncExecution, that.asyncExecution)
				&& Objects.equals(asyncTimeout, that.asyncTimeout)
				&& Objects.equals(hookSpecificOutput, that.hookSpecificOutput);
	}

	@Override
	public int hashCode() {
		return Objects.hash(continueExecution, suppressOutput, stopReason, decision, systemMessage, reason,
				asyncExecution, asyncTimeout, hookSpecificOutput);
	}

	@Override
	public String toString() {
		return "HookOutput[continueExecution=" + continueExecution + ", suppressOutput=" + suppressOutput
				+ ", stopReason=" + stopReason + ", decision=" + decision + ", systemMessage=" + systemMessage
				+ ", reason=" + reason + ", asyncExecution=" + asyncExecution + ", asyncTimeout=" + asyncTimeout
				+ ", hookSpecificOutput=" + hookSpecificOutput + "]";
	}

	/**
	 * Hook-specific output - varies by hook type.
	 */
	public static final class HookSpecificOutput {

		@ONodeAttr(name = "hookEventName")
		private final String hookEventName;

		// PreToolUse specific
		@ONodeAttr(name = "permissionDecision")
		private final String permissionDecision;

		@ONodeAttr(name = "permissionDecisionReason")
		private final String permissionDecisionReason;

		@ONodeAttr(name = "updatedInput")
		private final Map<String, Object> updatedInput;

		// PostToolUse / UserPromptSubmit specific
		@ONodeAttr(name = "additionalContext")
		private final String additionalContext;

		public HookSpecificOutput(@ONodeAttr(name = "hookEventName") String hookEventName,
				@ONodeAttr(name = "permissionDecision") String permissionDecision,
				@ONodeAttr(name = "permissionDecisionReason") String permissionDecisionReason,
				@ONodeAttr(name = "updatedInput") Map<String, Object> updatedInput,
				@ONodeAttr(name = "additionalContext") String additionalContext) {
			this.hookEventName = hookEventName;
			this.permissionDecision = permissionDecision;
			this.permissionDecisionReason = permissionDecisionReason;
			this.updatedInput = updatedInput;
			this.additionalContext = additionalContext;
		}

		public String hookEventName() {
			return hookEventName;
		}

		public String permissionDecision() {
			return permissionDecision;
		}

		public String permissionDecisionReason() {
			return permissionDecisionReason;
		}

		public Map<String, Object> updatedInput() {
			return updatedInput;
		}

		public String additionalContext() {
			return additionalContext;
		}

		/**
		 * Create PreToolUse output that allows execution.
		 */
		public static HookSpecificOutput preToolUseAllow() {
			return preToolUseAllow(null);
		}

		/**
		 * Create PreToolUse output that allows execution with reason.
		 */
		public static HookSpecificOutput preToolUseAllow(String reason) {
			return new HookSpecificOutput("PreToolUse", "allow", reason, null, null);
		}

		/**
		 * Create PreToolUse output that denies execution.
		 */
		public static HookSpecificOutput preToolUseDeny(String reason) {
			return new HookSpecificOutput("PreToolUse", "deny", reason, null, null);
		}

		/**
		 * Create PreToolUse output that asks for user permission.
		 */
		public static HookSpecificOutput preToolUseAsk() {
			return new HookSpecificOutput("PreToolUse", "ask", null, null, null);
		}

		/**
		 * Create PreToolUse output that modifies the input.
		 */
		public static HookSpecificOutput preToolUseModify(Map<String, Object> updatedInput) {
			return new HookSpecificOutput("PreToolUse", null, null, updatedInput, null);
		}

		/**
		 * Create PostToolUse output with additional context.
		 */
		public static HookSpecificOutput postToolUse(String additionalContext) {
			return new HookSpecificOutput("PostToolUse", null, null, null, additionalContext);
		}

		/**
		 * Create UserPromptSubmit output with additional context.
		 */
		public static HookSpecificOutput userPromptSubmit(String additionalContext) {
			return new HookSpecificOutput("UserPromptSubmit", null, null, null, additionalContext);
		}

		/**
		 * Builder for HookSpecificOutput.
		 */
		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String hookEventName;

			private String permissionDecision;

			private String permissionDecisionReason;

			private Map<String, Object> updatedInput;

			private String additionalContext;

			public Builder hookEventName(String hookEventName) {
				this.hookEventName = hookEventName;
				return this;
			}

			public Builder permissionDecision(String permissionDecision) {
				this.permissionDecision = permissionDecision;
				return this;
			}

			public Builder permissionDecisionReason(String permissionDecisionReason) {
				this.permissionDecisionReason = permissionDecisionReason;
				return this;
			}

			public Builder updatedInput(Map<String, Object> updatedInput) {
				this.updatedInput = updatedInput;
				return this;
			}

			public Builder additionalContext(String additionalContext) {
				this.additionalContext = additionalContext;
				return this;
			}

			public HookSpecificOutput build() {
				return new HookSpecificOutput(hookEventName, permissionDecision, permissionDecisionReason, updatedInput,
						additionalContext);
			}

		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof HookSpecificOutput)) {
				return false;
			}
			HookSpecificOutput that = (HookSpecificOutput) o;
			return Objects.equals(hookEventName, that.hookEventName)
					&& Objects.equals(permissionDecision, that.permissionDecision)
					&& Objects.equals(permissionDecisionReason, that.permissionDecisionReason)
					&& Objects.equals(updatedInput, that.updatedInput)
					&& Objects.equals(additionalContext, that.additionalContext);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookEventName, permissionDecision, permissionDecisionReason, updatedInput,
					additionalContext);
		}

		@Override
		public String toString() {
			return "HookSpecificOutput[hookEventName=" + hookEventName + ", permissionDecision=" + permissionDecision
					+ ", permissionDecisionReason=" + permissionDecisionReason + ", updatedInput=" + updatedInput
					+ ", additionalContext=" + additionalContext + "]";
		}
	}
}
