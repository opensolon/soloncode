package org.noear.solon.codecli.command.builtin;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证器工厂 — 根据目标条件匹配合适的验证器。
 *
 * <p>支持链式注册，多个验证器可同时生效（AND 语义）。
 * 所有匹配的验证器都通过才算通过，任一失败即返回失败。</p>
 *
 * <p>默认注册 {@link TestsPassValidator}，匹配含 test/pass/check 等关键字的目标。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class ValidatorFactory {
    private static final List<ValidatorRegistration> registrations = new ArrayList<>();

    private ValidatorFactory() {
    }

    /**
     * 注册一个带条件匹配的验证器。
     *
     * @param matcher   条件匹配器：返回 true 表示应该应用此验证器
     * @param validator 验证器实例
     */
    public static synchronized void register(ConditionMatcher matcher, GoalValidator validator) {
        registrations.add(new ValidatorRegistration(matcher, validator));
    }

    /**
     * 根据目标条件匹配合适的验证器链。
     *
     * <p>返回链式验证器（AND 语义），所有匹配的验证器都通过才算通过。
     * 无匹配时返回 {@link NoopValidator}。</p>
     */
    public static GoalValidator forCondition(String condition) {
        if (condition == null || condition.isEmpty()) {
            return new NoopValidator();
        }

        String lower = condition.toLowerCase();
        List<GoalValidator> matched = new ArrayList<>();

        for (ValidatorRegistration reg : registrations) {
            if (reg.matcher.matches(lower)) {
                matched.add(reg.validator);
            }
        }

        if (matched.isEmpty()) {
            return new NoopValidator();
        }

        return new ChainValidator(matched);
    }

    /**
     * 初始化默认验证器（在应用启动时调用）。
     */
    public static void initDefaults(String workspace) {
        register(
                condition -> condition.contains("test")
                        || condition.contains("pass")
                        || condition.contains("check")
                        || condition.contains("ensure")
                        || condition.contains("verify"),
                new TestsPassValidator(workspace)
        );

        // 构建验证器：匹配含 build/compile/implement/refactor/fix/create/add/write 的目标
        register(
                condition -> condition.contains("build")
                        || condition.contains("compile")
                        || condition.contains("implement")
                        || condition.contains("refactor")
                        || condition.contains("fix")
                        || condition.contains("create")
                        || condition.contains("add")
                        || condition.contains("write"),
                new BuildPassValidator(workspace)
        );
    }

    /**
     * 清除所有注册（用于测试）。
     */
    public static synchronized void clear() {
        registrations.clear();
    }

    @FunctionalInterface
    public interface ConditionMatcher {
        boolean matches(String conditionLowercase);
    }

    private static class ValidatorRegistration {
        final ConditionMatcher matcher;
        final GoalValidator validator;

        ValidatorRegistration(ConditionMatcher matcher, GoalValidator validator) {
            this.matcher = matcher;
            this.validator = validator;
        }
    }

    /**
     * 链式验证器 — 所有子验证器都通过才算通过。
     */
    private static class ChainValidator implements GoalValidator {
        private final List<GoalValidator> validators;

        ChainValidator(List<GoalValidator> validators) {
            this.validators = validators;
        }

        @Override
        public ValidationResult validate(String condition, String sessionId) {
            for (GoalValidator v : validators) {
                ValidationResult vr = v.validate(condition, sessionId);
                if (!vr.isPassed()) {
                    return vr;
                }
            }
            return ValidationResult.passed();
        }
    }
}
