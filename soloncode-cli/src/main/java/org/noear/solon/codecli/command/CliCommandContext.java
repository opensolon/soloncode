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
package org.noear.solon.codecli.command;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.AgentTaskIntent;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.lang.Nullable;

import java.util.List;

/**
 * CLI 命令执行上下文（实现通用 CommandContext 接口，持有 JLine Terminal/Reader）
 * <p>
 * 命令内部触发的 Agent 任务只记录意图，由分发器交给引擎装配、由 CLI 主循环消费 ——
 * 因此这里不再需要宿主回调，命令实现与端实现彻底解耦。
 *
 * @author noear
 * @since 2026.4.28
 */
public class CliCommandContext implements CommandContext {
    private final AgentSession session;
    private final Terminal terminal;
    private final LineReader reader;
    private final String rawInput;
    private final String commandName;
    private final List<String> args;
    private volatile AgentTaskIntent agentTaskIntent;

    public CliCommandContext(AgentSession session, Terminal terminal, LineReader reader,
                             String rawInput, String commandName, List<String> args) {
        this.session = session;
        this.terminal = terminal;
        this.reader = reader;
        this.rawInput = rawInput;
        this.commandName = commandName;
        this.args = args;
    }

    @Override
    public AgentSession getSession() {
        return session;
    }

    @Override
    public String getRawInput() {
        return rawInput;
    }

    @Override
    public String getCommandName() {
        return commandName;
    }

    @Override
    public List<String> getArgs() {
        return args;
    }

    @Override
    public boolean supportsAnsi() {
        return true;
    }

    @Override
    public void println(String text) {
        terminal.writer().println(text);
        terminal.flush();
    }

    @Override
    public void requestAgentTask(@Nullable String prompt, @Nullable String model) {
        this.agentTaskIntent = new AgentTaskIntent(prompt, model);
    }

    @Override
    public @Nullable AgentTaskIntent getAgentTaskIntent() {
        return agentTaskIntent;
    }
}
