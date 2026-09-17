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

package org.noear.soloncode.sdk;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.transport.StdioTransport;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实连通性集成测试：对本机安装的 {@code soloncode} 可执行文件跑通完整链路。
 *
 * <p>
 * 与其他 IT 的关键区别：<b>断言只覆盖 SDK 与 CLI 之间的协议契约</b>（进程能起来、
 * stream-json 事件能解析、会话 ID 能贯通、退出码语义正确），<b>不断言模型答案</b>。
 * 这样即使后端模型不可用（限流 / 503 / 未配置 Key），本测试仍然是对 SDK 链路的有效验证 ——
 * 模型故障时 CLI 依旧会输出 {@code system.init} 与 {@code result} 事件。
 * </p>
 *
 * <p>
 * 默认随 failsafe 跳过；本机验证用：{@code mvn -o verify -DskipITs=false -Dit.test=SolonCodeRealCliIT}
 * </p>
 */
@DisplayName("真实 soloncode CLI 连通性")
class SolonCodeRealCliIT extends SolonCodeCliTestBase {

	/** CLI 冷启动（Solon 装配 + 技能/MCP 挂载）比较慢，给足时间。 */
	private static final Duration CLI_TIMEOUT = Duration.ofMinutes(3);

	private static final long AWAIT_SECONDS = 180;

	@Test
	@DisplayName("单轮：能收到 system.init 与 result 事件，且 session 贯通")
	void singleTurnDeliversInitAndResult() throws Exception {
		String sessionId = "sdk-it-" + System.currentTimeMillis();

		CLIOptions options = CLIOptions.builder()
			.timeout(CLI_TIMEOUT)
			.sessionId(sessionId)
			// bare 模式跳过技能/MCP/记忆自动发现，让冷启动更快、更可复现
			.bare(true)
			.maxTurns(1)
			.build();

		List<Message> received = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(1);

		StdioTransport transport = new StdioTransport(itWorkDir(), CLI_TIMEOUT, getSolonCodeCliPath());
		transport.setTurnSession(sessionId, null);
		try {
			transport.startSession("回复两个字：收到", options, parsed -> {
				if (parsed instanceof ParsedMessage.EndOfStream) {
					done.countDown();
					return;
				}
				if (parsed.isRegularMessage()) {
					received.add(parsed.asMessage());
				}
			}, null);

			assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).describedAs("CLI 应在 %ss 内结束本轮", AWAIT_SECONDS)
				.isTrue();
		}
		finally {
			transport.close();
		}

		// 1) system.init：证明进程起来了、参数被 CLI 接受了
		List<SystemMessage> systems = filter(received, SystemMessage.class);
		assertThat(systems).describedAs("必须收到 system 事件").isNotEmpty();
		SystemMessage init = systems.get(0);
		assertThat(init.subtype()).isEqualTo("init");
		assertThat(init.data()).describedAs("init 事件应携带 session_id 与 version").containsKey("session_id");
		assertThat(String.valueOf(init.data().get("session_id"))).describedAs("SDK 传入的 --session-id 必须被 CLI 采用")
			.isEqualTo(sessionId);

		// 2) result：证明 stream-json 尾帧能被解析（模型故障时同样会有此帧）
		List<ResultMessage> results = filter(received, ResultMessage.class);
		assertThat(results).describedAs("必须收到 result 事件").hasSize(1);
		ResultMessage result = results.get(0);
		assertThat(result.sessionId()).isEqualTo(sessionId);
		assertThat(result.result()).describedAs("result 文本不应为空").isNotNull();
		// soloncode 用 metrics 承载 token 统计，SDK 需归一化到 usage
		assertThat(result.usage()).describedAs("metrics/usage 应被解析为非 null 映射").isNotNull();
	}

	@Test
	@DisplayName("多轮：第二轮走 --resume，沿用同一会话 ID")
	void multiTurnReusesSessionViaResume() throws Exception {
		List<String> initSessionIds = new ArrayList<>();

		try (SolonCodeClient client = SolonCodeClient.builder()
			.workingDirectory(itWorkDir())
			.stdio(getSolonCodeCliPath())
			.timeout(CLI_TIMEOUT)
			.bare(true)
			.maxTurns(1)
			.build()) {

			for (String prompt : new String[] { "记住数字 7", "刚才的数字是几？" }) {
				for (Message message : client.prompt(prompt).call().messages()) {
					if (message instanceof SystemMessage && "init".equals(((SystemMessage) message).subtype())) {
						Object sid = ((SystemMessage) message).data().get("session_id");
						if (sid != null) {
							initSessionIds.add(String.valueOf(sid));
						}
					}
				}
			}
		}

		assertThat(initSessionIds).describedAs("两轮都应收到 init 事件").hasSize(2);
		assertThat(initSessionIds.get(1)).describedAs("第二轮必须沿用第一轮的会话 ID（--resume 生效）")
			.isEqualTo(initSessionIds.get(0));
	}

	@Test
	@DisplayName("提示词以 '-' 开头时回退到 stdin 管道，不被当作选项")
	void dashLeadingPromptFallsBackToStdin() throws Exception {
		CLIOptions options = CLIOptions.builder().timeout(CLI_TIMEOUT).bare(true).maxTurns(1).build();

		List<Message> received = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(1);

		StdioTransport transport = new StdioTransport(itWorkDir(), CLI_TIMEOUT, getSolonCodeCliPath());
		try {
			transport.startSession("--这是提示词而不是选项，请回复 OK", options, parsed -> {
				if (parsed instanceof ParsedMessage.EndOfStream) {
					done.countDown();
					return;
				}
				if (parsed.isRegularMessage()) {
					received.add(parsed.asMessage());
				}
			}, null);

			assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			transport.close();
		}

		// 关键点：CLI 没有以退出码 3（未提供提示词）终止，而是正常产出 result 事件
		assertThat(filter(received, ResultMessage.class)).describedAs("stdin 回退路径也必须拿到 result 事件").hasSize(1);
	}

	private static <T extends Message> List<T> filter(List<Message> messages, Class<T> type) {
		List<T> result = new ArrayList<>();
		for (Message message : messages) {
			if (type.isInstance(message)) {
				result.add(type.cast(message));
			}
		}
		return result;
	}

}
