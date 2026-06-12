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
 * <p>用于同时承载单代理、maker/checker、多端执行的结构化结果，避免仅依赖字符串判断
 * goal 完成状态。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
public class LoopExecutionResult {
    public static final String GOAL_ACHIEVED = "[GOAL_ACHIEVED]";
    public static final String PASS = "[PASS]";

    private final boolean submitted;
    private final boolean completed;

    private final boolean goalAchieved;
    private final boolean checkerPassed;

    private final String makerResult;
    private final String checkerResult;
    private final String finalResult;
    private final String errorMessage;

    private LoopExecutionResult(boolean submitted, boolean completed, boolean goalAchieved,
                                boolean checkerPassed, String makerResult, String checkerResult,
                                String finalResult, String errorMessage) {
        this.submitted = submitted;
        this.completed = completed;
        this.goalAchieved = goalAchieved;
        this.checkerPassed = checkerPassed;
        this.makerResult = makerResult;
        this.checkerResult = checkerResult;
        this.finalResult = finalResult;
        this.errorMessage = errorMessage;
    }

    public static LoopExecutionResult fromText(String text) {
        return new LoopExecutionResult(
                true,
                text != null,
                containsGoalAchieved(text),
                containsPass(text),
                text,
                null,
                text,
                null);
    }

    public static LoopExecutionResult submittedOnly() {
        return new LoopExecutionResult(true, false, false, false, null, null, null, null);
    }

    public static LoopExecutionResult error(String errorMessage) {
        return new LoopExecutionResult(true, true, false, false, null, null,
                errorMessage != null ? "error: " + errorMessage : "error", errorMessage);
    }

    public static LoopExecutionResult makerChecker(String makerResult, String checkerResult) {
        String finalResult = checkerResult != null ? checkerResult : makerResult;
        return new LoopExecutionResult(
                true,
                makerResult != null || checkerResult != null,
                containsGoalAchieved(makerResult) || containsGoalAchieved(checkerResult),
                containsPass(checkerResult),
                makerResult,
                checkerResult,
                finalResult,
                null);
    }

    private static boolean containsGoalAchieved(String text) {
        return text != null && text.contains(GOAL_ACHIEVED);
    }

    private static boolean containsPass(String text) {
        return text != null && text.contains(PASS);
    }
}