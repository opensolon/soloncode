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

/**
 * 短期记忆（工作上下文）
 *
 * @author bai
 * @since 3.9.5
 */
@Setter
@Getter
public class ShortTermMemory extends Memory {
    private String agentId;      // 创建者代理ID
    private String context;      // 上下文内容
    private String taskId;       // 关联任务ID

    /**
     * 无参构造函数（用于反序列化）
     */
    public ShortTermMemory() {
        super(MemoryType.SHORT_TERM, 3600_000L);
        this.agentId = "";
        this.context = "";
        this.taskId = "";
    }

    /**
     * 构造函数
     *
     * @param agentId 创建者代理ID
     * @param context 上下文内容
     * @param taskId 关联任务ID
     */
    public ShortTermMemory(String agentId, String context, String taskId) {
        super(MemoryType.SHORT_TERM, 3600_000L); // 默认1小时TTL
        this.agentId = agentId;
        this.context = context;
        this.taskId = taskId;
    }

    /**
     * 构造函数（自定义TTL）
     */
    public ShortTermMemory(String agentId, String context, String taskId, long ttl) {
        super(MemoryType.SHORT_TERM, ttl);
        this.agentId = agentId;
        this.context = context;
        this.taskId = taskId;
    }

}
