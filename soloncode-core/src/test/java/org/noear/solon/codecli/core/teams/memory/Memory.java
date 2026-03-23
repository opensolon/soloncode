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
package org.noear.solon.codecli.core.teams.memory;



import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * 记忆基类
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
@Setter
public abstract class Memory  {
    protected String id;
    protected MemoryType type;
    protected long timestamp;
    protected long ttl; // Time to live (毫秒)
    protected Map<String, Object> metadata;

    /**
     * 无参构造函数（用于反序列化）
     */
    public Memory() {
        this.id = UUID.randomUUID().toString();
        this.type = MemoryType.WORKING;
        this.ttl = -1;
        this.timestamp = System.currentTimeMillis();
        this.metadata = new HashMap<>();
    }

    /**
     * 构造函数
     */
    public Memory(MemoryType type, long ttl) {
        id = UUID.randomUUID().toString();
        this.type = type;
        this.ttl = ttl;
        this.timestamp = System.currentTimeMillis();
        this.metadata = new HashMap<>();
    }

    /**
     * 记忆类型枚举
     */
    public enum MemoryType {
        /**
         * 工作记忆（极短期，仅内存）
         */
        WORKING,

        /**
         * 短期记忆（会话级别）
         */
        SHORT_TERM,

        /**
         * 长期记忆（跨会话）
         */
        LONG_TERM,

        /**
         * 知识库（持久化）
         */
        KNOWLEDGE
    }

    /**
     * 检查记忆是否已过期
     *
     * @return true表示已过期，false表示未过期
     */
    public boolean isExpired() {
        if (ttl <= 0) {
            return false; // TTL <= 0 表示永不过期
        }
        long elapsed = System.currentTimeMillis() - timestamp;
        return elapsed >= ttl; // 使用 >= 而不是 >，确保边界条件正确
    }

    /**
     * 设置元数据（用于反序列化）
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    /**
     * 添加元数据
     */
    public void putMetadata(String key, Object value) {
        if (this.metadata == null){
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }

    /**
     * 获取元数据值
     */
    public Object getMetadata(String key) {
        return this.metadata.get(key);
    }
}
