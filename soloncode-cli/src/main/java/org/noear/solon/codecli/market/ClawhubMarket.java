package org.noear.solon.codecli.market;

import org.noear.snack4.ONode;
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
 * <p>提供热门列表、搜索、详情查询和 zip 下载安装能力。</p>
 *
 * @author noear 2026/5/28 created
 */
public class ClawhubMarket implements Market {

    private static final Logger LOG = LoggerFactory.getLogger(ClawhubMarket.class);

    private static final String BASE_URL = "https://clawhub.ai";
    private static final String USER_AGENT = "SolonCode/1.0";

    // ==================== 列表与搜索 ====================

    @Override
    public Result<List<Map<String, Object>>> trending(int limit) {
        try {
            String url = BASE_URL + "/api/v1/skills?limit=" + limit + "&sort=trending";
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            List<Map<String, Object>> items = parseItems(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.trending error: {}", e.getMessage());
            return Result.failure("获取热门技能失败: " + e.getMessage());
        }
    }

    @Override
    public Result<List<Map<String, Object>>> search(String query, int limit) {
        if (Assert.isEmpty(query)) {
            return trending(limit);
        }

        try {
            String url = BASE_URL + "/api/v1/search?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            // 搜索结果可能在 results 或 items 字段中
            List<Map<String, Object>> items;
            ONode resultsNode = root.get("results");
            if (resultsNode != null && resultsNode.isArray()) {
                items = parseNodeArray(resultsNode);
            } else {
                items = parseItems(root);
            }

            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.search error: {}", e.getMessage());
            return Result.failure("搜索技能失败: " + e.getMessage());
        }
    }

    // ==================== 详情 ====================

    @Override
    public Result<Map<String, Object>> detail(String slug) {
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

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("slug", getStringValue(skillNode, "slug"));
            detail.put("displayName", getStringValue(skillNode, "displayName"));
            detail.put("summary", getStringValue(skillNode, "summary"));
            detail.put("description", getStringValue(skillNode, "description"));
            detail.put("ownerHandle", getStringValue(skillNode, "ownerHandle"));

            // stats
            ONode statsNode = skillNode.get("stats");
            if (statsNode != null && !statsNode.isNull()) {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("installsCurrent", getLongValue(statsNode, "installsCurrent"));
                stats.put("stars", getLongValue(statsNode, "stars"));
                detail.put("stats", stats);
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

        // 清理 slug，防止路径注入
        slug = slug.replaceAll("[^a-zA-Z0-9._-]", "");
        if (slug.isEmpty()) {
            return Result.failure("Invalid slug");
        }

        try {
            // 1. 获取技能详情，确认 slug 有效 + 获取 displayName
            Result<Map<String, Object>> detailResult = detail(slug);
            if (detailResult.getCode() != 200) {
                return Result.failure("技能不存在: " + detailResult.getDescription());
            }

            Map<String, Object> detailData = detailResult.getData();
            String displayName = (String) detailData.getOrDefault("displayName", slug);
            if (displayName == null || displayName.isEmpty()) {
                displayName = slug;
            }

            // 2. 下载 zip 包
            String downloadUrl = BASE_URL + "/api/v1/download?slug="
                    + java.net.URLEncoder.encode(slug, "UTF-8");

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

                // 3. 解压到 skills/<slug>/ 目录
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
        return HttpUtils.http(url)
                .header("User-Agent", USER_AGENT)
                .timeout(15000)
                .get();
    }

    /**
     * 从 ONode 中解析 items 数组为 Map 列表
     */
    private List<Map<String, Object>> parseItems(ONode root) {
        ONode itemsNode = root.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            return parseNodeArray(itemsNode);
        }
        return Collections.emptyList();
    }

    /**
     * 将 ONode 数组统一转为标准化的 Map 列表
     */
    private List<Map<String, Object>> parseNodeArray(ONode arrayNode) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ONode node : arrayNode.getArray()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slug", getStringValue(node, "slug"));
            item.put("name", getStringValue(node, "slug")); // 兼容：name = slug
            item.put("displayName", getStringValue(node, "displayName"));
            item.put("summary", getStringValue(node, "summary"));
            item.put("description", getStringValue(node, "description"));
            item.put("ownerHandle", getStringValue(node, "ownerHandle"));

            // owner 对象
            ONode ownerNode = node.get("owner");
            if (ownerNode != null && !ownerNode.isNull()) {
                Map<String, Object> owner = new LinkedHashMap<>();
                owner.put("handle", getStringValue(ownerNode, "handle"));
                item.put("owner", owner);
            }

            // stats 对象
            ONode statsNode = node.get("stats");
            if (statsNode != null && !statsNode.isNull()) {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("installsCurrent", getLongValue(statsNode, "installsCurrent"));
                stats.put("stars", getLongValue(statsNode, "stars"));
                item.put("stats", stats);
            }

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

                // 安全检查：防止 zip slip
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
