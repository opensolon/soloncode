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

import java.time.Instant;
import java.util.List;

/**
 * 字符串匹配评估器（Level 1, 默认 Fallback）
 *
 * <p>检测对话文本中是否包含 {@link LoopExecutionResult#GOAL_ACHIEVED}（[GOAL_ACHIEVED]）标记。
 * 这是最直接的评估方式，与原有 {@link LoopExecutionResult#isGoalAchieved()} 兼容。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class StringMatchEvaluator implements GoalEvaluator {

    // 三态协议标记
    private static final String MARKER_BLOCKED = "[GOAL_BLOCKED]";
    private static final String MARKER_CONTINUE = "[GOAL_CONTINUE]";

    @Override
    public GoalEvaluation evaluate(String condition, String transcript,
                                    List<GoalEvaluation> history) {
        if (transcript == null) {
            transcript = "";
        }

        // 优先级 1: 检查阻塞声明 [GOAL_BLOCKED]
        if (transcript.contains(MARKER_BLOCKED)) {
            String reason = MARKER_BLOCKED + " " + extractContext(transcript, MARKER_BLOCKED);
            return new GoalEvaluation(
                    history.size() + 1,
                    false,
                    reason,
                    "Model declared blocked",
                    Instant.now(),
                    "string-match"
            );
        }

        // 优先级 2: 检查达成声明 [GOAL_ACHIEVED]
        if (transcript.contains(LoopExecutionResult.GOAL_ACHIEVED)) {
            String reason = extractContext(transcript, LoopExecutionResult.GOAL_ACHIEVED);
            String summary = "Model indicated goal completion with " + LoopExecutionResult.GOAL_ACHIEVED;
            return new GoalEvaluation(
                    history.size() + 1,
                    true,
                    reason,
                    summary,
                    Instant.now(),
                    "string-match"
            );
        }

        // 优先级 3: 检查继续声明 [GOAL_CONTINUE]，语义上等同于未达成
        String reason;
        String summary;
        if (transcript.contains(MARKER_CONTINUE)) {
            reason = MARKER_CONTINUE + " " + extractContext(transcript, MARKER_CONTINUE);
            summary = "Model requested to continue";
        } else {
            // 默认：取尾部摘要
            reason = extractLastResponseSummary(transcript);
            summary = "Model has not yet signaled completion";
        }

        return new GoalEvaluation(
                history.size() + 1,
                false,
                reason,
                summary,
                Instant.now(),
                "string-match"
        );
    }

    /**
     * 从文本中提取标记附近的上下文（最多前后 80 字符）
     */
    private String extractContext(String text, String marker) {
        if (text == null) return "Goal achieved (no context)";
        int idx = text.indexOf(marker);
        if (idx < 0) return "Goal achieved (marker found)";

        int start = Math.max(0, idx - 80);
        int end = Math.min(text.length(), idx + marker.length() + 80);
        String context = text.substring(start, end).replace('\n', ' ').trim();

        if (context.length() > 160) {
            context = context.substring(0, 157) + "...";
        }
        return context;
    }

    /**
     * 从 transcript 尾部提取最近一次执行的反馈摘要（最多 200 字符）
     * 注意：不含 "Last response:" 前缀，因为历史对话中已有完整响应
     */
    private String extractLastResponseSummary(String text) {
        if (text == null || text.isEmpty()) {
            return "No AI response yet";
        }

        // 取最后 200 字符
        int len = Math.min(text.length(), 200);
        String tail = text.substring(text.length() - len).replace('\n', ' ').trim();

        if (tail.length() > 150) {
            tail = tail.substring(0, 147) + "...";
        }

        return tail;
    }
}
