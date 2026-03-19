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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.bot.core.message.AgentMessage;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 团队任务（Team Task）
 * <p>
 * Agent Teams 中的任务对象，支持：
 * - 任务分配和认领
 * - 优先级管理
 * - 依赖关系
 * - 状态跟踪
 * - 协作信息
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TeamTask {

    @Builder.Default
    private String id = UUID.randomUUID().toString();                  // 任务ID
    private String title;                          // 任务标题
    private String description;                    // 详细描述
    private int priority;  // 优先级（1-10，10最高）
    @Builder.Default
    private Status status = Status.PENDING;
    @Builder.Default// 任务状态
    private TaskType type = TaskType.DEVELOPMENT;                       // 任务类型

    // 认领信息
    private String claimedBy;                       // 认领者 Agent ID
    private long claimTime;                         // 认领时间

    // 执行信息
    private Object result;                         // 执行结果
    private String errorMessage;                   // 错误信息
    private long completedTime;                     // 完成时间

    // 依赖和协作
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();              // 依赖的任务ID列表
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();           // 元数据

    // 性能优化：依赖完成状态缓存（避免重复递归检查）
    private transient volatile Set<String> cachedCompletedDeps;         // 已完成的依赖ID集合
    private transient volatile long cacheVersion;                      // 缓存版本号（用于失效）


    /**
     * 任务状态枚举
     */
    public enum Status {
        PENDING,       // 待认领
        IN_PROGRESS,  // 进行中
        COMPLETED,    // 已完成
        FAILED,       // 失败
        CANCELLED      // 已取消
    }

    /**
     * 任务类型枚举
     */
    public enum TaskType {
        EXPLORATION,   // 探索类任务
        DEVELOPMENT,   // 开发类任务
        ANALYSIS,      // 分析类任务
        DOCUMENTATION, // 文档类任务
        TESTING,      // 测试类任务
        REVIEW        // 审查类任务
    }

    /**
     * 构造函数
     *
     * @param title 任务标题
     */
    public TeamTask(String title) {
        this.id = UUID.randomUUID().toString(); // 初始化 ID
        this.title = title;
        this.priority = 5;
        this.status = Status.PENDING;
        this.type = TaskType.DEVELOPMENT;
        this.dependencies = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 双参数构造函数
     *
     * @param id    任务ID
     * @param title 任务标题
     */
    public TeamTask(String id, String title) {
        this.id = id != null ? id : UUID.randomUUID().toString(); // 防止 id 为 null
        this.title = title;
        this.priority = 5;
        this.status = Status.PENDING;
        this.type = TaskType.DEVELOPMENT;
        this.dependencies = new ArrayList<>();
        this.metadata = new HashMap<>();
    }



    /**
     * 设置 Prompt 并执行
     *
     * @param chatModel LLM 模型
     * @return 异步结果
     */
    public CompletableFuture<ChatResponse> executeWithCall(ChatModel chatModel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 创建 Prompt
                Prompt prompt = Prompt.of(description != null ? description : title);

                // 调用 LLM
                return chatModel.prompt(prompt).call();

            } catch (Throwable e) {
                throw new RuntimeException("Task execution failed: " + title, e);
            }
        });
    }

    /**
     * 设置 Prompt 并执行
     *
     * @param chatModel LLM 模型
     * @return 异步结果
     */
    public CompletableFuture<Flux<ChatResponse>> executeWithSteam(ChatModel chatModel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 创建 Prompt
                Prompt prompt = Prompt.of(description != null ? description : title);

                // 调用 LLM
                return chatModel.prompt(prompt).stream();

            } catch (Throwable e) {
                throw new RuntimeException("Task execution failed: " + title, e);
            }
        });
    }


    /**
     * 设置状态（重写以清除缓存）
     *
     * @param status 任务状态
     * @throws IllegalArgumentException 如果状态转换不合法
     */
    public void setStatus(Status status) {
        // 验证状态转换合法性
        if (!isValidTransition(this.status, status)) {
            throw new IllegalArgumentException(
                String.format("非法的状态转换: %s -> %s (任务: %s)",
                    this.status, status, this.title));
        }
        this.status = status;
        // 清除缓存，因为状态变化会影响依赖完成状态
        this.cachedCompletedDeps = null;
    }

    /**
     * 验证状态转换是否合法
     *
     * @param from 当前状态
     * @param to 目标状态
     * @return 是否合法
     */
    private boolean isValidTransition(Status from, Status to) {
        if (from == to) {
            return true;  // 允许保持相同状态
        }

        switch (from) {
            case PENDING:
                // PENDING 可以转换到：IN_PROGRESS, CANCELLED
                return to == Status.IN_PROGRESS || to == Status.CANCELLED;

            case IN_PROGRESS:
                // IN_PROGRESS 可以转换到：COMPLETED, FAILED, CANCELLED
                return to == Status.COMPLETED || to == Status.FAILED || to == Status.CANCELLED;

            case COMPLETED:
            case FAILED:
            case CANCELLED:
                // 终态不允许再转换
                return false;

            default:
                return false;
        }
    }

    /**
     * 设置依赖列表（重写以清除缓存）
     *
     * @param dependencies 依赖任务ID列表
     */
    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
        // 清除缓存
        this.cachedCompletedDeps = null;
    }

    /**
     * 清除依赖缓存
     * 当依赖任务完成时调用此方法，强制重新检查依赖状态
     */
    public void clearCachedCompletedDeps() {
        this.cachedCompletedDeps = null;
    }

    /**
     * 添加元数据
     */
    public void putMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    /**
     * 检查是否已完成
     */
    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    /**
     * 检查是否失败
     */
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /**
     * 检查是否可认领
     */
    public boolean isClaimable() {
        return status == Status.PENDING;
    }

    /**
     * 检查依赖任务是否都已完成（使用缓存优化性能）
     *
     * @param taskLookup 任务查找函数
     * @return 是否所有依赖都已完成
     * @throws IllegalStateException 如果存在循环依赖
     */
    public boolean areAllDependenciesCompleted(java.util.function.Function<String, TeamTask> taskLookup) {
        // 使用缓存避免重复递归检查
        if (cachedCompletedDeps != null) {
            // 检查所有直接依赖是否都在缓存中
            if (dependencies == null || dependencies.isEmpty()) {
                return true;
            }
            boolean allInCache = cachedCompletedDeps.containsAll(dependencies);
            if (allInCache) {
                return true;
            }
            // 缓存不完整，重新计算
        }

        // 执行递归检查并缓存结果
        Set<String> completedDeps = new java.util.HashSet<>();
        boolean result = areAllDependenciesCompleted(taskLookup, completedDeps, new java.util.HashSet<>());

        if (result) {
            // 缓存已完成的依赖
            cachedCompletedDeps = completedDeps;
        }

        return result;
    }

    /**
     * 递归检查依赖任务是否完成（检测循环依赖）
     *
     * @param taskLookup 任务查找函数
     * @param completedDeps 已完成的依赖ID集合（输出参数）
     * @param visiting 正在访问的任务集合（用于检测循环）
     * @return 是否所有依赖都已完成
     */
    private boolean areAllDependenciesCompleted(java.util.function.Function<String, TeamTask> taskLookup,
                                                java.util.Set<String> completedDeps,
                                                java.util.Set<String> visiting) {
        // 检测循环依赖
        if (visiting.contains(this.id)) {
            throw new IllegalStateException("检测到循环依赖: 任务 " + this.id + " (" + this.title + ")");
        }

        // 如果没有依赖，返回true
        if (dependencies == null || dependencies.isEmpty()) {
            return true;
        }

        // 标记当前任务正在访问
        visiting.add(this.id);

        try {
            // 检查每个直接依赖
            for (String depId : dependencies) {
                TeamTask dep = taskLookup.apply(depId);

                // 依赖任务不存在
                if (dep == null) {
                    return false;
                }

                // 依赖任务未完成
                if (!dep.isCompleted()) {
                    return false;
                }

                // 记录已完成的依赖
                completedDeps.add(depId);

                // 递归检查依赖的依赖（间接依赖）
                if (!dep.areAllDependenciesCompleted(taskLookup, completedDeps, visiting)) {
                    return false;
                }
            }

            return true;
        } finally {
            // 移除访问标记
            visiting.remove(this.id);
        }
    }

    /**
     * 获取完整的依赖树（用于可视化）
     *
     * @param taskLookup 任务查找函数
     * @return 依赖树描述
     */
    public String getDependencyTree(java.util.function.Function<String, TeamTask> taskLookup) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务依赖树: ").append(this.title).append(" (").append(this.id).append(")\n");
        buildDependencyTree(taskLookup, this, "    ", new java.util.HashSet<>(), sb);
        return sb.toString();
    }

    /**
     * 递归构建依赖树
     */
    private void buildDependencyTree(java.util.function.Function<String, TeamTask> taskLookup,
                                     TeamTask task,
                                     String prefix,
                                     java.util.Set<String> visited,
                                     StringBuilder sb) {
        // 防止重复访问（循环依赖检测）
        if (visited.contains(task.getId())) {
            sb.append(prefix).append("└── [[WARN] 循环依赖] ").append(task.getTitle())
                    .append(" (").append(task.getId()).append(")\n");
            return;
        }

        visited.add(task.getId());

        // 如果不是根节点，输出当前任务
        if (!task.getId().equals(this.id)) {
            String statusIcon = getStatusIcon(task.getStatus());
            sb.append(prefix).append("└── ").append(statusIcon).append(" ").append(task.getTitle())
                    .append(" (").append(task.getId()).append(")\n");
        }

        // 递归输出依赖
        if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
            String childPrefix = prefix + "│   ";
            for (String depId : task.getDependencies()) {
                TeamTask dep = taskLookup.apply(depId);
                if (dep != null) {
                    buildDependencyTree(taskLookup, dep, childPrefix, visited, sb);
                } else {
                    sb.append(childPrefix).append("└── [[ERROR] 不存在] ").append(depId).append("\n");
                }
            }
        }

        visited.remove(task.getId());
    }

    /**
     * 获取状态图标
     * 支持通过系统属性控制使用 Emoji 或 ASCII 字符
     * -DteamTask.useEmoji=true  使用 Emoji（默认）
     * -DteamTask.useEmoji=false 使用 ASCII 字符（兼容旧系统）
     */
    private String getStatusIcon(Status status) {
        boolean useEmoji = Boolean.parseBoolean(
                System.getProperty("teamTask.useEmoji", "true"));

        if (useEmoji) {
            // Emoji 模式（默认，需要 UTF-8 支持）
            switch (status) {
                case PENDING:
                    return "[WAIT]";
                case IN_PROGRESS:
                    return "[PROCESS]";
                case COMPLETED:
                    return "[OK]";
                case FAILED:
                    return "[ERROR]";
                case CANCELLED:
                    return "[CANCEL]";
                default:
                    return "[UNKNOWN]";
            }
        } else {
            // ASCII 模式（兼容旧系统/Windows CMD）
            switch (status) {
                case PENDING:
                    return "[WAIT]";
                case IN_PROGRESS:
                    return "[DOING]";
                case COMPLETED:
                    return "[DONE]";
                case FAILED:
                    return "[FAIL]";
                case CANCELLED:
                    return "[CANCEL]";
                default:
                    return "[???]";
            }
        }
    }

    /**
     * 检测是否存在循环依赖
     *
     * @param taskLookup 任务查找函数
     * @return 是否存在循环依赖
     */
    public boolean hasCyclicDependency(java.util.function.Function<String, TeamTask> taskLookup) {
        return detectCyclicDependency(taskLookup, new java.util.HashSet<>());
    }

    /**
     * 递归检测循环依赖
     *
     * @param taskLookup 任务查找函数
     * @param visiting   正在访问的任务集合
     * @return 是否存在循环依赖
     */
    private boolean detectCyclicDependency(java.util.function.Function<String, TeamTask> taskLookup,
                                           java.util.Set<String> visiting) {
        // 检测循环依赖
        if (visiting.contains(this.id)) {
            return true; // 发现循环
        }

        // 如果没有依赖，返回false
        if (dependencies == null || dependencies.isEmpty()) {
            return false;
        }

        // 标记当前任务正在访问
        visiting.add(this.id);

        try {
            // 递归检查每个依赖
            for (String depId : dependencies) {
                TeamTask dep = taskLookup.apply(depId);
                if (dep != null) {
                    if (dep.detectCyclicDependency(taskLookup, visiting)) {
                        return true; // 发现循环
                    }
                }
            }

            return false; // 没有循环
        } finally {
            // 移除访问标记
            visiting.remove(this.id);
        }
    }

    /**
     * 获取所有依赖任务ID（包括间接依赖）
     *
     * @param taskLookup 任务查找函数
     * @return 所有依赖任务ID集合
     */
    public Set<String> getAllDependencyIds(java.util.function.Function<String, TeamTask> taskLookup) {
        Set<String> allDeps = new java.util.HashSet<>();
        collectAllDependencies(taskLookup, this, allDeps, new java.util.HashSet<>());
        return allDeps;
    }

    /**
     * 递归收集所有依赖ID
     */
    private void collectAllDependencies(java.util.function.Function<String, TeamTask> taskLookup,
                                        TeamTask task,
                                        Set<String> result,
                                        Set<String> visited) {
        // 防止循环依赖导致无限递归
        if (visited.contains(task.getId())) {
            return;
        }

        visited.add(task.getId());

        if (task.getDependencies() != null) {
            for (String depId : task.getDependencies()) {
                if (result.add(depId)) { // 避免重复添加
                    TeamTask dep = taskLookup.apply(depId);
                    if (dep != null) {
                        collectAllDependencies(taskLookup, dep, result, visited);
                    }
                }
            }
        }

        visited.remove(task.getId());
    }

    /**
     * 获取耗时
     */
    public long getDuration() {
        if (completedTime > 0 && claimTime > 0) {
            return completedTime - claimTime;
        }
        return 0;
    }


    @Override
    public String toString() {
        return "TeamTask{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", priority=" + priority +
                ", status=" + status +
                ", type=" + type +
                ", claimedBy='" + claimedBy + '\'' +
                (result != null ? ", hasResult=true" : "") +
                (errorMessage != null ? ", error='" + errorMessage + '\'' : "") +
                '}';
    }

}
