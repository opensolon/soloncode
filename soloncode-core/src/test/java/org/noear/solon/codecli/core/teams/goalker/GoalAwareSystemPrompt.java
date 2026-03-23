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

import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.codecli.core.teams.memory.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 目标感知系统提示词 - 防止目标漂移
 *
 * 在原始系统提示词基础上，自动注入：
 * 1. 目标锚定机制
 * 2. 定期目标提醒
 * 3. 进度跟踪
 *
 * @author bai
 * @since 3.9.5
 */
public class GoalAwareSystemPrompt implements ReActSystemPrompt {
    private static final Logger LOG = LoggerFactory.getLogger(GoalAwareSystemPrompt.class);

    private final ReActSystemPrompt delegate;
    private final GoalKeeper goalKeeper;

    public GoalAwareSystemPrompt(ReActSystemPrompt delegate, GoalKeeper goalKeeper) {
        this.delegate = delegate;
        this.goalKeeper = goalKeeper;
    }

    @Override
    public Locale getLocale() {
        return delegate != null ? delegate.getLocale() : Locale.CHINESE;
    }

    @Override
    public String getSystemPrompt(ReActTrace trace) {
        // 获取原始系统提示词
        String basePrompt = delegate.getSystemPrompt(trace);

        // 注入目标锚定信息
        return injectGoalAnchoring(basePrompt, trace);
    }

    /**
     * 注入目标锚定信息
     */
    private String injectGoalAnchoring(String basePrompt, ReActTrace trace) {
        StringBuilder sb = new StringBuilder(basePrompt);

        // 1. 在系统提示词开头添加目标信息
        sb.insert(0, buildGoalHeader());

        // 2. 在系统提示词末尾添加目标提醒机制
        sb.append("\n\n");
        sb.append(buildGoalReminderInstructions());

        return sb.toString();
    }

    /**
     * 构建目标头部信息
     */
    private String buildGoalHeader() {
        return "## 🎯 当前任务目标\n\n" +
                "原始目标: " + goalKeeper.getOriginalGoal() + "\n" +
                "目标ID: " + goalKeeper.getGoalId() + "\n\n" +
                "⚠️ 重要: 在整个任务过程中，请始终牢记这个目标。如果发现自己偏离了目标，请立即调整方向。\n\n";
    }

    /**
     * 构建目标提醒指令
     */
    private String buildGoalReminderInstructions() {
        return "## 🔄 目标保持机制\n\n" +
                "1. **每 5 步自检**: 在执行第 5、10、15、20、25 步时，停下来问自己：\n" +
                "   - 我当前的操作是否与原始目标一致？\n" +
                "   - 我是否在做一些无关紧要的事情？\n" +
                "   - 我是否应该给出最终答案了？\n\n" +
                "2. **避免过度执行**: 不要为了\"完美\"而过度执行。如果核心目标已完成，请立即给出 Final Answer。\n\n" +
                "3. **保持专注**: 如果发现自己在循环调用相同的工具，请停下来思考是否有更好的方法。\n\n" +
                "记住：用户的核心需求是 \"" + goalKeeper.getOriginalGoal() + "\"，所有操作都应该围绕这个目标展开。\n";
    }

    /**
     * 获取当前步数的目标提醒
     */
    public String getStepReminder(int currentStep) {
        return goalKeeper.injectReminder(currentStep);
    }

    /**
     * 更新到工作记忆
     */
    public void updateWorkingMemory(WorkingMemory workingMemory) {
        goalKeeper.updateWorkingMemory(workingMemory);
    }
}
