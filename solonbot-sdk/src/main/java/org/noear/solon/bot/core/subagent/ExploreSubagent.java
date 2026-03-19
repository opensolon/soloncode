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
import org.noear.solon.ai.skills.lucene.LuceneSkill;
import org.noear.solon.ai.skills.web.CodeSearchTool;
import org.noear.solon.ai.skills.web.WebfetchTool;
import org.noear.solon.ai.skills.web.WebsearchTool;
import org.noear.solon.bot.core.AgentKernel;

/**
 * 探索子代理 - 快速探索代码库
 *
 * @author bai
 * @since 3.9.5
 */
public class ExploreSubagent extends AbsSubagent {

    public ExploreSubagent(AgentKernel rootAgent) {
        super(rootAgent);
    }


    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 添加技能（仅终端和专家技能，不添加代码搜索）
        builder.defaultToolAdd(rootAgent.getCliSkills().getTerminalSkill()
                .getToolAry("ls", "read", "grep", "glob"));

        builder.defaultSkillAdd(rootAgent.getCliSkills().getExpertSkill());

        builder.defaultSkillAdd(LuceneSkill.getInstance());
        // 添加网络工具
        builder.defaultToolAdd(WebfetchTool.getInstance());
        builder.defaultToolAdd(WebsearchTool.getInstance());
        builder.defaultToolAdd(CodeSearchTool.getInstance());

    }


    @Override
    protected String getDefaultDescription() {
        return "快速探索子代理，专门用于 **本地项目** 查找文件、分析和理解代码结构和回答代码库问题";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## 探索子代理 (只读侦察兵)\n\n" +
                "你是一个本地代码库调研专家。**你的终极目标是深度理解代码，严禁任何形式的修改。**\n\n" +

                "### 核心原则\n" +
                "- **禁止修改**：你没有修改文件的权限，严禁尝试写操作。\n" +
                "- **引用证据**：所有的结论必须包含文件路径及行号引用。\n" +
                "- **结构化输出**：回答应包含代码架构分析、模块关系总结及关键发现。\n\n" +

                "请保持客观和严谨。你的输出将作为其他代理（如开发代理）行动的重要依据。";
    }
}