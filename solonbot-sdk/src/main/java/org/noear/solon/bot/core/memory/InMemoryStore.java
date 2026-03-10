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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存存储实现（示例）
 *
 * 特点：
 * - 纯内存存储，无持久化
 * - 高性能读写
 * - 适合测试和临时场景
 * - 应用重启后数据丢失
 *
 * 使用示例：
 * <pre>
 * // 创建内存存储
 * MemoryStore store = new MemoryStore(new InMemoryStore());
 *
 * // 或使用工厂方法
 * Map<String, Object> config = new HashMap<>();
 * config.put("maxSize", 1000);
 * MemoryStore store = MemoryStore.create("memory", config);
 * </pre>
 *
 * @author bai
 * @since 3.9.5
 */
public class InMemoryStore implements MemoryStorage {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryStore.class);

    // 按 ID 索引
    private final Map<String, Memory> memoryById = new ConcurrentHashMap<>();

    // 按类型索引
    private final Map<Memory.MemoryType, Map<String, Memory>> memoryByType = new ConcurrentHashMap<>();

    // 配置
    private final int maxSize;

    /**
     * 构造函数（使用默认配置）
     */
    public InMemoryStore() {
        this(10000); // 默认最大 10000 条
    }

    /**
     * 构造函数（自定义配置）
     *
     * @param maxSize 最大记忆数量
     */
    public InMemoryStore(int maxSize) {
        this.maxSize = maxSize;
        LOG.info("内存存储初始化完成: maxSize={}", maxSize);
    }

    @Override
    public void store(Memory memory) {
        if (memory == null || memory.getId() == null) {
            return;
        }

        // 检查数量限制
        if (memoryById.size() >= maxSize && !memoryById.containsKey(memory.getId())) {
            LOG.warn("内存存储已满，无法存储新记忆: maxSize={}", maxSize);
            return;
        }

        // 存储到 ID 索引
        memoryById.put(memory.getId(), memory);

        // 存储到类型索引
        memoryByType.computeIfAbsent(memory.getType(), k -> new ConcurrentHashMap<>())
                   .put(memory.getId(), memory);

        LOG.debug("记忆已存储: type={}, id={}", memory.getType(), memory.getId());
    }

    @Override
    public Memory load(String memoryId, Memory.MemoryType type) {
        Memory memory = memoryById.get(memoryId);

        // 检查类型匹配
        if (memory != null && memory.getType() == type) {
            return memory;
        }

        return null;
    }

    @Override
    public void delete(String memoryId, Memory.MemoryType type) {
        Memory removed = memoryById.remove(memoryId);

        if (removed != null) {
            Map<String, Memory> typeMap = memoryByType.get(type);
            if (typeMap != null) {
                typeMap.remove(memoryId);
            }

            LOG.debug("记忆已删除: type={}, id={}", type, memoryId);
        }
    }

    @Override
    public List<Memory> loadAll(Memory.MemoryType type) {
        Map<String, Memory> typeMap = memoryByType.get(type);

        if (typeMap == null) {
            return Collections.emptyList();
        }

        return typeMap.values().stream()
                .filter(memory -> !memory.isExpired())
                .collect(Collectors.toList());
    }

    @Override
    public void clear() {
        int count = memoryById.size();
        memoryById.clear();
        memoryByType.clear();

        LOG.info("清空记忆: count={}", count);
    }

    @Override
    public Map<String, Integer> getStats() {
        Map<String, Integer> stats = new HashMap<>();

        for (Memory.MemoryType type : Memory.MemoryType.values()) {
            Map<String, Memory> typeMap = memoryByType.get(type);
            if (typeMap != null) {
                stats.put(type.name().toLowerCase(), typeMap.size());
            }
        }

        return stats;
    }

    /**
     * 获取当前记忆数量
     *
     * @return 记忆数量
     */
    public int size() {
        return memoryById.size();
    }

    /**
     * 检查是否为空
     *
     * @return 是否为空
     */
    public boolean isEmpty() {
        return memoryById.isEmpty();
    }
}
