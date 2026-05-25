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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.CommandContext;

import java.util.List;

/**
 * CLI 命令执行上下文（实现通用 CommandContext 接口，持有 JLine Terminal/Reader）
 *
 * @author noear
 * @since 2026.4.28
 */
public class WebCommandContext implements CommandContext {
    private final AgentSession session;
    private final HarnessEngine agentRuntime;
    private final String rawInput;
    private final String commandName;
    private final List<String> args;
    private final AgentTaskRunner agentTaskRunner;

    private final StringBuilder outputBuffer = new StringBuilder();
    private boolean isAgentTask = false;

    /**
     * Agent 任务回调接口
     */
    @FunctionalInterface
    public interface AgentTaskRunner {
        void run(String prompt, String model);
    }

    public WebCommandContext(AgentSession session,
                             HarnessEngine agentRuntime,
                             String rawInput, String commandName, List<String> args,
                             AgentTaskRunner agentTaskRunner) {
        this.session = session;
        this.agentRuntime = agentRuntime;
        this.rawInput = rawInput;
        this.commandName = commandName;
        this.args = args;
        this.agentTaskRunner = agentTaskRunner;
    }

    @Override
    public AgentSession getSession() {
        return session;
    }

    @Override
    public HarnessEngine getEngine() {
        return agentRuntime;
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
    public void println(String text) {
        outputBuffer.append(text).append("\n");
    }

    @Override
    public boolean supportsAnsi() {
        return false;
    }

    @Override
    public void runAgentTask(String input, String model) {
        if (agentTaskRunner != null) {
            isAgentTask = true;
            agentTaskRunner.run(input, model);
        }
    }

    public boolean isAgentTask() {
        return isAgentTask;
    }

    public StringBuilder getOutputBuffer() {
        return outputBuffer;
    }
}
