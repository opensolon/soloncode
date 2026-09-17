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

import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Integration test for SolonCodeCliPrototype.
 *
 * <p>
 * This test extends {@link SolonCodeCliTestBase} which automatically discovers SolonCode CLI
 * and ensures all tests fail gracefully with a clear message if SolonCode CLI is not
 * available.
 * </p>
 */
class SolonCodeCliPrototypeIT extends SolonCodeCliTestBase {

	@Test
	void testBasicExecution() throws Exception {
		Path workingDir = Paths.get(System.getProperty("user.dir"));
		SolonCodeCliPrototype prototype = new SolonCodeCliPrototype(workingDir, getSolonCodeCliPath());

		// SolonCode CLI is guaranteed to be available here due to SolonCodeCliTestBase
		prototype.testBasicExecution();
	}

	@Test
	void testStreamingOutput() throws Exception {
		Path workingDir = Paths.get(System.getProperty("user.dir"));
		SolonCodeCliPrototype prototype = new SolonCodeCliPrototype(workingDir, getSolonCodeCliPath());

		prototype.testStreamingOutput("What is 1+1?");
	}

	@Test
	void testRealTimeStreaming() throws Exception {
		Path workingDir = Paths.get(System.getProperty("user.dir"));
		SolonCodeCliPrototype prototype = new SolonCodeCliPrototype(workingDir, getSolonCodeCliPath());

		prototype.testRealTimeStreaming("Hello, world!");
	}

}