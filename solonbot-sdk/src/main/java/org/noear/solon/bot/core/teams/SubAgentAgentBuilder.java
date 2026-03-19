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
package org.noear.solon.bot.core.teams;

import lombok.Builder;
import lombok.Getter;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.cli.PoolManager;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.SystemPrompt;
import org.noear.solon.bot.core.event.EventBus;
import org.noear.solon.bot.core.message.MessageChannel;
import org.noear.solon.bot.core.memory.SharedMemoryManager;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.Subagent;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * SubAgent 团队构建器
 *
 * 基于 SubAgents 构建团队协作的便捷工具。支持：
 * - 添加子代理成员
 * - 配置共享内存和事件总线
 * - 自动创建必要的协作组件
 * - 构建 MainAgent 作为团队协调器
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
@Builder
public class SubAgentAgentBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(SubAgentAgentBuilder.class);

    // 必需配置
    private final ChatModel chatModel;
    private final String workDir;
    private final AgentSessionProvider sessionProvider;
    private final PoolManager poolManager;

    // 可选配置（使用 @Builder.Default 设置默认值）
    @Builder.Default
    private final List<Subagent> subAgents = new ArrayList<>();
    private final SharedMemoryManager sharedMemoryManager;
    private final EventBus eventBus;
    private final MessageChannel messageChannel;
    private final SharedTaskList taskList;
    private final SubAgentMetadata mainAgentConfig;

    /**
     * 静态工厂方法：创建构建器
     *
     * @param chatModel 聊天模型（必需）
     * @return 构建器实例
     */
    public static SubAgentAgentBuilder of(ChatModel chatModel) {
        return SubAgentAgentBuilder.builder()
                .chatModel(chatModel)
                .subAgents(new ArrayList<>())
                .build();
    }

    /**
     * 添加子代理
     *
     * @param subAgent 子代理实例
     * @return this
     */
    public SubAgentAgentBuilder addAgent(Subagent subAgent) {
        if (subAgent != null) {
            this.subAgents.add(subAgent);
            LOG.debug("添加团队成员: {}", subAgent.name());
        }
        return this;
    }

    /**
     * 添加多个子代理
     *
     * @param agents 子代理列表
     * @return this
     */
    public SubAgentAgentBuilder addAgents(List<Subagent> agents) {
        if (agents != null) {
            for (Subagent agent : agents) {
                addAgent(agent);
            }
        }
        return this;
    }

    /**
     * 从 SubagentManager 添加所有预置子代理
     *
     * @param manager 子代理管理器
     * @return this
     */
    public SubAgentAgentBuilder addAllFrom(SubagentManager manager) {
        if (manager != null) {
            addAgents(new ArrayList<>(manager.getAgents()));
        }
        return this;
    }

    /**
     * 构建团队
     *
     * 创建并配置所有必要的协作组件，返回 MainAgent 实例
     *
     * @return MainAgent 实例作为团队协调器
     * @throws IllegalStateException 如果缺少必需参数
     */
    public MainAgent build() {
        // 验证必需参数
        validateRequiredParams();

        LOG.info("开始构建 Agent 团队...");
        LOG.info("工作目录: {}", workDir);
        LOG.info("团队成员数: {}", subAgents.size());

        // 1. 创建或使用提供的协作组件
        // 注意：SharedTaskList 依赖 EventBus，所以需要先创建 EventBus
        EventBus eb = eventBus != null
                ? eventBus
                : createEventBus();

        SharedMemoryManager smm = sharedMemoryManager != null
                ? sharedMemoryManager
                : createSharedMemoryManager();

        MessageChannel mc = messageChannel != null
                ? messageChannel
                : createMessageChannel();

        SharedTaskList tl = taskList != null
                ? taskList
                : createTaskList(eb);

        // 2. 创建主代理配置（如果没有提供）
        SubAgentMetadata config = mainAgentConfig != null
                ? mainAgentConfig
                : createDefaultMainAgentConfig();

        // 3. 构建 MainAgent
        MainAgent mainAgent = new MainAgent(
                config,
                sessionProvider,
                smm,
                eb,
                mc,
                tl,
                workDir,
                poolManager
        );

        // 4. 创建 ReActAgent 并设置到 MainAgent
        try {
            // 创建一个简单的 ReActAgent 用于 MainAgent
            ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel);

            // 使用 MainAgent 的 Team Lead 指令
            String teamLeadInstruction = mainAgent.getTeamLeadInstruction();
            agentBuilder.systemPrompt(SystemPrompt.builder()
                    .instruction(teamLeadInstruction)
                    .build());
            agentBuilder.maxSteps(50);

            ReActAgent reactAgent = agentBuilder.build();

            // 设置到 MainAgent
            mainAgent.setSharedAgent(reactAgent, chatModel);

            LOG.info("Agent 团队构建成功！主代理: {}", config.getName());
        } catch (Exception e) {
            LOG.error("设置 MainAgent 共享 Agent 失败", e);
            throw new RuntimeException("构建 Agent 团队失败", e);
        }

        return mainAgent;
    }

    /**
     * 验证必需参数
     */
    private void validateRequiredParams() {
        if (workDir == null || workDir.isEmpty()) {
            throw new IllegalStateException("workDir 是必需参数，请使用 workDir() 设置");
        }
        if (sessionProvider == null) {
            throw new IllegalStateException("sessionProvider 是必需参数，请使用 sessionProvider() 设置");
        }
        if (poolManager == null) {
            throw new IllegalStateException("poolManager 是必需参数，请使用 poolManager() 设置");
        }
        if (subAgents.isEmpty()) {
            LOG.warn("团队没有成员，建议至少添加一个子代理");
        }
    }

    /**
     * 创建默认的共享内存管理器
     */
    private SharedMemoryManager createSharedMemoryManager() {
        LOG.info("创建默认 SharedMemoryManager");
        return new SharedMemoryManager(Paths.get(workDir, AgentKernel.SOLONCODE_MEMORY));
    }

    /**
     * 创建默认的事件总线
     */
    private EventBus createEventBus() {
        LOG.info("创建默认 EventBus");
        return new EventBus();
    }

    /**
     * 创建默认的消息通道
     */
    private MessageChannel createMessageChannel() {
        LOG.info("创建默认 MessageChannel");
        return new MessageChannel(workDir);
    }

    /**
     * 创建默认的共享任务列表
     */
    private SharedTaskList createTaskList(EventBus eventBus) {
        LOG.info("创建默认 SharedTaskList");
        return new SharedTaskList(eventBus);
    }

    /**
     * 创建默认的主代理配置
     */
    private SubAgentMetadata createDefaultMainAgentConfig() {
        SubAgentMetadata config = new SubAgentMetadata();
        config.setName("main-agent");
        config.setDescription("Agent 团队协调器，负责任务分发和结果汇总");
        config.setEnabled(true);

        LOG.debug("创建默认主代理配置: {}", config.getName());
        return config;
    }
}
