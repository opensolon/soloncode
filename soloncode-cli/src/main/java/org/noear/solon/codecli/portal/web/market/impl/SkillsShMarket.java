package org.noear.solon.codecli.portal.web.market.impl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.noear.solon.codecli.portal.web.market.Market;
import org.noear.solon.codecli.portal.web.market.MarketDetail;
import org.noear.solon.codecli.portal.web.market.MarketItem;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
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
 * Skills.sh 市场适配器 — 通过爬取 HTML 页面获取技能列表。
 *
 * <p>不走 API 接口（需要 token），直接解析 HTML 页面获取数据。</p>
 *
 * @author noear 2026/5/29 created
 */
public class SkillsShMarket implements Market {

    private static final Logger LOG = LoggerFactory.getLogger(SkillsShMarket.class);

    private static final String BASE_URL = "https://www.skills.sh";
    private static final String USER_AGENT = "SolonCode/1.0";

    @Override
    public String name() {
        return "skills.sh";
    }

    @Override
    public String description() {
        return "";
    }

    // ==================== 列表与搜索 ====================

    @Override
    public Result<List<MarketItem>> trending(int limit) {
        try {
            String html = httpGet(BASE_URL + "/");
            List<MarketItem> items = parseHtmlItems(html, limit);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("SkillsShMarket.trending error: {}", e.getMessage());
            return Result.failure("获取热门技能失败: " + e.getMessage());
        }
    }

    @Override
    public Result<List<MarketItem>> search(String query, int limit) {
        if (Assert.isEmpty(query)) {
            return trending(limit);
        }

        try {
            // Skills.sh 的搜索页面
            String html = httpGet(BASE_URL + "/?q=" + java.net.URLEncoder.encode(query, "UTF-8"));
            List<MarketItem> items = parseHtmlItems(html, limit);

            // 如果搜索无结果，尝试从热门列表中过滤
            if (items.isEmpty()) {
                html = httpGet(BASE_URL + "/");
                List<MarketItem> all = parseHtmlItems(html, 500);
                String q = query.toLowerCase();
                for (MarketItem item : all) {
                    if (item.getSlug() != null && item.getSlug().toLowerCase().contains(q)) {
                        items.add(item);
                    } else if (item.getDisplayName() != null && item.getDisplayName().toLowerCase().contains(q)) {
                        items.add(item);
                    } else if (item.getOwnerHandle() != null && item.getOwnerHandle().toLowerCase().contains(q)) {
                        items.add(item);
                    }
                    if (items.size() >= limit) break;
                }
            }

            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("SkillsShMarket.search error: {}", e.getMessage());
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
            // 从热门列表中查找匹配的条目
            String html = httpGet(BASE_URL + "/");
            List<MarketItem> all = parseHtmlItems(html, 500);

            for (MarketItem item : all) {
                if (slug.equals(item.getSlug())) {
                    MarketDetail detail = new MarketDetail()
                            .slug(item.getSlug())
                            .displayName(item.getDisplayName())
                            .summary(item.getSummary())
                            .description(item.getDescription())
                            .ownerHandle(item.getOwnerHandle())
                            .installs(item.getInstalls())
                            .stars(item.getStars())
                            .installSlug(item.getOwnerHandle() != null
                                    ? item.getOwnerHandle() + "/" + item.getSlug()
                                    : item.getSlug());
                    return Result.succeed(detail);
                }
            }

            return Result.failure("技能不存在: " + slug);
        } catch (Exception e) {
            LOG.warn("SkillsShMarket.detail error: {}", e.getMessage());
            return Result.failure("获取技能详情失败: " + e.getMessage());
        }
    }

    // ==================== 安装 ====================

    @Override
    public Result<String> install(String slug, Path skillsDir) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        try {
            // 先查详情获取完整信息
            Result<MarketDetail> detailResult = detail(slug);
            if (detailResult.getCode() != 200) {
                return Result.failure("技能不存在: " + detailResult.getDescription());
            }

            MarketDetail detailData = detailResult.getData();
            String displayName = detailData.getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = slug;
            }

