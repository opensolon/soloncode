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

package org.noear.soloncode.sdk.parsing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.ContentBlock;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MessageParser - verifies parsing of all message types including structured
 * output.
 *
 * <p>
 * IMPORTANT: These tests exist because the MessageParser must parse ALL fields from CLI
 * JSON. A previous bug occurred when structured_output field was in the record but parser
 * didn't set it. For every field in a Message record, there MUST be a corresponding test
 * here.
 * </p>
 */
class MessageParserTest {

	private MessageParser parser;

	@BeforeEach
	void setUp() {
		parser = new MessageParser();
	}

	@Nested
	@DisplayName("Result Message Parsing")
	class ResultMessageParsing {

		/**
		 * FIELD PARITY TEST: Verifies ALL fields in ResultMessage record are parsed. If
		 * you add a field to ResultMessage, you MUST add an assertion here.
		 *
		 * ResultMessage fields: subtype, durationMs, durationApiMs, isError, numTurns,
		 * sessionId, totalCostUsd, usage, result, structuredOutput
		 */
		@Test
		@DisplayName("should parse ALL fields in ResultMessage (field parity test)")
		void shouldParseAllResultMessageFields() throws Exception {
			String json = "{ \"type\": \"result\", \"subtype\": \"success\", \"is_error\": true, \"duration_ms\": 1234, \"duration_api_ms\": 5678, \"num_turns\": 3, \"session_id\": \"sess-abc123\", \"total_cost_usd\": 0.0456, \"budget_limit_usd\": 1.5, \"budget_exceeded\": true, \"usage\": { \"input_tokens\": 100, \"output_tokens\": 200 }, \"result\": \"final result text\", \"structured_output\": {\"key\": \"value\"} }";

			Message message = parser.parseMessage(json);
			assertThat(message).isInstanceOf(ResultMessage.class);
			ResultMessage result = (ResultMessage) message;

			// Verify EVERY field - add new assertions when fields are added
			assertThat(result.subtype()).isEqualTo("success");
			assertThat(result.isError()).isTrue();
			assertThat(result.durationMs()).isEqualTo(1234);
			assertThat(result.durationApiMs()).isEqualTo(5678);
			assertThat(result.numTurns()).isEqualTo(3);
			assertThat(result.sessionId()).isEqualTo("sess-abc123");
			assertThat(result.totalCostUsd()).isEqualTo(0.0456);
			assertThat(((Number) result.usage().get("input_tokens")).intValue()).isEqualTo(100);
			assertThat(((Number) result.usage().get("output_tokens")).intValue()).isEqualTo(200);
			assertThat(result.result()).isEqualTo("final result text");
			assertThat(result.structuredOutput()).isNotNull();
			assertThat(result.getStructuredOutputAsMap()).containsEntry("key", "value");
			assertThat(result.budgetLimitUsd()).isEqualTo(1.5);
			assertThat(result.isBudgetExceeded()).isTrue();
		}

		@Test
		@DisplayName("should parse result message with structured_output")
		void shouldParseResultWithStructuredOutput() throws Exception {
			String json = "{ \"type\": \"result\", \"subtype\": \"success\", \"is_error\": false, \"duration_ms\": 1000, \"duration_api_ms\": 800, \"num_turns\": 2, \"session_id\": \"test-session\", \"total_cost_usd\": 0.01, \"result\": \"\", \"structured_output\": { \"answer\": 4, \"explanation\": \"2 plus 2 equals 4\" } }";

			Message message = parser.parseMessage(json);

			assertThat(message).isInstanceOf(ResultMessage.class);
			ResultMessage result = (ResultMessage) message;

			assertThat(result.hasStructuredOutput()).isTrue();
			Map<String, Object> output = result.getStructuredOutputAsMap();
			assertThat(output).isNotNull();
			assertThat(output.get("answer")).isEqualTo(4);
			assertThat(output.get("explanation")).isEqualTo("2 plus 2 equals 4");
		}

