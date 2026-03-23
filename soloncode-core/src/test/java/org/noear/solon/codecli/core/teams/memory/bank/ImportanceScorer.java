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
package org.noear.solon.codecli.core.teams.memory.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 重要性评分器（Importance Scorer）
 * <p>
 * 自动评估 Observation 的重要性，用于决定是否存储到长期记忆
 * <p>
 * 评分维度：
 * - 内容特征（关键词、长度）
 * - 观察类型
 * - 源 Agent
 * - 时间因素
 *
 * @author bai
 * @since 3.9.5
 */
public class ImportanceScorer {
    private static final Logger LOG = LoggerFactory.getLogger(ImportanceScorer.class);

    // ========== 关键词权重 ==========

    /**
     * 高重要性关键词（权重 +3.0）
     */
    private static final Set<String> HIGH_IMPORTANCE_KEYWORDS = new HashSet<>();

    /**
     * 中等重要性关键词（权重 +1.5）
     */
    private static final Set<String> MEDIUM_IMPORTANCE_KEYWORDS = new HashSet<>();

    /**
     * 低重要性关键词（权重 -1.0）
     */
    private static final Set<String> LOW_IMPORTANCE_KEYWORDS = new HashSet<>();

    static {
        // 高重要性关键词
        HIGH_IMPORTANCE_KEYWORDS.add("结论");
        HIGH_IMPORTANCE_KEYWORDS.add("决策");
        HIGH_IMPORTANCE_KEYWORDS.add("架构");
        HIGH_IMPORTANCE_KEYWORDS.add("设计");
        HIGH_IMPORTANCE_KEYWORDS.add("问题");
        HIGH_IMPORTANCE_KEYWORDS.add("错误");
        HIGH_IMPORTANCE_KEYWORDS.add("失败");
        HIGH_IMPORTANCE_KEYWORDS.add("成功");
        HIGH_IMPORTANCE_KEYWORDS.add("完成");
        HIGH_IMPORTANCE_KEYWORDS.add("发现");

        // 英文关键词
        HIGH_IMPORTANCE_KEYWORDS.add("conclusion");
        HIGH_IMPORTANCE_KEYWORDS.add("decision");
        HIGH_IMPORTANCE_KEYWORDS.add("architecture");
        HIGH_IMPORTANCE_KEYWORDS.add("design");
        HIGH_IMPORTANCE_KEYWORDS.add("issue");
        HIGH_IMPORTANCE_KEYWORDS.add("error");
        HIGH_IMPORTANCE_KEYWORDS.add("failure");
        HIGH_IMPORTANCE_KEYWORDS.add("success");
        HIGH_IMPORTANCE_KEYWORDS.add("completed");
        HIGH_IMPORTANCE_KEYWORDS.add("found");
        HIGH_IMPORTANCE_KEYWORDS.add("fixed");
        HIGH_IMPORTANCE_KEYWORDS.add("solved");

        // 中等重要性关键词
        MEDIUM_IMPORTANCE_KEYWORDS.add("分析");
        MEDIUM_IMPORTANCE_KEYWORDS.add("理解");
        MEDIUM_IMPORTANCE_KEYWORDS.add("实现");
        MEDIUM_IMPORTANCE_KEYWORDS.add("创建");
        MEDIUM_IMPORTANCE_KEYWORDS.add("修改");
        MEDIUM_IMPORTANCE_KEYWORDS.add("优化");
        MEDIUM_IMPORTANCE_KEYWORDS.add("重构");

        // 低重要性关键词
        LOW_IMPORTANCE_KEYWORDS.add("正在");
        LOW_IMPORTANCE_KEYWORDS.add("开始");
        LOW_IMPORTANCE_KEYWORDS.add("尝试");
        LOW_IMPORTANCE_KEYWORDS.add("准备");
        LOW_IMPORTANCE_KEYWORDS.add("待定");
    }

    // ========== 评分参数 ==========

    /**
     * 基础分数
     */
    private static final double BASE_SCORE = 4.0;

    /**
     * 内容长度权重（每 100 字符 +0.1 分，最多 +1.0）
     */
    private static final double LENGTH_WEIGHT = 0.1;

