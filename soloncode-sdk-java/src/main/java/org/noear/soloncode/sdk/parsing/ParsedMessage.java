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

package org.noear.soloncode.sdk.parsing;

import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.RateLimitEvent;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;

import java.util.Objects;

/**
 * Represents a parsed message from SolonCode CLI output. In bidirectional mode, the CLI can
 * send either regular messages (user, assistant, system, result) or control requests.
 *
 * <p>
 * This interface provides type-safe handling of both cases:
 * </p>
 */
public interface ParsedMessage {

	/**
	 * Check if this is a regular message (user, assistant, system, result).
	 */
	default boolean isRegularMessage() {
		return this instanceof RegularMessage;
	}

	/**
	 * Check if this is a result message (session final result). Used by transports to
	 * determine success when the CLI exits with an ambiguous code.
	 */
	default boolean isResultMessage() {
		return this instanceof RegularMessage
				&& ((RegularMessage) this).message() instanceof org.noear.soloncode.sdk.types.ResultMessage;
	}

	/**
	 * Check if this is a control request.
	 */
	default boolean isControlRequest() {
		return this instanceof Control;
	}

	/**
	 * Check if this is a control response.
	 */
	default boolean isControlResponse() {
		return this instanceof ControlResponseMessage;
	}

	/**
	 * Get as regular message, or null if this is a control request.
	 */
	default Message asMessage() {
		if (this instanceof RegularMessage) {
			return ((RegularMessage) this).message();
		}
		return null;
	}

	/**
	 * Get as control request, or null if this is a regular message.
	 */
	default ControlRequest asControlRequest() {
		if (this instanceof Control) {
			return ((Control) this).request();
		}
		return null;
	}

	/**
	 * Get as control response, or null if this is not a control response.
	 */
	default ControlResponse asControlResponse() {
		if (this instanceof ControlResponseMessage) {
			return ((ControlResponseMessage) this).response();
		}
		return null;
	}

	/**
	 * Check if this is a rate limit event.
	 */
	default boolean isRateLimitEvent() {
		return this instanceof RateLimitEventMessage;
	}

	/**
	 * Get as rate limit event, or null if this is not a rate limit event.
	 */
	default RateLimitEvent asRateLimitEvent() {
		if (this instanceof RateLimitEventMessage) {
			return ((RateLimitEventMessage) this).event();
		}
		return null;
	}

	/**
	 * Wrapper for regular messages (type=user, assistant, system, result).
	 */
	static final class RegularMessage implements ParsedMessage {

		private final Message message;

		private final String rawJson;

		public RegularMessage(Message message, String rawJson) {
			if (message == null) {
				throw new IllegalArgumentException("message must not be null");
			}
			this.message = message;
			this.rawJson = rawJson;
		}

		public Message message() {
			return message;
		}

		public String rawJson() {
			return rawJson;
		}

		/**
		 * Creates a RegularMessage without raw JSON (programmatic construction).
		 */
		public RegularMessage(Message message) {
			this(message, null);
		}

		/**
		 * Factory method for creating a RegularMessage without raw JSON.
		 */
		public static RegularMessage of(Message message) {
			return new RegularMessage(message, null);
		}

		/**
		 * Factory method for creating a RegularMessage retaining the raw JSON line it was
		 * parsed from.
		 */
		public static RegularMessage of(Message message, String rawJson) {
			return new RegularMessage(message, rawJson);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof RegularMessage)) {
				return false;
			}
			RegularMessage that = (RegularMessage) o;
			return Objects.equals(message, that.message) && Objects.equals(rawJson, that.rawJson);
		}

		@Override
		public int hashCode() {
			return Objects.hash(message, rawJson);
		}

		@Override
		public String toString() {
			return "RegularMessage[message=" + message + ", rawJson=" + rawJson + "]";
		}
	}

	/**
	 * Wrapper for control protocol requests (type=control_request).
	 */
	static final class Control implements ParsedMessage {

		private final ControlRequest request;

		public Control(ControlRequest request) {
			if (request == null) {
				throw new IllegalArgumentException("request must not be null");
			}
			this.request = request;
		}

		public ControlRequest request() {
			return request;
		}

		/**
		 * Factory method for creating a Control message.
		 */
		public static Control of(ControlRequest request) {
			return new Control(request);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Control)) {
				return false;
			}
			Control that = (Control) o;
			return Objects.equals(request, that.request);
		}

		@Override
		public int hashCode() {
			return Objects.hash(request);
		}

		@Override
		public String toString() {
			return "Control[request=" + request + "]";
		}
	}

	/**
	 * Wrapper for control protocol responses (type=control_response). These are responses
	 * from the CLI to control requests we sent (e.g., interrupt, set_model,
	 * set_permission_mode).
	 */
	static final class ControlResponseMessage implements ParsedMessage {

		private final ControlResponse response;

		public ControlResponseMessage(ControlResponse response) {
			if (response == null) {
				throw new IllegalArgumentException("response must not be null");
			}
			this.response = response;
		}

		public ControlResponse response() {
			return response;
		}

		/**
		 * Factory method for creating a ControlResponseMessage.
		 */
		public static ControlResponseMessage of(ControlResponse response) {
			return new ControlResponseMessage(response);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ControlResponseMessage)) {
				return false;
			}
			ControlResponseMessage that = (ControlResponseMessage) o;
			return Objects.equals(response, that.response);
		}

		@Override
		public int hashCode() {
			return Objects.hash(response);
		}

		@Override
		public String toString() {
			return "ControlResponseMessage[response=" + response + "]";
		}
	}

	/**
	 * Wrapper for rate limit events (type=rate_limit_event). These are server-sent events
	 * carrying quota status and reset timing. Currently informational — the transport
	 * skips these, but callers can check them for proactive back-off.
	 */
	static final class RateLimitEventMessage implements ParsedMessage {

		private final RateLimitEvent event;

		public RateLimitEventMessage(RateLimitEvent event) {
			if (event == null) {
				throw new IllegalArgumentException("event must not be null");
			}
			this.event = event;
		}

		public RateLimitEvent event() {
			return event;
		}

		/**
		 * Factory method for creating a RateLimitEventMessage.
		 */
		public static RateLimitEventMessage of(RateLimitEvent event) {
			return new RateLimitEventMessage(event);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof RateLimitEventMessage)) {
				return false;
			}
			RateLimitEventMessage that = (RateLimitEventMessage) o;
			return Objects.equals(event, that.event);
		}

		@Override
		public int hashCode() {
			return Objects.hash(event);
		}

		@Override
		public String toString() {
			return "RateLimitEventMessage[event=" + event + "]";
		}
	}

	/**
	 * Sentinel value used internally by MessageStreamIterator to signal end of stream.
	 * This should not be used by application code.
	 */
	static final class EndOfStream implements ParsedMessage {

		/**
		 * Singleton instance.
		 */
		public static final EndOfStream INSTANCE = new EndOfStream();

		public EndOfStream() {
		}

		@Override
		public boolean equals(Object o) {
			return this == o || o instanceof EndOfStream;
		}

		@Override
		public int hashCode() {
			return EndOfStream.class.hashCode();
		}

		@Override
		public String toString() {
			return "EndOfStream[]";
		}
	}

}
