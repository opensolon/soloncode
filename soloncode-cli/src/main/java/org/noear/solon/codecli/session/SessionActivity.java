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
package org.noear.solon.codecli.session;

import org.noear.solon.ai.agent.AgentSession;
import reactor.core.Disposable;

/**
 * 会话运行状态查询。
 *
 * <p>这些状态来自 AgentSession.attrs()，属于会话运行时事实，不属于 Web 网关实现。
 * WebGate 保留兼容委托，但命令、队列和其他入口应优先依赖本类。</p>
 */
public final class SessionActivity {
    /** 会话属性：输入已被接纳但 Agent 流尚未完成注册。 */
    public static final String ATTR_INPUT_ADMITTING = "session.input.admitting";

    private SessionActivity() {
    }

    /**
     * 流未建立（或已失败）时返回 false。
     */
    public static boolean hasActiveStream(AgentSession session) {
        if (session == null) {
            return false;
        }
        Object slot = session.attrs().get("disposable");
        return slot instanceof Disposable.Composite && !((Disposable.Composite) slot).isDisposed();
    }

    /**
     * 判断输入是否已占用本会话的启动闩。
     *
     * <p>该状态只表示输入正在从受理阶段进入任务/命令处理阶段，不代表已有 Agent 流。</p>
     */
    public static boolean isInputAdmitting(AgentSession session) {
        return session != null && Boolean.TRUE.equals(session.attrs().get(ATTR_INPUT_ADMITTING));
    }

    /**
     * 当前会话是否处于输入受理或 Agent 流执行状态。
     */
    public static boolean isBusy(AgentSession session) {
        return isInputAdmitting(session) || hasActiveStream(session);
    }
}
