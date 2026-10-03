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
package org.noear.solon.codecli.api.desktop.controller;

/**
 * Desktop 会话 ID 白名单校验。
 *
 * <p>原 WsController 的私有方法，随领域拆分抽出：Session / Goal 等多个桌面
 * Controller 都需要按同一套规则拒绝非法 ID（防路径穿越）。桌面端持久化会话
 * 使用正整数主键（IndexedDB 侧生成），与 Web 侧前缀规则不同。</p>
 */
public final class DesktopSessionIds {

    private DesktopSessionIds() {
    }

    /**
     * 校验桌面会话 ID 格式（白名单方式，防止路径遍历攻击）。
     *
     * @param sessionId 会话 ID
     * @return true 表示合法（非空且为 1~18 位纯数字）
     */
    public static boolean isValid(String value) {
        return value != null && value.matches("[0-9]{1,18}");
    }
}
