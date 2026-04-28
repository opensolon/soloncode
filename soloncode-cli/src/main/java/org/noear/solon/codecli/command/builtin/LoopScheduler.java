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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.scheduling.ScheduledAnno;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.simple.JobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时循环任务调度管理器
 *
 * <p>职责：
 * <ol>
 *   <li>管理任务元数据的 JSON 持久化（load / save）</li>
 *   <li>通过 IJobManager 动态注册/移除调度</li>
 *   <li>支持进程重启后恢复未过期任务</li>
 * </ol>
 *
 * @author noear
 * @since 3.9.1
 */
public class LoopScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LoopScheduler.class);
    private static final int MAX_TASKS_PER_SESSION = 50;
    private static final String TASKS_FILE = "loop-tasks.json";

    // Solon 原生调度管理器
    private final IJobManager jobManager;

    // 会话级任务列表：sessionId -> list of LoopTask
    private final ConcurrentHashMap<String, List<LoopTask>> sessionTasks = new ConcurrentHashMap<>();

    // CLI 端任务执行回调：sessionId, prompt -> void（同步阻塞）
    private volatile TaskExecutor taskExecutor;

    // Web 端任务执行回调：sessionId, prompt -> String（异步，返回执行结果摘要）
    private volatile ReactiveTaskExecutor reactiveTaskExecutor;

    /**
     * CLI 端任务执行器（同步阻塞）
     */
    @FunctionalInterface
    public interface TaskExecutor {
        void execute(String sessionId, String prompt);
    }

    /**
     * Web 端任务执行器（Reactive，返回执行结果摘要）
     */
    @FunctionalInterface
    public interface ReactiveTaskExecutor {
        String execute(String sessionId, String prompt);
    }

    public LoopScheduler() {
        this.jobManager = JobManager.getInstance();
    }

    public void setTaskExecutor(TaskExecutor executor) {
        this.taskExecutor = executor;
    }

    public void setReactiveTaskExecutor(ReactiveTaskExecutor executor) {
        this.reactiveTaskExecutor = executor;
    }

    /**
     * 是否已注入 Web 端回调
     */
    public boolean hasReactiveExecutor() {
        return reactiveTaskExecutor != null;
    }

    // ==================== 任务注册 ====================

    /**
     * 注册循环任务
     *
     * <p>流程：创建 LoopTask -> 注册到 IJobManager -> 加入内存列表 -> 持久化到 JSON
     *
     * @param sessionId      会话 ID
     * @param workspace      工作空间路径
     * @param harnessSessions 会话目录相对路径
     * @param task           待注册的任务
     * @return 已注册的任务
     */
    public LoopTask schedule(String sessionId, String workspace, String harnessSessions, LoopTask task) {
        // 1. 检查最大任务数
        List<LoopTask> tasks = sessionTasks.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        if (tasks.size() >= MAX_TASKS_PER_SESSION) {
            throw new IllegalStateException("Max tasks reached: " + MAX_TASKS_PER_SESSION);
        }

        // 2. 清理过期任务
        cleanExpired(sessionId, workspace, harnessSessions, tasks);

        // 3. 注册到 IJobManager
        registerJob(sessionId, task);

        // 4. 加入内存列表
        tasks.add(task);

        // 5. 持久化到 JSON
        saveToFile(sessionId, workspace, harnessSessions, tasks);

        return task;
    }

    // ==================== 任务移除 ====================

    /**
     * 停止指定任务
     */
    public void remove(String sessionId, String workspace, String harnessSessions, String taskId) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return;

        tasks.removeIf(t -> {
            if (t.getId().equals(taskId)) {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
                return true;
            }
            return false;
        });

        saveToFile(sessionId, workspace, harnessSessions, tasks);
    }

    // ==================== 任务列表 ====================

    /**
     * 列出活跃任务（自动清理过期）
     */
    public List<LoopTask> listActive(String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = sessionTasks.get(sessionId);
        if (tasks == null) return Collections.emptyList();

        // 清理过期任务
        cleanExpired(sessionId, workspace, harnessSessions, tasks);

        return new ArrayList<>(tasks);
    }

    // ==================== 批量停止 ====================

    /**
     * 停止会话的所有任务
     */
    public void stopAll(String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = sessionTasks.remove(sessionId);
        if (tasks != null) {
            tasks.forEach(t -> {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
            });
        }
        // 删除 JSON 文件
        deleteFile(sessionId, workspace, harnessSessions);
    }

    // ==================== 会话恢复 ====================

    /**
     * 从 JSON 恢复任务 — 过滤过期任务，重新注册到 IJobManager
     *
     * <p>在 CliShell.prepare() 或 ResumeCommand 中调用
     */
    public void restore(String sessionId, String workspace, String harnessSessions) {
        List<LoopTask> tasks = loadFromFile(sessionId, workspace, harnessSessions);
        if (tasks == null || tasks.isEmpty()) return;

        // 移除过期/已取消任务
        List<LoopTask> alive = new ArrayList<>();
        for (LoopTask t : tasks) {
            if (t.isExpired() || t.isCancelled()) {
                continue;
            }
            alive.add(t);
        }

        if (alive.isEmpty()) {
            deleteFile(sessionId, workspace, harnessSessions);
            return;
        }

        sessionTasks.put(sessionId, Collections.synchronizedList(alive));

        // 重新注册到 IJobManager
        for (LoopTask t : alive) {
            registerJob(sessionId, t);
        }

        // 回写（去掉过期任务）
        saveToFile(sessionId, workspace, harnessSessions, alive);
        LOG.info("Restored {} loop tasks for session {}", alive.size(), sessionId);
    }

    // ==================== IJobManager 注册 ====================

    /**
     * 注册任务到 IJobManager（使用 fixedDelay 串行策略）
     */
    private void registerJob(String sessionId, LoopTask task) {
        String jobName = task.getJobName();
        long intervalMs = (long) task.getIntervalMinutes() * 60_000L;

        ScheduledAnno scheduled = new ScheduledAnno()
                .fixedDelay(intervalMs)
                .initialDelay(intervalMs);

        jobManager.jobAdd(jobName, scheduled, ctx -> {
            onTrigger(sessionId, task);
        });
    }

    // ==================== 定时触发回调 ====================

    /**
     * 定时触发 — 执行任务
     */
    private void onTrigger(String sessionId, LoopTask task) {
        // 过期或已取消则移除
        if (task.isExpired() || task.isCancelled()) {
            String jobName = task.getJobName();
            if (jobManager.jobExists(jobName)) {
                jobManager.jobRemove(jobName);
            }
            return;
        }

        // 防重入：上一个还没执行完则跳过
        if (!task.tryStart()) {
            return;
        }

        try {
            if (reactiveTaskExecutor != null) {
                // Web 端：Reactive 执行，返回结果摘要
                String result = reactiveTaskExecutor.execute(sessionId, task.getPrompt());
                task.updateLastExecution(result != null ? result : "ok");
            } else if (taskExecutor != null) {
                // CLI 端：同步执行
                taskExecutor.execute(sessionId, task.getPrompt());
                task.updateLastExecution("ok");
            }
        } catch (Exception e) {
            LOG.error("Loop task '{}' failed: {}", task.getId(), e.getMessage());
            task.updateLastExecution("error: " + e.getMessage());
        } finally {
            task.finish();
        }
    }

    // ==================== 清理过期任务 ====================

    /**
     * 清理内存列表中的过期/已取消任务，并同步 IJobManager 和 JSON
     */
    private void cleanExpired(String sessionId, String workspace, String harnessSessions, List<LoopTask> tasks) {
        boolean changed = tasks.removeIf(t -> {
            if (t.isExpired() || t.isCancelled()) {
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
                return true;
            }
            return false;
        });

        if (changed) {
            saveToFile(sessionId, workspace, harnessSessions, tasks);
        }
    }

    public void shutdown() {
        for (Map.Entry<String, List<LoopTask>> entry : sessionTasks.entrySet()) {
            for (LoopTask t : entry.getValue()) {
                t.cancel();
                String jobName = t.getJobName();
                if (jobManager.jobExists(jobName)) {
                    jobManager.jobRemove(jobName);
                }
            }
        }
        sessionTasks.clear();
        LOG.info("LoopScheduler shutdown: all tasks cancelled and removed from IJobManager");
    }

    // ==================== JSON 持久化 ====================

    /**
     * 获取任务 JSON 文件路径
     * 位于会话目录下：&lt;workspace&gt;/&lt;harnessSessions&gt;/&lt;sessionId&gt;/loop_tasks.json
     */
    private Path getFilePath(String sessionId, String workspace, String harnessSessions) {
        return Paths.get(workspace, harnessSessions, sessionId, TASKS_FILE);
    }
    /**
     * 将任务列表保存到 JSON 文件（原子写入：先写临时文件，再 rename）
     */
    private void saveToFile(String sessionId, String workspace, String harnessSessions, List<LoopTask> tasks) {
        try {
            Path filePath = getFilePath(sessionId, workspace, harnessSessions);
            Files.createDirectories(filePath.getParent());

            ONode root = new ONode(Feature.Write_PrettyFormat);
            for (LoopTask t : tasks) {
                root.add(t.toONode());
            }
            String json = root.toJson();

            // 原子写入：先写临时文件，再 rename
            Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                    StandardCharsets.UTF_8)) {
                w.write(json);
            }
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.error("Failed to save loop tasks: {}", e.getMessage());
        }
    }

    /**
     * 从 JSON 文件加载任务列表
     */
    private List<LoopTask> loadFromFile(String sessionId, String workspace, String harnessSessions) {
        try {
            Path filePath = getFilePath(sessionId, workspace, harnessSessions);
            if (!Files.exists(filePath)) return null;

            String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            ONode root = ONode.ofJson(json);

            List<LoopTask> tasks = new ArrayList<>();
            for (ONode node : root.getArray()) {
                tasks.add(LoopTask.fromONode(node));
            }
            return tasks;
        } catch (Exception e) {
            LOG.error("Failed to load loop tasks: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 删除 JSON 文件
     */
    private void deleteFile(String sessionId, String workspace, String harnessSessions) {
        try {
            Path filePath = getFilePath(sessionId, workspace, harnessSessions);
            Files.deleteIfExists(filePath);
        } catch (Exception ignored) {
            // ignored
        }
    }
}
