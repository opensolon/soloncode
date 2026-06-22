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

import org.noear.snack4.ONode;

import java.time.Instant;

/**
 * Goal 单轮评估结果
 *
 * <p>评估器每轮执行完毕后，返回此对象记录评估结论。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class GoalEvaluation {
    private final int iteration;
    private final boolean achieved;
    private final String reason;
    private final String summary;
    private final Instant evaluatedAt;
    private final String evaluatorType;

    public GoalEvaluation(int iteration, boolean achieved, String reason,
                          String summary, Instant evaluatedAt, String evaluatorType) {
        this.iteration = iteration;
        this.achieved = achieved;
        this.reason = reason;
        this.summary = summary;
        this.evaluatedAt = evaluatedAt;
        this.evaluatorType = evaluatorType;
    }

    // ===== Getters =====

    public int getIteration() {
        return iteration;
    }

    public boolean isAchieved() {
        return achieved;
    }

    public String getReason() {
        return reason;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public String getEvaluatorType() {
        return evaluatorType;
    }

    // ===== 序列化 =====

    public ONode toONode() {
        ONode node = new ONode();
        node.set("iteration", iteration);
        node.set("achieved", achieved);
        if (reason != null) node.set("reason", reason);
        if (summary != null) node.set("summary", summary);
        if (evaluatedAt != null) node.set("evaluatedAt", evaluatedAt.toString());
        if (evaluatorType != null) node.set("evaluatorType", evaluatorType);
        return node;
    }

    public static GoalEvaluation fromONode(ONode node) {
        return new GoalEvaluation(
                node.get("iteration").getInt(),
                node.getOrNull("achieved") != null && node.get("achieved").getBoolean(),
                node.getOrNull("reason") != null ? node.get("reason").getString() : null,
                node.getOrNull("summary") != null ? node.get("summary").getString() : null,
                node.getOrNull("evaluatedAt") != null
                        ? Instant.parse(node.get("evaluatedAt").getString()) : Instant.now(),
                node.getOrNull("evaluatorType") != null
                        ? node.get("evaluatorType").getString() : "unknown"
        );
    }
}
