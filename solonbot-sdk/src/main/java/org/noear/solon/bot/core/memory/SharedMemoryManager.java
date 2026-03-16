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
package org.noear.solon.bot.core.memory;

import org.noear.solon.bot.core.memory.bank.MemoryBank;
import org.noear.solon.bot.core.memory.bank.Observation;
import org.noear.solon.bot.core.memory.bank.store.FileMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 共享记忆管理器（基于 MemoryBank 架构）
 *
 * 负责所有子代理之间的记忆存储、检索和生命周期管理
 *
 * @author bai
 * @since 3.9.5
 */
public class SharedMemoryManager {
    private static final Logger LOG = LoggerFactory.getLogger(SharedMemoryManager.class);

    // 内存存储（分层设计）
    private final Map<String, WorkingMemory> workingCache = new ConcurrentHashMap<>();
    private final Map<String, Memory> shortTermCache = new ConcurrentHashMap<>();
    private final Map<String, Memory> longTermCache = new ConcurrentHashMap<>();
    private final Map<String, Memory> knowledgeCache = new ConcurrentHashMap<>();

    // 持久化存储（使用 MemoryBank 架构）
    private final MemoryBank memoryBank;
    private final FileMemoryStore fileStore;  // 保存直接引用用于加载

    // 定期清理执行器
    private final ScheduledExecutorService cleanupExecutor;

    // 索引（用于快速检索）
    private final Map<String, Set<String>> tagIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> keywordIndex = new ConcurrentHashMap<>();

    // 配置
    private final long shortTermTtl;
    private final long longTermTtl;
    private final long cleanupInterval;
    private final boolean persistOnWrite;
    private final int maxShortTermCount;
    private final int maxLongTermCount;

    // 性能监控：内存使用上限
    private static final long MAX_MEMORY_USAGE = 100 * 1024 * 1024; // 100MB
    private static final double MEMORY_WARNING_THRESHOLD = 0.8;     // 80% 警告阈值

    /**
     * 构造函数（使用默认配置）
     */
    public SharedMemoryManager(Path path) {
        this(path, 3600_000L, 7 * 24 * 3600_000L, 300_000L, true, 1000, 500);
    }

