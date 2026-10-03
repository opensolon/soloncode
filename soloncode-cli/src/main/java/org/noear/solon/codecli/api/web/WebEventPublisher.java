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
package org.noear.solon.codecli.api.web;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web 事件输出端口：终态门（done 只发一次）、流回合门（reset）、广播出口。
 *
 * <p>从 WebGate 拆出的输出职责。事件的实际下发仍走 {@link WebGate#emitToClient}，
 * 由组合根在构造时提供回调，保证测试可以覆写网关方法注入行为、协议路径不重复。</p>
 *
 * @author noear 2026/5/8 created
 */
class WebEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(WebEventPublisher.class);

    /** 会话属性：本轮 agent 流是否已向客户端发送过 done（防 interrupt + doFinally 双发） */
    static final String ATTR_STREAM_DONE_SENT = "streamDoneSent";

    private final WebGate gate;

    WebEventPublisher(WebGate gate) {
        this.gate = gate;
    }

    /**
     * 统一输出回调：注入 sessionId 后按工作区连接池下发（用户隔离广播）。
     *
     * @see WebGate#emitToClient
     */
    void emit(WorkspaceContext wsContext, String sessionId, WebEvent<?> event) {
        gate.emitToClient(wsContext, sessionId, event);
    }

    /**
     * 流级 done 只发一次；返回 true 表示本次真正发出。
     *
     * <p>覆盖正常完成、异常、用户 interrupt 等路径，避免 dispose + doFinally 与
     * interrupt 显式 ofDone 造成双 done。</p>
     */
    boolean emitDoneOnce(WorkspaceContext wsContext, AgentSession session) {
        if (session == null) {
            return false;
        }
        AtomicBoolean doneSent = (AtomicBoolean) session.attrs()
                .computeIfAbsent(ATTR_STREAM_DONE_SENT, k -> new AtomicBoolean(false));
        if (!doneSent.compareAndSet(false, true)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[WebGate] skip duplicate done for session {}", session.getSessionId());
            }
            return false;
        }
        emit(wsContext, session.getSessionId(), WebEvent.ofDone());
        return true;
    }

    /**
     * 新开流前重置 done 标记，避免上一轮 streamDoneSent 挡住本轮 done。
     */
    void resetStreamDoneSent(AgentSession session) {
        if (session == null) {
            return;
        }
        session.attrs().put(ATTR_STREAM_DONE_SENT, new AtomicBoolean(false));
    }

    /**
     * 有活跃流时不动终态门；否则复位后发 done（供输入受理失败等「流可能尚未建立」路径）。
     */
    void emitDoneGuarded(WorkspaceContext wsContext, AgentSession session) {
        if (SessionActivity.hasActiveStream(session)) return;
        resetStreamDoneSent(session);
        emitDoneOnce(wsContext, session);
    }

    /**
     * 广播原始 JSON 字符串到指定工作区连接池（系统级事件，不注入 sessionId）。
     *
     * @see WebGate#broadcastRaw
     */
    void broadcastRaw(WorkspaceContext wsContext, String json) {
        gate.broadcastRaw(wsContext, json);
    }

}
