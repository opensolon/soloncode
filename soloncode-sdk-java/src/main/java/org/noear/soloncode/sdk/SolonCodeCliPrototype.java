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

package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.config.SolonCodeCliDiscovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.stream.LogOutputStream;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MVP prototype for validating zt-exec integration with SolonCode CLI. This class
 * demonstrates basic process execution, streaming output, and error handling.
 */
public class SolonCodeCliPrototype {

	private static final Logger logger = LoggerFactory.getLogger(SolonCodeCliPrototype.class);

	private final Path workingDirectory;

	private final String cliCommand;

	public SolonCodeCliPrototype(Path workingDirectory) {
		this(workingDirectory, null);
	}

	public SolonCodeCliPrototype(Path workingDirectory, String cliPath) {
		this.workingDirectory = workingDirectory;
		this.cliCommand = cliPath != null ? cliPath : findSolonCodeCommand();
	}

	/**
	 * Test basic ProcessExecutor with SolonCode CLI
	 */
	public void testBasicExecution() throws Exception {
		logger.info("Testing basic SolonCode CLI execution");

		ProcessResult result = new ProcessExecutor().command(cliCommand, "--version")
			.directory(workingDirectory.toFile())
			.timeout(10, TimeUnit.SECONDS)
			.redirectError(Slf4jStream.of(getClass()).asError())
			.readOutput(true)
			.execute();

		logger.info("SolonCode CLI version: {}", result.outputUTF8().trim());
		logger.info("Exit code: {}", result.getExitValue());
	}

	/**
	 * Test streaming JSON output from SolonCode CLI
	 */
	public void testStreamingOutput(String prompt) throws Exception {
		logger.info("Testing streaming output with prompt: {}", prompt);

		LogOutputStream lineProcessor = new LogOutputStream() {
			@Override
			protected void processLine(String line) {
				logger.info("Received line: {}", line);
				// In real implementation, this would parse JSON and emit messages
			}
		};

		ProcessResult result = new ProcessExecutor()
			.command(cliCommand, "run", prompt, "--output-format", "stream-json", "--verbose")
			.directory(workingDirectory.toFile())
			.environment("SOLONCODE_ENTRYPOINT", "sdk-java")
			.timeout(60, TimeUnit.SECONDS)
			.redirectOutput(lineProcessor)
			.redirectError(Slf4jStream.of(getClass()).asError())
			.readOutput(true)
			.execute();

		logger.info("Streaming completed with exit code: {}", result.getExitValue());
	}

	/**
	 * Test line-by-line processing for real-time streaming
	 */
	public void testRealTimeStreaming(String prompt) throws Exception {
		logger.info("Testing real-time streaming");

		StringBuilder jsonBuffer = new StringBuilder();

		LogOutputStream realtimeProcessor = new LogOutputStream() {
			@Override
			protected void processLine(String line) {
				logger.debug("Processing line: {}", line);

				// Simple JSON detection - in real implementation would be more robust
				if (line.trim().startsWith("{") && line.trim().endsWith("}")) {
					logger.info("Complete JSON message: {}", line);
					// Here we would parse and emit the message
				}
				else {
					// Buffer partial JSON
					jsonBuffer.append(line).append("\n");
				}
			}
		};

		ProcessResult result = new ProcessExecutor()
			.command(cliCommand, "run", prompt, "--output-format", "stream-json", "--verbose")
			.directory(workingDirectory.toFile())
			.environment("SOLONCODE_ENTRYPOINT", "sdk-java")
			.timeout(60, TimeUnit.SECONDS)
			.redirectOutput(realtimeProcessor)
			.redirectError(Slf4jStream.of(getClass()).asError())
			.readOutput(true)
			.execute();

		if (jsonBuffer.length() > 0) {
			logger.warn("Remaining buffer content: {}", jsonBuffer.toString());
		}

		logger.info("Real-time streaming completed with exit code: {}", result.getExitValue());
	}

	/**
	 * Discover SolonCode CLI command path.
	 *
	 * <p>统一走 {@link org.noear.soloncode.sdk.config.SolonCodeCliDiscovery}，避免这里维护
	 * 第二套候选路径与探测超时（原实现沿用的是 claude 的候选名，在 soloncode 上必然探不到）。</p>
	 */
	private String findSolonCodeCommand() {
		try {
			String path = SolonCodeCliDiscovery.discoverSolonCodePath();
			logger.info("Found SolonCode CLI at: {}", path);
			return path;
		}
		catch (Exception e) {
			logger.warn("SolonCode CLI not found, using default 'soloncode'", e);
			return "soloncode";
		}
	}

	/**
	 * Main method for testing the prototype
	 */
	public static void main(String[] args) {
		try {
			Path workingDir = Paths.get(System.getProperty("user.dir"));
			SolonCodeCliPrototype prototype = new SolonCodeCliPrototype(workingDir);

			// Test basic execution
			prototype.testBasicExecution();

			// Test streaming with a simple prompt
			prototype.testStreamingOutput("What is 2+2?");

			// Test real-time streaming
			prototype.testRealTimeStreaming("Explain the concept of recursion briefly");

		}
		catch (Exception e) {
			logger.error("Prototype test failed", e);
			System.exit(1);
		}
	}

}