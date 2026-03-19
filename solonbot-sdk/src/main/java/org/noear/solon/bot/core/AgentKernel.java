package org.noear.solon.bot.core;

import com.microsoft.playwright.Playwright;
import lombok.Getter;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.browser.BrowserSkill;
import org.noear.solon.ai.skills.cli.CliSkillProvider;
import org.noear.solon.ai.skills.cli.TodoSkill;
import org.noear.solon.ai.skills.diff.ApplyPatchTool;
import org.noear.solon.ai.skills.lucene.LuceneSkill;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.ai.skills.web.*;
import org.noear.solon.bot.core.event.EventBus;
import org.noear.solon.bot.core.memory.SharedMemoryManager;
import org.noear.solon.bot.core.message.MessageChannel;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.noear.solon.bot.core.subagent.TaskSkill;
import org.noear.solon.bot.core.teams.AgentTeamsSkill;
import org.noear.solon.bot.core.teams.AgentTeamsTools;
import org.noear.solon.bot.core.teams.MainAgent;
import org.noear.solon.bot.core.teams.SharedTaskList;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 智能体内核 (Pool-Box 模型)
 * <p>基于 ReAct 模式的终端智能助理，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
@Getter
public class AgentKernel {
    private final static Logger LOG = LoggerFactory.getLogger(AgentKernel.class);

    public final static String SESSION_DEFAULT = "cli";
    public final static String ATTR_CWD = "__cwd";

    public final static String SOLONCODE_SESSIONS = ".soloncode/sessions/";
    public final static String SOLONCODE_SKILLS = ".soloncode/skills/";
    public final static String SOLONCODE_AGENTS = ".soloncode/agents/";
    public final static String SOLONCODE_AGENTS_TEAMS = ".soloncode/agentsTeams/";
    public final static String SOLONCODE_DOWNLOADS = ".soloncode/downloads/";
    public final static String SOLONCODE_BROWSER = ".soloncode/browser/";
    public final static String SOLONCODE_MEMORY = ".soloncode/memory/";

    public final static String OPENCODE_SKILLS = ".opencode/skills/";
    public final static String OPENCODE_AGENTS = ".opencode/agents/";
    public final static String CLAUDE_SKILLS = ".claude/skills/";
    public final static String CLAUDE_AGENTS = ".claude/agents/";

    private final ChatModel chatModel;
    private final AgentSessionProvider sessionProvider;
    private final AgentProperties properties;

    private final CodeSkill codeSkill = new CodeSkill();
    private final LuceneSkill luceneSkill = new LuceneSkill();

    private final ReActAgent reActAgent;

    private final McpProviders mcpProviders;
    private final RestApiSkill restApis;

    private final Consumer<ReActAgent.Builder> configurator;
    private final CliSkillProvider cliSkills = new CliSkillProvider();

    private final SummarizationInterceptor summarizationInterceptor;

    private SubagentManager subagentManager;

    // Agent Teams 相关组件
    private MainAgent mainAgent;
    private EventBus eventBus;
    private SharedTaskList taskList;
    private SharedMemoryManager memoryManager;
    private MessageChannel messageChannel;

    public String getVersion() {
        return "v0.0.23";
    }

    public String getName() {
        return reActAgent.name();
    }

    public AgentProperties getProps() {
        return properties;
    }

    public AgentKernel(ChatModel chatModel, AgentProperties properties, AgentSessionProvider sessionProvider, Consumer<ReActAgent.Builder> configurator) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.sessionProvider = sessionProvider;
        this.configurator = configurator;

        if(Assert.isNotEmpty(properties.getRestApis())) {
            restApis = new RestApiSkill();
            for (Map.Entry<String, ApiSource> entry : properties.getRestApis().entrySet()) {
                restApis.addApi(entry.getValue());
            }
        } else {
            restApis = null;
        }

        try {
            if (Assert.isNotEmpty(properties.getMcpServers())) {
                mcpProviders = McpProviders.fromMcpServers(properties.getMcpServers());
            } else {
                mcpProviders = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Mcp servers load failure", e);
        }


        final ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel).name("main");
        final String agentsMd = getAgentsMd();

        if (Assert.isEmpty(agentsMd)) {
            //无 AGENTS.md 配置
            agentBuilder.systemPrompt(SystemPrompt.builder().build());
        } else {
            //有 AGENTS.md 配置
            agentBuilder.systemPrompt(trace -> agentsMd);
        }


        if (Assert.isNotEmpty(properties.getMountPool())) {
            properties.getMountPool().forEach((alias, dir) -> {
                cliSkills.skillPool(alias, dir);
            });
        }

        if (Assert.isNotEmpty(properties.getSkillPools())) {
            properties.getSkillPools().forEach((alias, dir) -> {
                cliSkills.skillPool(alias, dir);
            });
        }

        cliSkills.getTerminalSkill().setSandboxMode(properties.isSandboxMode());

