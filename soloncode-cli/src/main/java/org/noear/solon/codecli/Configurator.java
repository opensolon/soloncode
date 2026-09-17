package org.noear.solon.codecli;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import org.noear.solon.Solon;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.portal.*;
import org.noear.solon.codecli.portal.acp.AcpLink;
import org.noear.solon.codecli.portal.cli.CliShell;
import org.noear.solon.codecli.portal.help.HelpMode;
import org.noear.solon.codecli.portal.desktop.WsController;
import org.noear.solon.codecli.portal.printmode.PrintMode;
import org.noear.solon.codecli.portal.printmode.PrintModeOptions;
import org.noear.solon.codecli.portal.printmode.StreamMode;
import org.noear.solon.codecli.portal.desktop.WsGate;
import org.noear.solon.codecli.portal.web.WebChannel;
import org.noear.solon.codecli.portal.web.WebController;
import org.noear.solon.codecli.portal.web.MemoryController;
import org.noear.solon.codecli.portal.web.run.RunController;
import org.noear.solon.codecli.portal.web.WebSettingsController;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.auth.*;
import org.noear.solon.codecli.portal.web.settings.*;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.codecli.util.OsOpenUtil;
import org.noear.solon.core.util.JavaUtil;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.websocket.WebSocketRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author noear 2026/4/18 created
 *
 */
@Configuration
public class Configurator {
    private static final Logger LOG = LoggerFactory.getLogger(Configurator.class);

    @Inject
    HarnessEngine agentRuntime;

    @Inject
    AgentSettings agentSettings;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    SessionManager sessionManager;

    private LoopScheduler loopScheduler;

    @Bean
    public WorkspaceManager workspaceManager(AgentSettings settings) {
        return new WorkspaceManager(settings);
    }

    @Bean
    public SessionManager sessionManager() {
        return new SessionManager();
    }

    @Bean
    public HarnessEngine agentRuntime(AgentSettings settings, SessionManager sessionManager, WorkspaceManager workspaceManager) {
        // 复用默认工作区 engine：引擎构建逻辑已收敛到 WorkspaceManager.createWorkspaceContext，
        // 禁止再维护第二份 200 行构建代码（同一 user.dir 双 engine 会导致内存/MCP/LSP 子进程翻倍）。
        // 语义对齐：默认工作区 wsSettings 即注入的 settings；SessionManager(workspace) 与无参构造
        // 均指向 AgentFlags.getUserDir()；命令注册/LoopScheduler/GoalExtension 完全一致。
        workspaceManager.initDefaultWorkspace();
        WorkspaceContext defaultCtx = workspaceManager.getOrCreate(null);
        this.loopScheduler = defaultCtx.getLoopScheduler();
        return defaultCtx.getEngine();
    }

