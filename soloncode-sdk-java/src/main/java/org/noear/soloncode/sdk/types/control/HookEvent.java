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

/**
 * Hook event types supported by the SolonCode CLI control protocol.
 */
public enum HookEvent {

	/**
	 * Called before a tool is executed.
	 */
	PRE_TOOL_USE("PreToolUse"),

	/**
	 * Called after a tool is executed.
	 */
	POST_TOOL_USE("PostToolUse"),

	/**
	 * Called before a user prompt is submitted.
	 */
	USER_PROMPT_SUBMIT("UserPromptSubmit"),

	/**
	 * Called when the agent stops.
	 */
	STOP("Stop"),

	/**
	 * Called when a subagent stops.
	 */
	SUBAGENT_STOP("SubagentStop"),

	/**
	 * Called before context compaction.
	 */
	PRE_COMPACT("PreCompact");

	private final String protocolName;

	HookEvent(String protocolName) {
		this.protocolName = protocolName;
	}

	/**
	 * Get the protocol name used in JSON communication.
	 */
	public String getProtocolName() {
		return protocolName;
	}

	/**
	 * Parse a hook event from its protocol name.
	 */
	public static HookEvent fromProtocolName(String name) {
		for (HookEvent event : values()) {
			if (event.protocolName.equals(name)) {
				return event;
			}
		}
		throw new IllegalArgumentException("Unknown hook event: " + name);
	}

}
