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

import org.noear.solon.ai.chat.ChatModel;

/**
 * 子代理配置
 *
 * @author bai
 * @since 3.9.5
 */
public class SubAgentConfig {
    private SubAgentType type;
    private String customCode;
    private String description;
    private ChatModel chatModel;
    private String modelName;  // 模型名称（独立于 chatModel）
    private String workDir;
    private int maxSteps;
    private boolean enabled;

    public SubAgentConfig(SubAgentType type) {
        this.type = type;
        this.description = type.getDescription();
        this.maxSteps = 30;
        this.enabled = true;
    }

    /**
     * 创建自定义代理配置
     *
     * @param customCode 自定义代理代码
     * @param description 代理描述
     */
    public SubAgentConfig(String customCode, String description) {
        this.type = SubAgentType.CUSTOM;
        this.customCode = customCode;
        this.description = description;
        this.maxSteps = 30;
        this.enabled = true;
    }

    public SubAgentType getType() {
        return type;
    }

    public SubAgentConfig setType(SubAgentType type) {
        this.type = type;
        return this;
    }

    /**
     * 获取代理代码
     * 对于自定义代理，返回 customCode；否则返回 type.getCode()
     */
    public String getCode() {
        if (type == SubAgentType.CUSTOM && customCode != null) {
            return customCode;
        }
        return type.getCode();
    }

    /**
     * 获取自定义代码（仅对自定义代理有效）
     */
    public String getCustomCode() {
        return customCode;
    }

    public String getDescription() {
        return description;
    }

    public SubAgentConfig setDescription(String description) {
        this.description = description;
        return this;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public SubAgentConfig setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
        return this;
    }

    public String getWorkDir() {
        return workDir;
    }

    public SubAgentConfig setWorkDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public SubAgentConfig setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public SubAgentConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public String getModelName() {
        return modelName;
    }

    public SubAgentConfig setModelName(String modelName) {
        this.modelName = modelName;
        return this;
    }
}
