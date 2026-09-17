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
 * Cost information with calculation methods for pricing logic. Provides rich behavior for
 * cost analysis and reporting.
 */
public final class Cost {

	@ONodeAttr(name = "input_token_cost")
	private final double inputTokenCost;

	@ONodeAttr(name = "output_token_cost")
	private final double outputTokenCost;

	@ONodeAttr(name = "input_tokens")
	private final int inputTokens;

	@ONodeAttr(name = "output_tokens")
	private final int outputTokens;

	@ONodeAttr(name = "model")
	private final String model;

	public Cost(@ONodeAttr(name = "input_token_cost") double inputTokenCost,
			@ONodeAttr(name = "output_token_cost") double outputTokenCost,
			@ONodeAttr(name = "input_tokens") int inputTokens, @ONodeAttr(name = "output_tokens") int outputTokens,
			@ONodeAttr(name = "model") String model) {
		this.inputTokenCost = inputTokenCost;
		this.outputTokenCost = outputTokenCost;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.model = model;
	}

	public double inputTokenCost() {
		return inputTokenCost;
	}

	public double outputTokenCost() {
		return outputTokenCost;
	}

	public int inputTokens() {
		return inputTokens;
	}

	public int outputTokens() {
		return outputTokens;
	}

	public String model() {
		return model;
	}

	/**
	 * Calculates the total cost in USD.
	 */
	public double calculateTotal() {
		return inputTokenCost + outputTokenCost;
	}

	/**
	 * Calculates total cost with markup rate.
	 * @param markupRate markup rate (e.g., 0.15 for 15% markup)
	 */
	public double calculateWithMarkup(double markupRate) {
		return calculateTotal() * (1 + markupRate);
	}

	/**
	 * Calculates cost per token.
	 */
	public double calculatePerToken() {
		int totalTokens = inputTokens + outputTokens;
		return totalTokens > 0 ? calculateTotal() / totalTokens : 0;
	}

	/**
	 * Gets input cost per thousand tokens.
	 */
	public double getInputCostPerThousandTokens() {
		return inputTokens > 0 ? (inputTokenCost / inputTokens) * 1000 : 0;
	}

	/**
	 * Gets output cost per thousand tokens.
	 */
	public double getOutputCostPerThousandTokens() {
		return outputTokens > 0 ? (outputTokenCost / outputTokens) * 1000 : 0;
	}

	/**
	 * Returns true if this is considered an expensive query (>$0.10).
	 */
	public boolean isExpensive() {
		return calculateTotal() > 0.10;
	}

	/**
	 * Returns cost efficiency ratio (output tokens per dollar).
	 */
	public double getEfficiencyRatio() {
		double total = calculateTotal();
		return total > 0 ? outputTokens / total : 0;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private double inputTokenCost;

		private double outputTokenCost;

		private int inputTokens;

		private int outputTokens;

		private String model;

		public Builder inputTokenCost(double inputTokenCost) {
			this.inputTokenCost = inputTokenCost;
			return this;
		}

		public Builder outputTokenCost(double outputTokenCost) {
			this.outputTokenCost = outputTokenCost;
			return this;
		}

		public Builder inputTokens(int inputTokens) {
			this.inputTokens = inputTokens;
			return this;
		}

		public Builder outputTokens(int outputTokens) {
			this.outputTokens = outputTokens;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Cost build() {
			return new Cost(inputTokenCost, outputTokenCost, inputTokens, outputTokens, model);
		}

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Cost)) {
			return false;
		}
		Cost that = (Cost) o;
		return Double.compare(inputTokenCost, that.inputTokenCost) == 0
				&& Double.compare(outputTokenCost, that.outputTokenCost) == 0 && inputTokens == that.inputTokens
				&& outputTokens == that.outputTokens && Objects.equals(model, that.model);
	}

	@Override
	public int hashCode() {
		return Objects.hash(inputTokenCost, outputTokenCost, inputTokens, outputTokens, model);
	}

	@Override
	public String toString() {
		return "Cost[inputTokenCost=" + inputTokenCost + ", outputTokenCost=" + outputTokenCost + ", inputTokens="
				+ inputTokens + ", outputTokens=" + outputTokens + ", model=" + model + "]";
	}
}
