package org.noear.solon.codecli;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.codecli.command.builtin.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.ManagerExtension;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.config.entity.MountDo;
import org.noear.solon.codecli.memory.MemoryProvider;
import org.noear.solon.codecli.portal.*;
import org.noear.solon.codecli.portal.acp.AcpLink;
import org.noear.solon.codecli.portal.cli.CliShell;
import org.noear.solon.codecli.portal.desktop.WsController;
import org.noear.solon.codecli.portal.desktop.WsGate;
import org.noear.solon.codecli.portal.web.WebChannel;
import org.noear.solon.codecli.portal.web.WebController;
import org.noear.solon.codecli.portal.web.WebSettingsController;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.BeanWrap;
import org.noear.solon.core.util.JavaUtil;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.websocket.WebSocketRouter;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
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
    AgentSettings agentSettings;

    @Inject
    SessionManager sessionManager;

    @Inject
    ModelsAdapterManager modelProviderFactory;

    private LoopScheduler loopScheduler;

    @Bean
    public SessionManager sessionManager() {
        return new SessionManager();
    }

    @Bean
    public HarnessEngine agentRuntime(AgentSettings settings, SessionManager sessionManager) throws Exception {
        String stealthIdentity = "<!--\n" +
                "  @poweredby: soloncode\n" +
                "  @build: " + AgentFlags.getVersion() + "\n" +
                "-->\n\n";

        String workspace = AgentFlags.getUserDir();

        HarnessEngine engine = HarnessEngine.of(workspace, AgentFlags.getHarnessHome())
                .userAgent(settings.getGeneral().getUserAgent())
                .systemPrompt(stealthIdentity + AgentFlags.getAgentsMd())
                .maxTurns(settings.getGeneral().getMaxTurns())
                .autoRethink(settings.getGeneral().getAutoRethink())
                .sessionWindowSize(settings.getGeneral().getSessionWindowSize())
                .sessionProvider(sessionManager)
                .compressionThreshold(settings.getGeneral().getSummaryWindowSize(), settings.getGeneral().getSummaryWindowToken())
                .compressionModel(settings.getGeneral().getSummaryModel())
                .memoryEnabled(settings.getGeneral().getMemoryEnabled())
                .memoryProvider(new MemoryProvider(agentSettings))
                .sandboxEnabled(settings.getGeneral().getSandboxMode())
                .sandboxAllowUserHome(settings.getGeneral().getSandboxAllowUserHome())
                .sandboxSystemRestrict(settings.getGeneral().getSandboxSystemRestrict())
                .bashAsyncEnabled(settings.getGeneral().getBashAsyncEnabled())
                .subagentEnabled(settings.getGeneral().getSubagentEnabled())
                .hitlEnabled(settings.getGeneral().getHitlEnabled())
                .apiRetries(settings.getGeneral().getApiRetries())
                .modelRetries(settings.getGeneral().getModelRetries())
                .mcpRetries(settings.getGeneral().getModelRetries())
                .toolsAdd(settings.getPermission().getTools())
                .disallowedToolsAdd(settings.getPermission().getDisallowedTools())
                .cacheControl(CacheControl.ofEphemeral("30m"))
                .build();


        engine.setDefaultModel(settings.getDefaultModel());
        for (ModelDo model : agentSettings.getModels().values()) {
            engine.addModel(model);
        }

        for (Map.Entry<String, MountDo> entry : agentSettings.getMountPools().entrySet()) {
            MountDo mount = entry.getValue();
            engine.addMount(MountDir.builder()
                    .alias(entry.getKey())
                    .description(mount.getDescription())
                    .type(mount.getType())
                    .path(mount.getPath())
                    .primary(mount.isPrimary())
                    .enabled(mount.isEnabled())
                    .writeable(mount.isWriteable())
                    .build());
        }

        engine.addMount(MountDir.builder().alias("@user-skills").type(MountType.SKILLS).path("~/" + engine.getHarnessSkills()).primary(true).build());
        engine.addMount(MountDir.builder().alias("@workspace-skills").type(MountType.SKILLS).path("./" + engine.getHarnessSkills()).primary(true).build());

        engine.addMount(MountDir.builder().alias("@user-agents").type(MountType.AGENTS).path("~/" + engine.getHarnessAgents()).primary(true).build());
        engine.addMount(MountDir.builder().alias("@workspace-agents").type(MountType.AGENTS).path("./" + engine.getHarnessAgents()).primary(true).build());


        engine.getCommandRegistry().load(Paths.get(AgentFlags.getUserHome(), engine.getHarnessCommands()));
        engine.getCommandRegistry().load(Paths.get(workspace, engine.getHarnessCommands()));

        engine.getCommandRegistry().register(new ExitCommand());
        engine.getCommandRegistry().register(new ClearCommand());
        engine.getCommandRegistry().register(new ContinueCommand());
        engine.getCommandRegistry().register(new InterruptCommand());
        engine.getCommandRegistry().register(new RerunCommand());
        engine.getCommandRegistry().register(new RewindCommand());
        engine.getCommandRegistry().register(new ModelCommand());

        engine.getLspTalent().setEnabled(settings.getGeneral().getLspEnabled());

        RunUtil.async(() -> addServers(engine));

        // loop scheduler
        this.loopScheduler = new LoopScheduler(engine, agentSettings);

        // ★ 初始化 Goal 验证器（在 LoopScheduler 创建之后，GoalExtension 注册之前）
        ValidatorFactory.initDefaults(workspace);

        // ★ Goal 模式（受 feature flag 控制）
        boolean goalsEnabled = settings.getGeneral().getGoalsEnabled() != null
                ? settings.getGeneral().getGoalsEnabled() : true;
        GoalExtension goalExtension = new GoalExtension(loopScheduler);
        goalExtension.getGoalTalent().setEnabled(goalsEnabled);
        engine.addExtension(goalExtension);

        // LoopCommand 统一管理循环任务与 Goal（pause/resume 需 GoalTool 同步 sessionId）
        engine.getCommandRegistry().register(
                new LoopCommand(loopScheduler));

        engine.addExtension(new ManagerExtension(engine, agentSettings, loopScheduler));

        return engine;
    }

    private void addServers(HarnessEngine engine) {
        for (Map.Entry<String, McpServerDo> entry : agentSettings.getMcpServers().entrySet()) {
            engine.addMcpServer(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, ApiSourceDo> entry : agentSettings.getApiServers().entrySet()) {
            engine.addApiServer(entry.getValue());
        }

        for (Map.Entry<String, LspServerDo> entry : agentSettings.getLspServers().entrySet()) {
            engine.addLspServer(entry.getKey(), entry.getValue());
        }

        //系统级 LSP 服务器（参考 OpenCode / Claude Code 内置列表，仅注册常见语言）
        addSystemLspServer(engine, agentSettings, "java", Arrays.asList("jdtls"), Arrays.asList(".java"));
        addSystemLspServer(engine, agentSettings, "typescript", Arrays.asList("typescript-language-server", "--stdio"), Arrays.asList(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts"));
        addSystemLspServer(engine, agentSettings, "go", Arrays.asList("gopls"), Arrays.asList(".go"));
        addSystemLspServer(engine, agentSettings, "python", Arrays.asList("pyright-langserver", "--stdio"), Arrays.asList(".py", ".pyi"));
        addSystemLspServer(engine, agentSettings, "rust", Arrays.asList("rust-analyzer"), Arrays.asList(".rs"));
        addSystemLspServer(engine, agentSettings, "c-cpp", Arrays.asList("clangd", "--background-index", "--clang-tidy"), Arrays.asList(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx", ".hxx", ".c++", ".h++", ".hh"));
        addSystemLspServer(engine, agentSettings, "csharp", Arrays.asList("roslyn-language-server", "--stdio", "--autoLoadProjects"), Arrays.asList(".cs", ".csx"));
        addSystemLspServer(engine, agentSettings, "ruby", Arrays.asList("solargraph", "stdio"), Arrays.asList(".rb", ".rake", ".gemspec", ".ru"));
        addSystemLspServer(engine, agentSettings, "php", Arrays.asList("intelephense", "--stdio"), Arrays.asList(".php"));
        addSystemLspServer(engine, agentSettings, "bash", Arrays.asList("bash-language-server", "start"), Arrays.asList(".sh", ".bash", ".zsh", ".ksh"));
        addSystemLspServer(engine, agentSettings, "lua", Arrays.asList("lua-language-server"), Arrays.asList(".lua"));
        addSystemLspServer(engine, agentSettings, "dart", Arrays.asList("dart", "language-server", "--lsp"), Arrays.asList(".dart"));
        addSystemLspServer(engine, agentSettings, "swift", Arrays.asList("sourcekit-lsp"), Arrays.asList(".swift", ".objc", ".objcpp"));
        addSystemLspServer(engine, agentSettings, "kotlin", Arrays.asList("kotlin-language-server"), Arrays.asList(".kt", ".kts"));
        addSystemLspServer(engine, agentSettings, "yaml", Arrays.asList("yaml-language-server", "--stdio"), Arrays.asList(".yaml", ".yml"));

    }

    @Init
    public void init() {
        //订阅容器扩展
        appContext.subBeansOfType(HarnessExtension.class, extension -> {
            agentRuntime.addExtension(extension);
        });


        CliShell cliShell = new CliShell(agentRuntime, agentSettings, loopScheduler);
        String flag = Solon.cfg().argx().flagAt(0);

        if (AgentFlags.FLAG_VERSION.equals(flag)) {
            System.out.println(Solon.cfg().appTitle() + " " + AgentFlags.getVersion());
            Solon.stop();  // 退出进程
            return;
        }

        checkUpdate();

        //flag
        if (Solon.cfg().argx().flags().size() > 0) {
            if (AgentFlags.FLAG_RUN.equals(flag)) { // java -jar soloncode.jar run '你好' // soloncode run '你好'
                //单次任务态
                String prompt = Solon.cfg().argx().flagAt(1);
                new CliShell(agentRuntime, agentSettings, null).call(prompt);
                Solon.stop();
                return;
            }

            if (AgentFlags.FLAG_SERVE.equals(flag)) { // java -jar soloncode.jar server // soloncode server
                runDesktopServe(agentRuntime, agentSettings, cliShell);
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

    private void runDesktopServe(HarnessEngine agentRuntime, AgentSettings settings, CliShell cliShell) {
        //serve ws gate
        WebSocketRouter.getInstance().of("/desktop/ws", new WsGate(agentRuntime, settings));

        //serve desktop controller
        BeanWrap desktopBean = Solon.context().wrapAndPut(WsController.class, new WsController(agentRuntime, settings, modelProviderFactory));
        Solon.app().router().add(desktopBean);

        cliShell.printWelcome("Server port: " + Solon.cfg().serverPort());
    }


    private void runWebServe(HarnessEngine agentRuntime, AgentSettings settings, CliShell cliShell, SessionManager sessionManager) {
        //web ws gate
        WebGate webGate = new WebGate(agentRuntime, settings);
        WebSocketRouter.getInstance().of("/web/gate", webGate);

        // 初始化文件监听服务（提前创建，以便 WebSettingsController 引用）
        Path workspacePath = Paths.get(agentRuntime.getWorkspace()).toAbsolutePath().normalize();
        FileWatchService fileWatchService = new FileWatchService();
        // 默认工作区 → 前端广播
        fileWatchService.addRoot("workspace", workspacePath)
                .addHandler(changes -> webGate.broadcastRaw(FileWatchService.buildFrontendJson(changes)));

        //web
        BeanWrap webController = Solon.context().wrapAndPut(WebController.class, new WebController(agentRuntime, webGate, loopScheduler, sessionManager));
        Solon.app().router().add(webController);

        WebSettingsController settingsController = new WebSettingsController(agentRuntime, settings);
        settingsController.setFileWatchService(fileWatchService);
        settingsController.setWebGate(webGate);
        BeanWrap webSettingsController = Solon.context().wrapAndPut(WebSettingsController.class, settingsController);
        Solon.app().router().add(webSettingsController);

        BeanWrap webChannel = Solon.context().wrapAndPut(WebChannel.class, new WebChannel(agentRuntime, webGate));
        Solon.app().router().add(webChannel);

        // 启动微信通道
        RunUtil.async((Runnable) webChannel.get());

        // 遍历所有挂载点，按类型分配不同的处理器
        for (MountDir mount : agentRuntime.getMounts()) {
            if (!mount.isEnabled()) continue;

            FileWatchService.WatchRoot root = fileWatchService.addRoot(mount.getAlias(), mount.getRealPath());

            switch (mount.getType()) {
                case FILES:
                    // FILES 挂载 → 前端广播
                    root.addHandler(changes -> webGate.broadcastRaw(FileWatchService.buildFrontendJson(changes)));
                    break;
                case SKILLS:
                    // SKILLS 挂载 → 触发技能刷新
                    root.addHandler(changes -> agentRuntime.getSkillProvider().refreshByGroup(mount.getAlias()));
                    break;
                case AGENTS:
                    // AGENTS 挂载 → 触发代理刷新
                    root.addHandler(changes -> agentRuntime.getAgentManager().refreshByMountAlias(mount.getAlias()));
                    break;
            }
        }

        fileWatchService.start();

        if (cliShell != null) {
            String url = "http://localhost:" + Solon.cfg().serverPort() + "/";
            cliShell.printWelcome("Web interface: " + url);
        }
    }

    private void openBrowser() {
        String url = "http://localhost:" + Solon.cfg().serverPort() + "/";

        RunUtil.async(() -> {
            try {
                Thread.sleep(500);

                if (JavaUtil.IS_WINDOWS) {
                    new ProcessBuilder("cmd", "/c", "start", url.replace("&", "^&")).start();
                } else if (JavaUtil.IS_MAC) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }


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

    /**
     * 添加系统级 LSP 服务器（如果用户未自定义同名配置，则注册）
     */
    private void addSystemLspServer(HarnessEngine engine, AgentSettings settings, String name, List<String> command, List<String> extensions) {
        // 如果用户已自定义同名配置，跳过系统级注册
        if (settings.getLspServers().containsKey(name)) {
            return;
        }

        LspServerDo lspServer = new LspServerDo();
        lspServer.setCommand(command);
        lspServer.setExtensions(extensions);
        lspServer.setEnabled(false); // 默认禁用，用户按需启用
        lspServer.setScope(AgentFlags.SCOPE_LOCAL);

        // 注册到引擎（不启用不会真正加载，仅作为可选项）
        engine.addLspServer(name, lspServer);

        // 同步到 settings 以便前端展示
        settings.getLspServers().put(name, lspServer);
    }
}
