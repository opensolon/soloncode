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
import org.noear.solon.ai.codecli.core.CliSkillProvider;
import org.noear.solon.ai.codecli.core.AgentKernel;
import org.noear.solon.ai.codecli.core.PoolManager;
import org.noear.solon.ai.codecli.core.tool.CodeSearchTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;

/**
 * 动态子代理 - 从 MD 文件动态加载提示词
 *
 * @author bai
 * @since 3.9.5
 */
public class DynamicSubAgent extends AbstractSubAgent {

    private final String workDir;
    private final PoolManager poolManager;
    private final AgentKernel mainCodeAgent;
    private final String customPrompt;

    public DynamicSubAgent(SubAgentConfig config, AgentSessionProvider sessionProvider,
                           String workDir, PoolManager poolManager,
                           AgentKernel mainCodeAgent, String customPrompt) {
        this.workDir = workDir;
        this.poolManager = poolManager;
        this.mainCodeAgent = mainCodeAgent;
        this.customPrompt = customPrompt;
    }

    /**
     * 初始化动态代理
     */
    public void initialize(ChatModel chatModel) {
        initAgent(chatModel, builder -> {
            // 添加完整技能集
            CliSkillProvider skillProvider = new CliSkillProvider(workDir);

            if (poolManager != null) {
                poolManager.getPoolMap().forEach((alias, path) -> {
                    skillProvider.skillPool(alias, path);
                });
            }

            // 添加所有核心技能
            builder.defaultSkillAdd(skillProvider.getTerminalSkill());
            builder.defaultSkillAdd(skillProvider.getExpertSkill());

            // 添加网络工具
            builder.defaultToolAdd(WebfetchTool.getInstance());
            builder.defaultToolAdd(WebsearchTool.getInstance());

            // 添加代码搜索工具（与主代理共享能力）
            builder.defaultToolAdd(CodeSearchTool.getInstance());

            // 设置最大步数
            builder.maxSteps(25);

            // 设置会话窗口大小
            builder.sessionWindowSize(10);
        });
    }

    @Override
    protected String getDefaultSystemPrompt() {
        // 使用自定义提示词
        if (customPrompt != null && !customPrompt.isEmpty()) {
            return customPrompt;
        }
        // 如果没有自定义提示词，使用默认提示词
        return "## 动态代理\n\n" +
                "你是一个专门的任务执行代理，根据用户的需求完成相应任务。\n" +
                "\n" +
                "请充分利用你提供的工具和技能，高效完成任务。\n";
    }

    @Override
    protected String buildSystemPrompt() {
        // 动态代理优先使用自定义提示词
        if (customPrompt != null && !customPrompt.isEmpty()) {
            return customPrompt;
        }
        return super.buildSystemPrompt();
    }
}
