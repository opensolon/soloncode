package org.noear.solon.codecli.market;

import org.noear.solon.net.http.HttpSslSupplier;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * 市场请求专用的 SSL 供应器 — 使用平台默认 SSLContext 与默认主机名校验器。
 *
 * <p>solon 默认的 {@code HttpSslSupplierDefault} 会为每个连接设置自定义
 * HostnameVerifier；在 JDK8 上这会触发 JDK-8144566：连接不再发送 SNI 扩展，
 * CDN 站点（如 api.skillhub.cn）随之返回默认站点的证书，导致证书校验失败。
 * 改用平台默认 verifier 后 SNI 正常发送，主机名校验也更为严格。</p>
 *
 * @author noear 2026/10/20 created
 */
public class MarketSslSupplier implements HttpSslSupplier {
    private static final MarketSslSupplier INSTANCE = new MarketSslSupplier();

    public static MarketSslSupplier getInstance() {
        return INSTANCE;
    }

    private MarketSslSupplier() {
    }

    @Override
    public SSLContext getSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get default SSLContext", e);
        }
    }

    @Override
    public javax.net.ssl.SSLSocketFactory getSocketFactory() {
        return getSslContext().getSocketFactory();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        return HttpsURLConnection.getDefaultHostnameVerifier();
    }

    @Override
    public X509TrustManager getX509TrustManager() {
        return null;
    }
}
