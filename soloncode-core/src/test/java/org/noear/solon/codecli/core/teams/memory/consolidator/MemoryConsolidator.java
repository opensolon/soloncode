/*
 * 记忆合并器（Memory Consolidator）
 *
 * 自动检测并合并相似的记忆，减少冗余
 *
 * 算法设计：
 * - 相似度计算：Jaccard 相似度（关键词重叠度）
 * - 分组算法：基于相似度的贪心聚类
 * - 合并策略：保留最重要的，合并内容
 * - 去重级别：可配置（默认 0.85 相似度）
 *
 * @author bai
 * @since 3.9.5
 */
package org.noear.solon.codecli.core.teams.memory.consolidator;

import org.noear.solon.codecli.core.teams.memory.bank.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆合并器实现
 */
public class MemoryConsolidator {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryConsolidator.class);

    private final ConsolidationConfig config;

    public MemoryConsolidator() {
        this(new ConsolidationConfig());
    }

    public MemoryConsolidator(ConsolidationConfig config) {
        this.config = config;
    }

    /**
     * 合并重复记忆（主入口）
     *
     * @param memories 记忆列表
     * @return 合并结果
     */
    public ConsolidationResult consolidate(List<Observation> memories) {
        if (memories == null || memories.isEmpty()) {
            return new ConsolidationResult(0, 0, Collections.emptyMap());
        }

        LOG.info("开始记忆合并：原始记忆数 = {}", memories.size());

        List<List<Observation>> groups = groupBySimilarity(memories);

        LOG.info("分组完成：组数 = {}", groups.size());


        List<Observation> consolidated = new ArrayList<>();
        Map<String, Set<String>> mergeTracking = new HashMap<>();

        for (List<Observation> group : groups) {
            if (group.size() == 1) {
                // 单个记忆，直接保留
                consolidated.add(group.get(0));
            } else {
                // 多个相似记忆，合并
                Observation merged = mergeGroup(group);
                consolidated.add(merged);

                // 记录合并信息
                Set<String> mergedIds = group.stream()
                        .map(Observation::getId)
                        .collect(Collectors.toSet());
                mergeTracking.put(merged.getId(), mergedIds);

                LOG.debug("合并记忆: {} -> {} (相似度 > {})",
                        group.size(), merged.getId(), config.similarityThreshold);
            }
        }


        ConsolidationResult result = new ConsolidationResult(
                memories.size(),
                consolidated.size(),
                mergeTracking
        );

        LOG.info("记忆合并完成: {} -> {} (减少 {} 张, 去除率 {})",
                result.originalCount,
                result.consolidatedCount,
                result.removedCount,
                result.getReductionRate() * 100);

        return result;
    }

    /**
     * 按相似度分组
     */
    private List<List<Observation>> groupBySimilarity(List<Observation> memories) {
        Set<String> processed = new HashSet<>();
        List<List<Observation>> groups = new ArrayList<>();

        for (Observation obs : memories) {
            if (processed.contains(obs.getId())) {
                continue;
            }

            List<Observation> group = new ArrayList<>();
            group.add(obs);
            processed.add(obs.getId());

            // 查找相似记忆
            for (Observation other : memories) {
                if (!processed.contains(other.getId())) {
                    double similarity = calculateSimilarity(obs, other);

                    if (similarity >= config.similarityThreshold) {
                        // 检查组大小限制
                        if (group.size() < config.maxGroupSize) {
                            group.add(other);
                            processed.add(other.getId());
                        } else {
                            LOG.debug("组大小达到上限，跳过: {}",
                                    other.getId());
                        }
                    }
                }
            }

            groups.add(group);
        }

        return groups;
    }

    /**
     * 计算两个记忆的相似度
     *
     * 算法：Jaccard 相似度 + 语义增强
     */
    private double calculateSimilarity(Observation a, Observation b) {
        // 1. Jaccard 相似度（关键词重叠度）
        double jaccard = calculateJaccardSimilarity(
                extractWords(a.getContent()),
                extractWords(b.getContent())
        );

        // 2. 类型相同加分
        double typeBonus = (a.getType() == b.getType()) ? 0.1 : 0.0;

        // 3. 长度相似度（避免长度差异过大）
        double lenA = a.getContent().length();
        double lenB = b.getContent().length();
        double lenSimilarity = 1.0 - Math.abs(lenA - lenB) / Math.max(lenA, lenB);

        // 4. 综合相似度
        double similarity = jaccard * 0.8 + typeBonus * 0.1 + lenSimilarity * 0.1;

        // 安全地截取 ID 前 8 个字符用于日志
        String idA = a.getId() != null ? (a.getId().length() > 8 ? a.getId().substring(0, 8) : a.getId()) : "null";
        String idB = b.getId() != null ? (b.getId().length() > 8 ? b.getId().substring(0, 8) : b.getId()) : "null";

        LOG.trace("相似度计算: {} <-> {} = {}",
                idA,
                idB,
                similarity);

        return similarity;
    }

    /**
     * Jaccard 相似度
     */
    private double calculateJaccardSimilarity(Set<String> wordsA, Set<String> wordsB) {
        int intersection = 0;

        for (String word : wordsA) {
            if (wordsB.contains(word)) {
                intersection++;
            }
        }

        int union = wordsA.size() + wordsB.size() - intersection;

        if (union == 0) {
            return 0.0;
        }

        return (double) intersection / union;
    }

    /**
     * 提取单词（简单分词）
     *
     */
    private Set<String> extractWords(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptySet();
        }

        // 简单分词：按空格、标点符号分割
        String[] words = text.toLowerCase()
                .split("[\\s\\p{Punct}+]+");

        // 过滤掉短词（长度 < 2）
        Set<String> uniqueWords = new HashSet<>();
        for (String word : words) {
            if (word.length() >= 2) {
                uniqueWords.add(word);
            }
        }

        return uniqueWords;
    }

    /**
     * 合并一组相似记忆
     */
    private Observation mergeGroup(List<Observation> group) {
        // 选择最重要的作为基础
        Observation base = group.stream()
                .max(Comparator.comparingDouble(Observation::getDecayedImportance))
                .orElse(group.get(0));

        LOG.debug("选择基础记忆: {} (重要性: {})",
                base.getId().substring(0, 8),
                String.format("%.2f", base.getDecayedImportance()));

        // 构建合并后的内容
        StringBuilder mergedContent = new StringBuilder();
        mergedContent.append(base.getContent());

        // 合并其他记忆的内容（避免重复）
        if (config.mergeContent) {
            Set<String> existingWords = extractWords(base.getContent());

            for (int i = 1; i < group.size(); i++) {
                Observation obs = group.get(i);
                String content = obs.getContent();

                // 只添加不重复的内容片段
                if (!isContentDuplicate(content, existingWords)) {
                    mergedContent.append("; ").append(content);
                    existingWords.addAll(extractWords(content));
                }
            }
        }

        // 创建合并后的记忆
        Observation.ObservationBuilder builder = Observation.builder()
                .content(mergedContent.toString())
                .type(base.getType())
                .importance(base.getImportance())
                .timestamp(base.getTimestamp());

        // 如果配置了保留元数据，则合并所有元数据
        if (config.keepMetadata) {
            Map<String, Object> mergedMetadata = new HashMap<>();
            for (Observation obs : group) {
                if (obs.getMetadata() != null) {
                    mergedMetadata.putAll(obs.getMetadata());
                }
                mergedMetadata.put("merged_from", obs.getId());
            }
            builder.metadata(mergedMetadata);
        }

        Observation merged = builder.build();

        // 记录合并信息
        LOG.debug("合并完成: {} 个记忆 -> 1 个 (长度: {} 字符)",
                group.size(), mergedContent.length());

        return merged;
    }

    /**
     * 检查内容是否重复
     */
    private boolean isContentDuplicate(String content, Set<String> existingWords) {
        Set<String> contentWords = extractWords(content);

        // 计算重叠度
        int overlap = 0;
        for (String word : contentWords) {
            if (existingWords.contains(word)) {
                overlap++;
            }
        }

        // 如果重叠度 > 80%，认为是重复
        double overlapRatio = (double) overlap / contentWords.size();
        return overlapRatio > 0.8;
    }

    /**
     * 手动合并指定的记忆
     *
     * @param memories 要合并的记忆列表
     * @return 合并后的记忆
     */
    public Observation manualMerge(List<Observation> memories) {
        if (memories == null || memories.isEmpty()) {
            throw new IllegalArgumentException("记忆列表不能为空");
        }

        LOG.info("手动合并 {} 个记忆", memories.size());

        this.config.mergeContent = true;  // 强制合并内容
        this.config.keepMetadata = true;

        return mergeGroup(memories);
    }

    /**
     * 查找与给定记忆相似的记忆
     *
     * @param target 目标记忆
     * @param memories 记忆池
     * @return 相似的记忆列表（按相似度降序）
     */
    public List<Observation> findSimilar(Observation target, List<Observation> memories) {
        if (target == null || memories == null) {
            return Collections.emptyList();
        }

        return memories.stream()
                .filter(obs -> !obs.getId().equals(target.getId()))
                .map(obs -> new AbstractMap.SimpleEntry<>(
                        obs,
                        calculateSimilarity(target, obs)
                ))
                .filter(entry -> entry.getValue() >= config.similarityThreshold)
                .sorted(Map.Entry.<Observation, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
