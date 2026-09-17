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

import org.noear.soloncode.sdk.util.SdkCollections;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Rich contextual information aggregating Cost and Usage objects. Provides comprehensive
 * analytics and monitoring capabilities.
 */
public final class Metadata {

	@ONodeAttr(name = "model")
	private final String model;

	@ONodeAttr(name = "cost")
	private final Cost cost;

	@ONodeAttr(name = "usage")
	private final Usage usage;

	@ONodeAttr(name = "duration_ms")
	private final long durationMs;

	@ONodeAttr(name = "api_duration_ms")
	private final long apiDurationMs;

	@ONodeAttr(name = "session_id")
	private final String sessionId;

	@ONodeAttr(name = "num_turns")
	private final int numTurns;

	public Metadata(@ONodeAttr(name = "model") String model, @ONodeAttr(name = "cost") Cost cost,
			@ONodeAttr(name = "usage") Usage usage, @ONodeAttr(name = "duration_ms") long durationMs,
			@ONodeAttr(name = "api_duration_ms") long apiDurationMs, @ONodeAttr(name = "session_id") String sessionId,
			@ONodeAttr(name = "num_turns") int numTurns) {
		this.model = model;
		this.cost = cost;
		this.usage = usage;
		this.durationMs = durationMs;
		this.apiDurationMs = apiDurationMs;
		this.sessionId = sessionId;
		this.numTurns = numTurns;
	}

	public String model() {
		return model;
	}

	public Cost cost() {
		return cost;
	}

	public Usage usage() {
		return usage;
	}

	public long durationMs() {
		return durationMs;
	}

	public long apiDurationMs() {
		return apiDurationMs;
	}

	public String sessionId() {
		return sessionId;
	}

	public int numTurns() {
		return numTurns;
	}

	/**
	 * Gets the total duration as a Duration object.
	 */
	public Duration getDuration() {
		return Duration.ofMillis(durationMs);
	}

	/**
	 * Gets the API duration as a Duration object.
	 */
	public Duration getApiDuration() {
		return Duration.ofMillis(apiDurationMs);
	}

	/**
	 * Calculates efficiency score (tokens per millisecond).
	 */
	public double getEfficiencyScore() {
		return durationMs > 0 ? (double) usage.getTotalTokens() / durationMs : 0;
	}

	/**
	 * Calculates API overhead ratio (API time / total time).
	 */
	public double getApiOverheadRatio() {
		return durationMs > 0 ? (double) apiDurationMs / durationMs : 0;
	}

	/**
	 * Returns true if this is considered an expensive query.
	 */
	public boolean isExpensive() {
		return cost.isExpensive();
	}

	/**
	 * Gets tokens per second throughput.
	 */
	public double getTokensPerSecond() {
		return durationMs > 0 ? usage.getTotalTokens() * 1000.0 / durationMs : 0;
	}

	/**
	 * Gets the latency per token in milliseconds.
	 */
	public double getLatencyPerToken() {
		return usage.getTotalTokens() > 0 ? (double) durationMs / usage.getTotalTokens() : 0;
	}

	/**
	 * Returns true if this is a multi-turn conversation.
	 */
	public boolean isMultiTurn() {
		return numTurns > 1;
	}

	/**
	 * Returns true if the response was fast (< 2 seconds).
	 */
	public boolean isFastResponse() {
		return durationMs < 2000;
	}

	/**
	 * Returns a summary map for monitoring/logging systems.
	 */
	public Map<String, Object> toMetricsMap() {
		return SdkCollections.map("model", model, "totalCost", cost.calculateTotal(), "totalTokens",
				usage.getTotalTokens(), "durationMs", durationMs, "apiDurationMs", apiDurationMs, "efficiency",
				getEfficiencyScore(), "tokensPerSecond", getTokensPerSecond(), "numTurns", numTurns, "isExpensive",
				isExpensive(), "isMultiTurn", isMultiTurn());
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String model;

		private Cost cost;

		private Usage usage;

		private long durationMs;

		private long apiDurationMs;

		private String sessionId;

		private int numTurns;

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder cost(Cost cost) {
			this.cost = cost;
			return this;
		}

		public Builder usage(Usage usage) {
			this.usage = usage;
			return this;
		}

		public Builder durationMs(long durationMs) {
			this.durationMs = durationMs;
			return this;
		}

		public Builder apiDurationMs(long apiDurationMs) {
			this.apiDurationMs = apiDurationMs;
			return this;
		}

		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		public Builder numTurns(int numTurns) {
			this.numTurns = numTurns;
			return this;
		}

		public Metadata build() {
			return new Metadata(model, cost, usage, durationMs, apiDurationMs, sessionId, numTurns);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Metadata)) {
			return false;
		}
		Metadata that = (Metadata) o;
		return durationMs == that.durationMs && apiDurationMs == that.apiDurationMs && numTurns == that.numTurns
				&& Objects.equals(model, that.model) && Objects.equals(cost, that.cost)
				&& Objects.equals(usage, that.usage) && Objects.equals(sessionId, that.sessionId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(model, cost, usage, durationMs, apiDurationMs, sessionId, numTurns);
	}

	@Override
	public String toString() {
		return "Metadata[model=" + model + ", cost=" + cost + ", usage=" + usage + ", durationMs=" + durationMs
				+ ", apiDurationMs=" + apiDurationMs + ", sessionId=" + sessionId + ", numTurns=" + numTurns + "]";
	}
}
