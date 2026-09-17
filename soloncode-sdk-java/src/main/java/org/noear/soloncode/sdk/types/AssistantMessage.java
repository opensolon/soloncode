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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Assistant message with content blocks. Corresponds to AssistantMessage dataclass in
 * Python SDK.
 */
public final class AssistantMessage implements Message {

	@ONodeAttr(name = "content")
	private final List<ContentBlock> content;

	public AssistantMessage(@ONodeAttr(name = "content") List<ContentBlock> content) {
		this.content = content;
	}

	public List<ContentBlock> content() {
		return content;
	}

	@Override
	public String getType() {
		return "assistant";
	}

	/**
	 * Returns the first text content from this message, if any.
	 * @return optional text content
	 */
	public Optional<String> getTextContent() {
		return content.stream()
			.filter(block -> block instanceof TextBlock)
			.map(block -> ((TextBlock) block).text())
			.findFirst();
	}

	/**
	 * Returns all text content from this message concatenated, or empty string if none.
	 * This is a convenience method for common use cases where you want the text directly.
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * for (Message msg : client.messages()) {
	 *     if (msg instanceof AssistantMessage) {
	 *         AssistantMessage am = (AssistantMessage) msg;
	 *         System.out.println(am.text());
	 *     }
	 * }
	 * }</pre>
	 * @return concatenated text content, or empty string
	 */
	public String text() {
		return content.stream()
			.filter(block -> block instanceof TextBlock)
			.map(block -> ((TextBlock) block).text())
			.reduce((a, b) -> a + b)
			.orElse("");
	}

	@Override
	public String toString() {
		return text();
	}

	/**
	 * Returns all tool use blocks in this message.
	 * @return list of tool use blocks
	 */
	public List<ToolUseBlock> getToolUses() {
		return content.stream()
			.filter(block -> block instanceof ToolUseBlock)
			.map(block -> (ToolUseBlock) block)
			.collect(Collectors.toList());
	}

	/**
	 * Returns true if this message contains any tool use blocks.
	 * @return true if message contains tool use blocks
	 */
	public boolean hasToolUse() {
		return content.stream().anyMatch(block -> block instanceof ToolUseBlock);
	}

	/**
	 * Returns all text blocks in this message.
	 * @return list of text blocks
	 */
	public List<TextBlock> getTextBlocks() {
		return content.stream().filter(block -> block instanceof TextBlock).map(block -> (TextBlock) block)
				.collect(Collectors.toList());
	}

	/**
	 * Factory method to create an AssistantMessage from content blocks.
	 * @param content the content blocks
	 * @return new AssistantMessage instance
	 */
	public static AssistantMessage of(List<ContentBlock> content) {
		return new AssistantMessage(content);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof AssistantMessage)) {
			return false;
		}
		AssistantMessage that = (AssistantMessage) o;
		return Objects.equals(content, that.content);
	}

	@Override
	public int hashCode() {
		return Objects.hash(content);
	}
}
