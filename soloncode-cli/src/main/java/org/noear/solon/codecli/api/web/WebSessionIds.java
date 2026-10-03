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
 * Web 会话 ID 白名单校验。
 *
 * <p>原 WebController 的私有静态方法，随领域拆分抽出：Session / Chat / Queue /
 * Loop / Todo 等多个 Controller 都需要按同一套规则拒绝非法 ID（防路径穿越）。
 * 收敛为公开工具后也便于单元测试直接覆盖（原 {@code WebControllerSessionIdTest}
 * 通过反射访问私有方法，现改为直接调用）。</p>
 */
public final class WebSessionIds {

    private WebSessionIds() {
    }

    /**
     * 校验 web 会话 ID 格式（白名单方式，防止路径遍历攻击）。
     *
     * <p>Web 会话以 web 开头；桌面端的持久化会话使用正整数主键；
     * 自动任务专用会话以 auto 开头（UUID 十六进制后缀）。
     * 三类均使用严格白名单，保证后续 resolve 后不会出现路径穿越。</p>
     *
     * @param sessionId 会话 ID
     * @return true 表示合法
     */
    public static boolean isValid(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        return sessionId.matches("^web(-[a-zA-Z0-9._-]+)?$")
                || sessionId.matches("^[1-9][0-9]{0,18}$")
                || sessionId.matches("^auto-[a-f0-9]{16,64}$");
    }
}
