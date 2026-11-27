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
package org.noear.solon.codecli.api.web.pipeline;

import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.harness.extension.goal.GoalTalent;
import org.noear.solon.core.util.Assert;

import java.util.Map;

/**
 * 工具事件的「可见性」与「标题」规则 —— 实时流（{@link WebEventMapper}）与
 * 历史回放（last-trace 接口）必须共用同一份判定，否则同一次工具调用在两条路径下
 * 会呈现出不同的名字、或一边显示一边被过滤，导致刷新前后 UI 不一致。
 *
 * @author noear
 */
public final class ToolViewUtil {
    /** MCP 网关的中转调用工具名（模型经它调用具体的 MCP 工具） */
    static final String MCP_CALL_TOOL = "call_tool";
    /** 标题里服务名、工具名各自的最大长度（参数来自模型，防止异常值撑爆标题） */
    private static final int MCP_NAME_MAX = 64;

    private ToolViewUtil() {
    }

    /**
     * 是否为内部工具（不在对话流中渲染工具卡片）。
     *
     * <p>task / task_start / task_wait / task_stop 由 task-group 承载，memory/goal 属于系统侧动作。</p>
     */
    public static boolean isInternalTool(String toolName) {
        return Assert.isEmpty(toolName) ||
                TaskTalent.isTaskTool(toolName) ||
                MemoryTalent.isMemoryTool(toolName) ||
                GoalTalent.isGoalTool(toolName);
    }

    /**
     * 历史回放时是否隐藏该工具卡片。
     *
     * <p>与 {@link #isInternalTool(String)} 故意不同：实时流里委派工具被隐藏，是因为
     * 子代理自己的工具事件会另外汇聚成一个 task-group 来承载它；而子代理的过程记在它自己的
     * trace 里，主 trace 的 WorkingMemory 只留下一次 task 调用与其最终产出。回放若同样隐藏，
     * 整段子代理工作就会凭空消失（用户看到的正是「有些工具调用没显示」）。因此回放放行
     * task / task_start：带子任务索引的换成子过程卡片，没有索引的退化为一张普通工具卡展示其产出。
     * task_wait / task_stop 两条路径都隐藏：它们取回的结果就是子过程卡片的结论，再出一张卡是重复。</p>
     *
     * <p>memory/goal 属于系统侧动作，两条路径下都不展示。</p>
     */
    public static boolean isReplayHidden(String toolName) {
        return Assert.isEmpty(toolName) ||
                TaskTalent.TOOL_TASK_WAIT.equals(toolName) ||
                TaskTalent.TOOL_TASK_STOP.equals(toolName) ||
                MemoryTalent.isMemoryTool(toolName) ||
                GoalTalent.isGoalTool(toolName);
    }

    /**
     * 构造工具卡片标题：子代理调用加 {@code agentName/} 前缀，主代理直接用工具名。
     *
     * <p>MCP 网关的 {@code call_tool} 只是中转，单看工具名分不出调的是哪个连接器的哪个工具，
     * 因此按参数补成 {@code call_tool · 服务/工具}（缺服务名时为 {@code call_tool · 工具}）。</p>
     *
     * @param toolName       工具名
     * @param args           模型给的工具参数（可为 null）
     * @param taskAgentName  task 包装事件透出的子代理名（优先）
     * @param eventAgentName 事件自身的代理名（"main" 视为主代理）
     */
    public static String buildToolTitle(String toolName, Map<String, Object> args, String taskAgentName, String eventAgentName) {
        String title = toolName;
        if (MCP_CALL_TOOL.equals(toolName)) {
            String target = mcpTargetOf(args);
            if (target != null) {
                title = toolName + " · " + target;
            }
        }

        if (Assert.isNotEmpty(taskAgentName)) {
            return taskAgentName + "/" + title;
        }

        // agentName 为空时不加前缀，避免拼出 "null/xxx"
        if (Assert.isNotEmpty(eventAgentName) && "main".equals(eventAgentName) == false) {
            return eventAgentName + "/" + title;
        }

        return title;
    }

    /**
     * 从 call_tool 参数里取出「服务/工具」；没有工具名时返回 null（标题保持原样）。
     */
    private static String mcpTargetOf(Map<String, Object> args) {
        if (args == null) {
            return null;
        }

        String tool = nameOf(args.get("tool_name"));
        if (tool == null) {
            return null;
        }

        String server = nameOf(args.get("server_name"));
        return server == null ? tool : server + "/" + tool;
    }

    private static String nameOf(Object value) {
        if (value == null) {
            return null;
        }

        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }

        // 名字理应是单行短串；异常值压成一行并截断，避免撑坏卡片标题
        text = text.replaceAll("\\s+", " ");
        if (text.length() > MCP_NAME_MAX) {
            text = text.substring(0, MCP_NAME_MAX) + "…";
        }
        return text;
    }
}
