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
package org.noear.solon.codecli.api.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.command.builtin.GoalTalent;
import org.noear.solon.core.util.Assert;

/**
 * 桌面端 ReAct 流事件 → WebSocket 协议消息的转换器（无状态）。
 *
 * <p>从原 {@code WsGate} 中拆出 {@code onReasonDeltaEvent / onToolCallStartEvent /
 * onToolCallEndEvent / onReasonEndEvent} 四个纯转换方法：
 * 输入事件、输出 JSON 字符串，不触碰 WebSocket、session attrs 与流状态。</p>
 *
 * <p>agent 前缀规则：子 Agent 的工具名带 {@code agentName/toolName} 前缀并附加
 * {@code agentName} 字段，与前端既有渲染契约一致。</p>
 *
 * @author bai
 * @since 3.9.1
 */
final class DesktopEventConverter {
    private final HarnessEngine engine;

    DesktopEventConverter(HarnessEngine engine) {
        this.engine = engine;
    }

    String onReasonDeltaEvent(ReasonDeltaEvent reasonDeltaEvent, String finalSessionId) {
        String content = reasonDeltaEvent.getText();
        if (content != null && !content.isEmpty()) {
            boolean isThinking = reasonDeltaEvent.isThinking();
            String chunkTypeToSend = isThinking ? "think" : "text";

            ONode node = new ONode().set("type", chunkTypeToSend)
                    .set("sessionId", finalSessionId)
                    .set("text", content);

            String agentName = reasonDeltaEvent.getTrace().getAgentName();
            if (!engine.getName().equals(agentName)) {
                node.set("agentName", agentName);
            }

            return node.toJson();
        }

        return null;
    }

    /**
     * 处理 ActionChunk（工具调用前发送）：在工具实际执行前推送 action_start，
     * 让前端提前渲染 loading 状态的工具卡片骨架，提升流式实时感。
     * 过滤规则与 onObservationChunk 保持一致，避免卡片创建后却无对应结果填充。
     */
    String onToolCallStartEvent(ToolCallStartEvent toolCallStartEvent, String finalSessionId) {
        if (Assert.isEmpty(toolCallStartEvent.getToolName())) {
            return null;
        }

        if (TaskTalent.TOOL_MULTITASK.equals(toolCallStartEvent.getToolName()) ||
                TaskTalent.TOOL_TASK.equals(toolCallStartEvent.getToolName()) ||
                MemoryTalent.isMemoryTool(toolCallStartEvent.getToolName()) ||
                GoalTalent.isGoalTool(toolCallStartEvent.getToolName())) {
            return null;
        }

        // todowrite 的展示走专用通道，由 ObservationChunk 携带完整 todos 渲染，开始阶段不提前建卡
        if ("todowrite".equals(toolCallStartEvent.getToolName())) {
            return null;
        }

        ONode node = new ONode().set("type", "action_start")
                .set("sessionId", finalSessionId)
                .set("reasonId", toolCallStartEvent.getReasonId())
                .set("callId", toolCallStartEvent.getCallId());

        if (engine.getName().equals(toolCallStartEvent.getAgentName())) {
            node.set("toolName", toolCallStartEvent.getToolName());
        } else {
            node.set("toolName", toolCallStartEvent.getAgentName() + "/" + toolCallStartEvent.getToolName());
            node.set("agentName", toolCallStartEvent.getAgentName());
        }

        if (toolCallStartEvent.getArgs() != null) node.set("args", toolCallStartEvent.getArgs());

        return node.toJson();
    }

    String onToolCallEndEvent(ToolCallEndEvent toolCallEndEvent, String finalSessionId) {
        if (toolCallEndEvent.getError() != null) {
            return null;
        }

        if (Assert.isEmpty(toolCallEndEvent.getToolName())) {
            return null;
        }

        if (TaskTalent.TOOL_MULTITASK.equals(toolCallEndEvent.getToolName()) ||
                TaskTalent.TOOL_TASK.equals(toolCallEndEvent.getToolName()) ||
                MemoryTalent.isMemoryTool(toolCallEndEvent.getToolName()) ||
                GoalTalent.isGoalTool(toolCallEndEvent.getToolName())) {
            return null;
        }

        ONode node = new ONode().set("type", "action_end")
                .set("sessionId", finalSessionId)
                .set("reasonId", toolCallEndEvent.getReasonId())
                .set("callId", toolCallEndEvent.getCallId());

        if (engine.getName().equals(toolCallEndEvent.getAgentName())) {
            node.set("toolName", toolCallEndEvent.getToolName());
        } else {
            node.set("toolName", toolCallEndEvent.getAgentName() + "/" + toolCallEndEvent.getToolName());
            node.set("agentName", toolCallEndEvent.getAgentName());
        }

        if (toolCallEndEvent.getResult() != null && toolCallEndEvent.getText() != null) {
            node.set("text", toolCallEndEvent.getText());
        }
        if (toolCallEndEvent.getArgs() != null) node.set("args", toolCallEndEvent.getArgs());

        if ("todowrite".equals(toolCallEndEvent.getToolName())) {
            String todos = (String) toolCallEndEvent.getArgs().get("todos");
            if (Assert.isNotEmpty(todos)) {
                node.set("text", todos);
            }
        }

        return node.toJson();
    }

    String onReasonEndEvent(ReasonEndEvent reasonEndEvent, String finalSessionId) {
        if (reasonEndEvent.hasMeta(TaskTalent.TOOL_MULTITASK)) {
            String content = reasonEndEvent.getText();
            if (Assert.isNotEmpty(content)) {
                ONode node = new ONode().set("type", "text")
                        .set("sessionId", finalSessionId)
                        .set("text", "\n" + content);

                String agentName = reasonEndEvent.getTrace().getAgentName();
                if (!engine.getName().equals(agentName)) {
                    node.set("agentName", agentName);
                }

                return node.toJson();
            }
        }
        return null;
    }
}
