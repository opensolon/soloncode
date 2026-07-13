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
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.util.Assert;
import org.noear.solon.scheduling.annotation.EnableScheduling;
import org.noear.solon.web.cors.CrossFilter;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.net.URL;

/**
 * Cli 应用
 *
 * @author noear
 * @since 3.9.1
 */
@EnableScheduling
public class App {

    public static void main(String[] args) {
        // 1. 移除 JUL 默认的控制台处理器
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        // 2. 添加 SLF4J 处理器
        SLF4JBridgeHandler.install();

        AgentProperties agentProps = new AgentProperties();

        //配置用户扩展目录
        System.setProperty("solon.extend", "!" + AgentFlags.getUserExtensions());

        Solon.start(App.class, args, app -> {
            initAgentProperties(app, agentProps);
        });
    }

    private static void initAgentProperties(SolonApp app, AgentProperties c) throws Exception {
        //加载配置文件

        URL configUrl = AgentFlags.getConfigUrl();

        app.cfg().loadAdd(configUrl);

        //获取命令行运行的当前用户工作区
        app.cfg().getProp("soloncode").bindTo(c);

        //兼容旧的模型配置
        if (c.getChatModel() != null) {
            c.getModels().add(c.getChatModel());
        }

        initAgentSettings(app, c);

        //推入容器
        //app.context().wrapAndPut(AgentProperties.class, c);

        //-----

        app.enableHttp(false); //默认不启用 http

        String flag = app.cfg().argx().flagAt(0);

        if (AgentFlags.FLAG_SERVE.equals(flag)) {
            enabledWeb(app, c);
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

    private static void initAgentSettings(SolonApp app, AgentProperties props) throws Exception {

        AgentSettings agentSettings = AgentSettings.loadFromFile();

        //与 AgentProperties 双向合并
        agentSettings.mergeFrom(props);

        app.context().wrapAndPut(AgentSettings.class, agentSettings);
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
        // 允许跨域（桌面端前端通过 localhost 访问 CLI 后端）
        app.router().filter(new CrossFilter());
    }

    private static void enabledAcp(SolonApp app, AgentProperties c) {
        //开始控制台日志(web 通讯关闭)
        app.enableHttp(false);
        app.enableWebSocket(false);
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
