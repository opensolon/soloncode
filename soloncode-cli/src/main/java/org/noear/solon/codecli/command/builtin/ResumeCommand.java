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
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.util.Assert;

import java.util.List;

/**
 * /resume 命令
 *
 * @author noear
 * @since 2026.4.28
 */
public class ResumeCommand implements Command {
    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "恢复最后一个未完成的任务";
    }

    @Override
    public boolean execute(CommandContext ctx) throws Exception {
        AgentSession session = ctx.getSession();

        //优化 "/resume"
        ReActTrace trace = ReActTrace.getCurrent(session.getContext());
        if (trace != null) {
            if (trace.getFinalAnswer() != null && Agent.ID_END.equals(trace.getRoute())) {
                //说明有结束节点，重新回到思考点点
                trace.setRoute(ReActAgent.ID_REASON);
                trace.setFinalAnswer(null, false);
                trace.getWorkingMemory().removeLastMessage();

                //回退一条 ai 消息（要生新生成）
                List<ChatMessage> messageList = session.getLatestMessages(1);
                if (Assert.isNotEmpty(messageList) && messageList.get(0) instanceof AssistantMessage) {
                    session.removeLatestMessage(1);
                }
            }
        }

        ctx.runAgentTask(null, null);
        return true;
    }
}
