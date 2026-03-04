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
package org.noear.solon.ai.codecli.core.tool;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.subagent.SubAgent;
import org.noear.solon.ai.codecli.core.subagent.SubAgentManager;
import org.noear.solon.ai.codecli.core.subagent.SubAgentType;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子代理工具
 *
 * 将子代理能力暴露为可调用的工具
 *
 * @author bai
 * @since 3.9.5
 */
public class SubAgentTool {
    private static final Logger LOG = LoggerFactory.getLogger(SubAgentTool.class);

    private final SubAgentManager manager;

    public SubAgentTool(SubAgentManager manager) {
        this.manager = manager;
    }

    /**
     * 启动子代理执行任务
     */
    @ToolMapping(
            name = "subagent",
            description = "启动一个专门的子代理来处理复杂任务。不同类型的代理擅长不同的任务：explore(快速探索代码库), plan(设计实现计划), bash(执行命令), general-purpose(通用任务处理)"
    )
    public String subagent(
            @Param(value = "type", description = "子代理类型：explore, plan, bash, general-purpose") String type,
            @Param(value = "prompt", description = "任务描述或提示词") String prompt,
            @Param(value = "description", required = false, description = "简短的任务描述（3-5个词）") String description) {

        try {
            SubAgentType agentType = SubAgentType.fromCode(type);
            SubAgent agent = manager.getAgent(agentType);

            LOG.info("启动子代理: {}, 任务: {}", agentType.getCode(), description);

            Prompt taskPrompt = Prompt.of(prompt);
            AgentResponse response = agent.execute(taskPrompt);

            String result = response.getContent();
            LOG.info("子代理 {} 执行完成", agentType.getCode());

            return String.format("[子代理: %s]\n%s", agentType.getCode(), result);

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

        for (SubAgentType type : SubAgentType.values()) {
            sb.append(String.format("- **%s** (%s): %s\n",
                    type.getCode(),
                    type.name(),
                    type.getDescription()));
        }

        return sb.toString();
    }
}
