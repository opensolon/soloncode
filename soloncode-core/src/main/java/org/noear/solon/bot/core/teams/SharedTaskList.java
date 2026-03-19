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
package org.noear.solon.bot.core.teams;

import org.noear.solon.bot.core.AgentProperties;
import org.noear.solon.bot.core.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 共享任务列表（Shared Task List）
 *
 * Agent Teams 模式中的共享任务池，支持：
 * - 任务添加和删除
 * - 任务认领和释放
 * - 优先级队列
 * - Agent 负载跟踪
 * - 任务生命周期事件
 *
 * @author bai
 * @since 3.9.5
 */
public class SharedTaskList {
    private static final Logger LOG = LoggerFactory.getLogger(SharedTaskList.class);

    private final Map<String, TeamTask> tasks;              // 所有任务 (taskId -> Task)
    private final Map<String, TeamTask> pendingTasks;       // 待认领任务
    private final Map<String, Set<String>> agentTasks;      // Agent 的任务集合 (agentId -> Set<taskId>)
    private final Map<String, Integer> agentLoad;           // Agent 负载计数

    // 性能优化：使用细粒度锁替代全局读写锁
    private final Map<String, ReentrantLock> taskLocks;     // 每个任务一个锁

    // 保留一个轻量级的全局锁用于保护关键区域的原子操作（如批量添加任务）
    private final ReentrantLock globalLock;

    private final EventBus eventBus;                         // 事件总线
    private final int maxCompletedTasks;                    // 保留的最大已完成任务数
    private final Queue<String> completedTaskQueue;         // 已完成任务队列（FIFO清理）

    // 性能优化：专用线程池
    private final ExecutorService taskExecutor;              // 任务操作线程池
    private final ExecutorService eventExecutor;             // 事件发布线程池

    // 性能优化：最大依赖深度限制
    private int maxDependencyDepth = 100;

    /**
     * 构造函数（使用默认配置）
     *
     * @param eventBus 事件总线
     */
    public SharedTaskList(EventBus eventBus) {
        this(eventBus, null, null, 100);
    }

    /**
     * 完整构造函数（直接指定参数）
     *
     * @param eventBus 事件总线
     * @param maxCompletedTasks 最大保留已完成任务数
     * @deprecated 使用配置构造函数 {@link #SharedTaskList(EventBus, AgentProperties.TeamsConfig)}
     */
    public SharedTaskList(EventBus eventBus, int maxCompletedTasks) {
        this(eventBus, null, null, maxCompletedTasks);
    }

    /**
     * 配置构造函数
     *
     * @param eventBus 事件总线
     * @param config Teams 配置
     */
    public SharedTaskList(EventBus eventBus, AgentProperties.TeamsConfig config) {
        this(eventBus,
             config != null ? config.taskExecutorThreads : null,
             config != null ? config.eventExecutorThreads : null,
             config != null ? config.maxCompletedTasks : 100);

        if (config != null && config.maxDependencyDepth > 0) {
            this.maxDependencyDepth = config.maxDependencyDepth;
        }
    }

