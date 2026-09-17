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

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.QueryResult;
import reactor.core.publisher.Flux;

/**
 * 一次请求的描述（提示语 + 选项），用 {@link #call()} 或 {@link #stream()} 收束。
 *
 * <p>命名与 solon-ai 的 {@code ChatModel.prompt(...).call() / .stream()} 对齐：
 * {@code call()} 阻塞到本轮结束、返回聚合结果；{@code stream()} 返回真流式
 * {@link Flux}，消息一到就下发，不等本轮结束。</p>
 *
 * <pre>{@code
 * // 阻塞拿聚合结果（含 metadata / cost / status）
 * QueryResult result = SolonCode.prompt("写一首俳句").call();
 *
 * // 真流式：边生成边消费
 * SolonCode.prompt("解释递归")
 *     .stream()
 *     .filter(m -> m instanceof AssistantMessage)
 *     .subscribe(System.out::println);
 *
 * // 带选项
 * SolonCode.prompt("分析这个模块")
 *     .options(QueryOptions.builder().model("sonnet").build())
 *     .call();
 *
 * // 走 http 通道（通道由 client builder 配）
 * try (SolonCodeClient client = SolonCodeClient.builder()
 *         .http(url).authToken(token).workspace(ws)
 *         .build()) {
 *     client.prompt("分析这个模块").call();
 * }
 * }</pre>
 *
 * <p>与 {@link Query#stream(String)} 的区别：后者是「先跑完再把列表转成 Stream」的伪
 * 流式（保留是为了兼容），本接口的 {@code stream()} 是逐条下发的真流式。</p>
 *
 * @see SolonCode#prompt(String)
 * @see Query
 */
public interface SolonCodeRequestDesc {

	/**
	 * 指定本次请求的选项（模型、超时、工具白名单、工作目录等）。
	 * @param options 选项；null 视为默认
	 * @return this
	 */
	SolonCodeRequestDesc options(QueryOptions options);

	/**
	 * 阻塞执行，返回本轮的聚合结果（消息列表 + metadata + status）。
	 * @return 聚合结果
	 * @throws SolonCodeSDKException 执行失败
	 */
	QueryResult call() throws SolonCodeSDKException;

	/**
	 * 真流式执行：订阅后开始跑，消息逐条下发，本轮 result 事件到达即 complete。
	 *
	 * <p>冷流——每次订阅都会发起一次新的执行。取消订阅会中断本轮并释放通道资源。
	 * 只有下游存在 demand 时才继续从响应迭代器拉取，SDK 缓冲有明确上限；慢消费者
	 * 超过上限会收到错误而不是触发无界内存增长。执行发生在
	 * {@code Schedulers.boundedElastic()} 上，不会占用调用方线程。</p>
	 * @return 消息流
	 */
	Flux<Message> stream();

}
