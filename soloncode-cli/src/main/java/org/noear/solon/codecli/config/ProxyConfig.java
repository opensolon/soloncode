package org.noear.solon.codecli.config;

import org.noear.solon.codecli.config.entity.GeneralGroupDo;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpConfiguration;
import org.noear.solon.net.http.HttpSslSupplier;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.HttpUtilsFactory;
import org.noear.solon.net.http.ssl.SslAnyHostnameVerifier;
import org.noear.solon.net.http.ssl.SslAnyTrustManager;
import org.noear.solon.net.http.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTTP 代理配置工具类。
 *
 * <p>从 {@link GeneralGroupDo} 读取代理配置，通过 {@link #applyIfNeeded(HttpUtils)}
 * 应用到 HttpUtils 请求实例。代理配置在 UI 设置面板中动态更新。</p>
 *
 * <p>支持 NO_PROXY 排除列表：通过 {@link GeneralGroupDo#getNoProxy()} 配置，
 * 逗号分隔的主机名或域名，匹配的请求将绕过代理。</p>
 *
 * <p>注册全局 {@link HttpUtilsFactory} 代理，确保所有通过 {@code HttpUtils.http(url)}
 * 创建的请求实例（包括 webfetch、websearch 等库代码）自动应用代理配置。</p>
 *
 * @author noear 2026/8/5 created
 */
public class ProxyConfig {
    private static final Logger log = LoggerFactory.getLogger(ProxyConfig.class);

    private static volatile GeneralGroupDo cachedGeneral;
    private static volatile List<String> noProxyList = Collections.emptyList();
    private static volatile boolean globalFactoryRegistered = false;

    /**
     * 信任所有证书的 SSL 配置（用于代理场景下绕过 TLS 验证）
     */
    private static final HttpSslSupplier TRUST_ALL_SSL = buildTrustAllSsl();

    private static HttpSslSupplier buildTrustAllSsl() {
        try {
            SSLContext ctx = SslContextBuilder.of()
                    .trustManagers(SslAnyTrustManager.INSTANCE)
                    .build();

            return new HttpSslSupplier() {
                @Override
                public SSLContext getSslContext() {
                    return ctx;
                }

                @Override
                public HostnameVerifier getHostnameVerifier() {
                    return SslAnyHostnameVerifier.INSTANCE;
                }

                @Override
                public X509TrustManager getX509TrustManager() {
                    return SslAnyTrustManager.INSTANCE;
                }
            };
        } catch (Exception e) {
            log.warn("Failed to build trust-all SSL config, TLS verification will not be bypassed", e);
            return null;
        }
    }

    /**
     * 更新缓存的代理配置（当用户保存设置时调用）
     */
    public static void update(GeneralGroupDo general) {
        cachedGeneral = general;
        noProxyList = parseNoProxy(general != null ? general.getNoProxy() : null);
        ensureGlobalFactory();
    }

    /**
     * 注册全局 HttpUtilsFactory，确保所有通过 {@code HttpUtils.http(url)} 创建的实例
     * 自动应用代理配置。这覆盖了 webfetch、websearch 等库代码内部创建的 HttpUtils 实例。
     */
    private static synchronized void ensureGlobalFactory() {
        if (globalFactoryRegistered) {
            return;
        }
        globalFactoryRegistered = true;

        HttpUtilsFactory original = HttpConfiguration.getFactory();
        HttpConfiguration.setFactory(url -> {
            HttpUtils http = original.http(url);
            applyIfNeeded(http);
            return http;
        });
    }

    /**
     * 如果配置了代理，将其应用到 HttpUtils 实例，并绕过 TLS 证书验证
     * （避免代理环境下的证书问题，如自签名证书、域名不匹配、证书过期等）
     */
    public static void applyIfNeeded(HttpUtils http) {
        GeneralGroupDo g = cachedGeneral;
        if (g == null) {
            return;
        }

        if (Assert.isNotEmpty(g.getUserAgent())) {
            http.userAgent(g.getUserAgent());
        }

        String host = g.getProxyHost();
        int port = g.getProxyPort();

        if (host != null && !host.isEmpty() && port > 0) {
            // 检查 NO_PROXY 排除列表
            String url = http.url();
            if (url != null && isInNoProxy(url)) {
                return;
            }

            http.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));

            // 代理环境下绕过 TLS 证书验证（域名、时间、CA 等），
            // 避免企业代理或 MITM 代理导致的证书错误
            if (TRUST_ALL_SSL != null) {
                http.ssl(TRUST_ALL_SSL);
            }
        }
    }

    /**
     * 判断给定的 URL 是否匹配 NO_PROXY 排除列表
     */
    private static boolean isInNoProxy(String url) {
        List<String> list = noProxyList;
        if (list.isEmpty()) {
            return false;
        }

        String host = extractHost(url);
        if (host == null || host.isEmpty()) {
            return false;
        }

        String lowerHost = host.toLowerCase();
        for (String entry : list) {
            if (matchNoProxyEntry(lowerHost, entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 URL 中提取主机名
     */
    private static String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null) {
                return host;
            }
            // 对于没有协议前缀的 URL，尝试解析
            if (!url.contains("://")) {
                uri = URI.create("http://" + url);
                return uri.getHost();
            }
        } catch (Exception e) {
            // 忽略解析异常
        }
        return null;
    }

    /**
     * 匹配单个 NO_PROXY 条目
     *
     * <p>支持以下匹配规则：</p>
     * <ul>
     *   <li>精确匹配：example.com 匹配 example.com</li>
     *   <li>域名后缀匹配：.example.com 匹配 sub.example.com</li>
     *   <li>IP 地址精确匹配：127.0.0.1 匹配 127.0.0.1</li>
     *   <li>通配符 * 匹配所有</li>
     * </ul>
     */
    private static boolean matchNoProxyEntry(String lowerHost, String entry) {
        if (entry == null || entry.isEmpty()) {
            return false;
        }

        String lowerEntry = entry.toLowerCase().trim();

        // 通配符 *
        if ("*".equals(lowerEntry)) {
            return true;
        }

        // 精确匹配
        if (lowerHost.equals(lowerEntry)) {
            return true;
        }

        // 域名后缀匹配（.example.com 匹配 sub.example.com）
        if (lowerEntry.startsWith(".")) {
            return lowerHost.endsWith(lowerEntry) || lowerHost.equals(lowerEntry.substring(1));
        }

        return false;
    }

    /**
     * 解析 NO_PROXY 字符串为列表
     */
    private static List<String> parseNoProxy(String noProxy) {
        if (noProxy == null || noProxy.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = noProxy.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}