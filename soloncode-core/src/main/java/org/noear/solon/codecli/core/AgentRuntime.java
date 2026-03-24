package org.noear.solon.codecli.core;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentExtension;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.react.intercept.summarize.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.cli.CliSkillProvider;
import org.noear.solon.ai.skills.cli.TodoSkill;
import org.noear.solon.ai.skills.diff.ApplyPatchTool;
import org.noear.solon.ai.skills.lucene.LuceneSkill;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.ai.skills.web.*;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.codecli.core.agent.AgentManager;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.codecli.core.agent.GenerateTool;
import org.noear.solon.codecli.core.hitl.HitlStrategy;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 智能体运行时
 *
 * @author noear
 */
public class AgentRuntime {
    private final static Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

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
    public final static String CLAUDE_SKILLS = ".claude/skills/";

    private final ChatModel chatModel;
    private final AgentSessionProvider sessionProvider;
    private final AgentProperties properties;

    private final CodeSkill codeSkill = new CodeSkill();
    private final LuceneSkill luceneSkill = new LuceneSkill();
    private final TodoSkill todoSkill = new TodoSkill(SOLONCODE_SESSIONS);
    private final TaskSkill taskSkill = new TaskSkill(this);
    private final GenerateTool generateTool = new GenerateTool(this);

    private final ReActAgent reActAgent;

    private final McpProviders mcpProviders;
    private final RestApiSkill restApis;

    private final CliSkillProvider cliSkills = new CliSkillProvider();

    private final SummarizationInterceptor summarizationInterceptor;


    private AgentManager agentManager;

    public String getVersion() {
        return "v2026.2.23";
    }

    public String getName() {
        return reActAgent.name();
    }

    public AgentProperties getProps() {
        return properties;
    }

    public AgentProperties getProperties() {
        return properties;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public McpProviders getMcpProviders() {
        return mcpProviders;
    }

    public RestApiSkill getRestApis() {
        return restApis;
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }

    public SummarizationInterceptor getSummarizationInterceptor() {
        return summarizationInterceptor;
    }

    public CliSkillProvider getCliSkills() {
        return cliSkills;
    }

    public TodoSkill getTodoSkill() {
        return todoSkill;
    }

    public TaskSkill getTaskSkill() {
        return taskSkill;
    }

    public CodeSkill getCodeSkill() {
        return codeSkill;
    }

    private AgentRuntime(ChatModel chatModel, AgentProperties properties, AgentSessionProvider sessionProvider, Collection<ReActAgentExtension> extensions) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.sessionProvider = sessionProvider;

        if (Assert.isNotEmpty(properties.getRestApis())) {
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

        if (Assert.isNotEmpty(agentsMd)) {
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

        cliSkills.skillPool("@soloncode_skills", Paths.get(properties.getWorkDir(), AgentRuntime.SOLONCODE_SKILLS));
        cliSkills.skillPool("@installed_skills", Paths.get(properties.getWorkDir(), "skills"));

        cliSkills.skillPool("@opencode_skills", Paths.get(properties.getWorkDir(), AgentRuntime.OPENCODE_SKILLS));
        cliSkills.skillPool("@claude_skills", Paths.get(properties.getWorkDir(), AgentRuntime.CLAUDE_SKILLS));

        agentManager = new AgentManager();
        agentManager.agentPool(Paths.get(properties.getWorkDir(), AgentRuntime.SOLONCODE_AGENTS));
        agentManager.agentPool(Paths.get(properties.getWorkDir(), AgentRuntime.SOLONCODE_AGENTS_TEAMS), true);

        //上下文摘要
        SummarizationStrategy strategy = new CompositeSummarizationStrategy()
                .addStrategy(new KeyInfoExtractionStrategy(chatModel))      // 提取干货（去水）
                .addStrategy(new HierarchicalSummarizationStrategy(chatModel)); // 滚动更新摘要

        summarizationInterceptor = new SummarizationInterceptor(
                properties.getSummaryWindowSize(),
                properties.getSummaryWindowToken(),
                strategy);

        agentBuilder.defaultInterceptorAdd(summarizationInterceptor);

        if (properties.isSubagentEnabled()) {
            //agentBuilder.defaultToolAdd(MemorySkill.getInstance());
            agentBuilder.defaultSkillAdd(todoSkill);
            agentBuilder.defaultSkillAdd(cliSkills.getExpertSkill());

            agentBuilder.defaultToolAdd(generateTool);
            agentBuilder.defaultSkillAdd(taskSkill);
        } else {
            //agentBuilder.defaultToolAdd(MemorySkill.getInstance());
            agentBuilder.defaultToolAdd(WebfetchTool.getInstance());
            agentBuilder.defaultToolAdd(WebsearchTool.getInstance());
            agentBuilder.defaultToolAdd(CodeSearchTool.getInstance());
            agentBuilder.defaultToolAdd(new ApplyPatchTool());

            agentBuilder.defaultSkillAdd(cliSkills);
            agentBuilder.defaultSkillAdd(todoSkill);
            agentBuilder.defaultSkillAdd(codeSkill);
            agentBuilder.defaultSkillAdd(luceneSkill);
        }

        if (getMcpProviders() != null) {
            for (McpClientProvider mcpProvider : getMcpProviders().getProviders().values()) {
                agentBuilder.defaultToolAdd(mcpProvider);
            }
        }

        if (getRestApis() != null) {
            agentBuilder.defaultSkillAdd(getRestApis());
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

        if (Assert.isNotEmpty(extensions)) {
            for (ReActAgentExtension extension : extensions) {
                extension.configure(agentBuilder);
            }
        }

        reActAgent = agentBuilder.build();
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
                    o.toolContextPut(AgentRuntime.ATTR_CWD, activatedWorkDir);
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatModel chatModel;
        private AgentProperties properties;
        private AgentSessionProvider sessionProvider;
        private List<ReActAgentExtension> extensions = new ArrayList<>();

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder properties(AgentProperties properties) {
            this.properties = properties;
            return this;
        }

        public Builder sessionProvider(AgentSessionProvider sessionProvider) {
            this.sessionProvider = sessionProvider;
            return this;
        }

        public Builder extension(ReActAgentExtension extension) {
            this.extensions.add(extension);
            return this;
        }

        public AgentRuntime build() {
            Objects.nonNull(chatModel);
            Objects.nonNull(properties);
            Objects.nonNull(sessionProvider);

            return new AgentRuntime(chatModel, properties, sessionProvider, extensions);
        }
    }
}