/*
 * 自动记忆分类器（Auto Classifier）
 *
 * 使用多特征融合算法，自动判断记忆应该存储的类型和 TTL
 *
 * 算法设计：
 * - 特征提取：从内容中提取多维特征
 * - 规则匹配：基于关键词和模式的规则引擎
 * - 机器学习（可选）：使用预训练模型增强准确性
 * - 置信度计算：给出分类结果的置信度
 *
 * @author bai
 * @since 3.9.5
 */
package org.noear.solon.codecli.core.teams.memory.classifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 自动分类器实现
 */
public class MemoryAutoClassifier {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryAutoClassifier.class);

    // ========== 关键词库 ==========

    // 决策类关键词（权重：3.0）
    private static final Set<String> DECISION_KEYWORDS = new HashSet<>(Arrays.asList(
        "决策", "决定", "选择", "选定", "采用", "使用",
        "架构", "设计", "模式", "方案",
        "decision", "decision-made", "chosen", "selected", "adopt",
        "architecture", "design", "pattern", "solution"
    ));

    // 任务结果类关键词（权重：2.5）
    private static final Set<String> TASK_KEYWORDS = new HashSet<>(Arrays.asList(
        "完成", "实现", "成功", "失败", "修复", "解决", "创建",
        "结果", "输出", "产物", "成果",
        "完成时", "耗时",
        "completed", "implemented", "fixed", "solved", "created",
        "result", "output", "success", "failure", "took"
    ));

    // 临时上下文类关键词（权重：2.0）
    private static final Set<String> CONTEXT_KEYWORDS = new HashSet<>(Arrays.asList(
        "正在", "尝试", "准备", "临时", "当前", "待定",
        "分析中", "处理中", "检查中",
        "trying", "preparing", "temp", "temporary", "current",
        "analyzing", "processing", "checking"
    ));

    // 知识类关键词（权重：2.5）
    private static final Set<String> KNOWLEDGE_KEYWORDS = new HashSet<>(Arrays.asList(
        "架构", "模式", "设计原则", "最佳实践",
        "API", "接口", "类", "方法", "函数",
        "配置", "设置", "参数",
        "architecture", "pattern", "design principle", "best practice",
        "api", "interface", "class", "method", "function",
        "configuration", "setting", "parameter"
    ));

    // 错误类关键词（权重：2.0）
    private static final Set<String> ERROR_KEYWORDS = new HashSet<>(Arrays.asList(
        "错误", "异常", "失败", "问题", "bug", "issue",
        "堆栈", "异常信息", "错误码",
        "error", "exception", "failure", "issue", "bug",
        "stack trace", "error code"
    ));

    // ========== 特征模式 ==========

    // 代码片段模式
    private static final Pattern CODE_PATTERN = Pattern.compile(
        "```[\\s\\S]*?```|" +
        "public\\s+class|private\\s+void|def\\s+\\w+|function\\s+\\w+|" +
        "\\.(java|py|js|ts|go|rs|cpp|c|h|sql|json|xml)"
    );

    // 文件路径模式
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
        "[/\\\\][\\w-/\\\\]+\\.(java|py|js|ts|go|rs|cpp|c|h|sql|json|xml|md|txt|yml|yaml)"
    );

    // 时间戳模式
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}|\\d{2}:\\d{2}:\\d{2}"
    );

    /**
     * 分类记忆（主入口）
     *
     * @param content 记忆内容
     * @param context 上下文信息（可选）
     * @return 分类结果
     */
    public MemoryClassification classify(String content, Map<String, Object> context) {
        if (content == null || content.trim().isEmpty()) {
            return new MemoryClassification(
                MemoryCategory.WORKING,
                600_000L,
                0.5,
                "内容为空，使用工作记忆"
            );
        }

        // ========== 第1步：规则评分 ==========

        Map<MemoryCategory, Double> scores = new HashMap<>();
        scores.put(MemoryCategory.WORKING, 0.0);
        scores.put(MemoryCategory.SHORT_TERM, 0.0);
        scores.put(MemoryCategory.LONG_TERM, 0.0);
        scores.put(MemoryCategory.PERMANENT, 0.0);

        // 1.1 关键词匹配评分
        scoreByKeywords(content, scores);

        // 1.2 内容特征评分
        scoreByFeatures(content, scores);

        // 1.3 上下文线索评分
        if (context != null) {
            scoreByContext(content, context, scores);
        }

        // ========== 第2步：选择最高分类 ==========

        Map.Entry<MemoryCategory, Double> maxEntry = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(new AbstractMap.SimpleEntry<>(MemoryCategory.WORKING, 0.0));

        MemoryCategory selectedCategory = maxEntry.getKey();
        double rawScore = maxEntry.getValue();

        // ========== 第3步：置信度调整 ==========

        double confidence = calculateConfidence(rawScore, content);

        // 如果置信度太低，使用更保守的分类
        if (confidence < 0.5) {
            selectedCategory = MemoryCategory.SHORT_TERM;  // 默认使用短期记忆
        }

        // ========== 第4步：TTL 计算 ==========

        long ttl = calculateTtl(selectedCategory, rawScore, content);

        // ========== 第5步：生成原因说明 ==========

        String reason = generateReason(selectedCategory, ttl, scores);

        LOG.debug("记忆分类: category={}, ttl={}ms, confidence={}, reason={}",
                selectedCategory, ttl, String.format("%.2f", confidence), reason);

        return new MemoryClassification(selectedCategory, ttl, confidence, reason);
    }

    /**
     * 关键词匹配评分
     */
    private void scoreByKeywords(String content, Map<MemoryCategory, Double> scores) {
        String lower = content.toLowerCase();

        // 决策类 → PERMANENT
        double decisionScore = countKeywordMatches(lower, DECISION_KEYWORDS) * 3.0;
        scores.put(MemoryCategory.PERMANENT,
                scores.get(MemoryCategory.PERMANENT) + decisionScore);

        // 任务结果类 → LONG_TERM
        double taskScore = countKeywordMatches(lower, TASK_KEYWORDS) * 2.5;
        scores.put(MemoryCategory.LONG_TERM,
                scores.get(MemoryCategory.LONG_TERM) + taskScore);

        // 知识类 → PERMANENT
        double knowledgeScore = countKeywordMatches(lower, KNOWLEDGE_KEYWORDS) * 2.5;
        scores.put(MemoryCategory.PERMANENT,
                scores.get(MemoryCategory.PERMANENT) + knowledgeScore);

        // 上下文类 → WORKING 或 SHORT_TERM
        double contextScore = countKeywordMatches(lower, CONTEXT_KEYWORDS) * 2.0;
        scores.put(MemoryCategory.WORKING,
                scores.get(MemoryCategory.WORKING) + contextScore * 0.5);
        scores.put(MemoryCategory.SHORT_TERM,
                scores.get(MemoryCategory.SHORT_TERM) + contextScore * 0.5);

        // 错误类 → LONG_TERM（错误信息重要）
        double errorScore = countKeywordMatches(lower, ERROR_KEYWORDS) * 2.0;
        scores.put(MemoryCategory.LONG_TERM,
                scores.get(MemoryCategory.LONG_TERM) + errorScore);
    }

    /**
     * 内容特征评分
     */
    private void scoreByFeatures(String content, Map<MemoryCategory, Double> scores) {
        // 特征 1：是否包含代码片段
        if (CODE_PATTERN.matcher(content).find()) {
            scores.put(MemoryCategory.LONG_TERM,
                    scores.get(MemoryCategory.LONG_TERM) + 1.5);
        }

        // 特征 2：是否包含文件路径
        if (FILE_PATH_PATTERN.matcher(content).find()) {
            scores.put(MemoryCategory.LONG_TERM,
                    scores.get(MemoryCategory.LONG_TERM) + 1.0);
        }

        // 特征 3：内容长度分析
        int length = content.length();
        if (length < 30) {
            // 短内容 → WORKING
            scores.put(MemoryCategory.WORKING,
                    scores.get(MemoryCategory.WORKING) + 1.5);
        } else if (length > 500) {
            // 长内容 → LONG_TERM 或 PERMANENT
            scores.put(MemoryCategory.LONG_TERM,
                    scores.get(MemoryCategory.LONG_TERM) + 1.0);
            scores.put(MemoryCategory.PERMANENT,
                    scores.get(MemoryCategory.PERMANENT) + 0.5);
        }

        // 特征 4：是否包含结构化数据
        if (content.contains(": ") && content.length() > 100) {
            scores.put(MemoryCategory.SHORT_TERM,
                    scores.get(MemoryCategory.SHORT_TERM) + 0.5);
        }
    }

    /**
     * 上下文线索评分
     */
    private void scoreByContext(String content, Map<String, Object> context,
                               Map<MemoryCategory, Double> scores) {
        // 线索 1：来源 Agent
        String sourceAgent = (String) context.get("sourceAgent");
        if (sourceAgent != null) {
            if (sourceAgent.contains("explore") || sourceAgent.contains("analysis")) {
                scores.put(MemoryCategory.SHORT_TERM,
                        scores.get(MemoryCategory.SHORT_TERM) + 1.0);
            } else if (sourceAgent.contains("main") || sourceAgent.contains("coordinator")) {
                scores.put(MemoryCategory.LONG_TERM,
                        scores.get(MemoryCategory.LONG_TERM) + 0.5);
            }
        }

        // 线索 2：关联任务 ID
        if (context.containsKey("taskId")) {
            scores.put(MemoryCategory.LONG_TERM,
                    scores.get(MemoryCategory.LONG_TERM) + 1.5);
        }

        // 线索 3：用户发起的操作
        if (context.containsKey("userInitiated") &&
            Boolean.TRUE.equals(context.get("userInitiated"))) {
            scores.put(MemoryCategory.PERMANENT,
                    scores.get(MemoryCategory.PERMANENT) + 2.0);
        }
    }

    /**
     * 计算置信度
     */
    private double calculateConfidence(double rawScore, String content) {
        // 基础置信度（分数越高，置信度越高）
        double baseConfidence = Math.min(1.0, rawScore / 10.0);

        // 内容长度调整（中等长度的内容置信度更高）
        int length = content.length();
        if (length >= 50 && length <= 500) {
            baseConfidence += 0.1;
        } else if (length < 20 || length > 1000) {
            baseConfidence -= 0.2;
        }

        return Math.max(0.0, Math.min(1.0, baseConfidence));
    }

    /**
     * 计算 TTL（动态 TTL）
     */
    private long calculateTtl(MemoryCategory category, double score, String content) {
        // 基础 TTL
        long baseTtl = category.getDefaultTtl();

        // 如果是永久记忆，不需要调整
        if (category == MemoryCategory.PERMANENT) {
            return -1L;
        }

        // 根据分数调整 TTL
        double scoreFactor = score / 10.0;  // 0.0 - 1.0

        if (category == MemoryCategory.LONG_TERM) {
            // 长期记忆：1天 - 14天
            long minTtl = 24 * 3600_000L;      // 1 天
            long maxTtl = 14 * 24 * 3600_000L; // 14 天
            return minTtl + (long) ((maxTtl - minTtl) * scoreFactor);
        } else if (category == MemoryCategory.SHORT_TERM) {
            // 短期记忆：30分钟 - 3小时
            long minTtl = 30 * 60_000L;      // 30 分钟
            long maxTtl = 3 * 3600_000L;      // 3 小时
            return minTtl + (long) ((maxTtl - minTtl) * (1.0 - scoreFactor));
        } else {
            // 工作记忆：5分钟 - 15分钟
            long minTtl = 5 * 60_000L;         // 5 分钟
            long maxTtl = 15 * 60_000L;        // 15 分钟
            return minTtl + (long) ((maxTtl - minTtl) * (1.0 - scoreFactor));
        }
    }

    /**
     * 计算关键词匹配次数
     */
    private int countKeywordMatches(String text, Set<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生成原因说明
     */
    private String generateReason(MemoryCategory category, long ttl,
                                  Map<MemoryCategory, Double> scores) {
        List<String> reasons = new ArrayList<>();

        // 找出得分最高的原因
        scores.entrySet().stream()
                .sorted(Map.Entry.<MemoryCategory, Double>comparingByValue().reversed())
                .limit(2)
                .forEach(entry -> {
                    if (entry.getValue() > 0) {
                        reasons.add(entry.getKey().name());
                    }
                });

        StringBuilder sb = new StringBuilder();
        sb.append("分类为 ").append(category.name());

        if (ttl > 0) {
            long minutes = ttl / 60_000L;
            if (minutes < 60) {
                sb.append(" (TTL: ").append(minutes).append("分钟)");
            } else {
                long hours = minutes / 60;
                sb.append(" (TTL: ").append(hours).append("小时)");
            }
        } else {
            sb.append(" (永久存储)");
        }

        sb.append("; 原因: ").append(String.join(" + ", reasons));

        return sb.toString();
    }
}
