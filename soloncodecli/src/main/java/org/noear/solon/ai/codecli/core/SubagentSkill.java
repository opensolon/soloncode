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
package org.noear.solon.ai.codecli.core;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.codecli.core.subagent.Subagent;
import org.noear.solon.ai.codecli.core.subagent.SubagentManager;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 子代理工具
 *
 * 将子代理能力暴露为可调用的工具
 *
 * @author bai
 * @since 3.9.5
 */
public class SubagentSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(SubagentSkill.class);

    private final SubagentManager manager;

    public SubagentSkill(SubagentManager manager) {
        this.manager = manager;
    }

    /**
     * 启动子代理执行任务
     */
    @ToolMapping(
            name = "subagent",
            description = "启动一个专门的子代理来处理复杂任务。支持预定义类型（explore, plan, bash, general-purpose）和自定义代理（从 agentPools 中动态加载）"
    )
    public String subagent(
            @Param(value = "type", description = "子代理类型：explore, plan, bash, general-purpose，或自定义代理名称") String type,
            @Param(value = "prompt", description = "任务描述或提示词") String prompt,
            @Param(value = "description", required = false, description = "简短的任务描述（3-5个词）") String description) {

        try {
            // 使用新的 getAgent(String) 方法，支持预定义和自定义代理
            Subagent agent = manager.getAgent(type);

            LOG.info("启动子代理: {}, 任务: {}", type, description);

            Prompt taskPrompt = Prompt.of(prompt);
            AgentResponse response = agent.execute(taskPrompt);

            String result = response.getContent();
            LOG.info("子代理 {} 执行完成", type);

            return String.format("[子代理: %s]\n%s", type, result);

        } catch (Throwable e) {
            LOG.error("子代理执行失败: type={}, error={}", type, e.getMessage(), e);
            return "子代理执行失败: " + e.getMessage();
        }
    }

    /**
     * 获取可用的子代理列表
     */
    @ToolMapping(
            name = "subagent_list",
            description = "列出所有可用的子代理及其描述"
    )
    public String list() {
        StringBuilder sb = new StringBuilder("可用的子代理：\n\n");

        // 用于记录已列出的代理，避免重复
        Set<String> listedAgents = new HashSet<>();

        // 1. 列出所有预定义的 SubagentType
        sb.append("【预定义子代理】\n");
        for (Subagent subagent : manager.getAgents()) {
            sb.append(String.format("- **%s**: %s\n",
                    subagent.getName(),
                    subagent.getDescription()));
            listedAgents.add(subagent.getName());
        }

        // 2. 动态扫描所有 agentPools 中的自定义 agents
        Map<String, String> agentPools = manager.getAgentPools();
        if (!agentPools.isEmpty()) {
            sb.append("\n【自定义子代理】\n");

            // 用于去重的自定义代理
            Map<String, AgentInfo> customAgents = new LinkedHashMap<>();

            // 扫描所有注册的池
            for (Map.Entry<String, String> entry : agentPools.entrySet()) {
                String poolAlias = entry.getKey();
                String poolPath = entry.getValue();

                File poolDir = new File(poolPath);
                if (poolDir.exists() && poolDir.isDirectory()) {
                    File[] mdFiles = poolDir.listFiles((dir, name) -> name.endsWith(".md"));
                    if (mdFiles != null) {
                        for (File mdFile : mdFiles) {
                            String agentName = mdFile.getName();
                            if (agentName.endsWith(".md")) {
                                agentName = agentName.substring(0, agentName.length() - 3);
                            }

                            // 跳过已列出的预定义代理
                            if (listedAgents.contains(agentName)) {
                                continue;
                            }

                            // 读取代理描述
                            String description = extractDescription(mdFile);

                            // 如果同名代理已存在，保留优先级更高的（先扫描的）
                            if (!customAgents.containsKey(agentName)) {
                                customAgents.put(agentName,
                                        new AgentInfo(agentName, description, poolAlias));
                            }
                        }
                    }
                }
            }

            // 输出自定义代理
            if (!customAgents.isEmpty()) {
                for (AgentInfo agentInfo : customAgents.values()) {
                    sb.append(String.format("- **%s** (来自 %s): %s\n",
                            agentInfo.name,
                            agentInfo.source,
                            agentInfo.description));
                }
            } else {
                sb.append("(暂无自定义子代理)\n");
            }
        }

        sb.append("\n提示：可以通过 .soloncode/agents/ 目录添加自定义子代理\n");

        return sb.toString();
    }

    /**
     * 代理信息
     */
    private static class AgentInfo {
        final String name;
        final String description;
        final String source;

        AgentInfo(String name, String description, String source) {
            this.name = name;
            this.description = description;
            this.source = source;
        }
    }

    /**
     * 从 MD 文件提取描述
     * 优先读取第一行作为描述，否则使用默认描述
     */
    private String extractDescription(File mdFile) {
        try {
            if (mdFile.exists() && mdFile.isFile()) {
                List<String> lines = Files.readAllLines(Paths.get(mdFile.getAbsolutePath()), StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    String firstLine = lines.get(0).trim();
                    // 跳过 Markdown 标题标记 (# )
                    if (firstLine.startsWith("#")) {
                        firstLine = firstLine.substring(1).trim();
                    }
                    // 限制描述长度
                    if (firstLine.length() > 50) {
                        firstLine = firstLine.substring(0, 47) + "...";
                    }
                    return firstLine;
                }
            }
        } catch (Throwable e) {
            LOG.debug("读取代理描述失败: {}, error={}", mdFile.getName(), e.getMessage());
        }
        return "自定义代理";
    }
}
