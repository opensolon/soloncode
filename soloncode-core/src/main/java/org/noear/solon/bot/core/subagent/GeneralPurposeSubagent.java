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
import org.noear.solon.bot.core.AgentRuntime;

/**
 * 通用子代理 - 处理各种复杂任务
 *
 * @author bai
 * @since 3.9.5
 */
public class GeneralPurposeSubagent extends AbsSubagent {

    String instruction;

    public GeneralPurposeSubagent(AgentRuntime rootAgent) {
        super(rootAgent);
    }

    public GeneralPurposeSubagent(AgentRuntime rootAgent, SubAgentMetadata metadata) {
        super(rootAgent, metadata);
    }

    public GeneralPurposeSubagent(AgentRuntime rootAgent, SubAgentMetadata metadata, String instruction) {
        super(rootAgent, metadata);
        this.instruction = instruction;
    }

    @Override
    protected void applyMetadataToBuilder(ReActAgent.Builder builder, SubAgentMetadata metadata) {
        super.applyMetadataToBuilder(builder, metadata);
        builder.instruction(this.instruction);
    }


    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 添加所有核心技能
        builder.defaultSkillAdd(rootAgent.getCliSkills());

        builder.defaultSkillAdd(LuceneSkill.getInstance());

        // 添加网络工具
        builder.defaultToolAdd(WebfetchTool.getInstance());
        builder.defaultToolAdd(WebsearchTool.getInstance());
        builder.defaultToolAdd(CodeSearchTool.getInstance());

    }


    @Override
    protected String getDefaultDescription() {
        return "通用子代理，擅长研究复杂问题、执行多步骤任务";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## 通用任务代理\n\n" +
                "你是一个全能型执行专家，负责处理复杂、多步骤且需要综合能力的开发任务。\n\n" +

                "### 工具使用策略\n" +
                "1. **本地搜索 (内部)**：定位项目内代码、符号或文件时，优先使用 Lucene 或内置的 grep/glob。\n" +
                "2. **全网调研 (外部)**：遇到新技术、查阅第三方 SDK 文档或寻找业界最佳实践时，使用 CodeSearch 或 WebSearch。\n" +
                "3. **闭环执行**：你拥有写权限，可以根据需要 read 内容后直接进行编写或修改，并运行测试验证。\n\n" +

                "### 核心能力\n" +
                "- **端到端开发**：从分析需求到编写代码，再到执行 shell 命令验证，实现完整闭环。\n" +
                "- **跨域检索**：无缝切换本地代码库调研与互联网信息检索。\n" +
                "- **复杂逻辑拆解**：能将模糊的大任务拆解为清晰的原子步骤并逐一执行。\n\n" +

                "### 工作原则\n" +
                "1. **理解优先**：动笔修改前，必须确保通过读取文件充分理解了现有逻辑。\n" +
                "2. **分步验证**：每完成一个关键步骤，建议运行测试或检查输出，避免错误累积。\n" +
                "3. **系统性思考**：修改代码时需考虑对周边模块的影响，确保系统整体稳定性。\n" +
                "4. **自愈能力**：如果命令执行报错，应分析错误日志并尝试自动修正方案。\n\n" +

                "请灵活运用你的全量工具集，以最高效率解决用户提出的任何复杂问题。";
    }
}