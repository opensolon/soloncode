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
 *
 * Adapted from claude-agent-sdk-java (Apache License 2.0).
 */

package org.noear.soloncode.sdk.parsing;

import org.noear.soloncode.sdk.exceptions.MessageParseException;
import org.noear.soloncode.sdk.types.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Robust streaming parser that implements Python SDK-style character-based JSON
 * accumulation for reliable stream-json processing.
 *
 * <p>
 * Key improvements over naive line-based parsing:
 * </p>
 * <ul>
 * <li>Character-level accumulation handles multi-line JSON properly</li>
 * <li>Buffer size limits prevent memory exhaustion</li>
 * <li>Speculative parsing attempts on every addition</li>
 * <li>Graceful error handling with continued accumulation</li>
 * </ul>
 *
 * <p>
 * This implementation aligns with the official Python SDK streaming patterns for maximum
 * compatibility and robustness.
 * </p>
 */
public class RobustStreamParser {

	private static final Logger logger = LoggerFactory.getLogger(RobustStreamParser.class);

	/**
	 * Maximum buffer size before forcing a clear (matches Python SDK: 1MB).
	 */
	private static final int MAX_BUFFER_SIZE = 1024 * 1024;

	private final MessageParser messageParser;

	private final StringBuilder jsonBuffer = new StringBuilder();

	private long totalCharactersProcessed = 0;

	private int successfulParses = 0;

	private int parseAttempts = 0;

	public RobustStreamParser() {
		this.messageParser = new MessageParser();
	}

	/**
	 * Accumulates the provided line/data and attempts to parse complete JSON messages.
	 *
	 * <p>
	 * This method implements the core streaming algorithm from the Python SDK:
	 * </p>
	 * <ol>
	 * <li>Append new data to accumulation buffer</li>
	 * <li>Check buffer size limits</li>
	 * <li>Attempt to parse accumulated JSON</li>
	 * <li>Clear buffer on successful parse or continue accumulating</li>
	 * </ol>
	 * @param data the incoming data (typically a line from CLI output)
	 * @return Optional containing parsed Message if complete JSON was found, empty
	 * otherwise
	 * @throws MessageParseException if buffer size exceeds maximum limit
	 */
	public Optional<Message> accumulateAndParse(String data) throws MessageParseException {
		if (data == null || data.isEmpty()) {
			return Optional.empty();
		}

		// Accumulate character data (Python SDK pattern)
		jsonBuffer.append(data);
		totalCharactersProcessed += data.length();

		// Enforce buffer size limits (Python SDK: _MAX_BUFFER_SIZE)
		if (jsonBuffer.length() > MAX_BUFFER_SIZE) {
			String preview = jsonBuffer.substring(0, Math.min(200, jsonBuffer.length()));
			logger.warn("JSON buffer exceeded maximum size of {} bytes, clearing buffer. Preview: {}", MAX_BUFFER_SIZE,
					preview);

			jsonBuffer.setLength(0);
			throw new MessageParseException(
					String.format("JSON message exceeded maximum buffer size of %d bytes", MAX_BUFFER_SIZE));
		}

		// Attempt speculative parsing (Python SDK pattern)
		return attemptParse();
	}

	/**
	 * Attempts to parse the current buffer contents as JSON.
	 *
	 * <p>
	 * This implements the Python SDK's speculative parsing approach: try to parse on
	 * every addition, continue accumulating on failure.
	 * </p>
	 * @return Optional containing parsed Message if valid JSON, empty if more data needed
	 */
	private Optional<Message> attemptParse() {
		parseAttempts++;

		String json = jsonBuffer.toString().trim();
		if (json.isEmpty()) {
			return Optional.empty();
		}

		try {
			// Attempt to parse accumulated JSON
			Message message = messageParser.parseMessage(json);

			// Clear buffer regardless — valid JSON was parsed
			jsonBuffer.setLength(0);
			successfulParses++;

			// Unknown message types return null — skip silently
			if (message == null) {
				logger.debug("Skipping unrecognized message type (parse attempts: {}/{})", successfulParses,
						parseAttempts);
				return Optional.empty();
			}

			logger.debug("Successfully parsed message type: {} (parse attempts: {}/{})",
					message.getClass().getSimpleName(), successfulParses, parseAttempts);

			return Optional.of(message);

		}
		catch (MessageParseException e) {
			// Check if this is a JSON decode error (incomplete JSON) vs structural error
			if (e.getRawInput() != null) {
				// JSON decode error - incomplete JSON, continue accumulating (Python SDK
				// pattern)
				logger.trace("JSON parsing incomplete, continuing accumulation. Buffer size: {} chars",
						jsonBuffer.length());
				return Optional.empty();
			}
			else {
				// Structural error - malformed message structure
				logger.warn("Failed to parse message structure, clearing buffer. JSON preview: {}",
						json.substring(0, Math.min(100, json.length())), e);
				jsonBuffer.setLength(0);
				return Optional.empty();
			}
		}
	}

