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

import lombok.Getter;

/**
 * Loop 任务单轮执行结果。
 *
 * <p>用于承载单代理执行的结构化结果。</p>
 *
 * <p>Goal 完成判定由 {@code goal_update(complete)} 工具调用驱动，
 * 通过 {@link GoalState#getStatus()} 状态检测。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
public class LoopExecutionResult {
    private final boolean submitted;
    private final boolean completed;
    private final boolean hasToolCalls;   // R6: 真实工具调用标记
    private final long tokensUsed;        // G2: 本轮回合 token 消耗

    private final String finalResult;
    private final String errorMessage;

    private LoopExecutionResult(boolean submitted, boolean completed, boolean hasToolCalls,
                                long tokensUsed,
                                String finalResult, String errorMessage) {
        this.submitted = submitted;
        this.completed = completed;
        this.hasToolCalls = hasToolCalls;
        this.tokensUsed = tokensUsed;
        this.finalResult = finalResult;
        this.errorMessage = errorMessage;
    }

    /**
     * 从执行结果文本构建（R6: 启发式降级）
     */
    public static LoopExecutionResult fromText(String text) {
        boolean hasToolCalls = text != null && text.length() > 20
                && !text.startsWith("error:") && !text.equals("ok");
        long tokens = text != null ? Math.max(1, text.length() / 4) : 0;
        return new LoopExecutionResult(
                true,
                text != null,
                hasToolCalls,
                tokens,
                text,
                null);
    }

    /**
     * 从执行结果构建（R6: 使用真实工具调用和 token 信息）
     *
     * @param hasToolCalls 本轮是否有工具调用
     * @param tokensUsed   本轮消耗的 token 数
     * @param text         执行结果文本
     */
    public static LoopExecutionResult fromExecution(boolean hasToolCalls, long tokensUsed, String text) {
        return new LoopExecutionResult(
                true,
                text != null,
                hasToolCalls,
                tokensUsed,
                text,
                null);
    }

    public static LoopExecutionResult submittedOnly() {
        return new LoopExecutionResult(true, false, false, 0, null, null);
    }

    public static LoopExecutionResult error(String errorMessage) {
        return new LoopExecutionResult(true, true, false, 0,
                errorMessage != null ? "error: " + errorMessage : "error", errorMessage);
    }


}