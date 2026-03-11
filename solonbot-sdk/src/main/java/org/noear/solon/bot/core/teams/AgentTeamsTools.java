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

import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.event.AgentEvent;
import org.noear.solon.bot.core.event.AgentEventType;
import org.noear.solon.bot.core.event.EventBus;
import org.noear.solon.bot.core.memory.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Agent Teams 工具集
 *
 * 提供 MainAgent 内部使用的核心工具：
 * - 记忆存储和读取
 * - 事件发布
 *
 * 注意：代理间通信工具（send_message、list_agents）在 AgentTeamsSkill 中提供
 *
 * @author bai
 * @since 3.9.5
 */
public class AgentTeamsTools extends AbsSkill {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTeamsTools.class);

    private final SharedMemoryManager memoryManager;
    private final EventBus eventBus;

    public AgentTeamsTools(SharedMemoryManager memoryManager,
                          EventBus eventBus) {
        this.memoryManager = memoryManager;
        this.eventBus = eventBus;
    }

    @Override
    public String description() {
        return "Agent Teams 工具集：提供记忆存储、事件发布、消息传递等功能";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Agent Teams 工具集\n\n");
        sb.append("你可以使用以下工具来协调团队协作：\n\n");
        sb.append("### 记忆管理\n");
        sb.append("- `memory_store()`: 存储信息到共享记忆\n");
        sb.append("- `memory_retrieve()`: 从共享记忆检索信息\n");
        sb.append("- `memory_search()`: 搜索共享记忆\n");
        sb.append("- `working_memory_set()`: 设置工作记忆\n");
        sb.append("- `working_memory_get()`: 获取工作记忆\n\n");
        sb.append("### 事件和消息\n");
        sb.append("- `publish_event()`: 发布团队事件\n");
        sb.append("- `send_message()`: 发送消息给其他代理\n\n");
        sb.append("### 任务状态\n");
        sb.append("- `get_task_statistics()`: 获取任务统计信息\n");
        return sb.toString();
    }

    // ==================== 记忆存储工具 ====================

    /**
     * 存储短期记忆
     */
    @ToolMapping(name = "memory_store_short",
                 description = "存储短期记忆（会话级别，TTL 1小时）。用于临时信息。")
    public String memoryStoreShort(
            @Param(name = "key", description = "记忆键") String key,
            @Param(name = "value", description = "记忆值") String value,
            @Param(name = "ttl", description = "过期时间（秒），默认3600") Integer ttl) {
        try {
            int actualTtl = ttl != null && ttl > 0 ? ttl : 3600;
            memoryManager.putShortTerm(key, value, actualTtl);
            LOG.debug("存储短期记忆: key={}, ttl={}", key, actualTtl);
            return "✅ 短期记忆已存储: " + key;
        } catch (Exception e) {
            LOG.error("存储短期记忆失败", e);
            return "❌ 存储失败: " + e.getMessage();
        }
    }

    /**
     * 存储长期记忆
     */
    @ToolMapping(name = "memory_store_long",
                 description = "存储长期记忆（跨会话，TTL 7天）。用于重要信息。")
    public String memoryStoreLong(
            @Param(name = "key", description = "记忆键") String key,
            @Param(name = "value", description = "记忆值") String value,
            @Param(name = "ttl", description = "过期时间（秒），默认604800") Integer ttl) {
        try {
            int actualTtl = ttl != null && ttl > 0 ? ttl : 604800;
            memoryManager.putLongTerm(key, value, actualTtl);
            LOG.debug("存储长期记忆: key={}, ttl={}", key, actualTtl);
            return "✅ 长期记忆已存储: " + key;
        } catch (Exception e) {
            LOG.error("存储长期记忆失败", e);
            return "❌ 存储失败: " + e.getMessage();
        }
    }

    /**
     * 存储知识记忆
     */
    @ToolMapping(name = "memory_store_knowledge",
                 description = "存储知识记忆（永久保存）。用于最佳实践、经验教训等。")
    public String memoryStoreKnowledge(
            @Param(name = "key", description = "记忆键") String key,
            @Param(name = "value", description = "记忆值") String value) {
        try {
            memoryManager.putKnowledge(key, value);
            LOG.debug("存储知识记忆: key={}", key);
            return "✅ 知识记忆已存储: " + key;
        } catch (Exception e) {
            LOG.error("存储知识记忆失败", e);
            return "❌ 存储失败: " + e.getMessage();
        }
    }

    /**
     * 检索记忆
     */
    @ToolMapping(name = "memory_retrieve",
                 description = "根据键检索记忆（自动从短期、长期、知识记忆中查找）")
    public String memoryRetrieve(
            @Param(name = "key", description = "记忆键") String key) {
        try {
            // 按优先级查找：短期 -> 长期 -> 知识
            String value = memoryManager.get(key);
            if (value != null) {
                return "✅ 记忆: " + value;
            }

            return "⚠️ 未找到记忆: " + key;
        } catch (Exception e) {
            LOG.error("检索记忆失败", e);
            return "❌ 检索失败: " + e.getMessage();
        }
    }

    /**
     * 搜索记忆
     */
    @ToolMapping(name = "memory_search",
                 description = "搜索记忆（支持模糊匹配，返回相关记忆列表）")
    public String memorySearch(
            @Param(name = "query", description = "搜索查询") String query,
            @Param(name = "limit", description = "返回结果数量限制，默认10") Integer limit) {
        try {
            int actualLimit = limit != null && limit > 0 ? limit : 10;
            List<Memory> results = memoryManager.search(query, actualLimit);

            if (results.isEmpty()) {
                return "⚠️ 未找到相关记忆";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条相关记忆:\n\n");

            for (int i = 0; i < results.size(); i++) {
                Memory mem = results.get(i);
                sb.append(i + 1).append(". ");

                if (mem instanceof ShortTermMemory) {
                    sb.append("[短期] ");
                    sb.append(((ShortTermMemory) mem).getContext());
                } else if (mem instanceof LongTermMemory) {
                    sb.append("[长期] ");
                    sb.append(((LongTermMemory) mem).getSummary());
                } else if (mem instanceof KnowledgeMemory) {
                    sb.append("[知识] ");
                    sb.append(((KnowledgeMemory) mem).getContent());
                } else {
                    sb.append("[其他] ");
                    sb.append(mem.getId());
                }

                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("搜索记忆失败", e);
            return "❌ 搜索失败: " + e.getMessage();
        }
    }

    // ==================== 工作记忆工具 ====================

    /**
     * 设置工作记忆
     */
    @ToolMapping(name = "working_memory_set",
                 description = "设置工作记忆（用于存储当前任务状态、步骤等结构化数据）")
    public String workingMemorySet(
            @Param(name = "field", description = "字段名称（taskDescription/status/step/currentAgent）") String field,
            @Param(name = "value", description = "字段值") String value) {
        try {
            // 使用默认的 taskId "main-agent"
            String taskId = "main-agent";
            WorkingMemory workingMemory = memoryManager.getWorking(taskId);

            // 如果不存在，创建一个新的
            if (workingMemory == null) {
                workingMemory = new WorkingMemory(taskId);
            }

            switch (field.toLowerCase()) {
                case "taskdescription":
                case "currenttask":
                    workingMemory.setTaskDescription(value);
                    break;
                case "status":
                    workingMemory.setStatus(value);
                    break;
                case "step":
                    try {
                        workingMemory.setStep(Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        return "❌ 步骤必须是数字: " + value;
                    }
                    break;
                case "currentagent":
                    workingMemory.setCurrentAgent(value);
                    break;
                default:
                    // 存储到 data 字段中
                    if (workingMemory.getData() == null) {
                        workingMemory.setData(new java.util.concurrent.ConcurrentHashMap<>());
                    }
                    workingMemory.getData().put(field, value);
            }

            // 保存更新后的工作记忆
            memoryManager.storeWorking(workingMemory);

            LOG.debug("设置工作记忆: {}={}", field, value);
            return "✅ 工作记忆已设置: " + field + " = " + value;
        } catch (NumberFormatException e) {
            return "❌ 步骤必须是数字: " + value;
        } catch (Exception e) {
            LOG.error("设置工作记忆失败", e);
            return "❌ 设置失败: " + e.getMessage();
        }
    }

    /**
     * 获取工作记忆
     */
    @ToolMapping(name = "working_memory_get",
                 description = "获取工作记忆（查看当前任务状态、步骤等）")
    public String workingMemoryGet(
            @Param(name = "taskId", description = "任务ID，默认为'main-agent'") String taskId
    ) {
        try {
            // 如果没有提供 taskId，使用默认值
            String actualTaskId = (taskId != null && !taskId.isEmpty()) ? taskId : "main-agent";

            WorkingMemory workingMemory = memoryManager.getWorking(actualTaskId);

            if (workingMemory == null) {
                return "⚠️ 未找到工作记忆: " + actualTaskId;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 工作记忆\n\n");

            if (workingMemory.getTaskDescription() != null) {
                sb.append("**当前任务**: ").append(workingMemory.getTaskDescription()).append("\n");
            }
            if (workingMemory.getStatus() != null) {
                sb.append("**状态**: ").append(workingMemory.getStatus()).append("\n");
            }
            sb.append("**步骤**: ").append(workingMemory.getStep()).append("\n");
            if (workingMemory.getCurrentAgent() != null) {
                sb.append("**当前代理**: ").append(workingMemory.getCurrentAgent()).append("\n");
            }
            if (workingMemory.getSummary() != null) {
                sb.append("**摘要**: ").append(workingMemory.getSummary()).append("\n");
            }

            // 其他自定义数据字段
            if (workingMemory.getData() != null && !workingMemory.getData().isEmpty()) {
                sb.append("\n**其他数据**:\n");
                workingMemory.getData().forEach((key, value) -> {
                    sb.append("  - ").append(key).append(": ").append(value).append("\n");
                });
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.error("获取工作记忆失败", e);
            return "❌ 获取失败: " + e.getMessage();
        }
    }

    // ==================== 事件工具 ====================

    /**
     * 发布事件
     */
    @ToolMapping(name = "publish_event",
                 description = "发布团队事件（用于通知其他代理任务状态变化）")
    public String publishEvent(
            @Param(name = "eventType", description = "事件类型（TASK_CREATED, TASK_COMPLETED, TASK_FAILED, MESSAGE_RECEIVED等）") String eventType,
            @Param(name = "data", description = "事件数据（JSON格式或文本）") String data) {
        try {
            AgentEventType type = AgentEventType.valueOf(eventType.toUpperCase());
            AgentEvent event = new AgentEvent(type, data, null);
            eventBus.publish(event);

            LOG.debug("发布事件: type={}, data={}", type, data);
            return "✅ 事件已发布: " + type;
        } catch (IllegalArgumentException e) {
            return "❌ 无效的事件类型: " + eventType;
        }
    }
}
