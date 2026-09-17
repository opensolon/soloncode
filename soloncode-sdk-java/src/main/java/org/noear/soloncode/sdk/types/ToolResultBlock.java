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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tool result content block. Corresponds to ToolResultBlock dataclass in Python SDK.
 */
public final class ToolResultBlock implements ContentBlock {

	@ONodeAttr(name = "tool_use_id")
	private final String toolUseId;

	@ONodeAttr(name = "content")
	private final Object content; // Can be String, List<Map<String, Object>>, or null

	@ONodeAttr(name = "is_error")
	private final Boolean isError;

	public ToolResultBlock(@ONodeAttr(name = "tool_use_id") String toolUseId, @ONodeAttr(name = "content") Object content,
			@ONodeAttr(name = "is_error") Boolean isError) {
		this.toolUseId = toolUseId;
		this.content = content;
		this.isError = isError;
	}

	public String toolUseId() {
		return toolUseId;
	}

	public Object content() {
		return content;
	}

	public Boolean isError() {
		return isError;
	}

	@Override
	public String getType() {
		return "tool_result";
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns content as a string if it's a string, null otherwise
	 */
	public String getContentAsString() {
		return content instanceof String ? (String) content : null;
	}

	/**
	 * Returns content as a list if it's a list, null otherwise
	 */
	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> getContentAsList() {
		return content instanceof List ? (List<Map<String, Object>>) content : null;
	}

	public static class Builder {

		private String toolUseId;

		private Object content;

		private Boolean isError;

		public Builder toolUseId(String toolUseId) {
			this.toolUseId = toolUseId;
			return this;
		}

		public Builder content(String content) {
			this.content = content;
			return this;
		}

		public Builder content(List<Map<String, Object>> content) {
			this.content = content;
			return this;
		}

		public Builder isError(Boolean isError) {
			this.isError = isError;
			return this;
		}

		public ToolResultBlock build() {
			return new ToolResultBlock(toolUseId, content, isError);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ToolResultBlock)) {
			return false;
		}
		ToolResultBlock that = (ToolResultBlock) o;
		return Objects.equals(toolUseId, that.toolUseId) && Objects.equals(content, that.content)
				&& Objects.equals(isError, that.isError);
	}

	@Override
	public int hashCode() {
		return Objects.hash(toolUseId, content, isError);
	}

	@Override
	public String toString() {
		return "ToolResultBlock[toolUseId=" + toolUseId + ", content=" + content + ", isError=" + isError + "]";
	}
}
