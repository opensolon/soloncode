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
import org.noear.solon.ai.harness.session.SessionStatus;

/**
 * 会话运行状态查询。
 *
 * <p>运行流与待审批这两项事实统一读 {@link SessionStatus}（harness 门面，一次读全）；
 * 本类只额外叠加宿主自己的 {@link #ATTR_INPUT_ADMITTING} 标志——
 * 那是「输入已受理、流尚未注册」的窗口态，harness 无从得知，故只能留在宿主侧。</p>
 */
public final class SessionActivity {
    /** 会话属性：输入已被接纳但 Agent 流尚未完成注册。 */
    public static final String ATTR_INPUT_ADMITTING = "session.input.admitting";

    private SessionActivity() {
    }

    /**
     * 是否有活跃的 Agent 运行流（不含 HITL 挂起、不含输入受理窗口）。
     */
    public static boolean hasActiveStream(AgentSession session) {
        return SessionStatus.of(session).isBusy();
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

    /**
     * 会话是否「占着」：忙碌、待审批，或输入正在受理。
     *
     * <p>与 {@link #isBusy(AgentSession)} 的差别是包含待审批：HITL 挂起时引擎里没有运行流，
     * 但显然不能被新输入顶掉，故「能不能接新输入」要用本方法判断。</p>
     *
     * @since 4.2.0
     */
    public static boolean isOccupied(AgentSession session) {
        if (isInputAdmitting(session)) {
            return true;
        }

        SessionStatus status = SessionStatus.of(session);
        return status.isOccupied();
    }
}
