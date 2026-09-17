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

import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.QueryResult;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.ResultStatus;
import org.noear.soloncode.sdk.types.TextBlock;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * call() / stream() 两个收束接口：验证聚合语义、真流式（逐条下发而非跑完再发）、
 * 取消传播、异常包装与客户端资源释放。
 *
 * <p>不依赖真实 CLI：通过包内可见的 ClientFactory 注入 mock 客户端。</p>
 */
class SolonCodeRequestDescTest {

	private static ParsedMessage assistant(String text) {
		return ParsedMessage.RegularMessage
			.of(AssistantMessage.of(Arrays.<org.noear.soloncode.sdk.types.ContentBlock>asList(TextBlock.of(text))));
	}

	private static ParsedMessage result(String text) {
		return ParsedMessage.RegularMessage
			.of(ResultMessage.builder().subtype("success").result(text).build());
	}

	/** 建一个返回给定消息序列的 mock 客户端 */
	private static SolonCodeSession clientOf(List<ParsedMessage> messages) throws Exception {
		SolonCodeSession client = mock(SolonCodeSession.class);
		when(client.receiveResponse()).thenReturn(messages.iterator());
		return client;
	}

	// ---------- call()：阻塞聚合 ----------

	@Test
	void callAggregatesMessagesIntoQueryResult() throws Exception {
		SolonCodeSession client = clientOf(
				Arrays.asList(assistant("思考中"), assistant("答案是 4"), result("答案是 4")));

		QueryResult qr = new DefaultSolonCodeRequestDesc("2+2=?", options -> client).call();

		assertThat(qr.messages()).hasSize(3);
		assertThat(qr.status()).isEqualTo(ResultStatus.SUCCESS);
		assertThat(qr.metadata()).isNotNull();
	}

	@Test
	void callSendsPromptAndClosesClient() throws Exception {
		SolonCodeSession client = clientOf(Arrays.asList(result("ok")));

		new DefaultSolonCodeRequestDesc("hello", options -> client).call();

		verify(client).connect("hello");
		verify(client).close();
	}

	@Test
	void callPassesOptionsToClientFactory() throws Exception {
		SolonCodeSession client = clientOf(Arrays.asList(result("ok")));
		List<QueryOptions> seen = new ArrayList<>();
		QueryOptions custom = QueryOptions.builder().model("sonnet").build();

		new DefaultSolonCodeRequestDesc("hi", options -> {
			seen.add(options);
			return client;
		}).options(custom).call();

		assertThat(seen).containsExactly(custom);
	}

	@Test
	void nullOptionsFallsBackToDefaults() throws Exception {
		SolonCodeSession client = clientOf(Arrays.asList(result("ok")));
		List<QueryOptions> seen = new ArrayList<>();

		new DefaultSolonCodeRequestDesc("hi", options -> {
			seen.add(options);
			return client;
		}).options(null).call();

		assertThat(seen).containsExactly(QueryOptions.defaults());
	}

	@Test
	void callWrapsUnexpectedExceptionAndStillClosesClient() throws Exception {
		SolonCodeSession client = mock(SolonCodeSession.class);
		doThrow(new RuntimeException("boom")).when(client).connect(anyString());

		assertThatThrownBy(() -> new DefaultSolonCodeRequestDesc("hi", options -> client).call())
			.isInstanceOf(SolonCodeSDKException.class)
			.hasMessageContaining("Failed to execute request")
			.hasRootCauseMessage("boom");

		verify(client).close();
	}

	@Test
	void callPropagatesSdkExceptionUnwrapped() throws Exception {
		SolonCodeSession client = mock(SolonCodeSession.class);
		doThrow(new SolonCodeSDKException("cli not found")).when(client).connect(anyString());

		assertThatThrownBy(() -> new DefaultSolonCodeRequestDesc("hi", options -> client).call())
			.isInstanceOf(SolonCodeSDKException.class)
			.hasMessage("cli not found");
	}

	// ---------- stream()：真流式 ----------

	@Test
	void streamEmitsMessagesInOrderThenCompletes() throws Exception {
		SolonCodeSession client = clientOf(Arrays.asList(assistant("a"), assistant("b"), result("done")));

		Flux<Message> flux = new DefaultSolonCodeRequestDesc("hi", options -> client).stream();

		StepVerifier.create(flux).expectNextCount(3).verifyComplete();
		verify(client).close();
	}

	@Test
	void streamIsTrulyIncrementalNotCollectThenEmit() throws Exception {
		// 第 2 条消息在「订阅方已收到第 1 条」之前不生产：若实现是先跑完再下发，这里会死锁超时
		CountDownLatch firstDelivered = new CountDownLatch(1);
		AtomicInteger produced = new AtomicInteger();

		Iterator<ParsedMessage> lazy = new Iterator<ParsedMessage>() {
			@Override
			public boolean hasNext() {
				return produced.get() < 2;
			}

			@Override
			public ParsedMessage next() {
				int index = produced.getAndIncrement();
				if (index == 1) {
					try {
						// 等下游确认收到第 1 条
						if (!firstDelivered.await(5, TimeUnit.SECONDS)) {
							throw new IllegalStateException("first message was not delivered incrementally");
						}
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(e);
					}
				}
				return index == 0 ? assistant("first") : result("second");
			}
		};

		SolonCodeSession client = mock(SolonCodeSession.class);
		when(client.receiveResponse()).thenReturn(lazy);

		List<Message> received = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(1);
		new DefaultSolonCodeRequestDesc("hi", options -> client).stream().subscribe(m -> {
			received.add(m);
			firstDelivered.countDown();
		}, e -> done.countDown(), done::countDown);

		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(received).hasSize(2);
	}

