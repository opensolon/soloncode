/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.command.builtin;

/**
 * Goal 状态枚举 — 8 态显式状态机
 *
 * <pre>
 * CREATING → PURSUING → ACHIEVED → TERMINATED
 *                      → UNMET → TERMINATED
 *                      → BUDGET_LIMITED → TERMINATED
 *                      → BLOCKED → PURSUING (用户恢复)
 *           ↔ PAUSED (可恢复)
 * </pre>
 *
 * @author noear
 * @since 3.9.1
 */
public enum GoalStatus {
    /**
     * 目标已设置，等待首轮执行
     */
    CREATING,

    /**
     * 正在执行中
     */
    PURSUING,

    /**
     * 用户主动暂停
     */
    PAUSED,

    /**
     * 评估器判定达成
     */
    ACHIEVED,

    /**
     * 模型放弃或评估器判定不可达
     */
    UNMET,

    /**
     * 迭代/token/时间预算耗尽
     */
    BUDGET_LIMITED,

    /**
     * 模型声明阻塞（等待用户输入或外部条件解除）
     */
    BLOCKED,

    /**
     * 已终结（最终状态，可清理）
     */
    TERMINATED;

    /**
     * 是否为活跃状态（调度器应继续执行）
     */
    public boolean isActive() {
        return this == PURSUING;
    }

    /**
     * 是否为可暂停状态
     */
    public boolean isPausable() {
        return this == PURSUING;
    }

    /**
     * 是否为可恢复状态（PAUSED → PURSUING 或 BLOCKED → PURSUING）
     */
    public boolean isResumable() {
        return this == PAUSED || this == BLOCKED;
    }

    /**
     * 是否为终态（不可再转换）
     */
    public boolean isTerminal() {
        return this == ACHIEVED || this == UNMET
                || this == BUDGET_LIMITED || this == TERMINATED;
    }

    /**
     * 是否为需要用户关注的异常状态
     */
    public boolean isAbnormal() {
        return this == UNMET || this == BUDGET_LIMITED || this == BLOCKED;
    }
}
