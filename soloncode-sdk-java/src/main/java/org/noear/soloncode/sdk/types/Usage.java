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

import java.util.Objects;

/**
 * Token usage metrics and analytics. Provides rich behavior for usage analysis and
 * monitoring.
 * <p>
 * The SolonCode API reports input tokens across three categories:
 * <ul>
 * <li>{@code inputTokens} — tokens sent directly (non-cached)</li>
 * <li>{@code cacheCreationInputTokens} — tokens written to prompt cache</li>
 * <li>{@code cacheReadInputTokens} — tokens read from prompt cache</li>
 * </ul>
 * Use {@link #getTotalInputTokens()} for the full input token count.
 */
public final class Usage {

	@ONodeAttr(name = "input_tokens")
	private final int inputTokens;

	@ONodeAttr(name = "output_tokens")
	private final int outputTokens;

	@ONodeAttr(name = "thinking_tokens")
	private final int thinkingTokens;

	@ONodeAttr(name = "cache_creation_input_tokens")
	private final int cacheCreationInputTokens;

	@ONodeAttr(name = "cache_read_input_tokens")
	private final int cacheReadInputTokens;

	public Usage(@ONodeAttr(name = "input_tokens") int inputTokens, @ONodeAttr(name = "output_tokens") int outputTokens,
			@ONodeAttr(name = "thinking_tokens") int thinkingTokens,
			@ONodeAttr(name = "cache_creation_input_tokens") int cacheCreationInputTokens,
			@ONodeAttr(name = "cache_read_input_tokens") int cacheReadInputTokens) {
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.thinkingTokens = thinkingTokens;
		this.cacheCreationInputTokens = cacheCreationInputTokens;
		this.cacheReadInputTokens = cacheReadInputTokens;
	}

	/**
	 * Backward-compatible constructor for callers that don't provide cache token
	 * counts.
	 */
	public Usage(int inputTokens, int outputTokens, int thinkingTokens) {
		this(inputTokens, outputTokens, thinkingTokens, 0, 0);
	}

	public int inputTokens() {
		return inputTokens;
	}

	public int outputTokens() {
		return outputTokens;
	}

	public int thinkingTokens() {
		return thinkingTokens;
	}

	public int cacheCreationInputTokens() {
		return cacheCreationInputTokens;
	}

	public int cacheReadInputTokens() {
		return cacheReadInputTokens;
	}

	/**
	 * Gets the total input tokens including cached tokens. This is the actual number
	 * of input tokens consumed by the API call.
	 */
	public int getTotalInputTokens() {
		return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
	}

	/**
	 * Gets the total number of tokens used (input + output + thinking).
	 */
	public int getTotalTokens() {
		return getTotalInputTokens() + outputTokens + thinkingTokens;
	}

	/**
	 * Gets the compression ratio (output tokens / total input tokens).
	 */
	public double getCompressionRatio() {
		int totalInput = getTotalInputTokens();
		return totalInput > 0 ? (double) outputTokens / totalInput : 0;
	}

	/**
	 * Gets the thinking ratio (thinking tokens / total tokens).
	 */
	public double getThinkingRatio() {
		int total = getTotalTokens();
		return total > 0 ? (double) thinkingTokens / total : 0;
	}

	/**
	 * Checks if usage exceeds the given token limit.
	 */
	public boolean exceedsLimit(int tokenLimit) {
		return getTotalTokens() > tokenLimit;
	}

	/**
	 * Gets the input token percentage of total tokens.
	 */
	public double getInputTokenPercentage() {
		int total = getTotalTokens();
		return total > 0 ? (double) getTotalInputTokens() / total * 100 : 0;
	}

	/**
	 * Gets the output token percentage of total tokens.
	 */
	public double getOutputTokenPercentage() {
		int total = getTotalTokens();
		return total > 0 ? (double) outputTokens / total * 100 : 0;
	}

	/**
	 * Gets the cache hit ratio (cache read tokens / total input tokens).
	 */
	public double getCacheHitRatio() {
		int totalInput = getTotalInputTokens();
		return totalInput > 0 ? (double) cacheReadInputTokens / totalInput : 0;
	}

	/**
	 * Returns true if this is considered a large response (>5000 tokens).
	 */
	public boolean isLargeResponse() {
		return getTotalTokens() > 5000;
	}

	/**
	 * Returns true if thinking tokens are used significantly (>10% of total).
	 */
	public boolean hasSignificantThinking() {
		return getThinkingRatio() > 0.1;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private int inputTokens;

		private int outputTokens;

		private int thinkingTokens;

		private int cacheCreationInputTokens;

		private int cacheReadInputTokens;

		public Builder inputTokens(int inputTokens) {
			this.inputTokens = inputTokens;
			return this;
		}

		public Builder outputTokens(int outputTokens) {
			this.outputTokens = outputTokens;
			return this;
		}

		public Builder thinkingTokens(int thinkingTokens) {
			this.thinkingTokens = thinkingTokens;
			return this;
		}

		public Builder cacheCreationInputTokens(int cacheCreationInputTokens) {
			this.cacheCreationInputTokens = cacheCreationInputTokens;
			return this;
		}

		public Builder cacheReadInputTokens(int cacheReadInputTokens) {
			this.cacheReadInputTokens = cacheReadInputTokens;
			return this;
		}

		public Usage build() {
			return new Usage(inputTokens, outputTokens, thinkingTokens, cacheCreationInputTokens,
					cacheReadInputTokens);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Usage)) {
			return false;
		}
		Usage that = (Usage) o;
		return inputTokens == that.inputTokens && outputTokens == that.outputTokens
				&& thinkingTokens == that.thinkingTokens
				&& cacheCreationInputTokens == that.cacheCreationInputTokens
				&& cacheReadInputTokens == that.cacheReadInputTokens;
	}

	@Override
	public int hashCode() {
		return Objects.hash(inputTokens, outputTokens, thinkingTokens, cacheCreationInputTokens,
				cacheReadInputTokens);
	}

	@Override
	public String toString() {
		return "Usage[inputTokens=" + inputTokens + ", outputTokens=" + outputTokens + ", thinkingTokens="
				+ thinkingTokens + ", cacheCreationInputTokens=" + cacheCreationInputTokens
				+ ", cacheReadInputTokens=" + cacheReadInputTokens + "]";
	}
}
