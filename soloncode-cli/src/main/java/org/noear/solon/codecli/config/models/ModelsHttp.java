package org.noear.solon.codecli.config.models;

import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;

import javax.net.ssl.SSLException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 模型列表请求的公共执行逻辑。
 * <p>
 * HttpUtils#get() 对 4xx/5xx 不抛异常，只把错误响应体当正文返回；若适配器只解析 JSON 数组，
 * 404 这类“没有模型列表接口”的响应会被当成空列表，最终误报为“拉取成功但没有模型”。
 * 因此这里统一检查状态码，并把失败归类成 {@link ModelsFetchReason}。
 * <p>
 * 地址填错是最高频的失败来源，且表现各不相同（域名不存在、端口无监听、连得上但不响应、
 * 协议写成 http/https 相反）。这些情况的处置动作完全不同，必须分开归类，不能合并成
 * 一句“网络错误，请检查网络”，否则会把用户引向错误的排查方向。
 */
public final class ModelsHttp {
    /** 连接超时（秒）：连接阶段失败要尽快返回，避免坏地址让用户干等 */
    public static final int CONNECT_TIMEOUT_SECONDS = 5;
    /** 写超时（秒）*/
    public static final int WRITE_TIMEOUT_SECONDS = 5;
    /**
     * 读超时（秒）。
     * 三段超时之和必须小于前端 ajax 的 20s 超时，否则浏览器会先超时，
     * 后端好不容易分好类的 reason 根本没机会回传，用户只能看到笼统的超时提示。
     */
    public static final int READ_TIMEOUT_SECONDS = 10;

    private ModelsHttp() {
    }

    /**
     * 按统一超时预算创建请求对象
     *
     * @param url 已校验过的模型列表地址
     */
    public static HttpUtils create(String url, String userAgent) {
        return HttpUtils.http(url)
                .userAgent(userAgent)
                .timeout(CONNECT_TIMEOUT_SECONDS, WRITE_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS);
    }

    /**
     * 发请求前校验地址，明显非法的地址立即失败，不必白等一轮连接超时
     *
     * @param url 待校验地址
     * @param tag 协议标识，仅用于异常信息（不含地址与密钥）
     */
    public static void requireHttpUrl(String url, String tag) {
        String value = url == null ? "" : url.trim();

        if (value.isEmpty()) {
            throw invalidUrl(tag, "empty", null);
        }

        // 粘贴带来的空白与全角字符无法构成合法地址，提前拦下
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c == '\u3000' || c == '\uFF1A' || c == '\uFF0F') {
                throw invalidUrl(tag, "illegal char", null);
            }
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            throw invalidUrl(tag, "unparsable", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw invalidUrl(tag, "scheme", null);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw invalidUrl(tag, "host", null);
        }
    }

    private static ModelsFetchException invalidUrl(String tag, String detail, Throwable cause) {
        return new ModelsFetchException(tag + " model list url is invalid: " + detail,
                ModelsFetchReason.INVALID_URL, 0, cause);
    }

    /**
     * 发起 GET 请求并返回响应体，非 2xx 一律按对应原因抛出
     *
     * @param http 已配置好地址、请求头与代理的请求对象
     * @param tag  协议标识，仅用于异常信息（不含地址与密钥）
     */
    public static String getBody(HttpUtils http, String tag) {
        int status = 0;
        try (HttpResponse resp = http.exec("GET")) {
            status = resp.code();
            String body = resp.bodyAsString();

            if (status < 200 || status >= 300) {
                throw new ModelsFetchException(
                        tag + " model list request failed, status=" + status,
                        ModelsFetchReason.ofStatus(status), status, null);
            }

            return body;
        } catch (ModelsFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelsFetchException(tag + " model list request failed", classify(e), status, e);
        }
    }

    /**
     * 解析响应体，非 JSON 内容（如网关 HTML 错误页）按响应格式不可识别处理
     */
    public static ONode parseJson(String body, String tag) {
        if (body == null || body.trim().isEmpty()) {
            throw new ModelsFetchException(tag + " model list response is empty",
                    ModelsFetchReason.INVALID_RESPONSE, 200, null);
        }

        try {
            return ONode.ofJson(body);
        } catch (Exception e) {
            throw new ModelsFetchException(tag + " model list response is not valid json",
                    ModelsFetchReason.INVALID_RESPONSE, 200, e);
        }
    }

    /**
     * 传输层异常归类：底层实现可能把真实原因包在多层异常里，需沿 cause 链逐层判定
     */
    static ModelsFetchReason classify(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            // 域名拼错、内网域名不可解析
            if (cur instanceof UnknownHostException) {
                return ModelsFetchReason.DNS_FAILED;
            }
            // http/https 写反、证书不受信
            if (cur instanceof SSLException) {
                return ModelsFetchReason.TLS_ERROR;
            }
            if (cur instanceof SocketTimeoutException) {
                // OkHttp 连接阶段超时的消息含 connect，读阶段为 timeout/read timed out
                return isConnectPhase(cur) ? ModelsFetchReason.CONNECT_TIMEOUT : ModelsFetchReason.READ_TIMEOUT;
            }
            if (cur instanceof ConnectException) {
                // 连接阶段也可能以 ConnectException("connection timed out") 形式出现
                return isTimeoutMessage(cur)
                        ? ModelsFetchReason.CONNECT_TIMEOUT
                        : ModelsFetchReason.CONNECT_REFUSED;
            }
            if (cur instanceof NoRouteToHostException) {
                return ModelsFetchReason.NETWORK_ERROR;
            }
            if (cur instanceof InterruptedIOException || cur instanceof TimeoutException) {
                return isConnectPhase(cur) ? ModelsFetchReason.CONNECT_TIMEOUT : ModelsFetchReason.READ_TIMEOUT;
            }
            cur = cur.getCause();
        }
        return ModelsFetchReason.NETWORK_ERROR;
    }

    private static boolean isConnectPhase(Throwable e) {
        String msg = message(e);
        return msg.contains("connect");
    }

    private static boolean isTimeoutMessage(Throwable e) {
        String msg = message(e);
        return msg.contains("timed out") || msg.contains("timeout");
    }

    private static String message(Throwable e) {
        String msg = e.getMessage();
        return msg == null ? "" : msg.toLowerCase(Locale.ROOT);
    }
}
