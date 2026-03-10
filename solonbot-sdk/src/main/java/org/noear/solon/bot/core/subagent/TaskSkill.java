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
        sb.append("启动一个新的代理（Agent）来自主处理复杂的、多步骤的任务。\n\n");

        sb.append("可用的代理类型及其拥有的工具：\n");
        sb.append("<available_agents>\n");
        for (Subagent agent : manager.getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agent.getType(), agent.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("### 何时使用 Task 工具\n");
        sb.append("- **项目级认知与探索**：凡是涉及“探索项目”、“分析架构”、“查找核心入口”等需要阅读多个文件或理解整个代码库逻辑的任务，必须委派给子代理。\n");
        sb.append("- **复杂代码变更**：涉及跨文件的代码修复、大规模重构，或需要运行测试脚本来验证修改是否正确的任务，必须委派给子代理。\n");
        sb.append("- **长流程决策**：当你预感到需要连续调用超过 3 次原子工具（如多次 grep、多次 read_file）才能完成任务时，应改用子代理以节省主对话的上下文空间。\n");
        sb.append("- **主动协作**：如果子代理描述中提到应“主动使用”（如 `code-reviewer`），请在完成阶段性工作（如写完核心代码）后，自行判断并启动它，无需等待用户指令。\n\n");

        sb.append("### 何时【禁止】使用 Task 工具：\n");
        sb.append("- 如果你想读取特定的文件路径，请使用 `read` 或 `glob` 工具而不是 Task，以便更林捷地找到匹配项。\n");
        sb.append("- 如果你在搜索特定的类定义（如 `class Foo`），请使用 `glob` 工具，以便更快找到匹配项。\n");
        sb.append("- 如果你在特定的一个或 2-3 个文件中搜索代码，请使用 `read` 工具而不是 Task。\n");
        sb.append("- 其他与上述代理描述无关的任务。\n\n");

        sb.append("### 使用注意事项：\n");
        sb.append("1. **并行启动**：只要可能，请同时启动多个代理以最大化性能；为此，请在单条消息中使用多个工具调用。\n");
        sb.append("2. **结果处理**：代理完成后会返回一条消息给你。代理返回的结果对用户不可见。要向用户展示结果，你应该向用户发送一条包含结果简明摘要的文本消息。输出包含一个 `task_id`，你可以稍后重用它来继续同一个子代理会话。\n");
        sb.append("3. **上下文管理**：除非提供 `task_id` 来恢复同一个子代理会话（该会话将保留其之前的消息和工具输出），否则每次代理调用都以全新的上下文开始。在开启新会话时，你的 prompt 应包含高度详细的任务描述供代理自主执行，并且你应该明确指定代理在其最后一条（也是唯一一条）返回给你的消息中应提供哪些信息。\n");
        sb.append("4. **信任度**：代理的输出通常应该是值得信赖的。\n");
        sb.append("5. **明确意图**：清晰地告知代理你期望它编写代码还是仅进行研究（搜索、读取文件、网页抓取等），因为它并不知道用户的意图。如果可能，告诉它如何验证工作（例如：相关的测试命令）。\n");
        sb.append("6. **主动使用**：如果代理描述中提到应该主动使用，那么你应该尽力在用户没有要求的情况下尝试使用它。请自行判断。\n\n");

        sb.append("### 调用约定\n");
        sb.append("- **上下文对齐**: 子代理看不见当前历史。必须在 `prompt` 中通过 <context> 标签传入必要的类名、报错或路径。\n");
        sb.append("- **示例**: `task(subagent_type=\"explore\", prompt=\"分析 demo-web 核心架构\", description=\"架构探索\")`\n");

        return sb.toString();
    }


    @ToolMapping(name = "task", description = "派生并分派任务给专项子代理")
    public String handle(
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