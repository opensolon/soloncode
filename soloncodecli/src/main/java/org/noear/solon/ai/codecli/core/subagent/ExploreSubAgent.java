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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.ai.codecli.core.CliSkillProvider;
import org.noear.solon.ai.codecli.core.PoolManager;
import org.noear.solon.ai.codecli.core.TerminalSkill;

/**
 * 探索子代理 - 快速探索代码库
 *
 * @author bai
 * @since 3.9.5
 */
public class ExploreSubAgent extends AbstractSubAgent {

    private final String workDir;
    private final PoolManager poolManager;

    public ExploreSubAgent(SubAgentConfig config, AgentSessionProvider sessionProvider,
                           String workDir, PoolManager poolManager) {
        super(config, sessionProvider);
        this.workDir = workDir;
        this.poolManager = poolManager;
    }

    /**
     * 初始化探索代理
     */
    public void initialize(ChatModel chatModel) {
        initAgent(chatModel, builder -> {
            // 添加探索专用工具
            CliSkillProvider skillProvider = new CliSkillProvider(workDir);

            // 设置技能池
            if (poolManager != null) {
                poolManager.getPoolMap().forEach((alias, path) -> {
                    skillProvider.skillPool(alias, path);
                });
            }

            // 添加技能（仅终端和专家技能，不添加代码搜索）
            builder.defaultSkillAdd(skillProvider.getTerminalSkill());
            builder.defaultSkillAdd(skillProvider.getExpertSkill());

            // 设置最大步数（探索任务通常需要较少步数）
            builder.maxSteps(15);

            // 设置会话窗口大小
            builder.sessionWindowSize(5);
        });
    }

    @Override
    protected String buildSystemPrompt() {
        return "## 探索代理\n\n" +
                "你是一个快速的代码库探索专家。你的任务是：\n" +
                "\n" +
                "### 核心能力\n" +
                "- 使用 Glob 工具按模式查找文件（最高效）\n" +
                "- 使用 Grep 工具搜索代码内容\n" +
                "- 使用 Read 工具读取文件内容\n" +
                "- 分析代码结构和架构\n" +
                "\n" +
                "### 探索策略\n" +
                "1. **快速定位**：优先使用 Glob 查找目标文件\n" +
                "2. **精准搜索**：使用 Grep 搜索关键字或函数名\n" +
                "3. **理解上下文**：读取关键文件理解实现细节\n" +
                "4. **结构分析**：总结代码架构和模块关系\n" +
                "\n" +
                "### 输出要求\n" +
                "- 提供清晰简洁的探索结果\n" +
                "- 包含文件路径和行号引用\n" +
                "- 总结关键发现和架构洞察\n" +
                "- 不要执行修改操作（只读探索）\n";
    }
}
