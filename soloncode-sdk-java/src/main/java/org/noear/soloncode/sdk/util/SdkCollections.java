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

package org.noear.soloncode.sdk.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Java 8 replacements for List.of/Map.of/Set.of/*copyOf factory methods (introduced in JDK 9+).
 * Preserves the same semantics: unordered null elements/keys/values are rejected, and the
 * returned collections are unmodifiable.
 */
public final class SdkCollections {

	private SdkCollections() {
	}

	@SafeVarargs
	public static <T> List<T> list(T... elements) {
		List<T> copy = new ArrayList<>(Arrays.asList(elements));
		for (T e : copy) {
			Objects.requireNonNull(e);
		}
		return Collections.unmodifiableList(copy);
	}

	public static <T> List<T> copyList(List<? extends T> list) {
		List<T> copy = new ArrayList<>();
		for (T e : list) {
			copy.add(Objects.requireNonNull(e));
		}
		return Collections.unmodifiableList(copy);
	}

	@SafeVarargs
	public static <T> Set<T> set(T... elements) {
		Set<T> set = new LinkedHashSet<>(Arrays.asList(elements));
		for (T e : set) {
			Objects.requireNonNull(e);
		}
		return Collections.unmodifiableSet(set);
	}

	public static <K, V> Map<K, V> map(Object... keyValuePairs) {
		if (keyValuePairs.length % 2 != 0) {
			throw new IllegalArgumentException("Expected even number of key/value pairs");
		}
		Map<K, V> m = new LinkedHashMap<>();
		for (int i = 0; i < keyValuePairs.length; i += 2) {
			@SuppressWarnings("unchecked")
			K k = (K) Objects.requireNonNull(keyValuePairs[i]);
			@SuppressWarnings("unchecked")
			V v = (V) Objects.requireNonNull(keyValuePairs[i + 1]);
			m.put(k, v);
		}
		return Collections.unmodifiableMap(m);
	}

	public static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> map) {
		Map<K, V> m = new LinkedHashMap<>();
		for (Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
			m.put(Objects.requireNonNull(e.getKey()), e.getValue());
		}
		return Collections.unmodifiableMap(m);
	}
}
