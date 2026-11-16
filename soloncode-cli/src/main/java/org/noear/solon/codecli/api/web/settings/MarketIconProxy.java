package org.noear.solon.codecli.api.web.settings;

import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.market.skill.SkillMarketSslSupplier;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * 市场图标代理 — 技能市场与连接器市场的条目图标统一由此拉取。
 *
 * <p>前端不直连任何市场域名（境外可达性、隐私、CSP 都不可控），一律请求本服务端点，
 * 由后端按白名单拉取图片并透传，附带一天浏览器缓存。</p>
 *
 * @author noear 2026/11/16 created
 */
final class MarketIconProxy {

    private static final Logger LOG = LoggerFactory.getLogger(MarketIconProxy.class);

    /**
     * 允许代理的域名（含其子域）：只放行已知市场的图床，避免被当成任意 URL 的开放代理。
     */
    private static final List<String> ALLOWED_HOSTS = Arrays.asList(
            // Smithery
            "smithery.ai",
            "run.tools",
            // 腾讯云 MCP 广场：腾讯图床 + GitHub 头像
            "developer.qcloudimg.com",
            "avatars.githubusercontent.com",
            "raw.githubusercontent.com",
            // SkillHub：技能图标存放在腾讯云 COS 加速域名与腾讯云静态资源域
            "skillhub.cn",
            "cos.accelerate.myqcloud.com",
            "cloudcache.tencent-cloud.com",
            // ClawHub
            "clawhub.ai");

    private MarketIconProxy() {
    }

    /**
     * 按白名单校验图标地址：仅接受 https，且 host 必须命中白名单（或其子域）。
     */
    static boolean isAllowed(String url) {
        if (Assert.isEmpty(url)) {
            return false;
        }

        String host;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            host = uri.getHost();
        } catch (Exception e) {
            return false;
        }

        if (Assert.isEmpty(host)) {
            return false;
        }

        String lowerHost = host.toLowerCase();
        for (String allowed : ALLOWED_HOSTS) {
            if (lowerHost.equals(allowed) || lowerHost.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拉取远端图标并写入响应；地址非法返回 400，拉取失败返回 404/502。
     */
    static void output(Context ctx, String url) throws IOException {
        if (!isAllowed(url)) {
            ctx.status(400);
            return;
        }

        try {
            HttpUtils http = HttpUtils.http(url)
                    .header("User-Agent", "SolonCode/1.0")
                    .header("Accept", "image/*")
                    .timeout(15000)
                    .ssl(SkillMarketSslSupplier.getInstance());
            ProxyConfig.applyIfNeeded(http);

            HttpResponse response = http.exec("GET");
            if (response.code() != 200) {
                ctx.status(404);
                return;
            }

            byte[] data = response.bodyAsBytes();
            String contentType = response.contentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                // 部分图床返回 application/octet-stream，按 png 兜底
                contentType = "image/png";
            }

            ctx.contentType(contentType);
            ctx.headerSet("Cache-Control", "public, max-age=86400");
            ctx.output(data);
        } catch (Exception e) {
            LOG.warn("marketIcon error: {}", e.getMessage());
            ctx.status(502);
        }
    }
}
