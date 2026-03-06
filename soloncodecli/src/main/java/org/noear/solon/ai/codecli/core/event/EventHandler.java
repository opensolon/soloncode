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
 * 事件处理器接口
 *
 * @author bai
 * @since 3.9.5
 */
@FunctionalInterface
public interface EventHandler {
    /**
     * 处理事件
     *
     * @param event 事件对象
     * @return 处理结果（可选，用于链式处理）
     */
    CompletableFuture<Result> handle(AgentEvent event);

    /**
     * 处理结果
     */
    class Result {
        private final boolean success;
        private final String message;
        private final Object data;

        public Result(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        public static Result success() {
            return new Result(true, null, null);
        }

        public static Result success(String message) {
            return new Result(true, message, null);
        }

        public static Result success(String message, Object data) {
            return new Result(true, message, data);
        }

        public static Result failure(String message) {
            return new Result(false, message, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public Object getData() {
            return data;
        }
    }
}
