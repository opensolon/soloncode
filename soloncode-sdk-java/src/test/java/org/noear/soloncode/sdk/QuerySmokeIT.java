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

package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.QueryResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Query functionality.
 *
 * <p>
 * This test extends {@link SolonCodeCliTestBase} which automatically discovers SolonCode CLI
 * and ensures all tests fail gracefully with a clear message if SolonCode CLI is not
 * available.
 * </p>
 */
class QuerySmokeIT extends SolonCodeCliTestBase {

	/**
	 * bare 模式跳过技能/MCP/记忆的自动发现，配合一次性临时 cwd（见
	 * {@link SolonCodeCliTestBase#itWorkDir()}）让冷启动更快、结果更可复现。
	 * 用仓库目录当 cwd 时 CLI 会注入工作区上下文，提示词膨胀数千 token，
	 * 冒烟用例会因此逼近甚至超过超时上限。
	 */
	private static CLIOptions.Builder smokeOptions() {
		return CLIOptions.builder().timeout(Duration.ofMinutes(3)).bare(true);
	}

	@Test
	void testBasicQuery() throws Exception {
		QueryResult result = Query.execute("What is 1+1?", smokeOptions().build(), itWorkDir());

		assertThat(result).isNotNull();
		assertThat(result.messages()).isNotEmpty();
		assertThat(result.metadata()).isNotNull();
		assertThat(result.isSuccessful()).isTrue();
	}

	@Test
	void testQueryWithOptions() throws Exception {
		// systemPrompt 是 claude 兼容项，soloncode CLI 没有对应 flag，SDK 会告警后忽略。
		// 这里验证配置它不会让查询失败（系统提示词由 CLI 侧工作区配置声明）。
		CLIOptions options = smokeOptions().systemPrompt("You are a helpful math tutor.").build();

		QueryResult result = Query.execute("What is 2+2?", options, itWorkDir());

		assertThat(result).isNotNull();
		assertThat(result.messages()).isNotEmpty();
		assertThat(result.metadata()).isNotNull();
	}

	@Test
	void testQueryResultAnalysis() throws Exception {
		QueryResult result = Query.execute("Hello, world!", smokeOptions().build(), itWorkDir());

		// Test domain-specific methods
		assertThat(result.getMessageCount()).isGreaterThan(0);
		// soloncode 的 assistant 事件是 token 级增量帧，单帧不含完整答案；
		// text() 会跨所有 assistant 消息聚合，因此这里判 text() 而非首帧。
		assertThat(result.text()).isPresent();

		// Test metadata analysis
		assertThat(result.metadata().model()).isNotNull();
		assertThat(result.metadata().getDuration()).isNotNull();
	}

	@Test
	void testSolonCodeCliSanityCheck() throws Exception {
		// Direct zt-exec test: 按 soloncode 的真实调用形态（run <prompt> 在前，flags 在后）。
		String cliPath = getSolonCodeCliPath();

		org.zeroturnaround.exec.ProcessExecutor executor = new org.zeroturnaround.exec.ProcessExecutor()
			.command(cliPath, "run", "What is 2+2?", "--output-format", "json", "--permission-mode",
					"bypassPermissions", "--bare")
			.directory(itWorkDir().toFile())
			.redirectInput(new java.io.ByteArrayInputStream(new byte[0]))
			.environment("SOLONCODE_ENTRYPOINT", "sdk-java")
			.timeout(CLI_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
			.readOutput(true);

		long startTime = System.currentTimeMillis();
		org.zeroturnaround.exec.ProcessResult result = executor.execute();
		long duration = System.currentTimeMillis() - startTime;
			
		// Verify the command completed successfully
		assertThat(result.getExitValue()).isEqualTo(0);
		assertThat(result.outputUTF8()).isNotEmpty();
		
		// Log timing information for debugging
		System.out.printf("SolonCode CLI sanity check completed in %d ms%n", duration);
		System.out.printf("Output length: %d characters%n", result.outputUTF8().length());
		
		// Verify we got JSON output (should contain "result" field)
		assertThat(result.outputUTF8()).contains("\"result\"");
	}

}