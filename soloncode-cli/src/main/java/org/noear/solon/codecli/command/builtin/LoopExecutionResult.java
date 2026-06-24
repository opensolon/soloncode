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
 * <p>用于承载单代理执行的结构化结果，避免仅依赖字符串判断
 * goal 完成状态。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
public class LoopExecutionResult {
    public static final String GOAL_ACHIEVED = "[GOAL_ACHIEVED]";

    private final boolean submitted;
    private final boolean completed;

    private final boolean goalAchieved;

    private final String finalResult;
    private final String errorMessage;

    private LoopExecutionResult(boolean submitted, boolean completed, boolean goalAchieved,
                                String finalResult, String errorMessage) {
        this.submitted = submitted;
        this.completed = completed;
        this.goalAchieved = goalAchieved;
        this.finalResult = finalResult;
        this.errorMessage = errorMessage;
    }

    public static LoopExecutionResult fromText(String text) {
        return new LoopExecutionResult(
                true,
                text != null,
                containsGoalAchieved(text),
                text,
                null);
    }

    public static LoopExecutionResult submittedOnly() {
        return new LoopExecutionResult(true, false, false, null, null);
    }

    public static LoopExecutionResult error(String errorMessage) {
        return new LoopExecutionResult(true, true, false,
                errorMessage != null ? "error: " + errorMessage : "error", errorMessage);
    }

    private static boolean containsGoalAchieved(String text) {
        return text != null && text.contains(GOAL_ACHIEVED);
    }
}