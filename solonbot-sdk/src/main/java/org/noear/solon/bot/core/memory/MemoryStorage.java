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

import java.util.List;
import java.util.Map;

/**
 * 记忆存储接口
 *
 * 定义记忆持久化的抽象接口，支持不同的存储后端：
 * - 文件存储（FileMemoryStore）
 * - 数据库存储（未来扩展：MySQL, PostgreSQL, MongoDB）
 * - 内存存储（未来扩展：Redis, Memcached）
 * - 云存储（未来扩展：OSS, S3）
 *
 * @author bai
 * @since 3.9.5
 */
public interface MemoryStorage {

    /**
     * 存储记忆
     *
     * @param memory 记忆对象
     */
    void store(Memory memory);

    /**
     * 加载指定记忆
     *
     * @param memoryId 记忆ID
     * @param type 记忆类型
     * @return 记忆对象，不存在返回 null
     */
    Memory load(String memoryId, Memory.MemoryType type);

    /**
     * 删除指定记忆
     *
     * @param memoryId 记忆ID
     * @param type 记忆类型
     */
    void delete(String memoryId, Memory.MemoryType type);

    /**
     * 加载指定类型的所有记忆
     *
     * @param type 记忆类型
     * @return 记忆列表
     */
    List<Memory> loadAll(Memory.MemoryType type);

    /**
     * 清空所有记忆
     */
    void clear();

    /**
     * 获取统计信息
     *
     * @return 统计信息映射（类型 -> 数量）
     */
    Map<String, Integer> getStats();
}
