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

/**
 * 计划子代理 - 软件架构师
 *
 * @author bai
 * @since 3.9.5
 */
public class PlanSubAgent extends AbstractSubAgent {

    private final String workDir;

    public PlanSubAgent(SubAgentConfig config, AgentSessionProvider sessionProvider, String workDir) {
        super(config, sessionProvider);
        this.workDir = workDir;
    }

    /**
     * 初始化计划代理
     */
    public void initialize(ChatModel chatModel) {
        initAgent(chatModel, builder -> {
            // 计划代理主要依赖推理能力，不需要太多工具
            // 可以添加只读工具来了解代码库

            // 设置最大步数（计划任务通常需要较少步数）
            builder.maxSteps(20);

            // 设置会话窗口大小
            builder.sessionWindowSize(8);
        });
    }

    @Override
    protected String buildSystemPrompt() {
        return "## 计划代理（软件架构师）\n\n" +
                "你是一个经验丰富的软件架构师，负责设计实现方案和制定执行计划。\n" +
                "\n" +
                "### 核心职责\n" +
                "- 分析需求，理解任务目标\n" +
                "- 设计实现方案和技术路径\n" +
                "- 识别关键文件和依赖关系\n" +
                "- 制定清晰的执行步骤\n" +
                "- 评估架构权衡和技术选型\n" +
                "\n" +
                "### 输出格式\n" +
                "你的计划应包含：\n" +
                "1. **概述**：简要说明实现思路\n" +
                "2. **关键文件**：列出需要修改的核心文件\n" +
                "3. **执行步骤**：按顺序列出具体的实现步骤\n" +
                "4. **注意事项**：标注潜在风险和需要特别处理的地方\n" +
                "5. **验证方案**：说明如何验证实现是否正确\n" +
                "\n" +
                "### 工作原则\n" +
                "- 先理解现有代码，再设计变更\n" +
                "- 保持方案的简洁性和可维护性\n" +
                "- 考虑向后兼容性和副作用\n" +
                "- 提供多个备选方案（如果有）\n" +
                "- 避免过度设计\n" +
                "\n" +
                "请专注于**规划**而不是直接实现代码。你的输出应该是一个清晰、可执行的计划。\n";
    }
}
