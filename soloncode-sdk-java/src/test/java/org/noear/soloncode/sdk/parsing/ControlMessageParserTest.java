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
import org.noear.soloncode.sdk.exceptions.MessageParseException;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.RateLimitEvent;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;
import org.noear.soloncode.sdk.types.UserMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ControlMessageParser - verifies parsing of both regular messages and control
 * requests in bidirectional mode.
 */
class ControlMessageParserTest {

	private ControlMessageParser parser;

	@BeforeEach
	void setUp() {
		parser = new ControlMessageParser();
	}

	@Nested
	@DisplayName("Control Request Parsing")
	class ControlRequestParsing {

		@Test
		@DisplayName("Should parse hook_callback control request")
		void parseHookCallbackRequest() throws Exception {
			// Given - exact format from CLI
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_3_ghi789\", \"request\": { \"subtype\": \"hook_callback\", \"callback_id\": \"hook_0\", \"input\": { \"hook_event_name\": \"PreToolUse\", \"tool_name\": \"Bash\", \"tool_input\": {\"command\": \"ls -la\"}, \"session_id\": \"sess_xyz\", \"transcript_path\": \"/tmp/t.md\", \"cwd\": \"/home/user\" }, \"tool_use_id\": \"tool_123\" } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isControlRequest()).isTrue();
			assertThat(result.isRegularMessage()).isFalse();
			assertThat(result.asMessage()).isNull();

			ControlRequest request = result.asControlRequest();
			assertThat(request).isNotNull();
			assertThat(request.type()).isEqualTo("control_request");
			assertThat(request.requestId()).isEqualTo("req_3_ghi789");
			assertThat(request.request()).isInstanceOf(ControlRequest.HookCallbackRequest.class);

			ControlRequest.HookCallbackRequest hookCallback = (ControlRequest.HookCallbackRequest) request.request();
			assertThat(hookCallback.callbackId()).isEqualTo("hook_0");
			assertThat(hookCallback.toolUseId()).isEqualTo("tool_123");
			assertThat(hookCallback.input()).containsEntry("tool_name", "Bash");
		}

		@Test
		@DisplayName("Should parse can_use_tool control request")
		void parseCanUseToolRequest() throws Exception {
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_2_def456\", \"request\": { \"subtype\": \"can_use_tool\", \"tool_name\": \"Write\", \"input\": { \"path\": \"/home/user/file.txt\", \"file_text\": \"Hello world\" }, \"permission_suggestions\": [ { \"type\": \"addRules\", \"rules\": [{\"toolName\": \"Write\", \"ruleContent\": \"*.txt\"}], \"behavior\": \"allow\", \"destination\": \"session\" } ] } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isControlRequest()).isTrue();

			ControlRequest.CanUseToolRequest request = (ControlRequest.CanUseToolRequest) result.asControlRequest().request();
			assertThat(request.toolName()).isEqualTo("Write");
			assertThat(request.input()).containsEntry("path", "/home/user/file.txt");
			assertThat(request.permissionSuggestions()).hasSize(1);
		}

		@Test
		@DisplayName("Should parse initialize control request")
		void parseInitializeRequest() throws Exception {
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_1_init\", \"request\": { \"subtype\": \"initialize\" } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isControlRequest()).isTrue();
			assertThat(result.asControlRequest().request()).isInstanceOf(ControlRequest.InitializeRequest.class);
		}

		@Test
		@DisplayName("Should parse interrupt control request")
		void parseInterruptRequest() throws Exception {
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_int_001\", \"request\": { \"subtype\": \"interrupt\" } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isControlRequest()).isTrue();
			assertThat(result.asControlRequest().request()).isInstanceOf(ControlRequest.InterruptRequest.class);
		}

		@Test
		@DisplayName("Should parse set_permission_mode control request")
		void parseSetPermissionModeRequest() throws Exception {
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_perm_001\", \"request\": { \"subtype\": \"set_permission_mode\", \"mode\": \"acceptEdits\" } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isControlRequest()).isTrue();

			ControlRequest.SetPermissionModeRequest request = (ControlRequest.SetPermissionModeRequest) result.asControlRequest().request();
			assertThat(request.mode()).isEqualTo("acceptEdits");
		}

