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
import org.noear.soloncode.sdk.util.PrimitiveSafeCreator;
import org.noear.soloncode.sdk.util.SdkJson;

import java.util.Map;
import java.util.Objects;

/**
 * Result message with cost and usage information. Corresponds to ResultMessage dataclass
 * in Python SDK.
 */
@ONodeAttr(creator = PrimitiveSafeCreator.class)
public final class ResultMessage implements Message {

	@ONodeAttr(name = "subtype")
	private final String subtype;

	@ONodeAttr(name = "duration_ms")
	private final int durationMs;

	@ONodeAttr(name = "duration_api_ms")
	private final int durationApiMs;

	@ONodeAttr(name = "is_error")
	private final boolean isError;

	@ONodeAttr(name = "num_turns")
	private final int numTurns;

	@ONodeAttr(name = "session_id")
	private final String sessionId;

	@ONodeAttr(name = "total_cost_usd")
	private final Double totalCostUsd;

	@ONodeAttr(name = "usage")
	private final Map<String, Object> usage;

	@ONodeAttr(name = "result")
	private final String result;

	@ONodeAttr(name = "structured_output")
	private final Object structuredOutput;

	@ONodeAttr(name = "budget_limit_usd")
	private final Double budgetLimitUsd;

	@ONodeAttr(name = "budget_exceeded")
	private final Boolean budgetExceeded;

	public ResultMessage(@ONodeAttr(name = "subtype") String subtype, @ONodeAttr(name = "duration_ms") int durationMs,
			@ONodeAttr(name = "duration_api_ms") int durationApiMs, @ONodeAttr(name = "is_error") boolean isError,
			@ONodeAttr(name = "num_turns") int numTurns, @ONodeAttr(name = "session_id") String sessionId,
			@ONodeAttr(name = "total_cost_usd") Double totalCostUsd, @ONodeAttr(name = "usage") Map<String, Object> usage,
			@ONodeAttr(name = "result") String result, @ONodeAttr(name = "structured_output") Object structuredOutput,
			@ONodeAttr(name = "budget_limit_usd") Double budgetLimitUsd,
			@ONodeAttr(name = "budget_exceeded") Boolean budgetExceeded) {
		this.subtype = subtype;
		this.durationMs = durationMs;
		this.durationApiMs = durationApiMs;
		this.isError = isError;
		this.numTurns = numTurns;
		this.sessionId = sessionId;
		this.totalCostUsd = totalCostUsd;
		this.usage = usage;
		this.result = result;
		this.structuredOutput = structuredOutput;
		this.budgetLimitUsd = budgetLimitUsd;
		this.budgetExceeded = budgetExceeded;
	}

	public String subtype() {
		return subtype;
	}

	public int durationMs() {
		return durationMs;
	}

	public int durationApiMs() {
		return durationApiMs;
	}

	public boolean isError() {
		return isError;
	}

	public int numTurns() {
		return numTurns;
	}

	public String sessionId() {
		return sessionId;
	}

	public Double totalCostUsd() {
		return totalCostUsd;
	}

	public Map<String, Object> usage() {
		return usage;
	}

	public String result() {
		return result;
	}

	public Object structuredOutput() {
		return structuredOutput;
	}

	public Double budgetLimitUsd() {
		return budgetLimitUsd;
	}

	public Boolean budgetExceeded() {
		return budgetExceeded;
	}

	public boolean isBudgetExceeded() {
		return budgetExceeded != null && budgetExceeded.booleanValue();
	}

	@Override
	public String getType() {
		return "result";
	}

	@Override
	public boolean isTerminal() {
		return true;
	}

	@Override
	public String toString() {
		String cost = totalCostUsd != null ? String.format("$%.6f", totalCostUsd) : "n/a";
		return String.format("[Result: cost=%s, turns=%d, session=%s]", cost, numTurns, sessionId);
	}

	/**
	 * Converts this result message to a rich Metadata object. Extracts usage and cost
	 * information to create domain objects.
	 */
	public Metadata toMetadata(String model) {
		// Extract usage information
		Usage usageObj = extractUsage();

		// Extract cost information
		Cost costObj = extractCost(model, usageObj);

		return Metadata.builder()
			.model(model)
			.cost(costObj)
			.usage(usageObj)
			.durationMs(durationMs)
			.apiDurationMs(durationApiMs)
			.sessionId(sessionId)
			.numTurns(numTurns)
			.build();
	}

