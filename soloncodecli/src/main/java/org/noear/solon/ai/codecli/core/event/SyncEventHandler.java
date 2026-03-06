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
package org.noear.solon.ai.codecli.core.event;

import java.util.concurrent.CompletableFuture;

/**
 * 同步事件处理器（简化版）
 *
 * @author bai
 * @since 3.9.5
 */
@FunctionalInterface
public interface SyncEventHandler extends EventHandler {
    /**
     * 同步处理事件
     *
     * @param event 事件对象
     */
    void handleSync(AgentEvent event);

    @Override
    default CompletableFuture<Result> handle(AgentEvent event) {
        try {
            handleSync(event);
            return CompletableFuture.completedFuture(Result.success());
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Result.failure(e.getMessage()));
        }
    }
}
