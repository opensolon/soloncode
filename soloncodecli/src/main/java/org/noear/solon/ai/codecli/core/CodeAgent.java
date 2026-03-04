package org.noear.solon.ai.codecli.core;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.summarize.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.subagent.SubAgentManager;
import org.noear.solon.ai.codecli.core.tool.SubAgentTool;
import org.noear.solon.ai.codecli.core.tool.ApplyPatchTool;
import org.noear.solon.ai.codecli.core.tool.CodeSearchTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.core.util.Assert;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class CodeAgent {
    private final static Logger LOG = LoggerFactory.getLogger(CodeAgent.class);

    public final static String SESSION_DEFAULT = "cli";
    public final static String SOLONCODE_SESSIONS = "/.soloncode/sessions/";
    public final static String SOLONCODE_SKILLS = "/.soloncode/skills/";
    public final static String OPENCODE_SKILLS = "/.opencode/skills/";
    public final static String CLAUDE_SKILLS = "/.claude/skills/";

    private final ChatModel chatModel;
    private final AgentSessionProvider sessionProvider;
    private final CodeProperties config;

    private final Map<String, String> skillPools = new LinkedHashMap<>();
    private final McpProviders mcpProviders;
    private Consumer<ReActAgent.Builder> configurator;
    private boolean enableHitl = true;
    private boolean enableSubAgent = true; // 默认启用子代理
    private SubAgentManager subAgentManager;

    public CodeAgent(ChatModel chatModel, AgentSessionProvider sessionProvider, CodeProperties config) {
        this.chatModel = chatModel;
        this.sessionProvider = sessionProvider;
        this.config = config;

        if (Assert.isNotEmpty(config.mountPool)) {
            config.mountPool.forEach((alias, dir) -> {
                skillPool(alias, dir);
            });
        }

        if (Assert.isNotEmpty(config.skillPools)) {
            config.skillPools.forEach((alias, dir) -> {
                skillPool(alias, dir);
            });
        }

        try {
            if (Assert.isNotEmpty(config.mcpServers)) {
                mcpProviders = McpProviders.fromMcpServers(config.mcpServers);
            } else {
                mcpProviders = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Mcp servers load failure", e);
        }
    }

    public String getVersion() {
        return "v0.0.17";
    }

    public CodeProperties getConfig() {
        return config;
    }

    public String getWorkDir() {
        return config.workDir;
    }

    public CodeAgent skillPool(String alias, String dir) {
        if (dir != null) {
            this.skillPools.put(alias, dir);
        }
        return this;
    }

    public CodeAgent config(Consumer<ReActAgent.Builder> configurator) {
        this.configurator = configurator;
        return this;
    }

    /**
     * 是否启用 HITL 交互
     */
    public CodeAgent enableHitl(boolean enableHitl) {
        this.enableHitl = enableHitl;
        return this;
    }

    /**
     * 是否启用子代理功能
     */
    public CodeAgent enableSubAgent(boolean enableSubAgent) {
        this.enableSubAgent = enableSubAgent;
        return this;
    }

    /**
     * 获取子代理管理器
     */
    public SubAgentManager getSubAgentManager() {
        return subAgentManager;
    }

    private ReActAgent reActAgent;

    public CodeSkill getCodeSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", config.workDir);

        return (CodeSkill) session.attrs().computeIfAbsent("CodeSkill", x -> {
            CodeSkill skill = new CodeSkill(effectiveWorkDir);
            return skill;
        });
    }

    public LuceneSkill getLuceneSkill(AgentSession session) {
        return (LuceneSkill) session.attrs().computeIfAbsent("LuceneSkill", x -> {
            return new LuceneSkill();
        });
    }

    public AgentSession getSession(String instanceId) {
        return sessionProvider.getSession(instanceId);
    }

    private String getAgentsMd() {
        URL agentsUrl;

        try {
            Path path = Paths.get(config.workDir).toAbsolutePath().normalize().resolve("AGENTS.md");
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

    public void prepare() {
        if (reActAgent == null) {
            final ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel);
            final String agentsMd = getAgentsMd();

            if (Assert.isEmpty(agentsMd)) {
                //无 AGENTS.md 配置
                agentBuilder.systemPrompt(SystemPrompt.builder().build());
            } else {
                //有 AGENTS.md 配置
                agentBuilder.systemPrompt(trace -> agentsMd);
            }

            CliSkillProvider cliSkillProvider = new CliSkillProvider();
            if (Assert.isNotEmpty(skillPools)) {
                for (Map.Entry<String, String> entry : skillPools.entrySet()) {
                    cliSkillProvider.skillPool(entry.getKey(), entry.getValue());
                }
            }

            cliSkillProvider.getTerminalSkill().setSandboxMode(config.sandboxMode);

            cliSkillProvider.skillPool("@soloncode_skills", config.workDir + CodeAgent.SOLONCODE_SKILLS);
            cliSkillProvider.skillPool("@opencode_skills", config.workDir + CodeAgent.OPENCODE_SKILLS);
            cliSkillProvider.skillPool("@claude_skills", config.workDir + CodeAgent.CLAUDE_SKILLS);

            agentBuilder.defaultToolAdd(WebfetchTool.getInstance());
            agentBuilder.defaultToolAdd(WebsearchTool.getInstance());
            agentBuilder.defaultToolAdd(CodeSearchTool.getInstance());
            agentBuilder.defaultToolAdd(new ApplyPatchTool());
            agentBuilder.defaultSkillAdd(cliSkillProvider);
            agentBuilder.defaultSkillAdd(new TodoSkill());

            // 添加子代理工具
            if (enableSubAgent) {
                subAgentManager = new SubAgentManager(
                        sessionProvider,
                        config.workDir,
                        cliSkillProvider.getPoolManager(),
                        this,
                        chatModel
                );
                // SubAgentTool 会通过 @ToolMapping 自动注册为工具
                agentBuilder.defaultToolAdd(new SubAgentTool(subAgentManager));
                LOG.info("子代理功能已启用");
            }

            //上下文摘要
            SummarizationInterceptor summarizationInterceptor = new SummarizationInterceptor(
                    config.summaryWindowSize,
                    new HierarchicalSummarizationStrategy(chatModel));

            agentBuilder.defaultInterceptorAdd(summarizationInterceptor);

            // HITL 交互干预（优先使用实例字段，否则使用配置）
            boolean hitlEnabled = this.enableHitl || config.hitlEnabled;
            if (hitlEnabled) {
                agentBuilder.defaultInterceptorAdd(new HITLInterceptor()
                        .onTool("bash", new HitlStrategy()));
                LOG.info("HITL 交互干预已启用");
            }

            // 添加步数
            agentBuilder.maxSteps(config.maxSteps);
            // 添加步数自动扩展
            agentBuilder.maxStepsExtensible(config.maxStepsAutoExtensible);
            // 添加会话窗口大小
            agentBuilder.sessionWindowSize(config.sessionWindowSize);

            if (mcpProviders != null) {
                for (McpClientProvider mcpProvider : mcpProviders.getProviders().values()) {
                    agentBuilder.defaultToolAdd(mcpProvider);
                }
            }

            if (configurator != null) {
                configurator.accept(agentBuilder);
            }

            reActAgent = agentBuilder.build();
        }
    }

    private ReActRequest buildRequest(String sessonId, Prompt prompt) {
        if (sessonId == null) {
            sessonId = SESSION_DEFAULT;
        }

        AgentSession session = sessionProvider.getSession(sessonId);
        String activatedWorkDir = (String) session.attrs().getOrDefault("context:cwd", config.workDir);

        return reActAgent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__workDir", activatedWorkDir);

                    o.skillAdd(getCodeSkill(session));
                    o.toolAdd(getLuceneSkill(session).getTools(null));
                });
    }

    public String init(AgentSession session) {
        String code = getCodeSkill(session).refresh();
        String search = getLuceneSkill(session).refreshSearchIndex(config.workDir);

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