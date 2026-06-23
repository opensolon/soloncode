package org.noear.solon.codecli.command.builtin;

import org.noear.solon.Solon;

/**
 * Loop 工程集中配置 — 从 app.yml / 环境变量读取，提供合理默认值。
 *
 * <p>所有硬编码常量逐步迁移至此。配置前缀：{@code soloncode.loop.xxx}</p>
 *
 * <pre>
 * soloncode:
 *   loop:
 *     budgetWarningPercent: 70
 *     budgetCriticalPercent: 85
 *     pauseAutoAbandonHours: 24
 *     validatorEnabled: true
 *     defaultMaxTokens: 200000
 *     defaultMaxDurationMinutes: 30
 *     stagnationThreshold: 3
 *     maxConsecutiveErrors: 3
 * </pre>
 *
 * @author noear
 * @since 3.9.4
 */
public class LoopConfig {

    // ===== 预算阶段阈值 =====
    private int budgetWarningPercent = 70;
    private int budgetCriticalPercent = 85;

    // ===== 默认预算上限 =====
    private long defaultMaxTokens = 200_000;           // 默认 20 万 tokens
    private long defaultMaxDurationMs = 30 * 60 * 1000L; // 默认 30 分钟

    // ===== 运行时兜底 =====
    private int stagnationThreshold = 3;    // 连续无进展轮次阈值
    private int maxConsecutiveErrors = 3;   // 连续异常阈值（TurnError → blocked）

    // ===== PAUSED 超时放弃 =====
    private long pauseAutoAbandonMs = 24 * 60 * 60 * 1000L; // 24 小时

    // ===== 验证器 =====
    private boolean validatorEnabled = true;

    public LoopConfig() {
        try {
            org.noear.solon.core.Props cfg = Solon.cfg();
            budgetWarningPercent = cfg.getInt("soloncode.loop.budgetWarningPercent", 70);
            budgetCriticalPercent = cfg.getInt("soloncode.loop.budgetCriticalPercent", 85);
            pauseAutoAbandonMs = cfg.getInt("soloncode.loop.pauseAutoAbandonHours", 24) * 3_600_000L;
            validatorEnabled = cfg.getBool("soloncode.loop.validatorEnabled", true);
            defaultMaxTokens = cfg.getLong("soloncode.loop.defaultMaxTokens", 200_000L);
            defaultMaxDurationMs = cfg.getInt("soloncode.loop.defaultMaxDurationMinutes", 30) * 60_000L;
            stagnationThreshold = cfg.getInt("soloncode.loop.stagnationThreshold", 3);
            maxConsecutiveErrors = cfg.getInt("soloncode.loop.maxConsecutiveErrors", 3);
        } catch (Exception ignored) {
            // 非 Solon 环境或配置不可用时使用默认值
        }
    }

    // ===== Getters =====

    public int getBudgetWarningPercent() {
        return budgetWarningPercent;
    }

    public int getBudgetCriticalPercent() {
        return budgetCriticalPercent;
    }

    public long getPauseAutoAbandonMs() {
        return pauseAutoAbandonMs;
    }

    public boolean isValidatorEnabled() {
        return validatorEnabled;
    }

    public long getDefaultMaxTokens() {
        return defaultMaxTokens;
    }

    public long getDefaultMaxDurationMs() {
        return defaultMaxDurationMs;
    }

    public int getStagnationThreshold() {
        return stagnationThreshold;
    }

    public int getMaxConsecutiveErrors() {
        return maxConsecutiveErrors;
    }
}
