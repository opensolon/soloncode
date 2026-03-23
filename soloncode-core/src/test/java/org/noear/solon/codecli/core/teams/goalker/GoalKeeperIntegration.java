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

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.codecli.core.teams.memory.WorkingMemory;
import org.noear.solon.codecli.core.teams.MainAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标守护者集成工具
 *
 * 集成 GoalKeeper 到 AgentKernel，提供简单易用的 API
 *
 * @author bai
 * @since 3.9.5
 */
public class GoalKeeperIntegration {
    private static final Logger LOG = LoggerFactory.getLogger(GoalKeeperIntegration.class);

    private final MainAgent mainAgent;
    private GoalKeeper currentGoalKeeper;

    public GoalKeeperIntegration(MainAgent mainAgent) {
        this.mainAgent = mainAgent;
    }

    /**
     * 启动目标守护
     *
     * @param userPrompt 用户的目标提示词
     * @return 目标 ID
     */
    public String startGoalGuarding(String userPrompt) {
        // 生成目标 ID
        String goalId = "goal-" + System.currentTimeMillis();

        // 创建 GoalKeeper
        currentGoalKeeper = new GoalKeeper(goalId, userPrompt);

        // 保存到工作记忆
        if (mainAgent.getSharedMemoryManager() != null) {
            WorkingMemory workingMemory = mainAgent.getSharedMemoryManager().getWorking(mainAgent.getCurrentGoalId());
            if (workingMemory == null) {
                workingMemory = new WorkingMemory(goalId);
            }

            currentGoalKeeper.updateWorkingMemory(workingMemory);
            workingMemory.setCurrentAgent("main");
            workingMemory.setStatus("目标守护中: " + userPrompt);

            mainAgent.getSharedMemoryManager().storeWorking(workingMemory);
            LOG.info("目标已保存到工作记忆: {}", userPrompt);
        }

        LOG.info("目标守护已启动: goalId={}, 目标={}", goalId, userPrompt);
        return goalId;
    }

    /**
     * 为流式响应添加目标提醒
     *
     * @param chunks 响应块列表
     * @param currentStep 当前步数
     * @return 带提醒的响应块列表
     */
    public List<AgentChunk> addGoalRemindersIfNeeded(List<AgentChunk> chunks, int currentStep) {
        if (currentGoalKeeper == null) {
            return chunks;
        }

        // 检查是否需要注入提醒
        String reminder = currentGoalKeeper.injectReminder(currentStep);
        if (reminder != null && !chunks.isEmpty()) {
            LOG.info("注入目标提醒: step={}, reminderCount={}", currentStep, currentGoalKeeper.getReminderCount());

            // 创建一个包含提醒的简单 AgentChunk
            List<AgentChunk> result = new ArrayList<>(chunks.size() + 1);
            result.addAll(chunks);  // 先添加原有 chunks

            // 注意：由于 AgentChunk 是接口，我们无法直接实例化
            // 这里只是记录日志，实际的提醒需要通过其他方式注入
            LOG.info("目标提醒内容: {}", reminder);

            return result;
        }

        return chunks;
    }

    /**
     * 检查是否应该强制结束
     *
     * @param currentStep 当前步数
     * @param maxSteps 最大步数
     * @return 是否应该结束
     */
    public boolean shouldForceFinish(int currentStep, int maxSteps) {
        if (currentGoalKeeper == null) {
            return false;
        }

        return currentGoalKeeper.shouldForceFinish(currentStep, maxSteps);
    }

    /**
     * 停止目标守护
     */
    public void stopGoalGuarding() {
        if (currentGoalKeeper != null) {
            LOG.info("目标守护已停止: goalId={}", currentGoalKeeper.getGoalId());

            // 清理工作记忆
            if (mainAgent.getSharedMemoryManager() != null) {
                WorkingMemory workingMemory = mainAgent.getSharedMemoryManager().getWorking(mainAgent.getCurrentGoalId());
                if (workingMemory != null && workingMemory.getTaskId().equals(currentGoalKeeper.getGoalId())) {
                    workingMemory.setStatus("目标已完成");
                    mainAgent.getSharedMemoryManager().storeWorking(workingMemory);
                }
            }

            currentGoalKeeper = null;
        }
    }

    /**
     * 获取当前目标
     *
     * @return 当前目标描述，如果未启动返回 null
     */
    public String getCurrentGoal() {
        return currentGoalKeeper != null ? currentGoalKeeper.getOriginalGoal() : null;
    }

    /**
     * 获取当前目标 ID
     *
     * @return 目标 ID，如果未启动返回 null
     */
    public String getCurrentGoalId() {
        return currentGoalKeeper != null ? currentGoalKeeper.getGoalId() : null;
    }

    /**
     * 检查是否正在守护
     *
     * @return 是否正在守护
     */
    public boolean isGuarding() {
        return currentGoalKeeper != null;
    }

    /**
     * 获取提醒次数
     *
     * @return 提醒次数
     */
    public int getReminderCount() {
        return currentGoalKeeper != null ? currentGoalKeeper.getReminderCount() : 0;
    }

    /**
     * 为 Prompt 添加目标上下文
     *
     * @param prompt 原始提示词
     * @return 带目标的提示词
     */
    public Prompt enrichPromptWithGoal(Prompt prompt) {
        if (currentGoalKeeper == null) {
            return prompt;
        }

        String goalContext = String.format(
            "\n\n## 🎯 任务目标\n\n原始目标: %s\n目标ID: %s\n\n请在执行过程中始终牢记这个目标。\n",
            currentGoalKeeper.getOriginalGoal(),
            currentGoalKeeper.getGoalId()
        );

        // 创建新的 Prompt（保留原有内容）
        return Prompt.of(prompt.getUserContent() + goalContext);
    }
}
