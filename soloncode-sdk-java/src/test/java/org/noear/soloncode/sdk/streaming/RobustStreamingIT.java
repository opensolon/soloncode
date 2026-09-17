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

package org.noear.soloncode.sdk.streaming;

import org.noear.soloncode.sdk.config.OutputFormat;
import org.noear.soloncode.sdk.parsing.RobustStreamParser;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;
import org.noear.soloncode.sdk.types.TextBlock;
import org.noear.soloncode.sdk.util.SdkCollections;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for robust streaming implementation (Phase 4.8).
 *
 * <p>
 * These tests validate that our enhanced streaming components:
 * </p>
 * <ul>
 * <li>Parse stream-json correctly with character-based accumulation</li>
 * <li>Validate message flow matches expected protocol</li>
 * <li>Handle edge cases and error scenarios gracefully</li>
 * <li>Provide comprehensive diagnostics and monitoring</li>
 * </ul>
 */
class RobustStreamingIT extends SolonCodeCliTestBase {

	private static final Logger logger = LoggerFactory.getLogger(RobustStreamingIT.class);

	@Test
	void testRobustStreamingBasicFlow() throws Exception {
		String prompt = "What is 2+2? Be concise.";
		List<Message> messages = new ArrayList<>();
		Instant startTime = Instant.now();

		logger.info("Testing robust streaming with prompt: {}", prompt);

		// Create robust streaming processor
		RobustStreamingProcessor processor = new RobustStreamingProcessor(messages::add, OutputFormat.STREAM_JSON);

		// soloncode 的调用形态：stream-json 必须配 --verbose。提示词含 '.' 时不能作为 argv
		// 位置参数（旧版 Solon syncArgsToSys 会对含点的 key 取 null 值而 NPE），改走 stdin，
		// 与 SDK StdioTransport.needsStdinPrompt() 的生产路径保持一致。
		// --bare + 一次性临时 cwd：避免 CLI 注入仓库工作区上下文（提示词膨胀、MCP 冷启动），
		// 否则本用例会逼近 CLI_TIMEOUT_SECONDS。
		ProcessResult result = new ProcessExecutor()
			.command(getSolonCodeCliPath(), "run", "--output-format", "stream-json", "--verbose", "--bare")
			.directory(itWorkDir().toFile())
			.redirectOutput(processor)
			.redirectInput(new ByteArrayInputStream(prompt.getBytes(StandardCharsets.UTF_8)))
			.environment("SOLONCODE_ENTRYPOINT", "sdk-java")
			.timeout(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.execute();

		Duration duration = Duration.between(startTime, Instant.now());
		processor.close();

		// Validate process success
		assertThat(result.getExitValue()).isEqualTo(0);

		// Validate message flow: SystemMessage -> AssistantMessage(s) -> ResultMessage
		assertThat(messages).hasSizeGreaterThanOrEqualTo(3);
		assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);

		// Find assistant and result messages
		List<AssistantMessage> assistantMessages = messages.stream()
			.filter(msg -> msg instanceof AssistantMessage)
			.map(msg -> (AssistantMessage) msg)
			.collect(java.util.stream.Collectors.toList());

		List<ResultMessage> resultMessages = messages.stream()
			.filter(msg -> msg instanceof ResultMessage)
			.map(msg -> (ResultMessage) msg)
			.collect(java.util.stream.Collectors.toList());

		assertThat(assistantMessages).isNotEmpty();
		assertThat(resultMessages).hasSize(1);

		// Validate system message
		SystemMessage systemMessage = (SystemMessage) messages.get(0);
		assertThat(systemMessage.subtype()).isEqualTo("init");
		assertThat(systemMessage.data()).containsKey("session_id");

		// soloncode 的 assistant 事件是 token 级增量帧，单帧不含完整答案，须聚合后判断。
		String aggregatedText = aggregateText(assistantMessages);
		assertThat(aggregatedText).isNotEmpty();

		// Validate result message（result 事件不带 subtype，SDK 按 is_error 推导）
		ResultMessage resultMessage = resultMessages.get(0);
		assertThat(resultMessage.subtype()).isEqualTo("success");
		assertThat(resultMessage.isError()).isFalse();
		assertThat(resultMessage.sessionId()).isNotNull();
		assertThat(resultMessage.result()).isNotEmpty();

		// Get processor statistics
		RobustStreamingProcessor.StreamingStatistics stats = processor.getStatistics();
		assertThat(stats.messagesEmitted()).isEqualTo(messages.size());
		assertThat(stats.errors()).isEqualTo(0);

		logger.info("Robust streaming test completed successfully:");
		logger.info("  Duration: {} ms", duration.toMillis());
		logger.info("  Messages: {} (system + {} assistant deltas + result)", messages.size(),
				assistantMessages.size());
		logger.info("  Aggregated text length: {}", aggregatedText.length());
		logger.info("  Processor stats: {} lines, {} messages, {} errors", stats.linesProcessed(),
				stats.messagesEmitted(), stats.errors());
	}

	private static String aggregateText(List<AssistantMessage> assistantMessages) {
		StringBuilder sb = new StringBuilder();
		for (AssistantMessage am : assistantMessages) {
			am.getTextContent().ifPresent(sb::append);
		}
		return sb.toString();
	}

