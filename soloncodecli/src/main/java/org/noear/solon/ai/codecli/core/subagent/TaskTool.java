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

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.AbsTool;
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
public class TaskTool extends AbsTool {
    private static final Logger LOG = LoggerFactory.getLogger(TaskTool.class);

    private final SubagentManager manager;

    public TaskTool(SubagentManager manager) {
        this.manager = manager;

        addParam("subagent_type", String.class, "用于此任务的子代理类型");
        addParam("prompt", String.class, "分派给子代理的具体任务要求（应包含详细背景）");
        addParam("description", String.class, false, "简短的任务描述（3-5个词）");
        addParam("task_id", String.class, false, "可选。若要继续之前的任务会话，请传入对应的 task_id");
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append("启动专门的子代理处理复杂任务。子代理独立运行工具流并返回结论。\n\n");

        // 1. 列表优化：增加能力说明（如果 Subagent 接口支持获取能力描述）
        sb.append("### 可用的 subagent_type 列表:\n");
        sb.append("<available_agents>\n");
        for (Subagent agent : manager.getAgents()) {
            // 建议在 Subagent 接口增加一个 getCapabilities() 或类似方法
            sb.append(String.format("  <agent subagent_type=\"%s\" description=\"%s\" />\n",
                    agent.getType(), agent.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        // 2. 准则优化：使用更强烈的对比
        sb.append("### 决策准则:\n");
        sb.append("- **优先使用**: 涉及多步推理、代码重构、运行测试脚本、复杂系统分析。\n");
        sb.append("- **避免使用**: 仅读取已知路径文件、简单的关键字搜索、查看当前目录列表。\n\n");

        // 3. 示例优化：提供结构化的调用示例
        sb.append("### 调用示例:\n");
        sb.append("```json\n");
        sb.append("// 场景：需要对代码进行深度分析并修复\n");
        sb.append("task(subagent_type=\"dev\", prompt=\"分析并修复所有单元测试中发现的并发死锁问题\", description=\"修复并发死锁\")\n\n");
        sb.append("// 场景：继续之前的重构任务\n");
        sb.append("task(subagent_type=\"plan\", task_id=\"subagent_plan_12345\", prompt=\"基于上一步的方案，生成具体的接口定义\", description=\"细化接口设计\")\n");
        sb.append("```\n\n");

        // 4. 注意事项：醒目提示
        sb.append("> [!IMPORTANT]\n");
        sb.append("> 子代理是上下文隔离的。在 prompt 中必须提供任务所需的全部关键背景（如相关的类名、报错信息、具体要求），否则子代理可能因信息不足而失败。\n");

        return sb.toString();
    }

    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        String subagentType = (String) args.get("subagent_type");
        String prompt = (String) args.get("prompt");
        String description = (String) args.get("description");
        String taskId = (String) args.get("task_id");

        String __cwd = (String) args.get("__cwd");

        try {
            Subagent agent = manager.getAgent(subagentType);

            if (agent == null) {
                return "ERROR: 未知的子代理类型 '" + subagentType + "'。请从 <available_agents> 列表中选择。";
            }

            // 1. 会话标识处理：如果有 task_id 则沿用，否则生成基于类型的标识
            String finalSessionId = Assert.isEmpty(taskId)
                    ? "subagent_" + subagentType + "_" + System.currentTimeMillis()
                    : taskId;

            LOG.info("分派任务 -> 类型: {}, 会话: {}, 描述: {}", subagentType, finalSessionId, description);

            // 2. 这里的 AgentSession 逻辑应与 AgentKernel 深度绑定
            // 注意：execute 内部应能识别 sessionId 并从 sessionManager 恢复上下文
            AgentResponse response = agent.execute(finalSessionId, __cwd, Prompt.of(prompt));

            String result = response.getContent();
            LOG.info("子代理任务完成: {}", finalSessionId);

            // 3. 结构化输出，方便主代理识别结果并知道如何继续
            return String.format(
                    "task_id: %s\n" +
                            "subagent_type: %s\n" +
                            "\n" +
                            "<task_result>\n" +
                            "%s\n" +
                            "</task_result>",
                    finalSessionId, subagentType, result
            );

        } catch (Throwable e) {
            LOG.error("子代理执行崩溃: type={}, error={}", subagentType, e.getMessage(), e);
            return "ERROR: 子代理执行失败: " + e.getMessage();
        }
    }
}