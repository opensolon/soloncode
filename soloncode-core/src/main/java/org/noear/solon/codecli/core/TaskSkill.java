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
package org.noear.solon.codecli.core;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.core.agent.AgentDefinition;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final AgentRuntime agentRuntime;

    public TaskSkill(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    public String description() {
        return "子代理管理专家：委派任务给专门的子代理（explore、plan、bash等）";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 当前可用的子代理注册表\n");
        sb.append("<available_agents>\n");
        for (AgentDefinition agentDefinition : agentRuntime.getAgentManager().getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agentDefinition.getName(), agentDefinition.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("**提醒**： 如果以上子代理不满足需求，可通过 generate_agent 生态生成新的子代理。");

        return sb.toString();
    }

    @ToolMapping(name = "task", description =
            "派生并分派任务给专项子代理。所有实际开发工作必须使用此工具委派给子代理完成。\n" +
                    "\n" +
                    "调用约定：\n" +
                    "\n" +
                    "- **上下文对齐**: 子代理看不见当前历史，必须在 prompt 中传入必要的上下文")
    public String task(
            @Param(name = "name", description = "子代理名称") String name,
            @Param(name = "prompt", description = "具体指令。必须包含任务目标、关键类名或必要的背景上下文。") String prompt,
            @Param(name = "description", required = false, description = "简短的任务描述") String description,
            @Param(name = "taskId", required = false, description = "可选。若要继续之前的任务会话，请传入对应的 task_id") String taskId,
            String __cwd,
            String __sessionId
    ) {
        AgentSession __parentSession = agentRuntime.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        try {
            AgentDefinition agentDefinition = agentRuntime.getAgentManager().getAgent(name);
            if (agentDefinition == null) {
                return "ERROR: 未知的子代理类型 '" + name + "'。";
            }

            String finalSessionId = Assert.isEmpty(taskId)
                    ? "subagent_" + name
                    : taskId;

            LOG.info("分派任务 -> 类型: {}, 会话: {}, 描述: {}", name, finalSessionId, description);


            String result = null;

            ReActAgent agent = agentDefinition.builder(agentRuntime).build();
            AgentSession session = agentRuntime.getSession(finalSessionId);

            if (__parentTrace.getOptions().getStreamSink() == null) {
                // 同步模式
                AgentResponse response = agent.prompt(prompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut("__cwd", __cwd);
                        })
                        .call();

                result = response.getContent();
                __parentTrace.getMetrics().addMetrics(response.getMetrics());
            } else {
                // 流式模式
                ReActChunk response = (ReActChunk) agent.prompt(prompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut("__cwd", __cwd);
                        })
                        .stream()
                        .doOnNext(chunk -> {
                            if (chunk instanceof ActionEndChunk) {
                                __parentTrace.getOptions().getStreamSink().next(chunk);
                            } else if (chunk instanceof ReasonChunk) {
                                __parentTrace.getOptions().getStreamSink().next(chunk);
                            }
                        })
                        .blockLast();

                result = response.getContent();
                __parentTrace.getMetrics().addMetrics(response.getMetrics());
            }

            LOG.info("子代理任务完成: {}", finalSessionId);

            return String.format(
                    "task_id: %s\n" +
                            "name: %s\n" +
                            "\n" +
                            "<task_result>\n" +
                            "%s\n" +
                            "</task_result>",
                    finalSessionId, name, result != null ? result : "(无输出)"
            );
        } catch (Throwable e) {
            LOG.error("子代理执行失败: type={}, error={}", name, e.getMessage(), e);
            return "ERROR: 子代理执行失败: " + e.getMessage();
        }
    }
}