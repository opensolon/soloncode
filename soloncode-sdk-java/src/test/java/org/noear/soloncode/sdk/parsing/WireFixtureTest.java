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

package org.noear.soloncode.sdk.parsing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.ContentBlock;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;
import org.noear.soloncode.sdk.types.TextBlock;
import org.noear.soloncode.sdk.types.ThinkingBlock;
import org.noear.soloncode.sdk.types.ToolResultBlock;
import org.noear.soloncode.sdk.types.ToolUseBlock;
import org.noear.soloncode.sdk.types.UserMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses REAL stream-json stdout lines captured from a pinned SolonCode CLI version
 * (resources under {@code wire/2.1.162/}). These pin the wire contract: when a CLI
 * upgrade changes the format, these fixtures localize the diff.
 *
 * <p>
 * Notable wire facts these fixtures encode (captured 2026-06-06):
 * <ul>
 * <li>Thinking blocks frequently arrive REDACTED — {@code thinking:""} with a ~2KB
 * {@code signature}. Empirically verified identical on the wire and in the on-disk
 * session transcript (same message id, both empty), i.e. redaction happens upstream of
 * both channels. The parser must copy faithfully and never fabricate content.</li>
 * <li>User events carry a structured {@code tool_use_result} (snake_case twin of the
 * transcript's {@code toolUseResult}) that the typed API does not yet model — it is
 * recoverable via {@code ParsedMessage.RegularMessage#rawJson()}.</li>
 * <li>Result events carry {@code permission_denials} and {@code modelUsage}, also
 * unmodeled, also recoverable via rawJson.</li>
 * </ul>
 */
class WireFixtureTest {

	private static final String FIXTURE_DIR = "/wire/2.1.162/";

	private ControlMessageParser parser;

	@BeforeEach
	void setUp() {
		parser = new ControlMessageParser();
	}

	private String fixture(String name) throws IOException {
		try (InputStream in = WireFixtureTest.class.getResourceAsStream(FIXTURE_DIR + name)) {
			assertThat(in).as("fixture " + name).isNotNull();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
		}
	}

	@Test
	@DisplayName("redacted thinking block is copied faithfully: empty text, signature present")
	void redactedThinkingIsFaithful() throws Exception {
		String line = fixture("assistant-thinking-redacted.json");
		ParsedMessage parsed = parser.parse(line);

		AssistantMessage assistant = (AssistantMessage) parsed.asMessage();
		ThinkingBlock thinking = assistant.content()
			.stream()
			.filter(ThinkingBlock.class::isInstance)
			.map(ThinkingBlock.class::cast)
			.findFirst()
			.get();

		// The empirically-common upstream-redaction case: never fabricate content
		assertThat(thinking.thinking()).isEmpty();
		assertThat(thinking.signature()).isNotBlank();
	}

	@Test
	@DisplayName("non-empty thinking text is copied faithfully (synthetic — wire sends it intermittently)")
	void nonEmptyThinkingIsFaithful() throws Exception {
		String line = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"Let me work through the constraints...\",\"signature\":\"sig123\"}]},\"session_id\":\"sess-1\"}";
		ParsedMessage parsed = parser.parse(line);

		AssistantMessage assistant = (AssistantMessage) parsed.asMessage();
		ThinkingBlock thinking = (ThinkingBlock) assistant.content().get(0);
		assertThat(thinking.thinking()).isEqualTo("Let me work through the constraints...");
		assertThat(thinking.signature()).isEqualTo("sig123");
	}

	@Test
	@DisplayName("rawJson retains the exact wire line on every regular message")
	void rawJsonRetainsWireLine() throws Exception {
		for (String name : new String[] { "system-init.json", "assistant-thinking-redacted.json",
				"assistant-tool-use.json", "assistant-text.json", "user-tool-result.json", "result.json" }) {
			String line = fixture(name);
			ParsedMessage parsed = parser.parse(line);
			assertThat(parsed).as(name).isInstanceOf(ParsedMessage.RegularMessage.class);
			assertThat(((ParsedMessage.RegularMessage) parsed).rawJson()).as(name).isEqualTo(line);
		}
	}

	@Test
	@DisplayName("assistant tool_use fixture parses name/id/input")
	void assistantToolUseParses() throws Exception {
		ParsedMessage parsed = parser.parse(fixture("assistant-tool-use.json"));
		AssistantMessage assistant = (AssistantMessage) parsed.asMessage();
		ToolUseBlock toolUse = assistant.content()
			.stream()
			.filter(ToolUseBlock.class::isInstance)
			.map(ToolUseBlock.class::cast)
			.findFirst()
			.get();
		assertThat(toolUse.id()).isNotBlank();
		assertThat(toolUse.name()).isNotBlank();
		assertThat(toolUse.input()).isNotEmpty();
	}

	@Test
	@DisplayName("assistant text fixture parses non-empty text")
	void assistantTextParses() throws Exception {
		ParsedMessage parsed = parser.parse(fixture("assistant-text.json"));
		AssistantMessage assistant = (AssistantMessage) parsed.asMessage();
		TextBlock text = assistant.content()
			.stream()
			.filter(TextBlock.class::isInstance)
			.map(TextBlock.class::cast)
			.findFirst()
			.get();
		assertThat(text.text()).isNotBlank();
	}

	@Test
	@DisplayName("user fixture parses tool_result block; structured tool_use_result recoverable via rawJson")
	void userToolResultParsesAndStructuredResultRecoverable() throws Exception {
		String line = fixture("user-tool-result.json");
		ParsedMessage parsed = parser.parse(line);
		UserMessage user = (UserMessage) parsed.asMessage();

		ContentBlock block = user.getContentAsBlocks().get(0);
		assertThat(block).isInstanceOf(ToolResultBlock.class);
		assertThat(((ToolResultBlock) block).toolUseId()).isNotBlank();

		// The structured per-tool output is not yet modeled by the typed API but must
		// remain recoverable through the rawJson escape hatch
		assertThat(((ParsedMessage.RegularMessage) parsed).rawJson()).contains("\"tool_use_result\"");
	}

	@Test
	@DisplayName("result fixture parses contract fields; permission_denials/modelUsage recoverable via rawJson")
	void resultParsesAndUnmodeledFieldsRecoverable() throws Exception {
		String line = fixture("result.json");
		ParsedMessage parsed = parser.parse(line);
		ResultMessage result = (ResultMessage) parsed.asMessage();

		assertThat(result.sessionId()).isNotBlank();
		assertThat(result.durationMs()).isPositive();
		assertThat(result.usage()).containsKeys("input_tokens", "output_tokens");

		String rawJson = ((ParsedMessage.RegularMessage) parsed).rawJson();
		assertThat(rawJson).contains("\"permission_denials\"").contains("\"modelUsage\"");
	}

	@Test
	@DisplayName("system init fixture parses as SystemMessage")
	void systemInitParses() throws Exception {
		ParsedMessage parsed = parser.parse(fixture("system-init.json"));
		assertThat(parsed.asMessage()).isInstanceOf(SystemMessage.class);
	}

}