    @Init
    public void init() {
        // 初始化默认工作区（幂等；agentRuntime bean 创建时可能已初始化）
        if (workspaceManager != null) {
            workspaceManager.initDefaultWorkspace();
        }

        // 容器扩展订阅已由 WorkspaceManager.createWorkspaceContext 统一完成
        //（agentRuntime 即默认工作区 engine），此处禁止重复订阅，否则同一扩展被注册两遍

        CliShell cliShell = new CliShell(agentRuntime, agentSettings, loopScheduler);
        String flag = Solon.cfg().argx().flagAt(0);

        // help 优先于其它一切分支：不做更新检查，保证帮助输出可被工具链解析
        if (HelpMode.isHelpRequest(Solon.cfg().argx())) {
            HelpMode helpMode = new HelpMode(HelpMode.resolveTopic(Solon.cfg().argx()), AgentFlags.getVersion());
            haltWith(helpMode.execute());
            return;
        }

        if (AgentFlags.FLAG_VERSION.equals(flag)) {
            System.out.println(Solon.cfg().appTitle() + " " + AgentFlags.getVersion());
            haltWith(0);  // 退出进程（退出码 0）
            return;
        }

        // stream 的 stdout 是严格 JSONL 协议通道，不能夹入版本更新文本。
        // 常驻 SDK 通道也不应在启动时做联网更新检查。
        if (!AgentFlags.FLAG_STREAM.equals(flag)) {
            checkUpdate();
        }

        //flag
        if (Solon.cfg().argx().flags().size() > 0) {
            if (AgentFlags.FLAG_RUN.equals(flag)) { // soloncode run 'prompt' --output-format json --model sonnet --max-turns 10
                // Print / Headless 模式（对齐 claude -p）
                PrintModeOptions printOpts = PrintModeOptions.parse(Solon.cfg().argx());
                PrintMode printMode = new PrintMode(agentRuntime, agentSettings, printOpts);
                int exitCode = printMode.execute();
                haltWith(exitCode);
                return;
            }

            if (AgentFlags.FLAG_STREAM.equals(flag)) { // soloncode stream --verbose  （stdin 为 JSONL 消息流，进程常驻）
                // Stream / 常驻无头模式（对齐 claude -p --input-format stream-json）
                // 与 run 分开暴露：run 永远单次，stream 永远常驻，同一子命令不存在两种生命周期
                PrintModeOptions streamOpts = PrintModeOptions.parseStream(Solon.cfg().argx());
                StreamMode streamMode = new StreamMode(agentRuntime, agentSettings, streamOpts);
                haltWith(streamMode.execute());
                return;
            }

            if (AgentFlags.FLAG_SERVE.equals(flag)) { // java -jar soloncode.jar server // soloncode server
                runDesktopServe(agentRuntime, agentSettings, cliShell, sessionManager);
                runWebServe(agentRuntime, agentSettings, null, sessionManager);
                return;
            }

            if (AgentFlags.FLAG_WEB.equals(flag)) { // java -jar soloncode.jar web // soloncode web
                runWebServe(agentRuntime, agentSettings, cliShell, sessionManager);
                openBrowser();
                return;
            }

            if (AgentFlags.FLAG_ACP.equals(flag)) { // java -jar soloncode.jar acp // soloncode acp
                runAcp(agentRuntime, agentSettings, cliShell);
                return;
            }

            //未来可以支持更多控制标记
        }

        //cli - default
        new Thread(cliShell, "CLI-Interactive-Thread").start();
    }

    /**
     * 以指定退出码结束进程（先优雅停止容器，再强制退出）。
     *
     * <p>注意：不能用 {@code Solon.stop()}，它内部固定 {@code System.exit(1)}，
     * 会让 {@code soloncode run} / {@code --version} 即便成功也返回退出码 1，
     * 破坏 CI 与 SDK 对退出码语义的依赖。</p>
     *
     * @param exitCode 业务退出码（0=成功）
     */
    private void haltWith(int exitCode) {
        try {
            Solon.stopBlock(false, 0);  // 优雅停止，但不由 Solon 决定退出码
        } catch (Throwable e) {
            // 停止过程异常不应改变业务退出码
        }
        Runtime.getRuntime().halt(exitCode);
    }

    private void checkUpdate() {
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
    }

    private void runDesktopServe(HarnessEngine agentRuntime, AgentSettings settings, CliShell cliShell,
                                 SessionManager sessionManager) {
        //serve ws gate
        WsGate wsGate = new WsGate(agentRuntime, settings, loopScheduler);
        WebSocketRouter.getInstance().of("/desktop/ws", wsGate);

        //serve desktop controller
        BeanWrap desktopBean = Solon.context().wrapAndPut(WsController.class,
                new WsController(agentRuntime, settings, wsGate, loopScheduler, sessionManager));
        Solon.app().router().add(desktopBean);

        cliShell.printWelcome("Server port: " + Solon.cfg().serverPort());
    }


