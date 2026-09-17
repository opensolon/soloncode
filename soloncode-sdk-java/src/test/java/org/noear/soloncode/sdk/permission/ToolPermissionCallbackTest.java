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

package org.noear.soloncode.sdk.permission;

import org.noear.soloncode.sdk.util.SdkCollections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for tool permission callback functionality.
 */
class ToolPermissionCallbackTest {

	@Nested
	@DisplayName("PermissionResult")
	class PermissionResultTests {

		@Test
		@DisplayName("allow() creates Allow result without modified input")
		void allowWithoutModification() {
			PermissionResult result = PermissionResult.allow();

			assertThat(result.isAllowed()).isTrue();
			assertThat(result).isInstanceOf(PermissionResult.Allow.class);

			PermissionResult.Allow allow = (PermissionResult.Allow) result;
			assertThat(allow.hasUpdatedInput()).isFalse();
			assertThat(allow.updatedInput()).isNull();
		}

		@Test
		@DisplayName("allow(input) creates Allow result with modified input")
		void allowWithModification() {
			Map<String, Object> updatedInput = SdkCollections.map("file_path", "/safe/path.txt", "safe_mode", true);
			PermissionResult result = PermissionResult.allow(updatedInput);

			assertThat(result.isAllowed()).isTrue();
			assertThat(result).isInstanceOf(PermissionResult.Allow.class);

			PermissionResult.Allow allow = (PermissionResult.Allow) result;
			assertThat(allow.hasUpdatedInput()).isTrue();
			assertThat(allow.updatedInput()).containsEntry("file_path", "/safe/path.txt")
				.containsEntry("safe_mode", true);
		}

		@Test
		@DisplayName("deny() creates Deny result without message")
		void denyWithoutMessage() {
			PermissionResult result = PermissionResult.deny();

			assertThat(result.isAllowed()).isFalse();
			assertThat(result).isInstanceOf(PermissionResult.Deny.class);

			PermissionResult.Deny deny = (PermissionResult.Deny) result;
			assertThat(deny.hasMessage()).isFalse();
			assertThat(deny.message()).isNull();
		}

		@Test
		@DisplayName("deny(message) creates Deny result with message")
		void denyWithMessage() {
			PermissionResult result = PermissionResult.deny("Access denied for security reasons");

			assertThat(result.isAllowed()).isFalse();
			assertThat(result).isInstanceOf(PermissionResult.Deny.class);

			PermissionResult.Deny deny = (PermissionResult.Deny) result;
			assertThat(deny.hasMessage()).isTrue();
			assertThat(deny.message()).isEqualTo("Access denied for security reasons");
		}

	}

	@Nested
	@DisplayName("ToolPermissionCallback")
	class ToolPermissionCallbackTests {

		@Test
		@DisplayName("allowAll() callback always allows")
		void allowAllCallback() {
			ToolPermissionCallback callback = ToolPermissionCallback.allowAll();

			PermissionResult result = callback.checkPermission("Bash", SdkCollections.map("command", "rm -rf /"),
					ToolPermissionContext.empty());

			assertThat(result.isAllowed()).isTrue();
		}

		@Test
		@DisplayName("denyAll() callback always denies")
		void denyAllCallback() {
			ToolPermissionCallback callback = ToolPermissionCallback.denyAll();

			PermissionResult result = callback.checkPermission("Read", SdkCollections.map("file_path", "/etc/passwd"),
					ToolPermissionContext.empty());

			assertThat(result.isAllowed()).isFalse();
			PermissionResult.Deny deny = (PermissionResult.Deny) result;
			assertThat(deny.message()).contains("denied by policy");
		}

		@Test
		@DisplayName("Custom callback can deny specific tools")
		void customDenyCallback() {
			ToolPermissionCallback callback = (toolName, input, context) -> {
				if ("Bash".equals(toolName)) {
					return PermissionResult.deny("Bash commands are not allowed");
				}
				return PermissionResult.allow();
			};

			// Bash should be denied
			PermissionResult bashResult = callback.checkPermission("Bash", SdkCollections.map("command", "ls"),
					ToolPermissionContext.empty());
			assertThat(bashResult.isAllowed()).isFalse();

			// Other tools should be allowed
			PermissionResult readResult = callback.checkPermission("Read", SdkCollections.map("file_path", "/tmp/test.txt"),
					ToolPermissionContext.empty());
			assertThat(readResult.isAllowed()).isTrue();
		}