		@Test
		@DisplayName("Should parse mcp_message control request")
		void parseMcpMessageRequest() throws Exception {
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_mcp_001\", \"request\": { \"subtype\": \"mcp_message\", \"server_name\": \"my_mcp_server\", \"message\": { \"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"tools/list\" } } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isControlRequest()).isTrue();

			ControlRequest.McpMessageRequest request = (ControlRequest.McpMessageRequest) result.asControlRequest().request();
			assertThat(request.serverName()).isEqualTo("my_mcp_server");
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> messageMap = (java.util.Map<String, Object>) request.message();
			assertThat(messageMap).containsEntry("method", "tools/list");
		}

	}

	@Nested
	@DisplayName("Regular Message Parsing")
	class RegularMessageParsing {

		@Test
		@DisplayName("Should parse user message")
		void parseUserMessage() throws Exception {
			String json = "{ \"type\": \"user\", \"message\": { \"content\": \"Hello, SolonCode!\" } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isRegularMessage()).isTrue();
			assertThat(result.isControlRequest()).isFalse();
			assertThat(result.asControlRequest()).isNull();
			assertThat(result.asMessage()).isInstanceOf(UserMessage.class);
		}

		@Test
		@DisplayName("Should parse assistant message with text content")
		void parseAssistantMessage() throws Exception {
			String json = "{ \"type\": \"assistant\", \"message\": { \"content\": [ {\"type\": \"text\", \"text\": \"Hello! How can I help you today?\"} ] } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isRegularMessage()).isTrue();
			assertThat(result.asMessage()).isInstanceOf(AssistantMessage.class);
		}

		@Test
		@DisplayName("Should parse assistant message with tool use")
		void parseAssistantMessageWithToolUse() throws Exception {
			String json = "{ \"type\": \"assistant\", \"message\": { \"content\": [ {\"type\": \"text\", \"text\": \"Let me check that file.\"}, {\"type\": \"tool_use\", \"id\": \"tool_123\", \"name\": \"Read\", \"input\": {\"path\": \"/tmp/test.txt\"}} ] } }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isRegularMessage()).isTrue();
			AssistantMessage message = (AssistantMessage) result.asMessage();
			assertThat(message.content()).hasSize(2);
		}

		@Test
		@DisplayName("Should parse system message")
		void parseSystemMessage() throws Exception {
			String json = "{ \"type\": \"system\", \"subtype\": \"init\", \"session_id\": \"sess_abc123\", \"tools\": [\"Bash\", \"Read\", \"Write\"] }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isRegularMessage()).isTrue();
			assertThat(result.asMessage()).isInstanceOf(SystemMessage.class);

			SystemMessage message = (SystemMessage) result.asMessage();
			assertThat(message.subtype()).isEqualTo("init");
		}

		@Test
		@DisplayName("Should parse result message")
		void parseResultMessage() throws Exception {
			String json = "{ \"type\": \"result\", \"subtype\": \"success\", \"duration_ms\": 1234, \"duration_api_ms\": 987, \"is_error\": false, \"num_turns\": 3, \"session_id\": \"sess_xyz\", \"total_cost_usd\": 0.0025, \"usage\": {\"input_tokens\": 100, \"output_tokens\": 50}, \"result\": \"Task completed successfully\" }";

			// When
			ParsedMessage result = parser.parse(json);

			// Then
			assertThat(result.isRegularMessage()).isTrue();
			assertThat(result.asMessage()).isInstanceOf(ResultMessage.class);

			ResultMessage message = (ResultMessage) result.asMessage();
			assertThat(message.subtype()).isEqualTo("success");
			assertThat(message.durationMs()).isEqualTo(1234);
			assertThat(message.isError()).isFalse();
		}

	}

	@Nested
	@DisplayName("ParsedMessage API")
	class ParsedMessageApi {

		@Test
		@DisplayName("Should provide convenient accessor methods for control request")
		void controlRequestAccessors() throws Exception {
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_1\", \"request\": {\"subtype\": \"interrupt\"} }";

			ParsedMessage result = parser.parse(json);

			// Using accessor methods (Java 17 compatible)
			assertThat(result.isControlRequest()).isTrue();
			assertThat(result.asControlRequest().requestId()).isEqualTo("req_1");
		}

		@Test
		@DisplayName("Should provide convenient accessor methods for regular message")
		void regularMessageAccessors() throws Exception {
			String json = "{ \"type\": \"user\", \"message\": {\"content\": \"Hello\"} }";

			ParsedMessage result = parser.parse(json);

			// Using accessor methods (Java 17 compatible)
			assertThat(result.isRegularMessage()).isTrue();
			assertThat(result.asMessage()).isInstanceOf(UserMessage.class);
		}

	}

