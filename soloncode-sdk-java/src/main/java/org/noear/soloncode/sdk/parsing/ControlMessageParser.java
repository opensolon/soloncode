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

import org.noear.snack4.ONode;
import org.noear.snack4.SnackException;
import org.noear.soloncode.sdk.util.SdkJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.soloncode.sdk.exceptions.MessageParseException;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.RateLimitEvent;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;

/**
 * Parser for SolonCode CLI bidirectional control protocol messages. This parser handles both
 * regular messages (user, assistant, system, result) and control requests
 * (control_request).
 *
 * <p>
 * In bidirectional mode, the CLI uses line-delimited JSON where each line is either:
 * </p>
 * <ul>
 * <li>A regular message with type=user, assistant, system, or result</li>
 * <li>A control request with type=control_request</li>
 * </ul>
 *
 * <p>
 * This parser determines the message type from the "type" field and delegates to the
 * appropriate parser.
 * </p>
 *
 * @see ParsedMessage
 * @see ControlRequest
 */
public class ControlMessageParser {

	private static final Logger logger = LoggerFactory.getLogger(ControlMessageParser.class);

	private static final String TYPE_CONTROL_REQUEST = "control_request";

	private static final String TYPE_CONTROL_RESPONSE = "control_response";

	private static final String TYPE_RATE_LIMIT_EVENT = "rate_limit_event";

	/** Default maximum buffer size for JSON parsing (1MB). */
	public static final int DEFAULT_MAX_BUFFER_SIZE = 1024 * 1024;

	private final MessageParser messageParser;

	private final int maxBufferSize;

	/**
	 * Creates a new parser with default configuration.
	 */
	public ControlMessageParser() {
		this(DEFAULT_MAX_BUFFER_SIZE);
	}

	/**
	 * Creates a new parser with custom max buffer size.
	 * @param maxBufferSize maximum message size in bytes (for buffer overflow protection)
	 */
	public ControlMessageParser(int maxBufferSize) {
		this.messageParser = new MessageParser();
		this.maxBufferSize = maxBufferSize > 0 ? maxBufferSize : DEFAULT_MAX_BUFFER_SIZE;
	}

	/**
	 * Parses a JSON string into either a regular message or a control request.
	 * @param json the JSON string to parse
	 * @return a ParsedMessage containing either a Message or ControlRequest
	 * @throws MessageParseException if the JSON is malformed, message structure is
	 * invalid, or message exceeds max buffer size
	 */
	public ParsedMessage parse(String json) throws MessageParseException {
		if (json == null || json.trim().isEmpty()) {
			throw new MessageParseException("Cannot parse null or blank JSON");
		}

		// Buffer overflow protection - check message size before parsing
		if (json.length() > maxBufferSize) {
			throw new MessageParseException(
					String.format("JSON message exceeds maximum buffer size: %d bytes > %d bytes limit", json.length(),
							maxBufferSize));
		}

		try {
			ONode root = SdkJson.parse(json);
			return parseFromNode(root, json);
		}
		catch (SnackException e) {
			throw MessageParseException.jsonDecodeError(json, e);
		}
	}

	/**
	 * Returns the configured maximum buffer size.
	 * @return max buffer size in bytes
	 */
	public int getMaxBufferSize() {
		return maxBufferSize;
	}

	/**
	 * Parses an ONode into either a regular message or a control request.
	 * @param node the ONode to parse
	 * @param originalJson the original JSON string (for error messages)
	 * @return a ParsedMessage containing either a Message or ControlRequest
	 * @throws MessageParseException if the message structure is invalid
	 */
	public ParsedMessage parseFromNode(ONode node, String originalJson) throws MessageParseException {
		String type = SdkJson.getStringField(node, "type");
		if (type == null) {
			throw new MessageParseException("Missing or invalid 'type' field in message");
		}

		if (TYPE_CONTROL_REQUEST.equals(type)) {
			return parseControlRequest(node, originalJson);
		}
		else if (TYPE_CONTROL_RESPONSE.equals(type)) {
			return parseControlResponse(node, originalJson);
		}
		else if (TYPE_RATE_LIMIT_EVENT.equals(type)) {
			return parseRateLimitEvent(node);
		}
		else {
			return parseRegularMessage(node, originalJson);
		}
	}

