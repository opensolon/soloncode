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
package org.noear.solon.codecli.core.memory;

import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Agent Teams 共享记忆技能
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
public class MemorySkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(MemorySkill.class);

    private final MemoryManager memoryManager;

    public MemorySkill(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }


    @Override
    public String description() {
        return "核心存储与事件工具：支持分层记忆(TTL控制)、工作记忆状态管理及系统事件发布。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 记忆与状态管理规范\n");
        sb.append("负责维护任务的生命周期状态：\n");
        sb.append("1. **状态同步**：每完成一个关键步骤，必须调用 `working_memory_set` 更新 `step` 和 `status`。\n");
        sb.append("2. **事件通知**：开始新任务发 `TASK_CREATED`，遇到不可逆错误发 `TASK_FAILED`。\n");
        sb.append("3. **存储策略**：\n");
        sb.append("   - 临时变量/中间逻辑 -> `memory_store_short` (TTL: 3600)\n");
        sb.append("   - 任务阶段产出/用户偏好 -> `memory_store_long` (TTL: 604800)\n");
        sb.append("   - 核心规约/架构定义 -> `memory_store_knowledge` (永久)\n");
        return sb.toString();
    }


    /**
     * 存储短期记忆
     */
    @ToolMapping(name = "memory_store_short",
                 description = "[底层API] 存储短期记忆（会话级别，TTL 1小时）。注意：推荐使用 memory_store() 自动分类。")
    public String memoryStoreShort(
            @Param(name = "key", description = "记忆键") String key,
            @Param(name = "value", description = "记忆值") String value,
            @Param(name = "ttl", description = "过期时间（秒），默认3600") Integer ttl) {
        try {
            int actualTtl = ttl != null && ttl > 0 ? ttl : 3600;
            memoryManager.putShortTerm(key, value, actualTtl);
            LOG.debug("存储短期记忆: key={}, ttl={}", key, actualTtl);
            return "[OK] 短期记忆已存储: " + key;
        } catch (Throwable e) {
            LOG.error("存储短期记忆失败", e);
            return "[ERROR] 存储失败: " + e.getMessage();
        }
    }

    /**
     * 存储长期记忆
     */
    @ToolMapping(name = "memory_store_long",
                 description = "[底层API] 存储长期记忆（跨会话，TTL 7天）。注意：推荐使用 memory_store() 自动分类。")
    public String memoryStoreLong(
            @Param(name = "key", description = "记忆键") String key,
            @Param(name = "value", description = "记忆值") String value,
            @Param(name = "ttl", description = "过期时间（秒），默认604800") Integer ttl) {
        try {
            int actualTtl = ttl != null && ttl > 0 ? ttl : 604800;
            memoryManager.putLongTerm(key, value, actualTtl);
            LOG.debug("存储长期记忆: key={}, ttl={}", key, actualTtl);
            return "[OK] 长期记忆已存储: " + key;
        } catch (Throwable e) {
            LOG.error("存储长期记忆失败", e);
            return "[ERROR] 存储失败: " + e.getMessage();
        }
    }

    /**
     * 存储知识记忆
     */
    @ToolMapping(name = "memory_store_knowledge",
                 description = "[底层API] 存储知识记忆（永久保存）。注意：推荐使用 memory_store() 自动分类。")
    public String memoryStoreKnowledge(
            @Param(name = "key", description = "记忆键") String key,
            @Param(name = "value", description = "记忆值") String value) {
        try {
            memoryManager.putKnowledge(key, value);
            LOG.debug("存储知识记忆: key={}", key);
            return "[OK] 知识记忆已存储: " + key;
        } catch (Throwable e) {
            LOG.error("存储知识记忆失败", e);
            return "[ERROR] 存储失败: " + e.getMessage();
        }
    }

    /**
     * 检索记忆
     */
    @ToolMapping(name = "memory_retrieve",
                 description = "[底层API] 根据键精确检索记忆。注意：推荐使用 memory_recall() 智能检索。")
    public String memoryRetrieve(
            @Param(name = "key", description = "记忆键") String key) {
        try {
            // 按优先级查找：短期 -> 长期 -> 知识
            String value = memoryManager.get(key);
            if (value != null) {
                return "[OK] 记忆: " + value;
            }

            return "[WARN] 未找到记忆: " + key;
        } catch (Throwable e) {
            LOG.error("检索记忆失败", e);
            return "[ERROR] 检索失败: " + e.getMessage();
        }
    }

    /**
     * 搜索记忆
     */
    @ToolMapping(name = "memory_search",
                 description = "[底层API] 模糊搜索记忆（支持关键词匹配）。注意：推荐使用 memory_recall() 智能检索。")
    public String memorySearch(
            @Param(name = "query", description = "搜索查询") String query,
            @Param(name = "limit", description = "返回结果数量限制，默认10") Integer limit) {
        try {
            int actualLimit = limit != null && limit > 0 ? limit : 10;
            List<Memory> results = memoryManager.search(query, actualLimit);

            if (results.isEmpty()) {
                return "[WARN] 未找到相关记忆";
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
        } catch (Throwable e) {
            LOG.error("搜索记忆失败", e);
            return "[ERROR] 搜索失败: " + e.getMessage();
        }
    }


    /**
     * 设置工作记忆
     */
    @ToolMapping(name = "working_memory_set",
                 description = "[底层API] 设置工作记忆字段（用于存储当前任务状态、步骤等结构化数据）。直接操作 WorkingMemory。")
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
                        return "[ERROR] 步骤必须是数字: " + value;
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
            return "[OK] 工作记忆已设置: " + field + " = " + value;
        } catch (NumberFormatException e) {
            return "[ERROR] 步骤必须是数字: " + value;
        } catch (Throwable e) {
            LOG.error("设置工作记忆失败", e);
            return "[ERROR] 设置失败: " + e.getMessage();
        }
    }

    /**
     * 获取工作记忆
     */
    @ToolMapping(name = "working_memory_get",
                 description = "[底层API] 获取工作记忆（查看当前任务状态、步骤等）。直接读取 WorkingMemory。")
    public String workingMemoryGet(
            @Param(name = "taskId", description = "任务ID，默认为'main-agent'") String taskId
    ) {
        try {
            // 如果没有提供 taskId，使用默认值
            String actualTaskId = (taskId != null && !taskId.isEmpty()) ? taskId : "main-agent";

            WorkingMemory workingMemory = memoryManager.getWorking(actualTaskId);

            if (workingMemory == null) {
                return "[WARN] 未找到工作记忆: " + actualTaskId;
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
        } catch (Throwable e) {
            LOG.error("获取工作记忆失败", e);
            return "[ERROR] 获取失败: " + e.getMessage();
        }
    }
}
