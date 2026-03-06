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
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;

/**
 * Solon Code 指南代理 - 回答 Solon Code、Solon Agent SDK 和 Solon API 相关问题
 *
 * @author bai
 * @since 3.9.5
 */
public class SolonGuideSubagent extends AbsSubagent {
    public SolonGuideSubagent(AgentKernel mainAgent) {
        super(mainAgent);
    }

    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 添加专家技能（用于技能搜索和读取）
        builder.defaultSkillAdd(mainAgent.getCliSkills().getExpertSkill());

        // 添加网络获取工具（用于读取在线文档）
        builder.defaultToolAdd(WebfetchTool.getInstance());
        builder.defaultToolAdd(WebsearchTool.getInstance());

        // 添加自定义工具：读取 Solon 文档（传递 workDir）
        builder.defaultToolAdd(new SolonDocTool(mainAgent.getProps().getWorkDir()));

        // 设置较小的步数限制（主要是查询和回答）
        builder.maxSteps(15);

        // 设置会话窗口大小
        builder.sessionWindowSize(5);
    }

    @Override
    public String getType() {
        return "solon-guide";
    }

    @Override
    protected String getDefaultDescription() {
        return "Solon 开发指南子代理，专门回答关于 Solon Code、Solon Agent SDK 和 Solon API 的问题";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## Solon 开发指南子代理\n\n" +
                "你是一个深谙 Solon 生态的技术专家，负责为开发者提供关于 Solon Code、Agent SDK 及框架 API 的权威指导。\n\n" +

                "### 工具使用策略\n" +
                "1. **文档检索**：如果不确定文档确切名称，先调用 `list_solon_docs` 查看目录。\n" +
                "2. **精准阅读**：使用 `solon_doc_read` 获取官方规范；若官网无记录，再尝试 `websearch`。\n" +
                "3. **本地对比**：若用户询问当前项目的实现，可利用 `expert` 技能读取本地代码，并与官方文档进行对比分析。\n\n" +

                "### 核心职责\n" +
                "- 解析 Solon 核心架构与 AOP 机制。\n" +
                "- 指导 Solon AI / Agent SDK 的 Skill 和 Tool 开发。\n" +
                "- 解释 Solon Code 的 ReAct 执行逻辑与配置规范。\n\n" +

                "### 回答规范\n" +
                "- **文档优先**：所有技术结论应优先参考官方文档，并注明 [来源链接]。\n" +
                "- **代码示例**：提供的 Java 代码应符合 Solon 的轻量级编程风格（如使用 @Component 而非冗余配置）。\n" +
                "- **诚实原则**：若文档与联网搜索均无法涵盖用户问题，请明确告知，并基于 Solon 设计哲学给出逻辑推论。\n\n" +

                "请以专业、严谨且富有启发性的口吻回答问题。";
    }
}
