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

import lombok.Getter;
import lombok.Setter;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.bot.core.AgentRuntime;
import org.noear.solon.bot.core.teams.AgentTeamsTools;
import org.noear.solon.core.util.Assert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;


/**
 * 抽象子代理实现
 *
 * 增强功能：
 * - 元数据验证
 * - 元数据继承
 *
 * @author bai
 * @since 3.9.5
 */
@Setter
@Getter
public abstract class AbsSubagent implements Subagent {
    private static final Logger LOG = LoggerFactory.getLogger(AbsSubagent.class);

    protected final AgentRuntime rootAgent;
    private volatile ReActAgent cachedAgent;

    protected String description;
    protected SubAgentMetadata metadata;


    public AbsSubagent(AgentRuntime rootAgent) {
        this(rootAgent, null);
    }

    public AbsSubagent(AgentRuntime rootAgent, SubAgentMetadata metadata) {
        this.rootAgent = rootAgent;
        // 初始化默认元数据
        this.metadata = metadata == null ? createDefaultMetadata() : metadata;

        ReActAgent.Builder builder = ReActAgent.of(rootAgent.getChatModel());
        if (rootAgent.getProperties().isTeamsEnabled()){
            builder.defaultToolAdd(AgentTeamsTools.getInstance());
        }
        builder.instruction(getDefaultSystemPrompt());
        builder.defaultInterceptorAdd(rootAgent.getSummarizationInterceptor());
        // 应用元数据中的属性配置到builder
        applyMetadataToBuilder(builder, metadata);
        // 应用自定义配置
        customize(builder);
        cachedAgent = builder.build();
    }

    @Override
    public final String getDescription() {
        if (Assert.isEmpty(description)) {
            return getDefaultDescription();
        } else {
            return description;
        }
    }


    @Override
    public SubAgentMetadata getMetadata() {
        return metadata;
    }

    public String name(){
        return cachedAgent.name();
    }

    /**
     * 创建默认元数据（由子类重写）
     *
     * 此方法在构造函数开始时调用，在 cachedAgent 构建之前。
     * 元数据的名称会被用于配置 cachedAgent。
     *
     * @return 默认元数据
     */
    protected SubAgentMetadata createDefaultMetadata() {
        return SubAgentMetadata.builder()
                .description(getDefaultDescription())      // 设置描述
                .build();
    }


    /**
     * 获取内置描述（由子类实现）
     */
    protected abstract String getDefaultDescription();

    /**
     * 获取内置系统提示词（由子类实现）
     */
    protected abstract String getDefaultSystemPrompt();

    /**
     * 定制（由子类实现）
     */
    protected abstract void customize(ReActAgent.Builder builder);

    /**
     * 应用元数据属性到 Builder
     *
     * 将 metadata 中的配置属性应用到 ReActAgent.Builder，
     * 包括最大步数、工具列表、技能列表等。
     *
     * @param builder ReActAgent 构建器
     * @param metadata 元数据配置
     */
    protected void applyMetadataToBuilder(ReActAgent.Builder builder, SubAgentMetadata metadata) {
        if (metadata == null) {
            return;
        }
        builder.name(metadata.getName());

        // 应用最大步数（优先级：maxSteps > maxTurns > 默认值）
        if (metadata.getMaxSteps() != null && metadata.getMaxSteps() > 0) {
            builder.maxSteps(metadata.getMaxSteps());
            LOG.debug("使用 maxSteps 配置: {}", metadata.getMaxSteps());
        } else if (metadata.hasMaxTurns()) {
            // maxTurns 作为备选
            builder.maxSteps(metadata.getMaxTurns());
            LOG.debug("使用 maxTurns 作为 maxSteps: {}", metadata.getMaxTurns());
        } else {
            // 使用默认步数 30（与主 Agent 保持一致）
            builder.maxSteps(30);
            LOG.debug("使用默认 maxSteps: 30");
        }

        // 应用最大步数自动扩展（默认启用）
        if (metadata.getMaxStepsAutoExtensible() != null) {
            builder.maxStepsExtensible(metadata.getMaxStepsAutoExtensible());
            LOG.debug("maxStepsAutoExtensible: {}", metadata.getMaxStepsAutoExtensible());
        } else {
            // 默认启用步数自动扩展
            builder.maxStepsExtensible(true);
            LOG.debug("使用默认 maxStepsAutoExtensible: true");
        }
    }



    protected void refresh() {
        Utils.locker().lock();
        try {
            this.cachedAgent = null;
        } finally {
            Utils.locker().unlock();
        }
    }


    @Override
    public AgentResponse call(String __cwd, String sessionId, Prompt prompt) throws Throwable {
        AgentSession session = rootAgent.getSession(sessionId);

        return cachedAgent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__cwd", __cwd);
                })
                .call();
    }

    @Override
    public Flux<AgentChunk> stream(String __cwd, String sessionId, Prompt prompt) {
        AgentSession session = rootAgent.getSession(sessionId);

        return cachedAgent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__cwd", __cwd);
                })
                .stream();
    }
}