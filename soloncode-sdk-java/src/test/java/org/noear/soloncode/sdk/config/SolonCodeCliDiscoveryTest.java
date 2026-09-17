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

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for SolonCodeCliDiscovery utility class.
 *
 * <p>原版本继承了 claude SDK 的 {@code @EnabledIfEnvironmentVariable("ANTHROPIC_API_KEY")}
 * 门控：soloncode 的 CLI 发现与 Anthropic 凭证毫无关系，那个门控只会让两个用例在
 * 所有正常环境里永久 skip。现改为按“CLI 是否可发现”做 {@code Assumptions}，并在
 * 可发现时做真断言。</p>
 *
 * @author Mark Pollack
 */
class SolonCodeCliDiscoveryTest {

	@Test
	void testGetDiscoveredPath() {
		SolonCodeCliDiscovery.forceRediscovery();
		String discoveredPath = SolonCodeCliDiscovery.getDiscoveredPath();
		assumeTrue(discoveredPath != null, "soloncode CLI 不在当前环境中，跳过路径断言");

		// getDiscoveredPath() should always return a full, absolute path
		Path cliPath = Paths.get(discoveredPath);
		assertThat(cliPath.isAbsolute()).as("discovered path must be absolute").isTrue();
		assertThat(Files.exists(cliPath)).isTrue();
		assertThat(Files.isExecutable(cliPath)).isTrue();
		assertThat(cliPath.getFileName().toString()).contains("soloncode");
	}

	@Test
	void testIsSolonCodeCliAvailable() {
		SolonCodeCliDiscovery.forceRediscovery();
		boolean isAvailable = SolonCodeCliDiscovery.isSolonCodeCliAvailable();
		assumeTrue(isAvailable, "soloncode CLI 不在当前环境中，跳过可用性断言");

		// available 与 getDiscoveredPath() 必须一致，否则调用方会拿到 null 命令
		assertThat(SolonCodeCliDiscovery.getDiscoveredPath()).isNotNull();
	}

	@Test
	void testGetDiscoveredPathWhenNotAvailable() {
		// Clear any system properties that might help discovery
		String originalPath = System.getProperty("soloncode.cli.path");
		try {
			System.clearProperty("soloncode.cli.path");
			// Force rediscovery to clear any cached results
			SolonCodeCliDiscovery.forceRediscovery();

			// If SolonCode is truly not available, this should return null
			// If it's available, that's fine too - we're just testing the method works
			String path = SolonCodeCliDiscovery.getDiscoveredPath();
			// Should not throw - just return null if not found
		}
		finally {
			if (originalPath != null) {
				System.setProperty("soloncode.cli.path", originalPath);
			}
			// Force rediscovery to restore normal state
			SolonCodeCliDiscovery.forceRediscovery();
		}
	}

	@Test
	void testDiscoverSolonCodePathWhenNotAvailable() {
		// Test the exception-throwing discovery method
		String originalPath = System.getProperty("soloncode.cli.path");
		try {
			System.setProperty("soloncode.cli.path", "/nonexistent/soloncode");
			SolonCodeCliDiscovery.forceRediscovery();

			assertThatThrownBy(() -> SolonCodeCliDiscovery.discoverSolonCodePath())
				.isInstanceOf(SolonCodeCliDiscovery.SolonCodeCliNotFoundException.class);
		}
		finally {
			if (originalPath != null) {
				System.setProperty("soloncode.cli.path", originalPath);
			}
			else {
				System.clearProperty("soloncode.cli.path");
			}
			// Force rediscovery to restore normal state
			SolonCodeCliDiscovery.forceRediscovery();
		}
	}

}