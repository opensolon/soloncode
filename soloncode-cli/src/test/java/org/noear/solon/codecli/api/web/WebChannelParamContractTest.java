/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.api.web;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Param;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebChannel} 的 {@code @Param} 契约守卫。
 *
 * <p>Solon 的 {@code @Param.required()} 自 3.2 起默认为 {@code true}。一旦把「前端可能不传」
 * 的可选参数写成裸的 {@code @Param("x") Boolean x}，请求就会以
 * HTTP 400 {@code Missing required parameter 'x'} 失败。</p>
 *
 * <p>真实事故：飞书扫码状态轮询每次都不带 {@code force}，而该参数被写成必填，于是每 2 秒
 * 一次 400；前端 {@code $.get(...).done()} 永不触发，表现为「扫码后 soloncode 侧状态毫无变化」，
 * 且异常被静默吞掉、极难定位。此测试把这类回归钉死在编译期契约上。</p>
 */
public class WebChannelParamContractTest {

    /** 装箱类型不可能「必填」：缺省时它就是 null，声明 required 只会把正常请求打成 400。 */
    @Test
    public void boxedParamsMustNotBeRequired() {
        for (Method method : WebChannel.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                Param param = parameter.getAnnotation(Param.class);
                if (param == null) {
                    continue;
                }
                if (!isBoxed(parameter.getType())) {
                    continue;
                }
                assertFalse(param.required(),
                        method.getName() + " 的可选参数 " + paramName(param)
                                + " 必须声明 required = false（Solon 默认 true，会导致缺省时 400）");
            }
        }
    }

    /** 防止上面的通用规则因参数被删空转而形同虚设。 */
    @Test
    public void forceFlagsStillExistAndAreOptional() {
        int found = 0;
        for (Method method : WebChannel.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                Param param = parameter.getAnnotation(Param.class);
                if (param != null && "force".equals(paramName(param))) {
                    found++;
                    assertFalse(param.required(), method.getName() + " 的 force 必须是可选参数");
                }
            }
        }
        assertTrue(found >= 3, "应至少存在 3 个 force 可选参数（飞书绑定/飞书扫码轮询/钉钉绑定），实际 " + found);
    }

    private static boolean isBoxed(Class<?> type) {
        return type == Boolean.class || type == Integer.class
                || type == Long.class || type == Double.class || type == Float.class;
    }

    private static String paramName(Param param) {
        return param.name().isEmpty() ? param.value() : param.name();
    }
}
