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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.core.AgentRuntime;
import org.noear.solon.codecli.portal.AcpLink;
import org.noear.solon.codecli.portal.CliShellNew;
import org.noear.solon.codecli.portal.CliShellOld;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.codecli.remoting.WebSocketGate;
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
            //加载配置文件
            URL configUrl = AgentProperties.getConfigUrl();
            app.cfg().loadAdd(configUrl);

            //获取命令行运行的当前用户工作区
            String workDir = Paths.get(AgentProperties.getUserDir()).toAbsolutePath().normalize().toString();
            AgentProperties c = app.cfg().toBean("soloncode", AgentProperties.class);

            c.setWorkDir(workDir);
            app.context().wrapAndPut(AgentProperties.class, c);
            app.enableHttp(false); //默认不启用 http

            if (c.isWebEnabled()) {
                app.enableHttp(true);
            }

            if (c.isAcpEnabled() && "stdio".equals(c.getAcpTransport()) == false) {
                app.enableHttp(true);
                app.enableWebSocket(true);
            }

            if (c.isWsEnabled()) {
                app.enableWebSocket(true);
            }
        });

        AgentProperties agentProperties = Solon.context().getBean(AgentProperties.class);

        if (agentProperties == null || agentProperties.getChatModel() == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(agentProperties.getChatModel()).build();
        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        // 会话数据存到全局目录 ~/.soloncode/sessions/<sessionId>/
        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(agentProperties.getWorkDir(), AgentRuntime.SOLONCODE_SESSIONS).resolve(key).normalize().toFile().toString()));

        AgentRuntime agentRuntime = AgentRuntime.builder()
                .chatModel(chatModel)
                .properties(agentProperties)
                .sessionProvider(sessionProvider)
                .build();

        //flag
        if(Solon.cfg().argx().flags().size() > 0){
            String flag = Solon.cfg().argx().flagAt(0);

            if ("run".equals(flag)) {
                //单次任务态
                String prompt = Solon.cfg().argx().flagAt(1);
                new CliShellOld(agentRuntime).call(prompt);
                Solon.stop();
                return;
            }

            //未来可以支持更多控制标记
        }



        //cli
        if (agentProperties.isCliEnabled()) {
            if ("new".equals(agentProperties.getUiType())) {
                new Thread(new CliShellNew(agentRuntime), "CLI-Interactive-Thread").start();
            } else {
                new Thread(new CliShellOld(agentRuntime), "CLI-Interactive-Thread").start();
            }
        }

        //web
        if (agentProperties.isWebEnabled()) {
            Solon.app().router().get(agentProperties.getWebEndpoint(), new WebGate(agentRuntime));
        }

        //acp
        if (agentProperties.isAcpEnabled()) {
            AcpAgentTransport agentTransport;
            if ("stdio".equals(agentProperties.getAcpTransport())) {
                agentTransport = new StdioAcpAgentTransport();
            } else {
                agentTransport = new WebSocketSolonAcpAgentTransport(
                        agentProperties.getAcpTransport(), McpJsonMapper.getDefault());
            }

            new AcpLink(agentRuntime, agentTransport).run();
        }

        if (agentProperties.isWsEnabled()){
            WebSocketRouter.getInstance().of("ws", new WebSocketGate(agentRuntime));
        }
    }
}