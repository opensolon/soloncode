package org.noear.solon.codecli.market.impl;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.market.Market;
import org.noear.solon.codecli.market.MarketDetail;
import org.noear.solon.codecli.market.MarketItem;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ClawHub 市场适配器 — 对接 clawhub.ai API。
 *
 * @author noear 2026/5/28 created
 */
public class ClawhubMarket implements Market {

    private static final Logger LOG = LoggerFactory.getLogger(ClawhubMarket.class);

    private static final String BASE_URL = "https://clawhub.ai";
    private static final String USER_AGENT = "SolonCode/1.0";

    @Override
    public String name() {
        return "clawhub.ai";
    }

    @Override
    public String description() {
        return "";
    }

    // ==================== 列表与搜索 ====================

    @Override
    public Result<List<MarketItem>> trending(int limit) {
        try {
            String url = BASE_URL + "/api/v1/trending?kind=skills&limit=" + limit;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            List<MarketItem> items = parseItems(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.trending error: {}", e.getMessage());
            return Result.failure("获取热门技能失败: " + e.getMessage());
        }
    }

    @Override
    public Result<List<MarketItem>> search(String query, int limit) {
        if (Assert.isEmpty(query)) {
            return trending(limit);
        }

        try {
            String url = BASE_URL + "/api/v1/search?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            // 搜索 API 返回 JSON 数组，直接解析
            if (root.isArray()) {
                return Result.succeed(parseNodeArray(root));
            } else if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            } else {
                return Result.succeed(parseItems(root));
            }
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.search error: {}", e.getMessage());
            return Result.failure("搜索技能失败: " + e.getMessage());
        }
    }

    // ==================== 详情 ====================

