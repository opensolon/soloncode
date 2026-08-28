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
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ModelScope 魔搭市场适配器 — 对接 modelscope.cn OpenAPI 技能广场。
 *
 * <p>搜索、详情使用 ModelScope OpenAPI；下载通过技能文件下载接口。</p>
 *
 * @author noear 2026/7/28 created
 */
public class ModelscopeMarket implements Market {

    private static final Logger LOG = LoggerFactory.getLogger(ModelscopeMarket.class);

    private static final String BASE_URL = "https://modelscope.cn/openapi/v1";
    private static final String SKILLS_PAGE_URL = "https://www.modelscope.cn/skills";
    private static final String USER_AGENT = "SolonCode/1.0";

    @Override
    public String name() {
        return "modelscope.cn";
    }

    @Override
    public String description() {
        return "魔搭社区技能广场";
    }

    // ==================== 列表与搜索 ====================

    @Override
    public Result<List<MarketItem>> trending(int limit) {
        try {
            String url = BASE_URL + "/skills?page_size=" + limit + "&sort=downloads";
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (!root.get("success").getBoolean()) {
                return Result.failure("获取热门技能失败");
            }

            List<MarketItem> items = parseSkills(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("ModelscopeMarket.trending error: {}", e.getMessage());
            return Result.failure("获取热门技能失败: " + e.getMessage());
        }
    }

    @Override
    public Result<List<MarketItem>> search(String query, int limit) {
        if (Assert.isEmpty(query)) {
            return trending(limit);
        }

        try {
            String url = BASE_URL + "/skills?search=" + URLEncoder.encode(query, "UTF-8")
                    + "&page_size=" + limit;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (!root.get("success").getBoolean()) {
                return Result.failure("搜索技能失败");
            }

            List<MarketItem> items = parseSkills(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("ModelscopeMarket.search error: {}", e.getMessage());
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
            // ModelScope 技能 ID 格式: @author/skill_name, 无需 URL 编码 @ 和 /
            String url = BASE_URL + "/skills/" + slug;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (!root.get("success").getBoolean()) {
                return Result.failure("技能不存在: " + slug);
            }

            // 详情响应: data 直接是 SkillDetail 对象
            ONode data = root.get("data");
            if (data == null || data.isNull()) {
                return Result.failure("技能不存在: " + slug);
            }

            String skillId = getStringValue(data, "id");
            String displayName = getStringValue(data, "display_name");
            String description = getStringValue(data, "description");
            String developer = getStringValue(data, "developer");
            String sourceUrl = getStringValue(data, "source_url");
            long downloads = getLongValue(data, "downloads");
            long viewCount = getLongValue(data, "view_count");

            // 优先使用中文本地化描述
            ONode locales = data.get("locales");
            String bestDescription = description;
            if (locales != null && !locales.isNull()) {
                ONode zh = locales.get("zh");
                if (zh != null && !zh.isNull()) {
                    String zhDesc = getStringValue(zh, "description");
                    if (zhDesc != null && !zhDesc.isEmpty()) {
                        bestDescription = zhDesc;
                    }
                }
            }

            MarketDetail detail = new MarketDetail()
                    .slug(skillId)
                    .displayName(displayName)
                    .summary(bestDescription)
                    .description(bestDescription)
                    .ownerHandle(developer)
                    .installs(downloads)
                    .stars(viewCount)
                    .installSlug(skillId)
                    .sourceUrl(sourceUrl);

            return Result.succeed(detail);
        } catch (Exception e) {
            LOG.warn("ModelscopeMarket.detail error: {}", e.getMessage());
            return Result.failure("获取技能详情失败: " + e.getMessage());
        }
    }

    // ==================== 安装 ====================

    @Override
    public Result<String> install(String slug, Path skillsDir) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        // 保留 @ 和 / 用于 ModelScope 技能 ID
        String safeSlug = slug.replaceAll("[^a-zA-Z0-9@._/-]", "");
        if (safeSlug.isEmpty()) {
            return Result.failure("Invalid slug");
        }

        try {
            Result<MarketDetail> detailResult = detail(slug);
            if (detailResult.getCode() != 200) {
                return Result.failure(detailResult.getDescription());
            }

            String displayName = detailResult.getData().getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = safeSlug;
            }

            // 主下载 URL: https://www.modelscope.cn/skills/{id}/archive/zip/master
            // 来自 install.sh 脚本中的实际下载模式
            String downloadUrl = SKILLS_PAGE_URL + "/" + safeSlug + "/archive/zip/master";

            // 备用下载 URL（GitHub 回退）
            String fallbackUrl = buildGitHubFallbackUrl(detailResult.getData().getSourceUrl());

            Files.createDirectories(skillsDir);

            Path tempZip = Files.createTempFile("skill-", ".zip");
            try {
                // 先尝试主下载 URL
                byte[] zipBytes = tryDownload(downloadUrl);

                // 主下载失败且有备用 URL 时尝试备用
                if (zipBytes == null && fallbackUrl != null) {
                    LOG.warn("ModelscopeMarket.install: 主下载失败，尝试 GitHub 回退: {}", fallbackUrl);
                    zipBytes = tryDownload(fallbackUrl);
                }

                if (zipBytes == null || zipBytes.length == 0) {
                    return Result.failure("下载技能包失败: 所有下载源均不可用");
                }

                Files.write(tempZip, zipBytes);

                // 将 @ 和 / 替换为 _ 作为目录名
                String dirName = safeSlug.replaceAll("[@/]", "_");
                Path targetDir = skillsDir.resolve(dirName);
                if (Files.exists(targetDir)) {
                    deleteDirectory(targetDir);
                }

                unzipToDirectory(tempZip, targetDir);

                LOG.info("ModelscopeMarket.install: {} -> {}", safeSlug, targetDir);
                return Result.succeed(displayName);
            } finally {
                Files.deleteIfExists(tempZip);
            }

        } catch (Exception e) {
            LOG.warn("ModelscopeMarket.install error: {}", e.getMessage(), e);
            return Result.failure("安装失败: " + e.getMessage());
        }
    }

