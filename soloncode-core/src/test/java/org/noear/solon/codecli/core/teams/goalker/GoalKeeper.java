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
package org.noear.solon.codecli.core.teams.goalker;

import lombok.Getter;
import org.noear.solon.codecli.core.teams.memory.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 目标守护者 - 防止 AI 在多轮循环中偏离目标
 *
 * 核心功能：
 * 1. 持续在上下文中强化原始目标
 * 2. 定期提醒 AI 当前目标和进度
 * 3. 检测目标漂移并警告
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public class GoalKeeper {
    private static final Logger LOG = LoggerFactory.getLogger(GoalKeeper.class);

    private final String goalId;
    private final String originalGoal;
    private final long startTime;
    private int reminderCount = 0;

    // 配置
    private static final int REMINDER_INTERVAL = 5;  // 每 5 步提醒一次
    private static final int DRIFT_THRESHOLD = 10;  // 10 步后强制提醒

    public GoalKeeper(String goalId, String originalGoal) {
        this.goalId = goalId;
        this.originalGoal = originalGoal;
        this.startTime = System.currentTimeMillis();
        LOG.info("目标守护者已创建: goal={}, 目标={}", goalId, originalGoal);
    }

    /**
     * 在 ReAct 循环中注入目标提醒
     *
     * @param currentStep 当前步数
     * @return 目标提醒文本
     */
    public String injectReminder(int currentStep) {
        // 每 N 步提醒一次
        if (currentStep > 0 && currentStep % REMINDER_INTERVAL == 0) {
            reminderCount++;
            String reminder = buildReminder(currentStep);
            LOG.debug("注入目标提醒 [第{}次]: step={}", reminderCount, currentStep);
            return reminder;
        }

        // 超过阈值后，每次都提醒
        if (currentStep > DRIFT_THRESHOLD) {
            return buildUrgentReminder(currentStep);
        }

        return null;  // 不需要提醒
    }

    /**
     * 构建常规提醒
     */
    private String buildReminder(int currentStep) {
        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedMinutes = elapsed / 60000.0;

        return String.format("\n" +
                "=== 🎯 目标提醒 (第 %d 步，用时 %.1f 分钟) ===\n" +
                "原始目标: %s\n" +
                "当前进度: 第 %d / %d 步\n" +
                "提示: 请确保当前操作与原始目标保持一致，如果已经偏离，请立即调整方向。\n" +
                "====================\n",
                currentStep, elapsedMinutes, originalGoal,
                currentStep, currentStep + 5
        );
    }

    /**
     * 构建紧急提醒
     */
    private String buildUrgentReminder(int currentStep) {
        return String.format("\n" +
                "=== ⚠️ 警告：可能已偏离目标 (第 %d 步) ===\n" +
                "原始目标: %s\n" +
                "重要提示: 你已经执行了 %d 步，请停下来检查:\n" +
                "  1. 我是否还在为原始目标工作？\n" +
                "  2. 我是否在做无关紧要的操作？\n" +
                "  3. 我是否应该给出最终答案了？\n" +
                "如果以上任何一个答案是肯定的，请立即给出 Final Answer。\n" +
                "=========================================\n",
                currentStep, originalGoal, currentStep
        );
    }

    /**
     * 检查是否应该强制结束
     *
     * @param currentStep 当前步数
     * @param maxSteps 最大步数
     * @return 是否应该结束
     */
    public boolean shouldForceFinish(int currentStep, int maxSteps) {
        if (currentStep >= maxSteps) {
            LOG.warn("达到最大步数限制 ({}), 建议结束", maxSteps);
            return true;
        }
        return false;
    }

    /**
     * 获取目标摘要（用于初始化时存储）
     */
    public String getGoalSummary() {
        return String.format("目标: %s (ID: %s)", originalGoal, goalId);
    }

    /**
     * 更新到工作记忆
     */
    public void updateWorkingMemory(WorkingMemory workingMemory) {
        if (workingMemory != null) {
            workingMemory.setTaskDescription(originalGoal);
            workingMemory.putMetadata("goalId", goalId);
            workingMemory.putMetadata("startTime", String.valueOf(startTime));
        }
    }

}
