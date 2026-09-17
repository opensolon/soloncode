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

package org.noear.soloncode.sdk.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.parsing.ControlMessageParser;
import org.noear.soloncode.sdk.parsing.MessageParser;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.RateLimitEvent;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.util.SdkCollections;
import org.noear.soloncode.sdk.util.SdkJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON 框架从 Jackson 迁移到 snack4 后的线上协议兼容性守门测试。
 *
 * <p>
 * 固化四个行为兼容点（null 字段策略、snake_case 映射、未知字段容错、缺失字段取值语义），
 * 外加多态承重点（ControlRequest / ControlResponse / HookInput / McpServerConfig）的分派行为。
 * 这些断言描述的是「线上协议」，不允许为迁就实现而放宽。
 * </p>
 */
@DisplayName("snack4 迁移：线上协议兼容性")
class JsonWireCompatTest {

	@Nested
	@DisplayName("1. null 字段策略")
	class NullPolicy {

		@Test
		@DisplayName("Map 请求体：null 值照样输出（对齐 Jackson 默认，避免服务端语义变化）")
		void mapNullsAreWritten() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("prompt", null);
			body.put("options", new LinkedHashMap<String, Object>());

			assertThat(SdkJson.toJsonWithNulls(body)).isEqualTo("{\"prompt\":null,\"options\":{}}");
		}

		@Test
		@DisplayName("snack4 默认不写 null —— 等价 @JsonInclude(NON_NULL)")
		void defaultDropsNulls() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("prompt", null);
			body.put("kept", "v");

