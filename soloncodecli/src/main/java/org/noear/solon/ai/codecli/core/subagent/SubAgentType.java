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
package org.noear.solon.ai.codecli.core.subagent;

/**
 * 子代理类型枚举
 *
 * @author bai
 * @since 3.9.5
 */
public enum SubAgentType {
    /**
     * 通用代理 - 用于复杂问题研究、代码搜索、多步骤任务
     */
    GENERAL_PURPOSE("general-purpose", "通用代理，擅长研究复杂问题、搜索代码和执行多步骤任务"),

    /**
     * 探索代理 - 快速探索代码库结构和文件
     */
    EXPLORE("explore", "快速探索代理，专门用于查找文件、理解代码结构和回答代码库问题"),

    /**
     * 计划代理 - 软件架构师，设计实现计划
     */
    PLAN("plan", "计划代理，软件架构师，用于设计实现策略和步骤计划"),

    /**
     * 命令代理 - bash 命令执行专家
     */
    BASH("bash", "命令代理，专门执行 git 操作、命令行任务和终端操作"),

    /**
     * 状态栏配置代理 - 配置状态栏设置
     */
    STATUSLINE_SETUP("statusline-setup", "状态栏配置代理，用于配置用户的状态栏设置"),

    /**
     * solon Code 指南代理 - 回答 solon Code 相关问题
     */
    SOLON_CODE_GUIDE("solon-code-guide", "Solon Code 指南代理，专门回答关于 Solon Code、Solon Agent SDK 和 Solon API 的问题");

    private final String code;
    private final String description;

    SubAgentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据代码获取类型
     */
    public static SubAgentType fromCode(String code) {
        for (SubAgentType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return GENERAL_PURPOSE;
    }
}
