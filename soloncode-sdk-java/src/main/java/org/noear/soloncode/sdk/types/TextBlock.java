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

package org.noear.soloncode.sdk.types;

import org.noear.snack4.annotation.ONodeAttr;

import java.util.Objects;

/**
 * Text content block. Corresponds to TextBlock dataclass in Python SDK.
 */
public final class TextBlock implements ContentBlock {

	@ONodeAttr(name = "text")
	private final String text;

	public TextBlock(@ONodeAttr(name = "text") String text) {
		this.text = text;
	}

	public String text() {
		return text;
	}

	@Override
	public String getType() {
		return "text";
	}

	public static TextBlock of(String text) {
		return new TextBlock(text);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof TextBlock)) {
			return false;
		}
		TextBlock that = (TextBlock) o;
		return Objects.equals(text, that.text);
	}

	@Override
	public int hashCode() {
		return Objects.hash(text);
	}

	@Override
	public String toString() {
		return "TextBlock[text=" + text + "]";
	}
}
