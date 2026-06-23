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
package org.noear.solon.codecli.command.builtin;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.core.util.Assert;

import java.util.List;

/**
 * /continue 命令
 *
 * <pre>
 * 用法:
 *   /continue                   → 继续当前会话最后一个未完成的任务
 *   /continue &lt;sessionId&gt;       → 继续指定会话中最后一个未完成的任务（IM 跨会话控制）
 * </pre>
 *
 * @author noear
 * @since 2026.4.28
 */
public class ContinueCommand implements Command {
    @Override
    public String name() {
        return "continue";
    }

    @Override
    public String description() {
        return "继续运行最后一个未完成的任务";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/continue",
                "/continue <sessionId>"
        };
    }

    @Override
    public void execute(CommandContext ctx) throws Exception {
        String sessionId = ctx.argAt(0);
        AgentSession session;

        if (Assert.isNotEmpty(sessionId)) {
            session = ctx.getEngine().getSession(sessionId);
        } else {
            session = ctx.getSession();
            sessionId = session.getSessionId();
        }

        if (session == null) {
            return;
        }

        ReActTrace trace = session.getContext().getAs("__main");
        if (trace != null) {
            if (Agent.ID_END.equals(trace.getRoute())) {
                // 说明有结束节点，重新回到思考点
                trace.setRoute(ReActAgent.ID_REASON);
                trace.setFinalAnswer(null, false);

                ChatMessage workMessage = trace.getWorkingMemory().getLastMessage();
                if (workMessage instanceof AssistantMessage) {
                    trace.getWorkingMemory().removeLastMessage();
                }

                // 回退一条 ai 消息（要重新生成）
                List<ChatMessage> messageList = session.getMessages();
                if (Assert.isNotEmpty(messageList) && messageList.get(messageList.size() - 1) instanceof AssistantMessage) {
                    session.removeLatestMessage(1);
                }
            }
        }

        ctx.runAgentTask(null, null);
    }
}