    // ==================== 内部工具方法 ====================

    private String httpGet(String url) throws Exception {
        HttpUtils http = HttpUtils.http(url)
                .header("User-Agent", USER_AGENT)
                .timeout(15000);
        ProxyConfig.applyIfNeeded(http);
        return http.get();
    }

    /**
     * 解析 ModelScope 技能列表 API 返回的 skills 数组。
     *
     * <p>响应结构: { success: true, data: { skills: [...], total, page_number, page_size } }</p>
     */
    private List<MarketItem> parseSkills(ONode root) {
        ONode data = root.get("data");
        if (data == null || data.isNull()) {
            return Collections.emptyList();
        }

        ONode skillsNode = data.get("skills");
        if (skillsNode == null || !skillsNode.isArray()) {
            return Collections.emptyList();
        }

        List<MarketItem> result = new ArrayList<>();
        for (ONode node : skillsNode.getArray()) {
            String skillId = getStringValue(node, "id");
            String displayName = getStringValue(node, "display_name");
            String description = getStringValue(node, "description");
            String developer = getStringValue(node, "developer");
            long downloads = getLongValue(node, "downloads");
            long viewCount = getLongValue(node, "view_count");

            // 优先使用中文本地化描述
            ONode locales = node.get("locales");
            String bestDescription = description;
            if (locales != null && !locales.isNull()) {
                ONode zh = locales.get("zh");
                if (zh != null && !zh.isNull()) {
                    String zhDesc = getStringValue(zh, "description");
                    if (zhDesc != null && !zhDesc.isEmpty()) {
                        bestDescription = zhDesc;
                    }
                }
            }

            String detailUrl = SKILLS_PAGE_URL + "/" + skillId;

            MarketItem item = new MarketItem()
                    .slug(skillId)
                    .name(skillId)
                    .displayName(displayName)
                    .summary(bestDescription)
                    .description(bestDescription)
                    .ownerHandle(developer)
                    .url(detailUrl)
                    .installs(downloads)
                    .stars(viewCount);

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

    /**
     * 尝试从指定 URL 下载 zip 包，失败时返回 null 而非抛异常。
     */
    private byte[] tryDownload(String url) {
        try {
            HttpUtils http = HttpUtils.http(url)
                    .header("User-Agent", USER_AGENT)
                    .timeout(30000);
            ProxyConfig.applyIfNeeded(http);
            try (HttpResponse resp = http.exec("GET")) {
                int code = resp.code();
                if (code < 200 || code >= 300) {
                    LOG.warn("tryDownload: HTTP {} for {}", code, url);
                    return null;
                }
                byte[] bytes = resp.bodyAsBytes();
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                return bytes;
            }
        } catch (Exception e) {
            LOG.warn("tryDownload failed: {} - {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 从 source_url 构建 GitHub 下载回退 URL。
     *
     * <p>支持格式：
     * <ul>
     *   <li>https://github.com/owner/repo</li>
     *   <li>https://github.com/owner/repo.git</li>
     *   <li>https://github.com/owner/repo/tree/branch</li>
     * </ul>
     */
    private String buildGitHubFallbackUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return null;
        }

        // 只处理 GitHub 源
        if (!sourceUrl.startsWith("https://github.com/") && !sourceUrl.startsWith("https://www.github.com/")) {
            return null;
        }

        try {
            // 提取 owner/repo
            java.net.URL url = new java.net.URL(sourceUrl);
            String path = url.getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            // 移除 .git 后缀
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }

            // 处理分支路径: owner/repo/tree/branch -> owner/repo
            String[] parts = path.split("/");
            if (parts.length >= 2) {
                String owner = parts[0];
                String repo = parts[1];
                // 如果指定了分支，使用该分支；否则使用默认分支
                String branch = "HEAD";
                if (parts.length >= 4 && "tree".equals(parts[2])) {
                    branch = parts[3];
                }
                return "https://github.com/" + owner + "/" + repo + "/archive/refs/heads/" + branch + ".zip";
            }
        } catch (Exception e) {
            LOG.warn("buildGitHubFallbackUrl failed: {}", e.getMessage());
        }

        return null;
    }
}