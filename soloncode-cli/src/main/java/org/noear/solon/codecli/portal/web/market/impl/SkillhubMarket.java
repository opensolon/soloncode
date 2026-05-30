package org.noear.solon.codecli.portal.web.market.impl;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.portal.web.market.Market;
import org.noear.solon.codecli.portal.web.market.MarketDetail;
import org.noear.solon.codecli.portal.web.market.MarketItem;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * SkillHub 市场适配器 — 对接 skillhub.cn（ClawHub 中国本土化镜像）。
 *
 * <p>搜索、详情、下载全部使用 skillhub.cn 自有 API（api.skillhub.cn）。</p>
 *
 * @author noear 2026/5/30 created
 */
public class SkillhubMarket implements Market {

    private static final Logger LOG = LoggerFactory.getLogger(SkillhubMarket.class);

    private static final String BASE_URL = "https://api.skillhub.cn";
    private static final String USER_AGENT = "SolonCode/1.0";

    @Override
    public String name() {
        return "skillhub.cn";
    }

    @Override
    public String description() {
        return "专为中国用户优化的技能社区";
    }

    // ==================== 列表与搜索 ====================

    @Override
    public Result<List<MarketItem>> trending(int limit) {
        try {
            String url = BASE_URL + "/api/v1/search?q=&limit=" + limit;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            List<MarketItem> items = parseResults(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("SkillhubMarket.trending error: {}", e.getMessage());
            return Result.failure("获取热门技能失败: " + e.getMessage());
        }
    }

    @Override
    public Result<List<MarketItem>> search(String query, int limit) {
        if (Assert.isEmpty(query)) {
            return trending(limit);
        }

        try {
            String url = BASE_URL + "/api/v1/search?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&limit=" + limit;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            List<MarketItem> items = parseResults(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("SkillhubMarket.search error: {}", e.getMessage());
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
            // 使用 skillhub 详情接口: GET /api/v1/skills/{slug}
            String url = BASE_URL + "/api/v1/skills/" + URLEncoder.encode(slug, "UTF-8");
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                ONode msgNode = root.get("message");
                String errorMsg = (msgNode != null && !msgNode.isNull()) ? msgNode.getString() : "技能不存在";
                return Result.failure(errorMsg);
            }

            // 解析 skillhub 详情响应
            // 响应结构: { skill: { slug, displayName, summary, summary_zh, stats: { installs, stars, downloads } },
            //             owner: { handle, displayName }, latestVersion: { version } }
            ONode skillNode = root.get("skill");
            if (skillNode == null) {
                return Result.failure("技能不存在: " + slug);
            }

            String resolvedSlug = getStringValue(skillNode, "slug");
            String displayName = getStringValue(skillNode, "displayName");
            String summary = firstNonEmpty(
                    getStringValue(skillNode, "summary_zh"),
                    getStringValue(skillNode, "summary"));

            long installs = 0;
            long stars = 0;
            ONode statsNode = skillNode.get("stats");
            if (statsNode != null) {
                installs = getLongValue(statsNode, "installs");
                stars = getLongValue(statsNode, "stars");
            }

            String ownerHandle = null;
            ONode ownerNode = root.get("owner");
            if (ownerNode != null) {
                ownerHandle = getStringValue(ownerNode, "handle");
            }

            String latestVersion = null;
            ONode versionNode = root.get("latestVersion");
            if (versionNode != null) {
                latestVersion = getStringValue(versionNode, "version");
            }

            MarketDetail detail = new MarketDetail()
                    .slug(resolvedSlug)
                    .displayName(displayName)
                    .summary(summary)
                    .description(summary)
                    .ownerHandle(ownerHandle)
                    .installs(installs)
                    .stars(stars)
                    .installSlug(resolvedSlug);

            return Result.succeed(detail);
        } catch (Exception e) {
            LOG.warn("SkillhubMarket.detail error: {}", e.getMessage());
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
                return Result.failure("技能不存在: " + detailResult.getDescription());
            }

            String displayName = detailResult.getData().getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = slug;
            }

            // 使用 skillhub 自己的下载接口: GET /api/v1/download?slug={slug}
            String downloadUrl = BASE_URL + "/api/v1/download?slug="
                    + URLEncoder.encode(slug, "UTF-8");

            Files.createDirectories(skillsDir);

            Path tempZip = Files.createTempFile("skill-", ".zip");
            try {
                try (HttpResponse httpResp = HttpUtils.http(downloadUrl)
                        .header("User-Agent", USER_AGENT)
                        .timeout(30000)
                        .exec("GET")) {

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

                LOG.info("SkillhubMarket.install: {} -> {}", slug, targetDir);
                return Result.succeed(displayName);
            } finally {
                Files.deleteIfExists(tempZip);
            }

        } catch (Exception e) {
            LOG.warn("SkillhubMarket.install error: {}", e.getMessage(), e);
            return Result.failure("安装失败: " + e.getMessage());
        }
    }

    // ==================== 内部工具方法 ====================

    private String httpGet(String url) throws Exception {
        return HttpUtils.http(url)
                .header("User-Agent", USER_AGENT)
                .timeout(15000)
                .get();
    }

    /**
     * 解析 skillhub.cn 搜索 API 返回的 results 数组。
     *
     * <p>SkillHub 返回的字段与 ClawHub 不同：
     * 统计字段是平铺的（installs, stars, downloads）而非嵌套在 stats 对象中；
     * 所有者字段为 owner_name 而非 ownerHandle。</p>
     */
    private List<MarketItem> parseResults(ONode root) {
        ONode resultsNode = root.get("results");
        if (resultsNode == null || !resultsNode.isArray()) {
            return Collections.emptyList();
        }

        List<MarketItem> result = new ArrayList<>();
        for (ONode node : resultsNode.getArray()) {
            MarketItem item = new MarketItem()
                    .slug(getStringValue(node, "slug"))
                    .name(getStringValue(node, "slug"))
                    .displayName(getStringValue(node, "displayName"))
                    .summary(getStringValue(node, "summary"))
                    .description(firstNonEmpty(
                            getStringValue(node, "description_zh"),
                            getStringValue(node, "description")))
                    .ownerHandle(getStringValue(node, "owner_name"))
                    .url(firstNonEmpty(
                            getStringValue(node, "url"),
                            "https://skillhub.cn/skills/" + getStringValue(node, "slug")))
                    .installs(getLongValue(node, "installs"))
                    .stars(getLongValue(node, "stars"));

            result.add(item);
        }
        return result;
    }

    private String getStringValue(ONode node, String key) {
        ONode child = node.get(key);
        return (child != null && !child.isNull()) ? child.getString() : null;
    }

    private long getLongValue(ONode node, String key) {
        ONode child = node.get(key);
        return (child != null && !child.isNull()) ? child.getLong() : 0;
    }

    /**
     * 返回第一个非空、非空的字符串
     */
    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
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
