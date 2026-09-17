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

package org.noear.soloncode.sdk.config;

/**
 * Enumeration of supported SolonCode CLI output formats.
 *
 * <p>
 * The SolonCode CLI supports different output formats that affect how responses are
 * structured and delivered:
 * </p>
 *
 * <ul>
 * <li>{@code TEXT} - Plain text output (default CLI behavior)</li>
 * <li>{@code JSON} - Single JSON object containing the complete response</li>
 * <li>{@code STREAM_JSON} - Streaming JSON objects for real-time processing</li>
 * </ul>
 */
public enum OutputFormat {

	/**
	 * Plain text output format. This is the default CLI format that returns
	 * human-readable text responses.
	 */
	TEXT("text"),

	/**
	 * Single JSON object output format. Returns the complete response as a single JSON
	 * object containing the result, metadata, usage statistics, and cost information.
	 * Recommended for non-reactive synchronous operations.
	 */
	JSON("json"),

	/**
	 * Streaming JSON output format. Returns multiple JSON objects as the response is
	 * generated, enabling real-time processing. Requires --verbose flag. Recommended for
	 * reactive streaming operations.
	 */
	STREAM_JSON("stream-json");

	private final String value;

	OutputFormat(String value) {
		this.value = value;
	}

	/**
	 * Gets the CLI argument value for this output format.
	 * @return the string value used in CLI commands
	 */
	public String getValue() {
		return value;
	}

	/**
	 * Gets the OutputFormat enum from a CLI value string.
	 * @param value the CLI argument value
	 * @return the corresponding OutputFormat
	 * @throws IllegalArgumentException if the value is not recognized
	 */
	public static OutputFormat fromValue(String value) {
		for (OutputFormat format : values()) {
			if (format.value.equals(value)) {
				return format;
			}
		}
		throw new IllegalArgumentException("Unknown output format: " + value);
	}

}