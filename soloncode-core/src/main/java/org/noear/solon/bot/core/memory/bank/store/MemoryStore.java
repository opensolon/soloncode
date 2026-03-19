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
package org.noear.solon.bot.core.memory.bank.store;

import org.noear.solon.bot.core.memory.bank.Observation;

/**
 * MemoryBank 持久化存储接口
 * <p>
 * 用于将 Observation 持久化到文件或数据库
 *
 * @author bai
 * @since 3.9.5
 */
public interface MemoryStore {

    /**
     * 存储 Observation
     *
     * @param observation 观察
     */
    void store(Observation observation);

    /**
     * 加载 Observation
     *
     * @param id Observation ID
     * @return Observation，不存在返回 null
     */
    Observation load(String id);

    /**
     * 删除 Observation
     *
     * @param id Observation ID
     */
    void delete(String id);

    /**
     * 加载所有 Observation
     *
     * @return Observation 列表
     */
    java.util.List<Observation> loadAll();

    /**
     * 清空所有 Observation
     */
    void clear();

    /**
     * 获取统计信息
     *
     * @return 统计信息映射
     */
    java.util.Map<String, Object> getStats();
}
