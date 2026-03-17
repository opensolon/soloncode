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
package org.noear.solon.bot.core.memory.smart;

import org.noear.solon.bot.core.memory.*;
import org.noear.solon.bot.core.memory.bank.Observation;
import org.noear.solon.bot.core.memory.classifier.MemoryAutoClassifier;
import org.noear.solon.bot.core.memory.classifier.MemoryClassification;
import org.noear.solon.bot.core.memory.classifier.MemoryCategory;
import org.noear.solon.bot.core.memory.consolidator.MemoryConsolidator;
import org.noear.solon.bot.core.memory.consolidator.ConsolidationConfig;
import org.noear.solon.bot.core.memory.consolidator.ConsolidationResult;
import org.noear.solon.bot.core.memory.scorer.EnhancedImportanceScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 智能记忆管理器（Intelligent Memory Manager）
 * <p>
 * 整合自动分类、重要性评分、记忆合并等智能功能
 * <p>
 * **核心功能**：
 * - 自动分类：无需用户选择记忆类型
 * - 自动评分：多维度评估记忆重要性
 * - 智能检索：只返回最相关的记忆
 * - 记忆合并：自动清理重复记忆
 * - 上下文优化：减少传递给 LLM 的 token 数量
 *
 * **设计理念**：
 * - 渐进式集成：保留现有 SharedMemoryManager，添加智能层
 * - 向后兼容：现有 API 继续工作
 * - 用户无感知：内部优化，外部接口不变
 *
 * @author bai
 * @since 3.9.5
 */
public class IntelligentMemoryManager {

    private static final Logger LOG = LoggerFactory.getLogger(IntelligentMemoryManager.class);

    // ========== 核心组件 ==========

    private final MemoryAutoClassifier autoClassifier;
    private final EnhancedImportanceScorer importanceScorer;
    private final SharedMemoryManager delegate;  // 底层存储管理器
    private final MemoryConsolidator consolidator;  // 记忆合并器
    private final ScheduledExecutorService consolidationExecutor;  // 合并任务调度器

    // ========== 配置 ==========

    private final boolean autoConsolidate;  // 是否自动合并重复记忆
    private final double consolidationThreshold;  // 合并阈值
    private final long consolidationInterval;   // 合并间隔（毫秒）

    /**
     * 构造函数（使用默认配置）
     *
     * @param workDir 工作目录
     */
    public IntelligentMemoryManager(Path workDir) {
        this(workDir, true, 0.85, 300_000L);  // 默认5分钟合并一次
    }

