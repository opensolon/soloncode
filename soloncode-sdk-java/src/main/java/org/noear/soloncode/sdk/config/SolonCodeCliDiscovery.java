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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.util.concurrent.TimeUnit;

/**
 * Utility for discovering SolonCode CLI executable location across different environments.
 *
 * <p>
 * This utility attempts to find the SolonCode CLI executable in common installation
 * locations and provides a fallback for development environments.
 * </p>
 */
public class SolonCodeCliDiscovery {

	private static final Logger logger = LoggerFactory.getLogger(SolonCodeCliDiscovery.class);

	private static String discoveredPath;

	private static boolean discoveryAttempted = false;

	/**
	 * Discovers the SolonCode CLI executable path.
	 * @return the path to SolonCode CLI executable
	 * @throws SolonCodeCliNotFoundException if SolonCode CLI cannot be found
	 */
	public static synchronized String discoverSolonCodePath() throws SolonCodeCliNotFoundException {
		if (discoveryAttempted) {
			if (discoveredPath != null) {
				return discoveredPath;
			}
			else {
				throw new SolonCodeCliNotFoundException("SolonCode CLI was not found during discovery");
			}
		}

		discoveryAttempted = true;

				// Check system property first
		String systemPropertyPath = System.getProperty("soloncode.cli.path");
		if (systemPropertyPath != null) {
			String resolvedPath = testAndResolveSolonCodeExecutable(systemPropertyPath);
			if (resolvedPath != null) {
				discoveredPath = resolvedPath;
				logger.info("SolonCode CLI found at system property: {}", resolvedPath);
				return discoveredPath;
			}
			else {
				// If system property is set but doesn't work, fail immediately
				throw new SolonCodeCliNotFoundException(
						"SolonCode CLI specified in system property 'soloncode.cli.path' is not available: "
								+ systemPropertyPath);
			}
		}
				
		// Attempt discovery in order of preference
		String userHome = System.getProperty("user.home");
		String[] candidates = { "soloncode", // In PATH
				userHome + "/.local/bin/soloncode", // Local installation
				"/usr/local/bin/soloncode", // System-wide installation
			userHome + "/.nvm/versions/node/latest/bin/soloncode", // NVM installation
			"/usr/bin/soloncode" // Standard system path
		};

		for (String candidate : candidates) {
			String resolvedPath = testAndResolveSolonCodeExecutable(candidate);
			if (resolvedPath != null) {
				discoveredPath = resolvedPath;
				logger.info("SolonCode CLI found at: {}", resolvedPath);
				return discoveredPath;
			}
		}

		// If discovery fails, provide detailed error message
		StringBuilder errorMessage = new StringBuilder();
		errorMessage.append("SolonCode CLI executable not found. Searched locations:\n");
		for (String candidate : candidates) {
			errorMessage.append("  - ").append(candidate).append("\n");
		}
		errorMessage.append("\nPlease ensure SolonCode CLI is installed and accessible.\n");
		errorMessage.append("You can also set the system property 'soloncode.cli.path' to point at the executable.");

		throw new SolonCodeCliNotFoundException(errorMessage.toString());
	}

	/**
	 * 探测 CLI 可用性的超时（秒）。
	 *
	 * <p>soloncode 是 JVM 程序，冷启动 + Solon 容器初始化通常需 4~8 秒，5 秒探测会
	 * 随机失败（表现为「CLI is not available」）。默认放宽到 20 秒，可用系统属性
	 * {@code soloncode.cli.probe-timeout} 覆盖。</p>
	 */
	private static int probeTimeoutSeconds() {
		String v = System.getProperty("soloncode.cli.probe-timeout");
		if (v != null && v.trim().length() > 0) {
			try {
				int seconds = Integer.parseInt(v.trim());
				if (seconds > 0) {
					return seconds;
				}
			}
			catch (NumberFormatException ignored) {
				// 落回默认值
			}
		}
		return 20;
	}