	@Nested
	@DisplayName("Helper Methods")
	class HelperMethods {

		@Test
		@DisplayName("isControlRequest should return true for control requests")
		void isControlRequestTrue() {
			String json = "{\"type\": \"control_request\", \"request_id\": \"req_1\", \"request\": {\"subtype\": \"interrupt\"}}";

			assertThat(parser.isControlRequest(json)).isTrue();
		}

		@Test
		@DisplayName("isControlRequest should return false for regular messages")
		void isControlRequestFalse() {
			String json = "{\"type\": \"user\", \"message\": {\"content\": \"Hello\"}}";

			assertThat(parser.isControlRequest(json)).isFalse();
		}

		@Test
		@DisplayName("isControlRequest should return false for invalid JSON")
		void isControlRequestInvalidJson() {
			assertThat(parser.isControlRequest("not json")).isFalse();
			assertThat(parser.isControlRequest(null)).isFalse();
			assertThat(parser.isControlRequest("")).isFalse();
		}

		@Test
		@DisplayName("extractRequestId should return request ID from control request")
		void extractRequestIdSuccess() {
			String json = "{\"type\": \"control_request\", \"request_id\": \"req_abc_123\", \"request\": {\"subtype\": \"interrupt\"}}";

			assertThat(parser.extractRequestId(json)).isEqualTo("req_abc_123");
		}

		@Test
		@DisplayName("extractRequestId should return null for regular messages")
		void extractRequestIdFromRegularMessage() {
			String json = "{\"type\": \"user\", \"message\": {\"content\": \"Hello\"}}";

			assertThat(parser.extractRequestId(json)).isNull();
		}

	}

	@Nested
	@DisplayName("Error Handling")
	class ErrorHandling {

		@Test
		@DisplayName("Should throw MessageParseException for malformed JSON")
		void malformedJson() {
			assertThatThrownBy(() -> parser.parse("not valid json")).isInstanceOf(MessageParseException.class);
		}

		@Test
		@DisplayName("Should throw MessageParseException for missing type field")
		void missingTypeField() {
			String json = "{\"request_id\": \"req_1\", \"request\": {\"subtype\": \"interrupt\"}}";

			assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(MessageParseException.class)
				.hasMessageContaining("type");
		}

		@Test
		@DisplayName("Should throw MessageParseException for null input")
		void nullInput() {
			assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(MessageParseException.class)
				.hasMessageContaining("null or blank");
		}

		@Test
		@DisplayName("Should throw MessageParseException for blank input")
		void blankInput() {
			assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(MessageParseException.class)
				.hasMessageContaining("null or blank");
		}

		@Test
		@DisplayName("Should throw MessageParseException for control request missing request_id")
		void controlRequestMissingRequestId() {
			String json = "{\"type\": \"control_request\", \"request\": {\"subtype\": \"interrupt\"}}";

			assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(MessageParseException.class)
				.hasMessageContaining("request_id");
		}

	}

	@Nested
	@DisplayName("Rate Limit Event Parsing")
	class RateLimitEventParsing {

		@Test
		@DisplayName("Should parse rate_limit_event with all fields")
		void shouldParseRateLimitEvent() throws Exception {
			String json = "{ \"type\": \"rate_limit_event\", \"rate_limit_info\": { \"status\": \"allowed\", \"resetsAt\": 1772157600, \"rateLimitType\": \"five_hour\", \"overageStatus\": \"rejected\", \"overageDisabledReason\": \"org_level_disabled\", \"isUsingOverage\": false }, \"uuid\": \"eb7544df-6f3e-4438-a178-f4de557b156d\", \"session_id\": \"5227c790-48a2-425c-9e7a-da74f3a2761b\" }";

			ParsedMessage result = parser.parse(json);

			assertThat(result).isNotNull();
			assertThat(result.isRateLimitEvent()).isTrue();
			assertThat(result.isRegularMessage()).isFalse();
			assertThat(result.isControlRequest()).isFalse();

			RateLimitEvent event = result.asRateLimitEvent();
			assertThat(event).isNotNull();
			assertThat(event.type()).isEqualTo("rate_limit_event");
			assertThat(event.uuid()).isEqualTo("eb7544df-6f3e-4438-a178-f4de557b156d");
			assertThat(event.sessionId()).isEqualTo("5227c790-48a2-425c-9e7a-da74f3a2761b");
			assertThat(event.isAllowed()).isTrue();

			assertThat(event.rateLimitInfo()).isNotNull();
			assertThat(event.rateLimitInfo().status()).isEqualTo("allowed");
			assertThat(event.rateLimitInfo().resetsAt()).isEqualTo(1772157600L);
			assertThat(event.rateLimitInfo().rateLimitType()).isEqualTo("five_hour");
			assertThat(event.rateLimitInfo().overageStatus()).isEqualTo("rejected");
			assertThat(event.rateLimitInfo().overageDisabledReason()).isEqualTo("org_level_disabled");
			assertThat(event.rateLimitInfo().isUsingOverage()).isFalse();
			assertThat(event.rateLimitInfo().isAllowed()).isTrue();
		}

