package org.noear.solon.codecli.command.builtin;

/**
 * 空操作验证器 — 总是通过验证。
 *
 * <p>作为默认验证器，确保引入验证机制后不改变现有功能。
 * 当目标条件不匹配任何自定义验证器时使用此实现。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class NoopValidator implements GoalValidator {

    @Override
    public ValidationResult validate(String condition, String sessionId) {
        return ValidationResult.passed();
    }
}
