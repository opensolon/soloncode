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
package org.noear.solon.bot.core.subagent;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.SystemPrompt;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;


/**
 * 抽象子代理实现
 *
 * @author bai
 * @since 3.9.5
 */
public abstract class AbsSubagent implements Subagent {
    private static final Logger LOG = LoggerFactory.getLogger(AbsSubagent.class);

    protected final AgentKernel mainAgent;

    protected String description;
    protected String systemPrompt;

    public AbsSubagent(AgentKernel mainAgent) {
        this.mainAgent = mainAgent;
    }

    @Override
    public final String getDescription() {
        if (Assert.isEmpty(description)) {
            return getDefaultDescription();
        } else {
            return description;
        }
    }

    public final void setDescription(String description) {
        this.description = description;
    }

    public final String getSystemPrompt() {
        if (Assert.isEmpty(systemPrompt)) {
            return getDefaultSystemPrompt();
        } else {
            return systemPrompt;
        }
    }

    public final void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /**
     * 获取内置系描述（由子类实现）
     */
    protected abstract String getDefaultDescription();

    /**
     * 获取内置系统提示词（由子类实现）
     */
    protected abstract String getDefaultSystemPrompt();


    /**
     * 定制
     */
    protected abstract void customize(ReActAgent.Builder builder);


    private volatile ReActAgent cachedAgent;

    protected void refresh() {
        Utils.locker().lock();
        try {
            this.cachedAgent = null;
        } finally {
            Utils.locker().unlock();
        }
    }

    /**
     * 初始化代理
     */
    protected ReActAgent getOrBuildAgent() {
        if (cachedAgent == null) {
            Utils.locker().lock();
            try {
                if (cachedAgent == null) {
                    ReActAgent.Builder builder = ReActAgent.of(mainAgent.getChatModel());

                    builder.systemPrompt(SystemPrompt.builder()
                            .instruction(getSystemPrompt())
                            .build());

                    // 应用自定义配置
                    customize(builder);

                    cachedAgent = builder.build();
                }
            } finally {
                Utils.locker().unlock();
            }
        }

        return cachedAgent;
    }

    @Override
    public AgentResponse execute(String sessionId, String __cwd, Prompt prompt) throws Throwable {
        AgentSession session = mainAgent.getSession(sessionId);

        return getOrBuildAgent().prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__cwd", __cwd);
                })
                .call();
    }

    @Override
    public Flux<AgentChunk> stream(String sessionId, String __cwd, Prompt prompt) {
        AgentSession session = mainAgent.getSession(sessionId);

        return getOrBuildAgent().prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__cwd", __cwd);
                })
                .stream();
    }
}