		@Test
		@DisplayName("Custom callback can modify tool input")
		void customModifyCallback() {
			ToolPermissionCallback callback = (toolName, input, context) -> {
				if ("Write".equals(toolName)) {
					// Add safety flag to all Write operations
					Map<String, Object> safeInput = new HashMap<>(input);
					safeInput.put("backup", true);
					return PermissionResult.allow(safeInput);
				}
				return PermissionResult.allow();
			};

			Map<String, Object> originalInput = SdkCollections.map("file_path", "/tmp/output.txt", "content", "hello");
			PermissionResult result = callback.checkPermission("Write", originalInput, ToolPermissionContext.empty());

			assertThat(result.isAllowed()).isTrue();
			PermissionResult.Allow allow = (PermissionResult.Allow) result;
			assertThat(allow.hasUpdatedInput()).isTrue();
			assertThat(allow.updatedInput()).containsEntry("file_path", "/tmp/output.txt")
				.containsEntry("content", "hello")
				.containsEntry("backup", true);
		}

		@Test
		@DisplayName("Custom callback can inspect input to make decisions")
		void customInspectInputCallback() {
			ToolPermissionCallback callback = (toolName, input, context) -> {
				if ("Bash".equals(toolName)) {
					String command = String.valueOf(input.get("command"));
					if (command.contains("rm -rf")) {
						return PermissionResult.deny("Destructive command blocked");
					}
				}
				return PermissionResult.allow();
			};

			// Safe command should be allowed
			PermissionResult safeResult = callback.checkPermission("Bash", SdkCollections.map("command", "ls -la"),
					ToolPermissionContext.empty());
			assertThat(safeResult.isAllowed()).isTrue();

			// Destructive command should be denied
			PermissionResult dangerousResult = callback.checkPermission("Bash", SdkCollections.map("command", "rm -rf /tmp/*"),
					ToolPermissionContext.empty());
			assertThat(dangerousResult.isAllowed()).isFalse();
		}

	}

	@Nested
	@DisplayName("ToolPermissionContext")
	class ToolPermissionContextTests {

		@Test
		@DisplayName("empty() creates context with no data")
		void emptyContext() {
			ToolPermissionContext context = ToolPermissionContext.empty();

			assertThat(context.hasBlockedPath()).isFalse();
			assertThat(context.hasPermissionSuggestions()).isFalse();
			assertThat(context.requestId()).isNull();
		}

		@Test
		@DisplayName("of() creates context with all fields")
		void fullContext() {
			List<Map<String, Object>> suggestions = SdkCollections.list(SdkCollections.map("suggestion", "grant_once"));
			ToolPermissionContext context = ToolPermissionContext.of(suggestions, "/etc/passwd", "req-123");

			assertThat(context.hasBlockedPath()).isTrue();
			assertThat(context.blockedPath()).isEqualTo("/etc/passwd");
			assertThat(context.hasPermissionSuggestions()).isTrue();
			assertThat(context.permissionSuggestions()).hasSize(1);
			assertThat(context.requestId()).isEqualTo("req-123");
		}

		@Test
		@DisplayName("Callback can use context for decisions")
		void callbackUsesContext() {
			ToolPermissionCallback callback = (toolName, input, context) -> {
				if (context.hasBlockedPath() && context.blockedPath().startsWith("/etc")) {
					return PermissionResult.deny("System files are protected");
				}
				return PermissionResult.allow();
			};

			ToolPermissionContext systemContext = ToolPermissionContext.of(SdkCollections.list(), "/etc/passwd", "req-1");
			ToolPermissionContext safeContext = ToolPermissionContext.of(SdkCollections.list(), "/tmp/test.txt", "req-2");

			assertThat(callback.checkPermission("Read", SdkCollections.map(), systemContext).isAllowed()).isFalse();
			assertThat(callback.checkPermission("Read", SdkCollections.map(), safeContext).isAllowed()).isTrue();
		}

	}

}
