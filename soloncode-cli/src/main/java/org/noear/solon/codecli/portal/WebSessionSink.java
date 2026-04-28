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
package org.noear.solon.codecli.portal;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 端 Session 级 SSE 广播管理器
 *
 * <p>为每个 Web 会话维护一个 {@link Sinks.Many} 广播通道，
 * 用于将 Loop 定时任务（及其他后台推送）产生的流式数据实时推送到前端 EventSource 客户端。
 *
 * <p>典型数据流：
 * <pre>
 *   LoopScheduler.onTrigger()
 *     → WebController.executeLoopTask() [异步]
 *       → Flux 流元素 emit 到 sessionSink.tryEmitNext(line)
 *         → 前端 /chat/events?sessionId=xxx 订阅的 SSE 流收到数据
 *           → handleSSEData() 渲染到聊天界面
 * </pre>
 *
 * @author noear 2026/4/28 created
 */
public class WebSessionSink {
    /**
     * sessionId → Sinks.Many<String>（多播模式，支持多个前端页面同时订阅同一 session）
     */
    private final Map<String, Sinks.Many<String>> sinkMap = new ConcurrentHashMap<>();

    /**
     * 获取（或创建）指定 session 的广播 Sink
     */
    public Sinks.Many<String> getOrCreate(String sessionId) {
        return sinkMap.computeIfAbsent(sessionId,
                k -> Sinks.many().multicast().onBackpressureBuffer(256));
    }

    /**
     * 将指定 session 的 Sink 转为 Flux（供 SSE 端点订阅）
     */
    public Flux<String> asFlux(String sessionId) {
        return getOrCreate(sessionId).asFlux();
    }

    /**
     * 向指定 session 广播一条 SSE 数据行
     */
    public void emit(String sessionId, String line) {
        Sinks.Many<String> sink = sinkMap.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(line);
        }
    }

    /**
     * 关闭并移除指定 session 的 Sink（会话销毁时调用）
     */
    public void close(String sessionId) {
        Sinks.Many<String> sink = sinkMap.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }
}
