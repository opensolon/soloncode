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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.core.util.Assert;
import reactor.core.Disposable;

/**
 * /interrupt 命令 - 中断当前正在执行的任务。
 *
 * <p>在 CLI 中用户可通过 ESC 键中断，在 Web 前端可通过停止按钮中断，
 * 但在 IM 渠道（微信/飞书/钉钉）中缺少中断途径。此命令为 IM 场景提供
 * 统一的中断能力，也适用于所有终端。</p>
 *
 * @author noear
 * @since 2026.5.15
 */
public class InterruptCommand implements Command {
    @Override
    public String name() {
        return "interrupt";
    }

    @Override
    public String description() {
        return "中断当前正在执行的任务";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/interrupt",
                "/interrupt <sessionId>"
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

        Disposable disposable = (Disposable) session.attrs().remove("disposable");
        if (disposable != null) {
            disposable.dispose();
            session.addMessage(ChatMessage.ofAssistant("用户已取消任务."));
            ctx.println("用户已取消任务（或中断）");
        } else {
            ctx.println("当前没有正在执行的任务");
        }
    }
}
