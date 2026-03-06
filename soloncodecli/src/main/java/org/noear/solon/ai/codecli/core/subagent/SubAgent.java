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
 * 定义专门的任务执行代理接口，支持同步和流式执行。
 *
 * @author bai
 * @since 3.9.5
 */
public interface SubAgent {

    /**
     * 获取代理名称
     *
     * @return 代理名称
     */
    String name();

    /**
     * 获取代理角色描述
     *
     * @return 角色描述
     */
    String role();


    String model();

    /**
     * 获取代理类型
     *
     * @return 子代理类型
     */
    SubAgentType getType();

    /**
     * 获取配置
     *
     * @return 子代理配置
     */
    SubAgentConfig getConfig();

    /**
     * 执行任务（同步）
     *
     * @param prompt 任务提示
     * @return 执行结果
     * @throws Throwable 执行异常
     */
    AgentResponse execute(Prompt prompt) throws Throwable;

    /**
     * 执行任务（流式）
     *
     * @param prompt 任务提示
     * @return 流式结果
     */
    Flux<AgentChunk> stream(Prompt prompt);
}
