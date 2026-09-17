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

package org.noear.soloncode.sdk.types;

import org.noear.soloncode.sdk.util.SdkJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.util.SdkCollections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for soloncode CLI specific response semantics:
 * <ul>
 * <li>Exit code 4 / budget_exceeded: budget_limit_usd, budget_exceeded, ResultStatus.BUDGET_EXCEEDED</li>
 * <li>structured_output parsing when --json-schema is configured</li>
 * </ul>
 */
@DisplayName("Budget Exceeded and Structured Output Semantics")
class BudgetAndStructuredOutputTest {

	@Test
	@DisplayName("ResultMessage parses budget fields from JSON")
	void budgetFieldsParsed() throws Exception {
		String json = "{" + "\"type\":\"result\",\"subtype\":\"success\",\"duration_ms\":1000,"
				+ "\"duration_api_ms\":800,\"is_error\":false,\"num_turns\":3,\"session_id\":\"s-1\","
				+ "\"total_cost_usd\":0.0312,\"usage\":{},\"result\":\"done\","
				+ "\"budget_limit_usd\":5.0,\"budget_exceeded\":true}";

		ResultMessage rm = SdkJson.toBean(json, ResultMessage.class);
		assertThat(rm.totalCostUsd()).isEqualTo(0.0312);
		assertThat(rm.budgetLimitUsd()).isEqualTo(5.0);
		assertThat(rm.budgetExceeded()).isTrue();
		assertThat(rm.isBudgetExceeded()).isTrue();
	}

	@Test
	@DisplayName("budget fields are null-safe when absent")
	void budgetFieldsAbsent() throws Exception {
		String json = "{\"type\":\"result\",\"subtype\":\"success\",\"duration_ms\":1,\"duration_api_ms\":1,"
				+ "\"is_error\":false,\"num_turns\":1,\"session_id\":\"s-2\",\"result\":\"ok\"}";
		ResultMessage rm = SdkJson.toBean(json, ResultMessage.class);
		assertThat(rm.budgetLimitUsd()).isNull();
		assertThat(rm.budgetExceeded()).isNull();
		assertThat(rm.isBudgetExceeded()).isFalse();
	}

	@Test
	@DisplayName("ResultStatus includes BUDGET_EXCEEDED")
	void resultStatusEnum() {
		assertThat(ResultStatus.valueOf("BUDGET_EXCEEDED")).isEqualTo(ResultStatus.BUDGET_EXCEEDED);
	}

	@Test
	@DisplayName("ResultMessage builder supports budget fields")
	void builderBudgetFields() {
		ResultMessage rm = ResultMessage.builder()
			.subtype("success")
			.result("done")
			.totalCostUsd(1.5)
			.budgetLimitUsd(2.0)
			.budgetExceeded(true)
			.build();
		assertThat(rm.isBudgetExceeded()).isTrue();
		assertThat(rm.budgetLimitUsd()).isEqualTo(2.0);
	}

	@Test
	@DisplayName("ResultMessage parses structured_output field")
	void structuredOutputParsed() throws Exception {
		String json = "{\"type\":\"result\",\"subtype\":\"success\",\"duration_ms\":1,\"duration_api_ms\":1,"
				+ "\"is_error\":false,\"num_turns\":1,\"session_id\":\"s-3\",\"result\":\"```json\\n{}\\n```\","
				+ "\"structured_output\":{\"functions\":[{\"name\":\"foo\",\"signature\":\"public void foo()\"}]}}";

		ResultMessage rm = SdkJson.toBean(json, ResultMessage.class);
		assertThat(rm.hasStructuredOutput()).isTrue();

		java.util.Map<String, Object> asMap = rm.getStructuredOutputAsMap();
		assertThat(asMap).isNotNull();
		assertThat(asMap).containsKey("functions");

		FunctionList typed = rm.getStructuredOutputAs(FunctionList.class);
		assertThat(typed.functions).hasSize(1);
		assertThat(typed.functions.get(0).name).isEqualTo("foo");
	}

	@Test
	@DisplayName("QueryResult exposes structuredOutput and isBudgetExceeded")
	void queryResultConveniences() {
		ResultMessage rm = ResultMessage.builder()
			.subtype("success")
			.result("done")
			.structuredOutput(SdkCollections.map("answer", 42))
			.budgetExceeded(true)
			.budgetLimitUsd(1.0)
			.build();

		QueryResult result = QueryResult.builder()
			.addMessage(rm)
			.metadata(Metadata.builder().sessionId("s-4").build())
			.status(ResultStatus.SUCCESS)
			.build();

		assertThat(result.structuredOutput()).isPresent();
		assertThat(result.structuredOutput().get()).isInstanceOf(java.util.Map.class);
		assertThat(result.isBudgetExceeded()).isTrue();

		QueryResult empty = QueryResult.builder()
			.messages(SdkCollections.list())
			.metadata(Metadata.builder().build())
			.build();
		assertThat(empty.structuredOutput()).isEmpty();
		assertThat(empty.isBudgetExceeded()).isFalse();
	}

	public static class FunctionList {

		public java.util.List<Function> functions;

	}

	public static class Function {

		public String name;

		public String signature;

	}

}
