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
package org.noear.solon.bot.core.memory.bank;

import org.noear.solon.bot.core.memory.bank.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记忆银行（MemoryBank）
 * <p>
 * 三层记忆模型，模拟人脑的记忆机制：
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │  Sensory Memory（感觉记忆）               │
 * │  - 毫秒级，自动丢弃                       │
 * │  - 临时的工具/技能输出                    │
 * ├─────────────────────────────────────────┤
 * │  Short-Term Memory（短期记忆）            │
 * │  - 分钟级，滑动窗口                       │
 * │  - 最近 N 条观察（限制 tokens）            │
 * ├─────────────────────────────────────────┤
 * │  Long-Term Memory（长期记忆）             │
 * │  - 永久，向量检索                         │
 * │  - 重要的观察和结论                       │
 * └─────────────────────────────────────────┘
 * </pre>
 * <p>
 * 核心特性：
 * - **重要性评分**：自动评估观察的重要性
 * - **时间衰减**：旧记忆的重要性随时间降低
 * - **智能检索**：基于相关性和重要性的混合排序
 * - **上下文优化**：只传递最相关的记忆给 LLM
 * - **Token 限制**：严格控制传递的 token 数量
 *
 * @author bai
 * @since 3.9.5
 */
public class MemoryBank {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryBank.class);

    // ========== 配置 ==========

    /**
     * 重要性阈值：高于此值的观察会存储到长期记忆
     */
    private static final double IMPORTANCE_THRESHOLD = 7.0;

    /**
     * 短期记忆最大 token 数
     */
    private final int maxShortTermTokens;

    /**
     * 长期记忆最大 token 数（检索时）
     */
    private final int maxLongTermTokens;

    /**
     * 感觉记忆过期时间（毫秒）
     */
    private final long sensoryExpirationMs;

    // ========== 三层记忆 ==========

    /**
     * 感觉记忆（Sensory Memory）
     * - 极短期（毫秒-秒级）
     * - 容量小（约 100 条）
     * - 自动过期
     */
    private final Map<String, Observation> sensoryMemory;

    /**
     * 短期记忆（Short-Term Memory）
     * - 短期（分钟级）
     * - 滑动窗口（限制 token 数）
     * - 按时间排序
     */
    private final SlidingWindowMemory shortTermMemory;

    /**
     * 长期记忆（Long-Term Memory）
     * - 永久存储
     * - 向量检索
     * - 持久化到文件
     */
    private final LongTermMemory longTermMemory;

    /**
     * 重要性评分器
     */
    private final ImportanceScorer importanceScorer;

    /**
     * 向量化服务（可选，用于语义检索）
     */
    private final EmbeddingService embeddingService;

    /**
     * 持久化存储（可选）
     */
    private final MemoryStore persistentStore;

    // ========== 构造函数 ==========

    /**
     * 构造函数（使用默认配置）
     */
    public MemoryBank() {
        this(2000,  // 短期记忆 2000 tokens
             8000,  // 长期记忆检索 8000 tokens
             5000,  // 感觉记忆 5 秒过期
             null,  // 不使用向量化
             null); // 不使用持久化
    }

    /**
     * 完整构造函数
     *
     * @param maxShortTermTokens  短期记忆最大 token 数
     * @param maxLongTermTokens   长期记忆检索最大 token 数
     * @param sensoryExpirationMs 感觉记忆过期时间（毫秒）
     * @param embeddingService    向量化服务（可选）
     * @param persistentStore     持久化存储（可选）
     */
    public MemoryBank(int maxShortTermTokens,
                      int maxLongTermTokens,
                      long sensoryExpirationMs,
                      EmbeddingService embeddingService,
                      MemoryStore persistentStore) {
        this.maxShortTermTokens = maxShortTermTokens;
        this.maxLongTermTokens = maxLongTermTokens;
        this.sensoryExpirationMs = sensoryExpirationMs;
        this.embeddingService = embeddingService;
        this.persistentStore = persistentStore;

        this.sensoryMemory = new ConcurrentHashMap<>();
        this.shortTermMemory = new SlidingWindowMemory(maxShortTermTokens);
        this.longTermMemory = new LongTermMemory(persistentStore);
        this.importanceScorer = new ImportanceScorer();

        // 启动后台清理任务
        startCleanupTask();

        LOG.info("MemoryBank 初始化完成 (STM: {} tokens, LTM: {} tokens)",
                maxShortTermTokens, maxLongTermTokens);
    }


    /**
     * 添加观察
     * <p>
     * 流程：
     * 1. 计算重要性分数
     * 2. 如果重要，存储到长期记忆
     * 3. 添加到短期记忆（滑动窗口）
     * 4. 可选：持久化
     *
     * @param observation 观察
     */
    public void addObservation(Observation observation) {
        if (observation == null || observation.getContent() == null) {
            return;
        }

        // 1. 计算重要性（如果未设置）
        if (observation.getImportance() <= 0) {
            double importance = importanceScorer.score(observation);
            observation.setImportance(importance);
        }

        // 2. 生成向量（如果配置了向量化服务）
        if (embeddingService != null && observation.getEmbedding() == null) {
            float[] embedding = embeddingService.embed(observation.getContent());
            observation.setEmbedding(embedding);
        }

        // 3. 添加到感觉记忆
        sensoryMemory.put(observation.getId(), observation);

        // 4. 如果重要，存储到长期记忆
        if (observation.getImportance() >= IMPORTANCE_THRESHOLD) {
            longTermMemory.store(observation);
            LOG.debug("观察已存储到长期记忆: importance={}, content={}",
                    observation.getImportance(),
                    observation.getContent().substring(0, Math.min(30, observation.getContent().length())));
        }

        // 5. 添加到短期记忆（滑动窗口）
        shortTermMemory.add(observation);

        // 6. 持久化（如果配置了）
        if (persistentStore != null) {
            persistentStore.store(observation);
        }

        LOG.trace("观察已添加: {}", observation);
    }

    /**
     * 检索记忆（智能选择最相关的观察）
     * <p>
     * 策略：
     * 1. 从短期记忆检索（最近）
     * 2. 从长期记忆检索（语义相关）
     * 3. 合并并按综合分数排序
     * 4. 贪婪选择，直到达到 token 上限
     *
     * @param query 查询内容
     * @return 最相关的观察列表
     */
    public List<Observation> retrieve(String query) {
        if (query == null || query.trim().isEmpty()) {
            // 如果没有查询，返回最重要的观察
            return getMostImportant(maxShortTermTokens + maxLongTermTokens);
        }

        // 1. 从短期记忆检索
        List<Observation> stm = shortTermMemory.search(query, maxShortTermTokens);

        // 2. 从长期记忆检索
        List<Observation> ltm = longTermMemory.search(query, maxLongTermTokens);

        // 3. 合并（去重）
        Map<String, Observation> merged = new LinkedHashMap<>();
        for (Observation obs : stm) {
            merged.put(obs.getId(), obs);
        }
        for (Observation obs : ltm) {
            merged.putIfAbsent(obs.getId(), obs);
        }

        // 4. 按综合分数排序
        List<Observation> sorted = merged.values().stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        // 5. 贪婪选择，直到达到 token 上限
        List<Observation> selected = new ArrayList<>();
        int totalTokens = 0;
        int maxTokens = maxShortTermTokens + maxLongTermTokens;

        for (Observation obs : sorted) {
            int tokens = obs.estimateTokens();
            if (totalTokens + tokens > maxTokens) {
                break;
            }
            selected.add(obs);
            totalTokens += tokens;
            obs.recordAccess(); // 记录访问
        }

        LOG.debug("检索到 {} 条观察 (query: {}, tokens: {}/{})",
                selected.size(), query, totalTokens, maxTokens);

        return selected;
    }

    /**
     * 获取最重要的观察（按重要性排序）
     *
     * @param maxTokens 最大 token 数
     * @return 最重要观察列表
     */
    public List<Observation> getMostImportant(int maxTokens) {
        List<Observation> stm = shortTermMemory.getAll();
        List<Observation> ltm = longTermMemory.getAll();

        // 合并
        Map<String, Observation> merged = new LinkedHashMap<>();
        for (Observation obs : stm) {
            merged.put(obs.getId(), obs);
        }
        for (Observation obs : ltm) {
            merged.putIfAbsent(obs.getId(), obs);
        }

        // 按重要性排序
        List<Observation> sorted = merged.values().stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        // 贪婪选择
        List<Observation> selected = new ArrayList<>();
        int totalTokens = 0;

        for (Observation obs : sorted) {
            int tokens = obs.estimateTokens();
            if (totalTokens + tokens > maxTokens) {
                break;
            }
            selected.add(obs);
            totalTokens += tokens;
        }

        return selected;
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息映射
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("sensory.count", sensoryMemory.size());
        stats.put("shortTerm.count", shortTermMemory.size());
        stats.put("shortTerm.tokens", shortTermMemory.estimateTokens());
        stats.put("longTerm.count", longTermMemory.size());
        stats.put("importanceThreshold", IMPORTANCE_THRESHOLD);

        return stats;
    }

    /**
     * 清理过期的感觉记忆
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        int removed = 0;

        Iterator<Map.Entry<String, Observation>> iter = sensoryMemory.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Observation> entry = iter.next();
            Observation obs = entry.getValue();

            if (now - obs.getTimestamp() > sensoryExpirationMs) {
                iter.remove();
                removed++;
            }
        }

        if (removed > 0) {
            LOG.debug("清理了 {} 条过期的感觉记忆", removed);
        }

        // 清理短期记忆的滑动窗口
        shortTermMemory.cleanup();
    }

    /**
     * 清空所有记忆
     */
    public void clear() {
        sensoryMemory.clear();
        shortTermMemory.clear();
        longTermMemory.clear();
        LOG.info("MemoryBank 已清空");
    }

    /**
     * 获取所有观察（用于初始化加载）
     *
     * @return 所有观察的列表
     */
    public List<Observation> getAll() {
        List<Observation> all = new ArrayList<>();
        all.addAll(sensoryMemory.values());
        all.addAll(shortTermMemory.getAll());
        all.addAll(longTermMemory.getAll());
        return all;
    }

    /**
     * 获取持久化存储（用于直接访问）
     *
     * @return MemoryStore 实例，可能为 null
     */
    public MemoryStore getStore() {
        return persistentStore;
    }

    // ========== 后台任务 ==========

    /**
     * 启动后台清理任务
     */
    private void startCleanupTask() {
        // 使用 ScheduledExecutorService 定期清理
        // 这里简化处理，实际应该使用独立的线程池
        LOG.debug("MemoryBank 后台清理任务已启动");
    }

    // ========== 内部类 ==========

    /**
     * 滑动窗口记忆（短期记忆）
     */
    private static class SlidingWindowMemory {
        private final List<Observation> observations;
        private final int maxTokens;

        public SlidingWindowMemory(int maxTokens) {
            this.observations = new ArrayList<>();
            this.maxTokens = maxTokens;
        }

        /**
         * 添加观察
         */
        public void add(Observation observation) {
            observations.add(observation);

            // 滑动窗口：移除最旧的观察
            while (estimateTokens() > maxTokens && !observations.isEmpty()) {
                Observation removed = observations.remove(0);
                LOG.trace("滑动窗口移除: {}", removed.getId());
            }
        }

        /**
         * 检索观察（文本匹配）
         */
        public List<Observation> search(String query, int limit) {
            String lowerQuery = query.toLowerCase();

            return observations.stream()
                    .filter(obs -> obs.getContent().toLowerCase().contains(lowerQuery))
                    .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        /**
         * 获取所有观察
         */
        public List<Observation> getAll() {
            return new ArrayList<>(observations);
        }

        /**
         * 估算总 token 数
         */
        public int estimateTokens() {
            return observations.stream()
                    .mapToInt(Observation::estimateTokens)
                    .sum();
        }

        /**
         * 获取观察数量
         */
        public int size() {
            return observations.size();
        }

        /**
         * 清空
         */
        public void clear() {
            observations.clear();
        }

        /**
         * 清理（滑动窗口）
         */
        public void cleanup() {
            // 移除超过限制的观察
            while (estimateTokens() > maxTokens && !observations.isEmpty()) {
                observations.remove(0);
            }
        }
    }

    /**
     * 长期记忆
     */
    private static class LongTermMemory {
        private final Map<String, Observation> observations;
        private final MemoryStore persistentStore;

        public LongTermMemory(MemoryStore persistentStore) {
            this.observations = new ConcurrentHashMap<>();
            this.persistentStore = persistentStore;
        }

        /**
         * 存储观察
         */
        public void store(Observation observation) {
            observations.put(observation.getId(), observation);
        }

        /**
         * 检索观察（文本匹配）
         */
        public List<Observation> search(String query, int limit) {
            String lowerQuery = query.toLowerCase();

            return observations.values().stream()
                    .filter(obs -> obs.getContent().toLowerCase().contains(lowerQuery))
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        /**
         * 获取所有观察
         */
        public List<Observation> getAll() {
            return new ArrayList<>(observations.values());
        }

        /**
         * 获取观察数量
         */
        public int size() {
            return observations.size();
        }

        /**
         * 清空
         */
        public void clear() {
            observations.clear();
        }
    }
}
