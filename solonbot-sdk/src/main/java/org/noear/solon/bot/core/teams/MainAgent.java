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

import lombok.Getter;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.cli.PoolManager;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.event.AgentEvent;
import org.noear.solon.bot.core.event.AgentEventType;
import org.noear.solon.bot.core.event.EventBus;
import org.noear.solon.bot.core.event.EventHandler;
import org.noear.solon.bot.core.event.EventMetadata;
import org.noear.solon.bot.core.goalker.GoalKeeperIntegration;
import org.noear.solon.bot.core.memory.SharedMemoryManager;
import org.noear.solon.bot.core.memory.ShortTermMemory;
import org.noear.solon.bot.core.message.AgentMessage;
import org.noear.solon.bot.core.message.MessageAck;
import org.noear.solon.bot.core.message.MessageChannel;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.Subagent;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主代理（Team Lead）- Agent Teams 模式协调器
 *
 * 负责任务：
 * - 创建和管理共享任务列表
 * - 分配初始任务到共享任务池
 * - 协调多个子代理协作
 * - 监控任务执行状态
 * - 汇总最终结果
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public class MainAgent {
    private static final Logger LOG = LoggerFactory.getLogger(MainAgent.class);

    private final SubAgentMetadata config;
    private final AgentSessionProvider sessionProvider;
    private final SharedMemoryManager sharedMemoryManager;
    private final EventBus eventBus;
    private final MessageChannel messageChannel;
    private final SharedTaskList taskList;
    private final String workDir;
    private final PoolManager poolManager;

    // 目标守护者
    private GoalKeeperIntegration goalKeeper;

    // 新增：用于访问 subagent 功能
    private final AgentKernel kernel;
    private final SubagentManager subagentManager;

    private ReActAgent agent;
    private ChatModel chatModel;  // 保存 ChatModel 引用用于任务分析
    private AgentSession session;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 任务事件监听器
    private String taskEventSubscriptionId;
    private String taskFailedSubscriptionId;  //  新增：保存失败事件订阅ID
    private String messageHandlerId;

    // 性能优化：使用 CountDownLatch 替代轮询
    private volatile CountDownLatch taskCompletionLatch;

    private static final long SUBAGENT_STREAM_TIMEOUT_MS = 180_000;



    public MainAgent(SubAgentMetadata config,
                     AgentSessionProvider sessionProvider,
                     SharedMemoryManager sharedMemoryManager,
                     EventBus eventBus,
                     MessageChannel messageChannel,
                     SharedTaskList taskList,
                     String workDir,
                     PoolManager poolManager) {
        this(config, sessionProvider, sharedMemoryManager, eventBus, messageChannel,
             taskList, workDir, poolManager, null, null);
    }

    /**
     * 完整构造函数（支持 subagent 功能）
     */
    public MainAgent(SubAgentMetadata config,
                     AgentSessionProvider sessionProvider,
                     SharedMemoryManager sharedMemoryManager,
                     EventBus eventBus,
                     MessageChannel messageChannel,
                     SharedTaskList taskList,
                     String workDir,
                     PoolManager poolManager,
                     AgentKernel kernel,
                     SubagentManager subagentManager) {
        this.config = config;
        this.sessionProvider = sessionProvider;
        this.sharedMemoryManager = sharedMemoryManager;
        this.eventBus = eventBus;
        this.messageChannel = messageChannel;
        this.taskList = taskList;
        this.workDir = workDir;
        this.poolManager = poolManager;
        this.kernel = kernel;
        this.subagentManager = subagentManager;

        // 注册任务事件监听器
        registerTaskEventListeners();

        // 注册消息处理器
        registerMessageHandler();
    }

    /**
     * 获取 Team Lead（团队协调器）指令
     *
     * 此指令会被追加到系统提示词中，让 Agent 了解自己作为 Team Lead 的角色和职责。
     *
     * @return Team Lead 角色指令
     */
    public String getTeamLeadInstruction() {
        return "## Team Lead（团队协调器）角色\n\n" +
                "你现在启用了 **Agent Teams 模式**，你是团队的 **Team Lead（团队领导）**。\n\n" +
                "### 核心职责\n\n" +
                "1. **任务分析** - 判断任务类型，选择合适的协作模式\n" +
                "2. **团队协作** - 协调多个专业子代理（teammates）协作完成任务\n" +
                "3. **结果汇总** - 收集各子代理的结果，形成最终答案\n" +
                "4. **质量控制** - 确保子任务完成的质量和一致性\n\n" +
                "### 两种协作模式（必须正确选择）\n\n" +
                "#### 模式1：简单多专家协作（直接调用 task()）\n\n" +
                "**适用场景**：\n" +
                "- 用户明确要求多个专家从不同角度分析\n" +
                "- 任务相对简单，不需要复杂的工作流\n" +
                "- 可以并行调用多个专家，然后汇总结果\n\n" +
                "**关键词**：\"专家\"、\"分析\"、\"建议\"、\"从.*角度\"、\"多.*位.*专家\"\n\n" +
                "**执行流程**：\n" +
                "1. 直接调用多个 `task(name=\"专家名\", prompt=\"具体问题\")`\n" +
                "2. 等待所有专家返回结果\n" +
                "3. MainAgent 汇总各专家意见，给出总结\n\n" +
                "**示例**：\n" +
                "```\n" +
                "用户：找100以内的完全平方数，请三位专家（代数、几何、数论）分析\n\n" +
                "执行：\n" +
                "1. task(name=\"代数专家\", prompt=\"从代数角度分析100以内的完全平方数\")\n" +
                "2. task(name=\"几何专家\", prompt=\"从几何角度分析100以内的完全平方数\")\n" +
                "3. task(name=\"数论专家\", prompt=\"从数论角度分析100以内的完全平方数\")\n" +
                "4. 汇总三位专家的分析结果\n" +
                "```\n\n" +
                "**多轮讨论（如果需要专家互相质疑、补充）**：\n" +
                "```\n" +
                "# 第1轮：专家A首发\n" +
                "result1 = task(name=\"专家A\", prompt=\"分析问题\")\n\n" +
                "# 第2轮：专家B回应专家A\n" +
                "result2 = task(\n" +
                "    name=\"专家B\",\n" +
                "    prompt=\"专家A的观点：\" + result1 + \"\\n请从你的角度回应或质疑\"\n" +
                ")\n\n" +
                "# 第3轮：专家C综合前两位\n" +
                "result3 = task(\n" +
                "    name=\"专家C\",\n" +
                "    prompt=\"专家A：\" + result1 + \"\\n专家B：\" + result2 + \"\\n请给出综合建议\"\n" +
                ")\n\n" +
                "# 汇总讨论结果\n" +
                "final = \"经过讨论，三位专家达成共识...\"\n" +
                "```\n\n" +
                "#### 模式2：复杂任务编排（任务列表 + 智能路由）\n\n" +
                "**适用场景**：\n" +
                "- 多步骤、复杂的工作流\n" +
                "- 需要任务之间的依赖和协调\n" +
                "- 需要团队成员通过任务列表自主协作\n\n" +
                "**关键词**：\"实现\"、\"开发\"、\"设计\"、\"重构\"、\"优化\"等多步骤工程任务\n\n" +
                "**执行流程**：\n" +
                "1. 使用 `task_add()` 或 `create_task()` 创建任务列表\n" +
                "2. 团队成员通过 `list_all_tasks()` 或 `get_claimable_tasks()` 查看任务\n" +
                "3. 团队成员认领任务并执行（可选：使用 `claim_task()` 显式认领）\n" +
                "4. 团队成员完成任务（`complete_task()` 会自动处理认领）\n" +
                "5. MainAgent 使用 `team_status()` 跟踪进度并汇总最终结果\n\n" +
                "**简化用法**：\n" +
                "- 创建任务后，可以直接调用 `complete_task(taskId, result)`\n" +
                "- `complete_task()` 会自动处理 PENDING → IN_PROGRESS → COMPLETED 的状态流转\n" +
                "- 无需手动调用 `claim_task()`（除非需要明确指派给特定成员）\n\n" +
                "**示例**：\n" +
                "```\n" +
                "用户：实现一个用户登录功能\n\n" +
                "执行：\n" +
                "1. task_add(\"设计登录API接口\")\n" +
                "2. task_add(\"实现用户认证逻辑\")\n" +
                "3. task_add(\"编写单元测试\")\n" +
                "4. task_add(\"更新文档\")\n" +
                "5. team_status() 查看进度\n" +
                "6. 等待团队成员认领并完成任务\n" +
                "```\n\n" +
                "### 可用工具\n\n" +
                "**子代理调用**（模式1使用）：\n" +
                "- `task(name, prompt)` - 直接调用特定子代理执行任务\n" +
                "- `teammate_quick(name, role)` - 快速创建团队成员\n" +
                "- `teammates()` - 列出所有团队成员\n\n" +
                "**任务管理**（模式2使用）：\n" +
                "- `task_add(title)` - 快速添加任务\n" +
                "- `tasks_add(titles)` - 批量添加任务\n" +
                "- `create_task(title, description, ...)` - 创建任务（完整配置）\n" +
                "- `list_all_tasks()` - 查看所有任务\n" +
                "- `team_status()` - 查看团队任务状态\n\n" +
                "**记忆管理**：\n" +
                "- `memory_store(content)` - 存储记忆（自动分类）\n" +
                "- `memory_recall(query)` - 检索记忆\n" +
                "- `memory_stats()` - 查看记忆统计\n\n" +
                "### 强制规则（必须遵守）\n\n" +
                "**禁止行为**：\n" +
                "- ❌ 禁止\"模拟\"或\"编造\"专家的回答\n" +
                "- ❌ 禁止自己代替专家发言\n" +
                "- ❌ 禁止说\"专家讨论完毕\"但实际没有调用 `task()`\n" +
                "- ❌ 禁止简单任务也使用任务编排（过度设计）\n" +
                "- ❌ 禁止复杂任务直接调用 `task()`（缺乏协调）\n\n" +
                "**必须行为**：\n" +
                "- ✅ 简单多专家任务：必须使用 `task()` 多次调用，然后汇总\n" +
                "- ✅ 复杂多步骤任务：必须使用任务编排（task_add + team_status）\n" +
                "- ✅ 每个专家角色对应一次独立的 `task()` 调用\n" +
                "- ✅ 使用 `teammate_quick()` 快速创建需要的团队成员\n\n" +
                "### 决策流程\n\n" +
                "1. 用户请求包含\"专家\"、\"分析\"等关键词？\n" +
                "   → 是：使用模式1（直接调用 task()）\n" +
                "   → 否：继续判断\n" +
                "2. 任务是否为多步骤、需要依赖协调？\n" +
                "   → 是：使用模式2（任务编排）\n" +
                "   → 否：直接回答\n\n" +
                "### 工作原则\n\n" +
                "- 简单问题直接回答，无需协作\n" +
                "- 多专家分析用 `task()`，快速并行\n" +
                "- 复杂工程用任务编排，智能路由\n" +
                "- 记忆重要决策，方便后续查阅";
    }

    /**
     * 设置共享的 ReActAgent
     *
     * MainAgent 不再创建自己的 ReActAgent，而是使用 AgentKernel 创建的主 ReActAgent。
     * 这样可以避免两层推理，简化架构。
     *
     * @param agent 共享的 ReActAgent
     * @param chatModel ChatModel（用于任务分析等功能）
     */
    public synchronized void setSharedAgent(ReActAgent agent, ChatModel chatModel) {
        if (this.agent != null) {
            LOG.warn("MainAgent 已有共享 Agent，将被覆盖");
        }
        this.agent = agent;
        this.chatModel = chatModel;
        this.session = sessionProvider.getSession("main_agent");
        LOG.info("MainAgent 已设置共享 ReActAgent");
    }

    /**
     * 流式执行主任务（实时输出）
     *
     * @param prompt 用户提示
     * @param __cwd 工作目录
     * @return 响应流
     */
    public Flux<AgentChunk> executeStream(Prompt prompt, String __cwd) {
        if (agent == null) {
            throw new IllegalStateException("MainAgent 尚未初始化");
        }

        running.set(true);

        // 0. 自动创建主任务到 SharedTaskList
        TeamTask mainTask = null;
        try {
            String userContent = prompt.getUserContent();
            // 生成简短标题（取前50个字符）
            String title = userContent.length() > 50
                    ? userContent.substring(0, 50) + "..."
                    : userContent;

            mainTask = new TeamTask();
            mainTask.setTitle(title);
            mainTask.setDescription(userContent);
            mainTask.setType(TeamTask.TaskType.DEVELOPMENT);
            mainTask.setPriority(10); // 主任务高优先级
            mainTask.setStatus(TeamTask.Status.IN_PROGRESS);

            CompletableFuture<TeamTask> future = taskList.addTask(mainTask);
            mainTask = future.join();
            LOG.info("主任务已自动创建: taskId={}, title={}", mainTask.getId(), mainTask.getTitle());

        } catch (Exception e) {
            LOG.warn("创建主任务失败（继续执行）: {}", e.getMessage());
        }

        final TeamTask finalMainTask = mainTask;

        // 1. 启动目标守护（防止在多轮循环中偏离目标）
        String goalId = null;
        try {
            if (kernel != null) {
                goalId = this.startGoalGuarding(prompt.getUserContent());
                LOG.info("目标守护已启动: goalId={}, 目标={}", goalId, prompt.getUserContent());
            }
        } catch (Throwable e) {
            LOG.warn("启动目标守护失败（继续执行）: {}", e.getMessage());
        }

        try {
            // 2. 发布主代理任务开始事件
            publishEvent(AgentEventType.MAIN_TASK_STARTED, prompt.getUserContent(), null);

            // 2. 执行主代理内部的协调逻辑（流式输出）
            // 注意：任务分解现在由 Agent 通过工具自主决定
            reactor.core.publisher.Flux<AgentChunk> responseStream = agent.prompt(prompt)
                    .session(session)
                    .options(o -> {
                        // 传递工作目录给工具（ls、bash 等需要）
                        if (__cwd != null && !__cwd.isEmpty()) {
                            o.toolContextPut("__cwd", __cwd);
                        }
                    })
                    .stream();

            // 7. 在流完成后等待所有子任务完成
            // 等待所有子任务完成
            // 汇总结果
            // 更新主任务状态
            // 发布主代理任务完成事件
            // 停止目标守护
            // 更新主任务状态为失败
            // 出错时也要停止目标守护
            // 更新主任务状态为取消
            // 取消时也要停止目标守护

            return responseStream
                    .doOnComplete(() -> {
                        try {
                            // 等待所有子任务完成
                            waitForAllTasksCompleted();

                            // 汇总结果
                            String summary = summarizeResults();

                            // 更新主任务状态
                            if (finalMainTask != null) {
                                try {
                                    taskList.completeTask(finalMainTask.getId(), summary);
                                    LOG.info("主任务已完成: taskId={}", finalMainTask.getId());
                                } catch (Exception e) {
                                    LOG.warn("更新主任务状态失败: {}", e.getMessage());
                                }
                            }

                            // 发布主代理任务完成事件
                            publishEvent(AgentEventType.MAIN_TASK_COMPLETED, summary, null);

                            LOG.info("MainAgent 流式执行完成");

                        } catch (Throwable e) {
                            LOG.error("MainAgent 后处理失败", e);
                        } finally {
                            // 设置运行状态为 false
                            running.set(false);

                            // 停止目标守护
                            try {
                                this.stopGoalGuarding();
                                LOG.info("目标守护已停止");
                            } catch (Throwable e) {
                                LOG.warn("停止目标守护失败: {}", e.getMessage());
                            }
                        }
                    })
                    .doOnError(error -> {
                        LOG.error("MainAgent 流式执行出错", error);

                        // 更新主任务状态为失败
                        if (finalMainTask != null) {
                            try {
                                taskList.failTask(finalMainTask.getId(), error.getMessage());
                                LOG.info("主任务已标记为失败: taskId={}", finalMainTask.getId());
                            } catch (Exception e) {
                                LOG.warn("更新主任务失败状态失败: {}", e.getMessage());
                            }
                        }

                        running.set(false);
                        // 出错时也要停止目标守护
                        try {
                            this.stopGoalGuarding();
                            LOG.info("目标守护已停止（错误）");
                        } catch (Throwable e) {
                            LOG.warn("停止目标守护失败: {}", e.getMessage());
                        }
                    })
                    .doOnCancel(() -> {
                        LOG.warn("MainAgent 流式执行被取消");

                        // 更新主任务状态为取消
                        if (finalMainTask != null) {
                            try {
                                taskList.removeTask(finalMainTask.getId());
                                LOG.info("主任务已取消: taskId={}", finalMainTask.getId());
                            } catch (Exception e) {
                                LOG.warn("更新主任务取消状态失败: {}", e.getMessage());
                            }
                        }

                        running.set(false);
                        // 取消时也要停止目标守护
                        try {
                            this.stopGoalGuarding();
                            LOG.info("目标守护已停止（取消）");
                        } catch (Throwable e) {
                            LOG.warn("停止目标守护失败: {}", e.getMessage());
                        }
                    });

        } finally {
            running.set(false);
            // 确保目标守护被停止（即使发生异常）
            try {
                this.stopGoalGuarding();
                LOG.info("目标守护已停止（finally块）");
            } catch (Throwable e) {
                LOG.warn("停止目标守护失败（finally块）: {}", e.getMessage());
            }
        }
    }

    /**
     * 广播任务可用通知
     */
    private void broadcastTaskNotification(List<TeamTask> tasks) {
        if (messageChannel != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("action", "tasks_available");
            payload.put("count", tasks.size());
            payload.put("tasks", tasks);

            AgentMessage<Map<String, Object>> message = AgentMessage.<Map<String, Object>>of(payload)
                    .from(config.getName())
                    .to("*")
                    .type("task_notification")
                    .build();

            try {
                List<MessageAck> acks = messageChannel.broadcast(message).join();
                LOG.info("任务通知已广播，收到 {} 个确认", acks.size());
            } catch (Throwable e) {
                LOG.warn("广播任务通知失败", e);
            }
        }
    }

    /**
     * 等待所有任务完成（使用事件驱动机制，性能优化）
     */
    private void waitForAllTasksCompleted() {
        // 初始化 CountDownLatch（初始值为1，防止立即返回）
        taskCompletionLatch = new CountDownLatch(1);

        try {
            // 检查初始状态
            SharedTaskList.TaskStatistics stats = taskList.getStatistics();
            if (stats.inProgressTasks == 0 && stats.pendingTasks == 0) {
                LOG.info("所有子任务已完成（总计: {}, 完成: {}, 失败: {}）",
                        stats.totalTasks, stats.completedTasks, stats.failedTasks);
                return;
            }

            LOG.info("等待任务完成... (进行中: {}, 待认领: {})",
                    stats.inProgressTasks, stats.pendingTasks);

            // 每30秒打印一次统计信息（使用超时等待，避免阻塞太久）
            long remainingWaitMs = 300_000; // 最多等待5分钟
            long printIntervalMs = 30_000;   // 每30秒打印一次

            while (remainingWaitMs > 0) {
                long waitTime = Math.min(printIntervalMs, remainingWaitMs);

                // 等待任务完成或超时
                boolean completed = taskCompletionLatch.await(waitTime, TimeUnit.MILLISECONDS);

                stats = taskList.getStatistics();

                if (stats.inProgressTasks == 0 && stats.pendingTasks == 0) {
                    LOG.info("所有子任务已完成（总计: {}, 完成: {}, 失败: {}）",
                            stats.totalTasks, stats.completedTasks, stats.failedTasks);
                    break;
                }

                if (!completed) {
                    // 超时，打印进度
                    LOG.info("任务进度: {}", stats);

                    // 打印阻塞信息
                    if (stats.pendingTasks > 0) {
                        String blockingInfo = taskList.getBlockingInfo();
                        if (blockingInfo.length() > 50) {
                            LOG.debug("阻塞任务:\n{}", blockingInfo);
                        }
                    }
                }

                remainingWaitMs -= waitTime;
            }

            if (remainingWaitMs <= 0) {
                LOG.warn("等待任务完成超时，当前状态: {}", taskList.getStatistics());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("等待任务完成被中断");
        } finally {
            taskCompletionLatch = null;
        }
    }

    /**
     * 汇总结果
     */
    private String summarizeResults() {
        SharedTaskList.TaskStatistics stats = taskList.getStatistics();
        List<TeamTask> allTasks = taskList.getAllTasks();

        StringBuilder summary = new StringBuilder();
        summary.append("## 任务执行汇总\n\n");
        summary.append(stats.toString()).append("\n\n");

        // 列出完成的任务
        summary.append("### 已完成任务\n\n");
        for (TeamTask task : allTasks) {
            if (task.isCompleted()) {
                summary.append(String.format("- **%s**: %s\n",
                        task.getTitle(),
                        task.getResult() != null ? task.getResult().toString() : "完成"));
            }
        }

        // 列出失败的任务
        summary.append("\n### 失败任务\n\n");
        for (TeamTask task : allTasks) {
            if (task.isFailed()) {
                summary.append(String.format("- **%s**: %s\n",
                        task.getTitle(),
                        task.getErrorMessage()));
            }
        }

        return summary.toString();
    }

    /**
     * 注册任务事件监听器
     * 性能优化：添加事件驱动机制，在所有任务完成时触发 CountDownLatch
     */
    private void registerTaskEventListeners() {
        if (eventBus != null) {
            // 监听子任务完成事件
            taskEventSubscriptionId = eventBus.subscribe(AgentEventType.TASK_COMPLETED, event -> {
                String taskId = event.getMetadata().getTaskId();
                Map<String, Object> payload = (Map<String, Object>) event.getPayload();

                String taskTitle = payload != null ? (String) payload.get("taskTitle") : "Unknown";
                String agentId = payload != null ? (String) payload.get("agentId") : "Unknown";

                LOG.info("主代理收到子任务完成事件: {} by {}", taskTitle, agentId);

                // 将任务结果存储到共享内存（供后续任务使用）
                if (sharedMemoryManager != null && payload != null) {
                    try {
                        Object result = payload.get("result");
                        if (result != null) {
                            // 创建短期记忆存储任务结果
                            ShortTermMemory memory = new ShortTermMemory(
                                    agentId,
                                    result.toString(),
                                    taskId
                            );
                            memory.setId("task-result-" + taskId);

                            // 添加元数据
                            memory.putMetadata("taskTitle", taskTitle);
                            memory.putMetadata("taskType", "task-result");
                            memory.putMetadata("source", agentId);

                            sharedMemoryManager.store(memory);
                            LOG.debug("任务结果已存储到共享内存: taskId={}, source={}", taskId, agentId);
                        }
                    } catch (Throwable e) {
                        LOG.warn("存储任务结果到共享内存失败", e);
                    }
                }

                // 检查是否有依赖此任务的其他任务可以开始执行
                checkAndNotifyDependentTasks(taskId);

                // 性能优化：检查是否所有任务都已完成，触发 CountDownLatch
                checkAllTasksCompleted();

                return CompletableFuture.completedFuture(EventHandler.Result.success());
            });

            // 监听子任务失败事件
            taskFailedSubscriptionId = eventBus.subscribe(AgentEventType.TASK_FAILED, event -> {
                String taskId = event.getMetadata().getTaskId();
                Map<String, Object> payload = (Map<String, Object>) event.getPayload();

                String taskTitle = payload != null ? (String) payload.get("taskTitle") : "Unknown";
                String agentId = payload != null ? (String) payload.get("agentId") : "Unknown";

                // 获取错误信息（从任务对象或payload）
                String errorMessage = null;
                if (payload != null && payload.containsKey("errorMessage")) {
                    errorMessage = (String) payload.get("errorMessage");
                } else {
                    // 从任务列表中获取错误信息
                    TeamTask failedTask = taskList.getTask(taskId);
                    if (failedTask != null) {
                        errorMessage = failedTask.getErrorMessage();
                    }
                }

                if (errorMessage == null) {
                    errorMessage = "Unknown error";
                }

                LOG.warn("主代理收到子任务失败事件: {} by {} - {}", taskTitle, agentId, errorMessage);

                // 处理任务失败：决定是否需要重试或标记依赖任务失败
                handleTaskFailure(taskId, errorMessage);

                // 性能优化：检查是否所有任务都已完成（包括失败的），触发 CountDownLatch
                checkAllTasksCompleted();

                return CompletableFuture.completedFuture(EventHandler.Result.success());
            });

            LOG.info("MainAgent 任务事件监听器已注册（TASK_COMPLETED, TASK_FAILED）");
        }
    }

    /**
     * 检查是否所有任务都已完成
     * 性能优化：如果所有任务完成，触发 CountDownLatch
     */
    private void checkAllTasksCompleted() {
        if (taskCompletionLatch != null) {
            SharedTaskList.TaskStatistics stats = taskList.getStatistics();
            if (stats.inProgressTasks == 0 && stats.pendingTasks == 0) {
                // 所有任务都已完成或失败，触发 CountDownLatch
                taskCompletionLatch.countDown();
                LOG.debug("所有任务完成，触发 CountDownLatch");
            }
        }
    }

    /**
     * 检查并通知依赖此任务的其他任务
     * 修复：实际广播任务可用通知，而不仅仅是记录日志
     */
    private void checkAndNotifyDependentTasks(String completedTaskId) {
        try {
            // 查找依赖此任务的所有待认领任务
            List<TeamTask> claimableTasks = taskList.getClaimableTasks();

            if (!claimableTasks.isEmpty()) {
                LOG.info("发现 {} 个可认领的任务（由于任务 {} 完成），广播通知...",
                    claimableTasks.size(), completedTaskId);

                // 修复：通过 MessageChannel 广播任务可用通知
                broadcastTaskNotification(claimableTasks);
            }
        } catch (Throwable e) {
            LOG.error("检查依赖任务失败", e);
        }
    }

    /**
     * 处理任务失败
     */
    private void handleTaskFailure(String taskId, String errorMessage) {
        try {
            // 查找依赖此失败任务的其他任务
            List<TeamTask> allTasks = taskList.getAllTasks();
            List<TeamTask> dependentTasks = new ArrayList<>();

            for (TeamTask task : allTasks) {
                if (task.getStatus() == TeamTask.Status.PENDING &&
                    task.getDependencies().contains(taskId)) {
                    dependentTasks.add(task);
                }
            }

            if (!dependentTasks.isEmpty()) {
                LOG.warn("发现 {} 个任务受影响（依赖失败任务 {}）", dependentTasks.size(), taskId);

                // 策略1：标记依赖任务为失败
                // 策略2：等待人工干预
                // 当前：记录日志，保持待认领状态（由人工决策）
            }
        } catch (Throwable e) {
            LOG.error("处理任务失败时发生异常", e);
        }
    }

    /**
     * 注册消息处理器
     */
    private void registerMessageHandler() {
        if (messageChannel != null) {
            messageHandlerId = messageChannel.registerHandler(
                    config.getName(),
                    this::handleMessage
            );
            LOG.info("MainAgent 消息处理器已注册");
        }
    }

    /**
     * 处理子代理发送的消息
     */
    private <T> CompletableFuture<Object> handleMessage(AgentMessage<T> message) {
        LOG.debug("MainAgent 收到消息: from={}, type={}",
                message.getFrom(), message.getType());

        // 处理任务相关消息
        if ("task_query".equals(message.getType())) {
            // 子代理查询任务列表
            return CompletableFuture.completedFuture(getTaskListInfo());
        } else if ("task_help".equals(message.getType())) {
            // 子代理请求帮助
            return CompletableFuture.completedFuture(handleHelpRequest(message));
        }

        return CompletableFuture.completedFuture("ACK");
    }

    /**
     * 获取任务列表信息
     */
    private Map<String, Object> getTaskListInfo() {
        Map<String, Object> info = new HashMap<>();
        SharedTaskList.TaskStatistics stats = taskList.getStatistics();

        info.put("statistics", stats);
        info.put("pendingTasks", taskList.getPendingTasks().size());
        info.put("claimableTasks", taskList.getClaimableTasks().size());

        return info;
    }

    /**
     * 处理帮助请求
     */
    private <T> Object handleHelpRequest(AgentMessage<T> message) {
        LOG.info("收到来自 {} 的帮助请求", message.getFrom());

        // 这里可以实现协调逻辑，例如：
        // 1. 重新分配任务
        // 2. 提供额外的资源
        // 3. 协调多个代理协作

        return "Help request received";
    }

    /**
     * 发布事件
     */
    private void publishEvent(AgentEventType eventType, Object payload, String taskId) {
        if (eventBus != null) {
            EventMetadata metadata = EventMetadata.builder()
                    .sourceAgent(config.getName())
                    .taskId(taskId)
                    .priority(5)
                    .build();

            AgentEvent event = new AgentEvent(eventType, payload, metadata);
            eventBus.publishAsync(event);
        }
    }


    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }


    /**
     * 清理资源
     */
    public void destroy() {
        // 注销事件监听器
        if (eventBus != null) {
            if (taskEventSubscriptionId != null) {
                eventBus.unsubscribe(taskEventSubscriptionId);
                LOG.info("MainAgent TASK_COMPLETED 事件监听器已注销");
            }
            if (taskFailedSubscriptionId != null) {
                eventBus.unsubscribe(taskFailedSubscriptionId);
                LOG.info("MainAgent TASK_FAILED 事件监听器已注销");
            }
        }

        // 注销消息处理器
        if (messageChannel != null && messageHandlerId != null) {
            messageChannel.unregisterHandler(config.getName(), messageHandlerId);
            LOG.info("MainAgent 消息处理器已注销");
        }
    }

    /**
     * 启动目标守护
     *
     * @param userPrompt 用户的目标提示词
     * @return 目标 ID
     */
    public String startGoalGuarding(String userPrompt) {
        if (goalKeeper == null) {
            goalKeeper = new GoalKeeperIntegration(this);
        }
        return goalKeeper.startGoalGuarding(userPrompt);
    }

    /**
     * 停止目标守护
     */
    public void stopGoalGuarding() {
        if (goalKeeper != null) {
            goalKeeper.stopGoalGuarding();
        }
    }

    public AgentResponse call(String __cwd, String sessionId, Prompt prompt) throws Throwable {
        AgentSession session = kernel.getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__cwd", __cwd);
                })
                .call();
    }

    public Flux<AgentChunk> stream(String __cwd, String sessionId, Prompt prompt) {
        AgentSession session = kernel.getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut("__cwd", __cwd);
                })
                .stream();
    }

    /**
     * 执行流式子代理调用
     */
    public String executeStream( String __cwd, String sessionId,
                                       Prompt prompt, ReActTrace __parentTrace, String name) {
        try {
            String promptStr = prompt.toString();
            LOG.info("[mainAgent] 启动异步流式执行: type={}, sessionId={}, promptLength={}",
                    name, sessionId, promptStr != null ? promptStr.length() : 0);

            final long[] firstChunkTime = {0};
            final long[] lastChunkTime = {System.currentTimeMillis()};
            final int[] chunkCount = {0};
            final StringBuilder contentBuilder = new StringBuilder();

            String result = this.stream(__cwd, sessionId, prompt)
                    .doOnSubscribe(s -> {
                        LOG.info("[mainAgent] 流订阅成功: name={}, sessionId={}", name, sessionId);
                    })
                    .doOnNext(chunk -> {
                        long now = System.currentTimeMillis();
                        if (chunkCount[0] == 0) {
                            firstChunkTime[0] = now;
                            long firstChunkDelay = now - lastChunkTime[0];
                            LOG.info("[mainAgent] 收到首个chunk: name={}, delay={}ms, chunkType={}",
                                    name, firstChunkDelay, chunk.getClass().getSimpleName());
                        }
                        lastChunkTime[0] = now;
                        chunkCount[0]++;

                        LOG.debug("[mainAgent] 收到chunk: type={}, chunkType={}, total={}",
                                name, chunk.getClass().getSimpleName(), chunkCount[0]);

                        if (chunk instanceof ActionChunk) {
                            __parentTrace.getOptions().getStreamSink().next(chunk);
                        } else if (chunk instanceof ReasonChunk) {
                            __parentTrace.getOptions().getStreamSink().next(chunk);
                        }

                        if (chunk != null && chunk.hasContent()) {
                            contentBuilder.append(chunk.getContent());
                        }
                    })
                    .doOnComplete(() -> {
                        long totalDuration = System.currentTimeMillis() - firstChunkTime[0];
                        LOG.info("[mainAgent] 流完成: type={}, sessionId={}, totalChunks={}, totalDuration={}ms",
                                name, sessionId, chunkCount[0], totalDuration);
                    })
                    .doOnError(e -> {
                        LOG.error("[mainAgent] 流错误: type={}, sessionId={}, error={}, chunksReceived={}",
                                name, sessionId, e.getMessage(), chunkCount[0]);
                    })
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .then(Mono.fromCallable(() -> contentBuilder.toString()))
                    .block(Duration.ofMillis(SUBAGENT_STREAM_TIMEOUT_MS));

            LOG.info("[mainAgent] 执行成功: type={}, sessionId={}, chunks={}, resultLength={}",
                    name, sessionId, chunkCount[0],
                    result != null ? result.length() : 0);

            return result;

        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("Timeout")) {
                LOG.error("[mainAgent] 执行超时: name={}, sessionId={}", name, sessionId);
                return "ERROR: mainAgent执行超时。\n\n" +
                        "可能原因：\n" +
                        "1. LLM API 响应过慢或无响应\n" +
                        "2. mainAgent执行的任务过于复杂\n" +
                        "3. 网络连接问题\n\n" +
                        "建议：\n" +
                        "- 简化任务描述\n" +
                        "- 检查网络连接\n" +
                        "- 查看mainAgent日志了解详情";
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取当前目标
     *
     * @return 当前目标描述
     */
    public String getCurrentGoal() {
        return goalKeeper != null ? goalKeeper.getCurrentGoal() : null;
    }

    /**
     * 获取当前目标 ID
     *
     * @return 目标 ID
     */
    public String getCurrentGoalId() {
        return goalKeeper != null ? goalKeeper.getCurrentGoalId() : null;
    }

    /**
     * 检查是否正在守护
     *
     * @return 是否正在守护
     */
    public boolean isGuardingGoal() {
        return goalKeeper != null && goalKeeper.isGuarding();
    }

    /**
     * 获取目标提醒次数
     *
     * @return 提醒次数
     */
    public int getGoalReminderCount() {
        return goalKeeper != null ? goalKeeper.getReminderCount() : 0;
    }

    /**
     * 为 Prompt 添加目标上下文
     *
     * @param prompt 原始提示词
     * @return 带目标的提示词
     */
    public Prompt enrichPromptWithGoal(Prompt prompt) {
        if (goalKeeper != null) {
            return goalKeeper.enrichPromptWithGoal(prompt);
        }
        return prompt;
    }
}
