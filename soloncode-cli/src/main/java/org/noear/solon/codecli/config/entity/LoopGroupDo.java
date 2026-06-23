package org.noear.solon.codecli.config.entity;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * Loop Goal 配置分组（对应 settings.json -> loop 节点）
 *
 * <p>所有字段为包装类型，null 表示未配置，由便捷方法提供默认值。</p>
 *
 * @author noear
 * @since 3.9.5
 */
@Getter
@Setter
public class LoopGroupDo implements Serializable {
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

    // ===== PAUSED 超时放弃 =====
    // 暂停自动放弃时间（小时，默认 24）
    private Integer pauseAutoAbandonHours;

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

    public long getPauseAutoAbandonMsOrDefault() {
        return pauseAutoAbandonHours != null ? pauseAutoAbandonHours * 3_600_000L : 24 * 3_600_000L;
    }

    public boolean isValidatorEnabledOrDefault() {
        return validatorEnabled != null ? validatorEnabled : true;
    }
}