	@Test
	void streamStopsPullingAfterCancel() throws Exception {
		AtomicInteger pulled = new AtomicInteger();
		AtomicBoolean firstSeen = new AtomicBoolean();

		Iterator<ParsedMessage> endless = new Iterator<ParsedMessage>() {
			@Override
			public boolean hasNext() {
				return true;
			}

			@Override
			public ParsedMessage next() {
				pulled.incrementAndGet();
				return assistant("chunk");
			}
		};

		SolonCodeSession client = mock(SolonCodeSession.class);
		when(client.receiveResponse()).thenReturn(endless);

		CountDownLatch got = new CountDownLatch(1);
		Disposable sub = new DefaultSolonCodeRequestDesc("hi", options -> client).stream().subscribe(m -> {
			firstSeen.set(true);
			got.countDown();
		});
		assertThat(got.await(5, TimeUnit.SECONDS)).isTrue();
		sub.dispose();

		// 取消后停止拉取：轮询到「连续两次取样不变」即认为已停手（不靠固定 sleep，避免时序抖动）
		int stable = -1;
		boolean stopped = false;
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			int sample = pulled.get();
			Thread.sleep(100);
			if (sample == pulled.get() && sample == stable) {
				stopped = true;
				break;
			}
			stable = sample;
		}
		assertThat(stopped).as("iterator should stop being pulled after cancel").isTrue();
		assertThat(firstSeen).isTrue();
	}

	@Test
	void streamCancellationInterruptsBlockedClientAndClosesOnce() throws Exception {
		CountDownLatch enteredHasNext = new CountDownLatch(1);
		CountDownLatch releaseHasNext = new CountDownLatch(1);
		CountDownLatch closed = new CountDownLatch(1);
		AtomicBoolean errored = new AtomicBoolean();

		Iterator<ParsedMessage> blocked = new Iterator<ParsedMessage>() {
			@Override
			public boolean hasNext() {
				enteredHasNext.countDown();
				try {
					releaseHasNext.await();
					return false;
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}

			@Override
			public ParsedMessage next() {
				throw new UnsupportedOperationException("no message expected");
			}
		};

		SolonCodeSession client = mock(SolonCodeSession.class);
		when(client.receiveResponse()).thenReturn(blocked);
		doAnswer(invocation -> {
			releaseHasNext.countDown();
			return null;
		}).when(client).interrupt();
		doAnswer(invocation -> {
			closed.countDown();
			return null;
		}).when(client).close();

		Disposable sub = new DefaultSolonCodeRequestDesc("long task", options -> client).stream()
			.subscribe(message -> {
			}, error -> errored.set(true));

		assertThat(enteredHasNext.await(5, TimeUnit.SECONDS)).isTrue();
		sub.dispose();

		assertThat(closed.await(5, TimeUnit.SECONDS)).as("client should close after cancellation").isTrue();
		verify(client, times(1)).interrupt();
		verify(client, times(1)).close();
		verify(client, times(1)).connect("long task");
		assertThat(errored).as("cancellation must not surface an error").isFalse();
	}

	@Test
	void streamSurfacesErrorAsFluxErrorAndClosesClient() throws Exception {
		SolonCodeSession client = mock(SolonCodeSession.class);
		doThrow(new RuntimeException("stream boom")).when(client).connect(anyString());

		StepVerifier.create(new DefaultSolonCodeRequestDesc("hi", options -> client).stream())
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(SolonCodeSDKException.class)
				.hasMessageContaining("Failed to stream request"))
			.verify();

		verify(client).close();
	}

	@Test
	void streamIsColdAndRunsOncePerSubscription() throws Exception {
		AtomicInteger created = new AtomicInteger();

		Flux<Message> flux = new DefaultSolonCodeRequestDesc("hi", options -> {
			created.incrementAndGet();
			try {
				return clientOf(Arrays.asList(result("ok")));
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}).stream();

		assertThat(created).hasValue(0); // 未订阅不执行

		StepVerifier.create(flux).expectNextCount(1).verifyComplete();
		StepVerifier.create(flux).expectNextCount(1).verifyComplete();
		assertThat(created).hasValue(2);
	}

	@Test
	void streamCloseFailureDoesNotMaskCompletion() throws Exception {
		SolonCodeSession client = mock(SolonCodeSession.class);
		when(client.receiveResponse()).thenReturn(Arrays.asList(result("ok")).iterator());
		doThrow(new RuntimeException("close failed")).when(client).close();

		StepVerifier.create(new DefaultSolonCodeRequestDesc("hi", options -> client).stream())
			.expectNextCount(1)
			.verifyComplete();

		verify(client, times(1)).close();
	}

	// ---------- 入口与参数校验 ----------

	@Test
	void emptyPromptIsRejected() {
		assertThatThrownBy(() -> new DefaultSolonCodeRequestDesc("  ", options -> null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("prompt must not be empty");
		assertThatThrownBy(() -> new DefaultSolonCodeRequestDesc(null, options -> null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void facadeAndBuilderEntriesReturnRequestDesc() {
		assertThat(SolonCode.prompt("hi")).isInstanceOf(SolonCodeRequestDesc.class);
		try (SolonCodeClient client = SolonCodeClient.builder()
				.workingDirectory(java.nio.file.Paths.get(System.getProperty("user.dir")))
				.build()) {
			assertThat(client.prompt("hi")).isInstanceOf(SolonCodeClient.Request.class);
		}
	}

	@Test
	void optionsReturnsSameInstanceForChaining() {
		SolonCodeRequestDesc desc = SolonCode.prompt("hi");
		assertThat(desc.options(QueryOptions.defaults())).isSameAs(desc);
	}

}
