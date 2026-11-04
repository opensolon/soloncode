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
import org.noear.solon.ai.harness.session.InputGate;
import org.noear.solon.ai.harness.session.SessionStatus;

/**
 * 会话运行状态查询（宿主侧唯一口径）。
 *
 * <p>三项事实（运行流、待审批卡、输入受理窗口）全部由 harness 提供：
 * {@link SessionStatus} 一次读全，{@link InputGate} 把「已受理、流尚未登记」的窗口态
 * 也纳入占用判定。宿主不再自己维护任何运行态 attrs 键——此前那份私有标记
 * 既不跨端通用，也漏进了 {@link SessionStatus} 的视野，不占位的端就判定不到。</p>
 *
 * <p>本类保留为宿主内部的语义命名（{@code busy} / {@code activeStream} 在 Web 各处高频使用），
 * 实现全部委托给 harness。判断「能不能接新输入」请直接用
 * {@code engine.status(session).isOccupied()}（含 HITL 挂起，桌面端即如此）。</p>
 */
public final class SessionActivity {
    private SessionActivity() {
    }

    /**
     * 是否有活跃的 Agent 运行流（不含 HITL 挂起、不含输入受理窗口）。
     */
    public static boolean hasActiveStream(AgentSession session) {
        return SessionStatus.of(session).isBusy();
    }

    /**
     * 输入是否已被受理、但 Agent 流尚未完成注册。
     *
     * @see InputGate
     */
    public static boolean isInputAdmitting(AgentSession session) {
        return InputGate.isAdmitting(session);
    }

    /**
     * 是否「占着会话」：有活跃流、有输入正在受理，或有待审批的挂起卡。
     *
     * <p><b>受理判定必须用它，不要用 {@link #isBusy}。</b>二者的差别正是 HITL：
     * 审批挂起时引擎里没有运行流（{@code isBusy} 为 false），但此时开新回合会顶掉挂起任务——
     * 新的运行流与旧的未决 trace 并存，用户点「同意」时续流会被运行态认领拒绝（表现为
     * 「审批点了没反应」），或造成两条流交叉写快照。</p>
     */
    public static boolean isOccupied(AgentSession session) {
        return SessionStatus.of(session).isOccupied();
    }

    /**
     * 是否有待审批的挂起卡。
     *
     * <p>用于把「占用」进一步细分：忙（可排队/可插话）与审批挂起（应拒绝并提示）
     * 的期望行为不同，不能混为一谈。</p>
     */
    public static boolean hasPendingHitl(AgentSession session) {
        return SessionStatus.of(session).hasPendingHitl();
    }

    /**
     * 当前会话是否处于输入受理或 Agent 流执行状态（<b>不含</b> HITL 挂起）。
     *
     * <p>用于「是否已有流在跑」这类流生命周期判定；判断能否接新输入请用 {@link #isOccupied}。</p>
     */
    public static boolean isBusy(AgentSession session) {
        return SessionStatus.of(session).isBusy() || isInputAdmitting(session);
    }
}
