package org.noear.solon.codecli.market.skill.impl;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.market.MarketCategoryResource;
import org.noear.solon.codecli.market.skill.SkillMarket;
import org.noear.solon.codecli.market.skill.SkillMarketCategory;
import org.noear.solon.codecli.market.skill.SkillMarketDetail;
import org.noear.solon.codecli.market.skill.SkillMarketItem;
import org.noear.solon.codecli.market.skill.SkillMarketPage;
import org.noear.solon.codecli.market.skill.SkillMarketSslSupplier;
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
 * <p>搜索、详情、下载全部使用 skillhub.cn 自有 API（api.skillhub.cn）；分类清单是该站静态内容，
 * 内置为资源文件（{@link #CATEGORIES_RESOURCE}），与列表、搜索一样不依赖实时抓取。</p>
 *
 * @author noear 2026/5/30 created
 */
public class SkillhubSkillMarket implements SkillMarket {

    private static final Logger LOG = LoggerFactory.getLogger(SkillhubSkillMarket.class);

    private static final String BASE_URL = "https://api.skillhub.cn";
    /**
     * 网站技能列表真实接口（分页 + 分类 + 关键字，返回 {code,data:{skills[],total}}）。
     *
     * <p>不能用 /api/v1/search 代替：那个接口无分页、limit 封顶 100，且 category 等参数会被静默忽略。</p>
     */
    private static final String SKILLS_PAGE_URL = BASE_URL + "/api/skills";
    /** 分类清单接口（13 个一级分类，带 key/name/nameEn/sortOrder）——仅用于生成内置快照，运行期不调用 */
    private static final String CATEGORIES_RESOURCE = "skillhub-categories.json";
    /** /api/skills 的 pageSize 上限（超过返回空） */
    private static final int MAX_PAGE_SIZE = 100;
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
    public Result<List<SkillMarketItem>> trending(int limit) {
        Result<SkillMarketPage> result = page(null, null, 1, limit);
        if (result.getCode() == 200) {
            return Result.succeed(result.getData().getItems());
        }

        // 新接口异常时退回旧搜索接口，保证列表仍可用（旧接口无分类、无分页）
        return legacyTrending(limit);
    }

    @Override
    public Result<List<SkillMarketItem>> search(String query, int limit) {
        return search(query, limit, null);
    }

    @Override
    public Result<List<SkillMarketItem>> search(String query, int limit, String category) {
        Result<SkillMarketPage> result = page(query, category, 1, limit);
        if (result.getCode() == 200) {
            return Result.succeed(result.getData().getItems());
        }

        // 旧接口不支持分类，仅在未指定分类时退回
        if (Assert.isEmpty(category)) {
            return legacySearch(query, limit);
        }

        return Result.failure(result.getDescription());
    }

    /**
     * 原生分页：{@code /api/skills} 的 {@code page/pageSize} 直接透传，翻页不重复拉前面的数据。
     *
     * <p>失败时未指定分类的前提下退回旧搜索接口（一次性取前 pageSize 条，无分页）。</p>
     */
    @Override
    public Result<SkillMarketPage> page(String query, String category, int page, int pageSize) {
        Result<SkillMarketPage> result = listPage(query, category, page, pageSize);
        if (result.getCode() == 200) {
            return result;
        }

        if (Assert.isEmpty(category) && Math.max(page, 1) == 1) {
            Result<List<SkillMarketItem>> legacy = legacySearch(query, pageSize);
            if (legacy.getCode() == 200) {
                return Result.succeed(new SkillMarketPage()
                        .page(1)
                        .items(legacy.getData())
                        .hasMore(false));
            }
            return Result.failure(legacy.getDescription());
        }

        return result;
    }

    // ==================== 分类 ====================

    /**
     * 获取分类清单，来自内置资源（{@link #CATEGORIES_RESOURCE}），运行期不请求网络。
     *
     * <p>分类名取源站 {@code name}（中文站为中文），原样展示、不做翻译；分类条目数不再展示，
     * 因此不再逐分类请求 {@code /api/skills} 的 total（原先 13 个并发请求只为拿数字）。</p>
     */
    @Override
    public List<SkillMarketCategory> categories() {
        List<MarketCategoryResource.Entry> entries =
                MarketCategoryResource.load(SkillhubSkillMarket.class, CATEGORIES_RESOURCE);

        List<SkillMarketCategory> list = new ArrayList<>(entries.size());
        for (MarketCategoryResource.Entry entry : entries) {
            list.add(new SkillMarketCategory().id(entry.getId()).name(entry.getName()));
        }
        return list;
    }

    // ==================== 列表与搜索（新版 /api/skills） ====================

    /**
     * 拉取一页技能。query 为空表示浏览，category 为空表示全部分类。
     */
    private Result<SkillMarketPage> listPage(String query, String category, int page, int pageSize) {
        try {
            int currentPage = Math.max(page, 1);
            int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

            StringBuilder url = new StringBuilder(SKILLS_PAGE_URL)
                    .append("?page=").append(currentPage)
                    .append("&pageSize=").append(size);

            if (query != null && !query.isEmpty()) {
                url.append("&keyword=").append(URLEncoder.encode(query, "UTF-8"));
            }
            if (category != null && !category.isEmpty()) {
                url.append("&category=").append(URLEncoder.encode(category, "UTF-8"));
            }

            ONode root = ONode.ofJson(httpGet(url.toString()));

            ONode codeNode = root.get("code");
            if (codeNode != null && !codeNode.isNull() && codeNode.getInt() != 0) {
                return Result.failure(firstNonEmpty(getStringValue(root, "message"), "获取技能列表失败"));
            }

            ONode data = root.get("data");
            ONode skillsNode = (data != null && !data.isNull()) ? data.get("skills") : null;
            if (skillsNode == null || !skillsNode.isArray()) {
                return Result.succeed(new SkillMarketPage().page(currentPage).hasMore(false));
            }

            List<SkillMarketItem> items = new ArrayList<>();
            for (ONode node : skillsNode.getArray()) {
                items.add(parsePageItem(node));
            }

            // total 用于「加载更多（剩余 N 个）」与 hasMore 判定；缺省时按「本页是否拉满」推断
            int total = (data.get("total") != null && !data.get("total").isNull()) ? data.get("total").getInt() : -1;
            boolean hasMore = (total >= 0)
                    ? currentPage * size < total
                    : items.size() >= size;

            return Result.succeed(new SkillMarketPage()
                    .page(currentPage)
                    .items(items)
                    .total(total)
                    .hasMore(hasMore && !items.isEmpty()));
        } catch (Exception e) {
            LOG.warn("SkillhubMarket.listPage error: {}", e.getMessage());
            return Result.failure("获取技能列表失败: " + e.getMessage());
        }
    }

    /**
     * 解析 /api/skills 的条目。
     *
     * <p>该接口字段与 /api/v1/search 不同：没有 displayName/summary，
     * 但 name 本身就是可读名称（多为中文）、description_zh 为中文简介、iconUrl 为驼峰。</p>
     */
    private SkillMarketItem parsePageItem(ONode node) {
        String slug = getStringValue(node, "slug");
        String name = getStringValue(node, "name");

        return new SkillMarketItem()
                .slug(slug)
                .name(name)
                .displayName(firstNonEmpty(name, slug))
                .summary(firstNonEmpty(
                        getStringValue(node, "summary_zh"),
                        getStringValue(node, "description_zh"),
                        getStringValue(node, "summary"),
                        getStringValue(node, "description")))
                .description(firstNonEmpty(
                        getStringValue(node, "description_zh"),
                        getStringValue(node, "description")))
                .ownerHandle(firstNonEmpty(
                        getStringValue(node, "ownerName"),
                        parseNamespaceHandle(node)))
                .url("https://skillhub.cn/skills/" + slug)
                .installs(getLongValue(node, "installs"))
                .stars(getLongValue(node, "stars"))
                .version(parseVersion(node))
                .iconUrl(parseIcon(node));
    }

    private String parseNamespaceHandle(ONode node) {
        ONode namespaceNode = node.get("namespace");
        if (namespaceNode == null || namespaceNode.isNull()) {
            return null;
        }
        return getStringValue(namespaceNode, "handle");
    }

    // ==================== 列表与搜索（旧版 /api/v1/search，仅作降级） ====================

    private Result<List<SkillMarketItem>> legacyTrending(int limit) {
        try {
            String url = BASE_URL + "/api/v1/search?q=&limit=" + limit;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            List<SkillMarketItem> items = parseResults(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("SkillhubMarket.trending error: {}", e.getMessage());
            return Result.failure("获取热门技能失败: " + e.getMessage());
        }
    }

    private Result<List<SkillMarketItem>> legacySearch(String query, int limit) {
        if (Assert.isEmpty(query)) {
            return legacyTrending(limit);
        }

        try {
            String url = BASE_URL + "/api/v1/search?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&limit=" + limit;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            List<SkillMarketItem> items = parseResults(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("SkillhubMarket.search error: {}", e.getMessage());
            return Result.failure("搜索技能失败: " + e.getMessage());
        }
    }

    // ==================== 详情 ====================

    @Override
    public Result<SkillMarketDetail> detail(String slug) {
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
            // 响应结构: { skill: { slug, displayName, summary, summary_zh, overviewMd, category, subCategories,
            //             stats: { installs, stars, downloads } },
            //             owner: { handle, displayName }, publisher: { verified },
            //             latestVersion: { version, changelog },
            //             securityReports: { <scanner>: { status, statusText } } }
            ONode skillNode = root.get("skill");
            if (skillNode == null) {
                return Result.failure("技能不存在: " + slug);
            }

            String resolvedSlug = getStringValue(skillNode, "slug");
            String displayName = getStringValue(skillNode, "displayName");
            String summary = firstNonEmpty(
                    getStringValue(skillNode, "summary_zh"),
                    getStringValue(skillNode, "summary"));
            // 完整介绍（markdown）；为空时回退摘要，保证预览不空白
            String overview = firstNonEmpty(
                    getStringValue(skillNode, "overviewMd"),
                    summary);

            String category = getStringValue(skillNode, "category");
            ONode subCategoriesNode = skillNode.get("subCategories");
            if (subCategoriesNode != null && subCategoriesNode.isArray() && subCategoriesNode.getArray().size() > 0) {
                String subName = getStringValue(subCategoriesNode.getArray().get(0), "name");
                if (subName != null && !subName.isEmpty()) {
                    category = (category == null || category.isEmpty()) ? subName : category + " / " + subName;
                }
            }

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
            String changelog = null;
            ONode versionNode = root.get("latestVersion");
            if (versionNode != null) {
                latestVersion = getStringValue(versionNode, "version");
                changelog = getStringValue(versionNode, "changelog");
            }

            SkillMarketDetail detail = new SkillMarketDetail()
                    .slug(resolvedSlug)
                    .displayName(displayName)
                    .summary(summary)
                    .description(overview)
                    .ownerHandle(ownerHandle)
                    .installs(installs)
                    .stars(stars)
                    .installSlug(resolvedSlug)
                    .version(latestVersion)
                    .changelog(changelog)
                    .category(category)
                    .verified(parseVerified(root, skillNode))
                    .securityStatus(parseSecurityStatus(root));

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
            Result<SkillMarketDetail> detailResult = detail(slug);
            if (detailResult.getCode() != 200) {
                return Result.failure(detailResult.getDescription());
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
            HttpUtils httpForDownload = HttpUtils.http(downloadUrl)
                    .header("User-Agent", USER_AGENT)
                    .timeout(30000)
                    .ssl(SkillMarketSslSupplier.getInstance());
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
        HttpUtils http = HttpUtils.http(url)
                .header("User-Agent", USER_AGENT)
                .timeout(15000)
                .ssl(SkillMarketSslSupplier.getInstance());
        ProxyConfig.applyIfNeeded(http);
        return http.get();
    }

    /**
     * 解析 skillhub.cn 搜索 API 返回的 results 数组。
     *
     * <p>SkillHub 返回的字段与 ClawHub 不同：
     * 统计字段是平铺的（installs, stars, downloads）而非嵌套在 stats 对象中；
     * 所有者字段为 owner_name 而非 ownerHandle。</p>
     */
    private List<SkillMarketItem> parseResults(ONode root) {
        ONode resultsNode = root.get("results");
        if (resultsNode == null || !resultsNode.isArray()) {
            return Collections.emptyList();
        }

        List<SkillMarketItem> result = new ArrayList<>();
        for (ONode node : resultsNode.getArray()) {
            SkillMarketItem item = new SkillMarketItem()
                    .slug(getStringValue(node, "slug"))
                    .name(getStringValue(node, "slug"))
                    .displayName(getStringValue(node, "displayName"))
                    .summary(getStringValue(node, "summary"))
                    .description(firstNonEmpty(
                            getStringValue(node, "description_zh"),
                            getStringValue(node, "description")))
                    .ownerHandle(getStringValue(node, "owner_name"))
                    .url("https://skillhub.cn/skills/" + getStringValue(node, "slug"))
                    .installs(getLongValue(node, "installs"))
                    .stars(getLongValue(node, "stars"))
                    .version(parseVersion(node))
                    .iconUrl(parseIcon(node));

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

    /**
     * 解析图标地址：列表接口为 icon_url（下划线），详情接口为 iconUrl；
     * 都取不到时回退发布方 logo（publisher.logoUrl），可能仍为空（前端显示首字母）。
     */
    private String parseIcon(ONode node) {
        String icon = firstNonEmpty(
                getStringValue(node, "icon_url"),
                getStringValue(node, "iconUrl"));

        if (icon == null) {
            ONode publisherNode = node.get("publisher");
            if (publisherNode != null && !publisherNode.isNull()) {
                icon = getStringValue(publisherNode, "logoUrl");
            }
        }

        return icon;
    }

    /**
     * 解析认证状态：publisher.verified 优先，其次 skill.verified；都取不到返回 null（前端不显示）。
     */
    private Boolean parseVerified(ONode root, ONode skillNode) {
        ONode publisherNode = root.get("publisher");
        if (publisherNode != null) {
            ONode v = publisherNode.get("verified");
            if (v != null && !v.isNull()) {
                return v.getBoolean();
            }
        }
        ONode v = skillNode.get("verified");
        return (v != null && !v.isNull()) ? v.getBoolean() : null;
    }

    /**
     * 解析安全扫描状态（keen/sanbu 等）：有非 benign 的结果时拼接提示，否则取第一个 statusText。
     */
    private String parseSecurityStatus(ONode root) {
        ONode reportsNode = root.get("securityReports");
        if (reportsNode == null || !reportsNode.isObject()) {
            return null;
        }

        List<String> warnings = new ArrayList<>();
        String okText = null;
        for (Map.Entry<String, ONode> entry : reportsNode.getObject().entrySet()) {
            ONode report = entry.getValue();
            if (report == null || report.isNull() || !report.isObject()) {
                continue;
            }
            String status = getStringValue(report, "status");
            String statusText = getStringValue(report, "statusText");
            if ("benign".equalsIgnoreCase(status)) {
                if (okText == null) {
                    okText = (statusText != null && !statusText.isEmpty()) ? statusText : status;
                }
            } else if (statusText != null && !statusText.isEmpty()) {
                warnings.add(statusText);
            }
        }

        if (!warnings.isEmpty()) {
            return String.join("；", warnings);
        }
        return okText;
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
