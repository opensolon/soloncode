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
package org.noear.solon.bot.core.message;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 代理消息（统一消息接口）
 *
 * 合并了原 Message<T> 和 AgentMessage 的功能：
 * - 类型安全（泛型支持）
 * - 链式构建（Builder 模式）
 * - 可扩展（元数据支持）
 * - 消息选项（持久化、TTL、重试等）
 *
 * @param <T> 消息内容类型
 * @author bai
 * @since 3.9.5
 */
@Getter
public class AgentMessage<T> {

    private final String id;
    private final String from;
    private final String to;
    private final String type;
    private final T content;
    private final long timestamp;
    private final Map<String, String> metadata;

    private AgentMessage(Builder<T> builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.from = builder.from != null ? builder.from : "system";
        this.to = builder.to != null ? builder.to : "*";
        this.type = builder.type != null ? builder.type : "notification";
        this.content = builder.content;
        this.timestamp = System.currentTimeMillis();
        this.metadata = builder.metadata;
    }

    /**
     * 创建消息的便捷方法
     *
     * @param content 消息内容
     * @param <T> 内容类型
     * @return Builder
     */
    public static <T> Builder<T> of(T content) {
        return new Builder<T>().content(content);
    }

    /**
     * 创建空消息
     */
    public static <T> Builder<T> empty() {
        return new Builder<T>();
    }


    /**
     * 获取元数据值
     *
     * @param key 键
     * @return 元数据值，不存在返回 null
     */
    public String getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * 获取元数据值（带默认值）
     *
     * @param key 键
     * @param defaultValue 默认值
     * @return 元数据值
     */
    public String getMetadata(String key, String defaultValue) {
        return metadata.getOrDefault(key, defaultValue);
    }

    /**
     * 获取整数类型的元数据
     */
    public int getIntMetadata(String key, int defaultValue) {
        String value = getMetadata(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 获取布尔类型的元数据
     */
    public boolean getBooleanMetadata(String key, boolean defaultValue) {
        String value = getMetadata(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    // ========== 消息选项便捷方法（兼容旧的 AgentMessage 功能）==========

    /**
     * 是否持久化消息
     */
    public boolean isPersistent() {
        return getBooleanMetadata("persistent", false);
    }

    /**
     * 获取消息 TTL（毫秒）
     */
    public int getTtl() {
        return getIntMetadata("ttl", 60000);
    }

    /**
     * 获取重试次数
     */
    public int getRetryTimes() {
        return getIntMetadata("retryTimes", 0);
    }

    /**
     * 获取重试延迟（毫秒）
     */
    public long getRetryDelay() {
        return getIntMetadata("retryDelay", 1000);
    }

    /**
     * 是否需要确认
     */
    public boolean isRequireAck() {
        return getBooleanMetadata("requireAck", false);
    }

    /**
     * 获取自定义头信息
     */
    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey().startsWith("header:")) {
                headers.put(entry.getKey().substring(7), entry.getValue());
            }
        }
        return headers;
    }

    /**
     * 获取特定的头信息
     */
    public String getHeader(String key) {
        return getMetadata("header:" + key);
    }

    /**
     * 获取特定的头信息（带默认值）
     */
    public String getHeader(String key, String defaultValue) {
        return getMetadata("header:" + key, defaultValue);
    }

    /**
     * 转换为 Builder
     */
    public Builder<T> toBuilder() {
        return new Builder<T>()
            .id(id)
            .from(from)
            .to(to)
            .type(type)
            .content(content)
            .metadata(metadata);
    }

    /**
     * 构建器
     */
    public static class Builder<T> {
        private String id;
        private String from = "system";
        private String to = "*";
        private String type = "notification";
        private T content;
        private final Map<String, String> metadata = new HashMap<>();

        public Builder<T> id(String id) {
            this.id = id;
            return this;
        }

        public Builder<T> from(String from) {
            this.from = from;
            return this;
        }

        public Builder<T> from(Enum<?> from) {
            this.from = from.name().toLowerCase();
            return this;
        }

        public Builder<T> to(String to) {
            this.to = to;
            return this;
        }

        public Builder<T> to(Enum<?> to) {
            this.to = to.name().toLowerCase();
            return this;
        }

        public Builder<T> type(String type) {
            this.type = type;
            return this;
        }

        public Builder<T> type(Enum<?> type) {
            this.type = type.name().toLowerCase();
            return this;
        }

        public Builder<T> content(T content) {
            this.content = content;
            return this;
        }

        public Builder<T> metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder<T> metadata(Map<String, String> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }


        /**
         * 设置是否持久化
         */
        public Builder<T> persistent(boolean persistent) {
            return metadata("persistent", String.valueOf(persistent));
        }

        /**
         * 设置消息 TTL（毫秒）
         */
        public Builder<T> ttl(int ttl) {
            return metadata("ttl", String.valueOf(ttl));
        }

        /**
         * 设置重试配置
         */
        public Builder<T> retry(int times, long delay) {
            return metadata("retryTimes", String.valueOf(times))
                    .metadata("retryDelay", String.valueOf(delay));
        }

        /**
         * 设置是否需要确认
         */
        public Builder<T> requireAck(boolean requireAck) {
            return metadata("requireAck", String.valueOf(requireAck));
        }

        /**
         * 添加自定义头信息
         */
        public Builder<T> header(String key, String value) {
            return metadata("header:" + key, value);
        }

        /**
         * 批量添加头信息
         */
        public Builder<T> headers(Map<String, String> headers) {
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    metadata("header:" + entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public AgentMessage<T> build() {
            return new AgentMessage<>(this);
        }
    }


}
