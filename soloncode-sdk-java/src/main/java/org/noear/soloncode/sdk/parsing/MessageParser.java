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

package org.noear.soloncode.sdk.parsing;

import org.noear.soloncode.sdk.exceptions.MessageParseException;
import org.noear.soloncode.sdk.types.*;
import org.noear.soloncode.sdk.util.SdkCollections;
import org.noear.snack4.ONode;
import org.noear.snack4.SnackException;
import org.noear.soloncode.sdk.util.SdkJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JSON messages from SolonCode CLI output into typed Message objects. Handles the
 * stream JSON format and converts to domain objects.
 */
public class MessageParser {

	private static final Logger logger = LoggerFactory.getLogger(MessageParser.class);

	public MessageParser() {
	}

	/**
	 * Parses a JSON string into a Message object.
	 */
	public Message parseMessage(String json) throws MessageParseException {
		try {
			ONode root = SdkJson.parse(json);
			return parseMessageFromNode(root);
		}
		catch (SnackException e) {
			throw MessageParseException.jsonDecodeError(json, e);
		}
	}

	/**
	 * Parses a ONode into a Message object.
	 */
	public Message parseMessageFromNode(ONode node) throws MessageParseException {
		String type = getStringField(node, "type");
		if (type == null) {
			throw new MessageParseException("Missing 'type' field in message");
		}

		if ("user".equals(type)) {
			return parseUserMessage(node);
		}
		else if ("assistant".equals(type)) {
			return parseAssistantMessage(node);
		}
		else if ("system".equals(type)) {
			return parseSystemMessage(node);
		}
		else if ("result".equals(type)) {
			return parseResultMessage(node);
		}
		else if ("error".equals(type)) {
			// run-headless-mode-http.md：/web/run 在异常退出且流中无 result 事件时补发
			// error 事件（HTTP 通道下它是服务端故障的唯一通知）；CLI stream-json 同样可能
			// 出现顶层 error 事件。按 SystemMessage(subtype="error") 投递，向前兼容。
			return parseErrorMessage(node);
		}
		else {
			logger.error(
					"Unrecognized message type '{}' — skipping. "
							+ "This may indicate the SolonCode CLI has added a new message type. " + "Raw JSON: {}",
					type, node);
			return null;
		}
	}

	private UserMessage parseUserMessage(ONode node) throws MessageParseException {
		ONode messageNode = SdkJson.getField(node, "message");
		if (messageNode == null) {
			throw new MessageParseException("Missing 'message' field in user message");
		}

		ONode contentNode = SdkJson.getField(messageNode, "content");
		if (contentNode == null) {
			throw new MessageParseException("Missing 'content' field in user message");
		}

		if (contentNode.isString()) {
			return UserMessage.of(contentNode.getString());
		}
		else if (contentNode.isArray()) {
			List<ContentBlock> blocks = parseContentBlocks(contentNode);
			return UserMessage.of(blocks);
		}
		else {
			throw new MessageParseException("Invalid content format in user message");
		}
	}

	private AssistantMessage parseAssistantMessage(ONode node) throws MessageParseException {
		ONode messageNode = SdkJson.getField(node, "message");
		if (messageNode == null) {
			throw new MessageParseException("Missing 'message' field in assistant message");
		}

		ONode contentNode = SdkJson.getField(messageNode, "content");
		if (contentNode == null || !contentNode.isArray()) {
			throw new MessageParseException("Missing or invalid 'content' field in assistant message");
		}

		List<ContentBlock> blocks = parseContentBlocks(contentNode);
		return AssistantMessage.of(blocks);
	}

	private SystemMessage parseSystemMessage(ONode node) throws MessageParseException {
		// System messages have data directly in the root node, not nested under "message"
		String subtype = getStringField(node, "subtype");
		if (subtype == null) {
			throw new MessageParseException("Missing 'subtype' field in system message");
		}

		// Parse all fields as data (excluding type and subtype)
		Map<String, Object> data = parseDataMap(node);
		data.remove("type"); // Remove these metadata fields from data
		data.remove("subtype");

		return SystemMessage.of(subtype, data);
	}

