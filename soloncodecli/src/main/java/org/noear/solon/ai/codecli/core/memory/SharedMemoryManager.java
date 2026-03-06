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
package org.noear.solon.ai.codecli.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 共享记忆管理器
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

    // 持久化存储
    private final MemoryStore memoryStore;

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

    /**
     * 构造函数（使用默认配置）
     */
    public SharedMemoryManager(String workDir) {
        this(workDir, 3600_000L, 7 * 24 * 3600_000L, 300_000L, true, 1000, 500);
    }

    /**
     * 完整构造函数
     *
     * @param workDir 工作目录
     * @param shortTermTtl 短期记忆TTL（毫秒）
     * @param longTermTtl 长期记忆TTL（毫秒）
     * @param cleanupInterval 清理间隔（毫秒）
     * @param persistOnWrite 写入时是否立即持久化
     * @param maxShortTermCount 短期记忆最大数量
     * @param maxLongTermCount 长期记忆最大数量
     */
    public SharedMemoryManager(String workDir,
                               long shortTermTtl,
                               long longTermTtl,
                               long cleanupInterval,
                               boolean persistOnWrite,
                               int maxShortTermCount,
                               int maxLongTermCount) {
        this.memoryStore = new MemoryStore(workDir);
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
            cleanupInterval,
            cleanupInterval,
            TimeUnit.MILLISECONDS
        );

        LOG.info("共享记忆系统初始化完成: shortTermTtl={}ms, longTermTtl={}ms",
                 shortTermTtl, longTermTtl);
    }

    /**
     * 从 Store 加载记忆
     */
    private Memory loadFromSession(String memoryId, Memory.MemoryType type) {
        Memory memory = memoryStore.load(memoryId, type);
        if (memory != null && !memory.isExpired()) {
            LOG.debug("从 Store 加载记忆: type={}, id={}", type, memoryId);
            return memory;
        }
        return null;
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
        if (memory == null) {
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
        return stats;
    }

    // ========== 工作记忆方法 ==========

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
    }

    /**
     * 清理过期的工作记忆
     */
    private int cleanupWorkingMemories() {
        int removed = 0;
        long now = System.currentTimeMillis();

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
     * 异步持久化到文件（使用 MemoryStore）
     */
    private void persistAsync(Memory memory) {
        memoryStore.store(memory);
    }

    /**
     * 启动时加载持久化的记忆（使用 MemoryStore）
     */
    private void initStorage() {
        try {
            // 加载各类记忆到内存缓存（工作记忆不持久化，跳过）
            loadMemories(Memory.MemoryType.SHORT_TERM);
            loadMemories(Memory.MemoryType.LONG_TERM);
            loadMemories(Memory.MemoryType.KNOWLEDGE);

            Map<String, Integer> stats = memoryStore.getStats();
            LOG.info("共享记忆加载完成: {}", stats);

        } catch (Exception e) {
            LOG.warn("共享记忆初始化失败: error={}", e.getMessage());
        }
    }

    /**
     * 从 MemoryStore 加载指定类型的记忆
     */
    private void loadMemories(Memory.MemoryType type) {
        List<Memory> memories = memoryStore.loadAll(type);
        Map<String, Memory> cache = getCacheByType(type);

        for (Memory memory : memories) {
            if (!memory.isExpired()) {
                cache.put(memory.getId(), memory);
                buildIndex(memory);
            }
        }
    }

    /**
     * 根据类型获取缓存
     */
    @SuppressWarnings("unchecked")
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
