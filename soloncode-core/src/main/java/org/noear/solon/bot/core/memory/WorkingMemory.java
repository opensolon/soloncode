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

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工作记忆（Working Memory）
 *
 * 用于 Agent 执行任务时临时存储当前状态：
 * - 当前任务描述
 * - LLM 生成的摘要
 * - Tool/Skill 运行记录（统一）
 * - 中间结果和临时变量
 * - 任务进度和步骤
 *
 * 特点：
 * - 极短 TTL (默认10分钟)
 * - 仅内存存储（不持久化到文件）
 * - 按任务ID隔离
 * - 支持快速读写
 * - 任务完成/超时自动清理
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
@Setter
public class WorkingMemory extends Memory {
    private String taskId;                       // 关联任务ID
    private String taskDescription;              // 当前任务描述
    private String summary;                      // LLM 生成的摘要
//    private List<ActionRecord> toolRecords;        // Tool/Skill 运行记录（统一）
    private Map<String, Object> data;            // 其他工作数据（键值对）
    private int step;                            // 当前步骤
    private String status;                       // 状态（running/completed/failed）
    private String currentAgent;                  // 当前执行的Agent
    private List<String> completedSteps;         // 已完成步骤
    private long lastAccessTime;                 // 最后访问时间

    /**
     * 构造函数（使用默认TTL: 10分钟）
     *
     * @param taskId 关联任务ID
     */
    public WorkingMemory(String taskId) {
        this(taskId, 600_000L); // 默认10分钟
    }

    /**
     * 构造函数（自定义TTL）
     *
     * @param taskId 关联任务ID
     * @param ttl TTL（毫秒）
     */
    public WorkingMemory(String taskId, long ttl) {
        super(MemoryType.WORKING, ttl);
        this.taskId = taskId;
        this.taskDescription = null;
        this.summary = null;
//        this.toolRecords = new CopyOnWriteArrayList<>();
        this.data = new ConcurrentHashMap<>();
        this.step = 0;
        this.status = "running";
        this.completedSteps = new CopyOnWriteArrayList<>();
        this.lastAccessTime = System.currentTimeMillis();
    }


    public void setTaskDescription(String taskDescription) {
        this.taskDescription = taskDescription;
        lastAccessTime = System.currentTimeMillis();
    }


    public void setSummary(String summary) {
        this.summary = summary;
        lastAccessTime = System.currentTimeMillis();
    }



//    public void setActionRecords(List<ActionRecord> toolRecords) {
//        this.toolRecords = toolRecords != null
//            ? new CopyOnWriteArrayList<>(toolRecords)
//            : new CopyOnWriteArrayList<>();
//        lastAccessTime = System.currentTimeMillis();
//    }

    /**
     * 获取所有 Skill 类型的记录（向后兼容）
     *
     * @return Skill 类型的记录列表
     */
//    public List<ActionRecord> getSkillRecords() {
//        return toolRecords.stream()
//                .filter(ActionRecord::isSkill)
//                .collect(Collectors.toList());
//    }

    /**
     * 设置 Skill 记录（向后兼容）
     * 注意：此方法会移除所有现有的 skill 类型记录，并添加新记录
     *
//     * @param skillRecords Skill 记录列表（现在使用 ActionRecord）
     */
//    public void setSkillRecords(List<ActionRecord> skillRecords) {
//        // 移除所有现有的 skill 记录
//        toolRecords.removeIf(r -> "skill".equals(r.getActionType()));
//
//        // 添加新的 skill 记录
//        if (skillRecords != null) {
//            for (ActionRecord record : skillRecords) {
//                record.setActionType("skill");
//                toolRecords.add(record);
//            }
//        }
//        lastAccessTime = System.currentTimeMillis();
//    }



    public void setData(Map<String, Object> data) {
        this.data = data != null ? data : new ConcurrentHashMap<>();
    }


    public void setCompletedSteps(List<String> completedSteps) {
        this.completedSteps = completedSteps != null
            ? new CopyOnWriteArrayList<>(completedSteps)
            : new CopyOnWriteArrayList<>();
    }