        cliSkills.skillPool("@soloncode_skills", Paths.get(properties.getWorkDir(), AgentKernel.SOLONCODE_SKILLS));
        cliSkills.skillPool("@opencode_skills", Paths.get(properties.getWorkDir(), AgentKernel.OPENCODE_SKILLS));
        cliSkills.skillPool("@claude_skills", Paths.get(properties.getWorkDir(), AgentKernel.CLAUDE_SKILLS));

        agentBuilder.defaultToolAdd(WebfetchTool.getInstance());
        agentBuilder.defaultToolAdd(WebsearchTool.getInstance());
        agentBuilder.defaultToolAdd(CodeSearchTool.getInstance());
        agentBuilder.defaultToolAdd(new ApplyPatchTool());
        agentBuilder.defaultSkillAdd(cliSkills);
        agentBuilder.defaultSkillAdd(new TodoSkill(SOLONCODE_SESSIONS));
        agentBuilder.defaultSkillAdd(codeSkill);
        agentBuilder.defaultSkillAdd(luceneSkill);

        //上下文摘要
        SummarizationStrategy strategy = new CompositeSummarizationStrategy()
                .addStrategy(new KeyInfoExtractionStrategy(chatModel))      // 提取干货（去水）
                .addStrategy(new HierarchicalSummarizationStrategy(chatModel)); // 滚动更新摘要

        summarizationInterceptor = new SummarizationInterceptor(
                properties.getSummaryWindowSize(),
                properties.getSummaryWindowToken(),
                strategy);

        agentBuilder.defaultInterceptorAdd(summarizationInterceptor);

        if(properties.isBrowserEnabled() && ClassUtil.hasClass(()-> Playwright.class)) {
            agentBuilder.defaultSkillAdd(new BrowserSkill());
        }

        // 添加子代理工具
        if (properties.isSubagentEnabled()) {
            subagentManager = new SubagentManager(this);

            // 注册自定义 agents 池（类似 skillPool）
            // 注册 soloncode agents
            subagentManager.agentPool(Paths.get(properties.getWorkDir(), AgentKernel.SOLONCODE_AGENTS));
            // 注册 opencode agents
            subagentManager.agentPool(Paths.get(properties.getWorkDir(), AgentKernel.OPENCODE_AGENTS));
            // 注册 claude agents
            subagentManager.agentPool(Paths.get(properties.getWorkDir(), AgentKernel.CLAUDE_AGENTS));
            // 注册 soloncode agentsTeams（递归扫描团队成员目录）
            subagentManager.agentPool(Paths.get(properties.getWorkDir(), AgentKernel.SOLONCODE_AGENTS_TEAMS), true);

            // SubagentSkill 会通过 @ToolMapping 自动注册为工具
            agentBuilder.defaultSkillAdd(new TaskSkill(this, subagentManager));
            LOG.info("子代理模式已启用");
        }
        if (properties.isAgentTeamEnabled()){
            initAgentTeams(properties, agentBuilder);
        }

        // HITL 交互干预（优先使用实例字段，否则使用配置）
        if (properties.isHitlEnabled()) {
            agentBuilder.defaultInterceptorAdd(new HITLInterceptor()
                    .onTool("bash", new HitlStrategy()));
            LOG.info("HITL 交互干预已启用");
        }

        // 添加步数
        agentBuilder.maxSteps(properties.getMaxSteps());
        // 添加步数自动扩展
        agentBuilder.maxStepsExtensible(properties.isMaxStepsAutoExtensible());
        // 添加会话窗口大小
        agentBuilder.sessionWindowSize(properties.getSessionWindowSize());

        if (mcpProviders != null) {
            for (McpClientProvider mcpProvider : mcpProviders.getProviders().values()) {
                agentBuilder.defaultToolAdd(mcpProvider);
            }
        }

        if(restApis != null){
            agentBuilder.defaultSkillAdd(restApis);
        }

        // Agent Teams 模式：追加 Team Lead 指令到系统提示词
        if (properties.isAgentTeamEnabled() && mainAgent != null) {
            String teamLeadInstruction = mainAgent.getTeamLeadInstruction();
            if (Assert.isNotEmpty(teamLeadInstruction)) {
                // 获取当前的 systemPrompt 并追加指令
                agentBuilder.systemPrompt(SystemPrompt.builder()
                        .instruction(trace -> {
                            String basePrompt = agentsMd != null ? agentsMd : "";
                            return basePrompt + "\n\n" + teamLeadInstruction;
                        })
                        .build());
                LOG.info("Team Lead 指令已追加到系统提示词");
            }
        }

        if (configurator != null) {
            configurator.accept(agentBuilder);
        }

        reActAgent = agentBuilder.build();

