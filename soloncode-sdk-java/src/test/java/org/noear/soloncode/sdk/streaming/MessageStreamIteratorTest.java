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

package org.noear.soloncode.sdk.streaming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.SystemMessage;
import org.noear.soloncode.sdk.types.TextBlock;
import org.noear.soloncode.sdk.types.UserMessage;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for MessageStreamIterator - buffering, completion, and thread-safety.
 */
class MessageStreamIteratorTest {

	private MessageStreamIterator iterator;

	@BeforeEach
	void setUp() {
		iterator = new MessageStreamIterator(100, 50);
	}

	@AfterEach
	void tearDown() {
		if (iterator != null) {
			iterator.close();
		}
	}

	@Nested
	@DisplayName("Basic Iteration")
	class BasicIterationTests {

		@Test
		@DisplayName("Should iterate over offered messages")
		void iterateOverMessages() {
			// Given
			ParsedMessage msg1 = createUserMessage("Hello");
			ParsedMessage msg2 = createUserMessage("World");

			iterator.offer(msg1);
			iterator.offer(msg2);
			iterator.complete();

			// When
			List<ParsedMessage> collected = new ArrayList<>();
			for (ParsedMessage msg : iterator) {
				collected.add(msg);
			}

			// Then
			assertThat(collected).hasSize(2);
		}

		@Test
		@DisplayName("Should return false for hasNext when no messages")
		void hasNextReturnsFalseWhenEmpty() {
			iterator.complete();

			assertThat(iterator.hasNext()).isFalse();
		}

		@Test
		@DisplayName("Should throw NoSuchElementException when no more elements")
		void throwsWhenNoMoreElements() {
			iterator.complete();

			assertThatThrownBy(() -> iterator.next()).isInstanceOf(NoSuchElementException.class);
		}

		@Test
		@DisplayName("Should handle single message")
		void singleMessage() {
			ParsedMessage msg = createUserMessage("Single");
			iterator.offer(msg);
			iterator.complete();

			assertThat(iterator.hasNext()).isTrue();
			assertThat(iterator.next()).isSameAs(msg);
			assertThat(iterator.hasNext()).isFalse();
		}

		@Test
		@DisplayName("Should be usable with for-each loop")
		void forEachLoop() {
			iterator.offer(createUserMessage("1"));
			iterator.offer(createUserMessage("2"));
			iterator.offer(createUserMessage("3"));
			iterator.complete();

			AtomicInteger count = new AtomicInteger();
			for (ParsedMessage msg : iterator) {
				count.incrementAndGet();
			}

			assertThat(count.get()).isEqualTo(3);
		}

	}

	@Nested
	@DisplayName("Completion Handling")
	class CompletionHandlingTests {

		@Test
		@DisplayName("Should signal completion with complete()")
		void signalCompletion() {
			iterator.offer(createUserMessage("Test"));
			iterator.complete();

			// Should be able to read the message
			assertThat(iterator.hasNext()).isTrue();
			iterator.next();

			// Then no more
			assertThat(iterator.hasNext()).isFalse();
		}

		@Test
		@DisplayName("Should reject messages after completion")
		void rejectAfterCompletion() {
			iterator.complete();

			boolean accepted = iterator.offer(createUserMessage("Late"));

			assertThat(accepted).isFalse();
		}

		@Test
		@DisplayName("Should propagate error on completeWithError")
		void propagateError() {
			iterator.offer(createUserMessage("Before error"));
			iterator.completeWithError(new RuntimeException("Test error"));

			// Can still read buffered message
			assertThat(iterator.hasNext()).isTrue();
			iterator.next();

			// Then should throw
			assertThatThrownBy(() -> iterator.hasNext()).isInstanceOf(MessageStreamIterator.StreamException.class)
				.hasMessageContaining("Stream failed")
				.hasCauseInstanceOf(RuntimeException.class);
		}

		@Test
		@DisplayName("Top-level error should trigger terminal action")
		void topLevelErrorTriggersTerminalAction() {
			AtomicBoolean terminal = new AtomicBoolean();
			MessageStreamIterator terminalIterator = new MessageStreamIterator(10, 10, null, null,
					() -> terminal.set(true));
			try {
				terminalIterator.offer(ParsedMessage.RegularMessage.of(
						SystemMessage.of("error", SdkCollections.map("message", "turn failed"))));
				assertThat(terminalIterator.hasNext()).isTrue();
				assertThat(terminalIterator.next().asMessage().isTerminal()).isTrue();
				assertThat(terminal).isTrue();
			}
			finally {
				terminalIterator.close();
			}
		}

		@Test
		@DisplayName("complete() is idempotent")
		void completeIsIdempotent() {
			iterator.complete();
			iterator.complete();
			iterator.complete();

			assertThat(iterator.hasNext()).isFalse();
		}

	}

	@Nested
	@DisplayName("Closing")
	class ClosingTests {

