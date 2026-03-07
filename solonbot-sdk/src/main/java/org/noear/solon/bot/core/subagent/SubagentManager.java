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

import org.noear.snack4.ONode;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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

        // 添加预置的智能体类型
        addSubagent(new ExploreSubagent(mainAgent));
        addSubagent(new PlanSubagent(mainAgent));
        addSubagent(new GeneralPurposeSubagent(mainAgent));

        addSubagent(new BashSubagent(mainAgent));
        addSubagent(new SolonGuideSubagent(mainAgent));
    }


    public void addSubagent(Subagent subagent) {
        subagentMap.putIfAbsent(subagent.getType(), subagent);
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
     */
    public void agentPool(String dir) {
        if (Assert.isEmpty(dir)) {
            return;
        }

        Path path = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            LOG.warn("代理池目录不存在: {}", dir);
            return;
        }

        try (Stream<Path> stream = Files.list(path)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".md"))
                    .collect(Collectors.toList());

            for (Path file : files) {
                try {
                    String fileName = file.getFileName().toString();
                    List<String> fullContent = Files.readAllLines(file, StandardCharsets.UTF_8);

                    // 解析文件：拆分元数据和 Prompt
                    SubagentFile parsed = parseSubagentFile(fullContent);

                    String subagentType = (parsed.name != null) ? parsed.name : fileName.substring(0, fileName.length() - 3);

                    AbsSubagent subagent = (AbsSubagent) subagentMap.computeIfAbsent(subagentType,
                            k -> new GeneralPurposeSubagent(mainAgent, k));

                    // 设置解析后的属性
                    subagent.setDescription(parsed.description);
                    subagent.setSystemPrompt(parsed.systemPrompt);
                    subagent.refresh();
                } catch (IOException e) {
                    LOG.error("读取代理文件失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            LOG.error("扫描代理池目录失败: {}", dir, e);
        }
    }

    public static class SubagentFile {
        public String name;
        public String description;
        public Collection<String> tools;
        public String model;
        public Map<String, Object> metadata;

        public String systemPrompt;
    }

    public SubagentFile parseSubagentFile(List<String> lines) {
        SubagentFile result = new SubagentFile();

        if (lines == null || lines.isEmpty()) {
            result.systemPrompt = "";
            result.description = "自定义代理";
            return result;
        }

        // 规范：第一行必须是 --- 且不能有前导空行
        if ("---".equals(lines.get(0).trim())) {
            StringBuilder yamlBuilder = new StringBuilder();
            StringBuilder bodyBuilder = new StringBuilder();
            int secondSeparatorIndex = -1;

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (secondSeparatorIndex == -1) {
                    if ("---".equals(line.trim())) {
                        secondSeparatorIndex = i;
                    } else {
                        yamlBuilder.append(line).append("\n");
                    }
                } else {
                    bodyBuilder.append(line).append("\n");
                }
            }

            // 成功找到闭合标识
            if (secondSeparatorIndex != -1) {
                try {
                    Yaml yaml = new Yaml();
                    Object yamlData = yaml.load(yamlBuilder.toString());
                    // 使用 ONode.loadObj 性能更好且更直接
                    ONode oNode = ONode.ofBean(yamlData);

                    if (oNode.isObject()) {
                        // 1. 存入全量元数据
                        result.metadata = oNode.toBean(Map.class);

                        // 2. 提取标准字段
                        result.name = oNode.get("name").getString();
                        result.description = oNode.get("description").getString();
                        result.model = oNode.get("model").getString();

                        // 3. 灵活解析 tools (数组或逗号分隔)
                        if (oNode.hasKey("tools")) {
                            ONode tNode = oNode.get("tools");
                            if (tNode.isArray()) {
                                result.tools = tNode.toBean(List.class);
                            } else {
                                result.tools = Arrays.stream(tNode.getString().split(","))
                                        .map(String::trim)
                                        .filter(s -> !s.isEmpty())
                                        .collect(Collectors.toList());
                            }
                        }
                    }
                    result.systemPrompt = bodyBuilder.toString().trim();
                } catch (Exception e) {
                    LOG.error("YAML 格式异常，全文本回退", e);
                    result.systemPrompt = String.join("\n", lines).trim();
                }
            } else {
                // 有开头无结尾，视为普通文本
                result.systemPrompt = String.join("\n", lines).trim();
            }
        } else {
            // 第一行不是 ---，严格作为普通 Body 处理
            result.systemPrompt = String.join("\n", lines).trim();
        }

        // 描述兜底
        if (Assert.isEmpty(result.description) && !result.systemPrompt.isEmpty()) {
            // 取第一行并移除 Markdown 标题符
            String firstLine = result.systemPrompt.split("\\R")[0];
            result.description = firstLine.replace("#", "").trim();
        }

        if (Assert.isEmpty(result.description)) {
            result.description = "自定义代理";
        }

        return result;
    }
}