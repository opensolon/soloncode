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

import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.codecli.core.CliSkillProvider;
import org.noear.solon.ai.codecli.core.AgentKernel;
import org.noear.solon.ai.codecli.core.PoolManager;
import org.noear.solon.ai.codecli.core.memory.SharedMemoryManager;
import org.noear.solon.ai.codecli.core.event.EventBus;
import org.noear.solon.ai.codecli.core.message.MessageChannel;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;
import org.noear.solon.ai.codecli.core.teams.SharedTaskList;

/**
 * 通用子代理 - 处理各种复杂任务
 *
 * @author bai
 * @since 3.9.5
 */
public class GeneralPurposeSubAgent extends AbstractSubAgent {

    private final String workDir;
    private final PoolManager poolManager;

    public GeneralPurposeSubAgent(SubAgentConfig config, AgentSessionProvider sessionProvider,
                                   String workDir, PoolManager poolManager,
                                   SharedMemoryManager sharedMemoryManager,
                                   EventBus eventBus,
                                   MessageChannel messageChannel,
                                   SharedTaskList sharedTaskList) {
        super(config, sessionProvider, sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
        this.workDir = workDir;
        this.poolManager = poolManager;
    }

    /**
     * 初始化通用代理
     */
    public void initialize(ChatModel chatModel) {
        initAgent(chatModel, builder -> {
            // 添加完整技能集
            CliSkillProvider skillProvider = new CliSkillProvider(workDir);

            if (poolManager != null) {
                poolManager.getPoolMap().forEach((alias, path) -> {
                    skillProvider.skillPool(alias, path);
                });
            }

            // 添加所有核心技能
            builder.defaultSkillAdd(skillProvider.getTerminalSkill());
            builder.defaultSkillAdd(skillProvider.getExpertSkill());

            // 添加网络工具
            builder.defaultToolAdd(WebfetchTool.getInstance());
            builder.defaultToolAdd(WebsearchTool.getInstance());

            // 如果主 CodeAgent 有代码搜索能力，也可以添加
            // 这里可以根据需要动态添加工具

            // 设置最大步数（通用任务可能需要更多步数）
            builder.maxSteps(25);

            // 设置会话窗口大小
            builder.sessionWindowSize(10);
        });
    }

    @Override
    public String name() {
        return "general";
    }

    @Override
    public String role() {
        return "通用任务专家，能够处理各种复杂的多步骤任务，包括代码操作、命令执行、信息检索等";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "---\n" +
                "name: general-purpose\n" +
                "description: General task expert for complex multi-step tasks including code operations and command execution\n" +
                "tools: Bash, Read, Write, Edit, Glob, Grep, Webfetch, Websearch\n" +
                "model: glm-4.7\n" +
                "---\n\n" +
                "## 通用任务代理\n\n" +
                "你是一个功能全面的任务执行专家，能够处理各种复杂的多步骤任务。\n" +
                "\n" +
                String.format( "%s 这个文件夹就是你的家。要像对待家一样对待它。\n", workDir) +
                "### 核心能力\n" +
                "- **代码操作**：读取、编辑、搜索代码\n" +
                "- **命令执行**：运行各种 shell 命令和脚本\n" +
                "- **信息检索**：搜索网络资源和文档\n" +
                "- **问题分析**：理解复杂需求并提供解决方案\n" +
                "- **任务协调**：将大任务分解为小步骤并逐一完成\n" +
                "\n" +
                "### 工作原则\n" +
                "1. **理解优先**：确保充分理解用户需求再行动\n" +
                "2. **系统思考**：考虑任务的全貌和潜在影响\n" +
                "3. **验证结果**：执行后验证结果是否符合预期\n" +
                "4. **错误处理**：遇到错误时分析原因并尝试恢复\n" +
                "5. **清晰沟通**：及时反馈进度和发现的问题\n" +
                "\n" +
                "### 使用场景\n" +
                "- 复杂的代码重构\n" +
                "- 多步骤的功能实现\n" +
                "- 跨模块的任务协调\n" +
                "- 需要网络检索的研究任务\n" +
                "- 涉及多个工具的复合任务\n" +
                "\n" +
                "请充分发挥你的全面能力，高效完成用户交给你的任务。\n";
    }
}
