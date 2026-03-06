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
import org.noear.solon.ai.codecli.core.PoolManager;
import org.noear.solon.ai.codecli.core.memory.SharedMemoryManager;
import org.noear.solon.ai.codecli.core.event.EventBus;
import org.noear.solon.ai.codecli.core.message.MessageChannel;
import org.noear.solon.ai.codecli.core.tool.ReadSolonDocTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.teams.SharedTaskList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solon Code 指南代理 - 回答 Solon Code、Solon Agent SDK 和 Solon API 相关问题
 *
 * @author bai
 * @since 3.9.5
 */
public class SolonCodeGuideSubAgent extends AbstractSubAgent {
    private static final Logger LOG = LoggerFactory.getLogger(SolonCodeGuideSubAgent.class);

    private final String workDir;
    private final PoolManager poolManager;

    public SolonCodeGuideSubAgent(SubAgentConfig config, AgentSessionProvider sessionProvider,
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
     * 初始化 Solon Code 指南代理
     */
    public void initialize(ChatModel chatModel) {
        initAgent(chatModel, builder -> {
            // 添加基础技能集
            CliSkillProvider skillProvider = new CliSkillProvider(workDir);

            if (poolManager != null) {
                poolManager.getPoolMap().forEach((alias, path) -> {
                    skillProvider.skillPool(alias, path);
                });
            }

            // 添加专家技能（用于技能搜索和读取）
            builder.defaultSkillAdd(skillProvider.getExpertSkill());

            // 添加网络获取工具（用于读取在线文档）
            builder.defaultToolAdd(WebfetchTool.getInstance());

            // 添加自定义工具：读取 Solon 文档（传递 workDir）
            builder.defaultToolAdd(new ReadSolonDocTool(workDir));

            // 设置较小的步数限制（主要是查询和回答）
            builder.maxSteps(15);

            // 设置会话窗口大小
            builder.sessionWindowSize(5);
        });
    }

    @Override
    public String name() {
        return "solon-code-guide";
    }

    @Override
    public String role() {
        return "Solon Code 技术专家，负责回答 Solon Code、Solon Agent SDK 和 Solon API 相关问题";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "---\n" +
                "name: solon-code-guide\n" +
                "description: Solon Code technical expert for answering Solon Code, Solon Agent SDK, and Solon API questions\n" +
                "tools: Read, Webfetch, skillsearch, skillread\n" +
                "model: glm-4.7\n" +
                "---\n\n" +
                "## Solon Code 指南代理\n\n" +
                "你是 Solon Code、Solon Agent SDK 和 Solon API 的专家指南，专门回答相关问题。\n" +
                "\n" +
                String.format( "%s 这个文件夹就是你的家。要像对待家一样对待它。\n", workDir) +
                "### 核心知识\n" +
                "- **Solon Code**：基于 Java 的 AI 编程助手，兼容 Claude Code Agent Skills 规范\n" +
                "- **Solon Agent SDK**：用于构建 AI Agent 的开发框架\n" +
                "- **Solon API**：Solon 框架的应用程序接口\n" +
                "- **ReAct 模式**：推理-行动循环，用于 Agent 决策\n" +
                "\n" +
                "### 可用工具\n" +
                "- `read_solon_doc`: 读取 Solon 官网文档（支持缓存）\n" +
                "- `webfetch`: 获取网页内容\n" +
                "- `skillsearch`: 搜索技能文件\n" +
                "- `skillread`: 读取技能内容\n" +
                "\n" +
                "### 工作流程\n" +
                "1. 理解用户问题，识别涉及的主题（Solon Code / Agent SDK / API）\n" +
                "2. 使用 `read_solon_doc` 工具读取相关文档\n" +
                "3. 如果需要更多信息，使用 `webfetch` 获取在线资源\n" +
                "4. 结合文档内容给出准确、详细的回答\n" +
                "5. 如果文档中没有相关内容，说明情况并提供通用建议\n" +
                "\n" +
                "### 回答原则\n" +
                "- 优先基于官方文档回答\n" +
                "- 提供代码示例时确保准确性\n" +
                "- 引用文档来源和链接\n" +
                "- 如果不确定，诚实说明\n" +
                "\n" +
                "请充分利用你的专业知识，帮助用户更好地理解和使用 Solon 相关技术。\n";
    }
}
