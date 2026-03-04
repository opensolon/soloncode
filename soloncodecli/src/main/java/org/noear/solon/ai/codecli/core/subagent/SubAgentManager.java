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

import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.ai.codecli.core.PoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子代理管理器
 *
 * @author bai
 * @since 3.9.5
 */
public class SubAgentManager {
    private static final Logger LOG = LoggerFactory.getLogger(SubAgentManager.class);

    private final Map<SubAgentType, SubAgent> agents = new ConcurrentHashMap<>();
    private final AgentSessionProvider sessionProvider;
    private final String workDir;
    private final PoolManager poolManager;
    private final CodeAgent mainCodeAgent;
    private final ChatModel chatModel;

    public SubAgentManager(AgentSessionProvider sessionProvider,
                           String workDir,
                           PoolManager poolManager,
                           CodeAgent mainCodeAgent,
                           ChatModel chatModel) {
        this.sessionProvider = sessionProvider;
        this.workDir = workDir;
        this.poolManager = poolManager;
        this.mainCodeAgent = mainCodeAgent;
        this.chatModel = chatModel;
    }

    /**
     * 获取指定类型的子代理
     */
    public SubAgent getAgent(SubAgentType type) {
        return agents.computeIfAbsent(type, this::createAgent);
    }

    /**
     * 创建子代理
     */
    private SubAgent createAgent(SubAgentType type) {
        LOG.info("创建子代理: {}", type.getCode());

        SubAgentConfig config = new SubAgentConfig(type);

        switch (type) {
            case EXPLORE:
                ExploreSubAgent exploreAgent = new ExploreSubAgent(config, sessionProvider, workDir, poolManager);
                exploreAgent.initialize(chatModel);
                return exploreAgent;

            case PLAN:
                PlanSubAgent planAgent = new PlanSubAgent(config, sessionProvider, workDir);
                planAgent.initialize(chatModel);
                return planAgent;

            case BASH:
                BashSubAgent bashAgent = new BashSubAgent(config, sessionProvider, workDir, poolManager);
                bashAgent.initialize(chatModel);
                return bashAgent;

            case GENERAL_PURPOSE:
            default:
                GeneralPurposeSubAgent generalAgent = new GeneralPurposeSubAgent(
                        config, sessionProvider, workDir, poolManager, mainCodeAgent);
                generalAgent.initialize(chatModel);
                return generalAgent;
        }
    }

    /**
     * 检查子代理是否已注册
     */
    public boolean hasAgent(SubAgentType type) {
        return agents.containsKey(type);
    }

    /**
     * 获取所有已注册的子代理
     */
    public Map<SubAgentType, SubAgent> getAllAgents() {
        return new ConcurrentHashMap<>(agents);
    }

    /**
     * 清除所有子代理
     */
    public void clear() {
        agents.clear();
        LOG.info("已清除所有子代理");
    }
}
