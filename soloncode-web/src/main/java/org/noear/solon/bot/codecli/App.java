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

import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.bot.codecli.portal.WebGate;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.AgentProperties;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 启动类
 * @author oisin
 * @date 2026年3月13日
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
                new FileAgentSession(key, Paths.get(config.getWorkDir(), AgentKernel.SOLONCODE_SESSIONS, key).normalize().toFile().toString()));


        AgentKernel agentKernel = new AgentKernel(chatModel, config, sessionProvider, null);

        if (config.isWebEnabled()) {
            Solon.app().router().get(config.getWebEndpoint(), new WebGate(agentKernel));
        }

    }
}