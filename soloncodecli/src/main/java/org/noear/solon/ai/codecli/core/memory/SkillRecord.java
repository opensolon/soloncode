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

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 调用记录
 *
 * 记录 Skill 的调用过程和结果，用于调试和审计
 *
 * @author bai
 * @since 3.9.5
 */
public class SkillRecord {
    private String skillName;             // Skill 名称
    private String skillDescription;      // Skill 描述
    private String skillPool;             // Skill 池（如 @soloncode_skills）
    private Map<String, Object> inputs;  // 输入参数
    private Object output;               // 输出结果
    private long startTime;              // 开始时间
    private long endTime;                // 结束时间
    private long duration;               // 执行耗时（毫秒）
    private boolean success;             // 是否成功
    private String errorMessage;         // 错误信息
    private String agentId;              // 调用的 Agent ID
    private String skillFile;            // Skill 文件路径（如果是文件 skill）

    /**
     * 构造函数
     *
     * @param skillName Skill 名称
     * @param agentId 调用的 Agent ID
     */
    public SkillRecord(String skillName, String agentId) {
        this.skillName = skillName;
        this.agentId = agentId;
        this.inputs = new HashMap<>();
        this.startTime = System.currentTimeMillis();
        this.success = false;
    }

    /**
     * 标记成功
     *
     * @param output 输出结果
     */
    public void success(Object output) {
        this.output = output;
        this.success = true;
        this.endTime = System.currentTimeMillis();
        this.duration = this.endTime - this.startTime;
    }

    /**
     * 标记失败
     *
     * @param errorMessage 错误信息
     */
    public void failure(String errorMessage) {
        this.errorMessage = errorMessage;
        this.success = false;
        this.endTime = System.currentTimeMillis();
        this.duration = this.endTime - this.startTime;
    }

    /**
     * 添加输入参数
     *
     * @param key 参数名
     * @param value 参数值
     */
    public void addInput(String key, Object value) {
        this.inputs.put(key, value);
    }

    /**
     * 设置输入参数
     *
     * @param inputs 输入参数 Map
     */
    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs != null ? inputs : new HashMap<>();
    }

    // ========== Getter/Setter ==========

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getSkillDescription() {
        return skillDescription;
    }

    public void setSkillDescription(String skillDescription) {
        this.skillDescription = skillDescription;
    }

    public String getSkillPool() {
        return skillPool;
    }

    public void setSkillPool(String skillPool) {
        this.skillPool = skillPool;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public Object getOutput() {
        return output;
    }

    public void setOutput(Object output) {
        this.output = output;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSkillFile() {
        return skillFile;
    }

    public void setSkillFile(String skillFile) {
        this.skillFile = skillFile;
    }

    @Override
    public String toString() {
        return "SkillRecord{" +
                "skillName='" + skillName + '\'' +
                ", skillPool='" + skillPool + '\'' +
                ", success=" + success +
                ", duration=" + duration +
                ", inputs=" + inputs.size() +
                (output != null ? ", hasOutput=true" : "") +
                (errorMessage != null ? ", error='" + errorMessage + '\'' : "") +
                (skillFile != null ? ", file='" + skillFile + '\'' : "") +
                '}';
    }
}