    private void runWebServe(HarnessEngine agentRuntime, AgentSettings settings, CliShell cliShell, SessionManager sessionManager) {
        //web ws gate
        // 入口单例 WebGate：仅作 WS 路由入口（onOpen 按 workspaceId 分发）与默认工作区 FileWatch 广播。
        // 其连接池与默认工作区上下文共享同一引用，保证默认工作区推送一致。
        WorkspaceContext defaultCtx = workspaceManager.getOrCreate(null);
        WebGate webGate = new WebGate(workspaceManager);
        workspaceManager.setWebGate(webGate);
        WebSocketRouter.getInstance().of("/web/gate", webGate);

        // 复用默认工作区上下文中已创建并启动的 FileWatchService：
        // 它在 WorkspaceManager.createWorkspaceContext 中已 addRoot("workspace"+挂载点) 并 start()，
        // 广播走 Context 内部 WebGate.broadcastRaw（与入口单例共享同一默认连接池）。
        // 此处不再新建/重复监听同一目录，避免默认工作区文件变更向前端重复推送。
        FileWatchService fileWatchService = defaultCtx.getFileWatchService();
        
        // 用户认证系统（先初始化，确保 WebController 等组件可以访问）
        UserAuthConfig userAuthConfig = agentSettings.getUserAuth();
        UserSessionManager userSessionManager = new UserSessionManager();
        userSessionManager.init(userAuthConfig);
        
        UserStore userStore;
        try {
            userStore = createUserStore(userAuthConfig, userSessionManager);
        } catch (Exception e) {
            LOG.warn("[Configurator] Failed to create user store, using file store: {}", e.getMessage());
            userStore = new FileUserStore();
            try { userStore.init(userAuthConfig); } catch (Exception ignored) {}
        }
        
        // 注册 userStore 和 userSessionManager 到容器
        Solon.context().wrapAndPut(UserStore.class, userStore);
        Solon.context().wrapAndPut(UserSessionManager.class, userSessionManager);
        Solon.context().wrapAndPut(UserAuthConfig.class, userAuthConfig);

        //web
        BeanWrap webController = Solon.context().wrapAndPut(WebController.class, new WebController(workspaceManager));
        Solon.app().router().add(webController);

        addWebBean(new WebSettingsController(workspaceManager));
        addWebBean(new AgentSettingsController(workspaceManager));
        addWebBean(new MountSettingsController(workspaceManager));
        addWebBean(new SkillSettingsController(workspaceManager));
        addWebBean(new LlmSettingController(workspaceManager));

        addWebBean(new McpSettingsController(workspaceManager));
        addWebBean(new OpenapiSettingsController(workspaceManager));
        addWebBean(new LspSettingsController(workspaceManager));
        addWebBean(new ProfileSettingsController(workspaceManager));

        addWebBean(new MemoryController(agentRuntime));
        
        // /web/run：soloncode run 的 HTTP/SSE 远程执行入口（Bearer token 鉴权，子进程隔离执行）
        addWebBean(new RunController(workspaceManager));
        
        addWebBean(new UserLoginController(userStore, userSessionManager, userAuthConfig));
        addWebBean(new UserAuthController(userStore, userSessionManager, userAuthConfig, agentSettings));

        BeanWrap webChannel = Solon.context().wrapAndPut(WebChannel.class, new WebChannel(workspaceManager));
        Solon.app().router().add(webChannel);

        // IM 渠道长连接（微信/飞书/钉钉）已改由各工作区的 ChannelHub.run() 统一拉起
        // （见 WorkspaceManager.createWorkspaceContext），此处不再启动。

        // 挂载点监听已由默认工作区上下文（WorkspaceManager.createWorkspaceContext）统一装配并 start，
        // 此处不再重复遗历挂载点与 start，避免重复监听与重复推送。
        if (cliShell != null) {
            String url = "http://localhost:" + Solon.cfg().serverPort() + "/";
            cliShell.printWelcome("Web interface: " + url);
        }
    }

        private UserStore createUserStore(UserAuthConfig config, UserSessionManager sessionManager) throws Exception {
        String mode = config.getMode();
        if (mode == null) mode = "file";
        
        UserStore store;
        switch (mode) {
            case "ldap":
                store = new LdapUserStore();
                break;
            case "database":
                // 数据库模式目前使用文件存储作为兜底
                // 实际使用时可通过 UI 配置 JDBC 连接
                store = new FileUserStore();
                break;
            case "file":
            default:
                store = new FileUserStore();
                break;
        }
        store.init(config);
        return store;
    }
    
    private void addWebBean(Object bean) {
        BeanWrap beanWrap = Solon.context().wrapAndPut(bean.getClass(), bean);
        Solon.app().router().add(beanWrap);
    }

    private void openBrowser() {
        String url = "http://localhost:" + Solon.cfg().serverPort() + "/";

        RunUtil.async(() -> {
            try {
                Thread.sleep(500);

                OsOpenUtil.openBrowser(url);
            } catch (Throwable e) { // 使用 Throwable 捕获更全面
                LOG.warn("Failed to open browser: {}", e.getMessage());
            }
        });
    }


    private void runAcp(HarnessEngine agentRuntime, AgentSettings settings, CliShell cliShell) {
        AcpAgentTransport agentTransport = new StdioAcpAgentTransport();

        new AcpLink(agentRuntime, agentTransport, settings).run();

//        if (cliShell == null) {
//            return;
//        }

        //不能有打印
        //cliShell.printWelcome("Acp interface: stdio");
    }
}