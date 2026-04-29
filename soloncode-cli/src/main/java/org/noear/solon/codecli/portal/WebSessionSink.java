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

import org.noear.solon.web.sse.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 端 Session 级 SSE 广播管理器
 *
 * <p>为每个 Web 会话维护一个 {@link SseEmitter}，
 * 用于将 Loop 定时任务（及其他后台推送）产生的流式数据实时推送到前端 EventSource 客户端。
 *
 * @author noear 2026/4/28 created
 */
public class WebSessionSink {
    private static final Logger LOG = LoggerFactory.getLogger(WebSessionSink.class);

    /**
     * sessionId → SseEmitter（每个 session 一个长连接）
     */
    private final Map<String, SseEmitter> emitterMap = new ConcurrentHashMap<>();

    /**
     * 创建并注册指定 session 的 SseEmitter
     *
     * <p>如果该 session 已有 emitter（比如前端重连），先关闭旧的再创建新的。
     */
    public SseEmitter createEmitter(String sessionId) {
        // 关闭旧连接
        SseEmitter old = emitterMap.get(sessionId);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) { }
        }

        SseEmitter emitter = new SseEmitter(0L); // 0 = 永不超时

        emitter.onCompletion(() -> {
            LOG.debug("SSE emitter completed for session: {}", sessionId);
            emitterMap.remove(sessionId, emitter);
        });
        emitter.onError((e) -> {
            LOG.debug("SSE emitter error for session: {}", sessionId);
            emitterMap.remove(sessionId, emitter);
        });

        emitterMap.put(sessionId, emitter);
        return emitter;
    }

    /**
     * 向指定 session 广播一条 SSE 数据
     */
    public void emit(String sessionId, String data) {
        SseEmitter emitter = emitterMap.get(sessionId);
        if (emitter != null) {
            try {
                emitter.send(data);
            } catch (Exception e) {
                LOG.warn("Failed to emit SSE data for session {}: {}", sessionId, e.getMessage());
                emitterMap.remove(sessionId, emitter);
            }
        }
    }

    /**
     * 关闭并移除指定 session 的 Emitter
     */
    public void close(String sessionId) {
        SseEmitter emitter = emitterMap.remove(sessionId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) { }
        }
    }
}
