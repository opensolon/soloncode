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
package org.noear.solon.codecli.core.teams.event;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 事件元数据
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public class EventMetadata {
    private final String sourceAgent;    // 来源代理ID
    private final String taskId;         // 关联任务ID
    private final int priority;          // 优先级 (0-10, 默认5)
    private final Map<String, String> headers; // 扩展头

    private EventMetadata(String sourceAgent, String taskId, int priority, Map<String, String> headers) {
        this.sourceAgent = sourceAgent;
        this.taskId = taskId;
        this.priority = priority;
        this.headers = headers;
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    /**
     * Builder 模式
     */
    public static class Builder {
        private String sourceAgent;
        private String taskId;
        private int priority = 5;
        private Map<String, String> headers = new HashMap<>();

        public Builder sourceAgent(String sourceAgent) {
            this.sourceAgent = sourceAgent;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = Math.max(0, Math.min(10, priority));
            return this;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public EventMetadata build() {
            return new EventMetadata(sourceAgent, taskId, priority, headers);
        }
    }
}
