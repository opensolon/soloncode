package org.noear.solon.codecli.config.models;

/**
 * 模型列表拉取失败异常。
 * 适配器不得用空列表吞掉网络、认证或响应解析错误，否则调用方会误判为成功。
 * 必须携带 {@link ModelsFetchReason}，让调用方能区分“不支持拉取”与“拉取失败”。
 */
public class ModelsFetchException extends RuntimeException {
    private final ModelsFetchReason reason;
    /** 上游 HTTP 状态码，0 表示请求未拿到响应 */
    private final int status;

    public ModelsFetchException(String message, ModelsFetchReason reason, int status, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? ModelsFetchReason.UNKNOWN : reason;
        this.status = status;
    }

    public ModelsFetchException(String message, ModelsFetchReason reason, Throwable cause) {
        this(message, reason, 0, cause);
    }

    public ModelsFetchReason getReason() {
        return reason;
    }

    public int getStatus() {
        return status;
    }
}
