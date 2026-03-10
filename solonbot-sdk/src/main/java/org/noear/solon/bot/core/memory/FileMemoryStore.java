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
package org.noear.solon.bot.core.memory;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 文件存储实现（默认实现）
 *
 * 特点：
 * - POJO 对象 + JSON 序列化
 * - 直接文件管理
 * - 无复杂封装
 * - 轻量级、无依赖
 *
 * @author bai
 * @since 3.9.5
 */
public class FileMemoryStore implements MemoryStorage {
    private static final Logger LOG = LoggerFactory.getLogger(FileMemoryStore.class);

    private final String storePath;

    /**
     * 构造函数
     *
     * @param workDir 工作目录
     */
    public FileMemoryStore(String workDir) {
        this.storePath = workDir + File.separator + ".soloncode" + File.separator + "memory" + File.separator;

        // 确保目录存在
        File dir = new File(storePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        LOG.info("文件记忆存储初始化完成: path={}", storePath);
    }

    @Override
    public void store(Memory memory) {
        if (memory == null || memory.getId() == null) {
            return;
        }
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
    }

    @Override
    public Memory load(String memoryId, Memory.MemoryType type) {
        try {
            String typeDir = storePath + type.name().toLowerCase() + File.separator;
            String filePath = typeDir + memoryId + ".json";
            File file = new File(filePath);

            if (!file.exists()) {
                return null;
            }

            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return ONode.deserialize(json, getTypeClass(type));

        } catch (Exception e) {
            LOG.debug("记忆加载失败: id={}, error={}", memoryId, e.getMessage());
            return null;
        }
    }

    @Override
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

    @Override
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
                    Memory memory = ONode.deserialize(json, getTypeClass(type));

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

    @Override
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

    @Override
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
     * 根据记忆类型获取对应的 Class
     */
    private Class<? extends Memory> getTypeClass(Memory.MemoryType type) {
        switch (type) {
            case SHORT_TERM:
                return ShortTermMemory.class;
            case LONG_TERM:
                return LongTermMemory.class;
            case KNOWLEDGE:
                return KnowledgeMemory.class;
            default:
                throw new IllegalArgumentException("未知的记忆类型: " + type);
        }
    }
}
