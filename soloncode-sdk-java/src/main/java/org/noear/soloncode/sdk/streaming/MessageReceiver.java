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

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;

/**
 * Receiver for streaming messages from the SolonCode CLI.
 *
 * <p>
 * This receiver exposes a single blocking {@link #next()} operation. It returns null at a
 * normal end-of-stream, avoiding a separate hasNext()/next() protocol between producer and
 * consumer threads.
 * </p>
 *
 * <p>
 * Usage:
 * </p>
 *
 * <pre>{@code
 * try (MessageReceiver receiver = session.messageReceiver()) {
 *     ParsedMessage message;
 *     while ((message = receiver.next()) != null) {
 *         handleMessage(message);
 *     }
 * }
 * }</pre>
 *
 * @see ParsedMessage
 */
public interface MessageReceiver extends AutoCloseable {

	/**
	 * Returns the next message from the stream.
	 *
	 * <p>
	 * This method blocks until a message is available or the stream ends. Returns
	 * {@code null} when the stream has ended normally (no more messages will be
	 * received).
	 * </p>
	 * @return the next message, or {@code null} at end-of-stream
	 * @throws SolonCodeSDKException if an error occurs while receiving
	 * @throws InterruptedException if the current thread is interrupted while waiting
	 */
	ParsedMessage next() throws SolonCodeSDKException, InterruptedException;

	/**
	 * Closes the receiver and releases resources. After calling this method, subsequent
	 * calls to {@link #next()} will return {@code null}.
	 */
	@Override
	void close();

}