		@Test
		@DisplayName("should parse result message with nested structured_output")
		void shouldParseResultWithNestedStructuredOutput() throws Exception {
			String json = "{ \"type\": \"result\", \"subtype\": \"success\", \"is_error\": false, \"duration_ms\": 1000, \"num_turns\": 1, \"session_id\": \"test-session\", \"total_cost_usd\": 0.02, \"structured_output\": { \"languages\": [ {\"name\": \"Java\", \"year\": 1995}, {\"name\": \"Python\", \"year\": 1991} ] } }";

			Message message = parser.parseMessage(json);
			ResultMessage result = (ResultMessage) message;

			assertThat(result.hasStructuredOutput()).isTrue();
			Map<String, Object> output = result.getStructuredOutputAsMap();
			assertThat(output).containsKey("languages");

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> languages = (List<Map<String, Object>>) output.get("languages");
			assertThat(languages).hasSize(2);
			assertThat(languages.get(0).get("name")).isEqualTo("Java");
			assertThat(languages.get(0).get("year")).isEqualTo(1995);
		}

		@Test
		@DisplayName("should parse result message without structured_output")
		void shouldParseResultWithoutStructuredOutput() throws Exception {
			String json = "{ \"type\": \"result\", \"subtype\": \"success\", \"is_error\": false, \"duration_ms\": 500, \"num_turns\": 1, \"session_id\": \"test-session\", \"total_cost_usd\": 0.005, \"result\": \"Some text result\" }";

			Message message = parser.parseMessage(json);
			ResultMessage result = (ResultMessage) message;

			assertThat(result.hasStructuredOutput()).isFalse();
			assertThat(result.getStructuredOutputAsMap()).isNull();
			assertThat(result.result()).isEqualTo("Some text result");
		}

		@Test
		@DisplayName("should parse result message with null structured_output")
		void shouldParseResultWithNullStructuredOutput() throws Exception {
			String json = "{ \"type\": \"result\", \"subtype\": \"success\", \"is_error\": false, \"duration_ms\": 500, \"num_turns\": 1, \"session_id\": \"test-session\", \"structured_output\": null }";

			Message message = parser.parseMessage(json);
			ResultMessage result = (ResultMessage) message;

			assertThat(result.hasStructuredOutput()).isFalse();
			assertThat(result.getStructuredOutputAsMap()).isNull();
		}

		@Test
		void shouldMapSolonCodeMetricTokenNamesToMetadataUsage() throws Exception {
			ResultMessage result = (ResultMessage) parser.parseMessage(
					"{\"type\":\"result\",\"is_error\":false,\"metrics\":{\"prompt_tokens\":12,\"completion_tokens\":7,\"total_tokens\":19}}");
			assertThat(result.toMetadata("test").usage().inputTokens()).isEqualTo(12);
			assertThat(result.toMetadata("test").usage().outputTokens()).isEqualTo(7);
		}

	}

	@Nested
	@DisplayName("Assistant Message Parsing")
	class AssistantMessageParsing {

		@Test
		@DisplayName("should parse assistant message with text content block")
		void shouldParseAssistantWithTextContent() throws Exception {
			String json = "{ \"type\": \"assistant\", \"message\": { \"content\": [ {\"type\": \"text\", \"text\": \"Hello, world!\"} ] } }";

			Message message = parser.parseMessage(json);
			assertThat(message).isInstanceOf(AssistantMessage.class);
			AssistantMessage assistant = (AssistantMessage) message;

			assertThat(assistant.content()).hasSize(1);
			assertThat(assistant.getTextContent()).isPresent();
			assertThat(assistant.getTextContent().get()).isEqualTo("Hello, world!");
		}

		@Test
		@DisplayName("should parse assistant message with tool_use content block")
		void shouldParseAssistantWithToolUse() throws Exception {
			String json = "{ \"type\": \"assistant\", \"message\": { \"content\": [ { \"type\": \"tool_use\", \"id\": \"tool_123\", \"name\": \"Read\", \"input\": {\"file_path\": \"/tmp/test.txt\"} } ] } }";

			Message message = parser.parseMessage(json);
			AssistantMessage assistant = (AssistantMessage) message;

			assertThat(assistant.content()).hasSize(1);
			ContentBlock block = assistant.content().get(0);
			assertThat(block.getType()).isEqualTo("tool_use");
		}

	}

	@Nested
	@DisplayName("User Message Parsing")
	class UserMessageParsing {

		@Test
		@DisplayName("should parse user message with tool_result content")
		void shouldParseUserWithToolResult() throws Exception {
			String json = "{ \"type\": \"user\", \"message\": { \"content\": [ { \"type\": \"tool_result\", \"tool_use_id\": \"tool_123\", \"content\": \"file contents here\" } ] } }";

			Message message = parser.parseMessage(json);
			assertThat(message).isInstanceOf(UserMessage.class);
			UserMessage user = (UserMessage) message;

			assertThat(user.content()).isNotNull();
		}

	}

}
