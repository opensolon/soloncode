package org.noear.solon.codecli.artifact;

import java.io.IOException;

/** 可公开的固定失败原因；不从底层异常消息推断分类。 */
public final class ImageLoadException extends IOException {
    public enum Reason {
        LIMIT("超过图片限制"), FORMAT("格式不支持或图片损坏");
        private final String text;
        Reason(String text) { this.text = text; }
        public String getText() { return text; }
    }

    private final Reason reason;

    public ImageLoadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
