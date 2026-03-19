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
package org.noear.solon.bot.core.message;

/**
 * 预定义消息类型枚举
 *
 * @author bai
 * @since 3.9.5
 */
public enum AgentMessageType {
    // ========== 请求-响应类型 ==========
    /** 请求消息 */
    REQUEST("request"),
    /** 响应消息 */
    RESPONSE("response"),

    // ========== 通知类型 ==========
    /** 通知消息（无需响应） */
    NOTIFICATION("notification"),

    // ========== 查询类型 ==========
    /** 查询消息 */
    QUERY("query"),
    /** 查询结果消息 */
    QUERY_RESULT("query.result");

    private final String code;

    AgentMessageType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 根据代码获取枚举
     */
    public static AgentMessageType fromCode(String code) {
        for (AgentMessageType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的消息类型: " + code);
    }
}
