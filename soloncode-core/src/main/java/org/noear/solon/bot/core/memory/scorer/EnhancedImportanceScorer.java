/*
 * 增强型重要性评分器（Enhanced Importance Scorer）
 *
 * 多维度评分算法，综合考虑：
 * - 内容特征（30%）
 * - 观察类型（25%）
 * - 关键词匹配（25%）
 * - 时间新鲜度（10%）
 * - 访问热度（10%）
 *
 * 评分范围：0.0 - 10.0
 *
 * @author bai
 * @since 3.9.5
 */
package org.noear.solon.bot.core.memory.scorer;

import org.noear.solon.bot.core.memory.bank.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 增强型重要性评分器
 */
public class EnhancedImportanceScorer {
    private static final Logger LOG = LoggerFactory.getLogger(EnhancedImportanceScorer.class);

    // ========== 权重配置 ==========

    private static final double WEIGHT_CONTENT = 0.30;
    private static final double WEIGHT_TYPE = 0.25;
    private static final double WEIGHT_KEYWORDS = 0.25;
    private static final double WEIGHT_RECENCY = 0.10;
    private static final double WEIGHT_POPULARITY = 0.10;

    // ========== 基础分数 ==========

    private static final double BASE_SCORE = 4.0;
    private static final double MAX_SCORE = 10.0;
    private static final double MIN_SCORE = 0.0;

    // ========== 高级关键词库 ==========

    // 极高重要性关键词（+4.0）
    private static final Map<String, Set<String>> CRITICAL_KEYWORDS = new HashMap<>();
    static {
        CRITICAL_KEYWORDS.put("decision", new HashSet<>(Arrays.asList(
            "关键决策", "架构决策", "重大变更",
            "critical", "critical decision", "major change"
        )));
        CRITICAL_KEYWORDS.put("architecture", new HashSet<>(Arrays.asList(
            "系统架构", "核心架构", "整体设计",
            "system architecture", "core design"
        )));
    }

    // 高重要性关键词（+2.0）
    private static final Map<String, Set<String>> HIGH_KEYWORDS = new HashMap<>();
    static {
        HIGH_KEYWORDS.put("problem", new HashSet<>(Arrays.asList(
            "问题", "bug", "issue", "error", "问题发现"
        )));
        HIGH_KEYWORDS.put("solution", new HashSet<>(Arrays.asList(
            "解决", "修复", "方案", "fix", "solve", "solution"
        )));
    }

