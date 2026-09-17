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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;

import java.lang.reflect.Type;

/**
 * SDK 内部统一的 JSON 门面（snack4）。
 *
 * <p>
 * 存在两套 {@link Options}，用于把 snack4 的默认行为对齐到迁移前 Jackson 的线上语义：
 * </p>
 * <ul>
 * <li>{@link #wireOptions()}：snack4 默认（不写 null）。对应 Jackson 里带
 * {@code @JsonInclude(NON_NULL)} 的类型（ControlResponse / HookOutput / JsonSchema 等）。</li>
 * <li>{@link #withNullsOptions()}：开启 {@link Feature#Write_Nulls}。对应 Jackson 的默认行为——
 * Map/Bean 的 null 值照样出现在 JSON 里。发给 CLI / {@code /web/run} 的请求体走这套，
 * 保证「原来会出现的 null 字段不会凭空消失」。</li>
 * </ul>
 *
 * <p>
 * 未知字段容错：snack4 默认不开 {@code Write_FailOnUnknownProperties}，即遇到未建模字段直接忽略，
 * 等价于 Jackson 的 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 与
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = false}，故此处不需要额外开关。
 * </p>
 */
public final class SdkJson {

	/** 不写 null（对应 @JsonInclude(NON_NULL)） */
	private static final Options WIRE = Options.of().readonly();

	/** 写 null（对应 Jackson 默认：null 字段照样输出） */
	private static final Options WITH_NULLS = Options.of(Feature.Write_Nulls).readonly();

	private SdkJson() {
	}

	public static Options wireOptions() {
		return WIRE;
	}

	public static Options withNullsOptions() {
		return WITH_NULLS;
	}

	/**
	 * 解析 JSON 文本为树节点。
	 * @throws org.noear.snack4.SnackException JSON 非法时抛出（运行时异常）
	 */
	public static ONode parse(String json) {
		return ONode.ofJson(json, WIRE);
	}

	/**
	 * 序列化：null 字段不输出（@JsonInclude(NON_NULL) 语义）。
	 */
	public static String toJson(Object bean) {
		return ONode.ofBean(bean, WIRE).toJson();
	}

	/**
	 * 序列化：null 字段照样输出（Jackson 默认语义）。
	 */
	public static String toJsonWithNulls(Object bean) {
		return ONode.ofBean(bean, WITH_NULLS).toJson();
	}

	/**
	 * 反序列化到指定类型（等价 ObjectMapper.readValue）。
	 */
	public static <T> T toBean(String json, Type type) {
		return parse(json).toBean(type);
	}

	/**
	 * 树 → 指定类型（等价 ObjectMapper.treeToValue / convertValue）。
	 */
	public static <T> T toBean(ONode node, Type type) {
		if (node == null) {
			return null;
		}
		return node.options(WIRE).toBean(type);
	}

	/**
	 * Java 对象 → 指定类型（等价 ObjectMapper.convertValue）。
	 */
	public static <T> T convert(Object source, Type type) {
		if (source == null) {
			return null;
		}
		return ONode.ofBean(source, WIRE).toBean(type);
	}

	/**
	 * 取字符串字段，且要求节点确实是字符串——与 Jackson {@code isTextual()} 判定一致。
	 * <p>
	 * 注意 snack4 的 {@code ONode#getString()} 会把数字/布尔/对象也转成字符串，
	 * 直接使用会放宽原有语义，因此这里显式收紧。
	 * </p>
	 * @return 字段缺失、为 null 或不是字符串时返回 null
	 */
	public static String getStringField(ONode node, String name) {
		ONode field = getField(node, name);
		return (field != null && field.isString()) ? field.getString() : null;
	}

	/**
	 * 取子节点。节点不是对象或字段缺失时返回 null（对齐 Jackson {@code JsonNode#get} 的 null 语义）。
	 * <p>
	 * 不能直接用 {@code ONode#get(String)}：字段缺失时它返回空节点而非 null，
	 * 且在非对象节点上调用会抛 ClassCastException。
	 * </p>
	 */
	public static ONode getField(ONode node, String name) {
		if (node == null || !node.isObject()) {
			return null;
		}
		return node.getOrNull(name);
	}

	/**
	 * 判断字段是否存在（对齐 Jackson {@code JsonNode#has}）。
	 */
	public static boolean hasField(ONode node, String name) {
		return node != null && node.isObject() && node.hasKey(name);
	}

}
