package org.noear.solon.codecli.auth;

/** 本地用户密码策略。密码按 Java 字符数计数，允许 4 到 128 个字符。 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    public static String validate(String password) {
        if (password == null || password.isEmpty()) {
            return "密码不能为空";
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return "密码长度必须在 4 到 128 个字符之间";
        }
        for (int i = 0; i < password.length(); i++) {
            if (Character.isISOControl(password.charAt(i))) {
                return "密码不能包含控制字符";
            }
        }
        return null;
    }

    public static void requireValid(String password) {
        String error = validate(password);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
    }
}