    /**
     * 完整构造函数
     *
     * @param path 路径
     * @param shortTermTtl 短期记忆TTL（毫秒）
     * @param longTermTtl 长期记忆TTL（毫秒）
     * @param cleanupInterval 清理间隔（毫秒）
     * @param persistOnWrite 写入时是否立即持久化
     * @param maxShortTermCount 短期记忆最大数量
     * @param maxLongTermCount 长期记忆最大数量
     */
    public SharedMemoryManager(Path path,
                               long shortTermTtl,
                               long longTermTtl,
                               long cleanupInterval,
                               boolean persistOnWrite,
                               int maxShortTermCount,
                               int maxLongTermCount) {
        // 初始化 FileMemoryStore
        this.fileStore = new FileMemoryStore(path.toAbsolutePath().toString());

        // 初始化 MemoryBank（使用 FileMemoryStore）
        this.memoryBank = new MemoryBank(
            2000,   // 短期记忆 2000 tokens
            8000,   // 长期记忆检索 8000 tokens
            5000,   // 感觉记忆 5 秒过期
            null,   // 不使用向量化服务
            fileStore   // 使用文件持久化
        );
        this.shortTermTtl = shortTermTtl;
        this.longTermTtl = longTermTtl;
        this.cleanupInterval = cleanupInterval;
        this.persistOnWrite = persistOnWrite;
        this.maxShortTermCount = maxShortTermCount;
        this.maxLongTermCount = maxLongTermCount;

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryCleanupThread");
            t.setDaemon(true);
            return t;
        });

        // 初始化存储（从文件加载所有记忆）
        initStorage();

        // 启动定期清理任务
        this.cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredMemories,
            this.cleanupInterval,
            this.cleanupInterval,
            TimeUnit.MILLISECONDS
        );

        LOG.info("共享记忆系统初始化完成: shortTermTtl={}ms, longTermTtl={}ms",
                 shortTermTtl, longTermTtl);
    }

    /**
     * 将 Memory 转换为 Observation
     */
    private Observation convertMemoryToObservation(Memory memory) {
        Observation.ObservationType type;

        if (memory instanceof ShortTermMemory) {
            type = Observation.ObservationType.GENERAL;
        } else if (memory instanceof LongTermMemory) {
            type = Observation.ObservationType.TASK_RESULT;
        } else if (memory instanceof KnowledgeMemory) {
            type = Observation.ObservationType.ARCHITECTURE;
        } else {
            type = Observation.ObservationType.GENERAL;
        }

        Observation.ObservationBuilder builder = Observation.builder()
            .content(getMemoryContent(memory))
            .type(type)
            .timestamp(memory.getTimestamp());

        if (memory.getId() != null) {
            builder.id(memory.getId());
        }

        // 设置重要性（永久记忆需要高重要性）
        if (memory instanceof LongTermMemory) {
            builder.importance(((LongTermMemory) memory).getImportance());
        } else if (memory instanceof KnowledgeMemory) {
            // 永久记忆：高重要性（8.0-10.0），确保持久化
            builder.importance(9.0);
        } else {
            // 默认重要性
            builder.importance(5.0);
        }

        return builder.build();
    }

    /**
     * 获取 Memory 的内容
     */
    private String getMemoryContent(Memory memory) {
        if (memory instanceof ShortTermMemory) {
            return ((ShortTermMemory) memory).getContext();
        } else if (memory instanceof LongTermMemory) {
            return ((LongTermMemory) memory).getSummary();
        } else if (memory instanceof KnowledgeMemory) {
            return ((KnowledgeMemory) memory).getContent();
        }
        return "";
    }

    /**
     * 存储记忆
     *
     * @param memory 记忆对象
     */
    public void store(Memory memory) {
        if (memory == null) {
            return;
        }

        // 工作记忆特殊处理（不持久化）
        if (memory instanceof WorkingMemory) {
            storeWorking((WorkingMemory) memory);
            return;
        }

        // 自动分配ID
        if (memory.getId() == null) {
            memory.setId(UUID.randomUUID().toString());
        }

        Map<String, Memory> cache = getCacheByType(memory.type);

        // 检查数量限制
        if (memory.type == Memory.MemoryType.SHORT_TERM && cache.size() >= maxShortTermCount) {
            // 删除最旧的短期记忆
            removeOldestMemory(cache);
        } else if (memory.type == Memory.MemoryType.LONG_TERM && cache.size() >= maxLongTermCount) {
            // 删除最不重要的长期记忆
            removeLeastImportantMemory(cache);
        }

        // 存储到缓存
        cache.put(memory.getId(), memory);

        // 建立索引
        buildIndex(memory);

        // 异步持久化
        if (persistOnWrite) {
            persistAsync(memory);
        }

        LOG.debug("记忆已存储: type={}, id={}", memory.type, memory.id);
    }

    /**
     * 检索记忆（按类型）
     *
     * @param type 记忆类型
     * @param limit 最大数量
     * @return 记忆列表
     */
    public List<Memory> retrieve(Memory.MemoryType type, int limit) {
        Map<String, Memory> cache = getCacheByType(type);

        return cache.values().stream()
            .filter(m -> !m.isExpired())
            .sorted((a, b) -> Long.compare(b.timestamp, a.timestamp))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 按标签检索长期记忆
     *
     * @param tag 标签
     * @param limit 最大数量
     * @return 长期记忆列表
     */
    public List<LongTermMemory> retrieveByTag(String tag, int limit) {
        Set<String> memoryIds = tagIndex.getOrDefault(tag, Collections.emptySet());

        return memoryIds.stream()
            .map(id -> (LongTermMemory) longTermCache.get(id))
            .filter(Objects::nonNull)
            .filter(m -> !m.isExpired())
            .sorted((a, b) -> Double.compare(b.getImportance(), a.getImportance()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 按关键词检索知识库
     *
     * @param keyword 关键词
     * @param limit 最大数量
     * @return 知识库记忆列表
     */
    public List<KnowledgeMemory> searchKnowledge(String keyword, int limit) {
        Set<String> memoryIds = keywordIndex.getOrDefault(keyword, Collections.emptySet());

        return memoryIds.stream()
            .map(id -> (KnowledgeMemory) knowledgeCache.get(id))
            .filter(Objects::nonNull)
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 全文搜索（跨所有记忆类型）
     *
     * @param query 搜索关键词
     * @param limit 最大数量
     * @return 记忆列表
     */
    public List<Memory> search(String query, int limit) {
        String lowerQuery = query.toLowerCase();

        return Stream.of(
            shortTermCache.values().stream(),
            longTermCache.values().stream(),
            knowledgeCache.values().stream()
        )
        .flatMap(s -> s)
        .filter(m -> !m.isExpired())
        .filter(m -> matchesQuery(m, lowerQuery))
        .sorted((a, b) -> Long.compare(b.timestamp, a.timestamp))
        .limit(limit)
        .collect(Collectors.toList());
    }

    /**
     * 获取记忆统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("workingCount", workingCache.size());
        stats.put("shortTermCount", shortTermCache.size());
        stats.put("longTermCount", longTermCache.size());
        stats.put("knowledgeCount", knowledgeCache.size());
        stats.put("tagIndexSize", tagIndex.size());
        stats.put("keywordIndexSize", keywordIndex.size());

        // 添加 MemoryBank 统计
        Map<String, Object> bankStats = memoryBank.getStats();
        stats.putAll(bankStats);

        // 添加内存使用监控
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryUsageRatio = (double) usedMemory / MAX_MEMORY_USAGE;

        stats.put("memoryUsed", formatBytes(usedMemory));
        stats.put("memoryTotal", formatBytes(totalMemory));
        stats.put("memoryMax", formatBytes(MAX_MEMORY_USAGE));
        stats.put("memoryUsagePercent", String.format("%.2f%%", memoryUsageRatio * 100));

        // 超过阈值时发出警告
        if (memoryUsageRatio > MEMORY_WARNING_THRESHOLD) {
            LOG.warn("[WARN] 内存使用率过高: {}/{} ({}%)",
                    formatBytes(usedMemory),
                    formatBytes(MAX_MEMORY_USAGE),
                    String.format("%.2f", memoryUsageRatio * 100));
        }

        return stats;
    }

    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 检查内存使用是否超过限制
     *
     * @return true 如果内存使用超过限制
     */
    public boolean isMemoryOverLimit() {
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        return usedMemory > MAX_MEMORY_USAGE;
    }

    // ========== 工作记忆方法 ==========

    /**
     * 创建短期记忆（使用配置的TTL）
     *
     * @param agentId Agent ID
     * @param context 上下文内容
     * @param taskId 任务ID
     * @return ShortTermMemory 实例
     */
    public ShortTermMemory createShortTermMemory(String agentId, String context, String taskId) {
        return new ShortTermMemory(agentId, context, taskId, shortTermTtl);
    }

    /**
     * 创建长期记忆（使用配置的TTL）
     *
     * @param summary 摘要
     * @param sourceAgent 源Agent
     * @param tags 标签列表
     * @return LongTermMemory 实例
     */
    public LongTermMemory createLongTermMemory(String summary, String sourceAgent, List<String> tags) {
        return new LongTermMemory(summary, sourceAgent, tags, longTermTtl);
    }

    /**
     * 存储工作记忆（仅内存，不持久化）
     *
     * @param memory 工作记忆对象
     */
    public void storeWorking(WorkingMemory memory) {
        if (memory == null) {
            return;
        }

        // 自动分配ID
        if (memory.getId() == null) {
            memory.setId(UUID.randomUUID().toString());
        }

        workingCache.put(memory.getTaskId(), memory);

        LOG.debug("工作记忆已存储: taskId={}, id={}", memory.getTaskId(), memory.getId());
    }

    /**
     * 获取工作记忆
     *
     * @param taskId 任务ID
     * @return 工作记忆对象，不存在返回 null
     */
    public WorkingMemory getWorking(String taskId) {
        WorkingMemory memory = workingCache.get(taskId);
        if (memory != null) {
            // 更新最后访问时间
            memory.setLastAccessTime(System.currentTimeMillis());

            // 检查是否过期
            if (memory.isExpired()) {
                workingCache.remove(taskId);
                LOG.debug("工作记忆已过期: taskId={}", taskId);
                return null;
            }
        }
        return memory;
    }

    /**
     * 移除工作记忆
     *
     * @param taskId 任务ID
     */
    public void removeWorking(String taskId) {
        WorkingMemory memory = workingCache.remove(taskId);
        if (memory != null) {
            LOG.debug("工作记忆已移除: taskId={}", taskId);
        }
    }

    /**
     * 移除短期记忆
     *
     * @param key 键
     */
    public void removeShortTerm(String key) {
        Memory memory = shortTermCache.remove(key);
        if (memory != null) {
            LOG.debug("短期记忆已移除: key={}", key);
        }
    }

    /**
     * 移除长期记忆
     *
     * @param key 键
     */
    public void removeLongTerm(String key) {
        Memory memory = longTermCache.remove(key);
        if (memory != null) {
            LOG.debug("长期记忆已移除: key={}", key);
        }
    }

    /**
     * 移除知识记忆
     *
     * @param key 键
     */
    public void removeKnowledge(String key) {
        Memory memory = knowledgeCache.remove(key);
        if (memory != null) {
            LOG.debug("知识记忆已移除: key={}", key);
        }
    }

    /**
     * 完成工作记忆
     *
     * @param taskId 任务ID
     */
    public void completeWorking(String taskId) {
        WorkingMemory memory = workingCache.get(taskId);
        if (memory != null) {
            memory.complete();
            LOG.debug("工作记忆已完成: taskId={}, step={}", taskId, memory.getStep());

            // 可以选择转移到短期记忆
            // ShortTermMemory stm = convertToShortTerm(memory);
            // store(stm);

            workingCache.remove(taskId);
        }
    }

    /**
     * 清空所有工作记忆
     */
    public void clearWorking() {
        int size = workingCache.size();
        workingCache.clear();
        LOG.info("所有工作记忆已清空: count={}", size);
    }

    /**
     * 清空所有记忆（仅内存，不删除持久化文件）
     */
    public void clear() {
        shortTermCache.clear();
        longTermCache.clear();
        knowledgeCache.clear();
        tagIndex.clear();
        keywordIndex.clear();
        LOG.info("所有记忆已清空");
    }

    // ========== 便捷方法（简化键值对接口） ==========

    /**
     * 存储短期记忆（便捷方法）
     *
     * @param key 键
     * @param value 值
     * @param ttlSeconds 过期时间（秒）
     */
    public void putShortTerm(String key, String value, long ttlSeconds) {
        ShortTermMemory memory = new ShortTermMemory();
        memory.setId(key);
        memory.setContext(value);
        memory.setTtl(ttlSeconds * 1000L);  // 转换为毫秒
        memory.setTimestamp(System.currentTimeMillis());
        store(memory);
    }

    /**
     * 存储长期记忆（便捷方法）
     *
     * @param key 键
     * @param value 值
     * @param ttlSeconds 过期时间（秒）
     */
    public void putLongTerm(String key, String value, long ttlSeconds) {
        LongTermMemory memory = new LongTermMemory();
        memory.setId(key);
        memory.setSummary(value);
        memory.setTtl(ttlSeconds * 1000L);  // 转换为毫秒
        memory.setTimestamp(System.currentTimeMillis());
        memory.setTags(Collections.emptyList());
        store(memory);
    }

    /**
     * 存储知识记忆（便捷方法）
     *
     * @param key 键
     * @param value 值
     */
    public void putKnowledge(String key, String value) {
        KnowledgeMemory memory = new KnowledgeMemory();
        memory.setId(key);
        memory.setContent(value);
        memory.setTimestamp(System.currentTimeMillis());
        memory.setKeywords(Collections.emptyList());
        store(memory);
    }

    /**
     * 获取记忆（便捷方法，按优先级从短期、长期、知识记忆中查找）
     *
     * @param key 键
     * @return 值，不存在返回 null
     */
    public String get(String key) {
        // 1. 尝试从短期记忆获取
        Memory stm = shortTermCache.get(key);
        if (stm != null && !stm.isExpired()) {
            if (stm instanceof ShortTermMemory) {
                return ((ShortTermMemory) stm).getContext();
            }
        }

        // 2. 尝试从长期记忆获取
        Memory ltm = longTermCache.get(key);
        if (ltm != null && !ltm.isExpired()) {
            if (ltm instanceof LongTermMemory) {
                return ((LongTermMemory) ltm).getSummary();
            }
        }

        // 3. 尝试从知识记忆获取
        Memory km = knowledgeCache.get(key);
        if (km != null) {
            if (km instanceof KnowledgeMemory) {
                return ((KnowledgeMemory) km).getContent();
            }
        }

        return null;
    }

    /**
     * 关闭管理器
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
            LOG.info("共享记忆管理器已关闭");
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== 私有方法 ==========

    /**
     * 建立索引
     */
    private void buildIndex(Memory memory) {
        if (memory instanceof LongTermMemory) {
            LongTermMemory ltm = (LongTermMemory) memory;
            ltm.getTags().forEach(tag ->
                tagIndex.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet())
                       .add(memory.getId())
            );
        } else if (memory instanceof KnowledgeMemory) {
            KnowledgeMemory km = (KnowledgeMemory) memory;
            km.getKeywords().forEach(kw ->
                keywordIndex.computeIfAbsent(kw, k -> ConcurrentHashMap.newKeySet())
                          .add(memory.getId())
            );
        }
    }

    /**
     * 查询匹配
     */
    private boolean matchesQuery(Memory memory, String query) {
        if (memory instanceof ShortTermMemory) {
            return ((ShortTermMemory) memory).getContext().toLowerCase().contains(query);
        } else if (memory instanceof LongTermMemory) {
            LongTermMemory ltm = (LongTermMemory) memory;
            return ltm.getSummary().toLowerCase().contains(query) ||
                   ltm.getTags().stream().anyMatch(tag -> tag.toLowerCase().contains(query));
        } else if (memory instanceof KnowledgeMemory) {
            KnowledgeMemory km = (KnowledgeMemory) memory;
            return km.getContent().toLowerCase().contains(query) ||
                   km.getKeywords().stream().anyMatch(kw -> kw.toLowerCase().contains(query));
        }
        return false;
    }

    /**
     * 清理过期记忆
     * 性能优化：添加内存使用检查
     */
    private void cleanupExpiredMemories() {
        int removed = 0;

        // 清理工作记忆（特殊逻辑）
        removed += cleanupWorkingMemories();

        // 清理短期和长期记忆
        removed += cleanCache(shortTermCache);
        removed += cleanCache(longTermCache);
        // knowledgeCache 不清理

        if (removed > 0) {
            LOG.info("清理了 {} 条过期记忆", removed);
        }

        // 检查内存使用情况
        checkMemoryUsage();
    }

    /**
     * 检查内存使用情况
     */
    private void checkMemoryUsage() {
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryUsageRatio = (double) usedMemory / MAX_MEMORY_USAGE;

        if (memoryUsageRatio > MEMORY_WARNING_THRESHOLD) {
            LOG.warn("[WARN] 内存使用警告: 已使用 {}/{} ({}%)",
                    formatBytes(usedMemory),
                    formatBytes(MAX_MEMORY_USAGE),
                    String.format("%.2f", memoryUsageRatio * 100));

            // 如果超过限制，触发紧急清理
            if (memoryUsageRatio > 1.0) {
                LOG.error("[ERROR] 内存使用超过限制，触发紧急清理");
                emergencyMemoryCleanup();
            }
        }
    }

    /**
     * 紧急内存清理
     */
    private void emergencyMemoryCleanup() {
        LOG.warn("开始紧急内存清理...");

        // 清理所有短期记忆
        int cleared = shortTermCache.size();
        shortTermCache.clear();

        // 清理索引
        tagIndex.clear();
        keywordIndex.clear();

        LOG.warn("紧急清理完成，清理了 {} 条短期记忆", cleared);

        // 建议垃圾回收
        System.gc();
    }

    /**
     * 清理过期的工作记忆
     */
    private int cleanupWorkingMemories() {
        int removed = 0;

        workingCache.entrySet().removeIf(entry -> {
            WorkingMemory memory = entry.getValue();

            // 检查 TTL
            boolean expired = memory.isExpired();

            // 检查是否完成
            boolean completed = "completed".equals(memory.getStatus())
                             || "failed".equals(memory.getStatus());

            // 检查最后访问时间（超过5分钟未访问）
            boolean idle = memory.isIdle(300_000); // 5分钟

            if (expired || completed || idle) {
                LOG.debug("清理工作记忆: taskId={}, reason={}, status={}",
                    memory.getTaskId(),
                    expired ? "expired" : completed ? "completed" : "idle",
                    memory.getStatus());
                return true;
            }
            return false;
        });

        return removed;
    }

    /**
     * 清理缓存
     */
    private int cleanCache(Map<String, Memory> cache) {
        Iterator<Map.Entry<String, Memory>> it = cache.entrySet().iterator();
        int removed = 0;

        while (it.hasNext()) {
            Map.Entry<String, Memory> entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * 删除最旧的记忆
     */
    private void removeOldestMemory(Map<String, Memory> cache) {
        cache.entrySet().stream()
            .min(Comparator.comparingLong(e -> e.getValue().getTimestamp()))
            .ifPresent(entry -> {
                cache.remove(entry.getKey());
                LOG.debug("删除最旧的短期记忆: id={}", entry.getKey());
            });
    }

    /**
     * 删除最不重要的长期记忆
     */
    private void removeLeastImportantMemory(Map<String, Memory> cache) {
        cache.entrySet().stream()
            .filter(e -> e.getValue() instanceof LongTermMemory)
            .min(Comparator.comparingDouble(e -> ((LongTermMemory) e.getValue()).getImportance()))
            .ifPresent(entry -> {
                cache.remove(entry.getKey());
                LOG.debug("删除最不重要的长期记忆: id={}", entry.getKey());
            });
    }

    /**
     * 异步持久化到文件（使用 MemoryBank）
     */
    private void persistAsync(Memory memory) {
        Observation obs = convertMemoryToObservation(memory);
        memoryBank.addObservation(obs);
    }

    /**
     * 启动时加载持久化的记忆
     */
    private void initStorage() {
        try {
            // 从持久化存储加载所有观察
            List<Observation> observations = fileStore.loadAll();
            LOG.info("从存储加载了 {} 条观察", observations.size());

            // 将 Observation 转换并缓存到对应的 Memory 类型
            int loadedCount = 0;
            int skippedCount = 0;

            for (Observation obs : observations) {
                try {
                    // 根据类型转换
                    Memory memory = null;
                    if (obs.getType() == Observation.ObservationType.GENERAL) {
                        ShortTermMemory stm = new ShortTermMemory();
                        stm.setId(obs.getId());
                        stm.setContext(obs.getContent());
                        stm.setTimestamp(obs.getTimestamp());
                        memory = stm;
                    } else if (obs.getType() == Observation.ObservationType.TASK_RESULT) {
                        LongTermMemory ltm = new LongTermMemory();
                        ltm.setId(obs.getId());
                        ltm.setSummary(obs.getContent());
                        ltm.setTimestamp(obs.getTimestamp());
                        ltm.setImportance((float) obs.getImportance());
                        ltm.setTags(new ArrayList<>());
                        memory = ltm;
                    } else if (obs.getType() == Observation.ObservationType.ARCHITECTURE) {
                        KnowledgeMemory km = new KnowledgeMemory();
                        km.setId(obs.getId());
                        km.setContent(obs.getContent());
                        km.setTimestamp(obs.getTimestamp());
                        km.setKeywords(new ArrayList<>());
                        memory = km;
                    }

                    if (memory != null && !memory.isExpired()) {
                        store(memory);
                        loadedCount++;
                    } else {
                        skippedCount++;
                    }
                } catch (Exception e) {
                    LOG.warn("加载单个观察失败: id={}, error={}", obs.getId(), e.getMessage());
                    skippedCount++;
                }
            }

            Map<String, Object> stats = memoryBank.getStats();
            LOG.info("共享记忆加载完成: loaded={}, skipped={}, stats={}", loadedCount, skippedCount, stats);

        } catch (Exception e) {
            LOG.warn("共享记忆初始化失败: error={}", e.getMessage(), e);
            // 不抛出异常，允许系统继续运行
        }
    }

    /**
     * 加载指定类型的记忆（已弃用，使用 initStorage 替代）
     */
    @Deprecated
    private void loadMemories(Memory.MemoryType type) {
        // 不再使用，已整合到 initStorage 中
    }

    /**
     * 根据类型获取缓存
     */
    private Map<String, Memory> getCacheByType(Memory.MemoryType type) {
        switch (type) {
            case WORKING:
                // 工作记忆不返回，因为它的类型不同
                throw new IllegalArgumentException("Working memory should use dedicated methods: getWorking(), storeWorking()");
            case SHORT_TERM:
                return shortTermCache;
            case LONG_TERM:
                return longTermCache;
            case KNOWLEDGE:
                return knowledgeCache;
            default:
                throw new IllegalArgumentException("Unknown memory type: " + type);
        }
    }

}
