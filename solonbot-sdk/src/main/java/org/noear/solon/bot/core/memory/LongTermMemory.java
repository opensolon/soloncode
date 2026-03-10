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

import lombok.Getter;

import java.util.List;
import java.util.ArrayList;

/**
 * 长期记忆（重要结论）
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public class LongTermMemory extends Memory {
    private String summary;      // 摘要内容
    private String sourceAgent;  // 来源代理
    private List<String> tags;   // 标签（用于检索）
    private double importance;   // 重要性评分 (0.0-1.0)

    /**
     * 无参构造函数（用于反序列化）
     */
    public LongTermMemory() {
        super(MemoryType.LONG_TERM, 7 * 24 * 3600_000L);
        this.summary = "";
        this.sourceAgent = "";
        this.tags = new ArrayList<>();
        this.importance = 0.5;
    }

    /**
     * 构造函数
     *
     * @param summary 摘要内容
     * @param sourceAgent 来源代理
     * @param tags 标签列表
     */
    public LongTermMemory(String summary, String sourceAgent, List<String> tags) {
        super(MemoryType.LONG_TERM, 7 * 24 * 3600_000L); // 默认7天TTL
        this.summary = summary;
        this.sourceAgent = sourceAgent;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.importance = 0.5; // 默认重要性
    }

    /**
     * 构造函数（自定义TTL）
     */
    public LongTermMemory(String summary, String sourceAgent, List<String> tags, long ttl) {
        super(MemoryType.LONG_TERM, ttl);
        this.summary = summary;
        this.sourceAgent = sourceAgent;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.importance = 0.5;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setSourceAgent(String sourceAgent) {
        this.sourceAgent = sourceAgent;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public void setImportance(double importance) {
        this.importance = Math.max(0.0, Math.min(1.0, importance));
    }
}
