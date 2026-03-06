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
import org.noear.solon.ai.codecli.core.tool.ReadSolonDocTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;

/**
 * Solon Code 指南代理 - 回答 Solon Code、Solon Agent SDK 和 Solon API 相关问题
 *
 * @author bai
 * @since 3.9.5
 */
public class SolonGuideSubagent extends AbstractSubagent {
    public SolonGuideSubagent(AgentKernel mainAgent) {
        super(mainAgent);
    }

    @Override
    protected void initialize(ReActAgent.Builder builder) {
        // 添加专家技能（用于技能搜索和读取）
        builder.defaultSkillAdd(mainAgent.getCliSkills().getExpertSkill());

        // 添加网络获取工具（用于读取在线文档）
        builder.defaultToolAdd(WebfetchTool.getInstance());

        // 添加自定义工具：读取 Solon 文档（传递 workDir）
        builder.defaultToolAdd(new ReadSolonDocTool(mainAgent.getProps().getWorkDir()));

        // 设置较小的步数限制（主要是查询和回答）
        builder.maxSteps(15);

        // 设置会话窗口大小
        builder.sessionWindowSize(5);
    }

    @Override
    public String getName() {
        return "solon-guide";
    }

    @Override
    public String getDescription() {
        return "Solon Code 指南代理，专门回答关于 Solon Code、Solon Agent SDK 和 Solon API 的问题";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## Solon 开发指南子代理\n\n" +
                "你是 Solon Code、Solon Agent SDK 和 Solon API 的专家指南，专门回答相关问题。\n" +
                "\n" +
                "### 核心知识\n" +
                "- **Solon Code**：基于 Java 的 AI 编程助手，兼容 Claude Code Agent Skills 规范\n" +
                "- **Solon Agent SDK**：用于构建 AI Agent 的开发框架\n" +
                "- **Solon API**：Solon 框架的应用程序接口\n" +
                "- **ReAct 模式**：推理-行动循环，用于 Agent 决策\n" +
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