	/**
	 * 顶层 error 事件：{"type":"error","message":...[,"code":...]}，无 subtype 字段。
	 * 以 SystemMessage(subtype="error") 投递，message/code 进 data。
	 */
	private SystemMessage parseErrorMessage(ONode node) {
		Map<String, Object> data = parseDataMap(node);
		data.remove("type");
		return SystemMessage.of("error", data);
	}

	private ResultMessage parseResultMessage(ONode node) throws MessageParseException {
		// Result messages have data directly in the root node, not nested under "message"
		// soloncode 的 result 事件用 metrics 携带 token/耗时统计（claude 用 usage），两者都兼容。
		ONode usageNode = SdkJson.getField(node, "usage");
		ONode metricsNode = SdkJson.getField(node, "metrics");
		Map<String, Object> usage = parseUsageMap(usageNode != null ? usageNode : metricsNode);

		int durationMs = getIntField(node, "duration_ms", 0);
		if (durationMs == 0 && SdkJson.hasField(metricsNode, "duration_ms")) {
			durationMs = getIntField(metricsNode, "duration_ms", 0);
		}

		// soloncode 的 result 事件不带 subtype，按 is_error 推导，保证语义与 claude 一致。
		boolean isError = getBooleanField(node, "is_error", false);
		String subtype = getStringField(node, "subtype");
		if (subtype == null) {
			subtype = isError ? "error_during_execution" : "success";
		}

		return ResultMessage.builder()
			.subtype(subtype)
			.durationMs(durationMs)
			.durationApiMs(getIntField(node, "duration_api_ms", 0))
			.isError(isError)
			.numTurns(getIntField(node, "num_turns", 1))
			.sessionId(getStringField(node, "session_id"))
			.totalCostUsd(getDoubleField(node, "total_cost_usd"))
			.usage(usage)
			.result(getStringField(node, "result"))
			.structuredOutput(parseStructuredOutput(SdkJson.getField(node, "structured_output")))
			.budgetLimitUsd(getDoubleField(node, "budget_limit_usd"))
			.budgetExceeded(SdkJson.hasField(node, "budget_exceeded")
					? getBooleanField(node, "budget_exceeded", false) : null)
			.build();
	}

	private Object parseStructuredOutput(ONode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		// Convert ONode to native Java types (Map, List, primitives)
		return node.toBean(Object.class);
	}

	private List<ContentBlock> parseContentBlocks(ONode arrayNode) throws MessageParseException {
		List<ContentBlock> blocks = new ArrayList<>();

		for (ONode blockNode : arrayNode.getArray()) {
			String type = getStringField(blockNode, "type");
			if (type == null) {
				throw new MessageParseException("Missing 'type' field in content block");
			}

			ContentBlock block;
			if ("text".equals(type)) {
				block = parseTextBlock(blockNode);
			}
			else if ("tool_use".equals(type)) {
				block = parseToolUseBlock(blockNode);
			}
			else if ("tool_result".equals(type)) {
				block = parseToolResultBlock(blockNode);
			}
			else if ("thinking".equals(type)) {
				block = parseThinkingBlock(blockNode);
			}
			else {
				logger.error("Unrecognized content block type '{}' — skipping", type);
				block = null;
			}

			if (block != null) {
				blocks.add(block);
			}
		}

		return blocks;
	}

	private TextBlock parseTextBlock(ONode node) throws MessageParseException {
		String text = getStringField(node, "text");
		if (text == null) {
			throw new MessageParseException("Missing 'text' field in text block");
		}
		return TextBlock.of(text);
	}

	private ThinkingBlock parseThinkingBlock(ONode node) {
		String thinking = getStringField(node, "thinking");
		String signature = getStringField(node, "signature");
		return ThinkingBlock.of(thinking != null ? thinking : "", signature);
	}

