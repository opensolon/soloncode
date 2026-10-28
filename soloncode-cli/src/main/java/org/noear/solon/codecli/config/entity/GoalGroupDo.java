/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.config.entity;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * Goal 配置分组（对应 settings.json -> goal 节点）。
 *
 * <p>Goal 已从 loop 定时任务体系中独立出来，配置随之分离：本组只描述
 * 会话目标的预算阈值、默认预算与运行时兜底策略。所有字段为包装类型，
 * null 表示未配置，由便捷方法提供默认值。</p>
 *
 * <p>兼容性：{@code soloncode.loop.*} 旧键在 {@code AgentSettings} 中仍作为
 * 回填来源读取，升级用户不会丢配置。</p>
 *
 * @author noear
 */
@Getter
@Setter
public class GoalGroupDo implements Serializable {
    // ===== 预算阶段阈值 =====
    // 预算警告百分比（默认 70）
    private Integer budgetWarningPercent;
    // 预算紧急百分比（默认 85）
    private Integer budgetCriticalPercent;

    // ===== 默认预算上限 =====
    // 默认 Token 预算上限（0 = 不限制）
    private Long defaultMaxTokens;
    // 默认时间预算上限（分钟，0 = 不限制）
    private Integer defaultMaxDurationMinutes;

    // ===== 运行时兜底 =====
    // 连续无进展轮次阈值（默认 3）
    private Integer stagnationThreshold;
    // 连续异常阈值（TurnError → blocked，默认 3）
    private Integer maxConsecutiveErrors;

    // ===== 验证器 =====
    // 启用验证器（默认 true）
    private Boolean validatorEnabled;

    // ===== 便捷方法（提供默认值，确保 null 安全） =====

    public int getBudgetWarningPercentOrDefault() {
        return budgetWarningPercent != null ? budgetWarningPercent : 70;
    }

    public int getBudgetCriticalPercentOrDefault() {
        return budgetCriticalPercent != null ? budgetCriticalPercent : 85;
    }

    public long getDefaultMaxTokensOrDefault() {
        return defaultMaxTokens != null ? defaultMaxTokens : 0L;
    }

    public long getDefaultMaxDurationMsOrDefault() {
        return defaultMaxDurationMinutes != null ? defaultMaxDurationMinutes * 60_000L : 0L;
    }

    public int getStagnationThresholdOrDefault() {
        return stagnationThreshold != null ? stagnationThreshold : 3;
    }

    public int getMaxConsecutiveErrorsOrDefault() {
        return maxConsecutiveErrors != null ? maxConsecutiveErrors : 3;
    }

    public boolean isValidatorEnabledOrDefault() {
        return validatorEnabled != null ? validatorEnabled : true;
    }
}
