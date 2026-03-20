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
package org.noear.solon.bot.core.subagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.solon.bot.core.util.Markdown;
import org.noear.solon.bot.core.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SubAgent 元数据
 *
 * 从系统提示词头部的 YAML 配置中解析出来的元数据
 * 兼容 Claude Code Agent Skills 规范
 *
 * 增强功能：
 * - 元数据验证
 * - 元数据继承和合并
 * - Builder 模式支持（使用 Lombok）
 *
 * @author bai
 * @since 3.9.5
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
@Getter
public class AgentMetadata {
    private boolean enabled = true;

    // 必需字段
    private String name;
    private String description;

    // 模型配置
    private String model;

    // 最大步数（新增）
    private Integer maxSteps;

    // 最大步数自动扩展（新增）
    private Boolean maxStepsAutoExtensible;


    // 工具配置
    private List<String> tools = new ArrayList<>();

    private List<String> disallowedTools = new ArrayList<>();


    // 权限配置
    private String permissionMode;


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


    // 团队配置
    private String teamName;  // 所属团队名称（用于团队成员）

    /**
     * 将元数据转换为 YAML frontmatter 格式
     *
     * 格式：
     * ---
     * name: xxx
     * description: xxx
     * tools: xxx
     * ...
     * ---
     *
     * @return YAML frontmatter 字符串
     */
    public void injectYamlFrontmatter(StringBuilder buf) {
        String yaml = new Yaml().dump(this);
        if(Assert.isNotEmpty(yaml)){
            buf.append("---\n");
            buf.append(yaml);
            buf.append("---\n\n");
        }
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

    public boolean hasTeamName() {
        return teamName != null && !teamName.isEmpty();
    }
}
