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

import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.annotation.ONodeAttr;
import org.noear.snack4.codec.CodecException;
import org.noear.snack4.codec.ObjectCreator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

/**
 * 供不可变类使用的解码创建器：JSON 里缺字段时，基本类型参数用类型默认值（0 / false）兜底。
 *
 * <p>
 * snack4 4.0.59 的 {@code BeanDecoder#getParam} 对缺失的键统一返回 null，构造器参数是
 * {@code int/long/boolean} 这类基本类型时会直接抛 {@code IllegalArgumentException}；
 * 而迁移前的 Jackson 对缺失的基本类型字段填 0 / false。CLI 完全可能省略 {@code duration_ms}、
 * {@code stop_hook_active} 这类字段，所以这里补齐这段宽容语义。
 * </p>
 *
 * <p>
 * 用法：在不可变类上标注 {@code @ONodeAttr(creator = PrimitiveSafeCreator.class)}。仅适用于
 * 「单个全参构造器、且参数上带 {@code @ONodeAttr(name = ...)}」的类型。
 * </p>
 */
public class PrimitiveSafeCreator implements ObjectCreator<Object> {

	@Override
	public Object create(Options opts, ONode node, Class<?> clazz) {
		if (node == null || !node.isObject()) {
			return null;
		}

		Constructor<?> constructor = pickConstructor(clazz);
		Parameter[] parameters = constructor.getParameters();
		Object[] args = new Object[parameters.length];

		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			ONode field = node.getOrNull(aliasOf(parameter));

			if (field == null || field.isNull()) {
				args[i] = defaultValueOf(parameter.getType());
			}
			else {
				args[i] = field.options(opts).toBean(parameter.getParameterizedType());
			}
		}

		try {
			constructor.setAccessible(true);
			return constructor.newInstance(args);
		}
		catch (ReflectiveOperationException e) {
			throw new CodecException("Failed to create instance: " + clazz.getName(), e);
		}
	}

	/** 取参数数量最多的构造器（不可变类的全参构造器） */
	private static Constructor<?> pickConstructor(Class<?> clazz) {
		Constructor<?>[] all = clazz.getDeclaredConstructors();
		Constructor<?> best = null;
		for (Constructor<?> candidate : all) {
			if (best == null || candidate.getParameterCount() > best.getParameterCount()) {
				best = candidate;
			}
		}
		if (best == null) {
			throw new CodecException("No constructor found: " + clazz.getName());
		}
		return best;
	}

	private static String aliasOf(Parameter parameter) {
		ONodeAttr attr = parameter.getAnnotation(ONodeAttr.class);
		if (attr != null && !attr.name().isEmpty()) {
			return attr.name();
		}
		return parameter.getName();
	}

	private static Object defaultValueOf(Class<?> type) {
		if (!type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == double.class) {
			return 0D;
		}
		if (type == float.class) {
			return 0F;
		}
		if (type == short.class) {
			return (short) 0;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		if (type == char.class) {
			return (char) 0;
		}
		return null;
	}

}
