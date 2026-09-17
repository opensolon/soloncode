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

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * 与 soloncode 无头执行端（{@code soloncode stream} / {@code soloncode run}）通讯的传输层契约。
 *
 * <p>默认 stdio 使用常驻 {@code stream}：一个进程承载多轮，user/result 分别是轮次开始/结束边界。
 * 显式 one-shot stdio 与 HTTP 仍是一轮一个进程/请求，并通过 session/resume 串接。</p>
 *
 * <h2>实现</h2>
 * <ul>
 * <li>{@link StdioTransport} — 在本机拉起 {@code soloncode stream}（默认）或
 * {@code soloncode run}（兼容模式）子进程。</li>
 * <li>HTTP 实现（规划中）— 把同一组选项投递到服务端的 {@code /web/run} 端点，
 * 用 SSE / NDJSON 逐行接收同构事件流。见 {@code docs/run-headless-mode-http.md}。</li>
 * </ul>
 *
 * <h2>状态机</h2>
 * <p>五态单向流转，不可回退、不可复用：
 * {@code DISCONNECTED → CONNECTING → CONNECTED → CLOSING → CLOSED}。
 * 已 {@code CLOSED} 的实例再次 {@link #startSession} 必须抛
 * {@link IllegalStateException}。</p>
 *
 * <h2>一次性语义</h2>
 * <p>{@code soloncode run} 不是长驻的双向通道：首轮执行完成后
 * {@link #sendUserMessage(String, String)} 应抛 {@link org.noear.soloncode.sdk.exceptions.TransportException}
 * 而不是静默丢弃，提示调用方改用客户端层的多轮 API。</p>
 *
 * @see StdioTransport
 * @see TransportSpec
 */
public interface Transport extends AutoCloseable {

	// ============================================================
	// State Machine Constants
	// ============================================================

	/** 已创建，尚未连接 */
	int STATE_DISCONNECTED = 0;

	/** 连接中 */
	int STATE_CONNECTING = 1;

	/** 已连接，可通讯 */
	int STATE_CONNECTED = 2;

	/** 正在优雅关闭 */
	int STATE_CLOSING = 3;

	/** 已完全关闭 */
	int STATE_CLOSED = 4;

	// ============================================================
	// Session Lifecycle
	// ============================================================

	/**
	 * 设置本轮执行的会话语境。必须在 {@link #startSession} 之前调用。
	 * @param sessionId 首轮固定的会话 ID（传 null 则用 options 中的值）
	 * @param resume 需要续接的会话 ID（传 null 表示首轮）
	 */
	void setTurnSession(String sessionId, String resume);

	/**
	 * 启动一轮执行。
	 * @param prompt 提示词
	 * @param options 执行选项
	 * @param messageHandler 常规消息处理器；流结束时会收到
	 * {@link ParsedMessage.EndOfStream#INSTANCE}
	 * @param controlRequestHandler 控制请求处理器，返回响应
	 * @throws SolonCodeSDKException 启动失败
	 */
	default void startSession(String prompt, CLIOptions options, Consumer<ParsedMessage> messageHandler,
			ControlRequestHandler controlRequestHandler) throws SolonCodeSDKException {
		startSession(prompt, options, messageHandler, controlRequestHandler, null);
	}

	/**
	 * 启动一轮执行。
	 * @param prompt 提示词
	 * @param options 执行选项
	 * @param messageHandler 常规消息处理器
	 * @param controlRequestHandler 控制请求处理器，返回响应
	 * @param controlResponseHandler 我方发出的控制请求的响应处理器（可为 null）
	 * @throws SolonCodeSDKException 启动失败
	 */
	void startSession(String prompt, CLIOptions options, Consumer<ParsedMessage> messageHandler,
			ControlRequestHandler controlRequestHandler, Consumer<ControlResponse> controlResponseHandler)
			throws SolonCodeSDKException;

	/**
	 * 等待本轮执行结束。
	 * @param timeout 最长等待时间
	 * @return true 表示在超时前结束
	 * @throws SolonCodeSDKException 执行以失败告终
	 */
	boolean waitForCompletion(Duration timeout) throws SolonCodeSDKException;

	/** 中断本轮执行。 */
	void interrupt();

	/** 优雅关闭；返回的 Mono 在关闭完成时结束。 */
	Mono<Void> closeGracefully();

	@Override
	void close();

	// ============================================================
	// Sending
	// ============================================================

	/**
	 * 向执行端投递用户消息。
	 *
	 * <p>常驻 stream 下可在首轮之后继续投递；one-shot 下调用会失败。</p>
	 * @param content 消息内容
	 * @param sid 会话 ID
	 * @throws SolonCodeSDKException 投递失败，或通道已不接受新消息
	 */
	void sendUserMessage(String content, String sid) throws SolonCodeSDKException;

	/**
	 * 发送原始消息（JSON 行）。
	 * @param message 消息内容
	 * @throws SolonCodeSDKException 发送失败
	 */
	void sendMessage(String message) throws SolonCodeSDKException;

	/**
	 * 回送控制响应。
	 * @param response 响应
	 * @throws SolonCodeSDKException 发送失败
	 */
	void sendResponse(ControlResponse response) throws SolonCodeSDKException;

	// ============================================================
	// Receiving
	// ============================================================

	/** 入站消息流（响应式 API）。 */
	Flux<ParsedMessage> receiveMessages();

	/** 入站消息迭代器（非响应式消费者）。 */
	default Iterator<ParsedMessage> messageIterator() {
		return receiveMessages().toIterable().iterator();
	}

	/** 入站消息 Iterable，便于 for-each。 */
	default Iterable<ParsedMessage> messageIterable() {
		return receiveMessages().toIterable();
	}

	// ============================================================
	// Status
	// ============================================================

	/** 当前状态值，取 {@code STATE_*} 常量之一。 */
	int getState();

	/** 当前状态名，用于日志与诊断。 */
	String getStateName();

	/** 本轮执行是否仍在进行。 */
	boolean isRunning();

	/** 本轮执行中发生的错误，无错误时返回 null。 */
	Throwable getSessionError();

	/** 执行端分配的会话 ID，未分配时返回 null。 */
	String getSessionId();

	// ============================================================
	// Functional Interface
	// ============================================================

	/**
	 * 控制请求处理器。
	 */
	@FunctionalInterface
	interface ControlRequestHandler {

		/**
		 * 处理一个控制请求并返回响应。
		 * @param request 来自执行端的控制请求
		 * @return 要回送的响应
		 */
		ControlResponse handle(ControlRequest request);

	}

}