    /**
     * 内部构造函数
     */
    private SharedTaskList(EventBus eventBus, Integer taskThreads, Integer eventThreads, int maxCompletedTasks) {
        this.tasks = new ConcurrentHashMap<>();
        this.pendingTasks = new ConcurrentHashMap<>();
        this.agentTasks = new ConcurrentHashMap<>();
        this.agentLoad = new ConcurrentHashMap<>();
        this.taskLocks = new ConcurrentHashMap<>();
        this.globalLock = new ReentrantLock();
        this.eventBus = eventBus;
        this.maxCompletedTasks = maxCompletedTasks;
        this.completedTaskQueue = new LinkedList<>();

        // 创建专用线程池（使用配置或默认值）
        int taskPoolSize = taskThreads != null ? taskThreads :
                           Math.max(10, Runtime.getRuntime().availableProcessors() * 2);
        int eventPoolSize = eventThreads != null ? eventThreads : 1;

        this.taskExecutor = Executors.newFixedThreadPool(taskPoolSize, new NamedThreadFactory("SharedTaskList"));
        this.eventExecutor = Executors.newFixedThreadPool(eventPoolSize, new NamedThreadFactory("SharedTaskList-Event"));

        LOG.info("SharedTaskList 初始化完成 (任务线程池: {}, 事件线程池: {}, 使用细粒度锁)",
                 taskPoolSize, eventPoolSize);
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        LOG.info("SharedTaskList 正在关闭...");

        // 关闭线程池
        taskExecutor.shutdown();
        eventExecutor.shutdown();

        try {
            if (!taskExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                taskExecutor.shutdownNow();
            }
            if (!eventExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                eventExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            taskExecutor.shutdownNow();
            eventExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOG.info("SharedTaskList 已关闭");
    }


    /**
     * 添加任务
     * 性能优化：使用细粒度锁，只锁定必要的代码段
     *
     * @param task 任务
     * @return 异步结果
     */
    public CompletableFuture<TeamTask> addTask(TeamTask task) {
        return CompletableFuture.supplyAsync(() -> {
            // 获取任务级别的锁（按字母顺序排序以避免死锁）
            List<String> lockIds = new ArrayList<>(task.getDependencies());
            lockIds.add(task.getId());
            lockIds.sort(String::compareTo);

            // 获取所有相关的锁
            List<ReentrantLock> locks = new ArrayList<>();
            for (String id : lockIds) {
                ReentrantLock lock = taskLocks.computeIfAbsent(id, k -> new ReentrantLock());
                locks.add(lock);
            }

            // 按顺序获取所有锁（避免死锁），并记录获取数量
            int acquiredLocks = 0;
            try {
                for (ReentrantLock lock : locks) {
                    lock.lock();
                    acquiredLocks++;  // 记录成功获取的锁数量
                }

                // 验证依赖任务存在
                for (String depId : task.getDependencies()) {
                    if (!tasks.containsKey(depId)) {
                        throw new IllegalArgumentException("依赖任务不存在: " + depId);
                    }
                }

                // 检测循环依赖（带深度限制）
                if (hasCyclicDependency(task, tasks::get, maxDependencyDepth)) {
                    throw new IllegalArgumentException("检测到循环依赖或依赖深度超过限制: " + task.getTitle());
                }

                // 添加任务
                tasks.put(task.getId(), task);

                // 如果状态是 PENDING，加入待认领队列
                if (task.isClaimable()) {
                    pendingTasks.put(task.getId(), task);
                }

                LOG.debug("任务已添加: {} (优先级: {})", task.getTitle(), task.getPriority());

                // 异步发布事件（不阻塞返回）
                final TeamTask finalTask = task;
                CompletableFuture.runAsync(() -> {
                    publishTaskEvent(AgentEventType.TASK_CREATED, finalTask, null);
                }, eventExecutor);

                return task;

            } finally {
                // 按相反顺序释放已获取的锁（修复：使用acquiredLocks而不是locks.size()）
                for (int i = acquiredLocks - 1; i >= 0; i--) {
                    locks.get(i).unlock();
                }
            }
        }, taskExecutor);
    }

    /**
     * 检测循环依赖（带深度限制）
     */
    private boolean hasCyclicDependency(TeamTask task, java.util.function.Function<String, TeamTask> taskLookup, int maxDepth) {
        try {
            return task.hasCyclicDependency(taskLookup);
        } catch (StackOverflowError e) {
            LOG.warn("任务依赖深度过大，可能存在循环依赖: {} (深度限制: {})", task.getTitle(), maxDepth);
            return true;
        }
    }

    /**
     * 批量添加任务（原子操作，支持任务间依赖）
     *
     * @param tasks 任务列表
     * @return 异步结果
     */
    public CompletableFuture<List<TeamTask>> addTasks(List<TeamTask> tasks) {
        return CompletableFuture.supplyAsync(() -> {
            globalLock.lock();
            try {
                // 第一阶段：收集所有任务ID并验证唯一性
                Set<String> batchTaskIds = new HashSet<>();
                for (TeamTask task : tasks) {
                    // 检查任务ID唯一性（包括批次内）
                    if (this.tasks.containsKey(task.getId()) || batchTaskIds.contains(task.getId())) {
                        throw new IllegalArgumentException("任务ID已存在: " + task.getId());
                    }
                    batchTaskIds.add(task.getId());
                }

                // 第二阶段：验证依赖关系（修复：使用batchTaskIds避免时序问题）
                for (TeamTask task : tasks) {
                    for (String depId : task.getDependencies()) {
                        // 检查依赖是否在当前批次中
                        boolean inBatch = batchTaskIds.contains(depId);
                        // 检查依赖是否已存在
                        boolean exists = this.tasks.containsKey(depId);

                        if (!inBatch && !exists) {
                            throw new IllegalArgumentException("依赖任务不存在: " + depId +
                                    " (被任务 " + task.getTitle() + " 依赖)");
                        }
                    }
                }

                // 第三阶段：添加所有任务
                List<TeamTask> added = new ArrayList<>();
                for (TeamTask task : tasks) {
                    // 添加到任务列表
                    this.tasks.put(task.getId(), task);

                    // 如果状态是 PENDING，加入待认领队列
                    if (task.isClaimable()) {
                        pendingTasks.put(task.getId(), task);
                    }

                    added.add(task);

                    LOG.debug("任务已添加: {} (优先级: {})", task.getTitle(), task.getPriority());
                }

                // 第四阶段：检测循环依赖（所有任务添加后）
                for (TeamTask task : tasks) {
                    if (task.hasCyclicDependency(this.tasks::get)) {
                        // 回滚：删除所有已添加的任务
                        for (TeamTask addedTask : added) {
                            this.tasks.remove(addedTask.getId());
                            pendingTasks.remove(addedTask.getId());
                        }
                        throw new IllegalArgumentException("检测到循环依赖: " + task.getTitle());
                    }
                }

                // 第五阶段：触发事件（释放锁后触发）
                CompletableFuture.runAsync(() -> {
                    for (TeamTask task : added) {
                        publishTaskEvent(AgentEventType.TASK_CREATED, task, null);
                    }
                }, eventExecutor);

                return added;

            } finally {
                globalLock.unlock();
            }
        }, taskExecutor);
    }

    /**
     * 获取任务
     *
     * @param taskId 任务ID
     * @return 任务，不存在返回 null
     */
    public TeamTask getTask(String taskId) {
        // ConcurrentHashMap 支持并发读，无需加锁
        return tasks.get(taskId);
    }

    /**
     * 删除任务
     *
     * @param taskId 任务ID
     * @return 是否删除成功
     */
    public boolean removeTask(String taskId) {
        // 使用细粒度锁
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
        lock.lock();
        try {
            TeamTask task = tasks.remove(taskId);
            if (task != null) {
                pendingTasks.remove(taskId);

                // 从 Agent 的任务集合中移除
                if (task.getClaimedBy() != null) {
                    Set<String> agentTaskSet = agentTasks.get(task.getClaimedBy());
                    if (agentTaskSet != null) {
                        agentTaskSet.remove(taskId);
                        updateAgentLoad(task.getClaimedBy());
                    }
                }

                LOG.debug("任务已删除: {}", task.getTitle());
                return true;
            }
            return false;

        } finally {
            lock.unlock();
            // 清理锁（避免内存泄漏）
            taskLocks.remove(taskId);
        }
    }

    // ========== 任务认领 ==========

    /**
     * 认领任务
     *
     * @param taskId 任务ID
     * @param agentId Agent ID
     * @return 认领是否成功
     */
    public CompletableFuture<Boolean> claimTask(String taskId, String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            // 使用细粒度锁
            ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
            lock.lock();
            try {
                TeamTask task = tasks.get(taskId);

                // 验证任务存在
                if (task == null) {
                    LOG.warn("认领失败: 任务不存在 {}", taskId);
                    return false;
                }

                // 验证任务可认领
                if (!task.isClaimable()) {
                    LOG.warn("认领失败: 任务不可认领 {} (状态: {})", task.getTitle(), task.getStatus());
                    return false;
                }

                // 验证所有依赖任务已完成（递归检查）
                if (!task.areAllDependenciesCompleted(tasks::get)) {
                    LOG.warn("认领失败: 依赖任务未完成 {}", task.getTitle());
                    return false;
                }

                // 认领任务
                task.setStatus(TeamTask.Status.IN_PROGRESS);
                task.setClaimedBy(agentId);
                task.setClaimTime(System.currentTimeMillis());

                // 从待认领队列移除
                pendingTasks.remove(taskId);

                // 添加到 Agent 的任务集合
                agentTasks.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(taskId);
                updateAgentLoad(agentId);

                LOG.info("任务已认领: {} by {}", task.getTitle(), agentId);

                // 触发事件（在锁外执行，避免阻塞）
                TeamTask finalTask = task;
                String finalAgentId = agentId;
                CompletableFuture.runAsync(() -> {
                    publishTaskEvent(AgentEventType.TASK_CLAIMED, finalTask, finalAgentId);
                }, eventExecutor);

                return true;

            } catch (IllegalStateException e) {
                // 循环依赖异常
                LOG.error("认领失败: {}", e.getMessage());
                return false;
            } finally {
                lock.unlock();
            }
        }, taskExecutor);
    }