	/**
	 * Forces parsing of any remaining buffer content.
	 *
	 * <p>
	 * Called when the stream ends to attempt parsing any remaining data. This handles
	 * cases where the final message might not end with a newline.
	 * </p>
	 * @return Optional containing final parsed Message, empty if no valid JSON remains
	 */
	public Optional<Message> flushBuffer() {
		if (jsonBuffer.length() == 0) {
			return Optional.empty();
		}

		logger.debug("Flushing buffer with {} characters remaining", jsonBuffer.length());

		Optional<Message> result = attemptParse();

		if (!result.isPresent() && jsonBuffer.length() > 0) {
			// Log remaining unparseable content for debugging
			String remaining = jsonBuffer.toString().trim();
			if (!remaining.isEmpty()) {
				logger.warn("Discarding unparseable buffer content: {}",
						remaining.substring(0, Math.min(200, remaining.length())));
			}
			jsonBuffer.setLength(0);
		}

		return result;
	}

	/**
	 * Clears the accumulation buffer.
	 *
	 * <p>
	 * Used for error recovery scenarios or when resetting the parser state.
	 * </p>
	 */
	public void clearBuffer() {
		logger.debug("Manually clearing buffer with {} characters", jsonBuffer.length());
		jsonBuffer.setLength(0);
	}

	/**
	 * Gets current buffer size for monitoring and debugging.
	 * @return current buffer size in characters
	 */
	public int getBufferSize() {
		return jsonBuffer.length();
	}

	/**
	 * Gets parsing statistics for monitoring and debugging.
	 * @return statistics about parser performance
	 */
	public ParsingStats getStats() {
		return new ParsingStats(totalCharactersProcessed, parseAttempts, successfulParses, jsonBuffer.length());
	}

	/**
	 * Statistics about parser performance and state.
	 */
	public static final class ParsingStats {

		private final long totalCharactersProcessed;

		private final int parseAttempts;

		private final int successfulParses;

		private final int currentBufferSize;

		public ParsingStats(long totalCharactersProcessed, int parseAttempts, int successfulParses,
				int currentBufferSize) {
			this.totalCharactersProcessed = totalCharactersProcessed;
			this.parseAttempts = parseAttempts;
			this.successfulParses = successfulParses;
			this.currentBufferSize = currentBufferSize;
		}

		public long totalCharactersProcessed() {
			return totalCharactersProcessed;
		}

		public int parseAttempts() {
			return parseAttempts;
		}

		public int successfulParses() {
			return successfulParses;
		}

		public int currentBufferSize() {
			return currentBufferSize;
		}

		public double getSuccessRate() {
			return parseAttempts > 0 ? (double) successfulParses / parseAttempts : 0.0;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ParsingStats)) {
				return false;
			}
			ParsingStats that = (ParsingStats) o;
			return totalCharactersProcessed == that.totalCharactersProcessed && parseAttempts == that.parseAttempts
					&& successfulParses == that.successfulParses && currentBufferSize == that.currentBufferSize;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(totalCharactersProcessed, parseAttempts, successfulParses, currentBufferSize);
		}

		@Override
		public String toString() {
			return "ParsingStats[totalCharactersProcessed=" + totalCharactersProcessed + ", parseAttempts="
					+ parseAttempts + ", successfulParses=" + successfulParses + ", currentBufferSize=" + currentBufferSize
					+ "]";
		}
	}

}