            // Skills.sh 技能来源于 GitHub，使用 npx skillsadd 或直接下载 GitHub repo
            // 这里通过 GitHub 的 zipball 接口下载
            String ownerRepo = detailData.getOwnerHandle();
            if (ownerRepo == null || ownerRepo.isEmpty()) {
                return Result.failure("无法确定技能的 GitHub 仓库");
            }

            // 安装 slug 清理
            String safeSlug = slug.replaceAll("[^a-zA-Z0-9._-]", "");
            if (safeSlug.isEmpty()) {
                return Result.failure("Invalid slug");
            }

            // 下载 GitHub 仓库 zip
            String downloadUrl = "https://github.com/" + ownerRepo + "/archive/refs/heads/main.zip";
            String body = httpGetString(downloadUrl);

            // 如果 main 分支不存在，尝试 master
            if (body == null || body.length() < 200) {
                downloadUrl = "https://github.com/" + ownerRepo + "/archive/refs/heads/master.zip";
                body = httpGetString(downloadUrl);
            }

            if (body == null || body.length() < 200) {
                return Result.failure("下载技能包失败: 无法从 GitHub 获取");
            }

            Files.createDirectories(skillsDir);

            Path tempZip = Files.createTempFile("skill-", ".zip");
            try {
                Files.write(tempZip, body.getBytes("ISO-8859-1"));

                if (Files.size(tempZip) == 0) {
                    return Result.failure("下载技能包失败: 文件为空");
                }

                Path targetDir = skillsDir.resolve(safeSlug);
                if (Files.exists(targetDir)) {
                    deleteDirectory(targetDir);
                }

                unzipToDirectory(tempZip, targetDir);

                LOG.info("SkillsShMarket.install: {} -> {}", safeSlug, targetDir);
                return Result.succeed(displayName);
            } finally {
                Files.deleteIfExists(tempZip);
            }

        } catch (Exception e) {
            LOG.warn("SkillsShMarket.install error: {}", e.getMessage(), e);
            return Result.failure("安装失败: " + e.getMessage());
        }
    }

    // ==================== 内部工具方法 ====================

    protected String httpGet(String url) throws Exception {
        return HttpUtils.http(url)
                .header("User-Agent", USER_AGENT)
                .timeout(15000)
                .get();
    }

    private String httpGetString(String url) {
        try {
            return HttpUtils.http(url)
                    .header("User-Agent", USER_AGENT)
                    .timeout(30000)
                    .get();
        } catch (Exception e) {
            LOG.warn("SkillsShMarket.httpGetString error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用 jsoup 解析 HTML 页面中的技能列表。
     *
     * <p>解析策略：先尝试查找结构化 HTML 元素（table/tr 或有序列表），
     * 若无结构化元素则按文本行回退解析。兼容 Next.js 服务端渲染和纯文本场景。</p>
     */
    List<MarketItem> parseHtmlItems(String html, int limit) {
        List<MarketItem> items = new ArrayList<>();
        if (html == null || html.isEmpty()) return items;

        Document doc = Jsoup.parse(html);

        // 策略一：尝试从 HTML 结构化元素中提取（table 行或列表项）
        if (tryParseStructured(doc, items, limit)) {
            return items;
        }

        // 策略二：回退到原始文本行解析（按原始 HTML 换行分割）
        String[] lines = html.split("\\r?\\n");
        for (String line : lines) {
            if (items.size() >= limit) break;
            parseLineItem(line, items);
        }

        return items;
    }

    /**
     * 从结构化 HTML 元素中解析技能列表
     */
    private boolean tryParseStructured(Document doc, List<MarketItem> items, int limit) {
        // 尝试 table 行
        Elements rows = doc.select("table tr");
        if (rows.size() > 1) {
            for (Element row : rows) {
                if (items.size() >= limit) break;
                Elements cells = row.select("td");
                if (cells.size() >= 3) {
                    MarketItem item = buildItemFromCells(cells);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            return !items.isEmpty();
        }

        // 尝试有序/无序列表项
        Elements lis = doc.select("ol > li, ul > li");
        if (!lis.isEmpty()) {
            for (Element li : lis) {
                if (items.size() >= limit) break;
                String line = li.text().trim();
                parseLineItem(line, items);
            }
            return !items.isEmpty();
        }

        // 尝试 div 或 a 链接列表（常见 SPA 渲染结果）
        Elements links = doc.select("a[href*=\"/skill/\"], a[href*=\"/skills/\"]");
        if (!links.isEmpty()) {
            for (Element a : links) {
                if (items.size() >= limit) break;
                String line = a.text().trim();
                parseLineItem(line, items);
            }
            return !items.isEmpty();
        }

        return false;
    }

    /**
     * 从 table 单元格构建 MarketItem
     */
    private MarketItem buildItemFromCells(Elements cells) {
        try {
            Element firstCell = cells.get(0);
            String slug = firstCell.text().trim();
            String ownerRepo = cells.get(1).text().trim();
            String installsStr = cells.size() > 2 ? cells.get(2).text().trim() : "0";

            // 尝试从 <a> 标签提取详情页 URL
            String href = firstCell.select("a").attr("href");
            String url = (href != null && !href.isEmpty())
                    ? (href.startsWith("http") ? href : BASE_URL + href)
                    : BASE_URL + "/skill/" + slug;

            // 过滤无效行（如表头）
            if (slug.isEmpty() || slug.equalsIgnoreCase("name")
                    || slug.equalsIgnoreCase("skill") || slug.equalsIgnoreCase("#")) {
                return null;
            }

            return new MarketItem()
                    .slug(slug)
                    .name(slug)
                    .displayName(slug)
                    .ownerHandle(ownerRepo)
                    .url(url)
                    .installs(parseInstallCount(installsStr))
                    .stars(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按文本行解析单条技能记录。
     * 支持格式: "1 find-skills vercel-labs/skills 1.7M" 或 "find-skills vercel-labs/skills 1.7M"
     */
    private void parseLineItem(String line, List<MarketItem> items) {
        line = line.trim();
        if (line.isEmpty()) return;

        try {
            // 带排名: "1 find-skills vercel-labs/skills 1.7M"
            String[] parts = line.split("\\s+");
            int offset = 0;

            // 如果第一个部分是纯数字，视为排名
            if (parts.length > 0 && parts[0].matches("\\d+")) {
                offset = 1;
            }

            if (parts.length < offset + 3) return;

            String slug = parts[offset];
            String ownerRepo = parts[offset + 1];
            String installsStr = parts[offset + 2];

            // 校验 slug 格式
            if (!slug.matches("[a-zA-Z0-9_-]+")) return;

            // 校验安装量格式
            if (!installsStr.matches("[\\d.,]+[KkMm]?")) return;

            MarketItem item = new MarketItem()
                    .slug(slug)
                    .name(slug)
                    .displayName(slug)
                    .ownerHandle(ownerRepo)
                    .url(BASE_URL + "/skill/" + slug)
                    .installs(parseInstallCount(installsStr))
                    .stars(0);

            items.add(item);
        } catch (Exception e) {
            // 跳过无法解析的行
        }
    }

    /**
     * 解析安装数字符串，如 "1.7M", "474.8K", "56.8K"
     */
    long parseInstallCount(String str) {
        if (str == null || str.isEmpty()) return 0;
        try {
            str = str.trim().replace(",", "");
            if (str.endsWith("M") || str.endsWith("m")) {
                return (long) (Double.parseDouble(str.substring(0, str.length() - 1)) * 1_000_000);
            } else if (str.endsWith("K") || str.endsWith("k")) {
                return (long) (Double.parseDouble(str.substring(0, str.length() - 1)) * 1_000);
            } else {
                return Long.parseLong(str);
            }
        } catch (Exception e) {
            return 0;
        }
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
