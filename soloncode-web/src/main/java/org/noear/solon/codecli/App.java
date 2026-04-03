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
package org.noear.solon.codecli;

import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessProperties;

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
            AgentProperties c = new AgentProperties();

            app.cfg().getProp("soloncode").bindTo(c);
            app.context().wrapAndPut(HarnessProperties.class, c);
            app.enableHttp(false); //默认不启用 http

            if (c.isWebEnabled()) {
                app.enableHttp(true);
            }

            if (c.isAcpEnabled() && "stdio".equals(c.getAcpTransport()) == false) {
                app.enableHttp(true);
                app.enableWebSocket(true);
            }
        });

        AgentProperties agentProps = Solon.context().getBean(AgentProperties.class);

        if (agentProps == null || agentProps.getChatModel() == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(agentProps.getChatModel()).build();
        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(agentProps.getWorkspace(), agentProps.getHarnessSessions(), key).normalize().toFile().toString()));


        HarnessEngine agentKernel = HarnessEngine.builder()
                .chatModel(chatModel)
                .properties(agentProps)
                .sessionProvider(sessionProvider)
                .build();

        if (agentProps.isWebEnabled()) {
            Solon.app().router().get(agentProps.getWebEndpoint(), new WebGate(agentKernel));
        }

        if (agentProps.isWebEnabled()) {
            Solon.app().router().get(agentProps.getWebEndpoint(), new WebGate(agentKernel));
        }
    }
}