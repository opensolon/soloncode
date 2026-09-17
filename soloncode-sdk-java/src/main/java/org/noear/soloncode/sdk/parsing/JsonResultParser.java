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
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.snack4.ONode;
import org.noear.snack4.SnackException;
import org.noear.soloncode.sdk.util.SdkJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Parser for SolonCode CLI JSON output format (--output-format json).
 *
 * <p>
 * This parser handles the single JSON object format returned by the SolonCode CLI when using
 * {@code --output-format json}. Unlike the streaming format, this returns a complete
 * result in a single JSON response containing the answer, metadata, usage statistics, and
 * cost information.
 * </p>
 *
 * <p>
 * Example JSON structure:
 * </p>
 * <pre>
 * {
 *   "type": "result",
 *   "subtype": "success",
 *   "is_error": false,
 *   "duration_ms": 2406,
 *   "duration_api_ms": 2153,
 *   "num_turns": 1,
 *   "result": "4",
 *   "session_id": "a61c8133-0f9c-4c47-99f3-24109e1e9711",
 *   "total_cost_usd": 0.0604716,
 *   "usage": {
 *     "input_tokens": 6,
 *     "cache_creation_input_tokens": 14284,
 *     "cache_read_input_tokens": 22662,
 *     "output_tokens": 6,
 *     "server_tool_use": {"web_search_requests": 0},
 *     "service_tier": "standard"
 *   },
 *   "permission_denials": []
 * }
 * </pre>
 */
public class JsonResultParser {

	private static final Logger logger = LoggerFactory.getLogger(JsonResultParser.class);

	public JsonResultParser() {
	}

	/**
	 * Parses a complete JSON result from SolonCode CLI into a ResultMessage.
	 * @param json the complete JSON response from SolonCode CLI
	 * @return a ResultMessage containing the parsed data
	 * @throws MessageParseException if JSON parsing fails or the structure is invalid
	 */
	public ResultMessage parseJsonResult(String json) throws MessageParseException {
		try {
			ONode root = SdkJson.parse(json);
			return parseResultFromNode(root);
		}
		catch (SnackException e) {
			throw MessageParseException.jsonDecodeError(json, e);
		}
	}

	/**
	 * Parses an ONode into a ResultMessage object.
	 */
	private ResultMessage parseResultFromNode(ONode node) throws MessageParseException {
		// Validate this is a result type
		String type = getStringField(node, "type");
		if (!"result".equals(type)) {
			throw new MessageParseException("Expected 'result' type, got: " + type);
		}

		// Extract all fields for ResultMessage
		String subtype = getStringField(node, "subtype");
		int durationMs = getIntField(node, "duration_ms", 0);
		int durationApiMs = getIntField(node, "duration_api_ms", 0);
		boolean isError = getBooleanField(node, "is_error", false);
		int numTurns = getIntField(node, "num_turns", 1);
		String sessionId = getStringField(node, "session_id");
		Double totalCostUsd = getDoubleField(node, "total_cost_usd");
		String result = getStringField(node, "result");

		// Parse usage information
		// soloncode 的 result 事件用 metrics 携带 token/耗时统计（claude 用 usage），两者都兼容。
		ONode usageNode = SdkJson.getField(node, "usage");
		ONode metricsNode = SdkJson.getField(node, "metrics");
		Map<String, Object> usage = parseUsageMap(usageNode != null ? usageNode : metricsNode);

		if (durationMs == 0 && SdkJson.hasField(metricsNode, "duration_ms")) {
			durationMs = getIntField(metricsNode, "duration_ms", 0);
		}

		// Parse structured output (for --json-schema responses)
		Object structuredOutput = parseStructuredOutput(SdkJson.getField(node, "structured_output"));

		// Build and return ResultMessage
		return ResultMessage.builder()
			.subtype(subtype)
			.durationMs(durationMs)
			.durationApiMs(durationApiMs)
			.isError(isError)
			.numTurns(numTurns)
			.sessionId(sessionId)
			.totalCostUsd(totalCostUsd)
			.usage(usage)
			.result(result)
			.structuredOutput(structuredOutput)
			.budgetLimitUsd(getDoubleField(node, "budget_limit_usd"))
			.budgetExceeded(SdkJson.hasField(node, "budget_exceeded")
					? getBooleanField(node, "budget_exceeded", false) : null)
			.build();
	}