			assertThat(SdkJson.toJson(body)).isEqualTo("{\"kept\":\"v\"}");
		}

		@Test
		@DisplayName("ControlResponse（原 @JsonInclude(NON_NULL)）不输出 null 字段")
		void controlResponseDropsNulls() {
			String json = SdkJson.toJson(ControlResponse.success("req-1", null));

			assertThat(json).contains("\"subtype\":\"success\"").contains("\"request_id\":\"req-1\"");
			assertThat(json).doesNotContain("null");
		}

	}

	@Nested
	@DisplayName("2. snake_case 字段映射")
	class SnakeCaseMapping {

		@Test
		@DisplayName("出向：字段名逐个落到 snake_case（不依赖全局命名转换）")
		void encodeUsesDeclaredNames() {
			ControlRequest request = new ControlRequest(ControlRequest.TYPE, "req-7",
					new ControlRequest.CanUseToolRequest("Bash", SdkCollections.map("cmd", "ls"), null, "/etc/passwd"));

			String json = SdkJson.toJsonWithNulls(request);

			assertThat(json).contains("\"request_id\":\"req-7\"")
				.contains("\"tool_name\":\"Bash\"")
				.contains("\"blocked_path\":\"/etc/passwd\"")
				.contains("\"permission_suggestions\":null");
			// 驼峰字段名不得出现（全局 snake/camel 转换开关会造成静默错配，这里确认没启用）
			assertThat(json).doesNotContain("toolName").doesNotContain("requestId").doesNotContain("blockedPath");
		}

		@Test
		@DisplayName("入向：snake_case 字段回填到对应属性")
		void decodeUsesDeclaredNames() {
			String json = "{\"hook_event_name\":\"PreToolUse\",\"session_id\":\"s-1\","
					+ "\"transcript_path\":\"/tmp/t.jsonl\",\"cwd\":\"/work\",\"permission_mode\":\"default\","
					+ "\"tool_name\":\"Bash\",\"tool_use_id\":\"tu-1\",\"tool_input\":{\"command\":\"ls\"}}";

			HookInput.PreToolUseInput input = (HookInput.PreToolUseInput) SdkJson.toBean(json, HookInput.class);

			assertThat(input.sessionId()).isEqualTo("s-1");
			assertThat(input.transcriptPath()).isEqualTo("/tmp/t.jsonl");
			assertThat(input.toolName()).isEqualTo("Bash");
			assertThat(input.toolUseId()).isEqualTo("tu-1");
			assertThat(input.toolInput()).containsEntry("command", "ls");
			assertThat(input.permissionMode()).contains("default");
		}

		@Test
		@DisplayName("ResultMessage 的 snake_case 字段（session_id / total_cost_usd / duration_api_ms）")
		void resultMessageSnakeCase() {
			String json = "{\"type\":\"result\",\"subtype\":\"success\",\"duration_ms\":10,\"duration_api_ms\":8,"
					+ "\"is_error\":false,\"num_turns\":2,\"session_id\":\"s-9\",\"total_cost_usd\":0.25,"
					+ "\"result\":\"ok\"}";

			ResultMessage rm = SdkJson.toBean(json, ResultMessage.class);

			assertThat(rm.sessionId()).isEqualTo("s-9");
			assertThat(rm.totalCostUsd()).isEqualTo(0.25);
			assertThat(rm.durationApiMs()).isEqualTo(8);
			assertThat(rm.numTurns()).isEqualTo(2);
		}

	}

	@Nested
	@DisplayName("3. 未知字段容错")
	class UnknownFieldTolerance {

		@Test
		@DisplayName("CLI 新增字段时不炸：control_request 顶层与 payload 内的未知字段都被忽略")
		void controlRequestIgnoresUnknown() throws Exception {
			String json = "{\"type\":\"control_request\",\"request_id\":\"r-1\",\"brand_new_top\":123,"
					+ "\"request\":{\"subtype\":\"set_model\",\"model\":\"sonnet\",\"brand_new_inner\":true}}";

			ParsedMessage parsed = new ControlMessageParser().parse(json);

			ControlRequest request = ((ParsedMessage.Control) parsed).request();
			assertThat(request.requestId()).isEqualTo("r-1");
			assertThat(((ControlRequest.SetModelRequest) request.request()).model()).isEqualTo("sonnet");
		}

		@Test
		@DisplayName("HookInput / RateLimitEvent 同样宽容")
		void beansIgnoreUnknown() {
			HookInput input = SdkJson.toBean("{\"hook_event_name\":\"Stop\",\"session_id\":\"s\","
					+ "\"transcript_path\":\"t\",\"cwd\":\"/\",\"stop_hook_active\":true,\"future\":{\"a\":1}}",
					HookInput.class);
			assertThat(input.hookEventName()).isEqualTo("Stop");

			RateLimitEvent event = SdkJson.toBean("{\"type\":\"rate_limit_event\",\"unknown_x\":1,"
					+ "\"rate_limit_info\":{\"status\":\"allowed\",\"unknown_y\":2}}", RateLimitEvent.class);
			assertThat(event.rateLimitInfo().status()).isEqualTo("allowed");
		}

	}

	@Nested
	@DisplayName("4. 缺失字段 / 类型不符的取值语义")
	class MissingFieldSemantics {

		@Test
		@DisplayName("getStringField：缺失、null、非字符串一律返回 null（对齐 Jackson isTextual 判定）")
		void stringFieldIsStrict() {
			ONode node = SdkJson.parse("{\"s\":\"v\",\"n\":7,\"nul\":null,\"obj\":{},\"b\":true}");

			assertThat(SdkJson.getStringField(node, "s")).isEqualTo("v");
			assertThat(SdkJson.getStringField(node, "missing")).isNull();
			assertThat(SdkJson.getStringField(node, "nul")).isNull();
			// 注意：ONode#getString() 对这三种会分别给出 "7"/"{}"/"true"，SDK 内必须收紧
			assertThat(SdkJson.getStringField(node, "n")).isNull();
			assertThat(SdkJson.getStringField(node, "obj")).isNull();
			assertThat(SdkJson.getStringField(node, "b")).isNull();
		}

		@Test
		@DisplayName("getField：非对象节点或缺失字段返回 null，不抛异常")
		void fieldAccessIsNullSafe() {
			assertThat(SdkJson.getField(SdkJson.parse("\"just-a-string\""), "k")).isNull();
			assertThat(SdkJson.getField(SdkJson.parse("[1,2]"), "k")).isNull();
			assertThat(SdkJson.getField(null, "k")).isNull();
			assertThat(SdkJson.hasField(SdkJson.parse("{\"k\":1}"), "k")).isTrue();
			assertThat(SdkJson.hasField(SdkJson.parse("{}"), "k")).isFalse();
		}

		@Test
		@DisplayName("MessageParser：缺失可选字段时给 null / 默认值，而不是异常")
		void messageParserToleratesMissingFields() throws Exception {
			ResultMessage rm = (ResultMessage) new MessageParser()
				.parseMessage("{\"type\":\"result\",\"result\":\"ok\",\"is_error\":false}");

			assertThat(rm.sessionId()).isNull();
			assertThat(rm.totalCostUsd()).isNull();
			assertThat(rm.durationMs()).isEqualTo(0);
			assertThat(rm.numTurns()).isEqualTo(1);
			assertThat(rm.subtype()).isEqualTo("success");
		}

		@Test
		@DisplayName("缺失的基本类型字段补 0 / false（snack4 默认会给基本类型参数传 null 而抛异常）")
		void absentPrimitivesFallBackToDefaults() {
			ResultMessage rm = SdkJson.toBean("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\"}",
					ResultMessage.class);
			assertThat(rm.durationMs()).isEqualTo(0);
			assertThat(rm.durationApiMs()).isEqualTo(0);
			assertThat(rm.isError()).isFalse();
			assertThat(rm.result()).isEqualTo("ok");

			HookInput.StopInput stop = (HookInput.StopInput) SdkJson.toBean(
					"{\"hook_event_name\":\"Stop\",\"session_id\":\"s\",\"transcript_path\":\"t\",\"cwd\":\"/\"}",
					HookInput.class);
			assertThat(stop.stopHookActive()).isFalse();

			RateLimitEvent event = SdkJson.toBean(
					"{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\"}}",
					RateLimitEvent.class);
			assertThat(event.rateLimitInfo().resetsAt()).isEqualTo(0L);
			assertThat(event.rateLimitInfo().isUsingOverage()).isFalse();
		}

	}

	@Nested
	@DisplayName("5. 多态承重点")
	class Polymorphism {

		@Test
		@DisplayName("出向 ControlRequest 仍带 subtype 判别字段（原由 @JsonTypeInfo 输出）")
		void outboundKeepsSubtype() {
			Map<String, List<ControlRequest.HookMatcherConfig>> hooks = new LinkedHashMap<>();
			hooks.put("PreToolUse",
					SdkCollections.list(new ControlRequest.HookMatcherConfig("Bash", SdkCollections.list("cb-1"), 5)));

			String json = SdkJson.toJsonWithNulls(
					new ControlRequest(ControlRequest.TYPE, "r-2", new ControlRequest.InitializeRequest(hooks)));

			assertThat(json).contains("\"subtype\":\"initialize\"")
				.contains("\"hookCallbackIds\":[\"cb-1\"]")
				.contains("\"type\":\"control_request\"");
		}

		@Test
		@DisplayName("入向 control_response 按 subtype 分派到 Success/Error payload")
		void inboundControlResponseDispatch() throws Exception {
			ControlMessageParser parser = new ControlMessageParser();

			ParsedMessage ok = parser.parse("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\","
					+ "\"request_id\":\"r-3\",\"response\":{\"k\":1}}}");
			ControlResponse okResponse = ((ParsedMessage.ControlResponseMessage) ok).response();
			assertThat(okResponse.response()).isInstanceOf(ControlResponse.SuccessPayload.class);
			assertThat(okResponse.response().requestId()).isEqualTo("r-3");

			ParsedMessage err = parser.parse("{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\","
					+ "\"request_id\":\"r-4\",\"error\":\"boom\"}}");
			ControlResponse errResponse = ((ParsedMessage.ControlResponseMessage) err).response();
			assertThat(errResponse.response()).isInstanceOf(ControlResponse.ErrorPayload.class);
			assertThat(((ControlResponse.ErrorPayload) errResponse.response()).error()).isEqualTo("boom");
		}

		@Test
		@DisplayName("McpServerConfig 按 type 分派，缺失 type 回退 stdio（原 defaultImpl）")
		void mcpConfigDispatch() {
			McpServerConfig sse = SdkJson.toBean("{\"type\":\"sse\",\"url\":\"http://x/sse\","
					+ "\"headers\":{\"H\":\"1\"}}", McpServerConfig.class);
			assertThat(sse).isInstanceOf(McpServerConfig.McpSseServerConfig.class);
			assertThat(((McpServerConfig.McpSseServerConfig) sse).headers()).containsEntry("H", "1");

			McpServerConfig fallback = SdkJson.toBean("{\"command\":\"npx\",\"args\":[\"-y\",\"pkg\"]}",
					McpServerConfig.class);
			assertThat(fallback).isInstanceOf(McpServerConfig.McpStdioServerConfig.class);
			// 集合字段不得被重复填充
			assertThat(((McpServerConfig.McpStdioServerConfig) fallback).args()).containsExactly("-y", "pkg");
		}

	}

}
