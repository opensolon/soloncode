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

package org.noear.soloncode.sdk.transport;

import org.noear.soloncode.sdk.util.SdkCollections;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Callback interface for dynamic tool permission decisions. This is invoked when SolonCode
 * wants to use a tool and needs permission validation.
 *
 * <p>
 * The callback receives the tool name, input parameters, and any permission suggestions
 * from the CLI, and returns a decision to allow, deny, or modify the tool use.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>
 * {@code
 * // Allow all tools
 * ToolPermissionCallback callback = ToolPermissionCallback.allowAll();
 *
 * // Read-only mode - only allow specific tools
 * ToolPermissionCallback readOnly = ToolPermissionCallback.allowList(
 *     new java.util.HashSet<String>(java.util.Arrays.asList("Read", "Glob", "Grep", "WebFetch")));
 *
 * // Custom logic
 * ToolPermissionCallback custom = (tool, input, ctx) -> {
 *     if ("Bash".equals(tool) && input.get("command").toString().contains("rm")) {
 *         return CompletableFuture.completedFuture(
 *             ToolPermissionResult.deny("Destructive commands not allowed"));
 *     }
 *     return CompletableFuture.completedFuture(ToolPermissionResult.allow());
 * };
 * }
 * </pre>
 *
 * @see ToolPermissionResult
 * @see ToolPermissionContext
 */
@FunctionalInterface
public interface ToolPermissionCallback {

	/**
	 * Evaluate whether a tool can be used with the given input.
	 * @param toolName the name of the tool being invoked (e.g., "Bash", "Read", "Write")
	 * @param input the tool input parameters as a map
	 * @param context additional context including permission suggestions
	 * @return a future completing with the permission result
	 */
	CompletableFuture<ToolPermissionResult> canUseTool(String toolName, Map<String, Object> input,
			ToolPermissionContext context);

	/**
	 * Creates a callback that allows all tool uses.
	 * @return a callback that always allows
	 */
	static ToolPermissionCallback allowAll() {
		return (tool, input, ctx) -> CompletableFuture.completedFuture(ToolPermissionResult.allow());
	}

	/**
	 * Creates a callback that only allows tools in the specified set.
	 * @param allowedTools set of allowed tool names
	 * @return a callback that allows only listed tools
	 */
	static ToolPermissionCallback allowList(Set<String> allowedTools) {
		return (tool, input, ctx) -> {
			if (allowedTools.contains(tool)) {
				return CompletableFuture.completedFuture(ToolPermissionResult.allow());
			}
			return CompletableFuture.completedFuture(ToolPermissionResult.deny("Tool not in allowed list: " + tool));
		};
	}

	/**
	 * Creates a callback that denies tools in the specified set.
	 * @param deniedTools set of denied tool names
	 * @return a callback that denies listed tools
	 */
	static ToolPermissionCallback denyList(Set<String> deniedTools) {
		return (tool, input, ctx) -> {
			if (deniedTools.contains(tool)) {
				return CompletableFuture.completedFuture(ToolPermissionResult.deny("Tool is denied: " + tool));
			}
			return CompletableFuture.completedFuture(ToolPermissionResult.allow());
		};
	}

	/**
	 * Context provided to the permission callback with additional information.
	 *
	 * @param permissionSuggestions suggestions from the CLI for permission rules
	 * @param blockedPath path that was blocked (for file access tools)
	 */
	final class ToolPermissionContext {
		private final List<Map<String, Object>> permissionSuggestions;
		private final String blockedPath;

		public ToolPermissionContext(List<Map<String, Object>> permissionSuggestions, String blockedPath) {
			this.permissionSuggestions = permissionSuggestions;
			this.blockedPath = blockedPath;
		}

		public List<Map<String, Object>> permissionSuggestions() {
			return permissionSuggestions;
		}

		public String blockedPath() {
			return blockedPath;
		}

		public static ToolPermissionContext empty() {
			return new ToolPermissionContext(SdkCollections.list(), null);
		}

		public static ToolPermissionContext of(List<Map<String, Object>> suggestions, String blockedPath) {
			return new ToolPermissionContext(suggestions != null ? suggestions : SdkCollections.list(), blockedPath);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ToolPermissionContext)) {
				return false;
			}
			ToolPermissionContext other = (ToolPermissionContext) o;
			return Objects.equals(permissionSuggestions, other.permissionSuggestions)
					&& Objects.equals(blockedPath, other.blockedPath);
		}

		@Override
		public int hashCode() {
			return Objects.hash(permissionSuggestions, blockedPath);
		}

		@Override
		public String toString() {
			return "ToolPermissionContext[permissionSuggestions=" + permissionSuggestions + ", blockedPath="
					+ blockedPath + "]";
		}
	}

	/**
	 * Result of a tool permission check.
	 */
	interface ToolPermissionResult {

		/**
		 * Permission granted, optionally with modified input.
		 */
		final class Allow implements ToolPermissionResult {
			private final Map<String, Object> updatedInput;

			public Allow(Map<String, Object> updatedInput) {
				this.updatedInput = updatedInput;
			}

			public Allow() {
				this(null);
			}

			public Map<String, Object> updatedInput() {
				return updatedInput;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof Allow)) {
					return false;
				}
				Allow other = (Allow) o;
				return Objects.equals(updatedInput, other.updatedInput);
			}

			@Override
			public int hashCode() {
				return Objects.hash(updatedInput);
			}

			@Override
			public String toString() {
				return "Allow[updatedInput=" + updatedInput + "]";
			}
		}

		/**
		 * Permission denied with reason.
		 */
		final class Deny implements ToolPermissionResult {
			private final String reason;
			private final boolean interrupt;

			public Deny(String reason, boolean interrupt) {
				this.reason = reason;
				this.interrupt = interrupt;
			}

			public Deny(String reason) {
				this(reason, false);
			}

			public String reason() {
				return reason;
			}

			public boolean interrupt() {
				return interrupt;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof Deny)) {
					return false;
				}
				Deny other = (Deny) o;
				return interrupt == other.interrupt && Objects.equals(reason, other.reason);
			}

			@Override
			public int hashCode() {
				return Objects.hash(reason, interrupt);
			}

			@Override
			public String toString() {
				return "Deny[reason=" + reason + ", interrupt=" + interrupt + "]";
			}
		}

		/**
		 * Create an allow result.
		 */
		static ToolPermissionResult allow() {
			return new Allow();
		}

		/**
		 * Create an allow result with modified input.
		 */
		static ToolPermissionResult allowWithModifiedInput(Map<String, Object> updatedInput) {
			return new Allow(updatedInput);
		}

		/**
		 * Create a deny result with reason.
		 */
		static ToolPermissionResult deny(String reason) {
			return new Deny(reason);
		}

		/**
		 * Create a deny result that also interrupts the session.
		 */
		static ToolPermissionResult denyAndInterrupt(String reason) {
			return new Deny(reason, true);
		}

	}

}
