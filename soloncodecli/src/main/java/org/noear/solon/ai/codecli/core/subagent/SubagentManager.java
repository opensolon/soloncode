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

import org.noear.solon.ai.codecli.core.AgentKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子代理管理器
 *
 * @author bai
 * @since 3.9.5
 */
public class SubagentManager {
    private static final Logger LOG = LoggerFactory.getLogger(SubagentManager.class);

    private final Map<String, Subagent> subagentMap = new ConcurrentHashMap<>();
    private final AgentKernel mainAgent;

    // Agents 池（类似 skillPool）
    private final Map<String, String> agentPools = new ConcurrentHashMap<>();

    public SubagentManager(AgentKernel mainAgent) {
        this.mainAgent = mainAgent;

        // 初始化时导出所有 Subagent 的提示词到 .soloncode/agents 目录
        exportAllPrompts();
    }

    /**
     * 导出所有 Subagent 的提示词到 .soloncode/agents 目录
     */
    private void exportAllPrompts() {
        LOG.info("开始导出 Subagent 提示词到 .soloncode/agents 目录...");

        // 创建所有已实现的 Subagent 实例（仅用于导出提示词）
        addSubagent(ExploreSubagent::new);
        addSubagent(DevPlanSubagent::new);
        addSubagent(BashSubagent::new);
        addSubagent(SolonGuideSubagent::new);
        addSubagent(GeneralPurposeSubagent::new);

        LOG.info("Subagent 提示词导出完成");
    }

    public void addSubagent(SubagentFactory factory) {
        Subagent subagent1 = factory.create(mainAgent);
        subagentMap.put(subagent1.getName(), subagent1);
    }

    /**
     * 获取指定名称的子代理（支持自定义代理）
     */
    public Subagent getAgent(String agentName) {
        // 1. 首先尝试作为预定义类型
        Subagent subagent = subagentMap.get(agentName);
        if (subagent == null) {
            return null;
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
    private synchronized Subagent createDynamicAgent(String agentName, String customPrompt) {
        DynamicSubagent dynamicAgent = new DynamicSubagent(mainAgent, agentName, customPrompt);

        subagentMap.put(agentName, dynamicAgent);

        return dynamicAgent;
    }


    /**
     * 检查子代理是否已注册
     */
    public boolean hasAgent(String agentName) {
        return subagentMap.containsKey(agentName);
    }

    /**
     * 获取所有已注册的子代理
     */
    public Collection<Subagent> getAgents() {
        return subagentMap.values();
    }

    /**
     * 清除所有子代理
     */
    public void clear() {
        subagentMap.clear();
        LOG.info("已清除所有子代理");
    }

    /**
     * 注册自定义 agents 池
     *
     * @param alias      池别名，如 "agents"
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
                LOG.debug("从 {} 读取 agent '{}' 提示词，长度: {} 字符", filePath, agentName, content.length());
                return content;
            } catch (Throwable e) {
                LOG.warn("读取 agent '{}' 提示词失败: {}, error={}", agentName, filePath, e.getMessage());
            }
        }
        return null;
    }
}