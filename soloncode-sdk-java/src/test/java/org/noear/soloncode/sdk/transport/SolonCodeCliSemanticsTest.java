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

package org.noear.soloncode.sdk.transport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.soloncode.sdk.parsing.MessageParser;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住 soloncode CLI 与 claude CLI 的语义差异点。
 *
 * <p>
 * 这些差异都是踩过的坑，回归一次就会导致「SDK 静默拿不到结果」：
 * </p>
 * <ul>
 * <li>提示词必须是 {@code run} 之后的第一个位置参数 —— Solon argx 对选项做贪心 lookahead，
 * 提示词落到任何 {@code --flag} 之后都会被吃成该选项的值。</li>
 * <li>{@code --resume} 与 {@code --session-id} 互斥使用：续接时只发 resume。</li>
 * <li>result 事件用 {@code metrics} 承载 token/耗时统计，而非 claude 的 {@code usage}。</li>
 * </ul>
 */
@DisplayName("soloncode CLI 语义差异")
class SolonCodeCliSemanticsTest {

	@TempDir
	Path tempDir;

	private StdioTransport transport() {
		return new StdioTransport(tempDir, Duration.ofMinutes(5), "/usr/local/bin/soloncode");
	}

	@Nested
	@DisplayName("命令行拼装")
	class CommandLayout {

		@Test
		@DisplayName("提示词紧跟 run，且位于所有 --flag 之前")
		void promptIsFirstPositionalArgument() {
			CLIOptions options = CLIOptions.builder().model("sonnet").maxTurns(3).build();

			List<String> command = transport().buildStreamingCommand(options, "请总结构建失败原因");

			assertThat(command.get(1)).isEqualTo("run");
			assertThat(command.get(2)).describedAs("提示词必须是 run 之后的第一个参数").isEqualTo("请总结构建失败原因");

			int firstFlagIndex = -1;
			for (int i = 0; i < command.size(); i++) {
				if (command.get(i).startsWith("--")) {
					firstFlagIndex = i;
					break;
				}
			}
			assertThat(firstFlagIndex).describedAs("提示词索引必须小于第一个 --flag 的索引").isGreaterThan(2);
		}

		@Test
		@DisplayName("无提示词时不追加位置参数（由 stdin 回退路径提供）")
		void noPositionalWhenPromptIsNull() {
			List<String> command = transport().buildStreamingCommand(CLIOptions.builder().build(), null);

			assertThat(command.get(1)).isEqualTo("run");
			assertThat(command.get(2)).startsWith("--");
		}

		@Test
		@DisplayName("argv 敌对的提示词（含 = 或以 - 开头）必须改走 stdin")
		void argvHostilePromptsGoThroughStdin() {
			// Solon argx 会把含 '=' 的词解析成 key=value、把 '-' 开头的词解析成选项，
			// 两种情况下 CLI 都取不到提示词并以退出码 3 结束。
			assertThat(StdioTransport.needsStdinPrompt("x=1 是什么")).isTrue();
			assertThat(StdioTransport.needsStdinPrompt("--help 是什么")).isTrue();
			assertThat(StdioTransport.needsStdinPrompt("-verbose")).isTrue();

			// 普通提示词（含空格/中文/多行）作为单个 argv 参数是安全的
			assertThat(StdioTransport.needsStdinPrompt("请总结构建失败原因")).isFalse();
			assertThat(StdioTransport.needsStdinPrompt("line1\nline2")).isFalse();
			assertThat(StdioTransport.needsStdinPrompt(null)).isFalse();
		}

		@Test
		@DisplayName("首轮发 --session-id，续接轮只发 --resume")
		void resumeReplacesSessionIdOnLaterTurns() {
			CLIOptions options = CLIOptions.builder().build();

			StdioTransport first = transport();
			first.setTurnSession("sdk-abc123", null);
			List<String> firstTurn = first.buildStreamingCommand(options, "第一轮");
			assertThat(firstTurn).contains("--session-id", "sdk-abc123").doesNotContain("--resume");

			StdioTransport second = transport();
			second.setTurnSession("sdk-abc123", "sdk-abc123");
			List<String> secondTurn = second.buildStreamingCommand(options, "第二轮");
			assertThat(secondTurn).contains("--resume", "sdk-abc123").doesNotContain("--session-id");
		}

		@Test
		@DisplayName("stream-json 必须携带 --verbose")
		void streamJsonAlwaysCarriesVerbose() {
			List<String> command = transport().buildStreamingCommand(CLIOptions.builder().build(), "x");

			assertThat(command).containsSequence("--output-format", "stream-json");
			assertThat(command).contains("--verbose");
		}

		@Test
		@DisplayName("工具规则语法 Bash(rm *) 原样透传给 CLI")
		void toolRuleSyntaxIsPassedThrough() {
			CLIOptions options = CLIOptions.builder()
				.allowedTools(java.util.Arrays.asList("Read", "Bash(git status)"))
				.build();

			List<String> command = transport().buildStreamingCommand(options, "x");

			int index = command.indexOf("--allowedTools");
			assertThat(index).isGreaterThan(0);
			assertThat(command.get(index + 1)).isEqualTo("Read,Bash(git status)");
		}

	}

	@Nested
	@DisplayName("result 事件解析")
	class ResultParsing {

		private final MessageParser parser = new MessageParser();

		@Test
		@DisplayName("metrics 被归一化到 usage，duration_ms 一并回填")
		void metricsIsMappedToUsage() throws Exception {
			String json = "{\"type\":\"result\",\"result\":\"ok\",\"session_id\":\"print-1\",\"is_error\":false,"
					+ "\"metrics\":{\"total_tokens\":120,\"prompt_tokens\":100,\"completion_tokens\":20,"
					+ "\"duration_ms\":13289},\"total_cost_usd\":0.5}";

			Message message = parser.parseMessage(json);

			assertThat(message).isInstanceOf(ResultMessage.class);
			ResultMessage result = (ResultMessage) message;
			assertThat(result.usage()).containsEntry("total_tokens", 120.0).containsEntry("prompt_tokens", 100.0);
			assertThat(result.durationMs()).describedAs("duration_ms 应从 metrics 回填").isEqualTo(13289);
			assertThat(result.totalCostUsd()).isEqualTo(0.5);
		}

		@Test
		@DisplayName("claude 风格的 usage 仍然兼容")
		void claudeStyleUsageStillWorks() throws Exception {
			String json = "{\"type\":\"result\",\"result\":\"ok\",\"session_id\":\"s\",\"duration_ms\":42,"
					+ "\"usage\":{\"input_tokens\":7}}";

			ResultMessage result = (ResultMessage) parser.parseMessage(json);

			assertThat(result.usage()).containsEntry("input_tokens", 7.0);
			assertThat(result.durationMs()).isEqualTo(42);
		}

	}

}