    // 正向影响关键词（+1.0）
    private static final Set<String> POSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
        "成功", "完成", "正确", "优化", "改进",
        "success", "completed", "correct", "optimized", "improved"
    ));

    // 负向影响关键词（-1.0）
    private static final Set<String> NEGATIVE_KEYWORDS = new HashSet<>(Arrays.asList(
        "失败", "错误", "问题", "警告",
        "failed", "error", "issue", "warning"
    ));

    // ========== 编译模式 ==========

    // 代码检测模式
    private static final Pattern CODE_PATTERN = Pattern.compile(
        "```[\\s\\S]*?```|" +
        "(public|private|protected)\\s+(static\\s+)?" +
        "(class|interface|enum|void|int|String|boolean|double|float|long)\\s+\\w+"
    );

    // 数字/数据模式
    private static final Pattern DATA_PATTERN = Pattern.compile(
        "\\d+\\.\\d+|0x[0-9a-fA-F]+|\\d+%"
    );

    /**
     * 计算观察的重要性分数（0.0-10.0）
     *
     * @param obs 观察
     * @return 重要性分数
     */
    public double score(Observation obs) {
        if (obs == null) {
            return BASE_SCORE;
        }

        double score = BASE_SCORE;

        // ========== 维度 1：内容特征（30%） ==========

        double contentScore = scoreByContent(obs) * WEIGHT_CONTENT;
        score += contentScore;

        LOG.trace("内容特征评分: {} -> {}",
                obs.getContent().substring(0, Math.min(20, obs.getContent().length())),
                contentScore);

        // ========== 维度 2：观察类型（25%） ==========

        double typeScore = scoreByType(obs) * WEIGHT_TYPE;
        score += typeScore;

        LOG.trace("类型评分: {} -> {}", obs.getType(), typeScore);

        // ========== 维度 3：关键词匹配（25%） ==========

        double keywordScore = scoreByKeywords(obs) * WEIGHT_KEYWORDS;
        score += keywordScore;

        LOG.trace("关键词评分: {}", keywordScore);

        // ========== 维度 4：时间新鲜度（10%） ==========

        double recencyScore = scoreByRecency(obs) * WEIGHT_RECENCY;
        score += recencyScore;

        LOG.trace("时间新鲜度评分: {}", recencyScore);

        // ========== 维度 5：访问热度（10%） ==========

        double popularityScore = scoreByPopularity(obs) * WEIGHT_POPULARITY;
        score += popularityScore;

        LOG.trace("访问热度评分: {}", popularityScore);

        // ========== 限制范围并返回 ==========

        score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));

        LOG.debug("重要性评分: {} -> {}",
                obs.getContent().substring(0, Math.min(30, obs.getContent().length())),
                String.format("%.2f", score));

        return score;
    }

    /**
     * 维度 1：内容特征评分
     */
    private double scoreByContent(Observation obs) {
        String content = obs.getContent().toLowerCase();
        double score = 0.0;

        // 特征 1：包含代码（+1.0）
        if (CODE_PATTERN.matcher(content).find()) {
            score += 1.0;
        }

        // 特征 2：包含数据/数字（+0.5）
        if (DATA_PATTERN.matcher(content).find()) {
            score += 0.5;
        }

        // 特征 3：包含文件路径（+0.5）
        if (content.contains(".java") || content.contains(".py") ||
            content.contains(".js") || content.contains(".ts")) {
            score += 0.5;
        }

        // 特征 4：内容长度（中等长度最重要）
        int length = obs.getContent().length();
        if (length >= 50 && length <= 500) {
            score += 0.8;  // 黄金长度
        } else if (length > 500 && length <= 1000) {
            score += 0.3;  // 较长
        } else if (length > 1000) {
            score -= 0.5;  // 太长可能不重要
        }

        // 特征 5：结构化程度（JSON/XML 等结构化数据）
        if (content.contains("{") && content.contains("}") ||
            content.contains("<") && content.contains(">")) {
            score += 0.3;
        }

        return score;
    }

    /**
     * 维度 2：观察类型评分
     */
    private double scoreByType(Observation obs) {
        switch (obs.getType()) {
            case DECISION:
                return 2.5;  // 决策最重要

            case ARCHITECTURE:
                return 2.5;  // 架构最重要

            case ERROR:
                return 2.0;  // 错误重要

            case USER_REQUIREMENT:
                return 2.0;  // 用户需求重要

            case TASK_RESULT:
                return 1.5;  // 任务结果重要

            case CODE_UNDERSTANDING:
                return 1.2;  // 代码理解重要

            case TOOL_CALL:
            case SKILL_EXECUTION:
                return -1.0;  // 工具调用不太重要

            case GENERAL:
            default:
                return 0.0;
        }
    }

    /**
     * 维度 3：关键词匹配评分
     */
    private double scoreByKeywords(Observation obs) {
        String content = obs.getContent().toLowerCase();
        double score = 0.0;

        // 检查高级别关键词
        for (Map.Entry<String, Set<String>> entry : CRITICAL_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (content.contains(keyword.toLowerCase())) {
                    score += 4.0;
                    LOG.trace("发现关键关键词: {} (级别: critical)", keyword);
                    break;
                }
            }
        }

        // 检查高级别关键词
        for (Map.Entry<String, Set<String>> entry : HIGH_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (content.contains(keyword.toLowerCase())) {
                    score += 2.0;
                    LOG.trace("发现关键关键词: {} (级别: high)", keyword);
                    break;
                }
            }
        }

        // 检查正向关键词
        for (String keyword : POSITIVE_KEYWORDS) {
            if (content.contains(keyword)) {
                score += 1.0;
            }
        }

        // 检查负向关键词
        for (String keyword : NEGATIVE_KEYWORDS) {
            if (content.contains(keyword)) {
                score -= 1.0;
            }
        }

        return score;
    }

    /**
     * 维度 4：时间新鲜度评分
     */
    private double scoreByRecency(Observation obs) {
        long ageMs = System.currentTimeMillis() - obs.getTimestamp();
        double ageHours = ageMs / (1000.0 * 60 * 60);

        if (ageHours < 0.5) {
            return 0.5;  // 30分钟内非常新鲜
        } else if (ageHours < 1) {
            return 0.3;  // 1小时内新鲜
        } else if (ageHours < 6) {
            return 0.1;  // 6小时内
        } else if (ageHours < 24) {
            return 0.0;  // 24小时内
        } else if (ageHours < 168) {  // 1周
            return -0.1 * (ageHours / 24);  // 每天衰减 0.1
        } else {
            // 超过1周的记忆，严重衰减
            return -2.0;
        }
    }

    /**
     * 维度 5：访问热度评分
     */
    private double scoreByPopularity(Observation obs) {
        int accessCount = obs.getAccessCount();

        if (accessCount == 0) {
            return 0.0;
        } else if (accessCount <= 3) {
            return 0.3;
        } else if (accessCount <= 10) {
            return 0.6;
        } else if (accessCount <= 20) {
            return 1.0;
        } else {
            // 访问次数很多，但边际效应递减
            return 1.0 + Math.log10(accessCount) * 0.2;
        }
    }

    /**
     * 批量评分
     *
     * @param observations 观察列表
     * @return 分数映射
     */
    public Map<String, Double> scoreBatch(List<Observation> observations) {
        Map<String, Double> scores = new HashMap<>();

        for (Observation obs : observations) {
            double score = score(obs);
            scores.put(obs.getId(), score);
        }

        return scores;
    }

    /**
     * 获取评分详情（用于调试）
     */
    public Map<String, Double> getScoreBreakdown(Observation obs) {
        Map<String, Double> breakdown = new HashMap<>();

        breakdown.put("content", scoreByContent(obs));
        breakdown.put("type", scoreByType(obs));
        breakdown.put("keywords", scoreByKeywords(obs));
        breakdown.put("recency", scoreByRecency(obs));
        breakdown.put("popularity", scoreByPopularity(obs));
        breakdown.put("total", score(obs));

        return breakdown;
    }
}
