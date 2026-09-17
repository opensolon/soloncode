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
 * Thinking content block for extended thinking responses. Corresponds to ThinkingBlock
 * dataclass in Python SDK.
 *
 * <p>
 * When SolonCode uses extended thinking, it may include ThinkingBlock content in responses.
 * The thinking field contains the model's reasoning process, and the signature field
 * contains a cryptographic signature for verification.
 * </p>
 */
public final class ThinkingBlock implements ContentBlock {

	@ONodeAttr(name = "thinking")
	private final String thinking;

	@ONodeAttr(name = "signature")
	private final String signature;

	public ThinkingBlock(@ONodeAttr(name = "thinking") String thinking, @ONodeAttr(name = "signature") String signature) {
		this.thinking = thinking;
		this.signature = signature;
	}

	public String thinking() {
		return thinking;
	}

	public String signature() {
		return signature;
	}

	@Override
	public String getType() {
		return "thinking";
	}

	/**
	 * Creates a new ThinkingBlock with the given thinking content.
	 * @param thinking the thinking content
	 * @return a new ThinkingBlock
	 */
	public static ThinkingBlock of(String thinking) {
		return new ThinkingBlock(thinking, null);
	}

	/**
	 * Creates a new ThinkingBlock with the given thinking content and signature.
	 * @param thinking the thinking content
	 * @param signature the cryptographic signature
	 * @return a new ThinkingBlock
	 */
	public static ThinkingBlock of(String thinking, String signature) {
		return new ThinkingBlock(thinking, signature);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ThinkingBlock)) {
			return false;
		}
		ThinkingBlock that = (ThinkingBlock) o;
		return Objects.equals(thinking, that.thinking) && Objects.equals(signature, that.signature);
	}

	@Override
	public int hashCode() {
		return Objects.hash(thinking, signature);
	}

	@Override
	public String toString() {
		return "ThinkingBlock[thinking=" + thinking + ", signature=" + signature + "]";
	}
}
