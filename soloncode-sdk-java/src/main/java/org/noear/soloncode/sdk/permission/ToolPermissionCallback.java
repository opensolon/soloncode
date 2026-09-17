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

package org.noear.soloncode.sdk.permission;

import java.util.Map;

/**
 * Callback for handling tool permission requests from SolonCode CLI. When SolonCode attempts to
 * use a tool, this callback is invoked to determine whether the tool should be allowed
 * and optionally modify the tool's input.
 *
 * <p>
 * This follows the Python SDK's {@code can_use_tool} callback pattern.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * ToolPermissionCallback callback = (toolName, input, context) -> {
 *     // Block dangerous operations
 *     if (toolName.equals("Bash") && input.get("command").toString().contains("rm -rf")) {
 *         return PermissionResult.deny("Dangerous command blocked");
 *     }
 *
 *     // Add safety flags to file operations
 *     if (toolName.equals("Write")) {
 *         Map<String, Object> safeInput = new HashMap<>(input);
 *         safeInput.put("backup", true);
 *         return PermissionResult.allow(safeInput);
 *     }
 *
 *     // Allow everything else
 *     return PermissionResult.allow();
 * };
 *
 * session.setToolPermissionCallback(callback);
 * }</pre>
 *
 * @see PermissionResult
 * @see ToolPermissionContext
 */
@FunctionalInterface
public interface ToolPermissionCallback {

	/**
	 * Called when SolonCode attempts to use a tool. The callback should return a
	 * {@link PermissionResult} indicating whether the tool should be allowed and
	 * optionally providing modified input.
	 * @param toolName the name of the tool being used (e.g., "Write", "Bash", "Read")
	 * @param input the tool's input parameters as a map
	 * @param context additional context about the permission request
	 * @return permission result indicating allow/deny and optional input modifications
	 */
	PermissionResult checkPermission(String toolName, Map<String, Object> input, ToolPermissionContext context);

	/**
	 * Returns a callback that allows all tool usage without modification.
	 * @return callback that always returns {@code PermissionResult.allow()}
	 */
	static ToolPermissionCallback allowAll() {
		return (toolName, input, context) -> PermissionResult.allow();
	}

	/**
	 * Returns a callback that denies all tool usage.
	 * @return callback that always returns {@code PermissionResult.deny()}
	 */
	static ToolPermissionCallback denyAll() {
		return (toolName, input, context) -> PermissionResult.deny("All tools denied by policy");
	}

}
