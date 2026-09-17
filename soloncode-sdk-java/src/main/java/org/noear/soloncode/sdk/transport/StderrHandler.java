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

package org.noear.soloncode.sdk.transport;

import org.slf4j.Logger;

/**
 * Callback interface for handling stderr output from the SolonCode CLI. Following the MCP
 * SDK pattern with Consumer&lt;String&gt; style functional interface.
 *
 * <p>
 * Stderr contains diagnostic information, debug logs, and error messages from the CLI.
 * This handler allows SDK users to capture and process this output for debugging,
 * monitoring, or logging purposes.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>
 * {@code
 * // Log stderr to SLF4J
 * StderrHandler handler = StderrHandler.logging(logger);
 *
 * // Custom processing
 * StderrHandler custom = line -> {
 *     if (line.contains("ERROR")) {
 *         alerting.notify(line);
 *     }
 * };
 * }
 * </pre>
 *
 * @see CLIOptions
 * @see StdioTransport
 */
@FunctionalInterface
public interface StderrHandler {

	/**
	 * Handles a single line of stderr output from the CLI.
	 * @param line the stderr line (without trailing newline)
	 */
	void handle(String line);

	/**
	 * Creates a handler that logs stderr at DEBUG level.
	 * @param logger the SLF4J logger to use
	 * @return a handler that logs to the given logger
	 */
	static StderrHandler logging(Logger logger) {
		return line -> logger.debug("CLI stderr: {}", line);
	}

	/**
	 * Creates a handler that logs stderr at INFO level.
	 * @param logger the SLF4J logger to use
	 * @return a handler that logs to the given logger
	 */
	static StderrHandler loggingInfo(Logger logger) {
		return line -> logger.info("CLI stderr: {}", line);
	}

	/**
	 * Creates a handler that does nothing (discards stderr).
	 * @return a no-op handler
	 */
	static StderrHandler noop() {
		return line -> {
		};
	}

	/**
	 * Creates a handler that prints stderr to System.err.
	 * @return a handler that prints to System.err
	 */
	static StderrHandler systemErr() {
		return System.err::println;
	}

	/**
	 * Chains this handler with another handler.
	 * @param other the other handler to also invoke
	 * @return a combined handler that calls both
	 */
	default StderrHandler andThen(StderrHandler other) {
		return line -> {
			this.handle(line);
			other.handle(line);
		};
	}

}
