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
import java.util.Objects;

/**
 * User message. Corresponds to UserMessage dataclass in Python SDK.
 */
public final class UserMessage implements Message {

	@ONodeAttr(name = "content")
	private final Object content; // Can be String or List<ContentBlock>

	public UserMessage(@ONodeAttr(name = "content") Object content) {
		this.content = content;
	}

	public Object content() {
		return content;
	}

	@Override
	public String getType() {
		return "user";
	}

	@Override
	public String toString() {
		if (content instanceof String) {
			String s = (String) content;
			return s;
		}
		return content != null ? content.toString() : "";
	}

	/**
	 * Returns content as a string if it's a string, null otherwise.
	 * @return the content as a string or null
	 */
	public String getContentAsString() {
		return content instanceof String ? (String) content : null;
	}

	/**
	 * Returns content as a list of content blocks if it's a list, null otherwise.
	 * @return the content as a list of blocks or null
	 */
	@SuppressWarnings("unchecked")
	public List<ContentBlock> getContentAsBlocks() {
		return content instanceof List ? (List<ContentBlock>) content : null;
	}

	/**
	 * Factory method to create a UserMessage from string content.
	 * @param content the string content
	 * @return new UserMessage instance
	 */
	public static UserMessage of(String content) {
		return new UserMessage(content);
	}

	/**
	 * Factory method to create a UserMessage from content blocks.
	 * @param content the content blocks
	 * @return new UserMessage instance
	 */
	public static UserMessage of(List<ContentBlock> content) {
		return new UserMessage(content);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof UserMessage)) {
			return false;
		}
		UserMessage that = (UserMessage) o;
		return Objects.equals(content, that.content);
	}

	@Override
	public int hashCode() {
		return Objects.hash(content);
	}
}
