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
import org.noear.solon.lang.Preview;
import reactor.core.publisher.Flux;

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
    private final static String SESSION_DEFAULT = "cli";

    private final ChatModel chatModel;
    private AgentSessionProvider sessionProvider;
    private String nickname = "CodeCLI";
    private String instruction = "";
    private String workDir = ".";
    private final Map<String, String> skillPools = new LinkedHashMap<>();
    private Consumer<ReActAgent.Builder> configurator;
    private boolean enableHitl = false;

    public CodeAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 设置 Agent 名称 (同时也作为控制台输出前缀)
     */
    public CodeAgent nickname(String nickname) {
        if (nickname != null && !nickname.isEmpty()) {
            this.nickname = nickname;
        }
        return this;
    }

    public CodeAgent instruction(String instruction) {
        this.instruction = instruction;
        return this;
    }

    public CodeAgent workDir(String workDir) {
        this.workDir = workDir;
        return this;
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

    public String getNickname() {
        return nickname;
    }

    public String getVersion() {
        return "v0.0.15-M2";
    }

    public String getWorkDir() {
        return workDir;
    }

    private ReActAgent reActAgent;

    public CodeSkill getInitSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", this.workDir);
        String boxId = session.getSessionId();

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

    public void prepare() {
        if (reActAgent == null) {
            if (sessionProvider == null) {
                Map<String, AgentSession> store = new ConcurrentHashMap<>();
                sessionProvider = (k) -> store.computeIfAbsent(k, InMemoryAgentSession::new);
            }

            ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel)
                    .role("你的昵称叫 " + nickname);

            if (Assert.isNotEmpty(instruction)) {
                agentBuilder.systemPrompt(CodeSystemPrompt.builder()
                        .instruction(instruction)
                        .build());
            } else {
                agentBuilder.systemPrompt(CodeSystemPrompt.builder()
                        .build());
            }

            PoolManager poolManager = new PoolManager();
            if(Assert.isNotEmpty(skillPools)) {
                for (Map.Entry<String, String> entry : skillPools.entrySet()) {
                    poolManager.registerPool(entry.getKey(), entry.getValue());
                }
            }

            agentBuilder.defaultToolAdd(WebfetchTool.getInstance());
            agentBuilder.defaultToolAdd(WebsearchTool.getInstance());
            agentBuilder.defaultToolAdd(CodeSearchTool.getInstance());
            agentBuilder.defaultToolAdd(new ApplyPatchTool());
            agentBuilder.defaultSkillAdd(new TerminalSkill(poolManager));
            agentBuilder.defaultSkillAdd(new ExpertSkill(poolManager));
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
        String activatedWorkDir =  (String) session.attrs().getOrDefault("context:cwd", workDir);

        return reActAgent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__workDir", activatedWorkDir);

                    o.skillAdd(getInitSkill(session));
                    o.toolAdd(getLuceneSkill(session).getTools(null));
                });
    }

    public String init(AgentSession session) {
        String code = getInitSkill(session).refresh();
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