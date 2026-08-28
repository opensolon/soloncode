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
package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.command.builtin.GoalTalent;
import org.noear.solon.core.util.Assert;

/**
 * 工具事件的「可见性」与「标题」规则 —— 实时流（{@link WebEventMapper}）与
 * 历史回放（last-trace 接口）必须共用同一份判定，否则同一次工具调用在两条路径下
 * 会呈现出不同的名字、或一边显示一边被过滤，导致刷新前后 UI 不一致。
 *
 * @author noear
 */
public final class ToolViewUtil {
    private ToolViewUtil() {
    }

    /**
     * 是否为内部工具（不在对话流中渲染工具卡片）。
     *
     * <p>task/multitask 由 task-group 承载，memory/goal 属于系统侧动作。</p>
     */
    public static boolean isInternalTool(String toolName) {
        return Assert.isEmpty(toolName) ||
                TaskTalent.TOOL_MULTITASK.equals(toolName) ||
                TaskTalent.TOOL_TASK.equals(toolName) ||
                MemoryTalent.isMemoryTool(toolName) ||
                GoalTalent.isGoalTool(toolName);
    }

    /**
     * 历史回放时是否隐藏该工具卡片。
     *
     * <p>与 {@link #isInternalTool(String)} 故意不同：实时流里 task/multitask 被隐藏，是因为
     * 子代理自己的工具事件会另外汇聚成一个 task-group 来承载它；而子代理的过程记在它自己的
     * trace 里，主 trace 的 WorkingMemory 只留下一次 task 调用与其最终产出。回放若同样隐藏，
     * 整段子代理工作就会凭空消失（用户看到的正是「有些工具调用没显示」）。因此回放放行
     * task/multitask，退化为一张普通工具卡展示其最终产出。</p>
     *
     * <p>memory/goal 属于系统侧动作，两条路径下都不展示。</p>
     */
    public static boolean isReplayHidden(String toolName) {
        return Assert.isEmpty(toolName) ||
                MemoryTalent.isMemoryTool(toolName) ||
                GoalTalent.isGoalTool(toolName);
    }

    /**
     * 构造工具卡片标题：子代理调用加 {@code agentName/} 前缀，主代理直接用工具名。
     *
     * @param toolName       工具名
     * @param taskAgentName  task 包装事件透出的子代理名（优先）
     * @param eventAgentName 事件自身的代理名（"main" 视为主代理）
     */
    public static String buildToolTitle(String toolName, String taskAgentName, String eventAgentName) {
        if (Assert.isNotEmpty(taskAgentName)) {
            return taskAgentName + "/" + toolName;
        }

        // agentName 为空时不加前缀，避免拼出 "null/xxx"
        if (Assert.isNotEmpty(eventAgentName) && "main".equals(eventAgentName) == false) {
            return eventAgentName + "/" + toolName;
        }

        return toolName;
    }
}