    /**
     * 释放任务
     *
     * @param taskId 任务ID
     * @param agentId Agent ID
     * @return 是否释放成功
     */
    public boolean releaseTask(String taskId, String agentId) {
        // 使用细粒度锁
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
        lock.lock();
        try {
            TeamTask task = tasks.get(taskId);

            if (task == null) {
                return false;
            }

            // 验证是认领者
            if (!agentId.equals(task.getClaimedBy())) {
                LOG.warn("释放失败: 不是任务的认领者 {}", agentId);
                return false;
            }

            // 重置状态
            task.setStatus(TeamTask.Status.PENDING);
            task.setClaimedBy(null);
            task.setClaimTime(0);

            // 加入待认领队列
            pendingTasks.put(taskId, task);

            // 从 Agent 的任务集合移除
            Set<String> agentTaskSet = agentTasks.get(agentId);
            if (agentTaskSet != null) {
                agentTaskSet.remove(taskId);
                updateAgentLoad(agentId);
            }

            LOG.info("任务已释放: {} by {}", task.getTitle(), agentId);

            // 触发事件（在锁外执行，避免阻塞）
            TeamTask finalTask = task;
            String finalAgentId = agentId;
            CompletableFuture.runAsync(() -> {
                publishTaskEvent(AgentEventType.TASK_RELEASED, finalTask, finalAgentId);
            }, eventExecutor);

            return true;

        } finally {
            lock.unlock();
        }
    }

