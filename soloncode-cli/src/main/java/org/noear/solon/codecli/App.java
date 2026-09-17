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
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.GeneralGroupDo;
import org.noear.solon.core.util.Assert;
import org.noear.solon.codecli.util.LogDirUtil;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.codecli.workspace.WorkspaceStartupMigrator;
import org.noear.solon.codecli.workspace.WorkspaceStorageReport;
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
        // 0. 存储目录启动归一化（必须最早：删临时测试目录 + v1 改名 ws-，须在日志 WS_KEY 设置前完成，
        //    否则日志 appender 会按旧 v1 key 重建出已改名的空目录）
        WorkspaceStartupMigrator.migrateIfNeeded();

        // 1. 移除 JUL 默认的控制台处理器
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        // 2. 添加 SLF4J 处理器
        SLF4JBridgeHandler.install();

        //配置用户扩展目录
        System.setProperty(LogDirUtil.WS_KEY, LogDirUtil.workspaceKey());

        System.setProperty("solon.extend", "!" + AgentFlags.getUserPlugins());

        Solon.start(App.class, args, app -> {
            initAgentProperties(app);
        });

        //日志体系已初始化（logback 配置加载完成）：把全局 file appender 替换为按工作区分流的路由器
        WorkspaceLogRouter.install();
        //Reactor 调度器 MDC 传播：agent 管道跳线程不丢工作区日志标记（需在任何调度发生前）
        WorkspaceLogRouter.installMdcPropagation();

        //工作区存储目录观测报告（阶段一：只统计 v1/v2/缺元数据/双布局分布，不迁移不删除）
        WorkspaceStorageReport.reportIfNeeded();
    }

    private static void initAgentProperties(SolonApp app) throws Exception {
        //加载配置文件

        URL configUrl = AgentFlags.getConfigUrl();

        app.cfg().loadAdd(configUrl);

        initAgentSettings(app);

        //推入容器
        //app.context().wrapAndPut(AgentProperties.class, c);

        //-----

        app.enableHttp(false); //默认不启用 http

        String flag = app.cfg().argx().flagAt(0);

        if (AgentFlags.FLAG_SERVE.equals(flag)) {
            enabledWeb(app);
            return;
        }

        if (AgentFlags.FLAG_WEB.equals(flag)) {
            //开始控制台日志
            enabledWeb(app);
            return;
        }

        if (AgentFlags.FLAG_ACP.equals(flag)) {
            //开始控制台日志
            enabledAcp(app);
            return;
        }
    }

    private static void initAgentSettings(SolonApp app) throws Exception {

        AgentSettings agentSettings = AgentSettings.loadFromFile();

        //与 AgentProperties 双向合并
        agentSettings.mergeFrom();

        //将 settings.json 中的日志配置同步到 Solon.cfg()，使重启后日志系统真正读取到用户保存的值
        syncLogPropertiesToCfg(agentSettings.getGeneral());

        app.context().wrapAndPut(AgentSettings.class, agentSettings);
    }

    /**
     * 将用户保存的日志配置（来自 settings.json）写入 Solon.cfg()，
     * 并让 logLevel 立即生效。
     */
    private static void syncLogPropertiesToCfg(GeneralGroupDo general) {
        try {
            //写入 Solon.cfg()，以便 Solon 日志模块后续读取
            if (general.getLogLevel() != null) {
                Solon.cfg().setProperty("solon.logging.appender.file.level", general.getLogLevel());
            }

            if (general.getLogFileMaxSize() != null) {
                Solon.cfg().setProperty("solon.logging.appender.file.maxFileSize", general.getLogFileMaxSize());
            }

            if (general.getLogMaxHistory() != null) {
                Solon.cfg().setProperty("solon.logging.appender.file.maxHistory",
                        String.valueOf(general.getLogMaxHistory()));
            }
        } catch (Exception e) {
            //非 Solon 环境或日志系统未就绪时静默跳过，不影响启动
        }
    }

    private static void enabledWeb(SolonApp app) {
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

    private static void enabledAcp(SolonApp app) {
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
