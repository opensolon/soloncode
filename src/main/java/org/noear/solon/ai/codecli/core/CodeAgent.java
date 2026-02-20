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
import org.noear.solon.ai.skills.cli.CliSkill;
import org.noear.solon.ai.skills.diff.DiffSkill;
import org.noear.solon.ai.skills.lucene.LuceneSkill;
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
    private final Map<String, String> extraPools = new LinkedHashMap<>();
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

    public CodeAgent mountPool(String alias, String dir) {
        if (dir != null) {
            this.extraPools.put(alias, dir);
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
        return "v0.0.11";
    }

    public String getWorkDir() {
        return workDir;
    }

    private ReActAgent reActAgent;

    public CliSkill getCliSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", this.workDir);
        String boxId = session.getSessionId();

        return (CliSkill) session.attrs().computeIfAbsent("CliSkill", x -> {
            CliSkill skill = new CliSkill(boxId, effectiveWorkDir);
            extraPools.forEach(skill::mountPool);
            return skill;
        });
    }

    public CodeInitSkill getInitSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", this.workDir);
        String boxId = session.getSessionId();

        return (CodeInitSkill) session.attrs().computeIfAbsent("CodeSkill", x -> {
            CodeInitSkill skill = new CodeInitSkill(effectiveWorkDir);
            return skill;
        });
    }

    public LuceneSkill getLuceneSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", this.workDir);
        String boxId = session.getSessionId();

        return (LuceneSkill) session.attrs().computeIfAbsent("LuceneSkill", x -> {
            return new LuceneSkill(effectiveWorkDir);
        });
    }

    public DiffSkill getDiffSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", this.workDir);
        String boxId = session.getSessionId();

        return (DiffSkill) session.attrs().computeIfAbsent("DiffSkill", x -> {
            return new DiffSkill(effectiveWorkDir);
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
                    .role("你的名字叫 " + nickname + "。");

            if (Assert.isNotEmpty(instruction)) {
                agentBuilder.instruction(instruction);
            }

            //上下文摘要
            CompositeSummarizationStrategy compositeStrategy = new CompositeSummarizationStrategy();
            compositeStrategy.addStrategy(new KeyInfoExtractionStrategy(chatModel));
            compositeStrategy.addStrategy(new HierarchicalSummarizationStrategy(chatModel));
            SummarizationInterceptor summarizationInterceptor = new SummarizationInterceptor(12, compositeStrategy);

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

        return reActAgent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.skillAdd(getCliSkill(session));
                    o.skillAdd(getInitSkill(session));
                    o.skillAdd(getLuceneSkill(session));
                    o.skillAdd(getDiffSkill(session));
                });
    }

    public String init(AgentSession session) {
        getInitSkill(session).refresh();
        return getLuceneSkill(session).refreshSearchIndex();
    }

    public Flux<AgentChunk> stream(String sessionId, Prompt prompt) {
        return buildRequest(sessionId, prompt)
                .stream();
    }

    public AgentResponse call(String sessionId, Prompt prompt) throws Throwable {
        return buildRequest(sessionId, prompt).call();
    }
}