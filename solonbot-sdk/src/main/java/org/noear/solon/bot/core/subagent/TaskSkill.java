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

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 子代理技能
 *
 * 将子代理能力暴露为可调用的工具（Claude Code Subagent 类似实现）
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(TaskSkill.class);

    private final AgentKernel mainAgent;
    private final SubagentManager manager;

    public TaskSkill(AgentKernel mainAgent, SubagentManager manager) {
        this.mainAgent = mainAgent;
        this.manager = manager;
    }


    @Override
    public String description() {
        return "战略任务调度与子代理委派专家";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("处理复杂的、多步骤的任务，优先委派子代理（Subagent）执行\n\n");

        sb.append("可用的代理类型及其拥有的工具：\n");
        sb.append("<available_agents>\n");
        for (Subagent agent : manager.getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agent.getType(), agent.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("## 战略任务委派 (Task Delegation)\n");
        sb.append("当你面临高熵任务（信息量大、不确定性高）时，应启动专项子代理。这能让你保持高层视野，避免被底层执行细节干扰。\n\n");

        sb.append("### 委派子代理的显著优势：\n");
        sb.append("- **上下文隔离**：子代理内部的数十次搜索和读取不会污染你的主对话历史。\n");
        sb.append("- **专注深度**：子代理在特定领域（如架构探索、测试驱动开发）拥有比原子工具更强的自主纠错逻辑。\n");
        sb.append("- **并行加速**：你可以在单条消息中 `task(...)` 启动多个代理同时分析不同的模块。\n\n");

        sb.append("### 强制委派场景：\n");
        sb.append("- **未知领域探索**：如“找出该项目的认证逻辑实现”，此类涉及跨文件链式追踪的任务。\n");
        sb.append("- **闭环变更**：涉及“修改代码 + 运行测试 + 修复错误”的循环任务。\n");
        sb.append("- **信息密集型操作**：预感需要连续调用 3 次以上 `read` 或 `grep` 时。\n\n");

        sb.append("### 调用深度指南：\n");
        sb.append("1. **精准上下文注入**：子代理初始状态是孤立的。你必须在 `prompt` 中提供核心锚点。使用 `<context>` 标签包裹路径、类名或错误日志。\n");
        sb.append("2. **定义输出格式**：明确要求子代理在 `task_result` 中提供“结论、修改点、待办事项”，方便你向用户汇报。\n");
        sb.append("3. **会话延续**：如果子代理没能一次性解决，利用返回的 `task_id` 再次调用，它将保留之前的内存。\n\n");

        return sb.toString();
    }


    @ToolMapping(name = "task", description = "分派一个战略任务给子代理")
    public String task(
            @Param(name = "subagent_type", description = "子代理类型") String subagent_type,
            @Param(name = "prompt",description = "具体指令。必须包含任务目标、关键类名或必要的背景上下文。") String prompt,
            @Param(name = "description", required = false, description = "简短的任务描述（3-5个词），给用户看") String description,
            @Param(name = "taskId", required = false, description = "可选。若要继续之前的任务会话，请传入对应的 task_id") String taskId,
            String __cwd,
            String __sessionId
    ) {
        AgentSession __parentSession = mainAgent.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getSnapshot());

        try {
            Subagent agent = manager.getAgent(subagent_type);

            if (agent == null) {
                return "ERROR: 未知的子代理类型 '" + subagent_type + "'。请从 <available_agents> 列表中选择。";
            }

            // 1. 会话标识处理：如果有 task_id 则沿用，否则生成基于类型的标识
            String finalSessionId = Assert.isEmpty(taskId)
                    ? "subagent_" + subagent_type + "_" + System.currentTimeMillis()
                    : taskId;

            LOG.info("分派任务 -> 类型: {}, 会话: {}, 描述: {}", subagent_type, finalSessionId, description);

            // 2. 这里的 AgentSession 逻辑应与 AgentKernel 深度绑定
            // 注意：execute 内部应能识别 sessionId 并从 sessionManager 恢复上下文

            String result = null;

            if (__parentTrace.getOptions().getStreamSink() == null) {
                //没有流信息（同步）
                AgentResponse response = agent.call(__cwd, finalSessionId, Prompt.of(prompt));
                result = response.getContent();

                //累计 tokens 计数
                __parentTrace.getMetrics().addMetrics(response.getMetrics());
            } else {
                ReActChunk chunk1 = (ReActChunk) agent.stream(__cwd, finalSessionId, Prompt.of(prompt))
                        .doOnNext(chunk -> {
                            if(chunk instanceof ActionChunk) {
                                __parentTrace.getOptions().getStreamSink().next(chunk);
                            } else if(chunk instanceof ReasonChunk){
                                __parentTrace.getOptions().getStreamSink().next(chunk);
                            }
                        })
                        .blockLast();

                result = chunk1.getContent();
                //累计 tokens 计数
                __parentTrace.getMetrics().addMetrics(chunk1.getResponse().getMetrics());
            }


            LOG.info("子代理任务完成: {}", finalSessionId);

            // 3. 结构化输出，方便主代理识别结果并知道如何继续
            return String.format(
                    "task_id: %s\n" +
                            "subagent_type: %s\n" +
                            "\n" +
                            "<task_result>\n" +
                            "%s\n" +
                            "</task_result>",
                    finalSessionId, subagent_type, result
            );

        } catch (Throwable e) {
            LOG.error("子代理执行崩溃: type={}, error={}", subagent_type, e.getMessage(), e);
            return "ERROR: 子代理执行失败: " + e.getMessage();
        }
    }
}