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
package org.noear.solon.bot.core.event;

import lombok.Getter;

import java.util.UUID;

/**
 * 事件对象
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public class AgentEvent {
    private final String eventId;
    private final AgentEventType eventType;
    private final String customEventTypeCode; // 用于自定义事件类型
    private final Object payload;
    private final EventMetadata metadata;
    private final long timestamp;

    /**
     * 使用枚举类型创建事件
     */
    public AgentEvent(AgentEventType eventType, Object payload, EventMetadata metadata) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.customEventTypeCode = eventType.getCode();
        this.payload = payload;
        this.metadata = metadata;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 使用自定义事件类型代码创建事件
     */
    public AgentEvent(String customEventTypeCode, Object payload, EventMetadata metadata) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = AgentEventType.CUSTOM;
        this.customEventTypeCode = customEventTypeCode;
        this.payload = payload;
        this.metadata = metadata;
        this.timestamp = System.currentTimeMillis();
    }


    /**
     * 获取事件类型代码（向后兼容）
     */
    public String getEventTypeCode() {
        if (eventType == AgentEventType.CUSTOM && customEventTypeCode != null) {
            return customEventTypeCode;
        }
        return eventType.getCode();
    }


    @Override
    public String toString() {
        return "AgentEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType='" + getEventTypeCode() + '\'' +
                ", payload=" + payload +
                ", timestamp=" + timestamp +
                '}';
    }
}
