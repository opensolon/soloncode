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

import java.util.List;

/**
 * Goal 条件评估器 — 与执行模型分离
 *
 * <p>职责：给定执行结果（transcript）和 goal 条件，返回是否达成 + 原因。
 * 评估器不参与 AI 执行流程，不存在自我评估偏差。</p>
 *
 * <p>实现层次（从简单到精确）：</p>
 * <ol>
 *   <li><b>字符串匹配</b>（Level 1, 默认） — 检测 [GOAL_ACHIEVED] 标记</li>
 *   <li><b>外部评估 API</b>（Level 2, 可注入） — 调用小模型 API 独立评估</li>
 *   <li><b>Shell 命令验证</b>（Level 3, 可注入） — 通过 exit code 验证</li>
 * </ol>
 *
 * @author noear
 * @since 3.9.1
 */
@FunctionalInterface
public interface GoalEvaluator {

    /**
     * 评估本轮执行是否达成目标
     *
     * @param condition  目标条件（如 "all tests pass"）
     * @param transcript 本轮执行后的完整对话文本
     * @param history    历史评估记录（用于上下文感知）
     * @return 评估结果
     */
    GoalEvaluation evaluate(String condition, String transcript,
                             List<GoalEvaluation> history);
}
