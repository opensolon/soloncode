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

package org.noear.soloncode.sdk.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Configuration for SolonCode Code plugins. Plugins extend SolonCode with custom commands,
 * agents, skills, and hooks.
 *
 * <p>
 * Currently only "local" plugin type is supported, which loads plugins from a local
 * directory path.
 */
public final class PluginConfig {

	private final String type;

	private final Path path;

	/**
	 * Create a new PluginConfig with validation.
	 * @throws IllegalArgumentException if type is not "local"
	 */
	public PluginConfig(String type, Path path) {
		if (!"local".equals(type)) {
			throw new IllegalArgumentException("Only 'local' plugin type is supported, got: " + type);
		}
		if (path == null) {
			throw new IllegalArgumentException("Plugin path cannot be null");
		}
		this.type = type;
		this.path = path;
	}

	public String type() {
		return type;
	}

	public Path path() {
		return path;
	}

	/**
	 * Creates a local plugin configuration from a path.
	 * @param path the directory containing the plugin
	 * @return a new PluginConfig
	 */
	public static PluginConfig local(Path path) {
		return new PluginConfig("local", path);
	}

	/**
	 * Creates a local plugin configuration from a string path.
	 * @param pathString the directory path as a string
	 * @return a new PluginConfig
	 */
	public static PluginConfig local(String pathString) {
		return new PluginConfig("local", Paths.get(pathString));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PluginConfig)) {
			return false;
		}
		PluginConfig that = (PluginConfig) o;
		return Objects.equals(type, that.type) && Objects.equals(path, that.path);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, path);
	}

	@Override
	public String toString() {
		return "PluginConfig[type=" + type + ", path=" + path + "]";
	}

}
