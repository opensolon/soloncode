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
import org.noear.solon.ai.codecli.core.skills.CodeSkill;
import org.noear.solon.ai.skills.cli.CliSkill;
import org.noear.solon.ai.skills.diff.DiffSkill;
import org.noear.solon.ai.skills.lucene.LuceneSkill;
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
public class AgentNexus {
    private final static String SESSION_DEFAULT = "cli";

    private final ChatModel chatModel;
    private AgentSessionProvider sessionProvider;
    private String nickname = "CodeCLI";
    private String workDir = ".";
    private final Map<String, String> extraPools = new LinkedHashMap<>();
    private Consumer<ReActAgent.Builder> configurator;
    private boolean enableHitl = false;

    public AgentNexus(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 设置 Agent 名称 (同时也作为控制台输出前缀)
     */
    public AgentNexus nickname(String nickname) {
        if (nickname != null && !nickname.isEmpty()) {
            this.nickname = nickname;
        }
        return this;
    }

    public AgentNexus workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public AgentNexus mountPool(String alias, String dir) {
        if (dir != null) {
            this.extraPools.put(alias, dir);
        }
        return this;
    }

    public AgentNexus session(AgentSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
        return this;
    }

    public AgentNexus config(Consumer<ReActAgent.Builder> configurator) {
        this.configurator = configurator;
        return this;
    }

    /**
     * 是否启用 HITL 交互
     */
    public AgentNexus enableHitl(boolean enableHitl) {
        this.enableHitl = enableHitl;
        return this;
    }

    public String getNickname() {
        return nickname;
    }

    public String getVersion() {
        return "v0.0.10";
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

    public CodeSkill getCodeSkill(AgentSession session) {
        String effectiveWorkDir = (String) session.attrs().getOrDefault("context:cwd", this.workDir);
        String boxId = session.getSessionId();

        return (CodeSkill) session.attrs().computeIfAbsent("CodeSkill", x -> {
            CodeSkill skill = new CodeSkill(effectiveWorkDir);
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
                    .role("你的名字叫 " + nickname + "。")
                    .instruction(
                            "你是一个超级智能体。请遵循以下准则：\n" +
                                    "1.【行动原则】：不要假设，要验证。修改前必读，交付前必测。\n" +
                                    "2.【任务管理】：面对复杂任务，必须在根目录维护 `TODO.md`。规范：\n" +
                                    "   - 初始任务：收到指令后，先在 `TODO.md` 中列出所有逻辑步骤。\n" +
                                    "   - 状态追踪：使用 [ ] 表示待办，[x] 表示已完成。每完成一步必须物理更新文件。\n" +
                                    "   - 恢复上下文：任何时候开始工作前（包括每一轮思考开始），必须先读取 `TODO.md` 以确认进度。如果是新任务，必须先初始化 `TODO.md`。\n" +
                                    "3.【权限边界】：写操作（创建/修改/删除）仅限在当前盒子（Box）路径内。严禁修改盒子外的文件。\n" +
                                    "4.【自主性】：bash 是你的核心工具，用于构建、测试及自动化任务。当内置工具不足时，应自主编写脚本解决。\n" +
                                    "5.【规范对齐】：遇到 @pool 路径时，必读其 SKILL.md；所有相对路径严禁使用 './' 前缀。\n" +
                                    "6.【交互风格】：回复问题时——简洁、直接、结果导向，要拟人化。禁止使用表情包（Emoji），禁止长篇大论。\n" +
                                    "7.【安全性】：保护环境安全，不泄露密钥，不访问盒子外的绝对路径。\n" +
                                    "8.【任务切换】：若用户中途改变任务方向，必须第一时间清空或重构 `TODO.md` 中的内容，以确保后续步骤与新目标一致。"
                    );

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
                    o.skillAdd(getCodeSkill(session));
                    o.skillAdd(getLuceneSkill(session));
                    o.skillAdd(getDiffSkill(session));
                });
    }

    public String init(AgentSession session) {
        getCodeSkill(session).refresh();
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