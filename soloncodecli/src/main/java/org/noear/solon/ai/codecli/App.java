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
package org.noear.solon.ai.codecli;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.codecli.portal.AcpLink;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.ai.codecli.portal.CliShell;
import org.noear.solon.ai.codecli.portal.WebGate;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.core.util.Assert;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cli 应用
 *
 * @author noear
 * @since 3.9.1
 */
public class App {

    public static void main(String[] args) {
        Solon.start(App.class, args, app -> {
            Config c = app.cfg().toBean("solon.code.cli", Config.class);

            app.enableHttp(false); //默认不启用 http

            if (c.webEnabled) {
                app.enableHttp(true);
            }

            if (c.acpEnabled && "stdio".equals(c.acpTransport) == false) {
                app.enableHttp(true);
                app.enableWebSocket(true);
            }
        });

        Config config = Solon.context().getBean(Config.class);

        if (config == null || config.chatModel == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(config.chatModel).build();
        Map<String, AgentSession> store = new ConcurrentHashMap<>();
        AgentSessionProvider sessionProvider = (sessionId) -> store.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, config.workDir + "/.soloncode/sessions/" + key));

        final McpProviders mcpProviders;

        try {
            if (Assert.isNotEmpty(config.mcpServers)) {
                mcpProviders = McpProviders.fromMcpServers(config.mcpServers);
            } else {
                mcpProviders = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Mcp servers load failure", e);
        }

        CodeAgent codeAgent = new CodeAgent(chatModel)
                .workDir(config.workDir)
                .session(sessionProvider)
                .enableHitl(config.hitlEnabled)
                .config(agent -> {
                    if (mcpProviders != null) {
                        for (McpClientProvider mcpProvider : mcpProviders.getProviders().values()) {
                            agent.defaultToolAdd(mcpProvider);
                        }
                    }

                    // 添加步数
                    agent.maxSteps(config.maxSteps);
                    // 添加步数自动扩展
                    agent.maxStepsExtensible(config.maxStepsAutoExtensible);
                    // 添加会话窗口大小
                    agent.sessionWindowSize(config.sessionWindowSize);
                });

        if (Assert.isNotEmpty(config.mountPool)) {
            config.mountPool.forEach((alias, dir) -> {
                codeAgent.skillPool(alias, dir);
            });
        }

        if (Assert.isNotEmpty(config.skillPools)) {
            config.skillPools.forEach((alias, dir) -> {
                codeAgent.skillPool(alias, dir);
            });
        }

        if (config.cliEnabled) {
            new Thread(new CliShell(codeAgent, config.cliPrintSimplified), "CLI-Interactive-Thread").start();
        }

        if (config.webEnabled) {
            Solon.app().router().get(config.webEndpoint, new WebGate(codeAgent));
        }

        if (config.acpEnabled) {
            AcpAgentTransport agentTransport;
            if ("stdio".equals(config.acpTransport)) {
                agentTransport = new StdioAcpAgentTransport();
            } else {
                agentTransport = new WebSocketSolonAcpAgentTransport(
                        config.acpEndpoint, McpJsonMapper.getDefault());
            }

            new AcpLink(codeAgent, agentTransport).run();
        }
    }
}