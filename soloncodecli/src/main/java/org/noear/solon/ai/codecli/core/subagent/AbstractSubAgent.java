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
package org.noear.solon.ai.codecli.core.subagent;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.SystemPrompt;
import org.noear.solon.ai.codecli.core.event.AgentEventType;
import org.noear.solon.ai.codecli.core.memory.Memory;
import org.noear.solon.ai.codecli.core.memory.ShortTermMemory;
import org.noear.solon.ai.codecli.core.memory.LongTermMemory;
import org.noear.solon.ai.codecli.core.memory.WorkingMemory;
import org.noear.solon.ai.codecli.core.memory.ToolRecord;
import org.noear.solon.ai.codecli.core.memory.SkillRecord;
import org.noear.solon.ai.codecli.core.memory.SharedMemoryManager;
import org.noear.solon.ai.codecli.core.event.AgentEvent;
import org.noear.solon.ai.codecli.core.event.EventHandler;
import org.noear.solon.ai.codecli.core.event.EventBus;
import org.noear.solon.ai.codecli.core.message.AgentMessage;
import org.noear.solon.ai.codecli.core.message.MessageAck;
import org.noear.solon.ai.codecli.core.message.MessageChannel;
import org.noear.solon.ai.codecli.core.teams.SharedTaskList;
import org.noear.solon.ai.codecli.core.teams.TeamTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 抽象子代理实现
 *
 * @author bai
 * @since 3.9.5
 */
