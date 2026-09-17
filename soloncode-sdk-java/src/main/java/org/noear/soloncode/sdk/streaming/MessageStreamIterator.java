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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.ResultMessage;

import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Iterator adapter for streaming messages. Allows consuming messages using standard
 * Iterator/Iterable patterns while the underlying transport streams asynchronously.
 *
 * <p>
 * Usage:
 * </p>
 *
 * <pre>{@code
 * try (MessageStreamIterator iterator = new MessageStreamIterator()) {
 *     // Start transport that calls iterator.offer() for each message
 *     transport.startSession(..., iterator::offer, ...);
 *
 *     // Consume messages with for-each
 *     for (ParsedMessage message : iterator) {
 *         handleMessage(message);
 *     }
 * }
 * }</pre>
 *
 * <p>
 * Thread-safety: The iterator is thread-safe. Messages can be offered from one thread
 * (transport) and consumed from another thread (application).
 * </p>
 *
 * @see ParsedMessage
 */
public class MessageStreamIterator implements Iterator<ParsedMessage>, Iterable<ParsedMessage>, AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(MessageStreamIterator.class);

	/**
	 * Sentinel value to signal end of stream.
	 */
	private static final ParsedMessage END_OF_STREAM = ParsedMessage.EndOfStream.INSTANCE;

	private final BlockingQueue<ParsedMessage> queue;

	private final long pollTimeoutMs;

	private final AtomicBoolean completed = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicReference<Throwable> error = new AtomicReference<>();

	private final long deadlineNanos;

	private final Runnable timeoutAction;

	private final Runnable terminalMessageAction;

	private final AtomicBoolean timeoutTriggered = new AtomicBoolean(false);

	private ParsedMessage nextMessage;

	/**
	 * Creates an iterator with default settings.
	 */
	public MessageStreamIterator() {
		this(1000, 100, null, null, null);
	}

	/**
	 * Creates an iterator with custom settings.
	 * @param queueCapacity maximum number of buffered messages
	 * @param pollTimeoutMs timeout in milliseconds for blocking poll
	 */
	public MessageStreamIterator(int queueCapacity, long pollTimeoutMs) {
		this(queueCapacity, pollTimeoutMs, null, null, null);
	}

	public MessageStreamIterator(int queueCapacity, long pollTimeoutMs, Duration timeout, Runnable timeoutAction) {
		this(queueCapacity, pollTimeoutMs, timeout, timeoutAction, null);
	}

	public MessageStreamIterator(int queueCapacity, long pollTimeoutMs, Duration timeout, Runnable timeoutAction,
			Runnable terminalMessageAction) {
		this.queue = new LinkedBlockingQueue<>(queueCapacity);
		this.pollTimeoutMs = pollTimeoutMs;
		this.deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
		this.timeoutAction = timeoutAction;
		this.terminalMessageAction = terminalMessageAction;
	}

	/**
	 * Offers a message to the iterator. Called by the transport when a message arrives.
	 * @param message the message to offer
	 * @return true if accepted, false if queue is full or iterator is closed
	 */
	public boolean offer(ParsedMessage message) {
		if (closed.get() || completed.get()) {
			return false;
		}

		try {
			boolean accepted = queue.offer(message, pollTimeoutMs, TimeUnit.MILLISECONDS);
			if (!accepted) {
				completeWithError(new IllegalStateException("Message iterator buffer is full"));
			}
			return accepted;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			completeWithError(e);
			return false;
		}
	}

	/**
	 * Signals that the stream has completed normally. No more messages will be offered.
	 */
	public void complete() {
		if (completed.compareAndSet(false, true)) {
			logger.debug("Stream completed");
		}
	}

	/**
	 * Signals that the stream has failed with an error.
	 * @param throwable the error that caused the failure
	 */
	public void completeWithError(Throwable throwable) {
		logger.debug("Stream completed with error: {}", throwable.getMessage());
		error.compareAndSet(null, throwable);
		completed.set(true);
	}

	@Override
	public boolean hasNext() {
		if (closed.get()) {
			return false;
		}

		if (nextMessage != null && nextMessage != END_OF_STREAM) {
			return true;
		}

		// Drain buffered messages first, then observe the independently stored terminal state.
		try {
			while (!closed.get()) {
				if (System.nanoTime() >= deadlineNanos && timeoutTriggered.compareAndSet(false, true)) {
					if (timeoutAction != null) {
						timeoutAction.run();
					}
					completeWithError(new IllegalStateException("Response timed out"));
				}
				nextMessage = queue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS);

				if (nextMessage != null && nextMessage != END_OF_STREAM) {
					return true;
				}

				if (completed.get() && queue.isEmpty()) {
					nextMessage = null;
					Throwable err = error.get();
					if (err != null) {
						throw new StreamException("Stream failed", err);
					}
					return false;
				}
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return false;
	}

	@Override
	public ParsedMessage next() {
		// Do NOT call hasNext() here - this causes race conditions with the
		// timeout-based polling. Per Iterator contract, caller must call hasNext()
		// before next().
		if (nextMessage == null) {
			throw new NoSuchElementException("No element available. Did you call hasNext() first?");
		}

		ParsedMessage message = nextMessage;
		nextMessage = null;
		if (message.isRegularMessage() && message.asMessage().isTerminal()
				&& terminalMessageAction != null) {
			terminalMessageAction.run();
		}
		return message;
	}

	@Override
	public Iterator<ParsedMessage> iterator() {
		return this;
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			logger.debug("Iterator closed");
			queue.clear();
		}
	}

	/**
	 * Checks if the stream is still active (not completed and not closed).
	 */
	public boolean isActive() {
		return !completed.get() && !closed.get();
	}

	/**
	 * Gets the number of messages currently buffered.
	 */
	public int getBufferedCount() {
		return queue.size();
	}

	/**
	 * Exception thrown when the stream fails with an error.
	 */
	public static class StreamException extends RuntimeException {

		public StreamException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
