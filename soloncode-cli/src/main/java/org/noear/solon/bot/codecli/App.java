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
package org.noear.solon.bot.codecli;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.bot.core.AgentProperties;
import org.noear.solon.bot.codecli.portal.AcpLink;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.codecli.portal.CliShell;
import org.noear.solon.bot.codecli.portal.WebGate;

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
            AgentProperties c = app.cfg().toBean("solon.code.cli", AgentProperties.class);
            app.context().wrapAndPut(AgentProperties.class, c);
            app.enableHttp(false); //默认不启用 http

            if (c.isWebEnabled()) {
                app.enableHttp(true);
            }

            if (c.isAcpEnabled() && "stdio".equals(c.getAcpTransport()) == false) {
                app.enableHttp(true);
                app.enableWebSocket(true);
            }
        });

        AgentProperties config = Solon.context().getBean(AgentProperties.class);

        if (config == null || config.getChatModel() == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(config.getChatModel()).build();
        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, config.getWorkDir() + AgentKernel.SOLONCODE_SESSIONS + key));


        AgentKernel agentKernel = new AgentKernel(chatModel, config, sessionProvider, null);


        if (config.isCliEnabled()) {
            new Thread(new CliShell(agentKernel), "CLI-Interactive-Thread").start();
        }

        if (config.isWebEnabled()) {
            Solon.app().router().get(config.getWebEndpoint(), new WebGate(agentKernel));
        }

        if (config.isAcpEnabled()) {
            AcpAgentTransport agentTransport;
            if ("stdio".equals(config.getAcpTransport())) {
                agentTransport = new StdioAcpAgentTransport();
            } else {
                agentTransport = new WebSocketSolonAcpAgentTransport(
                        config.getAcpTransport(), McpJsonMapper.getDefault());
            }

            new AcpLink(agentKernel, agentTransport).run();
        }
    }
}