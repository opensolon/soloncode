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

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.LuceneSkill;
import org.noear.solon.bot.core.tool.CodeSearchTool;

/**
 * 探索子代理 - 快速探索代码库
 *
 * @author bai
 * @since 3.9.5
 */
public class ExploreSubagent extends AbsSubagent {

    public ExploreSubagent(AgentKernel mainAgent) {
        super(mainAgent);
    }

    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 添加技能（仅终端和专家技能，不添加代码搜索）
        builder.defaultToolAdd(mainAgent.getCliSkills().getTerminalSkill()
                .getToolAry("ls", "read", "grep", "glob"));

        builder.defaultSkillAdd(mainAgent.getCliSkills().getExpertSkill());
        builder.defaultSkillAdd(LuceneSkill.getInstance());
        builder.defaultToolAdd(CodeSearchTool.getInstance());

        // 设置最大步数（探索任务通常需要较少步数）
        builder.maxSteps(15);

        // 设置会话窗口大小
        builder.sessionWindowSize(5);
    }

    @Override
    public String getType() {
        return "explore";
    }

    @Override
    protected String getDefaultDescription() {
        return "快速探索子代理，专门用于查找文件、分析和理解代码结构和回答代码库问题";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## 探索子代理 (只读侦察兵)\n\n" +
                "你是一个代码库调研专家。**你的终极目标是深度理解代码，严禁任何形式的修改。**\n\n" +
                "### 搜索策略指南\n" +
                "1. **符号定位 (Lucene)**：查找类名、方法名或接口定义时，优先使用 Lucene。\n" +
                "2. **模式匹配 (Glob)**：按文件名后缀或路径模式查找文件时使用 Glob。\n" +
                "3. **关键字检索 (Grep)**：在文件内容中搜索特定文本或字符串时使用 Grep。\n" +
                "4. **深度调研 (CodeSearch)**：遇到不熟悉的第三方库 API 或编程模式时，调用此工具获取外部背景。\n" +
                "5. **内容阅读 (Read)**：在得出结论前，必须阅读相关文件的核心代码。\n\n" +

                "### 核心原则\n" +
                "- **禁止修改**：你没有修改文件的权限，严禁尝试写操作。\n" +
                "- **引用证据**：所有的结论必须包含文件路径及行号引用。\n" +
                "- **结构化输出**：回答应包含代码架构分析、模块关系总结及关键发现。\n\n" +

                "请保持客观和严谨。你的输出将作为其他代理（如开发代理）行动的重要依据。";
    }
}