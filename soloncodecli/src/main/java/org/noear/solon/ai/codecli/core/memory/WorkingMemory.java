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
 * - Tool 运行记录
 * - Skill 调用记录
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
public class WorkingMemory extends Memory {
    private String taskId;                       // 关联任务ID
    private String taskDescription;              // 当前任务描述
    private String summary;                      // LLM 生成的摘要
    private List<ToolRecord> toolRecords;        // Tool 运行记录
    private List<SkillRecord> skillRecords;      // Skill 调用记录
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
        this.toolRecords = new CopyOnWriteArrayList<>();
        this.skillRecords = new CopyOnWriteArrayList<>();
        this.data = new ConcurrentHashMap<>();
        this.step = 0;
        this.status = "running";
        this.completedSteps = new CopyOnWriteArrayList<>();
        this.lastAccessTime = System.currentTimeMillis();
    }

    // ========== 基础 Getter/Setter ==========

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public void setTaskDescription(String taskDescription) {
        this.taskDescription = taskDescription;
        lastAccessTime = System.currentTimeMillis();
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
        lastAccessTime = System.currentTimeMillis();
    }

    public List<ToolRecord> getToolRecords() {
        return toolRecords;
    }

    public void setToolRecords(List<ToolRecord> toolRecords) {
        this.toolRecords = toolRecords != null
            ? new CopyOnWriteArrayList<>(toolRecords)
            : new CopyOnWriteArrayList<>();
        lastAccessTime = System.currentTimeMillis();
    }

    public List<SkillRecord> getSkillRecords() {
        return skillRecords;
    }

    public void setSkillRecords(List<SkillRecord> skillRecords) {
        this.skillRecords = skillRecords != null
            ? new CopyOnWriteArrayList<>(skillRecords)
            : new CopyOnWriteArrayList<>();
        lastAccessTime = System.currentTimeMillis();
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data != null ? data : new ConcurrentHashMap<>();
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentAgent() {
        return currentAgent;
    }

    public void setCurrentAgent(String currentAgent) {
        this.currentAgent = currentAgent;
    }

    public List<String> getCompletedSteps() {
        return completedSteps;
    }

    public void setCompletedSteps(List<String> completedSteps) {
        this.completedSteps = completedSteps != null
            ? new CopyOnWriteArrayList<>(completedSteps)
            : new CopyOnWriteArrayList<>();
    }

    public long getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(long lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }

    // ========== 便捷方法 ==========

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
        toolRecords.clear();
        skillRecords.clear();
        summary = null;
        taskDescription = null;
        step = 0;
        status = "running";
        completedSteps.clear();
        lastAccessTime = System.currentTimeMillis();
    }

    // ========== ToolRecord 便捷方法 ==========

    /**
     * 添加 Tool 记录
     *
     * @param record Tool 记录
     */
    public void addToolRecord(ToolRecord record) {
        toolRecords.add(record);
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 创建并添加 Tool 记录
     *
     * @param toolName Tool 名称
     * @return 新创建的 ToolRecord
     */
    public ToolRecord createToolRecord(String toolName) {
        ToolRecord record = new ToolRecord(toolName, currentAgent);
        toolRecords.add(record);
        lastAccessTime = System.currentTimeMillis();
        return record;
    }

    /**
     * 获取所有成功的 Tool 记录
     *
     * @return 成功的记录列表
     */
    public List<ToolRecord> getSuccessfulToolRecords() {
        lastAccessTime = System.currentTimeMillis();
        return toolRecords.stream()
                .filter(ToolRecord::isSuccess)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有失败的 Tool 记录
     *
     * @return 失败的记录列表
     */
    public List<ToolRecord> getFailedToolRecords() {
        lastAccessTime = System.currentTimeMillis();
        return toolRecords.stream()
                .filter(r -> !r.isSuccess())
                .collect(Collectors.toList());
    }

    /**
     * 获取指定 Tool 的记录
     *
     * @param toolName Tool 名称
     * @return 该 Tool 的所有记录
     */
    public List<ToolRecord> getToolRecordsByName(String toolName) {
        lastAccessTime = System.currentTimeMillis();
        return toolRecords.stream()
                .filter(r -> toolName.equals(r.getToolName()))
                .collect(Collectors.toList());
    }

    /**
     * 获取 Tool 执行总次数
     *
     * @return 总次数
     */
    public int getToolExecutionCount() {
        lastAccessTime = System.currentTimeMillis();
        return toolRecords.size();
    }

    /**
     * 获取 Tool 执行总耗时
     *
     * @return 总耗时（毫秒）
     */
    public long getTotalToolDuration() {
        lastAccessTime = System.currentTimeMillis();
        return toolRecords.stream()
                .mapToLong(ToolRecord::getDuration)
                .sum();
    }

    // ========== SkillRecord 便捷方法 ==========

    /**
     * 添加 Skill 记录
     *
     * @param record Skill 记录
     */
    public void addSkillRecord(SkillRecord record) {
        skillRecords.add(record);
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 创建并添加 Skill 记录
     *
     * @param skillName Skill 名称
     * @return 新创建的 SkillRecord
     */
    public SkillRecord createSkillRecord(String skillName) {
        SkillRecord record = new SkillRecord(skillName, currentAgent);
        skillRecords.add(record);
        lastAccessTime = System.currentTimeMillis();
        return record;
    }

    /**
     * 获取所有成功的 Skill 记录
     *
     * @return 成功的记录列表
     */
    public List<SkillRecord> getSuccessfulSkillRecords() {
        lastAccessTime = System.currentTimeMillis();
        return skillRecords.stream()
                .filter(SkillRecord::isSuccess)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有失败的 Skill 记录
     *
     * @return 失败的记录列表
     */
    public List<SkillRecord> getFailedSkillRecords() {
        lastAccessTime = System.currentTimeMillis();
        return skillRecords.stream()
                .filter(r -> !r.isSuccess())
                .collect(Collectors.toList());
    }

    /**
     * 获取指定 Skill 的记录
     *
     * @param skillName Skill 名称
     * @return 该 Skill 的所有记录
     */
    public List<SkillRecord> getSkillRecordsByName(String skillName) {
        lastAccessTime = System.currentTimeMillis();
        return skillRecords.stream()
                .filter(r -> skillName.equals(r.getSkillName()))
                .collect(Collectors.toList());
    }

    /**
     * 获取 Skill 调用总次数
     *
     * @return 总次数
     */
    public int getSkillExecutionCount() {
        lastAccessTime = System.currentTimeMillis();
        return skillRecords.size();
    }

    /**
     * 获取 Skill 执行总耗时
     *
     * @return 总耗时（毫秒）
     */
    public long getTotalSkillDuration() {
        lastAccessTime = System.currentTimeMillis();
        return skillRecords.stream()
                .mapToLong(SkillRecord::getDuration)
                .sum();
    }

    @Override
    public String toString() {
        return "WorkingMemory{" +
                "id='" + id + '\'' +
                ", taskId='" + taskId + '\'' +
                ", taskDescription='" + (taskDescription != null ? taskDescription.substring(0, Math.min(30, taskDescription.length())) + "..." : "null") + '\'' +
                ", summary='" + (summary != null ? summary.substring(0, Math.min(30, summary.length())) + "..." : "null") + '\'' +
                ", toolRecords=" + toolRecords.size() +
                ", skillRecords=" + skillRecords.size() +
                ", step=" + step +
                ", status='" + status + '\'' +
                ", currentAgent='" + currentAgent + '\'' +
                ", dataSize=" + data.size() +
                ", completedSteps=" + completedSteps.size() +
                ", lastAccessTime=" + lastAccessTime +
                '}';
    }
}
