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
package org.noear.solon.ai.codecli.core.subagent;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 抽象子代理实现
 *
 * @author bai
 * @since 3.9.5
 */
public abstract class AbstractSubAgent implements SubAgent {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSubAgent.class);

    protected final SubAgentConfig config;
    protected final AgentSessionProvider sessionProvider;
    protected final Map<String, AgentSession> sessionStore = new ConcurrentHashMap<>();
    protected ReActAgent agent;

    public AbstractSubAgent(SubAgentConfig config, AgentSessionProvider sessionProvider) {
        this.config = config;
        this.sessionProvider = sessionProvider;
    }

    @Override
    public SubAgentType getType() {
        return config.getType();
    }

    @Override
    public SubAgentConfig getConfig() {
        return config;
    }

    /**
     * 初始化代理
     */
    protected synchronized void initAgent(ChatModel chatModel,
                                           Consumer<ReActAgent.Builder> configurator) {
        if (agent == null) {
            ReActAgent.Builder builder = ReActAgent.of(chatModel);

            // 设置系统提示词
            String systemPrompt = buildSystemPrompt();
            builder.systemPrompt(SystemPrompt.builder()
                    .instruction(systemPrompt)
                    .build());

            // 应用自定义配置
            if (configurator != null) {
                configurator.accept(builder);
            }

            this.agent = builder.build();
            LOG.info("SubAgent '{}' 初始化完成", getType().getCode());
        }
    }

    /**
     * 构建系统提示词（由子类实现）
     */
    protected abstract String buildSystemPrompt();

    /**
     * 获取会话
     */
    protected AgentSession getSession(String sessionId) {
        return sessionProvider.getSession(sessionId);
    }

    @Override
    public AgentResponse execute(Prompt prompt) throws Throwable {
        if (agent == null) {
            throw new IllegalStateException("SubAgent 尚未初始化");
        }

        String sessionId = "subagent_" + getType().getCode();
        AgentSession session = getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .call();
    }

    @Override
    public Flux<org.noear.solon.ai.agent.AgentChunk> stream(Prompt prompt) {
        if (agent == null) {
            return Flux.error(new IllegalStateException("SubAgent 尚未初始化"));
        }

        String sessionId = "subagent_" + getType().getCode();
        AgentSession session = getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .stream();
    }
}
