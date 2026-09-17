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

package org.noear.soloncode.sdk.exceptions;

/**
 * Exception thrown when there is an issue with the transport layer. This includes
 * connection failures, process execution errors, CLI not found, and protocol violations.
 *
 * <p>
 * Modeled after MCP Java SDK's {@code McpTransportException}.
 */
public class TransportException extends SolonCodeSDKException {

	private final Integer exitCode;

	private final String stderr;

	public TransportException(String message) {
		super(message);
		this.exitCode = null;
		this.stderr = null;
	}

	public TransportException(String message, Throwable cause) {
		super(message, cause);
		this.exitCode = null;
		this.stderr = null;
	}

	public TransportException(String message, Integer exitCode, String stderr) {
		super(buildMessage(message, exitCode, stderr));
		this.exitCode = exitCode;
		this.stderr = stderr;
	}

	public Integer getExitCode() {
		return exitCode;
	}

	public String getStderr() {
		return stderr;
	}

	private static String buildMessage(String message, Integer exitCode, String stderr) {
		StringBuilder builder = new StringBuilder(message);
		if (exitCode != null) {
			builder.append(" (exit code: ").append(exitCode).append(")");
		}
		if (stderr != null && !stderr.trim().isEmpty()) {
			builder.append("\nError output: ").append(stderr);
		}
		return builder.toString();
	}

	public static TransportException withExitCode(String message, int exitCode) {
		return new TransportException(message, exitCode, null);
	}

	public static TransportException withStderr(String message, String stderr) {
		return new TransportException(message, null, stderr);
	}

	public static TransportException cliNotFound(String cliPath) {
		return new TransportException(cliPath != null ? "SolonCode Code not found: " + cliPath : "SolonCode Code not found");
	}

}
