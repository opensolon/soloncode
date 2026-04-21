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
import org.noear.solon.codecli.core.AgentFlags;
import org.noear.solon.codecli.core.AgentProperties;
import org.noear.solon.core.util.Assert;

import java.net.URL;
import java.nio.file.Paths;

/**
 * Cli 应用
 *
 * @author noear
 * @since 3.9.1
 */
public class App {

    public static void main(String[] args) {
        AgentProperties agentProps = new AgentProperties();

        //配置用户扩展目录
       System.setProperty("solon.extend", "!" + agentProps.getUserExtensions());

        Solon.start(App.class, args, app -> {
            initAgentProperties(app, agentProps);
        });
    }

    private static void initAgentProperties(SolonApp app, AgentProperties c) throws Exception {
        //加载配置文件

        URL configUrl = c.getConfigUrl();

        app.cfg().loadAdd(configUrl);

        //获取命令行运行的当前用户工作区
        String workspace = Paths.get(AgentProperties.getUserDir()).toAbsolutePath().normalize().toString();
        app.cfg().getProp("soloncode").bindTo(c);

        //兼容旧的模型配置
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

        //设定系统提示词
        c.setSystemPrompt(c.getAgentsMd());

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
        String port = app.cfg().argx().flagAt(1);

        if ("0".equals(port)) {
            port = findAvailablePort();
        }

        if (Assert.isNotEmpty(port) && Assert.isNumber(port)) {
            // soloncode web 1212 //= soloncode web -server.port=1212
            app.cfg().setProperty("server.port", port);
        }

        app.enableHttp(true);
        app.enableWebSocket(true);
    }

    private static void enabledAcp(SolonApp app, AgentProperties c) {
        //开始控制台日志
        if ("stdio".equals(c.getAcpTransport()) == false) {
            app.enableHttp(true);
            app.enableWebSocket(true);
        }
    }

    private static String findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return String.valueOf(socket.getLocalPort());
        } catch (Throwable e) {
            // 如果分配失败，返回一个保底的默认端口
            return null;
        }
    }
}