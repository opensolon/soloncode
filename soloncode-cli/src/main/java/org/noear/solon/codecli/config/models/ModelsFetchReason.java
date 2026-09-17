package org.noear.solon.codecli.config.models;

/**
 * 模型列表拉取失败原因。
 * 前端按该原因给出不同的处置建议（如 404 表示供应商不提供模型列表接口，应引导手动添加）。
 */
public enum ModelsFetchReason {
    /** API 地址格式不合法，请求未发出 */
    INVALID_URL,
    /** 供应商没有模型列表接口（404 / 405）*/
    NOT_SUPPORTED,
    /** 密钥无效或无权访问模型列表（401 / 403）*/
    AUTH_FAILED,
    /** 触发限流（429）*/
    RATE_LIMITED,
    /** 供应商服务端异常（5xx）*/
    UPSTREAM_ERROR,
    /** 其它非 2xx 状态码 */
    BAD_STATUS,
    /** 连接阶段超时：地址、端口写错或被防火墙丢包时最常见 */
    CONNECT_TIMEOUT,
    /** 已连接但迟迟不返回数据 */
    READ_TIMEOUT,
    /** 无法判定阶段的超时（如上游返回 408 / 504）*/
    TIMEOUT,
    /** 域名无法解析 */
    DNS_FAILED,
    /** 端口无服务监听（connection refused）*/
    CONNECT_REFUSED,
    /** TLS 握手或证书校验失败 */
    TLS_ERROR,
    /** 其它网络不可达 */
    NETWORK_ERROR,
    /** 响应不是可识别的模型列表结构 */
    INVALID_RESPONSE,
    /** 未归类错误 */
    UNKNOWN;

    /**
     * HTTP 状态码映射为失败原因
     */
    public static ModelsFetchReason ofStatus(int status) {
        if (status == 401 || status == 403 || status == 407) {
            return AUTH_FAILED;
        }
        if (status == 404 || status == 405 || status == 501) {
            return NOT_SUPPORTED;
        }
        if (status == 408 || status == 504) {
            return TIMEOUT;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status >= 500) {
            return UPSTREAM_ERROR;
        }
        return BAD_STATUS;
    }
}