	/**
	 * Parses a control request from an ONode.
	 */
	private ParsedMessage parseControlRequest(ONode node, String originalJson) throws MessageParseException {
		try {
			ControlRequest request = SdkJson.toBean(node, ControlRequest.class);

			if (request.requestId() == null) {
				throw new MessageParseException("Control request missing 'request_id' field");
			}

			logger.debug("Parsed control request: type={}, subtype={}, requestId={}", request.type(),
					request.request() != null ? request.request().subtype() : "null", request.requestId());

			return ParsedMessage.Control.of(request);
		}
		catch (SnackException e) {
			throw new MessageParseException("Failed to parse control request: " + e.getMessage(), e);
		}
	}

	/**
	 * Parses a control response from an ONode. These are responses from the CLI to our
	 * outgoing control requests (e.g., interrupt, set_model, set_permission_mode).
	 */
	private ParsedMessage parseControlResponse(ONode node, String originalJson) throws MessageParseException {
		try {
			ControlResponse response = SdkJson.toBean(node, ControlResponse.class);

			String requestId = response.response() != null ? response.response().requestId() : null;
			String subtype = response.response() != null ? response.response().subtype() : "null";

			logger.debug("Parsed control response: subtype={}, requestId={}", subtype, requestId);

			return ParsedMessage.ControlResponseMessage.of(response);
		}
		catch (SnackException e) {
			throw new MessageParseException("Failed to parse control response: " + e.getMessage(), e);
		}
	}

	/**
	 * Parses a rate limit event from an ONode.
	 */
	private ParsedMessage parseRateLimitEvent(ONode node) throws MessageParseException {
		try {
			RateLimitEvent event = SdkJson.toBean(node, RateLimitEvent.class);
			logger.debug("Rate limit event: status={}, type={}, resetsAt={}",
					event.rateLimitInfo() != null ? event.rateLimitInfo().status() : "unknown",
					event.rateLimitInfo() != null ? event.rateLimitInfo().rateLimitType() : "unknown",
					event.rateLimitInfo() != null ? event.rateLimitInfo().resetsAt() : 0);
			return ParsedMessage.RateLimitEventMessage.of(event);
		}
		catch (SnackException e) {
			throw new MessageParseException("Failed to parse rate_limit_event: " + e.getMessage(), e);
		}
	}

	/**
	 * Parses a regular message from an ONode. Returns null if the message type is
	 * unrecognized (graceful forward-compatibility with newer CLI versions). The raw JSON
	 * line is retained on the RegularMessage as a lossless escape hatch for wire fields
	 * not yet modeled by the typed API.
	 */
	private ParsedMessage parseRegularMessage(ONode node, String originalJson) throws MessageParseException {
		Message message = messageParser.parseMessageFromNode(node);
		if (message == null) {
			return null;
		}
		return ParsedMessage.RegularMessage.of(message, originalJson);
	}

	/**
	 * Checks if the given JSON represents a control request without fully parsing it.
	 * This is useful for quick type checks before committing to full parsing.
	 * @param json the JSON string to check
	 * @return true if this is a control request, false otherwise
	 */
	public boolean isControlRequest(String json) {
		if (json == null || json.trim().isEmpty()) {
			return false;
		}

		try {
			ONode root = SdkJson.parse(json);
			ONode typeNode = SdkJson.getField(root, "type");
			return typeNode != null && TYPE_CONTROL_REQUEST.equals(typeNode.getString());
		}
		catch (SnackException e) {
			return false;
		}
	}

	/**
	 * Extracts the request ID from a control request JSON without fully parsing it.
	 * Useful for correlation and logging.
	 * @param json the JSON string
	 * @return the request ID, or null if not a control request or missing
	 */
	public String extractRequestId(String json) {
		if (json == null || json.trim().isEmpty()) {
			return null;
		}

		try {
			return SdkJson.getStringField(SdkJson.parse(json), "request_id");
		}
		catch (SnackException e) {
			return null;
		}
	}

}