        // Agent Teams 模式：将主 ReActAgent 设置到 MainAgent
        if (properties.isAgentTeamEnabled() && mainAgent != null) {
            mainAgent.setSharedAgent(reActAgent, chatModel);
            LOG.info("MainAgent 已设置共享 ReActAgent");
        }
    }


    /**
     * 初始化 Agent Teams 模式
     */
    private void initAgentTeams(AgentProperties properties, ReActAgent.Builder agentBuilder) {
        try {
            LOG.info("正在初始化 Agent Teams 模式...");

            // 1. 创建 EventBus（事件总线）- 使用配置
            int eventThreads = properties.getEventBus().asyncThreads;
            int eventHistorySize = properties.getEventBus().maxHistorySize;
            this.eventBus = new EventBus(eventThreads, eventHistorySize);
            LOG.debug("EventBus 已创建 (线程数: {}, 历史大小: {})", eventThreads, eventHistorySize);

            // 2. 创建 SharedTaskList（共享任务列表）- 使用配置
            this.taskList = new SharedTaskList(eventBus, properties.getTeams());
            LOG.debug("SharedTaskList 已创建");

            // 3. 创建 SharedMemoryManager（共享内存管理器）
            Path memoryPath = Paths.get(properties.getWorkDir(), SOLONCODE_MEMORY);
            this.memoryManager = new SharedMemoryManager(memoryPath);
            LOG.debug("SharedMemoryManager 已创建，路径: {}", memoryPath);

            // 4. 创建 MessageChannel（消息通道）- 使用配置
            Path messagePath = Paths.get(properties.getWorkDir(), SOLONCODE_MEMORY);
            int messageThreads = properties.getMessageChannel().threads != null ?
                                  properties.getMessageChannel().threads : 4;
            this.messageChannel = new MessageChannel(messagePath.toString(), messageThreads);
            LOG.debug("MessageChannel 已创建，路径: {}, 线程数: {}", messagePath, messageThreads);

            // 5. 创建 MainAgent 配置
            SubAgentMetadata mainAgentConfig = new SubAgentMetadata();
            mainAgentConfig.setName("main-agent");
            mainAgentConfig.setDescription("Agent Teams 协调器，负责任务分解和团队协作");
            mainAgentConfig.setEnabled(true);

            // 6. 创建 MainAgent（传入 kernel 和 subagentManager 以支持 subagent 功能）
            this.mainAgent = new MainAgent(
                    mainAgentConfig,
                    sessionProvider,
                    memoryManager,
                    eventBus,
                    messageChannel,
                    taskList,
                    properties.getWorkDir(),
                    cliSkills.getPoolManager(),
                    this,  // AgentKernel
                    subagentManager  // SubagentManager
            );
            LOG.debug("MainAgent 已创建");

            // 6. 创建 AgentTeamsSkill 并注册到主 Agent
            AgentTeamsSkill agentTeamsSkill = new AgentTeamsSkill(
                    mainAgent,
                    this,  // AgentKernel
                    subagentManager
            );
            agentBuilder.defaultSkillAdd(agentTeamsSkill);
            agentBuilder.defaultSkillAdd(new AgentTeamsTools(memoryManager, mainAgent.getEventBus()));

            LOG.info("AgentTeamsSkill 已注册");

            LOG.info("Agent Teams 模式初始化完成 [OK]");

        } catch (Throwable e) {
            LOG.error("Agent Teams 模式初始化失败", e);
            throw new RuntimeException("Failed to initialize Agent Teams mode", e);
        }
    }


    public AgentSession getSession(String instanceId) {
        return sessionProvider.getSession(instanceId);
    }

    private String getAgentsMd() {
        URL agentsUrl;

        try {
            Path path = Paths.get(properties.getWorkDir()).toAbsolutePath().normalize()
                    .resolve("AGENTS.md");

            if (Files.exists(path)) {
                //如果工作区有
                agentsUrl = path.toUri().toURL();
            } else {
                //默认尝试找资源
                agentsUrl = ResourceUtil.findResourceOrFile("AGENTS.md");
            }

            if (agentsUrl != null) {
                try (InputStream is = agentsUrl.openStream()) {
                    String content = IoUtil.transferToString(is, "utf-8").trim();

                    if (content.length() > 10000) { // 例如限制在 1万字符以内
                        LOG.warn("AGENTS.md is too large, truncating...");
                        return content.substring(0, 10000);
                    }
                    return content;
                }
            }
        } catch (Throwable e) {
            LOG.warn("AGENTS.md load failure: {}", e.getMessage(), e);
        }

        return null;
    }

    private ReActRequest buildRequest(String sessonId, Prompt prompt) {
        if (sessonId == null) {
            sessonId = SESSION_DEFAULT;
        }

        AgentSession session = sessionProvider.getSession(sessonId);
        String activatedWorkDir = (String) session.attrs()
                .getOrDefault(ATTR_CWD, properties.getWorkDir());

        return reActAgent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut(AgentKernel.ATTR_CWD, activatedWorkDir);
                });
    }

    public String init(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs()
                .getOrDefault(ATTR_CWD, properties.getWorkDir());

        String code = codeSkill.refresh(effectiveWorkDir);
        String search = luceneSkill.refreshSearchIndex(effectiveWorkDir);

        if (Assert.isNotEmpty(code)) {
            return search + "\n" + code;
        } else {
            return search;
        }
    }

    public Flux<AgentChunk> stream(String sessionId, Prompt prompt) {
        return buildRequest(sessionId, prompt)
                .stream();
    }

    public AgentResponse call(String sessionId, Prompt prompt) throws Throwable {
        return buildRequest(sessionId, prompt)
                .call();
    }

}