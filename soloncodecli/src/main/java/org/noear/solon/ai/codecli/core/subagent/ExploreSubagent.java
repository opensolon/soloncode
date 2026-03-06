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

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.codecli.core.AgentKernel;

/**
 * 探索子代理 - 快速探索代码库
 *
 * @author bai
 * @since 3.9.5
 */
public class ExploreSubagent extends AbstractSubagent {

    public ExploreSubagent(AgentKernel mainAgent) {
        super(mainAgent);
    }

    @Override
    protected void initialize(ReActAgent.Builder builder) {
        // 添加技能（仅终端和专家技能，不添加代码搜索）
        builder.defaultSkillAdd(mainAgent.getCliSkills());

        // 设置最大步数（探索任务通常需要较少步数）
        builder.maxSteps(15);

        // 设置会话窗口大小
        builder.sessionWindowSize(5);
    }

    @Override
    public String getName() {
        return "explore";
    }

    @Override
    public String getDescription() {
        return "快速探索子代理，专门用于查找文件、理解代码结构和回答代码库问题";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## 探索子代理\n\n" +
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
