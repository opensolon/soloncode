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
 * </pre>
 *
 * @author noear
 * @since 3.9.4
 */
public class LoopConfig {

    // ===== 预算阶段阈值 =====
    private int budgetWarningPercent = 70;
    private int budgetCriticalPercent = 85;

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
}
