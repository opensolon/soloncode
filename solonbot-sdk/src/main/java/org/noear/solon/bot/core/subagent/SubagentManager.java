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

import org.noear.solon.bot.core.AgentKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public SubagentManager(AgentKernel mainAgent) {
        this.mainAgent = mainAgent;

        // 添加预置的智能体类型（保持向后兼容）
        addSubagent(new ExploreSubagent(mainAgent));
        addSubagent(new PlanSubagent(mainAgent));
        addSubagent(new GeneralPurposeSubagent(mainAgent));
        addSubagent(new BashSubagent(mainAgent));
    }

    public void addSubagent(Subagent subagent) {
        subagentMap.putIfAbsent(subagent.name(), subagent);
    }

    /**
     * 获取指定名称的子代理（支持自定义代理）
     */
    public Subagent getAgent(String agentName) {
        // 1. 首先尝试作为预定义类型
        Subagent subagent = subagentMap.get(agentName);
        if (subagent == null) {
            throw new IllegalArgumentException("未找到代理: " + agentName);
        } else {
            return subagent;
        }
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
    }

    /**
     * 注册自定义 agents 池
     *
     * @param dir agents 目录路径，可以是绝对路径或相对路径
     * @param recursive 是否递归扫描子目录（用于团队成员目录）
     */
    public void agentPool(Path dir, boolean recursive) {
        if (dir == null) {
            return;
        }

        Path path = dir.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            LOG.warn("代理池目录不存在: {}", dir);
            return;
        }

        if (!Files.isDirectory(path)) {
            LOG.warn("代理池路径不是目录: {}", dir);
            return;
        }

        try {
            if (recursive) {
                // 递归扫描子目录（用于团队成员）
                Files.walk(path)
                        .filter(p -> p.toString().endsWith(".md"))
                        .forEach(file -> loadAgentFile(file));
            } else {
                // 只扫描当前目录
                try (Stream<Path> stream = Files.list(path)) {
                    List<Path> files = stream.filter(p -> p.toString().endsWith(".md"))
                            .collect(Collectors.toList());

                    for (Path file : files) {
                        loadAgentFile(file);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("扫描代理池目录失败: {}", dir, e);
        }
    }

    /**
     * 注册自定义 agents 池（不递归）
     *
     * @param dir agents 目录路径，可以是绝对路径或相对路径
     */
    public void agentPool(Path dir) {
        agentPool(dir, false);
    }

    /**
     * 从文件加载子代理定义
     *
     * @param file 代理定义文件路径
     */
    private void loadAgentFile(Path file) {
        try {
            String fileName = file.getFileName().toString();
            List<String> fullContent = Files.readAllLines(file, StandardCharsets.UTF_8);

            // 解析文件：拆分元数据和 Prompt
            SubAgentMetadata.PromptWithMetadata parsed = SubAgentMetadata.fromFileLines(fullContent);

            String subagentType = parsed.getMetadata().getName();
            if (subagentType == null || subagentType.isEmpty()) {
                subagentType = fileName.substring(0, fileName.length() - 3);
            }

            AbsSubagent subagent = (AbsSubagent) subagentMap.computeIfAbsent(subagentType,
                    k -> new GeneralPurposeSubagent(mainAgent, parsed.getMetadata(), parsed.getPrompt()));

            // 设置解析后的属性
            subagent.setDescription(parsed.getMetadata().getDescription());

            LOG.debug("加载子代理: {} 从 {}", subagentType, file);
        } catch (IOException e) {
            LOG.error("读取代理文件失败: {}", file, e);
        }
    }
}