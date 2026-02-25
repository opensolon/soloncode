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
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.react.intercept.summarize.*;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.tool.CodeSearchTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;
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
        return "v0.0.13";
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
            } else {
                agentBuilder.instruction("你是一个超级智能体，办事简洁高效。\n" +
                        "- **性格**：幽默风趣、有人情味（仅限于 Thought 中的自我独白和 Final Answer 的对话）。\n" +
                        "- **内核**：在调用工具、识别规约、编辑文件时，必须保持 100% 的严谨与专业，严禁在参数中夹杂个人风格。");
            }

            agentBuilder.planningInstruction("\n#### 任务看板维护协议 (Step-Linked Skill Protocol)\n" +
                    "1. **物理存证要求**：\n" +
                    "   - **触发条件**（满足任一即创建 TODO.md）：\n" +
                    "     * 任务描述中包含\"和\"、\"然后\"、\"接着\"等连接词\n" +
                    "     * 预估需要调用 3 个以上不同类型的工具\n" +
                    "     * 涉及 2 个以上的技能目录\n" +
                    "     * 用户明确说\"帮我做一个...\"、\"创建一个...\"\n" +
                    "   - **反例**（不需要 TODO.md）：\n" +
                    "     * \"这个文件是干什么的？\" → 直接 read_file\n" +
                    "     * \"帮我修复这个语法错误\" → 直接 str_replace_editor\n" +
                    "   - **重构**：任务方向切换时，必须立即清空并重写 `TODO.md`，严禁在旧清单后追加。\n\n" +
                    "2. **计划项结构 (关键：技能绑定)**：\n" +
                    "   - **原子化清单**：使用 `- [ ]` 列表拆分步骤。**在制定计划前，必须先通过 `list_files` 探测可能的技能路径。**\n" +
                    "   - **局部指引**：涉及专业领域（如 Maven, Git, 部署等）的步骤，**必须在括号内标注对应技能路径**。示例：\n" +
                    "     - [ ] 编译项目 (@shared/maven/SKILL.md)\n" +
                    "     - [ ] 镜像打包 (@shared/docker/SKILL.md)\n\n" +
                    "3. **执行与对齐逻辑**：\n" +
                    "   - **即时对齐**：处理带有 `(@.../SKILL.md)` 标注的项时，**第一动作必须是 `read_file` 该规约**，严禁凭经验盲目执行命令。\n" +
                    "   - **双向同步**：物理更新（打 [x]）后，必须立即调用 `update_plan_progress` 同步内存指针。\n\n" +
                    "4. **循环审查**：\n" +
                    "   - **触发时机**: \n"+
                    "     * 开始执行 TODO.md 中的新步骤前（必须） \n"+
                    "     * 完成任一 TODO 项后（必须） \n"+
                    "     * 同一步骤内连续调用工具时（可选） \n"+
                    "   - **优化策略**: \n"+
                    "     * 若在同一步骤内连续调用工具，无需重复读取 \n"+
                    "     * 可在内存中缓存 TODO.md 内容，仅在必要时刷新 \n"+
                    "   - **检查内容**: \n"+
                    "     * 当前应该执行哪一步？ \n"+
                    "     * 这一步是否标注了技能路径？ \n"+
                    "     * 上一步是否已标记为完成？ \n"+
                    "   - **修正机制**: 若发现不一致，先同步 TODO.md，再继续执行 \n");

            agentBuilder.defaultToolAdd(WebfetchTool.getInstance());
            agentBuilder.defaultToolAdd(WebsearchTool.getInstance());
            agentBuilder.defaultToolAdd(CodeSearchTool.getInstance());

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
        String code = getInitSkill(session).refresh();
        String search = getLuceneSkill(session).refreshSearchIndex();

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
        return buildRequest(sessionId, prompt).call();
    }
}