		@Test
		@DisplayName("Should stop iteration when closed")
		void stopOnClose() {
			iterator.offer(createUserMessage("1"));
			iterator.offer(createUserMessage("2"));
			// Don't complete - close while messages pending

			iterator.close();

			assertThat(iterator.hasNext()).isFalse();
		}

		@Test
		@DisplayName("Should reject messages after close")
		void rejectAfterClose() {
			iterator.close();

			boolean accepted = iterator.offer(createUserMessage("Late"));

			assertThat(accepted).isFalse();
		}

		@Test
		@DisplayName("close() is idempotent")
		void closeIsIdempotent() {
			iterator.close();
			iterator.close();
			iterator.close();

			assertThat(iterator.isActive()).isFalse();
		}

	}

	@Nested
	@DisplayName("State Queries")
	class StateQueryTests {

		@Test
		@DisplayName("isActive returns true initially")
		void isActiveInitially() {
			assertThat(iterator.isActive()).isTrue();
		}

		@Test
		@DisplayName("isActive returns false after complete")
		void isActiveAfterComplete() {
			iterator.complete();

			assertThat(iterator.isActive()).isFalse();
		}

		@Test
		@DisplayName("isActive returns false after close")
		void isActiveAfterClose() {
			iterator.close();

			assertThat(iterator.isActive()).isFalse();
		}

		@Test
		@DisplayName("getBufferedCount returns queue size")
		void bufferedCount() {
			assertThat(iterator.getBufferedCount()).isEqualTo(0);

			iterator.offer(createUserMessage("1"));
			assertThat(iterator.getBufferedCount()).isEqualTo(1);

			iterator.offer(createUserMessage("2"));
			assertThat(iterator.getBufferedCount()).isEqualTo(2);
		}

	}

	@Nested
	@DisplayName("Thread Safety")
	class ThreadSafetyTests {

		@Test
		@DisplayName("Should handle concurrent offer and consume")
		void concurrentOfferAndConsume() throws InterruptedException {
			int messageCount = 1000;
			ExecutorService executor = Executors.newFixedThreadPool(2);
			CountDownLatch producerDone = new CountDownLatch(1);
			CountDownLatch consumerDone = new CountDownLatch(1);
			AtomicInteger consumed = new AtomicInteger();

			// Producer
			executor.submit(() -> {
				try {
					for (int i = 0; i < messageCount; i++) {
						iterator.offer(createUserMessage("msg_" + i));
					}
					iterator.complete();
				}
				finally {
					producerDone.countDown();
				}
			});

			// Consumer
			executor.submit(() -> {
				try {
					for (ParsedMessage msg : iterator) {
						consumed.incrementAndGet();
					}
				}
				finally {
					consumerDone.countDown();
				}
			});

			// Wait for both
			assertThat(producerDone.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(consumerDone.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(consumed.get()).isEqualTo(messageCount);

			executor.shutdown();
		}

		@Test
		@DisplayName("Should handle interrupt during hasNext")
		void handleInterrupt() throws InterruptedException {
			Thread testThread = Thread.currentThread();

			// Schedule interrupt after short delay
			ExecutorService executor = Executors.newSingleThreadExecutor();
			executor.submit(() -> {
				try {
					Thread.sleep(20);
					testThread.interrupt();
				}
				catch (InterruptedException e) {
					// ignore
				}
			});

			// This should return false when interrupted (not hang)
			// Note: hasNext uses polling with timeout, so interrupt will eventually be
			// noticed
			long start = System.currentTimeMillis();
			boolean hasNext = iterator.hasNext();
			long elapsed = System.currentTimeMillis() - start;

			// Should complete within reasonable time
			assertThat(elapsed).isLessThan(1000);
			assertThat(hasNext).isFalse();

			// Clear interrupt flag
			Thread.interrupted();

			executor.shutdown();
		}

	}

	@Nested
	@DisplayName("Backpressure")
	class BackpressureTests {

		@Test
		@DisplayName("Should respect queue capacity")
		void respectQueueCapacity() {
			MessageStreamIterator smallIterator = new MessageStreamIterator(3, 10);

			// Fill the queue
			assertThat(smallIterator.offer(createUserMessage("1"))).isTrue();
			assertThat(smallIterator.offer(createUserMessage("2"))).isTrue();
			assertThat(smallIterator.offer(createUserMessage("3"))).isTrue();

			// Queue is full - should block briefly then fail with small timeout
			long start = System.currentTimeMillis();
			boolean accepted = smallIterator.offer(createUserMessage("4"));
			long elapsed = System.currentTimeMillis() - start;

			// Should have waited for timeout
			assertThat(elapsed).isGreaterThanOrEqualTo(10);
			assertThat(accepted).isFalse();

			smallIterator.close();
		}

	}

	// Helper methods

	private ParsedMessage createUserMessage(String content) {
		return ParsedMessage.RegularMessage.of(UserMessage.of(content));
	}

	private ParsedMessage createAssistantMessage(String content) {
		return ParsedMessage.RegularMessage.of(new AssistantMessage(SdkCollections.list(new TextBlock(content))));
	}

}