		@Test
		@DisplayName("Should not produce ERROR log for rate_limit_event")
		void shouldNotTreatAsUnrecognizedType() throws Exception {
			String json = "{ \"type\": \"rate_limit_event\", \"rate_limit_info\": {\"status\": \"allowed\", \"resetsAt\": 0, \"rateLimitType\": \"five_hour\", \"overageStatus\": \"rejected\", \"overageDisabledReason\": \"org_level_disabled\", \"isUsingOverage\": false}, \"uuid\": \"test-uuid\", \"session_id\": \"test-session\" }";

			// Should parse without error — previously fell through to default case
			// and logged ERROR "Unrecognized message type 'rate_limit_event'"
			ParsedMessage result = parser.parse(json);
			assertThat(result).isNotNull();
			assertThat(result).isInstanceOf(ParsedMessage.RateLimitEventMessage.class);
		}

	}

	@Nested
	@DisplayName("Backwards Compatibility")
	class BackwardsCompatibility {

		@Test
		@DisplayName("Should handle unknown fields gracefully")
		void unknownFieldsIgnored() throws Exception {
			String json = "{ \"type\": \"control_request\", \"request_id\": \"req_1\", \"unknown_field\": \"should be ignored\", \"another_unknown\": 12345, \"request\": { \"subtype\": \"interrupt\", \"future_field\": \"also ignored\" } }";

			ParsedMessage result = parser.parse(json);

			assertThat(result.isControlRequest()).isTrue();
			assertThat(result.asControlRequest().request()).isInstanceOf(ControlRequest.InterruptRequest.class);
		}

		@Test
		@DisplayName("Should parse messages with extra whitespace")
		void extraWhitespace() throws Exception {
			String json = "{ \"type\":   \"control_request\"  , \"request_id\":  \"req_1\", \"request\": { \"subtype\": \"interrupt\" } }";

			ParsedMessage result = parser.parse(json);

			assertThat(result.isControlRequest()).isTrue();
		}

	}

	@Nested
	@DisplayName("Protocol Compatibility")
	class ProtocolCompatibility {

		@Test
		@DisplayName("Full bidirectional message flow simulation")
		void bidirectionalFlow() throws Exception {
			// Simulate messages in a bidirectional session

			// 1. System init message
			String initJson = "{\"type\": \"system\", \"subtype\": \"init\", \"session_id\": \"sess_1\"}";
			ParsedMessage init = parser.parse(initJson);
			assertThat(init.isRegularMessage()).isTrue();

			// 2. Control request for hook callback
			String hookJson = "{ \"type\": \"control_request\", \"request_id\": \"req_1\", \"request\": { \"subtype\": \"hook_callback\", \"callback_id\": \"hook_0\", \"input\": { \"hook_event_name\": \"PreToolUse\", \"tool_name\": \"Bash\", \"tool_input\": {\"command\": \"echo hello\"} } } }";
			ParsedMessage hook = parser.parse(hookJson);
			assertThat(hook.isControlRequest()).isTrue();

			// 3. Assistant message
			String assistantJson = "{ \"type\": \"assistant\", \"message\": { \"content\": [{\"type\": \"text\", \"text\": \"Done!\"}] } }";
			ParsedMessage assistant = parser.parse(assistantJson);
			assertThat(assistant.isRegularMessage()).isTrue();

			// 4. Result message
			String resultJson = "{\"type\": \"result\", \"subtype\": \"success\", \"result\": \"Complete\"}";
			ParsedMessage result = parser.parse(resultJson);
			assertThat(result.isRegularMessage()).isTrue();
		}

	}

}
