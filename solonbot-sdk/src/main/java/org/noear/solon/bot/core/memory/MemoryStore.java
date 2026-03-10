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

import java.util.List;
import java.util.Map;

/**
 * 记忆存储门面
 *
 * 提供统一的存储接口，支持多种存储后端：
 * - 默认：文件存储（FileMemoryStore）
 * - 未来扩展：数据库（MySQL, PostgreSQL, MongoDB）
 * - 未来扩展：缓存（Redis, Memcached）
 * - 未来扩展：云存储（OSS, S3）
 *
 * 使用方式：
 * <pre>
 * // 1. 使用默认文件存储
 * MemoryStore store = new MemoryStore("/path/to/workdir");
 *
 * // 2. 使用自定义存储实现
 * MemoryStore store = new MemoryStore(new CustomMemoryStore());
 *
 * // 3. 通过配置指定存储类型（未来扩展）
 * MemoryStore store = MemoryStore.create("mysql", config);
 * </pre>
 *
 * @author bai
 * @since 3.9.5
 */
public class MemoryStore {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryStore.class);

    private final MemoryStorage storage;

    /**
     * 构造函数（使用默认文件存储）
     *
     * @param workDir 工作目录
     */
    public MemoryStore(String workDir) {
        this(new FileMemoryStore(workDir));
        LOG.info("记忆存储初始化: type=file, path={}", workDir);
    }

    /**
     * 构造函数（使用自定义存储实现）
     *
     * @param storage 存储实现
     */
    public MemoryStore(MemoryStorage storage) {
        if (storage == null) {
            throw new IllegalArgumentException("存储实现不能为 null");
        }
        this.storage = storage;
        LOG.info("记忆存储初始化: type={}", storage.getClass().getSimpleName());
    }

    /**
     * 创建存储实例（工厂方法，未来扩展）
     *
     * @param type 存储类型（file, memory, mysql, postgresql, mongodb, redis 等）
     * @param config 配置参数
     * @return 存储实例
     */
    public static MemoryStore create(String type, Map<String, Object> config) {
        if (type == null || type.trim().isEmpty()) {
            type = "file";
        }

        switch (type.toLowerCase()) {
            case "file":
                String workDir = config != null ? (String) config.getOrDefault("workDir", "./work") : "./work";
                return new MemoryStore(workDir);

            case "memory":
                int maxSize = config != null ? (int) config.getOrDefault("maxSize", 10000) : 10000;
                return new MemoryStore(new InMemoryStore(maxSize));

            // 未来扩展：数据库存储
            // case "mysql":
            //     return new MemoryStore(new MySqlMemoryStore(config));

            // 未来扩展：Redis 存储
            // case "redis":
            //     return new MemoryStore(new RedisMemoryStore(config));

            default:
                throw new IllegalArgumentException("不支持的存储类型: " + type + "，支持的类型: file, memory");
        }
    }

    /**
     * 存储 Memory
     *
     * @param memory 记忆对象
     */
    public void store(Memory memory) {
        storage.store(memory);
    }

    /**
     * 加载 Memory
     *
     * @param memoryId 记忆ID
     * @param type 记忆类型
     * @return 记忆对象，不存在返回 null
     */
    public Memory load(String memoryId, Memory.MemoryType type) {
        return storage.load(memoryId, type);
    }

    /**
     * 删除 Memory
     *
     * @param memoryId 记忆ID
     * @param type 记忆类型
     */
    public void delete(String memoryId, Memory.MemoryType type) {
        storage.delete(memoryId, type);
    }

    /**
     * 加载指定类型的所有记忆
     *
     * @param type 记忆类型
     * @return 记忆列表
     */
    public List<Memory> loadAll(Memory.MemoryType type) {
        return storage.loadAll(type);
    }

    /**
     * 清空所有记忆
     */
    public void clear() {
        storage.clear();
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息映射（类型 -> 数量）
     */
    public Map<String, Integer> getStats() {
        return storage.getStats();
    }

    /**
     * 获取底层存储实现
     *
     * @return 存储实现
     */
    public MemoryStorage getStorage() {
        return storage;
    }
}