	/**
	 * Parses the structured_output node into a native Java object.
	 */
	private Object parseStructuredOutput(ONode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		// Convert ONode to native Java types (Map, List, primitives)
		return node.toBean(Object.class);
	}

	/**
	 * Parses the usage node into a Map structure.
	 */
	private Map<String, Object> parseUsageMap(ONode usageNode) {
		Map<String, Object> usage = new HashMap<>();

		if (usageNode == null || usageNode.isNull() || !usageNode.isObject()) {
			return usage;
		}

		// Parse all usage fields
		// 类型判定与迁移前 Jackson 版逐一对齐：isInt/isDouble/isTextual/isObject，其余落到 toString()。
		for (Map.Entry<String, ONode> entry : usageNode.getObject().entrySet()) {
			String key = entry.getKey();
			ONode value = entry.getValue();

			if (isIntNode(value)) {
				usage.put(key, value.getInt());
			}
			else if (isDoubleNode(value)) {
				usage.put(key, value.getDouble());
			}
			else if (value.isString()) {
				usage.put(key, value.getString());
			}
			else if (value.isObject()) {
				// Handle nested objects like server_tool_use
				Map<String, Object> nestedMap = new HashMap<>();
				for (Map.Entry<String, ONode> nestedEntry : value.getObject().entrySet()) {
					ONode nestedValue = nestedEntry.getValue();
					if (isIntNode(nestedValue)) {
						nestedMap.put(nestedEntry.getKey(), nestedValue.getInt());
					}
					else if (nestedValue.isString()) {
						nestedMap.put(nestedEntry.getKey(), nestedValue.getString());
					}
					else {
						nestedMap.put(nestedEntry.getKey(), nestedValue.toJson());
					}
				}
				usage.put(key, nestedMap);
			}
			else {
				usage.put(key, value.toJson());
			}
		}

		return usage;
	}

	/** 对应 Jackson JsonNode#isInt()：整型且在 int 范围内 */
	private static boolean isIntNode(ONode node) {
		return node.isNumber() && node.getValue() instanceof Integer;
	}

	/** 对应 Jackson JsonNode#isDouble() */
	private static boolean isDoubleNode(ONode node) {
		return node.isNumber() && node.getValue() instanceof Double;
	}

	// 取值语义与迁移前一致：字段缺失或为 null 返回默认值；否则做宽松类型转换。
	// snack4 的 getInt/getDouble 在字符串不可解析时会抛异常，这里兜底成默认值，
	// 对齐 Jackson asInt()/asDouble() 解析失败返回 0 的宽容行为。
	private String getStringField(ONode node, String fieldName) {
		ONode field = SdkJson.getField(node, fieldName);
		return (field != null && !field.isNull()) ? field.getString() : null;
	}

	private int getIntField(ONode node, String fieldName, int defaultValue) {
		ONode field = SdkJson.getField(node, fieldName);
		if (field == null || field.isNull()) {
			return defaultValue;
		}
		try {
			return field.getInt(defaultValue);
		}
		catch (RuntimeException e) {
			return 0;
		}
	}

	private boolean getBooleanField(ONode node, String fieldName, boolean defaultValue) {
		ONode field = SdkJson.getField(node, fieldName);
		if (field == null || field.isNull()) {
			return defaultValue;
		}
		return field.getBoolean(defaultValue);
	}

	private Double getDoubleField(ONode node, String fieldName) {
		ONode field = SdkJson.getField(node, fieldName);
		if (field == null || field.isNull()) {
			return null;
		}
		try {
			return field.getDouble();
		}
		catch (RuntimeException e) {
			return 0D;
		}
	}

}