    /**
     * 存储工作数据
     *
     * @param key 键
     * @param value 值
     */
    public void put(String key, Object value) {
        data.put(key, value);
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 获取工作数据
     *
     * @param key 键
     * @return 值，不存在返回 null
     */
    public Object get(String key) {
        lastAccessTime = System.currentTimeMillis();
        return data.get(key);
    }

    /**
     * 获取工作数据（带类型转换）
     *
     * @param key 键
     * @param type 类型
     * @param <T> 泛型类型
     * @return 值，不存在或类型不匹配返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        lastAccessTime = System.currentTimeMillis();
        Object value = data.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * 获取工作数据（带默认值）
     *
     * @param key 键
     * @param defaultValue 默认值
     * @return 值，不存在返回默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        lastAccessTime = System.currentTimeMillis();
        Object value = data.get(key);
        if (value != null) {
            return (T) value;
        }
        return defaultValue;
    }

    /**
     * 移除工作数据
     *
     * @param key 键
     * @return 被移除的值
     */
    public Object remove(String key) {
        lastAccessTime = System.currentTimeMillis();
        return data.remove(key);
    }

    /**
     * 检查是否包含键
     *
     * @param key 键
     * @return 是否包含
     */
    public boolean containsKey(String key) {
        lastAccessTime = System.currentTimeMillis();
        return data.containsKey(key);
    }

    /**
     * 获取数据大小
     *
     * @return 数据条目数
     */
    public int size() {
        return data.size();
    }

    /**
     * 增加步骤
     */
    public void incrementStep() {
        this.step++;
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 设置步骤
     *
     * @param step 步骤号
     */
    public void setStepAndUpdate(int step) {
        this.step = step;
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 添加已完成步骤
     *
     * @param step 步骤名称
     */
    public void addCompletedStep(String step) {
        this.completedSteps.add(step);
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 检查步骤是否已完成
     *
     * @param step 步骤名称
     * @return 是否已完成
     */
    public boolean isStepCompleted(String step) {
        lastAccessTime = System.currentTimeMillis();
        return completedSteps.contains(step);
    }

    /**
     * 标记为完成
     */
    public void complete() {
        this.status = "completed";
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 标记为失败
     */
    public void fail() {
        this.status = "failed";
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 检查是否正在运行
     *
     * @return 是否正在运行
     */
    public boolean isRunning() {
        return "running".equals(status);
    }

    /**
     * 检查是否已完成
     *
     * @return 是否已完成
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /**
     * 检查是否失败
     *
     * @return 是否失败
     */
    public boolean isFailed() {
        return "failed".equals(status);
    }

    /**
     * 检查是否空闲（超过指定时间未访问）
     *
     * @param idleTimeout 空闲超时时间（毫秒）
     * @return 是否空闲
     */
    public boolean isIdle(long idleTimeout) {
        long idleTime = System.currentTimeMillis() - lastAccessTime;
        return idleTime > idleTimeout;
    }

    /**
     * 清空数据
     */
    public void clear() {
        data.clear();
//        toolRecords.clear();
        summary = null;
        taskDescription = null;
        step = 0;
        status = "running";
        completedSteps.clear();
        lastAccessTime = System.currentTimeMillis();
    }


    /**
     * 添加 Tool 记录
     *
     * @param record Tool 记录
     */
//    public void addActionRecord(ActionRecord record) {
//        toolRecords.add(record);
//        lastAccessTime = System.currentTimeMillis();
//    }

    /**
     * 创建并添加 Tool 记录
     *
     * @param toolName Tool 名称
     * @return 新创建的 ActionRecord
     */
//    public ActionRecord createActionRecord(String toolName) {
//        ActionRecord record = ActionRecord.forTool(toolName, currentAgent);
//        toolRecords.add(record);
//        lastAccessTime = System.currentTimeMillis();
//        return record;
//    }

    /**
     * 添加 Tool 记录（向后兼容别名）
     *
     * @param record Tool 记录
     */
//    public void addToolRecord(ActionRecord record) {
//        addActionRecord(record);
//    }

    /**
     * 创建并添加 Tool 记录（向后兼容别名）
     *
     * @param toolName Tool 名称
     * @return 新创建的 ActionRecord
     */
//    public ActionRecord createToolRecord(String toolName) {
//        return createActionRecord(toolName);
//    }

    /**
     * 获取所有成功的 Tool 记录
     *
     * @return 成功的记录列表
     */
//    public List<ActionRecord> getSuccessfulActionRecords() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(ActionRecord::isSuccess)
//                .collect(Collectors.toList());
//    }

    /**
     * 获取所有失败的 Tool 记录
     *
     * @return 失败的记录列表
     */
//    public List<ActionRecord> getFailedActionRecords() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(r -> !r.isSuccess())
//                .collect(Collectors.toList());
//    }

//    /**
//     * 获取指定 Tool 的记录
//     *
//     * @param toolName Tool 名称
//     * @return 该 Tool 的所有记录
//     */
//    public List<ActionRecord> getActionRecordsByName(String toolName) {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(r -> toolName.equals(r.getToolName()))
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 获取 Tool 执行总次数
//     *
//     * @return 总次数
//     */
//    public int getToolExecutionCount() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.size();
//    }
//
//    /**
//     * 获取 Tool 执行总耗时
//     *
//     * @return 总耗时（毫秒）
//     */
//    public long getTotalToolDuration() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .mapToLong(ActionRecord::getDuration)
//                .sum();
//    }
//
//    // ========== Skill 记录便捷方法（使用 ActionRecord，向后兼容）==========
//
//    /**
//     * 添加 Skill 记录
//     *
//     * @param record Skill 记录（使用 ActionRecord，类型自动设为 "skill"）
//     */
//    public void addSkillRecord(ActionRecord record) {
//        record.setActionType("skill");
//        toolRecords.add(record);
//        lastAccessTime = System.currentTimeMillis();
//    }
//
//    /**
//     * 创建并添加 Skill 记录
//     *
//     * @param skillName Skill 名称
//     * @return 新创建的 ActionRecord（类型为 "skill"）
//     */
//    public ActionRecord createSkillRecord(String skillName) {
//        ActionRecord record = ActionRecord.forSkill(skillName, currentAgent);
//        toolRecords.add(record);
//        lastAccessTime = System.currentTimeMillis();
//        return record;
//    }
//
//    /**
//     * 获取所有成功的 Skill 记录
//     *
//     * @return 成功的记录列表
//     */
//    public List<ActionRecord> getSuccessfulSkillRecords() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(r -> r.isSkill() && r.isSuccess())
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 获取所有失败的 Skill 记录
//     *
//     * @return 失败的记录列表
//     */
//    public List<ActionRecord> getFailedSkillRecords() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(r -> r.isSkill() && !r.isSuccess())
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 获取指定 Skill 的记录
//     *
//     * @param skillName Skill 名称
//     * @return 该 Skill 的所有记录
//     */
//    public List<ActionRecord> getSkillRecordsByName(String skillName) {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(r -> r.isSkill() && skillName.equals(r.getSkillName()))
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 获取 Skill 调用总次数
//     *
//     * @return 总次数
//     */
//    public int getSkillExecutionCount() {
//        lastAccessTime = System.currentTimeMillis();
//        return (int) toolRecords.stream()
//                .filter(ActionRecord::isSkill)
//                .count();
//    }
//
//    /**
//     * 获取 Skill 执行总耗时
//     *
//     * @return 总耗时（毫秒）
//     */
//    public long getTotalSkillDuration() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(ActionRecord::isSkill)
//                .mapToLong(ActionRecord::getDuration)
//                .sum();
//    }
//
//    // ========== 统一的记录查询方法 ==========
//
//    /**
//     * 获取所有记录（Tool + Skill）
//     *
//     * @return 所有记录列表
//     */
//    public List<ActionRecord> getAllRecords() {
//        lastAccessTime = System.currentTimeMillis();
//        return new CopyOnWriteArrayList<>(toolRecords);
//    }
//
//    /**
//     * 获取指定类型的记录
//     *
//     * @param toolType 类型："tool" 或 "skill"
//     * @return 该类型的记录列表
//     */
//    public List<ActionRecord> getRecordsByType(String toolType) {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(r -> toolType.equals(r.getActionType()))
//                .collect(Collectors.toList());
//    }
//
//    // ========== MCP 工具记录便捷方法 ==========
//
//    /**
//     * 创建并添加 MCP 工具记录
//     *
//     * @param toolName MCP 工具名称
//     * @param mcpServer MCP 服务器名称
//     * @return 新创建的 ActionRecord（类型为 "mcp"）
//     */
//    public ActionRecord createMcpRecord(String toolName, String mcpServer) {
//        ActionRecord record = ActionRecord.forMcp(toolName, mcpServer, currentAgent);
//        toolRecords.add(record);
//        lastAccessTime = System.currentTimeMillis();
//        return record;
//    }
//
//    /**
//     * 获取所有成功的 MCP 记录
//     *
//     * @return 成功的记录列表
//     */
//    public List<ActionRecord> getSuccessfulMcpRecords() {
//        lastAccessTime = System.currentTimeMillis();
//        return toolRecords.stream()
//                .filter(r -> r.isMcp() && r.isSuccess())
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 获取 MCP 执行总次数
//     *
//     * @return 总次数
//     */
//    public int getMcpExecutionCount() {
//        lastAccessTime = System.currentTimeMillis();
//        return (int) toolRecords.stream()
//                .filter(ActionRecord::isMcp)
//                .count();
//    }
//
//    // ========== 自定义工具记录便捷方法 ==========
//
//    /**
//     * 创建并添加自定义工具记录
//     *
//     * @param toolName 工具名称
//     * @param customType 自定义类型
//     * @return 新创建的 ActionRecord
//     */
//    public ActionRecord createCustomRecord(String toolName, String customType) {
//        ActionRecord record = ActionRecord.forCustom(toolName, customType, currentAgent);
//        toolRecords.add(record);
//        lastAccessTime = System.currentTimeMillis();
//        return record;
//    }

    @Override
    public String toString() {


        return "WorkingMemory{" +
                "id='" + id + '\'' +
                ", taskId='" + taskId + '\'' +
                ", taskDescription='" + (taskDescription != null ? taskDescription.substring(0, Math.min(30, taskDescription.length())) + "..." : "null") + '\'' +
                ", summary='" + (summary != null ? summary.substring(0, Math.min(30, summary.length())) + "..." : "null") + '\'' +
                ", step=" + step +
                ", status='" + status + '\'' +
                ", currentAgent='" + currentAgent + '\'' +
                ", dataSize=" + data.size() +
                ", completedSteps=" + completedSteps.size() +
                ", lastAccessTime=" + lastAccessTime +
                '}';
    }
}
