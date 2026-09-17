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
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.ResultMessage;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking queue-based implementation of {@link MessageReceiver}.
 *
 * <p>
 * The bounded queue is polled while checking the configured per-receiver deadline.
 * {@link #next()} returns at normal end-of-stream and propagates timeout or producer errors.
 * </p>
 *
 * <p>
 * Thread-safety: Messages are offered from the transport inbound thread and consumed from
 * the application thread. The blocking queue handles synchronization.
 * </p>
 *
 * @see MessageReceiver
 */
public class BlockingMessageReceiver implements MessageReceiver {

	private static final Logger logger = LoggerFactory.getLogger(BlockingMessageReceiver.class);

	/**
	 * Sentinel value to signal end of stream.
	 */
	private static final ParsedMessage END_OF_STREAM = ParsedMessage.EndOfStream.INSTANCE;

	private final BlockingQueue<ParsedMessage> queue;

	private final AtomicBoolean completed = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicReference<Throwable> error = new AtomicReference<>();

	private final long deadlineNanos;

	private final Runnable timeoutAction;

	private final Runnable terminalMessageAction;

	private final AtomicBoolean timeoutTriggered = new AtomicBoolean(false);

	/**
	 * Creates a receiver with default queue capacity (1000 messages).
	 */
	public BlockingMessageReceiver() {
		this(1000, null, null, null);
	}

	/**
	 * Creates a receiver with the specified queue capacity.
	 * @param queueCapacity maximum number of buffered messages
	 */
	public BlockingMessageReceiver(int queueCapacity) {
		this(queueCapacity, null, null, null);
	}

	public BlockingMessageReceiver(int queueCapacity, Duration timeout, Runnable timeoutAction) {
		this(queueCapacity, timeout, timeoutAction, null);
	}

	public BlockingMessageReceiver(int queueCapacity, Duration timeout, Runnable timeoutAction,
			Runnable terminalMessageAction) {
		this.queue = new LinkedBlockingQueue<>(queueCapacity);
		this.deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
		this.timeoutAction = timeoutAction;
		this.terminalMessageAction = terminalMessageAction;
	}

	/**
	 * Offers a message to the receiver. Called by the transport when a message arrives.
	 * @param message the message to offer
	 * @return true if accepted, false if queue is full or receiver is closed
	 */
	public boolean offer(ParsedMessage message) {
		if (closed.get() || completed.get()) {
			return false;
		}
		boolean accepted = queue.offer(message);
		if (!accepted) {
			completeWithError(new TransportException("Message receiver buffer is full"));
		}
		return accepted;
	}

	/**
	 * Signals that the stream has completed normally. No more messages will be offered.
	 */
	public void complete() {
		if (!closed.get() && completed.compareAndSet(false, true)) {
			logger.debug("Stream completed");
		}
	}

	/**
	 * Signals that the stream has failed with an error.
	 * @param throwable the error that caused the failure
	 */
	public void completeWithError(Throwable throwable) {
		if (!closed.get()) {
			logger.debug("Stream completed with error: {}", throwable.getMessage());
			error.compareAndSet(null, throwable);
			completed.set(true);
		}
	}

	@Override
	public ParsedMessage next() throws SolonCodeSDKException, InterruptedException {
		if (closed.get()) {
			return null;
		}

		while (!closed.get()) {
			if (System.nanoTime() >= deadlineNanos && timeoutTriggered.compareAndSet(false, true)) {
				if (timeoutAction != null) {
					timeoutAction.run();
				}
				completeWithError(new TransportException("Response timed out"));
			}
			ParsedMessage message = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
			if (message != null && message != END_OF_STREAM) {
				if (message.isRegularMessage() && message.asMessage().isTerminal()
						&& terminalMessageAction != null) {
					terminalMessageAction.run();
				}
				return message;
			}
			if (completed.get() && queue.isEmpty()) {
				Throwable err = error.get();
				if (err != null) {
					if (err instanceof SolonCodeSDKException) {
						throw (SolonCodeSDKException) err;
					}
					throw new TransportException("Stream failed", err);
				}
				return null;
			}
		}
		return null;
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			logger.debug("Receiver closed");
			queue.clear();
		}
	}

	/**
	 * Checks if the receiver is still active (not closed).
	 * @return true if still accepting messages
	 */
	public boolean isActive() {
		return !closed.get();
	}

	/**
	 * Gets the number of messages currently buffered.
	 * @return buffered message count
	 */
	public int getBufferedCount() {
		return queue.size();
	}

}
