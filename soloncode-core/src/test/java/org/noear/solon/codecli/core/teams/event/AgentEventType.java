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
package org.noear.solon.codecli.core.teams.event;

import lombok.Getter;

/**
 * 预定义事件类型枚举
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public enum AgentEventType {
    // ========== 主代理任务事件 ==========
    /** 主代理任务开始 */
    MAIN_TASK_STARTED("main.task.started"),
    /** 主代理任务完成 */
    MAIN_TASK_COMPLETED("main.task.completed"),
    /** 主代理任务失败 */
    MAIN_TASK_FAILED("main.task.failed"),

    // ========== 子任务事件 ==========
    /** 任务创建 */
    TASK_CREATED("task.created"),
    /** 任务开始 */
    TASK_STARTED("task.started"),
    /** 任务完成 */
    TASK_COMPLETED("task.completed"),
    /** 任务失败 */
    TASK_FAILED("task.failed"),
    /** 任务进度更新 */
    TASK_PROGRESS("task.progress"),
    /** 任务认领 */
    TASK_CLAIMED("task.claimed"),
    /** 任务释放 */
    TASK_RELEASED("task.released"),

    // ========== 记忆事件 ==========
    /** 记忆已存储 */
    MEMORY_STORED("memory.stored"),
    /** 记忆已检索 */
    MEMORY_RETRIEVED("memory.retrieved"),

    // ========== 代理事件 ==========
    /** 代理已初始化 */
    AGENT_INITIALIZED("agent.initialized"),
    /** 代理错误 */
    AGENT_ERROR("agent.error"),

    // ========== 自定义事件 ==========
    /** 自定义事件类型（用于未预定义的事件） */
    CUSTOM("");

    private final String code;

    AgentEventType(String code) {
        this.code = code;
    }

    /**
     * 根据代码获取枚举
     */
    public static AgentEventType fromCode(String code) {
        for (AgentEventType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的事件类型: " + code);
    }

    /**
     * 从代码获取枚举，如果未找到则返回 null
     * 对于自定义事件类型，请直接使用 String 订阅/发布
     */
    public static AgentEventType fromCodeOrNull(String code) {
        for (AgentEventType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null; // 未找到，表示这是一个自定义事件类型
    }
}
