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

import org.jline.terminal.Terminal;
import org.noear.solon.ai.harness.command.AgentTaskIntent;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.lang.Nullable;

/**
 * CLI 命令输出（即时写 JLine 终端，支持 ANSI）
 * <p>
 * 命令需要读取的会话、参数、渠道、语言都在 {@code CommandInput} 上；本类只负责输出。
 * 命令内部触发的 Agent 任务只记录意图，由分发器交给引擎装配、由 CLI 主循环消费 ——
 * 因此这里不需要宿主回调，命令实现与端实现彻底解耦。
 *
 * @author noear
 * @since 2026.4.28
 */
public class CliCommandOutput implements CommandOutput {
    private final Terminal terminal;
    private volatile AgentTaskIntent agentTaskIntent;

    public CliCommandOutput(Terminal terminal) {
        this.terminal = terminal;
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
