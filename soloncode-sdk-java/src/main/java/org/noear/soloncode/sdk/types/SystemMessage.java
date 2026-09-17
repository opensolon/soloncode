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

import java.util.Map;
import java.util.Objects;

/**
 * System message with metadata. Corresponds to SystemMessage dataclass in Python SDK.
 */
public final class SystemMessage implements Message {

	@ONodeAttr(name = "subtype")
	private final String subtype;

	@ONodeAttr(name = "data")
	private final Map<String, Object> data;

	public SystemMessage(@ONodeAttr(name = "subtype") String subtype, @ONodeAttr(name = "data") Map<String, Object> data) {
		this.subtype = subtype;
		this.data = data;
	}

	public String subtype() {
		return subtype;
	}

	public Map<String, Object> data() {
		return data;
	}

	@Override
	public String getType() {
		return "system";
	}

	@Override
	public boolean isTerminal() {
		return "error".equalsIgnoreCase(subtype);
	}

	@Override
	public String toString() {
		return String.format("[System: %s]", subtype != null ? subtype : "unknown");
	}

	public static SystemMessage of(String subtype, Map<String, Object> data) {
		return new SystemMessage(subtype, data);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof SystemMessage)) {
			return false;
		}
		SystemMessage that = (SystemMessage) o;
		return Objects.equals(subtype, that.subtype) && Objects.equals(data, that.data);
	}

	@Override
	public int hashCode() {
		return Objects.hash(subtype, data);
	}
}
