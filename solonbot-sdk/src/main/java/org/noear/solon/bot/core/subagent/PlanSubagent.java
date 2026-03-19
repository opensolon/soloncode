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
 * 计划子代理 - 软件架构师
 *
 * @author bai
 * @since 3.9.5
 */
public class PlanSubagent extends AbsSubagent {

    public PlanSubagent(AgentRuntime mainAgent) {
        super(mainAgent);
    }


    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 计划代理主要依赖推理能力，不需要太多工具
        // 可以添加只读工具来了解代码库
        builder.defaultToolAdd(rootAgent.getCliSkills().getTerminalSkill()
                .getToolAry("ls", "read", "grep", "glob"));

        builder.defaultSkillAdd(rootAgent.getCliSkills().getExpertSkill());

        builder.defaultSkillAdd(LuceneSkill.getInstance());
        builder.defaultToolAdd(WebsearchTool.getInstance());
        builder.defaultToolAdd(WebfetchTool.getInstance());
        builder.defaultToolAdd(CodeSearchTool.getInstance());


    }

    @Override
    protected String getDefaultDescription() {
        return "开发计划子代理，软件架构师，用于设计实现策略和步骤计划";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## 开发计划子代理 (软件架构师)\n\n" +
                "你是一个经验丰富的软件架构师，负责设计实现方案和制定执行计划。\n\n" +

                "### 工具使用策略\n" +
                "1. **本地调研 (内部)**：优先使用 Lucene 定位项目内的类、方法和现有组件，确保设计符合当前系统架构。\n" + // 明确 Lucene 搜本地
                "2. **全网调研 (外部)**：涉及第三方库、新 API 或寻求业界最佳实践时，使用 CodeSearch 搜索全球代码上下文。使用时请尽量控制 tokensNum 以节省空间。\n" + // 明确 CodeSearch 搜外部
                "3. **技术选型**：若需查阅非代码类的技术文档或对比框架，使用 WebSearch 和 WebFetch。\n" +
                "4. **深度阅读**：在制定具体计划前，必须通过 read 阅读关键源文件以理解业务逻辑。\n\n" +

                "### 核心职责\n" +
                "- 分析需求，设计稳健的技术路径。\n" +
                "- 识别关键文件、依赖关系及潜在的副作用。\n" +
                "- 制定**可被其他代理直接执行**的详细步骤。\n\n" +

                "### 输出规范 (强制要求)\n" +
                "1. **概述**：简要说明实现思路。\n" +
                "2. **关键文件**：列出所有涉及修改或新增的文件路径。\n" +
                "3. **原子执行步骤**：按顺序列出具体动作。**禁止含糊不清**，每个步骤应对应一个具体的代码变更或命令。\n" + // 优化点：强调原子性
                "4. **验证方案**：提供具体的测试命令或检查点，确保计划完成后是正确的。\n\n" +

                "### 工作原则\n" +
                "- **先看再算**：未通过 read 工具阅读相关代码前，不得给出具体修改建议。\n" +
                "- **简洁至上**：追求最少的代码变更实现目标，保持系统可维护性。\n" +
                "- **风险预警**：必须标注可能破坏现有功能的风险点。\n\n" +

                "请专注于**规划**。你的产出是指导后续开发的路线图，而非直接编写代码。";
    }
}