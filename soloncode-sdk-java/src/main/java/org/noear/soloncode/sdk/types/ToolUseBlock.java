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

package org.noear.soloncode.sdk.types;

import org.noear.snack4.annotation.ONodeAttr;

import java.util.Map;
import java.util.Objects;

/**
 * Tool use content block. Corresponds to ToolUseBlock dataclass in Python SDK.
 */
public final class ToolUseBlock implements ContentBlock {

	@ONodeAttr(name = "id")
	private final String id;

	@ONodeAttr(name = "name")
	private final String name;

	@ONodeAttr(name = "input")
	private final Map<String, Object> input;

	public ToolUseBlock(@ONodeAttr(name = "id") String id, @ONodeAttr(name = "name") String name,
			@ONodeAttr(name = "input") Map<String, Object> input) {
		this.id = id;
		this.name = name;
		this.input = input;
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public Map<String, Object> input() {
		return input;
	}

	@Override
	public String getType() {
		return "tool_use";
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String id;

		private String name;

		private Map<String, Object> input;

		public Builder id(String id) {
			this.id = id;
			return this;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder input(Map<String, Object> input) {
			this.input = input;
			return this;
		}

		public ToolUseBlock build() {
			return new ToolUseBlock(id, name, input);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ToolUseBlock)) {
			return false;
		}
		ToolUseBlock that = (ToolUseBlock) o;
		return Objects.equals(id, that.id) && Objects.equals(name, that.name) && Objects.equals(input, that.input);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, name, input);
	}

	@Override
	public String toString() {
		return "ToolUseBlock[id=" + id + ", name=" + name + ", input=" + input + "]";
	}
}
