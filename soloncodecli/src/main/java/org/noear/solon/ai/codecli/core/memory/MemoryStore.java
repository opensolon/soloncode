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
package org.noear.solon.ai.codecli.core.memory;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 记忆持久化存储
 *
 * 类似 opencode 的简单结构体设计：
 * - POJO 对象 + JSON 序列化
 * - 直接文件管理
 * - 无复杂封装
 *
 * @author bai
 * @since 3.9.5
 */
public class MemoryStore {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryStore.class);

    private final String storePath;

    /**
     * 构造函数
     *
     * @param workDir 工作目录
     */
    public MemoryStore(String workDir) {
        this.storePath = workDir + File.separator + ".soloncode" + File.separator + "memory" + File.separator;

        // 确保目录存在
        File dir = new File(storePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        LOG.info("记忆存储初始化完成: path={}", storePath);
    }

    /**
     * 存储 Memory
     *
     * @param memory 记忆对象
     */
    public void store(Memory memory) {
        if (memory == null || memory.getId() == null) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String typeDir = storePath + memory.getType().name().toLowerCase() + File.separator;
                File dir = new File(typeDir);
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                String filePath = typeDir + memory.getId() + ".json";
                String json = ONode.serialize(memory);

                Files.write(Paths.get(filePath), json.getBytes(StandardCharsets.UTF_8));

                LOG.debug("记忆已存储: type={}, id={}", memory.getType(), memory.getId());

            } catch (Exception e) {
                LOG.warn("记忆存储失败: id={}, error={}", memory.getId(), e.getMessage());
            }
        });
    }

    /**
     * 加载 Memory
     *
     * @param memoryId 记忆ID
     * @param type 记忆类型
     * @return 记忆对象，不存在返回 null
     */
    public Memory load(String memoryId, Memory.MemoryType type) {
        try {
            String typeDir = storePath + type.name().toLowerCase() + File.separator;
            String filePath = typeDir + memoryId + ".json";
            File file = new File(filePath);

            if (!file.exists()) {
                return null;
            }

            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return fromJson(json, type);

        } catch (Exception e) {
            LOG.debug("记忆加载失败: id={}, error={}", memoryId, e.getMessage());
            return null;
        }
    }

    /**
     * 删除 Memory
     *
     * @param memoryId 记忆ID
     * @param type 记忆类型
     */
    public void delete(String memoryId, Memory.MemoryType type) {
        try {
            String typeDir = storePath + type.name().toLowerCase() + File.separator;
            String filePath = typeDir + memoryId + ".json";
            File file = new File(filePath);

            if (file.exists()) {
                file.delete();
                LOG.debug("记忆已删除: type={}, id={}", type, memoryId);
            }

        } catch (Exception e) {
            LOG.warn("记忆删除失败: id={}, error={}", memoryId, e.getMessage());
        }
    }

    /**
     * 加载指定类型的所有记忆
     *
     * @param type 记忆类型
     * @return 记忆列表
     */
    public List<Memory> loadAll(Memory.MemoryType type) {
        try {
            String typeDir = storePath + type.name().toLowerCase() + File.separator;
            File dir = new File(typeDir);

            if (!dir.exists()) {
                return Collections.emptyList();
            }

            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) {
                return Collections.emptyList();
            }

            List<Memory> memories = new ArrayList<>();

            for (File file : files) {
                try {
                    String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    Memory memory = fromJson(json, type);

                    // 跳过已过期的记忆
                    if (memory != null && !memory.isExpired()) {
                        memories.add(memory);
                    }

                } catch (Exception e) {
                    LOG.debug("加载记忆文件失败: file={}, error={}", file.getName(), e.getMessage());
                }
            }

            LOG.info("加载记忆: type={}, count={}", type, memories.size());
            return memories;

        } catch (Exception e) {
            LOG.warn("加载记忆失败: type={}, error={}", type, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 清空所有记忆
     */
    public void clear() {
        try {
            File dir = new File(storePath);
            File[] typeDirs = dir.listFiles(File::isDirectory);

            if (typeDirs != null) {
                int count = 0;
                for (File typeDir : typeDirs) {
                    File[] files = typeDir.listFiles((d, name) -> name.endsWith(".json"));
                    if (files != null) {
                        for (File file : files) {
                            if (file.delete()) {
                                count++;
                            }
                        }
                    }
                }
                LOG.info("清空记忆: count={}", count);
            }

        } catch (Exception e) {
            LOG.warn("清空记忆失败: error={}", e.getMessage());
        }
    }

    /**
     * 获取统计信息
     */
    public Map<String, Integer> getStats() {
        Map<String, Integer> stats = new HashMap<>();

        try {
            File dir = new File(storePath);
            File[] typeDirs = dir.listFiles(File::isDirectory);

            if (typeDirs != null) {
                for (File typeDir : typeDirs) {
                    File[] files = typeDir.listFiles((d, name) -> name.endsWith(".json"));
                    if (files != null) {
                        stats.put(typeDir.getName(), files.length);
                    }
                }
            }

        } catch (Exception e) {
            LOG.warn("获取统计信息失败: error={}", e.getMessage());
        }

        return stats;
    }




    /**
     * 从 JSON 反序列化为 Memory
     */
    private Memory fromJson(String json, Memory.MemoryType type) {
        try {
            // 使用反射和字符串解析（避免依赖外部 JSON 库）
            Map<String, String> fields = parseJsonFields(json);

            String id = fields.get("id");
            long timestamp = Long.parseLong(fields.getOrDefault("timestamp", "0"));
            long ttl = Long.parseLong(fields.getOrDefault("ttl", "0"));

            Memory memory;

            if (type == Memory.MemoryType.SHORT_TERM) {
                String agentId = unescapeJson(fields.getOrDefault("agentId", ""));
                String context = unescapeJson(fields.getOrDefault("context", ""));
                String taskId = unescapeJson(fields.getOrDefault("taskId", ""));

                memory = new ShortTermMemory(agentId, context, taskId, ttl);
            }
            else if (type == Memory.MemoryType.LONG_TERM) {
                String summary = unescapeJson(fields.getOrDefault("summary", ""));
                String sourceAgent = unescapeJson(fields.getOrDefault("sourceAgent", ""));
                double importance = Double.parseDouble(fields.getOrDefault("importance", "0.5"));

                // 解析 tags 数组
                List<String> tags = parseJsonArray(fields.getOrDefault("tags", "[]"));

                memory = new LongTermMemory(summary, sourceAgent, tags, ttl);
                ((LongTermMemory) memory).setImportance(importance);
            }
            else { // KNOWLEDGE
                String subject = unescapeJson(fields.getOrDefault("subject", ""));
                String content = unescapeJson(fields.getOrDefault("content", ""));
                String category = unescapeJson(fields.getOrDefault("category", ""));

                // 解析 keywords 数组
                List<String> keywords = parseJsonArray(fields.getOrDefault("keywords", "[]"));

                memory = new KnowledgeMemory(subject, content, category, keywords);
            }

            memory.setId(id);
            memory.setTimestamp(timestamp);

            return memory;

        } catch (Exception e) {
            LOG.warn("JSON 反序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 JSON 字段（简化实现）
     */
    private Map<String, String> parseJsonFields(String json) {
        Map<String, String> fields = new HashMap<>();

        // 移除 { }
        json = json.trim();
        if (json.startsWith("{")) {
            json = json.substring(1);
        }
        if (json.endsWith("}")) {
            json = json.substring(0, json.length() - 1);
        }

        // 解析字段
        int pos = 0;
        while (pos < json.length()) {
            // 跳过空白和逗号
            while (pos < json.length() && (json.charAt(pos) == ' ' || json.charAt(pos) == ',')) {
                pos++;
            }
            if (pos >= json.length()) break;

            // 查找字段名
            int nameStart = json.indexOf("\"", pos);
            if (nameStart == -1) break;

            int nameEnd = json.indexOf("\"", nameStart + 1);
            if (nameEnd == -1) break;

            String name = json.substring(nameStart + 1, nameEnd);

            // 查找冒号
            int colonPos = json.indexOf(":", nameEnd);
            if (colonPos == -1) break;

            pos = colonPos + 1;

            // 跳过空白
            while (pos < json.length() && json.charAt(pos) == ' ') {
                pos++;
            }

            // 查找值
            if (pos >= json.length()) break;

            char valueChar = json.charAt(pos);
            String value;

            if (valueChar == '"') {
                // 字符串值
                int valueEnd = json.indexOf("\"", pos + 1);
                while (valueEnd != -1 && json.charAt(valueEnd - 1) == '\\') {
                    valueEnd = json.indexOf("\"", valueEnd + 1);
                }
                if (valueEnd == -1) break;

                value = json.substring(pos + 1, valueEnd);
                pos = valueEnd + 1;
            }
            else if (valueChar == '[') {
                // 数组值
                int valueEnd = json.indexOf("]", pos);
                if (valueEnd == -1) break;

                value = json.substring(pos, valueEnd + 1);
                pos = valueEnd + 1;
            }
            else if (valueChar == '{') {
                // 对象值
                int depth = 1;
                int valueEnd = pos + 1;
                while (valueEnd < json.length() && depth > 0) {
                    if (json.charAt(valueEnd) == '{') depth++;
                    else if (json.charAt(valueEnd) == '}') depth--;
                    valueEnd++;
                }
                if (depth != 0) break;

                value = json.substring(pos, valueEnd);
                pos = valueEnd;
            }
            else {
                // 数字或布尔值
                int valueEnd = json.indexOf(",", pos);
                if (valueEnd == -1) {
                    valueEnd = json.length();
                }
                value = json.substring(pos, valueEnd).trim();
                pos = valueEnd;
            }

            fields.put(name, value);
        }

        return fields;
    }

    /**
     * 解析 JSON 数组
     */
    private List<String> parseJsonArray(String jsonArray) {
        List<String> result = new ArrayList<>();

        // 移除 [ ]
        jsonArray = jsonArray.trim();
        if (jsonArray.startsWith("[")) {
            jsonArray = jsonArray.substring(1);
        }
        if (jsonArray.endsWith("]")) {
            jsonArray = jsonArray.substring(0, jsonArray.length() - 1);
        }

        // 分割字符串
        int start = 0;
        int depth = 0;
        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                String item = jsonArray.substring(start, i).trim();
                if (item.length() > 0) {
                    result.add(unescapeJson(item.replaceAll("^\"|\"$", "")));
                }
                start = i + 1;
            }
        }

        // 添加最后一个元素
        if (start < jsonArray.length()) {
            String item = jsonArray.substring(start).trim();
            if (item.length() > 0) {
                result.add(unescapeJson(item.replaceAll("^\"|\"$", "")));
            }
        }

        return result;
    }

    /**
     * 转义 JSON 字符串
     */
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 反转义 JSON 字符串
     */
    private String unescapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
