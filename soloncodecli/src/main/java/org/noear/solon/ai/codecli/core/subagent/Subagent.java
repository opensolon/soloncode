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
package org.noear.solon.ai.codecli.core.subagent;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 子代理接口
 *
 * @author bai
 * @since 3.9.5
 */
public interface Subagent {
    /**
     * 获取类型
     */
    String getType();

    /**
     * 获取描述
     */
    String getDescription();

    /**
     * 获取系统提示词
     */
    String getSystemPrompt();

    /**
     * 执行任务（同步）
     *
     * @param prompt 任务提示词
     * @return 执行结果
     */
    AgentResponse execute(String sessionId, String __cwd, Prompt prompt) throws Throwable;

    /**
     * 执行任务（流式）
     *
     * @param prompt 任务提示词
     * @return 流式结果
     */
    Flux<AgentChunk> stream(String sessionId, String __cwd, Prompt prompt);
}