	private Usage extractUsage() {
		if (usage == null) {
			return new Usage(0, 0, 0);
		}

		int inputTokens = getIntFromUsage("input_tokens", getIntFromUsage("prompt_tokens", 0));
		int outputTokens = getIntFromUsage("output_tokens", getIntFromUsage("completion_tokens", 0));
		int thinkingTokens = getIntFromUsage("thinking_tokens", 0);
		int cacheCreationInputTokens = getIntFromUsage("cache_creation_input_tokens", 0);
		int cacheReadInputTokens = getIntFromUsage("cache_read_input_tokens", 0);

		return new Usage(inputTokens, outputTokens, thinkingTokens, cacheCreationInputTokens, cacheReadInputTokens);
	}

	private Cost extractCost(String model, Usage usageObj) {
		double totalCost = totalCostUsd != null ? totalCostUsd : 0.0;

		// Estimate input/output cost breakdown if total cost is available
		// This is an approximation - real implementation would use actual pricing
		double inputCost = totalCost * 0.4; // Rough estimate
		double outputCost = totalCost * 0.6; // Rough estimate

		return Cost.builder()
			.inputTokenCost(inputCost)
			.outputTokenCost(outputCost)
			.inputTokens(usageObj.inputTokens())
			.outputTokens(usageObj.outputTokens())
			.model(model)
			.build();
	}

	private int getIntFromUsage(String key, int defaultValue) {
		if (usage == null)
			return defaultValue;
		Object value = usage.get(key);
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		return defaultValue;
	}

	/**
	 * Gets the structured output as a typed object.
	 * @param <T> the target type
	 * @param type the class of the target type
	 * @return the structured output as the target type, or null if not present
	 */
	public <T> T getStructuredOutputAs(Class<T> type) {
		if (structuredOutput == null) {
			return null;
		}
		return SdkJson.convert(structuredOutput, type);
	}

	/**
	 * Gets the structured output as a Map.
	 * @return the structured output as a Map, or null if not present or not a Map
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> getStructuredOutputAsMap() {
		if (structuredOutput instanceof Map) {
			return (Map<String, Object>) structuredOutput;
		}
		return null;
	}

	/**
	 * Checks if structured output is present.
	 * @return true if structured output is present, false otherwise
	 */
	public boolean hasStructuredOutput() {
		return structuredOutput != null;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String subtype;

		private int durationMs;

		private int durationApiMs;

		private boolean isError;

		private int numTurns;

		private String sessionId;

		private Double totalCostUsd;

		private Map<String, Object> usage;

		private String result;

		private Object structuredOutput;

		private Double budgetLimitUsd;

		private Boolean budgetExceeded;

		public Builder subtype(String subtype) {
			this.subtype = subtype;
			return this;
		}

		public Builder durationMs(int durationMs) {
			this.durationMs = durationMs;
			return this;
		}

		public Builder durationApiMs(int durationApiMs) {
			this.durationApiMs = durationApiMs;
			return this;
		}

		public Builder isError(boolean isError) {
			this.isError = isError;
			return this;
		}

		public Builder numTurns(int numTurns) {
			this.numTurns = numTurns;
			return this;
		}

		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		public Builder totalCostUsd(Double totalCostUsd) {
			this.totalCostUsd = totalCostUsd;
			return this;
		}

		public Builder usage(Map<String, Object> usage) {
			this.usage = usage;
			return this;
		}

		public Builder result(String result) {
			this.result = result;
			return this;
		}

		public Builder structuredOutput(Object structuredOutput) {
			this.structuredOutput = structuredOutput;
			return this;
		}

		public Builder budgetLimitUsd(Double budgetLimitUsd) {
			this.budgetLimitUsd = budgetLimitUsd;
			return this;
		}

		public Builder budgetExceeded(Boolean budgetExceeded) {
			this.budgetExceeded = budgetExceeded;
			return this;
		}

		public ResultMessage build() {
			return new ResultMessage(subtype, durationMs, durationApiMs, isError, numTurns, sessionId, totalCostUsd,
					usage, result, structuredOutput, budgetLimitUsd, budgetExceeded);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ResultMessage)) {
			return false;
		}
		ResultMessage that = (ResultMessage) o;
		return durationMs == that.durationMs && durationApiMs == that.durationApiMs && isError == that.isError
				&& numTurns == that.numTurns && Objects.equals(subtype, that.subtype)
				&& Objects.equals(sessionId, that.sessionId) && Objects.equals(totalCostUsd, that.totalCostUsd)
				&& Objects.equals(usage, that.usage) && Objects.equals(result, that.result)
				&& Objects.equals(structuredOutput, that.structuredOutput)
				&& Objects.equals(budgetLimitUsd, that.budgetLimitUsd)
				&& Objects.equals(budgetExceeded, that.budgetExceeded);
	}

	@Override
	public int hashCode() {
		return Objects.hash(subtype, durationMs, durationApiMs, isError, numTurns, sessionId, totalCostUsd, usage,
				result, structuredOutput, budgetLimitUsd, budgetExceeded);
	}
}
