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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.noear.soloncode.sdk.util.SdkCollections;

/**
 * Context information provided to {@link ToolPermissionCallback} when evaluating tool
 * permissions.
 *
 * <p>
 * This record contains additional metadata from the CLI's permission request that may be
 * useful for making permission decisions.
 * </p>
 *
 * @param permissionSuggestions suggested permissions from CLI
 * @param blockedPath path that triggered a permission check (for file operations)
 * @param requestId the unique identifier for this permission request
 */
public final class ToolPermissionContext {

	private final List<Map<String, Object>> permissionSuggestions;

	private final String blockedPath;

	private final String requestId;

	public ToolPermissionContext(List<Map<String, Object>> permissionSuggestions, String blockedPath,
			String requestId) {
		this.permissionSuggestions = permissionSuggestions;
		this.blockedPath = blockedPath;
		this.requestId = requestId;
	}

	public List<Map<String, Object>> permissionSuggestions() {
		return permissionSuggestions;
	}

	public String blockedPath() {
		return blockedPath;
	}

	public String requestId() {
		return requestId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ToolPermissionContext)) {
			return false;
		}
		ToolPermissionContext that = (ToolPermissionContext) o;
		return Objects.equals(permissionSuggestions, that.permissionSuggestions)
				&& Objects.equals(blockedPath, that.blockedPath) && Objects.equals(requestId, that.requestId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(permissionSuggestions, blockedPath, requestId);
	}

	@Override
	public String toString() {
		return "ToolPermissionContext[permissionSuggestions=" + permissionSuggestions + ", blockedPath=" + blockedPath
				+ ", requestId=" + requestId + "]";
	}

	/**
	 * Creates a context with all fields.
	 */
	public static ToolPermissionContext of(List<Map<String, Object>> permissionSuggestions, String blockedPath,
			String requestId) {
		return new ToolPermissionContext(permissionSuggestions, blockedPath, requestId);
	}

	/**
	 * Creates an empty context.
	 */
	public static ToolPermissionContext empty() {
		return new ToolPermissionContext(SdkCollections.list(), null, null);
	}

	/**
	 * Returns true if there's a blocked path in this context.
	 */
	public boolean hasBlockedPath() {
		return blockedPath != null && !blockedPath.isEmpty();
	}

	/**
	 * Returns true if there are permission suggestions.
	 */
	public boolean hasPermissionSuggestions() {
		return permissionSuggestions != null && !permissionSuggestions.isEmpty();
	}

}
