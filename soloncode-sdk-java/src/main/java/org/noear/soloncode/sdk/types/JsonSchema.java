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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSON Schema representation for structured output. Based on the MCP SDK pattern for
 * schema handling.
 *
 * <p>
 * This class provides a type-safe representation of JSON Schema that can be serialized
 * to JSON for the SolonCode CLI --json-schema flag. It supports common JSON Schema features
 * including properties, required fields, and nested definitions.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * JsonSchema schema = JsonSchema.ofObject(
 *     SdkCollections.map("answer", SdkCollections.map("type", "number")),
 *     SdkCollections.list("answer")
 * );
 *
 * CLIOptions options = CLIOptions.builder()
 *     .jsonSchema(schema.toMap())
 *     .build();
 * }</pre>
 */
public final class JsonSchema {

	@ONodeAttr(name = "type")
	private final String type;

	@ONodeAttr(name = "properties")
	private final Map<String, Object> properties;

	@ONodeAttr(name = "required")
	private final List<String> required;

	@ONodeAttr(name = "additionalProperties")
	private final Boolean additionalProperties;

	@ONodeAttr(name = "$defs")
	private final Map<String, Object> defs;

	public JsonSchema(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "properties") Map<String, Object> properties,
			@ONodeAttr(name = "required") List<String> required,
			@ONodeAttr(name = "additionalProperties") Boolean additionalProperties,
			@ONodeAttr(name = "$defs") Map<String, Object> defs) {
		this.type = type;
		this.properties = properties;
		this.required = required;
		this.additionalProperties = additionalProperties;
		this.defs = defs;
	}

	public String type() {
		return type;
	}

	public Map<String, Object> properties() {
		return properties;
	}

	public List<String> required() {
		return required;
	}

	public Boolean additionalProperties() {
		return additionalProperties;
	}

	public Map<String, Object> defs() {
		return defs;
	}

	/**
	 * Creates a simple object schema with the given properties and required fields.
	 * @param properties map of property names to their schema definitions
	 * @param required list of required property names
	 * @return a new JsonSchema for an object type
	 */
	public static JsonSchema ofObject(Map<String, Object> properties, List<String> required) {
		return new JsonSchema("object", properties, required, false, null);
	}

	/**
	 * Creates a simple object schema with the given properties (all optional).
	 * @param properties map of property names to their schema definitions
	 * @return a new JsonSchema for an object type with no required fields
	 */
	public static JsonSchema ofObject(Map<String, Object> properties) {
		return new JsonSchema("object", properties, null, false, null);
	}

	/**
	 * Creates a schema from a raw Map representation. This is useful when working with
	 * schemas that are already in Map form.
	 * @param schemaMap the schema as a Map
	 * @return a new JsonSchema parsed from the Map
	 */
	@SuppressWarnings("unchecked")
	public static JsonSchema fromMap(Map<String, Object> schemaMap) {
		if (schemaMap == null) {
			return null;
		}

		String type = (String) schemaMap.get("type");
		Map<String, Object> properties = (Map<String, Object>) schemaMap.get("properties");
		List<String> required = (List<String>) schemaMap.get("required");
		Boolean additionalProperties = (Boolean) schemaMap.get("additionalProperties");
		Map<String, Object> defs = (Map<String, Object>) schemaMap.get("$defs");

		return new JsonSchema(type, properties, required, additionalProperties, defs);
	}

	/**
	 * Converts this schema to a Map representation suitable for JSON serialization.
	 * @return the schema as a Map
	 */
	public Map<String, Object> toMap() {
		java.util.Map<String, Object> map = new java.util.HashMap<>();
		if (type != null) {
			map.put("type", type);
		}
		if (properties != null) {
			map.put("properties", properties);
		}
		if (required != null && !required.isEmpty()) {
			map.put("required", required);
		}
		if (additionalProperties != null) {
			map.put("additionalProperties", additionalProperties);
		}
		if (defs != null && !defs.isEmpty()) {
			map.put("$defs", defs);
		}
		return map;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof JsonSchema)) {
			return false;
		}
		JsonSchema that = (JsonSchema) o;
		return Objects.equals(type, that.type) && Objects.equals(properties, that.properties)
				&& Objects.equals(required, that.required)
				&& Objects.equals(additionalProperties, that.additionalProperties) && Objects.equals(defs, that.defs);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, properties, required, additionalProperties, defs);
	}

	@Override
	public String toString() {
		return "JsonSchema[type=" + type + ", properties=" + properties + ", required=" + required
				+ ", additionalProperties=" + additionalProperties + ", defs=" + defs + "]";
	}
}
