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

import org.noear.soloncode.sdk.util.SdkCollections;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Top-level response object containing messages and metadata. Provides convenient
 * accessors and domain-specific operations.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * QueryResult result = Query.execute("What is 2+2?");
 *
 * // Get text response (most common)
 * result.text().ifPresent(System.out::println);
 *
 * // Or get all text responses
 * result.allText().forEach(System.out::println);
 *
 * // Check status
 * if (result.isSuccessful()) {
 *     System.out.println("Cost: $" + result.metadata().cost().calculateTotal());
 * }
 * }</pre>
 */
public final class QueryResult {

	private final List<Message> messages;

	private final Metadata metadata;

	private final ResultStatus status;

	public QueryResult(List<Message> messages, Metadata metadata, ResultStatus status) {
		this.messages = messages;
		this.metadata = metadata;
		this.status = status;
	}

	public List<Message> messages() {
		return messages;
	}

	public Metadata metadata() {
		return metadata;
	}

	public ResultStatus status() {
		return status;
	}

	/**
	 * Returns the combined text from all assistant responses. This is the most common way
	 * to get the response text.
	 *
	 * <pre>{@code
	 * result.text().ifPresent(System.out::println);
	 * }</pre>
	 * @return the combined text, or empty if no text responses
	 */
	public Optional<String> text() {
		StringBuilder sb = new StringBuilder();
		for (Message msg : messages) {
			if (msg instanceof AssistantMessage) {
				AssistantMessage assistant = (AssistantMessage) msg;
				assistant.getTextContent().ifPresent(text -> {
					if (sb.length() > 0) {
						sb.append("\n");
					}
					sb.append(text);
				});
			}
		}
		return sb.length() > 0 ? Optional.of(sb.toString()) : Optional.empty();
	}

	/**
	 * Returns all text responses as a list.
	 * @return list of text responses from assistant messages
	 */
	public List<String> allText() {
		return messages.stream()
			.filter(m -> m instanceof AssistantMessage)
			.map(m -> ((AssistantMessage) m).getTextContent())
			.filter(Optional::isPresent)
			.map(Optional::get)
			.collect(Collectors.toList());
	}

	/**
	 * Returns the first assistant response text, if any.
	 * @deprecated Use {@link #text()} instead
	 */
	@Deprecated
	public Optional<String> getFirstAssistantResponse() {
		return messages.stream()
			.filter(m -> m instanceof AssistantMessage)
			.findFirst()
			.flatMap(m -> ((AssistantMessage) m).getTextContent());
	}

	/**
	 * Returns true if any message contains tool use.
	 */
	public boolean hasToolUse() {
		return messages.stream()
			.filter(m -> m instanceof AssistantMessage)
			.anyMatch(m -> ((AssistantMessage) m).hasToolUse());
	}

	/**
	 * Returns all tool use blocks across all messages.
	 */
	public List<ToolUseBlock> getAllToolUses() {
		return messages.stream()
			.filter(m -> m instanceof AssistantMessage)
			.flatMap(m -> ((AssistantMessage) m).getToolUses().stream())
			.collect(Collectors.toList());
	}

	/**
	 * Returns all assistant messages in the result.
	 */
	public List<AssistantMessage> getAssistantMessages() {
		return messages.stream().filter(m -> m instanceof AssistantMessage).map(m -> (AssistantMessage) m)
				.collect(Collectors.toList());
	}

	/**
	 * Returns all user messages in the result.
	 */
	public List<UserMessage> getUserMessages() {
		return messages.stream().filter(m -> m instanceof UserMessage).map(m -> (UserMessage) m)
				.collect(Collectors.toList());
	}

	/**
	 * Returns the result message if present.
	 */
	public Optional<ResultMessage> getResultMessage() {
		return messages.stream().filter(m -> m instanceof ResultMessage).map(m -> (ResultMessage) m).findFirst();
	}

	/**
	 * Returns true if the query was successful (no errors).
	 */
	public boolean isSuccessful() {
		return status == ResultStatus.SUCCESS;
	}

	/**
	 * Returns true if the query resulted in an error.
	 */
	public boolean isError() {
		return status == ResultStatus.ERROR;
	}

	/**
	 * Returns the total number of messages.
	 */
	public int getMessageCount() {
		return messages.size();
	}

	/**
	 * Returns true if this was an expensive query.
	 */
	public boolean isExpensive() {
		return metadata.isExpensive();
	}

	/**
	 * Returns true if this was a fast response.
	 */
	public boolean isFastResponse() {
		return metadata.isFastResponse();
	}

	/**
	 * Returns the structured output parsed via --json-schema, if present.
	 * @return the structured output from the result message, or empty if absent
	 */
	public Optional<Object> structuredOutput() {
		return getResultMessage().filter(rm -> rm.hasStructuredOutput()).map(rm -> rm.structuredOutput());
	}

	/**
	 * Returns true if the result message reports budget_exceeded=true.
	 */
	public boolean isBudgetExceeded() {
		return getResultMessage().isPresent() && getResultMessage().get().isBudgetExceeded();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private List<Message> messages;

		private Metadata metadata;

		private ResultStatus status = ResultStatus.SUCCESS;

		public Builder messages(List<Message> messages) {
			this.messages = messages;
			return this;
		}

		public Builder addMessage(Message message) {
			if (this.messages == null) {
				this.messages = new java.util.ArrayList<>();
			}
			this.messages.add(message);
			return this;
		}

		public Builder metadata(Metadata metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder status(ResultStatus status) {
			this.status = status;
			return this;
		}

		public QueryResult build() {
			return new QueryResult(messages != null ? SdkCollections.copyList(messages) : SdkCollections.list(),
					metadata, status);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof QueryResult)) {
			return false;
		}
		QueryResult that = (QueryResult) o;
		return Objects.equals(messages, that.messages) && Objects.equals(metadata, that.metadata)
				&& status == that.status;
	}

	@Override
	public int hashCode() {
		return Objects.hash(messages, metadata, status);
	}

	@Override
	public String toString() {
		return "QueryResult[messages=" + messages + ", metadata=" + metadata + ", status=" + status + "]";
	}
}
