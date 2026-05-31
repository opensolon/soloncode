package org.noear.solon.codecli;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.skills.cli.PoolDir;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.codecli.command.builtin.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.memory.MemoryFactory;
import org.noear.solon.codecli.portal.*;
import org.noear.solon.codecli.portal.acp.AcpLink;
import org.noear.solon.codecli.portal.cli.CliShell;
import org.noear.solon.codecli.portal.desktop.WsController;
import org.noear.solon.codecli.portal.desktop.WsGate;
import org.noear.solon.codecli.portal.web.WebChannel;
import org.noear.solon.codecli.portal.web.WebController;
import org.noear.solon.codecli.portal.web.WebSettingsController;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.WebStreamBuilder;
import org.noear.solon.codecli.portal.desktop.provider.ModelProviderFactory;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.util.JavaUtil;
import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.websocket.WebSocketRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author noear 2026/4/18 created
 *
 */
@Configuration
public class Configurator {
    private static final Logger LOG = LoggerFactory.getLogger(Configurator.class);

    @Inject
    AppContext appContext;

    @Inject
    HarnessEngine agentRuntime;

    @Inject
    AgentProperties agentProps;

    @Inject
    AgentSettings agentSettings;

    @Inject
    ModelProviderFactory modelProviderFactory;

    private LoopScheduler loopScheduler;