    /**
     * 完整构造函数
     *
     * @param workDir 工作目录
     * @param autoConsolidate 是否自动合并
     * @param consolidationThreshold 合并阈值（0-1）
     * @param consolidationInterval 合并间隔（毫秒）
     */
    public IntelligentMemoryManager(Path workDir,
                                    boolean autoConsolidate,
                                    double consolidationThreshold,
                                    long consolidationInterval) {
        this.autoClassifier = new MemoryAutoClassifier();
        this.importanceScorer = new EnhancedImportanceScorer();
        this.delegate = new SharedMemoryManager(workDir);
        this.autoConsolidate = autoConsolidate;
        this.consolidationThreshold = consolidationThreshold;
        this.consolidationInterval = consolidationInterval;

        // 初始化记忆合并器
        ConsolidationConfig config = new ConsolidationConfig();
        config.similarityThreshold = consolidationThreshold;
        this.consolidator = new MemoryConsolidator(config);

        // 初始化调度器
        this.consolidationExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryConsolidation");
            t.setDaemon(true);
            return t;
        });

        if (autoConsolidate) {
            startConsolidationTask();
        }

        LOG.info("IntelligentMemoryManager 初始化完成: autoConsolidate={}, threshold={}",
                autoConsolidate, consolidationThreshold);
    }

    // ========== 智能存储方法 ==========

    /**
     * 智能存储记忆（自动分类和评分）
     *
     * @param key 记忆键
     * @param content 记忆内容
     * @return 存储结果
     */
    public String store(String key, String content) {
        return store(key, content, null);
    }

    /**
     * 智能存储记忆（带上下文）
     *
     * @param key 记忆键
     * @param content 记忆内容
     * @param context 上下文信息
     * @return 存储结果
     */
    public String store(String key, String content, Map<String, Object> context) {
        if (content == null || content.trim().isEmpty()) {
            return "[WARN] 内容为空，无法存储";
        }

        try {
            // ========== 第1步：自动分类 ==========
            MemoryClassification classification = autoClassifier.classify(content, context);

            // ========== 第2步：创建 Observation ==========
            Observation obs = Observation.builder()
                    .id(key != null && !key.isEmpty() ? key : UUID.randomUUID().toString())
                    .content(content)
                    .type(mapCategory(classification.getCategory()))
                    .build();

            // ========== 第3步：自动评分 ==========
            double importance = importanceScorer.score(obs);
            obs.setImportance(importance);

            LOG.debug("智能存储: key={}, category={}, importance={}",
                    key, classification.getCategory(), String.format("%.2f", importance));

            // ========== 第4步：存储到相应层级 ==========
            storeToCategory(obs, classification.getCategory());

            return String.format(
                    "[OK] 已存储到 %s (重要性: %.1f/10, TTL: %s)",
                    classification.getCategory().getDisplayName(),
                    importance,
                    formatTtl(classification.getTtl())
            );

        } catch (Throwable e) {
            LOG.error("智能存储失败: key={}, error={}", key, e.getMessage(), e);
            return "[ERROR] 存储失败: " + e.getMessage();
        }
    }

    /**
     * 智能检索记忆（只返回最相关的）
     *
     * @param query 查询内容
     * @param limit 返回数量限制
     * @return 检索结果
     */
    public String retrieve(String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return getAllMemories(limit);
        }

        try {
            // ========== 第1步：从各层级检索 ==========
            List<Observation> allMemories = new ArrayList<>();

            // 1.1 从短期记忆检索
            List<Memory> shortTermMemories = delegate.retrieve(Memory.MemoryType.SHORT_TERM, 1000);
            allMemories.addAll(convertToObservations(shortTermMemories));

            // 1.2 从长期记忆检索
            List<Memory> longTermMemories = delegate.retrieve(Memory.MemoryType.LONG_TERM, 1000);
            allMemories.addAll(convertToObservations(longTermMemories));

            // 1.3 从知识记忆检索
            List<Memory> knowledgeMemories = delegate.retrieve(Memory.MemoryType.KNOWLEDGE, 1000);
            allMemories.addAll(convertToObservations(knowledgeMemories));

            // ========== 第2步：计算相关性并排序 ==========
            List<Observation> ranked = rankByRelevance(allMemories, query);

            // ========== 第3步：限制返回数量 ==========
            List<Observation> selected = ranked.stream()
                    .limit(limit > 0 ? limit : 10)
                    .collect(Collectors.toList());

            // 记录访问
            selected.forEach(Observation::recordAccess);

            // ========== 第4步：格式化输出 ==========
            return formatResults(selected, query);

        } catch (Throwable e) {
            LOG.error("智能检索失败: query={}, error={}", query, e.getMessage(), e);
            return "[ERROR] 检索失败: " + e.getMessage();
        }
    }

    /**
     * 获取所有记忆（按重要性排序）
     */
    public String getAllMemories(int limit) {
        try {
            List<Observation> all = new ArrayList<>();

            // 从各层级收集
            List<Memory> shortTermMemories = delegate.retrieve(Memory.MemoryType.SHORT_TERM, 1000);
            all.addAll(convertToObservations(shortTermMemories));

            List<Memory> longTermMemories = delegate.retrieve(Memory.MemoryType.LONG_TERM, 1000);
            all.addAll(convertToObservations(longTermMemories));

            List<Memory> knowledgeMemories = delegate.retrieve(Memory.MemoryType.KNOWLEDGE, 1000);
            all.addAll(convertToObservations(knowledgeMemories));

            // 按重要性排序
            all.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            // 限制数量
            List<Observation> selected = all.stream()
                    .limit(limit > 0 ? limit : 20)
                    .collect(Collectors.toList());

            return formatResults(selected, "全部");

        } catch (Throwable e) {
            LOG.error("获取记忆失败: error={}", e.getMessage(), e);
            return "[ERROR] 获取失败: " + e.getMessage();
        }
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = delegate.getStats();

        // 添加智能层统计
        stats.put("intelligentLayer", "已启用");
        stats.put("autoConsolidate", autoConsolidate);

        return stats;
    }

    // ========== 私有方法 ==========

    /**
     * 存储到指定类别
     */
    private void storeToCategory(Observation obs, MemoryCategory category) {
        switch (category) {
            case WORKING:
                // WorkingMemory 由 delegate 管理
                WorkingMemory working = new WorkingMemory(obs.getId());
                working.setSummary(obs.getContent());
                working.setStep(0);
                working.setStatus("running");
                delegate.storeWorking(working);
                break;

            case SHORT_TERM:
                // 使用便捷方法存储短期记忆
                delegate.putShortTerm(obs.getId(), obs.getContent(), 3600L);  // 1小时 TTL
                break;

            case LONG_TERM:
                // 使用便捷方法存储长期记忆
                delegate.putLongTerm(obs.getId(), obs.getContent(), 7 * 24 * 3600L);  // 7天 TTL
                break;

            case PERMANENT:
                // 使用便捷方法存储知识记忆
                delegate.putKnowledge(obs.getId(), obs.getContent());
                break;
        }
    }

    /**
     * 按相关性排序记忆
     */
    private List<Observation> rankByRelevance(List<Observation> memories, String query) {
        String lowerQuery = query.toLowerCase();

        return memories.stream()
                .sorted((a, b) -> {
                    double scoreA = calculateRelevance(a, lowerQuery);
                    double scoreB = calculateRelevance(b, lowerQuery);
                    return Double.compare(scoreB, scoreA);  // 降序
                })
                .collect(Collectors.toList());
    }

    /**
     * 计算相关性分数
     */
    private double calculateRelevance(Observation obs, String query) {
        double score = 0.0;

        // 1. 文本匹配（40%）
        String content = obs.getContent().toLowerCase();
        String[] queryWords = query.toLowerCase().split("\\s+");

        for (String word : queryWords) {
            if (content.contains(word)) {
                score += 1.0;
            }
        }
        score *= 0.4;

        // 2. 重要性（30%）
        score += obs.getImportance() * 0.3;

        // 3. 时间新鲜度（20%）
        long ageHours = (System.currentTimeMillis() - obs.getTimestamp()) / (1000 * 60 * 60);
        if (ageHours < 1) {
            score += 2.0 * 0.2;
        } else if (ageHours < 24) {
            score += 1.0 * 0.2;
        }

        // 4. 访问热度（10%）
        score += Math.log(obs.getAccessCount() + 1) * 0.1;

        return score;
    }

    /**
     * 格式化结果
     */
    private String formatResults(List<Observation> observations, String query) {
        if (observations.isEmpty()) {
            return "[WARN] 未找到相关记忆";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(observations.size()).append(" 条记忆")
          .append(query.equals("全部") ? "" : " (查询: " + query + ")")
          .append(":\n\n");

        for (int i = 0; i < observations.size(); i++) {
            Observation obs = observations.get(i);
            sb.append(i + 1).append(". ");

            // 重要性指示器
            double imp = obs.getImportance();
            if (imp >= 8.0) {
                sb.append("[STAR]");  // 非常重要
            } else if (imp >= 5.0) {
                sb.append("[IMPORTANT]");  // 重要
            } else {
                sb.append("[NOTE]");  // 普通
            }

            // 内容摘要
            String content = obs.getContent();
            String preview = content.length() > 100
                ? content.substring(0, 100) + "..."
                : content;

            sb.append(preview);

            // 重要性分数
            sb.append(" (").append(String.format("%.1f", imp)).append("/10)");

            // 时间
            long ageHours = (System.currentTimeMillis() - obs.getTimestamp()) / (1000 * 60 * 60);
            if (ageHours < 1) {
                sb.append(" [刚刚]");
            } else if (ageHours < 24) {
                sb.append(" [").append(ageHours).append("小时前]");
            } else {
                sb.append(" [").append(ageHours / 24).append("天前]");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 将 Memory 转换为 Observation
     */
    private List<Observation> convertToObservations(Collection<? extends Memory> memories) {
        List<Observation> observations = new ArrayList<>();

        for (Memory mem : memories) {
            Observation obs = convertToObservation(mem);
            if (obs != null) {
                observations.add(obs);
            }
        }

        return observations;
    }

    /**
     * 将单个 Memory 转换为 Observation
     */
    private Observation convertToObservation(Memory memory) {
        if (memory == null) {
            return null;
        }

        Observation.ObservationBuilder builder = Observation.builder()
                .id(memory.getId())
                .timestamp(memory.getTimestamp())
                .importance(5.0);  // 默认重要性

        // 根据类型设置内容
        if (memory instanceof ShortTermMemory) {
            ShortTermMemory stm = (ShortTermMemory) memory;
            builder.content(stm.getContext())
                    .type(Observation.ObservationType.GENERAL);
        } else if (memory instanceof LongTermMemory) {
            LongTermMemory ltm = (LongTermMemory) memory;
            builder.content(ltm.getSummary())
                    .type(Observation.ObservationType.TASK_RESULT);
        } else if (memory instanceof KnowledgeMemory) {
            KnowledgeMemory km = (KnowledgeMemory) memory;
            builder.content(km.getContent())
                    .type(Observation.ObservationType.ARCHITECTURE);
        }

        return builder.build();
    }

    /**
     * 映射 MemoryCategory 到 ObservationType
     */
    private Observation.ObservationType mapCategory(MemoryCategory category) {
        switch (category) {
            case WORKING:
                return Observation.ObservationType.GENERAL;
            case SHORT_TERM:
                return Observation.ObservationType.GENERAL;
            case LONG_TERM:
                return Observation.ObservationType.TASK_RESULT;
            case PERMANENT:
                return Observation.ObservationType.ARCHITECTURE;
            default:
                return Observation.ObservationType.GENERAL;
        }
    }

    /**
     * 格式化 TTL
     */
    private String formatTtl(long ttl) {
        if (ttl < 0) {
            return "永久";
        } else if (ttl < 60_000L) {
            return (ttl / 1000) + "秒";
        } else if (ttl < 3600_000L) {
            return (ttl / 60_000) + "分钟";
        } else if (ttl < 24 * 3600_000L) {
            return (ttl / 3600_000) + "小时";
        } else {
            return (ttl / (24 * 3600_000L)) + "天";
        }
    }

    /**
     * 启动后台合并任务
     */
    private void startConsolidationTask() {
        consolidationExecutor.scheduleAtFixedRate(
            this::performConsolidation,
            consolidationInterval,  // 初始延迟
            consolidationInterval,  // 间隔
            TimeUnit.MILLISECONDS
        );

        LOG.info("记忆合并任务已启动（间隔: {}ms, 阈值: {}）",
                consolidationInterval, consolidationThreshold);
    }

    /**
     * 执行记忆合并
     */
    private void performConsolidation() {
        try {
            LOG.debug("开始执行记忆合并...");

            // 1. 从 SharedMemoryManager 获取所有记忆
            List<Observation> allObservations = getAllObservations();

            if (allObservations.isEmpty()) {
                LOG.debug("没有记忆需要合并");
                return;
            }

            // 2. 执行合并
            ConsolidationResult result =
                consolidator.consolidate(allObservations);

            // 3. 记录结果
            if (result.removedCount > 0) {
                LOG.info("记忆合并完成: {} -> {} (减少 {} 张, 去除率 {}%)",
                        result.originalCount,
                        result.consolidatedCount,
                        result.removedCount,
                        String.format("%.1f", result.getReductionRate() * 100));

                // 4. 将合并后的记忆写回存储
                applyConsolidationResult(result);
            } else {
                LOG.debug("记忆合并完成: 无需合并（{} 个记忆）", result.originalCount);
            }

        } catch (Throwable e) {
            LOG.error("记忆合并失败", e);
        }
    }

    /**
     * 应用合并结果到存储
     *
     * @param result 合并结果
     */
    private void applyConsolidationResult(ConsolidationResult result) {
        try {
            LOG.debug("开始应用合并结果...");

            int removedCount = 0;
            int mergedCount = 0;

            // 遍历每个合并组
            for (Map.Entry<String, Set<String>> entry : result.mergeGroups.entrySet()) {
                Set<String> sourceIds = entry.getValue();

                if (sourceIds.size() <= 1) {
                    continue;  // 没有合并，跳过
                }

                // 找到合并后的记忆
                Observation mergedObs = findMergedObservation(sourceIds);
                if (mergedObs == null) {
                    LOG.warn("无法找到合并后的记忆: {}", sourceIds);
                    continue;
                }

                // 删除被合并的原始记忆
                for (String sourceId : sourceIds) {
                    if (!sourceId.equals(mergedObs.getId())) {
                        boolean removed = removeMemoryById(sourceId);
                        if (removed) {
                            removedCount++;
                        }
                    }
                }

                // 更新或创建合并后的记忆
                updateOrCreateMemory(mergedObs);
                mergedCount++;

                LOG.debug("合并记忆组: {} -> {} (删除 {} 个旧记忆)",
                        sourceIds.size(), mergedObs.getId(), sourceIds.size() - 1);
            }

            LOG.info("合并结果应用完成: 合并 {} 组, 删除 {} 个记忆",
                    mergedCount, removedCount);

        } catch (Throwable e) {
            LOG.error("应用合并结果失败", e);
        }
    }

    /**
     * 查找合并后的观察
     *
     * @param sourceIds 源ID集合
     * @return 合并后的观察，如果找不到返回 null
     */
    private Observation findMergedObservation(Set<String> sourceIds) {
        // 重新获取所有观察
        List<Observation> allObs = getAllObservations();

        // 查找合并后的观察（ID在 sourceIds 中，且内容包含多个记忆的标记）
        for (Observation obs : allObs) {
            if (sourceIds.contains(obs.getId())) {
                // 检查是否是合并后的（包含多个记忆的分隔符）
                String content = obs.getContent();
                if (content.contains("; ") && content.length() > 100) {
                    return obs;
                }
            }
        }

        return null;
    }

    /**
     * 根据 ID 删除记忆
     *
     * @param memoryId 记忆ID
     * @return 是否删除成功
     */
    private boolean removeMemoryById(String memoryId) {
        try {
            // 尝试从各个存储中删除
            boolean removed = false;

            // 从工作记忆删除
            try {
                delegate.removeWorking(memoryId);
                removed = true;
            } catch (Throwable e) {
                // 不是工作记忆，继续尝试其他类型
            }

            // 从短期记忆删除
            try {
                delegate.removeShortTerm(memoryId);
                removed = true;
            } catch (Throwable e) {
                // 不是短期记忆，继续尝试其他类型
            }

            // 从长期记忆删除
            try {
                delegate.removeLongTerm(memoryId);
                removed = true;
            } catch (Throwable e) {
                // 不是长期记忆，继续尝试其他类型
            }

            // 从知识记忆删除
            try {
                delegate.removeKnowledge(memoryId);
                removed = true;
            } catch (Throwable e) {
                // 不是知识记忆，忽略
            }

            if (removed) {
                LOG.debug("删除记忆: {}", memoryId);
            }

            return removed;

        } catch (Throwable e) {
            LOG.warn("删除记忆失败: {}", memoryId, e);
            return false;
        }
    }

    /**
     * 更新或创建记忆
     *
     * @param obs 观察对象
     */
    private void updateOrCreateMemory(Observation obs) {
        try {
            // 根据观察类型更新相应的存储
            switch (obs.getType()) {
                case GENERAL:
                    // 更新短期记忆（使用便捷方法，TTL 默认1小时）
                    delegate.putShortTerm(obs.getId(), obs.getContent(), 3600L);
                    break;

                case TASK_RESULT:
                    // 更新长期记忆（使用便捷方法，TTL 默认7天）
                    delegate.putLongTerm(obs.getId(), obs.getContent(), 7 * 24 * 3600L);
                    break;

                case ARCHITECTURE:
                    // 更新知识记忆
                    delegate.putKnowledge(obs.getId(), obs.getContent());
                    break;

                default:
                    LOG.warn("未知的观察类型: {}", obs.getType());
            }

            LOG.debug("更新记忆: {} (类型: {})", obs.getId(), obs.getType());

        } catch (Throwable e) {
            LOG.error("更新记忆失败: {}", obs.getId(), e);
        }
    }

    /**
     * 从 SharedMemoryManager 获取所有观察（用于合并）
     */
    private List<Observation> getAllObservations() {
        List<Observation> observations = new ArrayList<>();

        // 使用 retrieve() 方法获取所有类型的记忆
        // 获取短期记忆
        List<Memory> shortTermMemories = delegate.retrieve(Memory.MemoryType.SHORT_TERM, 1000);
        for (Memory mem : shortTermMemories) {
            if (mem instanceof ShortTermMemory) {
                ShortTermMemory stm = (ShortTermMemory) mem;
                Observation obs = Observation.builder()
                    .id(stm.getId())
                    .content(stm.getContext())
                    .type(Observation.ObservationType.GENERAL)
                    .timestamp(stm.getTimestamp())
                    .build();
                observations.add(obs);
            }
        }

        // 获取长期记忆
        List<Memory> longTermMemories = delegate.retrieve(Memory.MemoryType.LONG_TERM, 1000);
        for (Memory mem : longTermMemories) {
            if (mem instanceof LongTermMemory) {
                LongTermMemory ltm = (LongTermMemory) mem;
                Observation obs = Observation.builder()
                    .id(ltm.getId())
                    .content(ltm.getSummary())
                    .type(Observation.ObservationType.TASK_RESULT)
                    .timestamp(ltm.getTimestamp())
                    .importance(ltm.getImportance())
                    .build();
                observations.add(obs);
            }
        }

        // 获取知识记忆
        List<Memory> knowledgeMemories = delegate.retrieve(Memory.MemoryType.KNOWLEDGE, 1000);
        for (Memory mem : knowledgeMemories) {
            if (mem instanceof KnowledgeMemory) {
                KnowledgeMemory km = (KnowledgeMemory) mem;
                Observation obs = Observation.builder()
                    .id(km.getId())
                    .content(km.getContent())
                    .type(Observation.ObservationType.ARCHITECTURE)
                    .timestamp(km.getTimestamp())
                    .build();
                observations.add(obs);
            }
        }

        return observations;
    }

    /**
     * 获取底层存储管理器（用于兼容）
     */
    public SharedMemoryManager getDelegate() {
        return delegate;
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        if (consolidationExecutor != null && !consolidationExecutor.isShutdown()) {
            LOG.info("关闭 IntelligentMemoryManager...");

            consolidationExecutor.shutdown();
            try {
                if (!consolidationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    consolidationExecutor.shutdownNow();
                }
                LOG.info("IntelligentMemoryManager 已关闭");
            } catch (InterruptedException e) {
                consolidationExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
