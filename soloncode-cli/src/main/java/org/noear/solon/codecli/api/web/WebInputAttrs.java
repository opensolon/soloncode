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

/**
 * Web/IM 宿主域的输入属性键（经 {@code HarnessInput#attr} 随积压保存，续发时从 {@code QueueItem#getAttrs} 取回）。
 *
 * <p>键名与旧版队列文件的顶层字段同名：queue 读入旧数据时会把这些顶层字段并入 attrs。</p>
 *
 * @author noear
 * @since 2026.11
 */
public final class WebInputAttrs {
    /** 推理强度 */
    public static final String REASONING_EFFORT = "reasoningEffort";
    /** 思考模式 */
    public static final String THINKING_MODE = "thinkingMode";

    private WebInputAttrs() {
    }
}
