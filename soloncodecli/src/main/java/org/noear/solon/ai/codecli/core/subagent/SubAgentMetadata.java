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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SubAgent 元数据
 *
 * 从系统提示词头部的 YAML 配置中解析出来的元数据
 * 兼容 Claude Code Agent Skills 规范
 *
 * @author bai
 * @since 3.9.5
 */
public class SubAgentMetadata {
    // 必需字段
    private String name;
    private String description;

    // 工具配置
    private List<String> tools = new ArrayList<>();
    private List<String> disallowedTools = new ArrayList<>();

    // 模型配置
    private String model;

    // 权限配置
    private String permissionMode;  // default, acceptEdits, dontAsk, bypassPermissions, plan

    // 执行限制
    private Integer maxTurns;

    // Skills 配置
    private List<String> skills = new ArrayList<>();

    // MCP Servers 配置
    private List<String> mcpServers = new ArrayList<>();

    // Hooks 配置（暂不解析，保留字段）
    private Object hooks;

    // 记忆配置
    private String memory;  // user, project, local

    // 后台任务
    private Boolean background;

    // 隔离配置
    private String isolation;  // worktree

    /**
     * 从系统提示词中解析元数据
     *
     * @param systemPrompt 系统提示词
     * @return 解析出的元数据对象
     */
    public static SubAgentMetadata fromPrompt(String systemPrompt) {
        SubAgentMetadata metadata = new SubAgentMetadata();

        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return metadata;
        }

        // 查找 YAML 配置块
        // 格式：---\nname: xxx\ndescription: xxx\n...\n---
        int startIndex = systemPrompt.indexOf("---");
        if (startIndex == -1) {
            return metadata; // 没有找到 YAML 配置
        }

        int endIndex = systemPrompt.indexOf("---", startIndex + 3);
        if (endIndex == -1) {
            return metadata; // 没有找到结束标记
        }

        // 提取 YAML 配置块
        String yamlBlock = systemPrompt.substring(startIndex + 3, endIndex).trim();

        // 解析每一行
        for (String line : yamlBlock.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // 跳过注释
            if (line.startsWith("#")) {
                continue;
            }

            // 解析 key: value
            if (line.contains(":")) {
                int colonIndex = line.indexOf(":");
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                switch (key) {
                    case "name":
                        metadata.name = value;
                        break;
                    case "description":
                        metadata.description = value;
                        break;
                    case "tools":
                        // 工具列表，逗号分隔
                        metadata.tools = Arrays.asList(value.split(",\\s*"));
                        break;
                    case "disallowedTools":
                        // 禁用工具列表，逗号分隔
                        metadata.disallowedTools = Arrays.asList(value.split(",\\s*"));
                        break;
                    case "model":
                        metadata.model = value;
                        break;
                    case "permissionMode":
                        metadata.permissionMode = value;
                        break;
                    case "maxTurns":
                        try {
                            metadata.maxTurns = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            // 忽略无效值
                        }
                        break;
                    case "skills":
                        // Skills 列表，逗号分隔
                        metadata.skills = Arrays.asList(value.split(",\\s*"));
                        break;
                    case "mcpServers":
                        // MCP Servers 列表，逗号分隔
                        metadata.mcpServers = Arrays.asList(value.split(",\\s*"));
                        break;
                    case "memory":
                        metadata.memory = value;
                        break;
                    case "background":
                        metadata.background = Boolean.parseBoolean(value);
                        break;
                    case "isolation":
                        metadata.isolation = value;
                        break;
                    // hooks 字段暂不解析（需要复杂的对象解析）
                }
            }
        }

        return metadata;
    }

    /**
     * 从系统提示词中解析元数据并移除 YAML 配置块
     *
     * @param systemPrompt 系统提示词
     * @return 包含元数据和清理后提示词的对象
     */
    public static PromptWithMetadata parseAndClean(String systemPrompt) {
        SubAgentMetadata metadata = fromPrompt(systemPrompt);
        String cleanedPrompt = removeYamlBlock(systemPrompt);
        return new PromptWithMetadata(metadata, cleanedPrompt);
    }

    /**
     * 移除提示词头部的 YAML 配置块
     *
     * @param systemPrompt 系统提示词
     * @return 清理后的提示词
     */
    private static String removeYamlBlock(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return systemPrompt;
        }

        int startIndex = systemPrompt.indexOf("---");
        if (startIndex == -1) {
            return systemPrompt; // 没有找到 YAML 配置
        }

        int endIndex = systemPrompt.indexOf("---", startIndex + 3);
        if (endIndex == -1) {
            return systemPrompt; // 没有找到结束标记
        }

        // 移除 YAML 配置块，保留后面的内容
        return systemPrompt.substring(endIndex + 3).trim();
    }

    /**
     * 提示词和元数据的组合
     */
    public static class PromptWithMetadata {
        private final SubAgentMetadata metadata;
        private final String prompt;

        public PromptWithMetadata(SubAgentMetadata metadata, String prompt) {
            this.metadata = metadata;
            this.prompt = prompt;
        }

        public SubAgentMetadata getMetadata() {
            return metadata;
        }

        public String getPrompt() {
            return prompt;
        }
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getTools() {
        return tools;
    }

    public List<String> getDisallowedTools() {
        return disallowedTools;
    }

    public String getModel() {
        return model;
    }

    public boolean hasModel() {
        return model != null && !model.isEmpty();
    }

    public boolean hasPermissionMode() {
        return permissionMode != null && !permissionMode.isEmpty();
    }

    public boolean hasMaxTurns() {
        return maxTurns != null && maxTurns > 0;
    }

    public boolean hasSkills() {
        return skills != null && !skills.isEmpty();
    }

    public boolean hasMcpServers() {
        return mcpServers != null && !mcpServers.isEmpty();
    }

    public boolean hasDisallowedTools() {
        return disallowedTools != null && !disallowedTools.isEmpty();
    }

    public boolean hasMemory() {
        return memory != null && !memory.isEmpty();
    }

    public boolean isBackground() {
        return background != null && background;
    }

    public boolean hasIsolation() {
        return isolation != null && !isolation.isEmpty();
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public List<String> getSkills() {
        return skills;
    }

    public List<String> getMcpServers() {
        return mcpServers;
    }

    public Object getHooks() {
        return hooks;
    }

    public String getMemory() {
        return memory;
    }

    public Boolean getBackground() {
        return background;
    }

    public String getIsolation() {
        return isolation;
    }
}