    @Bean
    public HarnessEngine agentRuntime(AgentProperties props) throws Exception {
        //props.getMountPools().put("@global", Paths.get(props.getUserHome(), props.getHarnessSkills()).toString());
        //props.getMountPools().put("@local", Paths.get(props.getWorkspace(), props.getHarnessSkills()).toString());

        props.getAgentPools().add(Paths.get(props.getUserHome(), props.getHarnessAgents()).toString()); //global
        props.getAgentPools().add(Paths.get(props.getWorkspace(), props.getHarnessAgents()).toString()); //local


        //-----------------

        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        // 会话数据存到全局目录 ~/.soloncode/sessions/<sessionId>/
        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(props.getWorkspace(), props.getHarnessSessions()).resolve(key).normalize().toFile().toString()));

        HarnessEngine engine = HarnessEngine.of(props)
                .sessionProvider(sessionProvider)
                .memorySolution(new MemoryFactory(agentProps))
                .build();

        engine.getPoolManager().register(new PoolDir("@global-skills", true, "~/"+props.getHarnessSkills(), Paths.get(props.getUserHome(), props.getHarnessSkills())));
        engine.getPoolManager().register(new PoolDir("@workspace-skills", true, "./"+props.getHarnessSkills(), Paths.get(props.getWorkspace(), props.getHarnessSkills())));

        engine.getCommandRegistry().load(Paths.get(AgentProperties.getUserHome(), props.getHarnessCommands()));
        engine.getCommandRegistry().load(Paths.get(agentProps.getWorkspace(), props.getHarnessCommands()));

        engine.getCommandRegistry().register(new ExitCommand());
        engine.getCommandRegistry().register(new ClearCommand());
        engine.getCommandRegistry().register(new ResumeCommand());
        engine.getCommandRegistry().register(new RewindCommand());
        engine.getCommandRegistry().register(new ModelCommand());

        // loop scheduler
        this.loopScheduler = new LoopScheduler();
        engine.getCommandRegistry().register(new LoopCommand(loopScheduler));




        return engine;
    }

    @Init
    public void init() {
        //订阅容器扩展
        appContext.subBeansOfType(HarnessExtension.class, extension -> {
            agentRuntime.addExtension(extension);
        });


        CliShell cliShell = new CliShell(agentRuntime, agentProps, loopScheduler);
        String flag = Solon.cfg().argx().flagAt(0);

        if (AgentFlags.FLAG_VERSION.equals(flag)) {
            System.err.println(Solon.cfg().appTitle() + " " + AgentFlags.getVersion());
            return;
        }

        checkUpdate();

        //flag
        if (Solon.cfg().argx().flags().size() > 0) {
            if (AgentFlags.FLAG_RUN.equals(flag)) { // java -jar soloncode.jar run '你好' // soloncode run '你好'
                //单次任务态
                String prompt = Solon.cfg().argx().flagAt(1);
                new CliShell(agentRuntime, agentProps, null).call(prompt);
                Solon.stop();
                return;
            }

            if (AgentFlags.FLAG_SERVE.equals(flag)) { // java -jar soloncode.jar server // soloncode server
                runServe(agentRuntime, agentProps, cliShell);
                return;
            }

            if (AgentFlags.FLAG_WEB.equals(flag)) { // java -jar soloncode.jar web // soloncode web
                runWeb(agentRuntime, agentProps, cliShell);
                return;
            }

            if (AgentFlags.FLAG_ACP.equals(flag)) { // java -jar soloncode.jar acp // soloncode acp
                runAcp(agentRuntime, agentProps, cliShell);
                return;
            }

            //未来可以支持更多控制标记
        }

        //cli - default
        new Thread(cliShell, "CLI-Interactive-Thread").start();
    }

    private void checkUpdate() {
        if (AgentFlags.checkUpdate()) {
            // 使用颜色代码让提示更醒目
            System.err.println("\033[33mDiscover the new version: " + AgentFlags.getLastVersion() + "\033[0m");

            if (JavaUtil.IS_WINDOWS) {
                System.err.println("Update: \033[36mirm https://solon.noear.org/soloncode/setup.ps1 | iex\033[0m");
            } else {
                System.err.println("Update: \033[36mcurl -fsSL https://solon.noear.org/soloncode/setup.sh | bash\033[0m");
            }
            System.err.println();
        }
    }

    private void runServe(HarnessEngine agentRuntime, AgentProperties agentProps, CliShell cliShell) {
        //serve ws gate
        WebSocketRouter.getInstance().of(agentProps.getWsEndpoint(), new WsGate(agentRuntime, agentProps));

        //serve web controller
        BeanWrap webBean = Solon.context().wrapAndPut(WsController.class, new WsController(agentRuntime, modelProviderFactory));
        Solon.app().router().add(webBean);

        //注册第三方渠道（HTTP 端点 + 后台线程）
        WebGate webGate = new WebGate(agentRuntime, agentProps);
        WebStreamBuilder streamBuilder = new WebStreamBuilder(agentRuntime);
        WebChannel webChannel = new WebChannel(agentRuntime, webGate);
        // 将渠道绑定到 streamBuilder，使 IM 回复能同步
        for (Channel ch : Collections.singletonList(webChannel.getWeChatLink())) {
            streamBuilder.bind(ch);
        }
        streamBuilder.bind(webChannel.getFeishuLink());
        streamBuilder.bind(webChannel.getDingTalkLink());
        BeanWrap channelBean = Solon.context().wrapAndPut(WebChannel.class, webChannel);
        Solon.app().router().add(channelBean);
        RunUtil.async((Runnable) webChannel);

        cliShell.printWelcome("Server port: " + Solon.cfg().serverPort());
    }


    private void runWeb(HarnessEngine agentRuntime, AgentProperties agentProps, CliShell cliShell) {
        //web ws gate
        WebGate webGate = new WebGate(agentRuntime, agentProps);
        WebSocketRouter.getInstance().of("/web/gate", webGate);

        //web
        BeanWrap webController = Solon.context().wrapAndPut(WebController.class, new WebController(agentRuntime, webGate, loopScheduler));
        Solon.app().router().add(webController);

        WebSettingsController settingsController = new WebSettingsController(agentRuntime, agentSettings);
        BeanWrap webSettingsController = Solon.context().wrapAndPut(WebSettingsController.class, settingsController);
        Solon.app().router().add(webSettingsController);

        BeanWrap webChannel = Solon.context().wrapAndPut(WebChannel.class, new WebChannel(agentRuntime, webGate));
        Solon.app().router().add(webChannel);

        // 启动微信通道
        RunUtil.async((Runnable) webChannel.get());

        // 启动工作区文件变化监听
        try {
            Path workspacePath = Paths.get(agentProps.getWorkspace()).toAbsolutePath().normalize();
            WorkspaceWatcher workspaceWatcher = new WorkspaceWatcher(workspacePath);
            workspaceWatcher.addBroadcastHandler(webGate::broadcastRaw);
            workspaceWatcher.start();
        } catch (Exception e) {
            // watcher 启动失败不影响主流程
        }

        if (cliShell == null) {
            return;
        }

        RunUtil.async(() -> {
            try {
                Thread.sleep(500);

                String url = "http://localhost:" + Solon.cfg().serverPort() + "/";

                if (JavaUtil.IS_WINDOWS) {
                    new ProcessBuilder("cmd", "/c", "start", url.replace("&", "^&")).start();
                } else if (JavaUtil.IS_MAC) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }

                if (cliShell != null) {
                    cliShell.printWelcome("Web interface: " + url);
                }
            } catch (Throwable e) { // 使用 Throwable 捕获更全面
                LOG.warn("Failed to open browser: {}", e.getMessage());
            }
        });
    }


    private void runAcp(HarnessEngine agentRuntime, AgentProperties agentProps, CliShell cliShell) {
        AcpAgentTransport agentTransport;
        if ("stdio".equals(agentProps.getAcpTransport())) {
            agentTransport = new StdioAcpAgentTransport();
        } else {
            agentTransport = new WebSocketSolonAcpAgentTransport(
                    agentProps.getAcpTransport(), McpJsonDefaults.getMapper());
        }

        new AcpLink(agentRuntime, agentTransport, agentProps).run();

        if (cliShell == null) {
            return;
        }

        if ("stdio".equals(agentProps.getAcpTransport())) {
            //不能有打印
            cliShell.printWelcome("Acp interface: stdio");
        } else {
            String url = "ws://localhost:" + Solon.cfg().serverPort() + "/acp";
            cliShell.printWelcome("Acp interface: " + url);
        }
    }
}