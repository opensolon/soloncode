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
import org.noear.solon.ai.codecli.core.LuceneSkill;
import org.noear.solon.ai.codecli.core.tool.CodeSearchTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;
import org.noear.solon.core.util.Assert;

/**
 * 动态子代理 - 从 MD 文件动态加载提示词
 *
 * @author bai
 * @since 3.9.5
 */
public class DynamicSubagent extends AbsSubagent {

    private final String subagentType;


    public DynamicSubagent(AgentKernel mainAgent, String subagentType) {
        super(mainAgent);

        this.subagentType = subagentType;
    }

    /**
     * 初始化动态代理
     */
    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 添加所有核心技能
        builder.defaultSkillAdd(mainAgent.getCliSkills());

        builder.defaultSkillAdd(LuceneSkill.getInstance());

        // 添加网络工具
        builder.defaultToolAdd(WebfetchTool.getInstance());
        builder.defaultToolAdd(WebsearchTool.getInstance());
        builder.defaultToolAdd(CodeSearchTool.getInstance());

        // 设置最大步数
        builder.maxSteps(25);

        // 设置会话窗口大小
        builder.sessionWindowSize(10);
    }

    @Override
    public String getType() {
        return subagentType;
    }

    @Override
    protected String getDefaultDescription() {
        return String.format("动态自定义子代理 [%s]，可执行该领域专属的深度调研、代码修改和多步复合任务", subagentType);
    }

    @Override
    protected String getDefaultSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 动态子代理 (角色类型: ").append(subagentType).append(")\n\n");
        sb.append("你是一个在 **").append(subagentType).append("** 领域具有深度专业知识的执行专家。\n\n");

        sb.append("### 工作指引\n");
        sb.append("1. **领域优先**：请严格遵循通过 `.md` 或其他配置文件注入的特定领域指令集。\n");
        sb.append("2. **工具链配合**：\n");
        sb.append("   - 使用 `Lucene` 快速扫描项目内的类和方法。\n");
        sb.append("   - 使用 `CodeSearch` 获取全球范围内的 API 最佳实践和代码参考。\n");
        sb.append("   - 使用 `bash` 和相关读写工具执行实际的工程变更。\n");
        sb.append("3. **结果验证**：每一步关键修改后，必须通过终端命令进行功能验证或编译检查。\n\n");

        sb.append("### 基本准则\n");
        sb.append("- 保持逻辑严密。如果当前工具无法解决问题，请分析原因并尝试调整搜索词或执行策略。\n");
        sb.append("- 如果遇到不确定的技术细节，优先进行调研而非猜测。\n");

        return sb.toString();
    }
}