    // ========== 任务完成 ==========

    /**
     * 完成任务
     *
     * @param taskId 任务ID
     * @param result 执行结果
     * @return 是否完成成功
     */
    public boolean completeTask(String taskId, Object result) {
        // 使用细粒度锁
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
        lock.lock();
        try {
            TeamTask task = tasks.get(taskId);

            if (task == null) {
                return false;
            }

            String agentId = task.getClaimedBy();

            // 更新任务状态
            task.setStatus(TeamTask.Status.COMPLETED);
            task.setResult(result);
            task.setCompletedTime(System.currentTimeMillis());

            // 从待认领队列移除（如果存在）
            pendingTasks.remove(taskId);

            // 从 Agent 的任务集合移除
            if (agentId != null) {
                Set<String> agentTaskSet = agentTasks.get(agentId);
                if (agentTaskSet != null) {
                    agentTaskSet.remove(taskId);
                    updateAgentLoad(agentId);
                }
            }

            // 添加到已完成队列
            completedTaskQueue.offer(taskId);
            cleanupCompletedTasks();

            // 性能优化：清除依赖此任务的其他任务的缓存
            clearDependentTasksCache(taskId);

            LOG.info("任务已完成: {} by {}", task.getTitle(), agentId);

            // 触发事件（在锁外执行，避免阻塞）
            TeamTask finalTask = task;
            String finalAgentId = agentId;
            CompletableFuture.runAsync(() -> {
                publishTaskEvent(AgentEventType.TASK_COMPLETED, finalTask, finalAgentId);
            }, eventExecutor);

            return true;

        } finally {
            lock.unlock();
            // 完成后清理锁（避免内存泄漏）
            taskLocks.remove(taskId);
        }
    }