	private ToolUseBlock parseToolUseBlock(ONode node) throws MessageParseException {
		String id = getStringField(node, "id");
		String name = getStringField(node, "name");
		ONode inputNode = SdkJson.getField(node, "input");

		if (id == null || name == null) {
			throw new MessageParseException("Missing required fields in tool_use block");
		}

		Map<String, Object> input = inputNode != null ? parseDataMap(inputNode) : SdkCollections.map();

		return ToolUseBlock.builder().id(id).name(name).input(input).build();
	}

	private ToolResultBlock parseToolResultBlock(ONode node) throws MessageParseException {
		String toolUseId = getStringField(node, "tool_use_id");
		if (toolUseId == null) {
			throw new MessageParseException("Missing 'tool_use_id' field in tool_result block");
		}

		ONode contentNode = SdkJson.getField(node, "content");
		Object content = null;
		if (contentNode != null) {
			if (contentNode.isString()) {
				content = contentNode.getString();
			}
			else if (contentNode.isArray()) {
				content = parseDataList(contentNode);
			}
		}

		Boolean isError = getBooleanField(node, "is_error");

		ToolResultBlock.Builder builder = ToolResultBlock.builder().toolUseId(toolUseId).isError(isError);

		if (content instanceof String) {
			builder.content((String) content);
		}
		else if (content instanceof List) {
			builder.content((List<Map<String, Object>>) content);
		}

		return builder.build();
	}

	private Map<String, Object> parseDataMap(ONode node) {
		Map<String, Object> map = new HashMap<>();
		if (node == null || !node.isObject()) {
			return map;
		}
		for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
			map.put(entry.getKey(), parseJsonValue(entry.getValue()));
		}
		return map;
	}

	private List<Object> parseDataList(ONode node) {
		List<Object> list = new ArrayList<>();
		for (ONode item : node.getArray()) {
			list.add(parseJsonValue(item));
		}
		return list;
	}

	private Object parseJsonValue(ONode node) {
		if (node.isString()) {
			return node.getString();
		}
		else if (node.isNumber()) {
			// 迁移前是 `node.isInt() ? node.asInt() : node.asDouble()`：三目运算符会把 int 提升为
			// double，即所有数字最终都装箱成 Double。这里保持同样的结果类型，否则 usage/metrics
			// 里的整数会由 Double 变成 Integer，改变调用方拿到的 Map 值类型。
			return Double.valueOf(node.getNumber().doubleValue());
		}
		else if (node.isBoolean()) {
			return node.getBoolean();
		}
		else if (node.isArray()) {
			return parseDataList(node);
		}
		else if (node.isObject()) {
			return parseDataMap(node);
		}
		else {
			return null;
		}
	}

	private Map<String, Object> parseUsageMap(ONode node) {
		return node != null ? parseDataMap(node) : SdkCollections.map();
	}

	// Utility methods for safe field extraction
	// 类型判定与迁移前 Jackson 版一致：字段缺失、为 null 或类型不符时返回 null / 默认值。
	// 注意不能直接用 ONode#getString()——它会把数字/布尔/对象也转成字符串，语义比 isTextual() 宽。
	private String getStringField(ONode node, String fieldName) {
		return SdkJson.getStringField(node, fieldName);
	}

	private int getIntField(ONode node, String fieldName, int defaultValue) {
		ONode field = SdkJson.getField(node, fieldName);
		return field != null && field.isNumber() ? field.getInt() : defaultValue;
	}

	private boolean getBooleanField(ONode node, String fieldName, boolean defaultValue) {
		ONode field = SdkJson.getField(node, fieldName);
		return field != null && field.isBoolean() ? field.getBoolean() : defaultValue;
	}

	private Boolean getBooleanField(ONode node, String fieldName) {
		ONode field = SdkJson.getField(node, fieldName);
		return field != null && field.isBoolean() ? field.getBoolean() : null;
	}

	private Double getDoubleField(ONode node, String fieldName) {
		ONode field = SdkJson.getField(node, fieldName);
		return field != null && field.isNumber() ? field.getDouble() : null;
	}

}