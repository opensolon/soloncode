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
import org.noear.solon.SolonApp;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessProperties;
import org.noear.solon.core.util.Assert;

import java.net.URL;
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
            initAgentProperties(app);
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

    private static void initAgentProperties(SolonApp app) throws Exception{
        //加载配置文件
        AgentProperties c = new AgentProperties();
        URL configUrl = c.getConfigUrl();
        app.cfg().loadAdd(configUrl);

        //获取命令行运行的当前用户工作区
        String workspace = Paths.get(AgentProperties.getUserDir()).toAbsolutePath().normalize().toString();
        app.cfg().getProp("soloncode").bindTo(c);

        if(c.getChatModel() != null){
            c.addModel(c.getChatModel().getModel(), c.getChatModel());
        }

        //设定默认会话id
        String sessionId = Solon.cfg().argx().get(AgentProperties.ARG_SESSION);
        if (Assert.isNotEmpty(sessionId)) {
            c.setSessionId(sessionId);
        }

        //设定默认工作区
        c.setWorkspace(workspace);

        //推入容器
        app.context().wrapAndPut(AgentProperties.class, c);

        //-----

        app.enableHttp(false); //默认不启用 http

        String flag = app.cfg().argx().flagAt(0);

        if (AgentFlags.FLAG_ACP.equals(flag)) {
            c.setAcpEnabled(true);
            c.setCliEnabled(false);
            c.setWebEnabled(false);

            //开始控制台日志
            app.cfg().setProperty("solon.logging.appender.console.enable", "true");
        }

        if (c.isWebEnabled()) {
            app.enableHttp(true);
        }

        if (c.isAcpEnabled() && "stdio".equals(c.getAcpTransport()) == false) {
            app.enableHttp(true);
            app.enableWebSocket(true);
        }

        if (c.isWsEnabled()) {
            app.enableHttp(true);
            app.enableWebSocket(true);
        }

        if (AgentFlags.FLAG_SERVE.equals(flag)) {
            app.enableHttp(true);
            app.enableWebSocket(true);

            c.setCliEnabled(false);
            c.setAcpEnabled(false);

            //开始控制台日志
            app.cfg().setProperty("solon.logging.appender.console.enable", "true");
        }
    }
}