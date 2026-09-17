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

import org.noear.soloncode.sdk.transport.TransportSpec;

/**
 * SDK 的 prompt 风格入口，与 solon-ai 的 {@code ChatModel.prompt(...)} 对齐。
 *
 * <pre>{@code
 * // 阻塞聚合
 * QueryResult result = SolonCode.prompt("写一首俳句").call();
 *
 * // 真流式
 * SolonCode.prompt("解释递归").stream().subscribe(System.out::println);
 * }</pre>
 *
 * <p>默认走 stdio 子进程通道。要走 http 远端通道，从统一 client builder 起：
 * {@code SolonCodeClient.builder().http(url).authToken(t).build().prompt("...").call()}。</p>
 *
 * @see SolonCodeRequestDesc
 * @see Query
 */
public final class SolonCode {

	private SolonCode() {
	}

	/**
	 * 起一次请求描述（stdio 通道）。
	 * @param prompt 提示语；不可为空
	 * @return 请求描述，用 call() / stream() 收束
	 */
	public static SolonCodeRequestDesc prompt(String prompt) {
		return new DefaultSolonCodeRequestDesc(prompt, options -> new DefaultSolonCodeSession(
				options.workingDirectory(), options.toCLIOptions(), options.timeout(),
				TransportSpec.stdio(), null));
	}

}
