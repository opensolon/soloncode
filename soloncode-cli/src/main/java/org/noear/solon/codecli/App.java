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
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.codecli.portal.AcpLink;
import org.noear.solon.codecli.portal.CliShellOld;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.codecli.portal.ui.CliShellNew;
import org.noear.solon.codecli.remoting.WebSocketGate;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.JavaUtil;
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

        if (AgentFlags.checkUpdate()) {
            // 使用颜色代码让提示更醒目
            System.out.println("\033[33mDiscover the new version: " + AgentFlags.getLastVersion() + "\033[0m");

            if (JavaUtil.IS_WINDOWS) {
                System.out.println("Update: \033[36mirm https://solon.noear.org/soloncode/setup.ps1 | iex\033[0m");
            } else {
                System.out.println("Update: \033[36mcurl -fsSL https://solon.noear.org/soloncode/setup.sh | bash\033[0m");
            }
            System.out.println();
        }

        AgentProperties agentProps = Solon.context().getBean(AgentProperties.class);

        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        // 会话数据存到全局目录 ~/.soloncode/sessions/<sessionId>/
        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(agentProps.getWorkspace(), agentProps.getHarnessSessions()).resolve(key).normalize().toFile().toString()));

        HarnessEngine agentRuntime = HarnessEngine.builder()
                .properties(agentProps)
                .sessionProvider(sessionProvider)
                .build();

        //flag
        if (Solon.cfg().argx().flags().size() > 0) {
            String flag = Solon.cfg().argx().flagAt(0);

            if (AgentFlags.FLAG_RUN.equals(flag)) { // java -jar soloncode.jar run '你好' // soloncode run '你好'
                //单次任务态
                String prompt = Solon.cfg().argx().flagAt(1);
                new CliShellOld(agentRuntime, agentProps).call(prompt);
                Solon.stop();
                return;
            }

            if (AgentFlags.FLAG_SERVE.equals(flag)) { // java -jar soloncode.jar server // soloncode server
                runWeb(agentRuntime, agentProps);
                runAcp(agentRuntime, agentProps);
                return;
            }

            if (AgentFlags.FLAG_WEB.equals(flag)) { // java -jar soloncode.jar web // soloncode web
                runWeb(agentRuntime, agentProps);
                return;
            }

            if (AgentFlags.FLAG_ACP.equals(flag)) { // java -jar soloncode.jar acp // soloncode acp
                runAcp(agentRuntime, agentProps);
                return;
            }

            //未来可以支持更多控制标记
        }


        //cli - default
        new Thread(new CliShellOld(agentRuntime, agentProps), "CLI-Interactive-Thread").start();
    }

    private static void runWeb(HarnessEngine agentRuntime, AgentProperties agentProps) {
        WebSocketRouter.getInstance().of(agentProps.getWsEndpoint(), new WebSocketGate(agentRuntime, agentProps));
        Solon.app().router().get(agentProps.getWebEndpoint(), new WebGate(agentRuntime, agentProps));
    }

    private static void runAcp(HarnessEngine agentRuntime, AgentProperties agentProps) {
        AcpAgentTransport agentTransport;
        if ("stdio".equals(agentProps.getAcpTransport())) {
            agentTransport = new StdioAcpAgentTransport();
        } else {
            agentTransport = new WebSocketSolonAcpAgentTransport(
                    agentProps.getAcpTransport(), McpJsonMapper.getDefault());
        }

        new AcpLink(agentRuntime, agentTransport, agentProps).run();
    }

    private static void initAgentProperties(SolonApp app) throws Exception {
        //加载配置文件
        AgentProperties c = new AgentProperties();
        URL configUrl = c.getConfigUrl();
        app.cfg().loadAdd(configUrl);

        //获取命令行运行的当前用户工作区
        String workspace = Paths.get(AgentProperties.getUserDir()).toAbsolutePath().normalize().toString();
        app.cfg().getProp("soloncode").bindTo(c);

        if (c.getChatModel() != null) {
            c.addModel(c.getChatModel());
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

        if (AgentFlags.FLAG_SERVE.equals(flag)) {
            enabledWeb(app, c);
            enabledAcp(app, c);
            return;
        }

        if (AgentFlags.FLAG_WEB.equals(flag)) {
            //开始控制台日志
            enabledWeb(app, c);
            return;
        }

        if (AgentFlags.FLAG_ACP.equals(flag)) {
            //开始控制台日志
            enabledAcp(app, c);
            return;
        }
    }

    private static void enabledWeb(SolonApp app, AgentProperties c) {
        app.enableHttp(true);
        app.enableWebSocket(true);

        //开始控制台日志
        app.cfg().setProperty("solon.logging.appender.console.level", "INFO");
        app.cfg().setProperty("solon.logging.appender.console.enable", "true");
    }

    private static void enabledAcp(SolonApp app, AgentProperties c) {
        //开始控制台日志
        if ("stdio".equals(c.getAcpTransport()) == false) {
            app.enableHttp(true);
            app.enableWebSocket(true);
        }
        app.cfg().setProperty("solon.logging.appender.console.level", "INFO");
        app.cfg().setProperty("solon.logging.appender.console.enable", "true");
    }
}