	/**
	 * Tests if a SolonCode CLI executable exists and works at the given path. When the path
	 * is a command name (like "soloncode"), this resolves it to the full path.
	 * @param path command name or full path to test
	 * @return the resolved full path if the executable works, null if not found
	 */
	private static String testAndResolveSolonCodeExecutable(String path) {
		try {
			ProcessResult result = new ProcessExecutor().command(path, "--version")
				.timeout(probeTimeoutSeconds(), TimeUnit.SECONDS)
				.readOutput(true)
				.execute();

			String version = result.outputUTF8().trim();
			// soloncode CLI 的 --version 可能以非零退出码结束但已输出有效版本信息（如 "SolonCode v2026.x"），
			// 只要输出非空且包含 SolonCode 标识即视为可用
			boolean usable = result.getExitValue() == 0
					|| (version.length() > 0 && version.toLowerCase().contains("soloncode"));

			if (usable) {
				logger.debug("Found SolonCode CLI at {} with version: {}", path, version);

				// If this is just a command name (no path separators), resolve to full
				// path
				if (!path.contains("/") && !path.contains("\\")) {
					String resolvedPath = resolveCommandPath(path);
					if (resolvedPath != null) {
						logger.debug("Resolved command '{}' to full path: {}", path, resolvedPath);
						return resolvedPath;
					}
				}

				// Return the original path (already a full path)
				return path;
			}
		}
		catch (Exception e) {
			logger.debug("SolonCode CLI not found at: {} ({})", path, e.getMessage());
		}
		return null;
	}

	/**
	 * Resolves a command name to its full path using platform-appropriate commands. Uses
	 * 'which' on Unix/Linux/macOS and 'where' on Windows.
	 */
	private static String resolveCommandPath(String commandName) {
		try {
			String osName = System.getProperty("os.name").toLowerCase();
			String[] command;

			if (osName.contains("win")) {
				// Windows uses 'where'
				command = new String[] { "where", commandName };
			}
			else {
				// Unix/Linux/macOS use 'which'
				command = new String[] { "which", commandName };
			}

			ProcessResult result = new ProcessExecutor().command(command)
				.timeout(3, TimeUnit.SECONDS)
				.readOutput(true)
				.execute();

			if (result.getExitValue() == 0) {
				String output = result.outputUTF8().trim();
				// Windows 'where' can return multiple paths, take the first one
				if (osName.contains("win") && output.contains("\n")) {
					output = output.split("\n")[0].trim();
				}
				return output;
			}
		}
		catch (Exception e) {
			logger.debug("Failed to resolve command path for '{}': {}", commandName, e.getMessage());
		}
		return null;
	}

	/**
	 * Gets the SolonCode CLI path, performing discovery on first use.
	 *
	 * <p>claude SDK 的同名方法是纯字段 getter：在没有其他代码先调用过
	 * {@link #discoverSolonCodePath()} 时永远返回 null，方法名却读起来像“取到路径”，
	 * 是一个容易误用的陷阱（在测试里就表现为 forceRediscovery() 后拿到 null）。
	 * 这里改为懒触发发现，失败时返回 null 而不抛异常。</p>
	 * @return the resolved absolute path, or null if the CLI cannot be discovered
	 */
	public static String getDiscoveredPath() {
		try {
			return discoverSolonCodePath();
		}
		catch (SolonCodeCliNotFoundException e) {
			logger.debug("SolonCode CLI not discovered: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Checks if SolonCode CLI is available without throwing exceptions.
	 * @return true if SolonCode CLI is available, false otherwise
	 */
	public static boolean isSolonCodeCliAvailable() {
		try {
			discoverSolonCodePath();
			return true;
		}
		catch (SolonCodeCliNotFoundException e) {
			return false;
		}
	}

	/**
	 * Forces re-discovery of SolonCode CLI path. Useful for testing or when installation
	 * state may have changed.
	 */
	public static synchronized void forceRediscovery() {
		discoveryAttempted = false;
		discoveredPath = null;
	}

	/**
	 * Exception thrown when SolonCode CLI cannot be discovered.
	 */
	public static class SolonCodeCliNotFoundException extends Exception {

		public SolonCodeCliNotFoundException(String message) {
			super(message);
		}

	}

}