    /**
     * 最大长度加分
     */
    private static final double MAX_LENGTH_BONUS = 1.0;

    // ========== 公开方法 ==========

    /**
     * 计算 Observation 的重要性分数
     *
     * @param observation 观察
     * @return 重要性分数（0.0-10.0）
     */
    public double score(Observation observation) {
        if (observation == null) {
            return 0.0;
        }

        double score = BASE_SCORE;

        // 1. 内容特征评分
        score += scoreByContent(observation);

        // 2. 类型评分
        score += scoreByType(observation);

        // 3. 关键词评分
        score += scoreByKeywords(observation);

        // 4. 长度评分
        score += scoreByLength(observation);

        // 5. 源 Agent 评分（可选）
        score += scoreBySource(observation);

        // 限制范围 [0.0, 10.0]
        score = Math.max(0.0, Math.min(10.0, score));

        LOG.trace("重要性评分: {} -> {}", observation.getContent().substring(0, Math.min(20, observation.getContent().length())), score);

        return score;
    }

    // ========== 私有方法 ==========

    /**
     * 根据内容特征评分
     */
    private double scoreByContent(Observation observation) {
        String content = observation.getContent();
        double score = 0.0;

        // 检查是否包含数字/代码（可能更重要）
        if (content.matches(".*\\d+.*")) {
            score += 0.5;
        }

        // 检查是否包含代码块
        if (content.contains("```") || content.contains("public class") || content.contains("function ")) {
            score += 1.0;
        }

        // 检查是否包含路径/文件名
        if (content.matches(".*[\\w/]+\\.(java|py|js|ts|go|rs|cpp|c|h).*")) {
            score += 0.5;
        }

        return score;
    }

    /**
     * 根据观察类型评分
     */
    private double scoreByType(Observation observation) {
        Observation.ObservationType type = observation.getType();

        switch (type) {
            case DECISION:
                return 2.0; // 决策最重要
            case ARCHITECTURE:
                return 2.0; // 架构最重要
            case ERROR:
                return 1.5; // 错误重要
            case TASK_RESULT:
                return 1.0; // 任务结果重要
            case CODE_UNDERSTANDING:
                return 0.8; // 代码理解重要
            case USER_REQUIREMENT:
                return 1.5; // 用户需求重要
            case TOOL_CALL:
                return -0.5; // 工具调用不太重要
            case SKILL_EXECUTION:
                return -0.5; // 技能执行不太重要
            case GENERAL:
            default:
                return 0.0; // 一般观察不加分
        }
    }

    /**
     * 根据关键词评分
     */
    private double scoreByKeywords(Observation observation) {
        String content = observation.getContent().toLowerCase();
        double score = 0.0;

        // 高重要性关键词
        for (String keyword : HIGH_IMPORTANCE_KEYWORDS) {
            if (content.contains(keyword.toLowerCase())) {
                score += 3.0;
                break; // 只加分一次
            }
        }

        // 中等重要性关键词
        for (String keyword : MEDIUM_IMPORTANCE_KEYWORDS) {
            if (content.contains(keyword.toLowerCase())) {
                score += 1.5;
                break;
            }
        }

        // 低重要性关键词
        for (String keyword : LOW_IMPORTANCE_KEYWORDS) {
            if (content.contains(keyword.toLowerCase())) {
                score -= 1.0;
                break;
            }
        }

        return score;
    }

    /**
     * 根据内容长度评分
     */
    private double scoreByLength(Observation observation) {
        int length = observation.getContent().length();

        // 每 100 字符 +0.1 分，最多 +1.0
        double bonus = (length / 100) * LENGTH_WEIGHT;
        return Math.min(bonus, MAX_LENGTH_BONUS);
    }

    /**
     * 根据源 Agent 评分
     */
    private double scoreBySource(Observation observation) {
        String source = observation.getSourceAgent();

        if (source == null) {
            return 0.0;
        }

        // 某些 Agent 的观察可能更重要
        if (source.contains("main") || source.contains("coordinator")) {
            return 0.5;
        }

        return 0.0;
    }
}
