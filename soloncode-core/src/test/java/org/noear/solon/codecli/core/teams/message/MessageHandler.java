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
package org.noear.solon.codecli.core.teams.message;

import java.util.concurrent.CompletableFuture;

/**
 * 消息处理器接口
 *
 * 支持类型安全的泛型消息处理
 *
 * @author bai
 * @since 3.9.5
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * 处理消息（类型安全）
     *
     * @param message 泛型消息对象
     * @param <T> 消息内容类型
     * @return 处理结果（用于响应）
     */
    <T> CompletableFuture<Object> handle(AgentMessage<T> message);
}