public abstract class AbstractSubAgent implements SubAgent {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractSubAgent.class);

    protected final SubAgentConfig config;
    protected final AgentSessionProvider sessionProvider;
    protected ReActAgent agent;

    // Agent Teams 增强功能
    protected SharedMemoryManager sharedMemoryManager;
    protected EventBus eventBus;
    protected MessageChannel messageChannel;
    protected SharedTaskList sharedTaskList;
    private String messageHandlerId;

    public AbstractSubAgent(SubAgentConfig config,
                           AgentSessionProvider sessionProvider,
                           SharedMemoryManager sharedMemoryManager,
                           EventBus eventBus,
                           MessageChannel messageChannel,
                           SharedTaskList sharedTaskList) {
        this.config = config;
        this.sessionProvider = sessionProvider;
        this.sharedMemoryManager = sharedMemoryManager;
        this.eventBus = eventBus;
        this.messageChannel = messageChannel;
        this.sharedTaskList = sharedTaskList;
    }

    @Override
    public SubAgentType getType() {
        return config.getType();
    }

    @Override
    public SubAgentConfig getConfig() {
        return config;
    }


    @Override
    public String model() {
        // 优先使用配置中的 modelName（可能来自 config.yml 或 YAML 元数据）
        if (config != null && config.getModelName() != null && !config.getModelName().isEmpty()) {
            return config.getModelName();
        }
        // 其次使用 chatModel 的模型名称
        if (config != null && config.getChatModel() != null) {
            return config.getChatModel().getName();
        }
        // 默认模型名称（表示未配置）
        return "";
    }

    /**
     * 初始化代理
     */
    protected synchronized void initAgent(ChatModel chatModel,
                                           Consumer<ReActAgent.Builder> configurator) {
        if (agent == null) {
            ReActAgent.Builder builder = ReActAgent.of(chatModel);

            // 设置系统提示词
            String systemPrompt = buildSystemPrompt();
            builder.systemPrompt(SystemPrompt.builder()
                    .instruction(systemPrompt)
                    .build());

            // 应用自定义配置
            if (configurator != null) {
                configurator.accept(builder);
            }

            this.agent = builder.build();

            // 注册消息处理器（新 API - 类型安全）
            if (messageChannel != null) {
                messageHandlerId = messageChannel.registerHandler(
                        config.getCode(),
                        this::handleMessageInternalAsyncNew
                );
                LOG.info("SubAgent '{}' 消息处理器已注册", config.getCode());
            }

            LOG.info("SubAgent '{}' 初始化完成", config.getCode());
        }
    }

    /**
     * 构建系统提示词（优先从 agents 池读取，否则使用内置提示词）
     * 解析提示词头部的 YAML 元数据并应用配置
     */
    protected String buildSystemPrompt() {
        // 1. 尝试从自定义文件读取提示词（如果存在）
        String customPrompt = readCustomPrompt();
        String rawPrompt;

        if (customPrompt != null) {
            LOG.info("SubAgent '{}' 使用自定义提示词", config.getCode());
            rawPrompt = customPrompt;
        } else {
            // 2. 使用内置提示词
            String defaultPrompt = getDefaultSystemPrompt();
            LOG.debug("SubAgent '{}' 使用内置提示词", config.getCode());
            rawPrompt = defaultPrompt;
        }

        // 3. 解析元数据并应用配置
        SubAgentMetadata.PromptWithMetadata parsed = SubAgentMetadata.parseAndClean(rawPrompt);
        SubAgentMetadata metadata = parsed.getMetadata();

        // 应用 model 配置（如果元数据中有且 config 中还没有设置）
        if (metadata.hasModel() && (config.getModelName() == null || config.getModelName().isEmpty())) {
            config.setModelName(metadata.getModel());
            LOG.info("SubAgent '{}' 从元数据应用模型配置: {}", config.getCode(), metadata.getModel());
        }

        // 返回清理后的提示词（移除 YAML 头部）
        return parsed.getPrompt();
    }

    /**
     * 获取内置系统提示词（由子类实现）
     */
    protected abstract String getDefaultSystemPrompt();

    /**
     * 导出提示词到默认目录
     */
    public void exportSystemPrompt(String workDir) {
        try {
            String promptDir = workDir +  File.separator + ".soloncode" + File.separator + "agents";
            File dir = new File(promptDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String promptFile = promptDir + File.separator + config.getCode() + ".md";
            File file = new File(promptFile);

            // 只在不存在的时才导出，避免覆盖用户自定义的提示词
            if (!file.exists()) {
                String content = getDefaultSystemPrompt();
                Files.write(Paths.get(promptFile), content.getBytes(StandardCharsets.UTF_8));
                LOG.info("SubAgent '{}' 提示词已导出到: {}", config.getCode(), promptFile);
            } else {
                LOG.debug("SubAgent '{}' 提示词文件已存在，跳过导出: {}", config.getCode(), promptFile);
            }
        } catch (Throwable e) {
            LOG.warn("SubAgent '{}' 提示词导出失败: {}", config.getCode(), e.getMessage());
        }
    }

    /**
     * 从文件系统读取自定义提示词
     */
    private String readCustomPrompt() {
        try {
            // 尝试多个位置
            String[] locations = {
                ".soloncode/agents/" + config.getCode() + ".md",  // 项目根目录
                ".soloncode/agents/" + config.getCode() + ".md",  // 相对路径
                config.getWorkDir() + "/.soloncode/agents/" + config.getCode() + ".md"  // work 目录下
            };

            for (String location : locations) {
                File file = new File(location);
                if (file.exists() && file.isFile()) {
                    byte[] bytes = Files.readAllBytes(Paths.get(location));
                    LOG.info("从 {} 读取 SubAgent '{}' 提示词", location, config.getCode());
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }

            LOG.debug("未找到 SubAgent '{}' 的自定义提示词文件", config.getCode());
        } catch (Throwable e) {
            LOG.warn("读取 SubAgent '{}' 自定义提示词失败: {}", config.getCode(), e.getMessage());
        }
        return null;
    }


    /**
     * 获取会话
     */
    protected AgentSession getSession(String sessionId) {
        return sessionProvider.getSession(sessionId);
    }


    // ========== Agent Teams 便捷方法 ==========

    /**
     * 获取或创建工作记忆
     *
     * @param taskId 任务ID
     * @return 工作记忆对象
     */
    protected WorkingMemory getWorkingMemory(String taskId) {
        if (sharedMemoryManager != null) {
            WorkingMemory memory = sharedMemoryManager.getWorking(taskId);
            if (memory == null) {
                memory = new WorkingMemory(taskId);
                memory.setCurrentAgent(config.getCode());
                sharedMemoryManager.storeWorking(memory);
            }
            return memory;
        }
        return null;
    }

    /**
     * 存储工作数据
     *
     * @param taskId 任务ID
     * @param key 键
     * @param value 值
     */
    protected void putWorkingData(String taskId, String key, Object value) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            memory.put(key, value);
        }
    }

    /**
     * 获取工作数据
     *
     * @param taskId 任务ID
     * @param key 键
     * @return 值，不存在返回 null
     */
    protected Object getWorkingData(String taskId, String key) {
        if (sharedMemoryManager != null) {
            WorkingMemory memory = sharedMemoryManager.getWorking(taskId);
            return memory != null ? memory.get(key) : null;
        }
        return null;
    }

    /**
     * 获取工作数据（带类型转换）
     *
     * @param taskId 任务ID
     * @param key 键
     * @param type 类型
     * @param <T> 泛型类型
     * @return 值，不存在或类型不匹配返回 null
     */
    protected <T> T getWorkingData(String taskId, String key, Class<T> type) {
        if (sharedMemoryManager != null) {
            WorkingMemory memory = sharedMemoryManager.getWorking(taskId);
            return memory != null ? memory.get(key, type) : null;
        }
        return null;
    }

    /**
     * 增加工作记忆步骤
     *
     * @param taskId 任务ID
     */
    protected void incrementStep(String taskId) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            memory.incrementStep();
        }
    }

    /**
     * 添加已完成步骤
     *
     * @param taskId 任务ID
     * @param step 步骤名称
     */
    protected void addCompletedStep(String taskId, String step) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            memory.addCompletedStep(step);
        }
    }

    /**
     * 完成工作记忆
     *
     * @param taskId 任务ID
     */
    protected void completeWorkingMemory(String taskId) {
        if (sharedMemoryManager != null) {
            sharedMemoryManager.completeWorking(taskId);
        }
    }

    /**
     * 设置任务描述
     *
     * @param taskId 任务ID
     * @param description 任务描述
     */
    protected void setTaskDescription(String taskId, String description) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            memory.setTaskDescription(description);
        }
    }

    /**
     * 更新工作记忆摘要
     *
     * @param taskId 任务ID
     * @param summary LLM 生成的摘要
     */
    protected void updateSummary(String taskId, String summary) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            memory.setSummary(summary);
        }
    }

    /**
     * 创建 Tool 记录
     *
     * @param taskId 任务ID
     * @param toolName Tool 名称
     * @return ToolRecord 对象
     */
    protected ToolRecord createToolRecord(String taskId, String toolName) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            return memory.createToolRecord(toolName);
        }
        return null;
    }

    /**
     * 添加 Tool 记录
     *
     * @param taskId 任务ID
     * @param record Tool 记录
     */
    protected void addToolRecord(String taskId, ToolRecord record) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            memory.addToolRecord(record);
        }
    }

    /**
     * 获取 Tool 执行统计
     *
     * @param taskId 任务ID
     * @return 统计信息字符串
     */
    protected String getToolStats(String taskId) {
        WorkingMemory memory = sharedMemoryManager != null
            ? sharedMemoryManager.getWorking(taskId)
            : null;
        if (memory != null) {
            return String.format("Tool 调用: %d 次 (成功: %d, 失败: %d), 总耗时: %dms",
                memory.getToolExecutionCount(),
                memory.getSuccessfulToolRecords().size(),
                memory.getFailedToolRecords().size(),
                memory.getTotalToolDuration()
            );
        }
        return "Tool 统计: 无数据";
    }

    /**
     * 获取 Skill 调用统计
     *
     * @param taskId 任务ID
     * @return 统计信息字符串
     */
    protected String getSkillStats(String taskId) {
        WorkingMemory memory = sharedMemoryManager != null
            ? sharedMemoryManager.getWorking(taskId)
            : null;
        if (memory != null) {
            return String.format("Skill 调用: %d 次 (成功: %d, 失败: %d), 总耗时: %dms",
                memory.getSkillExecutionCount(),
                memory.getSuccessfulSkillRecords().size(),
                memory.getFailedSkillRecords().size(),
                memory.getTotalSkillDuration()
            );
        }
        return "Skill 统计: 无数据";
    }

    /**
     * 创建 Skill 记录
     *
     * @param taskId 任务ID
     * @param skillName Skill 名称
     * @return SkillRecord 对象
     */
    protected SkillRecord createSkillRecord(String taskId, String skillName) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            return memory.createSkillRecord(skillName);
        }
        return null;
    }

    /**
     * 添加 Skill 记录
     *
     * @param taskId 任务ID
     * @param record Skill 记录
     */
    protected void addSkillRecord(String taskId, SkillRecord record) {
        WorkingMemory memory = getWorkingMemory(taskId);
        if (memory != null) {
            memory.addSkillRecord(record);
        }
    }

    /**
     * 存储短期记忆（便捷方法）
     *
     * @param context 上下文内容
     * @param taskId 关联任务ID
     */
    protected void remember(String context, String taskId) {
        if (sharedMemoryManager != null) {
            ShortTermMemory memory = new ShortTermMemory(
                    config.getCode(), context, taskId
            );
            sharedMemoryManager.store(memory);
        }
    }

    /**
     * 存储长期记忆（便捷方法）
     *
     * @param summary 摘要内容
     * @param tags 标签列表
     * @param importance 重要性评分 (0.0-1.0)
     */
    protected void rememberLong(String summary, List<String> tags, double importance) {
        if (sharedMemoryManager != null) {
            LongTermMemory memory = new LongTermMemory(
                    summary, config.getCode(), tags
            );
            memory.setImportance(importance);
            sharedMemoryManager.store(memory);
        }
    }

    /**
     * 检索相关记忆（便捷方法）
     *
     * @param query 搜索关键词
     * @param limit 最大数量
     * @return 记忆列表
     */
    protected List<Memory> recall(String query, int limit) {
        if (sharedMemoryManager != null) {
            return sharedMemoryManager.search(query, limit);
        }
        return java.util.Collections.emptyList();
    }

    /**
     * 发布事件（便捷方法，使用枚举类型）
     *
     * @param eventType 事件类型
     * @param payload 事件内容
     * @param taskId 关联任务ID
     */
    protected void publishEvent(org.noear.solon.ai.codecli.core.event.AgentEventType eventType, Object payload, String taskId) {
        if (eventBus != null) {
            org.noear.solon.ai.codecli.core.event.EventMetadata metadata =
                    org.noear.solon.ai.codecli.core.event.EventMetadata.builder()
                            .sourceAgent(config.getCode())
                            .taskId(taskId)
                            .priority(5)
                            .build();

            AgentEvent event = new AgentEvent(eventType, payload, metadata);
            eventBus.publishAsync(event);
        }
    }

    /**
     * 发布事件（便捷方法，使用自定义事件类型代码）
     *
     * @param customEventTypeCode 自定义事件类型代码
     * @param payload 事件内容
     * @param taskId 关联任务ID
     */
    protected void publishEvent(String customEventTypeCode, Object payload, String taskId) {
        if (eventBus != null) {
            org.noear.solon.ai.codecli.core.event.EventMetadata metadata =
                    org.noear.solon.ai.codecli.core.event.EventMetadata.builder()
                            .sourceAgent(config.getCode())
                            .taskId(taskId)
                            .priority(5)
                            .build();

            AgentEvent event = new AgentEvent(customEventTypeCode, payload, metadata);
            eventBus.publishAsync(event);
        }
    }

    /**
     * 订阅事件（便捷方法）
     *
     * @param eventType 事件类型
     * @param handler 事件处理器
     * @return 订阅ID
     */
    protected String subscribeEvent(AgentEventType eventType, EventHandler handler) {
        if (eventBus != null) {
            return eventBus.subscribe(eventType, handler);
        }
        return null;
    }

    /**
     * 取消事件订阅（便捷方法）
     *
     * @param subscriptionId 订阅ID
     */
    protected void unsubscribeEvent(String subscriptionId) {
        if (eventBus != null) {
            eventBus.unsubscribe(subscriptionId);
        }
    }

    /**
     * 发送点对点消息（便捷方法）
     *
     * @param to 接收者代理ID
     * @param type 消息类型
     * @param payload 消息内容
     * @param <T> 消息内容类型
     * @return CompletableFuture
     */
    protected <T> CompletableFuture<MessageAck> sendMessage(String to, String type, T payload) {
        if (messageChannel != null) {
            AgentMessage<T> message = AgentMessage.<T>of(payload)
                    .from(config.getCode())
                    .to(to)
                    .type(type)
                    .metadata("requireAck", "true")
                    .build();
            return messageChannel.send(message);
        }
        return CompletableFuture.completedFuture(
                new MessageAck("", to, false, "Message channel not available")
        );
    }

    /**
     * 广播消息（便捷方法）
     *
     * @param type 消息类型
     * @param payload 消息内容
     * @param <T> 消息内容类型
     * @return CompletableFuture
     */
    protected <T> CompletableFuture<List<MessageAck>> broadcastMessage(String type, T payload) {
        if (messageChannel != null) {
            AgentMessage<T> message = AgentMessage.<T>of(payload)
                    .from(config.getCode())
                    .to("*")
                    .type(type)
                    .build();
            return messageChannel.broadcast(message);
        }
        return CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    /**
     * 处理接收到的消息（由子类覆盖）
     *
     * @param message 泛型消息对象
     * @param <T> 消息内容类型
     * @return 处理结果
     */
    protected <T> Object handleMessageInternal(AgentMessage<T> message) {
        LOG.debug("SubAgent '{}' 收到消息: from={}, type={}",
                config.getCode(), message.getFrom(), message.getType());

        // 默认实现：返回确认
        return "ACK";
    }

    /**
     * 消息处理器桥接方法（异步）
     */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<Object> handleMessageInternalAsyncNew(AgentMessage<T> message) {
        try {
            // 调用 protected 方法
            Object result = handleMessageInternal(message);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            LOG.error("处理消息异常: from={}, type={}, error={}",
                    message.getFrom(), message.getType(), e.getMessage(), e);
            // Java 8 兼容：手动创建失败的 Future
            CompletableFuture<Object> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("消息处理失败", e));
            return failedFuture;
        }
    }

    // ========== Agent Teams 任务认领便捷方法 ==========

    /**
     * 添加任务到共享任务列表
     *
     * @param task 任务对象
     * @return 异步结果
     */
    protected CompletableFuture<TeamTask> addTask(TeamTask task) {
        if (sharedTaskList != null) {
            return sharedTaskList.addTask(task);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 批量添加任务
     *
     * @param tasks 任务列表
     * @return 异步结果
     */
    protected CompletableFuture<List<TeamTask>> addTasks(List<TeamTask> tasks) {
        if (sharedTaskList != null) {
            return sharedTaskList.addTasks(tasks);
        }
        return CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    /**
     * 认领指定任务
     *
     * @param taskId 任务ID
     * @return 是否认领成功
     */
    protected CompletableFuture<Boolean> claimTask(String taskId) {
        if (sharedTaskList != null) {
            return sharedTaskList.claimTask(taskId, config.getCode());
        }
        return CompletableFuture.completedFuture(false);
    }

    /**
     * 智能认领（自动选择最佳任务）
     *
     * @return 认领的任务，无任务可认领返回 null
     */
    protected CompletableFuture<TeamTask> smartClaim() {
        if (sharedTaskList != null) {
            return sharedTaskList.smartClaim(config.getCode());
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 释放任务
     *
     * @param taskId 任务ID
     * @return 是否释放成功
     */
    protected boolean releaseTask(String taskId) {
        if (sharedTaskList != null) {
            return sharedTaskList.releaseTask(taskId, config.getCode());
        }
        return false;
    }

    /**
     * 完成任务
     *
     * @param taskId 任务ID
     * @param result 执行结果
     * @return 是否完成成功
     */
    protected boolean completeTask(String taskId, Object result) {
        if (sharedTaskList != null) {
            return sharedTaskList.completeTask(taskId, result);
        }
        return false;
    }

    /**
     * 失败任务
     *
     * @param taskId 任务ID
     * @param errorMessage 错误信息
     * @return 是否标记成功
     */
    protected boolean failTask(String taskId, String errorMessage) {
        if (sharedTaskList != null) {
            return sharedTaskList.failTask(taskId, errorMessage);
        }
        return false;
    }

    /**
     * 获取任务
     *
     * @param taskId 任务ID
     * @return 任务对象
     */
    protected TeamTask getTask(String taskId) {
        if (sharedTaskList != null) {
            return sharedTaskList.getTask(taskId);
        }
        return null;
    }

    /**
     * 获取当前代理的任务列表
     *
     * @return 任务列表
     */
    protected List<TeamTask> getMyTasks() {
        if (sharedTaskList != null) {
            return sharedTaskList.getAgentTasks(config.getCode());
        }
        return java.util.Collections.emptyList();
    }

    /**
     * 获取待认领任务
     *
     * @return 任务列表
     */
    protected List<TeamTask> getPendingTasks() {
        if (sharedTaskList != null) {
            return sharedTaskList.getPendingTasks();
        }
        return java.util.Collections.emptyList();
    }

    /**
     * 获取可认领任务（考虑依赖关系）
     *
     * @return 任务列表
     */
    protected List<TeamTask> getClaimableTasks() {
        if (sharedTaskList != null) {
            return sharedTaskList.getClaimableTasks();
        }
        return java.util.Collections.emptyList();
    }

    /**
     * 获取当前代理负载数
     *
     * @return 负载数（进行中的任务数）
     */
    protected int getMyLoad() {
        if (sharedTaskList != null) {
            return sharedTaskList.getAgentLoad(config.getCode());
        }
        return 0;
    }

    /**
     * 获取所有 Agent 负载
     *
     * @return Agent ID -> 负载数
     */
    protected Map<String, Integer> getAllAgentLoads() {
        if (sharedTaskList != null) {
            return sharedTaskList.getAllAgentLoads();
        }
        return java.util.Collections.emptyMap();
    }

    /**
     * 获取任务统计
     *
     * @return 统计信息
     */
    protected SharedTaskList.TaskStatistics getTaskStatistics() {
        if (sharedTaskList != null) {
            return sharedTaskList.getStatistics();
        }
        return null;
    }

    /**
     * 清理资源
     */
    public void destroy() {
        // 注销消息处理器
        if (messageChannel != null && messageHandlerId != null) {
            messageChannel.unregisterHandler(config.getCode(), messageHandlerId);
            LOG.info("SubAgent '{}' 消息处理器已注销", config.getCode());
        }
    }

    // ========== Agent 接口实现 ==========

    /**
     * 执行任务（同步）
     *
     * @param prompt 任务提示
     * @return 执行结果
     */
    @Override
    public AgentResponse execute(Prompt prompt) throws Throwable {
        if (agent == null) {
            throw new IllegalStateException("SubAgent 尚未初始化");
        }

        String sessionId = "subagent_" + config.getCode();
        AgentSession session = getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .call();
    }

    /**
     * 执行任务（流式）
     *
     * @param prompt 任务提示
     * @return 流式结果
     */
    @Override
    public reactor.core.publisher.Flux<org.noear.solon.ai.agent.AgentChunk> stream(Prompt prompt) {
        if (agent == null) {
            return reactor.core.publisher.Flux.error(new IllegalStateException("SubAgent 尚未初始化"));
        }

        String sessionId = "subagent_" + config.getCode();
        AgentSession session = getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .stream();
    }


}
