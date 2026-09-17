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
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A message receiver that stops after receiving a {@link ResultMessage}. This is useful
 * for processing a single response before sending another query.
 *
 * <p>
 * The receiver wraps a delegate receiver and returns messages until a ResultMessage is
 * received. The ResultMessage is included in the output, then subsequent calls to
 * {@link #next()} return null.
 * </p>
 *
 * <p>
 * Note: This receiver does NOT close the underlying receiver when it reaches the
 * ResultMessage boundary. The underlying receiver can continue to be used for subsequent
 * queries.
 * </p>
 */
public class ResponseBoundedReceiver implements MessageReceiver {

	private final MessageReceiver delegate;

	private final AtomicBoolean resultReceived = new AtomicBoolean(false);

	/**
	 * Creates a response-bounded receiver.
	 * @param delegate the underlying receiver to wrap
	 */
	public ResponseBoundedReceiver(MessageReceiver delegate) {
		this.delegate = delegate;
	}

	@Override
	public ParsedMessage next() throws SolonCodeSDKException, InterruptedException {
		// If we've already received ResultMessage, return null
		if (resultReceived.get()) {
			return null;
		}

		ParsedMessage message = delegate.next();
		if (message == null) {
			return null;
		}

		// Check if this is a ResultMessage
		if (message.isRegularMessage()) {
			Message msg = message.asMessage();
			if (msg.isTerminal()) {
				resultReceived.set(true);
			}
		}

		return message;
	}

	@Override
	public void close() {
		// Don't close the delegate - it may be reused for subsequent queries
		// Just mark this bounded receiver as done
		resultReceived.set(true);
	}

	/**
	 * Checks if the ResultMessage has been received.
	 * @return true if ResultMessage was received
	 */
	public boolean isResultReceived() {
		return resultReceived.get();
	}

}
