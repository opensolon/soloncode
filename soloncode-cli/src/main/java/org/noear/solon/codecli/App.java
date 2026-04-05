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

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.portal.AcpLink;
import org.noear.solon.codecli.portal.CliShellOld;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.codecli.portal.ui.CliShellNew;
import org.noear.solon.codecli.remoting.WebSocketGate;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocketRouter;

import java.net.URL;
import java.nio.file.Paths;
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
            initAgentProperties(app);
        });

        AgentProperties agentProps = Solon.context().getBean(AgentProperties.class);

        if (agentProps == null || agentProps.getChatModel() == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(agentProps.getChatModel()).build();
        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        // 会话数据存到全局目录 ~/.soloncode/sessions/<sessionId>/
        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(agentProps.getWorkspace(), agentProps.getHarnessSessions()).resolve(key).normalize().toFile().toString()));

        HarnessEngine agentRuntime = HarnessEngine.builder()
                .chatModel(chatModel)
                .properties(agentProps)
                .sessionProvider(sessionProvider)
                .build();

        //flag
        if (Solon.cfg().argx().flags().size() > 0) {
            String flag = Solon.cfg().argx().flagAt(0);

            if (AgentFlags.FLAG_RUN.equals(flag)) {
                //单次任务态
                String prompt = Solon.cfg().argx().flagAt(1);
                new CliShellOld(agentRuntime, agentProps).call(prompt);
                Solon.stop();
                return;
            }

            if (AgentFlags.FLAG_SERVE.equals(flag)) {
                Solon.app().router().get(agentProps.getWebEndpoint(), new WebGate(agentRuntime, agentProps));
                WebSocketRouter.getInstance().of(agentProps.getWsEndpoint(), new WebSocketGate(agentRuntime, agentProps));
                return;
            }

            //未来可以支持更多控制标记
        }


        //cli
        if (agentProps.isCliEnabled()) {
            if ("new".equals(agentProps.getUiType())) {
                new Thread(new CliShellNew(agentRuntime, agentProps), "CLI-Interactive-Thread").start();
            } else {
                new Thread(new CliShellOld(agentRuntime, agentProps), "CLI-Interactive-Thread").start();
            }
        }

        //web
        if (agentProps.isWebEnabled()) {
            Solon.app().router().get(agentProps.getWebEndpoint(), new WebGate(agentRuntime, agentProps));
        }

        //ws
        if (agentProps.isWsEnabled()) {
            WebSocketRouter.getInstance().of(agentProps.getWsEndpoint(), new WebSocketGate(agentRuntime, agentProps));
        }

        //acp
        if (agentProps.isAcpEnabled()) {
            AcpAgentTransport agentTransport;
            if ("stdio".equals(agentProps.getAcpTransport())) {
                agentTransport = new StdioAcpAgentTransport();
            } else {
                agentTransport = new WebSocketSolonAcpAgentTransport(
                        agentProps.getAcpTransport(), McpJsonMapper.getDefault());
            }

            new AcpLink(agentRuntime, agentTransport).run();
        }


    }

    private static void initAgentProperties(SolonApp app) throws Throwable {
        //加载配置文件
        AgentProperties c = new AgentProperties();
        URL configUrl = c.getConfigUrl();
        app.cfg().loadAdd(configUrl);

        //获取命令行运行的当前用户工作区
        String workspace = Paths.get(AgentProperties.getUserDir()).toAbsolutePath().normalize().toString();
        app.cfg().getProp("soloncode").bindTo(c);

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

        if (AgentFlags.FLAG_SERVE.equals(app.cfg().argx().flagAt(0))) {
            app.enableHttp(true);
            app.enableWebSocket(true);

            c.setCliEnabled(false);
            c.setAcpEnabled(false);

            //开始控制台日志
            app.cfg().setProperty("solon.logging.appender.console.enable", "true");
        }
    }
}