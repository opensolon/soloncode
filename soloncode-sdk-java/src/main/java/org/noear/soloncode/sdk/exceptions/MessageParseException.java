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

package org.noear.soloncode.sdk.exceptions;

import java.util.Map;

/**
 * Raised when unable to parse a message from CLI output. Includes JSON decode errors.
 *
 * <p>
 * Combines the functionality of MessageParseError and CLIJSONDecodeError from Python SDK.
 */
public class MessageParseException extends SolonCodeSDKException {

	private final Map<String, Object> data;

	private final String rawInput;

	public MessageParseException(String message) {
		super(message);
		this.data = null;
		this.rawInput = null;
	}

	public MessageParseException(String message, Map<String, Object> data) {
		super(message);
		this.data = data;
		this.rawInput = null;
	}

	public MessageParseException(String message, Throwable cause) {
		super(message, cause);
		this.data = null;
		this.rawInput = null;
	}

	public MessageParseException(String message, String rawInput, Throwable cause) {
		super(message, cause);
		this.data = null;
		this.rawInput = rawInput;
	}

	public Map<String, Object> getData() {
		return data;
	}

	public String getRawInput() {
		return rawInput;
	}

	/**
	 * Creates a MessageParseException with parsed data context.
	 */
	public static MessageParseException withData(String message, Map<String, Object> data) {
		return new MessageParseException(message, data);
	}

	/**
	 * Creates a MessageParseException for JSON decode failures.
	 */
	public static MessageParseException jsonDecodeError(String json, Exception cause) {
		String truncated = json.length() > 100 ? json.substring(0, 100) + "..." : json;
		return new MessageParseException("Failed to decode JSON: " + truncated, json, cause);
	}

}