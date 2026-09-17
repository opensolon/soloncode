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

package org.noear.soloncode.sdk.test;

import org.noear.soloncode.sdk.config.SolonCodeCliDiscovery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base class for tests that require SolonCode CLI to be available.
 *
 * <p>
 * This class automatically discovers the SolonCode CLI executable and makes it available to
 * subclasses. If SolonCode CLI cannot be found, all tests will fail with a clear message
 * indicating the issue.
 * </p>
 *
 * <p>
 * Subclasses can access the discovered CLI path via {@link #getSolonCodeCliPath()}.
 * </p>
 *
 * <p>
 * <strong>Usage:</strong>
 * </p>
 * <pre>
 * class MySolonCodeTest extends SolonCodeCliTestBase {
 *
 *     {@literal @}Test
 *     void testSomething() {
 *         // SolonCode CLI is guaranteed to be available here
 *         String cliPath = getSolonCodeCliPath();
 *         // ... test implementation
 *     }
 * }
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SolonCodeCliTestBase {

	private static final Logger logger = LoggerFactory.getLogger(SolonCodeCliTestBase.class);

	/**
	 * 集成测试调用真实 CLI 的统一超时（秒）。真实模型应答较慢，但必须有上限，避免用例无限期挂住。
	 */
	protected static final int CLI_TIMEOUT_SECONDS = 120;

	private static String cliPath;

	/**
	 * Discovers SolonCode CLI before any tests run. If discovery fails, all tests in
	 * subclasses will fail with a clear error message.
	 */
	@BeforeAll
	static void discoverSolonCodeCli() {
		try {
			cliPath = org.noear.soloncode.sdk.config.SolonCodeCliDiscovery.discoverSolonCodePath();
			logger.info("SolonCode CLI tests will use executable at: {}", cliPath);
		}
		catch (org.noear.soloncode.sdk.config.SolonCodeCliDiscovery.SolonCodeCliNotFoundException e) {
			String errorMsg = "SolonCode CLI Integration Tests Failed: " + e.getMessage();
			logger.error(errorMsg);

			// Throw a runtime exception that will cause all tests to fail with a clear
			// message
			throw new SolonCodeCliNotAvailableException(errorMsg);
		}
	}

	/**
	 * Gets the discovered SolonCode CLI executable path.
	 * @return the path to SolonCode CLI executable
	 * @throws IllegalStateException if SolonCode CLI discovery hasn't been performed yet
	 */
	protected static String getSolonCodeCliPath() {
		if (cliPath == null) {
			throw new IllegalStateException("SolonCode CLI path not discovered. Ensure @BeforeAll method has run.");
		}
		return cliPath;
	}

	/**
	 * Checks if SolonCode CLI is available for testing.
	 * @return true if SolonCode CLI is available, false otherwise
	 */
	protected static boolean isSolonCodeCliAvailable() {
		return cliPath != null;
	}

	/**
	 * 集成测试的工作目录：一次性临时目录。
	 *
	 * <p>不要用仓库目录作为 CLI 的 cwd——CLI 会把工作区的 AGENTS/CODE.md、技能、历史会话
	 * 一并注入系统提示（实测提示词从 5.5k tokens 涨到 9.3k+，单轮耗时从 5s 涨到 190s 甚至超时）。
	 * 契约测试只验证 stdio 协议，不需要仓库上下文。</p>
	 */
	protected static Path itWorkDir() {
		if (itWorkDir == null) {
			synchronized (SolonCodeCliTestBase.class) {
				if (itWorkDir == null) {
					try {
						itWorkDir = Files.createTempDirectory("soloncode-it-");
						itWorkDir.toFile().deleteOnExit();
					}
					catch (IOException e) {
						throw new IllegalStateException("Failed to create IT working directory", e);
					}
				}
			}
		}
		return itWorkDir;
	}

	private static volatile Path itWorkDir;

	/**
	 * Gets the working directory for test execution. Can be overridden by subclasses if
	 * needed.
	 * @return the working directory path
	 */
	protected Path workingDirectory() {
		return itWorkDir();
	}

	/**
	 * Exception thrown when SolonCode CLI is not available for testing. This is a runtime
	 * exception that will cause test execution to fail.
	 */
	public static class SolonCodeCliNotAvailableException extends RuntimeException {

		public SolonCodeCliNotAvailableException(String message) {
			super(message);
		}

	}

}