    /**
     * 失败任务
     *
     * @param taskId 任务ID
     * @param errorMessage 错误信息
     * @return是否标记成功
     */
    public boolean failTask(String taskId, String errorMessage) {
        // 使用细粒度锁
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
        lock.lock();
        try {
            TeamTask task = tasks.get(taskId);

            if (task == null) {
                return false;
            }

            String agentId = task.getClaimedBy();

            // 更新任务状态
            task.setStatus(TeamTask.Status.FAILED);
            task.setErrorMessage(errorMessage);
            task.setCompletedTime(System.currentTimeMillis());

            // 从待认领队列移除
            pendingTasks.remove(taskId);

            // 从 Agent 的任务集合移除
            if (agentId != null) {
                Set<String> agentTaskSet = agentTasks.get(agentId);
                if (agentTaskSet != null) {
                    agentTaskSet.remove(taskId);
                    updateAgentLoad(agentId);
                }
            }

            // 性能优化：清除依赖此任务的其他任务的缓存
            clearDependentTasksCache(taskId);

            LOG.warn("任务已失败: {} - {}", task.getTitle(), errorMessage);

            // 触发事件（在锁外执行，避免阻塞）
            TeamTask finalTask = task;
            String finalAgentId = agentId;
            CompletableFuture.runAsync(() -> {
                publishTaskEvent(AgentEventType.TASK_FAILED, finalTask, finalAgentId);
            }, eventExecutor);

            return true;

        } finally {
            lock.unlock();
            // 失败后清理锁（避免内存泄漏）
            taskLocks.remove(taskId);
        }
    }

    // ========== 查询方法 ==========

    /**
     * 获取所有任务
     * 性能优化：不再持有锁
     *
     * @return 任务列表
     */
    public List<TeamTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 获取待认领任务
     * 性能优化：不再持有锁
     *
     * @return 任务列表
     */
    public List<TeamTask> getPendingTasks() {
        return new ArrayList<>(pendingTasks.values());
    }