    @Override
    public Result<MarketDetail> detail(String slug) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        try {
            String url = BASE_URL + "/api/v1/skills/" + java.net.URLEncoder.encode(slug, "UTF-8");
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            ONode skillNode = root.get("skill");
            if (skillNode == null || skillNode.isNull()) {
                return Result.failure("技能不存在: " + slug);
            }

            MarketDetail detail = new MarketDetail()
                    .slug(getStringValue(skillNode, "slug"))
                    .displayName(getStringValue(skillNode, "displayName"))
                    .summary(getStringValue(skillNode, "summary"))
                    .description(getStringValue(skillNode, "description"))
                    .ownerHandle(getStringValue(skillNode, "ownerHandle"))
                    .installSlug(getStringValue(skillNode, "slug"));

            ONode statsNode = skillNode.get("stats");
            if (statsNode != null && !statsNode.isNull()) {
                detail.installs(getLongValue(statsNode, "installsCurrent"));
                detail.stars(getLongValue(statsNode, "stars"));
            }

            return Result.succeed(detail);
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.detail error: {}", e.getMessage());
            return Result.failure("获取技能详情失败: " + e.getMessage());
        }
    }

    // ==================== 安装 ====================

    @Override
    public Result<String> install(String slug, Path skillsDir) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        slug = slug.replaceAll("[^a-zA-Z0-9._-]", "");
        if (slug.isEmpty()) {
            return Result.failure("Invalid slug");
        }

        try {
            Result<MarketDetail> detailResult = detail(slug);
            if (detailResult.getCode() != 200) {
                // detail() 已经返回了具体的错误描述，直接透传，不要再次包装
                return Result.failure(detailResult.getDescription());
            }

            String displayName = detailResult.getData().getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = slug;
            }

            String downloadUrl = BASE_URL + "/api/v1/download?slug="
                    + java.net.URLEncoder.encode(slug, "UTF-8");

            Files.createDirectories(skillsDir);

            Path tempZip = Files.createTempFile("skill-", ".zip");
            try {
                HttpUtils httpForDownload = HttpUtils.http(downloadUrl)
                        .header("User-Agent", USER_AGENT)
                        .timeout(30000);
                ProxyConfig.applyIfNeeded(httpForDownload);
                try (HttpResponse httpResp = httpForDownload.exec("GET")) {

                    byte[] zipBytes = httpResp.bodyAsBytes();
                    if (zipBytes == null || zipBytes.length == 0) {
                        return Result.failure("下载技能包失败: 返回内容为空");
                    }
                    Files.write(tempZip, zipBytes);
                }

                if (Files.size(tempZip) == 0) {
                    return Result.failure("下载技能包失败: 文件为空");
                }

                Path targetDir = skillsDir.resolve(slug);
                if (Files.exists(targetDir)) {
                    deleteDirectory(targetDir);
                }

                unzipToDirectory(tempZip, targetDir);

                LOG.info("ClawhubMarket.install: {} -> {}", slug, targetDir);
                return Result.succeed(displayName);
            } finally {
                Files.deleteIfExists(tempZip);
            }

        } catch (Exception e) {
            LOG.warn("ClawhubMarket.install error: {}", e.getMessage(), e);
            return Result.failure("安装失败: " + e.getMessage());
        }
    }

    // ==================== 内部工具方法 ====================

    private String httpGet(String url) throws Exception {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try {
                HttpUtils http = HttpUtils.http(url)
                        .header("User-Agent", USER_AGENT)
                        .timeout(15000);
                ProxyConfig.applyIfNeeded(http);
                return http.get();
            } catch (Exception e) {
                lastEx = e;
                LOG.warn("httpGet attempt {} failed: {}", i + 1, e.getMessage());
                if (i < 2) {
                    Thread.sleep(1000);
                }
            }
        }
        throw lastEx;
    }

    private List<MarketItem> parseItems(ONode root) {
        ONode itemsNode = root.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            return parseNodeArray(itemsNode);
        }
        return Collections.emptyList();
    }

    private List<MarketItem> parseNodeArray(ONode arrayNode) {
        List<MarketItem> result = new ArrayList<>();
        for (ONode node : arrayNode.getArray()) {
            String slug = getStringValue(node, "slug");

            // 构造技能介绍页 URL
            String canonicalUrl = getStringValue(node, "canonicalUrl");
            String detailUrl;
            String ownerHandle = null;

            if (canonicalUrl != null) {
                // 新 trending API 格式：canonicalUrl 包含完整路径
                detailUrl = BASE_URL + canonicalUrl;
                ONode publisher = node.get("publisher");
                if (publisher != null && !publisher.isNull()) {
                    ownerHandle = getStringValue(publisher, "handle");
                }
            } else {
                // 旧格式（搜索 API）：通过 ownerHandle + slug 构造
                ownerHandle = getStringValue(node, "ownerHandle");
                detailUrl = (ownerHandle != null)
                        ? BASE_URL + "/" + ownerHandle + "/skills/" + slug
                        : BASE_URL + "/skills/" + slug;
            }

            MarketItem item = new MarketItem()
                    .slug(slug)
                    .name(slug)
                    .displayName(getStringValue(node, "displayName"))
                    .summary(getStringValue(node, "summary"))
                    .description(getStringValue(node, "description"))
                    .ownerHandle(ownerHandle)
                    .url(detailUrl);

            // 解析安装量：新格式 metrics.lifetimeInstalls
            ONode metricsNode = node.get("metrics");
            if (metricsNode != null && !metricsNode.isNull()) {
                item.installs(getLongValue(metricsNode, "lifetimeInstalls"));
            }

            item.version(parseVersion(node));

            result.add(item);
        }
        return result;
    }

    /**
     * 解析版本号：兼容平铺字段（version / latestVersion）与嵌套对象（latestVersion.version）。
     * 取不到时返回 null，前端据此决定是否显示。
     */
    private String parseVersion(ONode node) {
        String v = getStringValue(node, "version");
        if (v != null && !v.isEmpty()) return v;
        ONode latest = node.get("latestVersion");
        if (latest != null && !latest.isNull()) {
            if (latest.isObject()) {
                return getStringValue(latest, "version");
            }
            return latest.getString();
        }
        return null;
    }

    private String getStringValue(ONode node, String key) {
        ONode child = node.get(key);
        return (child != null && !child.isNull()) ? child.getString() : null;
    }

    private long getLongValue(ONode node, String key) {
        ONode child = node.get(key);
        return (child != null && !child.isNull()) ? child.getLong() : 0;
    }

    private void deleteDirectory(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                    }
                });
    }

    private void unzipToDirectory(Path zipFile, Path targetDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                if (!entryPath.startsWith(targetDir.normalize())) {
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
    }
}