	@Test
	void testRobustStreamingMultiplePrompts() throws Exception {
		String[] prompts = { "What is 3+3?", "List 2 colors.", "Say hello." };

		for (int i = 0; i < prompts.length; i++) {
			String prompt = prompts[i];
			logger.info("Testing prompt {}/{}: {}", i + 1, prompts.length, prompt);

			List<Message> messages = new ArrayList<>();
			RobustStreamingProcessor processor = new RobustStreamingProcessor(messages::add, OutputFormat.STREAM_JSON);

			ProcessResult result = new ProcessExecutor()
				.command(getSolonCodeCliPath(), "run", "--output-format", "stream-json", "--verbose", "--bare")
				.directory(itWorkDir().toFile())
				.redirectOutput(processor)
				.redirectInput(new ByteArrayInputStream(prompt.getBytes(StandardCharsets.UTF_8)))
				.environment("SOLONCODE_ENTRYPOINT", "sdk-java")
				.timeout(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.execute();

			processor.close();

			// Validate each test
			assertThat(result.getExitValue()).isEqualTo(0);
			assertThat(messages).hasSizeGreaterThanOrEqualTo(3);

			// Validate message types
			assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
			assertThat(messages.stream().anyMatch(msg -> msg instanceof AssistantMessage)).isTrue();
			assertThat(messages.stream().anyMatch(msg -> msg instanceof ResultMessage)).isTrue();

			logger.info("  Prompt {} completed: {} messages", i + 1, messages.size());
		}
	}

	@Test
	void testStreamingErrorRecovery() throws Exception {
		// Test that streaming processor can handle malformed input gracefully
		List<Message> messages = new ArrayList<>();
		RobustStreamingProcessor processor = new RobustStreamingProcessor(messages::add, OutputFormat.STREAM_JSON);

		// Simulate individual error scenarios that should be handled gracefully
		processor.processLine("invalid line that should be ignored"); // Non-JSON line -
																		// should be
																		// ignored
		processor.close(); // Close to flush any incomplete buffer

		// Create new processor for the valid message test
		messages.clear();
		RobustStreamingProcessor processor2 = new RobustStreamingProcessor(messages::add, OutputFormat.STREAM_JSON);

		processor2.processLine("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"test123\"}"); // Valid
																											// message
		processor2.close();

		RobustStreamingProcessor.StreamingStatistics stats = processor2.getStatistics();

		// Should have processed the valid message line
		assertThat(stats.linesProcessed()).isEqualTo(1);
		// Should have parsed the valid system message
		assertThat(stats.messagesEmitted()).isGreaterThanOrEqualTo(1);

		// Verify message was actually added to the list
		assertThat(messages).isNotEmpty();

		logger.info("Error recovery test completed:");
		logger.info("  Lines processed: {}", stats.linesProcessed());
		logger.info("  Messages emitted: {}", stats.messagesEmitted());
		logger.info("  Errors handled: {}", stats.errors());
	}

	@Test
	void testParserBufferManagement() throws Exception {
		// Test that the robust parser properly manages large inputs
		RobustStreamParser parser = new RobustStreamParser();

		// Test normal accumulation with valid SolonCode message structure
		assertThat(parser.accumulateAndParse("{\"type\":\"system\",\"subtype\":")).isEmpty();
		assertThat(parser.accumulateAndParse("\"init\",\"session_id\":\"test123\"}")).isPresent();

		// Test buffer limits (should handle gracefully)
		StringBuilder largeJson = new StringBuilder(
				"{\"type\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"");
		for (int i = 0; i < 100000; i++) {
			largeJson.append("x");
		}
		largeJson.append("\"}]}");

		// This should either parse successfully or fail gracefully due to buffer limits
		try {
			parser.accumulateAndParse(largeJson.toString());
		}
		catch (Exception e) {
			// Buffer limit handling is expected for very large inputs
			logger.info("Large input handled with: {}", e.getClass().getSimpleName());
		}

		RobustStreamParser.ParsingStats stats = parser.getStats();
		assertThat(stats.parseAttempts()).isGreaterThan(0);

		logger.info("Buffer management test completed:");
		logger.info("  Parse attempts: {}", stats.parseAttempts());
		logger.info("  Successful parses: {}", stats.successfulParses());
		logger.info("  Success rate: {:.2f}%", stats.getSuccessRate() * 100);
	}

	@Test
	void testStreamingStateMachine() throws Exception {
		// Test that state machine properly validates message flow
		StreamingStateMachine stateMachine = new StreamingStateMachine();

		// Create mock messages in proper order
		SystemMessage systemMsg = SystemMessage.of("init", SdkCollections.map("session_id", "test123"));

		AssistantMessage assistantMsg = AssistantMessage.of(SdkCollections.list(new TextBlock("Test response")));

		ResultMessage resultMsg = ResultMessage.builder()
			.subtype("success")
			.sessionId("test123")
			.isError(false)
			.numTurns(1)
			.durationMs(1000)
			.durationApiMs(800)
			.result("Test response")
			.build();

		// Process messages in correct order
		stateMachine.processMessage(systemMsg);
		assertThat(stateMachine.getCurrentState()).isEqualTo(StreamingStateMachine.State.AWAITING_CONTENT);

		stateMachine.processMessage(assistantMsg);
		assertThat(stateMachine.getCurrentState()).isEqualTo(StreamingStateMachine.State.AWAITING_CONTENT);

		stateMachine.processMessage(resultMsg);
		assertThat(stateMachine.getCurrentState()).isEqualTo(StreamingStateMachine.State.COMPLETED);
		assertThat(stateMachine.isComplete()).isTrue();

		// Validate completion
		StreamingStateMachine.StreamCompletionSummary summary = stateMachine.validateCompletion();
		assertThat(summary.totalMessages()).isEqualTo(3);
		assertThat(summary.sessionId()).isEqualTo("test123");
		assertThat(summary.hasAssistantResponse()).isTrue();

		logger.info("State machine test completed:");
		logger.info("  Final state: {}", stateMachine.getCurrentState());
		logger.info("  Total messages: {}", summary.totalMessages());
		logger.info("  Session ID: {}", summary.sessionId());
	}

}