    /**
     * 获取可认领任务（考虑依赖关系，按优先级排序）
     * 性能优化：不再持有全局锁，使用细粒度锁
     *
     * @return 任务列表（按优先级降序排列）
     */
    public List<TeamTask> getClaimableTasks() {
        // 不加锁，ConcurrentHashMap 支持并发读
        return pendingTasks.values().stream()
                .filter(task -> {
                    try {
                        // 使用缓存优化后的依赖检查（TeamTask 中有缓存）
                        return task.areAllDependenciesCompleted(tasks::get);
                    } catch (IllegalStateException e) {
                        // 循环依赖的任务不能被认领
                        LOG.warn("任务存在循环依赖，无法认领: {}", task.getTitle());
                        return false;
                    }
                })
                .sorted((a, b) -> {
                    // 按优先级降序排序（高优先级在前）
                    int priorityCompare = Integer.compare(b.getPriority(), a.getPriority());
                    if (priorityCompare != 0) {
                        return priorityCompare;
                    }
                    // 优先级相同时，按创建时间升序排序（早创建的在前）
                    return Long.compare(a.getClaimTime(), b.getClaimTime());
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取 Agent 的任务列表
     *
     * @param agentId Agent ID
     * @return 任务列表
     */
    public List<TeamTask> getAgentTasks(String agentId) {
        // ConcurrentHashMap 支持并发读，无需加锁
        Set<String> taskIds = agentTasks.getOrDefault(agentId, Collections.emptySet());
        return taskIds.stream()
                .map(tasks::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 获取 Agent 负载
     *
     * @param agentId Agent ID
     * @return 负载数（进行中的任务数）
     */
    public int getAgentLoad(String agentId) {
        // ConcurrentHashMap 支持并发读，无需加锁
        return agentLoad.getOrDefault(agentId, 0);
    }

    /**
     * 获取所有 Agent 负载
     *
     * @return Agent ID -> 负载数
     */
    public Map<String, Integer> getAllAgentLoads() {
        // 返回副本以避免并发修改
        return new HashMap<>(agentLoad);
    }

    /**
     * 按状态获取任务
     *
     * @param status 状态
     * @return 任务列表
     */
    public List<TeamTask> getTasksByStatus(TeamTask.Status status) {
        // ConcurrentHashMap 支持并发读，无需加锁
        return tasks.values().stream()
                .filter(task -> task.getStatus() == status)
                .collect(Collectors.toList());
    }

    /**
     * 按类型获取任务
     *
     * @param type 类型
     * @return 任务列表
     */
    public List<TeamTask> getTasksByType(TeamTask.TaskType type) {
        // ConcurrentHashMap 支持并发读，无需加锁
        return tasks.values().stream()
                .filter(task -> task.getType() == type)
                .collect(Collectors.toList());
    }

    // ========== 统计方法 ==========

    /**
     * 获取任务统计
     * 性能优化：不再持有锁
     *
     * @return 统计信息
     */
    public TaskStatistics getStatistics() {
        Map<TeamTask.Status, Long> statusCounts = tasks.values().stream()
                .collect(Collectors.groupingBy(TeamTask::getStatus, Collectors.counting()));

        return new TaskStatistics(
                tasks.size(),
                statusCounts.getOrDefault(TeamTask.Status.PENDING, 0L).intValue(),
                statusCounts.getOrDefault(TeamTask.Status.IN_PROGRESS, 0L).intValue(),
                statusCounts.getOrDefault(TeamTask.Status.COMPLETED, 0L).intValue(),
                statusCounts.getOrDefault(TeamTask.Status.FAILED, 0L).intValue(),
                agentLoad.size()
        );
    }

    // ========== 依赖关系诊断 ==========

    /**
     * 获取任务的依赖树
     *
     * @param taskId 任务ID
     * @return 依赖树字符串
     */
    public String getTaskDependencyTree(String taskId) {
        // ConcurrentHashMap 支持并发读，无需加锁
        TeamTask task = tasks.get(taskId);
        if (task == null) {
            return "任务不存在: " + taskId;
        }
        return task.getDependencyTree(tasks::get);
    }

    /**
     * 检测所有任务的循环依赖
     *
     * @return 存在循环依赖的任务列表
     */
    public List<TeamTask> detectCyclicDependencies() {
        // ConcurrentHashMap 支持并发读，无需加锁
        return tasks.values().stream()
                .filter(task -> task.hasCyclicDependency(tasks::get))
                .collect(Collectors.toList());
    }

    /**
     * 获取任务依赖图（用于调试）
     *
     * @return 依赖关系的文本表示
     */
    public String getDependencyGraph() {
        // ConcurrentHashMap 支持并发读，无需加锁
        StringBuilder sb = new StringBuilder();
        sb.append("=== 任务依赖关系图 ===\n\n");

        for (TeamTask task : tasks.values()) {
            if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
                sb.append(task.getTitle())
                  .append(" (").append(task.getId()).append(")")
                  .append(" [").append(task.getStatus()).append("]")
                  .append(" 依赖:\n");

                for (String depId : task.getDependencies()) {
                    TeamTask dep = tasks.get(depId);
                    if (dep != null) {
                        sb.append("  → ").append(dep.getTitle())
                          .append(" (").append(depId).append(")")
                          .append(" [").append(dep.getStatus()).append("]\n");
                    } else {
                        sb.append("  → [不存在] ").append(depId).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        // 检测循环依赖
        List<TeamTask> cyclicTasks = detectCyclicDependencies();
        if (!cyclicTasks.isEmpty()) {
            sb.append("[WARN] 检测到循环依赖:\n");
            for (TeamTask task : cyclicTasks) {
                sb.append("  - ").append(task.getTitle())
                  .append(" (").append(task.getId()).append(")\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取阻塞的任务（依赖未完成导致无法认领）
     *
     * @return 被阻塞的任务列表
     */
    public List<TeamTask> getBlockedTasks() {
        // ConcurrentHashMap 支持并发读，无需加锁
        try {
            return pendingTasks.values().stream()
                    .filter(task -> !task.areAllDependenciesCompleted(tasks::get))
                    .collect(Collectors.toList());
        } catch (IllegalStateException e) {
            LOG.error("检查阻塞任务时发生异常", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取阻塞任务的详细信息
     *
     * @return 阻塞信息
     */
    public String getBlockingInfo() {
        // ConcurrentHashMap 支持并发读，无需加锁
        List<TeamTask> blockedTasks = getBlockedTasks();

        if (blockedTasks.isEmpty()) {
            return "没有阻塞的任务";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 阻塞任务详情 ===\n\n");

        for (TeamTask task : blockedTasks) {
            sb.append("任务: ").append(task.getTitle())
              .append(" (").append(task.getId()).append(")\n");
            sb.append("状态: ").append(task.getStatus()).append("\n");
            sb.append("等待依赖:\n");

            for (String depId : task.getAllDependencyIds(tasks::get)) {
                TeamTask dep = tasks.get(depId);
                if (dep != null && !dep.isCompleted()) {
                    sb.append("  - ").append(dep.getTitle())
                      .append(" [").append(dep.getStatus()).append("]\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 任务统计信息
     */
    public static class TaskStatistics {
        public final int totalTasks;
        public final int pendingTasks;
        public final int inProgressTasks;
        public final int completedTasks;
        public final int failedTasks;
        public final int activeAgents;

        public TaskStatistics(int totalTasks, int pendingTasks, int inProgressTasks,
                             int completedTasks, int failedTasks, int activeAgents) {
            this.totalTasks = totalTasks;
            this.pendingTasks = pendingTasks;
            this.inProgressTasks = inProgressTasks;
            this.completedTasks = completedTasks;
            this.failedTasks = failedTasks;
            this.activeAgents = activeAgents;
        }

        @Override
        public String toString() {
            return String.format(
                    "TaskStatistics{总任务=%d, 待认领=%d, 进行中=%d, 已完成=%d, 失败=%d, 活跃Agent=%d}",
                    totalTasks, pendingTasks, inProgressTasks, completedTasks, failedTasks, activeAgents
            );
        }
    }

    /**
     * 更新 Agent 负载
     */
    private void updateAgentLoad(String agentId) {
        Set<String> taskSet = agentTasks.get(agentId);
        int load = (taskSet != null) ? taskSet.size() : 0;
        agentLoad.put(agentId, load);
    }

    /**
     * 清除依赖此任务的所有任务的缓存
     * 性能优化：当任务完成/失败时，通知依赖它的任务清除缓存
     *
     * @param completedTaskId 已完成的任务ID
     */
    private void clearDependentTasksCache(String completedTaskId) {
        int clearedCount = 0;
        for (TeamTask task : tasks.values()) {
            if (task.getDependencies().contains(completedTaskId)) {
                task.clearCachedCompletedDeps();
                clearedCount++;
            }
        }
        if (clearedCount > 0) {
            LOG.debug("清除了 {} 个依赖任务的缓存（任务 {} 完成）", clearedCount, completedTaskId);
        }
    }

    /**
     * 清理已完成任务
     */
    private void cleanupCompletedTasks() {
        while (completedTaskQueue.size() > maxCompletedTasks) {
            String taskId = completedTaskQueue.poll();
            if (taskId != null) {
                TeamTask task = tasks.get(taskId);
                if (task != null && task.isCompleted()) {
                    tasks.remove(taskId);
                    LOG.debug("清理已完成任务: {}", task.getTitle());
                }
            }
        }
    }

    /**
     * 发布任务事件到 EventBus
     */
    private void publishTaskEvent(AgentEventType eventType, TeamTask task, String agentId) {
        if (eventBus != null) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("taskId", task.getId());
                payload.put("taskTitle", task.getTitle());
                payload.put("agentId", agentId);
                payload.put("status", task.getStatus());
                payload.put("priority", task.getPriority());

                // 对于失败任务，添加错误信息
                if (eventType == AgentEventType.TASK_FAILED && task.getErrorMessage() != null) {
                    payload.put("errorMessage", task.getErrorMessage());
                }

                // 创建事件元数据
                EventMetadata metadata = EventMetadata.builder()
                        .taskId(task.getId())
                        .priority(task.getPriority())
                        .build();

                // 创建并发布事件
                AgentEvent event = new AgentEvent(eventType, payload, metadata);
                eventBus.publishAsync(event);
            } catch (Throwable e) {
                LOG.error("发布任务事件失败", e);
            }
        }
    }

    /**
     * 命名线程工厂（用于线程池）
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final boolean daemon;

        NamedThreadFactory(String namePrefix) {
            this(namePrefix, true);
        }

        NamedThreadFactory(String namePrefix, boolean daemon) {
            this.namePrefix = namePrefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
            thread.setDaemon(daemon);
            return thread;
        }
    }
}
