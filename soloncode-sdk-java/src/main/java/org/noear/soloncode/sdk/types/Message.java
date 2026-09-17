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


/**
 * Base interface for all message types. Corresponds to Message union type in Python SDK.
 */
public interface Message {

	/**
	 * Returns the type of this message.
	 * @return the message type
	 */
	String getType();

	/**
	 * Returns whether this message terminates the current response turn.
	 *
	 * <p>A normal/error {@code result} message is terminal. A top-level protocol
	 * error represented as {@link SystemMessage} with subtype {@code error} is
	 * terminal as well, even when a persistent transport remains connected.</p>
	 * @return true when no more messages belong to the current turn
	 */
	default boolean isTerminal() {
		return false;
	}

}