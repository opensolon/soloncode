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
import org.noear.solon.ai.codecli.core.AgentKernel;
import org.noear.solon.ai.codecli.core.AgentProperties;
import org.noear.solon.ai.codecli.core.PoolManager;
import org.noear.solon.ai.codecli.core.memory.SharedMemoryManager;
import org.noear.solon.ai.codecli.core.event.EventBus;
import org.noear.solon.ai.codecli.core.message.MessageChannel;
import org.noear.solon.ai.codecli.core.teams.SharedTaskList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final AgentKernel mainCodeAgent;
    private final ChatModel chatModel;
    private final AgentProperties properties;  // 添加 properties 配置

    // Agent Teams 增强功能
    private final SharedMemoryManager sharedMemoryManager;
    private final EventBus eventBus;
    private final MessageChannel messageChannel;
    private final SharedTaskList sharedTaskList;

    // Agents 池（类似 skillPool）
    private final Map<String, String> agentPools = new ConcurrentHashMap<>();

    public SubAgentManager(AgentSessionProvider sessionProvider,
                           String workDir,
                           PoolManager poolManager,
                           AgentKernel mainCodeAgent,
                           ChatModel chatModel,
                           SharedMemoryManager sharedMemoryManager,
                           EventBus eventBus,
                           MessageChannel messageChannel,
                           SharedTaskList sharedTaskList) {
        this(sessionProvider, workDir, poolManager, mainCodeAgent, chatModel, null,
                sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
    }

    public SubAgentManager(AgentSessionProvider sessionProvider,
                           String workDir,
                           PoolManager poolManager,
                           AgentKernel mainCodeAgent,
                           ChatModel chatModel,
                           AgentProperties properties,
                           SharedMemoryManager sharedMemoryManager,
                           EventBus eventBus,
                           MessageChannel messageChannel,
                           SharedTaskList sharedTaskList) {
        this.sessionProvider = sessionProvider;
        this.workDir = workDir;
        this.poolManager = poolManager;
        this.mainCodeAgent = mainCodeAgent;
        this.chatModel = chatModel;
        this.properties = properties;
        this.sharedMemoryManager = sharedMemoryManager;
        this.eventBus = eventBus;
        this.messageChannel = messageChannel;
        this.sharedTaskList = sharedTaskList;

        // 初始化时导出所有 SubAgent 的提示词到 .soloncode/agents 目录
        exportAllPrompts();
    }

    /**
     * 导出所有 SubAgent 的提示词到 .soloncode/agents 目录
     */
    private void exportAllPrompts() {
        LOG.info("开始导出 SubAgent 提示词到 .soloncode/agents 目录...");

        // 创建所有已实现的 SubAgent 实例（仅用于导出提示词）
        SubAgentType[] implementedTypes = {
                SubAgentType.EXPLORE,
                SubAgentType.PLAN,
                SubAgentType.BASH,
                SubAgentType.GENERAL_PURPOSE,
                SubAgentType.SOLON_CODE_GUIDE
        };

        for (SubAgentType type : implementedTypes) {
            try {
                SubAgentConfig config = new SubAgentConfig(type);
                config.setWorkDir(workDir);

                AbstractSubAgent agent = createAgentInstance(type, config);
                agent.exportSystemPrompt(workDir);
            } catch (Throwable e) {
                LOG.warn("导出 SubAgent '{}' 提示词失败: {}", type.getCode(), e.getMessage());
            }
        }

        LOG.info("SubAgent 提示词导出完成");
    }

    /**
     * 创建 SubAgent 实例（不初始化）
     */
    private AbstractSubAgent createAgentInstance(SubAgentType type, SubAgentConfig config) {
        switch (type) {
            case EXPLORE:
                return new ExploreSubAgent(config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
            case PLAN:
                return new PlanSubAgent(config, sessionProvider, workDir,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
            case BASH:
                return new BashSubAgent(config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
            case SOLON_CODE_GUIDE:
                return new SolonCodeGuideSubAgent(config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
            case GENERAL_PURPOSE:
                return new GeneralPurposeSubAgent(config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
            default:
                throw new IllegalArgumentException("Unsupported SubAgent type: " + type.getCode());
        }
    }

    /**
     * 获取指定类型的子代理
     */
    public SubAgent getAgent(SubAgentType type) {
        return agents.computeIfAbsent(type, this::createAgent);
    }

    /**
     * 获取指定名称的子代理（支持自定义代理）
     *
     * @param agentName 代理名称（可以是预定义类型代码或自定义代理名称）
     * @return 子代理实例
     * @throws IllegalArgumentException 如果代理不存在
     */
    public SubAgent getAgent(String agentName) {
        // 1. 首先尝试作为预定义类型
        try {
            SubAgentType type = SubAgentType.fromCode(agentName);
            return getAgent(type);
        } catch (IllegalArgumentException e) {
            // 不是预定义类型，继续尝试动态代理
        }

        // 2. 尝试从 agentPools 创建动态代理
        String customPrompt = readAgentPrompt(agentName);
        if (customPrompt == null) {
            throw new IllegalArgumentException("未找到代理: " + agentName);
        }

        // 3. 创建动态代理
        return createDynamicAgent(agentName, customPrompt);
    }

    /**
     * 创建动态子代理
     */
    private synchronized SubAgent createDynamicAgent(String agentName, String customPrompt) {
        // 使用自定义代码创建一个虚拟的 SubAgentType
        SubAgentConfig config = new SubAgentConfig(agentName, "自定义代理: " + agentName);

        // 应用自定义 model 配置（针对动态代理）
        if (properties != null && properties.subAgentModels != null) {
            String customModel = properties.subAgentModels.get(agentName);
            if (customModel != null && !customModel.isEmpty()) {
                config.setModelName(customModel);
                LOG.info("动态代理 '{}' 使用自定义模型: {}", agentName, customModel);
            }
        }

        LOG.info("创建动态子代理: {}", agentName);

        DynamicSubAgent dynamicAgent = new DynamicSubAgent(
                config,
                sessionProvider,
                workDir,
                poolManager,
                mainCodeAgent,
                customPrompt
        );

        dynamicAgent.initialize(chatModel);

        // 缓存动态代理（使用名称作为 key）
        agents.put(config.getType(), dynamicAgent);

        LOG.info("动态子代理 '{}' 创建完成", agentName);
        return dynamicAgent;
    }


    /**
     * 创建子代理
     */
    private SubAgent createAgent(SubAgentType type) {
        LOG.info("创建子代理: {}", type.getCode());

        SubAgentConfig config = new SubAgentConfig(type);

        // 应用自定义 model 配置
        if (properties != null && properties.subAgentModels != null) {
            String customModel = properties.subAgentModels.get(type.getCode());
            if (customModel != null && !customModel.isEmpty()) {
                config.setModelName(customModel);
                LOG.info("SubAgent '{}' 使用自定义模型: {}", type.getCode(), customModel);
            }
        }

        switch (type) {
            case EXPLORE:
                ExploreSubAgent exploreAgent = new ExploreSubAgent(config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
                exploreAgent.initialize(chatModel);
                return exploreAgent;

            case PLAN:
                PlanSubAgent planAgent = new PlanSubAgent(config, sessionProvider, workDir,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
                planAgent.initialize(chatModel);
                return planAgent;

            case BASH:
                BashSubAgent bashAgent = new BashSubAgent(config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
                bashAgent.initialize(chatModel);
                return bashAgent;

            case SOLON_CODE_GUIDE:
                SolonCodeGuideSubAgent guideAgent = new SolonCodeGuideSubAgent(config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
                guideAgent.initialize(chatModel);
                return guideAgent;

            case GENERAL_PURPOSE:
            default:
                GeneralPurposeSubAgent generalAgent = new GeneralPurposeSubAgent(
                        config, sessionProvider, workDir, poolManager,
                        sharedMemoryManager, eventBus, messageChannel, sharedTaskList);
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

    /**
     * 获取共享记忆管理器
     */
    public SharedMemoryManager getSharedMemoryManager() {
        return sharedMemoryManager;
    }

    /**
     * 获取事件总线
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * 获取消息通道
     */
    public MessageChannel getMessageChannel() {
        return messageChannel;
    }

    /**
     * 获取共享任务列表
     */
    public SharedTaskList getSharedTaskList() {
        return sharedTaskList;
    }

    /**
     * 注册自定义 agents 池
     *
     * @param alias 池别名，如 "agents"
     * @param agentsPath agents 目录路径，可以是绝对路径或相对路径
     */
    public void agentPool(String alias, String agentsPath) {
        if (alias == null || alias.isEmpty()) {
            return;
        }

        if (agentsPath == null || agentsPath.isEmpty()) {
            return;
        }

        // 确保路径以 / 结尾
        if (!agentsPath.endsWith("/") && !agentsPath.endsWith(File.separator)) {
            agentsPath = agentsPath + "/";
        }

        agentPools.put(alias, agentsPath);
        LOG.info("已注册 agents 池: {} -> {}", alias, agentsPath);
    }


    /**
     * 获取所有已注册的 agents 池
     */
    public Map<String, String> getAgentPools() {
        return new ConcurrentHashMap<>(agentPools);
    }

    /**
     * 根据别名获取 agents 池路径
     */
    public String getAgentPoolPath(String alias) {
        return agentPools.get(alias);
    }

    /**
     * 查找指定类型的 SubAgent 的提示词文件
     * 优先级：自定义池 > 默认池
     *
     * @param type SubAgent 类型
     * @return 提示词文件的完整路径，如果不存在返回 null
     */
    public String findAgentPromptFile(SubAgentType type) {
        return findAgentPromptFile(type.getCode());
    }

    /**
     * 查找指定名称的 Agent 提示词文件
     * 优先级：自定义池 > 默认池
     *
     * @param agentName Agent 名称（不含 .md 扩展名）
     * @return 提示词文件的完整路径，如果不存在返回 null
     */
    public String findAgentPromptFile(String agentName) {
        String fileName = agentName + ".md";

        // 1. 优先从自定义池查找
        for (Map.Entry<String, String> entry : agentPools.entrySet()) {
            String poolPath = entry.getValue();
            File file = new File(poolPath, fileName);
            if (file.exists()) {
                LOG.debug("在池 '{}' 中找到 agent 提示词: {}", entry.getKey(), file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }

        // 2. 从默认池查找
        String defaultPath = ".soloncode" + File.separator + "agents" + File.separator + fileName;
        File defaultFile = new File(defaultPath);
        if (defaultFile.exists()) {
            LOG.debug("在默认 agents 目录中找到提示词: {}", defaultFile.getAbsolutePath());
            return defaultFile.getAbsolutePath();
        }

        LOG.debug("未找到 agent '{}' 的提示词文件", agentName);
        return null;
    }

    /**
     * 从指定池读取 SubAgent 提示词
     *
     * @param type SubAgent 类型
     * @return 提示词内容，如果不存在返回 null
     */
    public String readAgentPrompt(SubAgentType type) {
        return readAgentPrompt(type.getCode());
    }

    /**
     * 从指定池读取 Agent 提示词
     *
     * @param agentName Agent 名称
     * @return 提示词内容，如果不存在返回 null
     */
    public String readAgentPrompt(String agentName) {
        String filePath = findAgentPromptFile(agentName);
        if (filePath != null) {
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(filePath));
                String content = new String(bytes, StandardCharsets.UTF_8);
                LOG.info("从 {} 读取 agent '{}' 提示词，长度: {} 字符", filePath, agentName, content.length());
                return content;
            } catch (Throwable e) {
                LOG.warn("读取 agent '{}' 提示词失败: {}, error={}", agentName, filePath, e.getMessage());
            }
        }
        return null;
    }

    /**
     * AgentDir：agents 目录信息
     */
    public static class AgentDir {
        public final String aliasPath;
        public final String realPath;
        public final String description;

        public AgentDir(String aliasPath, String realPath, String description) {
            this.aliasPath = aliasPath;
            this.realPath = realPath;
            this.description = description;
        }

        public String getAliasPath() {
            return aliasPath;
        }

        public String getRealPath() {
            return realPath;
        }

        public String getDescription() {
            return description;
        }
    }
}
