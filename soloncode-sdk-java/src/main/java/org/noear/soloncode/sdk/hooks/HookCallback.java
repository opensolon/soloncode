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

package org.noear.soloncode.sdk.hooks;

import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.types.control.HookOutput;

/**
 * Functional interface for hook callbacks. Called when a hook event occurs that matches
 * the registered pattern.
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * HookCallback callback = input -> {
 *     if (input instanceof HookInput.PreToolUseInput preToolUse) {
 *         if (preToolUse.toolName().equals("Bash")) {
 *             // Block dangerous commands
 *             String command = preToolUse.getArgument("command", String.class).orElse("");
 *             if (command.contains("rm -rf")) {
 *                 return HookOutput.block("Dangerous command blocked");
 *             }
 *         }
 *     }
 *     return HookOutput.allow();
 * };
 * }</pre>
 *
 * @see HookInput
 * @see HookOutput
 */
@FunctionalInterface
public interface HookCallback {

	/**
	 * Called when a hook event occurs. The implementation should return a HookOutput
	 * indicating whether to continue, block, or modify the operation.
	 * @param input the hook input containing event details
	 * @return the hook output with decision
	 */
	HookOutput handle(HookInput input);

	/**
	 * Creates a callback that always allows the operation.
	 */
	static HookCallback allow() {
		return input -> HookOutput.allow();
	}

	/**
	 * Creates a callback that always blocks with the given reason.
	 */
	static HookCallback block(String reason) {
		return input -> HookOutput.block(reason);
	}

}
