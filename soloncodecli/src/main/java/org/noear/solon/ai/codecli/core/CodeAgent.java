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
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.hitl.HitlStrategy;
import org.noear.solon.ai.codecli.core.tool.ApplyPatchTool;
import org.noear.solon.ai.codecli.core.tool.CodeSearchTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final static String SESSION_DEFAULT = "cli";

    private final ChatModel chatModel;
    private AgentSessionProvider sessionProvider;
    private String workDir = ".";
    private final Map<String, String> skillPools = new LinkedHashMap<>();
    private Consumer<ReActAgent.Builder> configurator;
    private boolean enableHitl = false;

    public CodeAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public CodeAgent workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public String getVersion() {
        return "v0.0.15-M2";
    }

    public String getWorkDir() {
        return workDir;
    }

    public CodeAgent skillPool(String alias, String dir) {
        if (dir != null) {
            this.skillPools.put(alias, dir);
        }
        return this;
    }

    public CodeAgent session(AgentSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
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

    private ReActAgent reActAgent;

    public CodeSkill getCodeSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", this.workDir);

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
            Path path = Paths.get(workDir).toAbsolutePath().normalize().resolve("AGENTS.md");
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
            if (sessionProvider == null) {
                Map<String, AgentSession> store = new ConcurrentHashMap<>();
                sessionProvider = (k) -> store.computeIfAbsent(k, InMemoryAgentSession::new);
            }

            final ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel);
            final String agentsMd = getAgentsMd();

            if (Assert.isEmpty(agentsMd)) {
                //无 AGENTS.md 配置
                agentBuilder.systemPrompt(trace -> "# 系统角色\n" +
                        "\n" +
                        "具备自主行动能力的专业任务解决专家。\n");
            } else {
                //有 AGENTS.md 配置
                agentBuilder.systemPrompt(trace -> "# 系统角色\n" +
                        "\n" +
                        "具备自主行动能力的专业任务解决专家。\n" + agentsMd);
            }

            CliSkillProvider cliSkillProvider = new CliSkillProvider();
            if (Assert.isNotEmpty(skillPools)) {
                for (Map.Entry<String, String> entry : skillPools.entrySet()) {
                    cliSkillProvider.skillPool(entry.getKey(), entry.getValue());
                }
            }
            cliSkillProvider.skillPool("@local", Paths.get(workDir).resolve(".soloncode/skills"));

            agentBuilder.defaultToolAdd(WebfetchTool.getInstance());
            agentBuilder.defaultToolAdd(WebsearchTool.getInstance());
            agentBuilder.defaultToolAdd(CodeSearchTool.getInstance());
            agentBuilder.defaultToolAdd(new ApplyPatchTool());
            agentBuilder.defaultSkillAdd(cliSkillProvider);
            agentBuilder.defaultSkillAdd(new TodoSkill());

            //上下文摘要
            SummarizationInterceptor summarizationInterceptor = new SummarizationInterceptor(12,
                    new HierarchicalSummarizationStrategy(chatModel));

            agentBuilder.defaultInterceptorAdd(summarizationInterceptor);

            if (enableHitl) {
                agentBuilder.defaultInterceptorAdd(new HITLInterceptor()
                        .onTool("bash", new HitlStrategy()));
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
        String activatedWorkDir = (String) session.attrs().getOrDefault("context:cwd", workDir);

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
        String search = getLuceneSkill(session).refreshSearchIndex(workDir);

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