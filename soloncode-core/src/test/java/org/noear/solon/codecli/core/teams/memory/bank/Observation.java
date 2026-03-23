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
package org.noear.solon.codecli.core.teams.memory.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 观察（Observation）
 * <p>
 * MemoryBank 的基本记忆单元，记录一次观察或操作
 * <p>
 * 设计理念：
 * - 模拟人脑的"观察"机制
 * - 每个 Observation 都是原子化的、独立的
 * - 支持重要性评分和向量检索
 *
 * @author bai
 * @since 3.9.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Observation {
    /**
     * 唯一标识
     */
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /**
     * 观察内容（核心信息）
     * 限制：最多 500 字符
     */
    private String content;

    /**
     * 观察类型（用于分类）
     */
    @Builder.Default
    private ObservationType type = ObservationType.GENERAL;

    /**
     * 重要性评分（0.0-10.0）
     * - 0-3: 低重要性（临时信息）
     * - 4-6: 中等重要性（一般信息）
     * - 7-10: 高重要性（关键决策/结论）
     */
    @Builder.Default
    private double importance = 5.0;

    /**
     * 创建时间戳（毫秒）
     */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    /**
     * 访问次数（用于计算热度）
     */
    @Builder.Default
    private int accessCount = 0;

    /**
     * 最后访问时间
     */
    private long lastAccessTime;

    /**
     * 来源 Agent ID
     */
    private String sourceAgent;

    /**
     * 关联任务 ID
     */
    private String taskId;

    /**
     * 向量 Embedding（用于语义检索）
     * 可选：如果不使用向量检索，可以为 null
     */
    private float[] embedding;

    /**
     * 元数据（扩展信息）
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 观察类型枚举
     */
    public enum ObservationType {
        /**
         * 一般观察（默认）
         */
        GENERAL,

        /**
         * 工具调用记录
         */
        TOOL_CALL,

        /**
         * 技能执行记录
         */
        SKILL_EXECUTION,

        /**
         * 任务结果
         */
        TASK_RESULT,

        /**
         * 决策
         */
        DECISION,

        /**
         * 错误/异常
         */
        ERROR,

        /**
         * 代码理解
         */
        CODE_UNDERSTANDING,

        /**
         * 架构知识
         */
        ARCHITECTURE,

        /**
         * 用户需求
         */
        USER_REQUIREMENT
    }

    /**
     * 计算时间衰减后的重要性
     * <p>
     * 公式：importance * exp(-lambda * age_hours)
     * 其中 lambda 是衰减系数（默认 0.1）
     *
     * @return 衰减后的重要性分数
     */
    public double getDecayedImportance() {
        if (timestamp <= 0) {
            return importance;
        }

        long ageMs = System.currentTimeMillis() - timestamp;
        double ageHours = ageMs / (1000.0 * 60 * 60);

        // 衰减系数：0.1 表示每小时衰减 10%
        double lambda = 0.1;
        double decay = Math.exp(-lambda * ageHours);

        return importance * decay;
    }

    /**
     * 计算综合分数（用于排序）
     * <p>
     * 综合考虑：
     * - 重要性（50%）
     * - 时间衰减（30%）
     * - 访问热度（20%）
     *
     * @return 综合分数
     */
    public double getScore() {
        double importanceScore = getDecayedImportance() * 0.5;
        double accessScore = Math.log(accessCount + 1) * 2.0 * 0.2;
        double recencyScore = Math.max(0, 10 - (System.currentTimeMillis() - timestamp) / (1000.0 * 60 * 60)) * 0.3;

        return importanceScore + accessScore + recencyScore;
    }

    /**
     * 估算 token 数量
     * <p>
     * 粗略估算：英文约 4 字符/token，中文约 2 字符/token
     *
     * @return 估算的 token 数
     */
    public int estimateTokens() {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        // 简单估算：平均每 3 字符 1 token
        return (content.length() / 3) + 10; // +10 用于元数据
    }

    /**
     * 记录一次访问
     */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 添加元数据
     */
    public void putMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }

    /**
     * 获取元数据
     */
    public Object getMetadata(String key) {
        if (this.metadata == null) {
            return null;
        }
        return this.metadata.get(key);
    }

    @Override
    public String toString() {
        return "Observation{" +
                "id='" + id.substring(0, 8) + '\'' +
                ", type=" + type +
                ", content='" + (content != null ? content.substring(0, Math.min(30, content.length())) + "..." : "null") + '\'' +
                ", importance=" + String.format("%.1f", importance) +
                ", score=" + String.format("%.1f", getScore()) +
                ", tokens=" + estimateTokens() +
                ", sourceAgent='" + sourceAgent + '\'' +
                '}';
    }
}
