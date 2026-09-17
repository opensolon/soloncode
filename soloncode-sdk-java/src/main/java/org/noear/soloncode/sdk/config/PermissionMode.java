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

/**
 * Permission modes for SolonCode Code tool usage. Corresponds to PermissionMode in Python
 * SDK.
 */
public enum PermissionMode {

	/**
	 * Default permission mode - prompt for tool usage permissions.
	 */
	DEFAULT("default"),

	/**
	 * Automatically accept edit permissions without prompting.
	 */
	ACCEPT_EDITS("acceptEdits"),

	/**
	 * CI-recommended mode: same as default, explicit no-ask semantics.
	 */
	DONT_ASK("dontAsk"),

	/**
	 * Plan-only mode: analysis and proposals, no write tools.
	 */
	PLAN("plan"),

	/**
	 * Bypass all permission checks (use with caution).
	 */
	BYPASS_PERMISSIONS("bypassPermissions"),

	/**
	 * Dangerously skip all permission checks. Recommended only for sandboxes with no
	 * internet access.
	 */
	DANGEROUSLY_SKIP_PERMISSIONS("dangerously-skip-permissions");

	private final String value;

	PermissionMode(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	/**
	 * Creates PermissionMode from string value.
	 */
	public static PermissionMode fromValue(String value) {
		for (PermissionMode mode : values()) {
			if (mode.value.equals(value)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unknown permission mode: " + value);
	}

	@Override
	public String toString() {
		return value;
	}

}