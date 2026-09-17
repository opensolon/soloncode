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

package org.noear.soloncode.sdk.hooks;

import org.noear.soloncode.sdk.types.control.HookEvent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A registered hook with its event type, pattern matcher, callback, and configuration.
 */
public final class HookRegistration {

	/**
	 * Default timeout in seconds.
	 */
	public static final int DEFAULT_TIMEOUT = 60;

	private final String id;

	private final HookEvent event;

	private final Pattern toolPattern;

	private final HookCallback callback;

	private final int timeout;

	public HookRegistration(String id, HookEvent event, Pattern toolPattern, HookCallback callback, int timeout) {
		if (id == null || id.trim().isEmpty()) {
			throw new IllegalArgumentException("id must not be null or blank");
		}
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		if (callback == null) {
			throw new IllegalArgumentException("callback must not be null");
		}
		if (timeout <= 0) {
			timeout = DEFAULT_TIMEOUT;
		}
		this.id = id;
		this.event = event;
		this.toolPattern = toolPattern;
		this.callback = callback;
		this.timeout = timeout;
	}

	public String id() {
		return id;
	}

	public HookEvent event() {
		return event;
	}

	public Pattern toolPattern() {
		return toolPattern;
	}

	public HookCallback callback() {
		return callback;
	}

	public int timeout() {
		return timeout;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HookRegistration)) {
			return false;
		}
		HookRegistration that = (HookRegistration) o;
		return timeout == that.timeout && Objects.equals(id, that.id) && event == that.event
				&& Objects.equals(toolPattern, that.toolPattern) && Objects.equals(callback, that.callback);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, event, toolPattern, callback, timeout);
	}

	@Override
	public String toString() {
		return "HookRegistration[id=" + id + ", event=" + event + ", toolPattern=" + toolPattern + ", callback="
				+ callback + ", timeout=" + timeout + "]";
	}

	/**
	 * Creates a registration with default timeout.
	 */
	public HookRegistration(String id, HookEvent event, Pattern toolPattern, HookCallback callback) {
		this(id, event, toolPattern, callback, DEFAULT_TIMEOUT);
	}

	/**
	 * Creates a registration for all tools (null pattern matches everything).
	 */
	public HookRegistration(String id, HookEvent event, HookCallback callback) {
		this(id, event, null, callback, DEFAULT_TIMEOUT);
	}

	/**
	 * Checks if this hook matches the given tool name.
	 * @param toolName the tool name to check
	 * @return true if matches (null pattern matches all)
	 */
	public boolean matchesTool(String toolName) {
		if (toolPattern == null) {
			return true; // Match all tools
		}
		if (toolName == null) {
			return false;
		}
		return toolPattern.matcher(toolName).matches();
	}

	/**
	 * Gets the pattern as a string for serialization.
	 * @return pattern string or null if matches all
	 */
	public String getPatternString() {
		return toolPattern != null ? toolPattern.pattern() : null;
	}

	/**
	 * Builder for creating hook registrations.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates a PreToolUse hook for specific tools.
	 */
	public static HookRegistration preToolUse(String id, String toolPattern, HookCallback callback) {
		Pattern pattern = toolPattern != null ? Pattern.compile(toolPattern) : null;
		return new HookRegistration(id, HookEvent.PRE_TOOL_USE, pattern, callback);
	}

	/**
	 * Creates a PreToolUse hook for all tools.
	 */
	public static HookRegistration preToolUse(String id, HookCallback callback) {
		return new HookRegistration(id, HookEvent.PRE_TOOL_USE, callback);
	}

	/**
	 * Creates a PostToolUse hook for specific tools.
	 */
	public static HookRegistration postToolUse(String id, String toolPattern, HookCallback callback) {
		Pattern pattern = toolPattern != null ? Pattern.compile(toolPattern) : null;
		return new HookRegistration(id, HookEvent.POST_TOOL_USE, pattern, callback);
	}

	/**
	 * Creates a PostToolUse hook for all tools.
	 */
	public static HookRegistration postToolUse(String id, HookCallback callback) {
		return new HookRegistration(id, HookEvent.POST_TOOL_USE, callback);
	}

	/**
	 * Creates a UserPromptSubmit hook.
	 */
	public static HookRegistration userPromptSubmit(String id, HookCallback callback) {
		return new HookRegistration(id, HookEvent.USER_PROMPT_SUBMIT, callback);
	}

	/**
	 * Creates a Stop hook.
	 */
	public static HookRegistration stop(String id, HookCallback callback) {
		return new HookRegistration(id, HookEvent.STOP, callback);
	}

	public static class Builder {

		private String id;

		private HookEvent event;

		private Pattern toolPattern;

		private HookCallback callback;

		private int timeout = DEFAULT_TIMEOUT;

		public Builder id(String id) {
			this.id = id;
			return this;
		}

		public Builder event(HookEvent event) {
			this.event = event;
			return this;
		}

		public Builder toolPattern(String pattern) {
			this.toolPattern = pattern != null ? Pattern.compile(pattern) : null;
			return this;
		}

		public Builder toolPattern(Pattern pattern) {
			this.toolPattern = pattern;
			return this;
		}

		public Builder callback(HookCallback callback) {
			this.callback = callback;
			return this;
		}

		public Builder timeout(int timeout) {
			this.timeout = timeout;
			return this;
		}

		public HookRegistration build() {
			return new HookRegistration(id, event, toolPattern, callback, timeout);
		}

	}

}
