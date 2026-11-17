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
 * ClawHub 市场适配器 — 对接 clawhub.ai API。
 *
 * <p>列表、搜索、详情、下载全部走 JSON 接口（{@code /api/v1/*} 与站点后端
 * {@code skills:listPublicPageV4}），<b>不做 HTML 解析</b>；分类清单是该站前端打包的静态常量、
 * 没有对应接口，故内置为资源文件，见 {@link #CATEGORIES_RESOURCE}。</p>
 *
 * @author noear 2026/5/28 created
 */
public class ClawhubSkillMarket implements SkillMarket {

    private static final Logger LOG = LoggerFactory.getLogger(ClawhubSkillMarket.class);

    private static final String BASE_URL = "https://clawhub.ai";
    private static final String USER_AGENT = "SolonCode/1.0";
    /** 站点接口需要浏览器 UA（列表查询走站点后端），否则可能被拦 */
    private static final String BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 搜索接口：支持 category 过滤，但 q 必填（q 为空返回空数组）；返回 {results:[...]} */
    private static final String SEARCH_URL = BASE_URL + "/api/v1/search";
    /** 搜索接口 limit 上限（传 200/500 均按 100 返回） */
    private static final int MAX_SEARCH_LIMIT = 100;

    /**
     * 分类清单的内置资源（与源站 2026-11-17 的分类表逐项一致）。
     *
     * <p>分类表是站点前端打包的静态常量（{@code catalogMetadata-<hash>.js}），没有对应的 JSON 接口
     * （{@code /api/v1/categories} 为 404），且文件名带构建 hash、每次发版都会变。原先靠
     * 「SSR HTML → 入口脚本 → 分类 chunk」三层跟着抓，源站一改版就失效，因此改为随包内置、
     * 运行期零网络请求；源站增删分类时更新这个文件即可（见 {@code MarketCategoryResource}）。</p>
     *
     * <p>分类名属于源站动态内容，<b>原样展示，不做翻译</b>。slug 是浏览与搜索接口共用的标识
     * （小写且大小写敏感：{@code ?category=Development} 不生效）。站点未提供各分类条目数。</p>
     */
    private static final String CATEGORIES_RESOURCE = "clawhub-categories.json";

    /**
     * 站点后端（Convex）查询入口 —— 浏览页的列表、以及「加载更多」按钮都由它承载。
     *
     * <p>地址取自站点前端运行时配置 {@code VITE_CONVEX_URL}（{@code /assets/runtimeEnv-<hash>.js}）。
     * 这是站点自己的后端接口，无公开契约；变了下沉到本类，只需改这里。</p>
     */
    private static final String CONVEX_QUERY_URL = "https://wry-manatee-359.convex.cloud/api/query";
    /** 技能列表查询（与站点浏览页同款，支持 categorySlug + 游标分页） */
    private static final String CONVEX_LIST_PATH = "skills:listPublicPageV4";
    /** numItems 实测上限：传 40 返回 40，传 50/100/200 仍按 40 返回 */
    private static final int CONVEX_MAX_CHUNK = 40;
    /** 单次请求最多向上游取几块（正常翻页只需 1~2 块，这里只兜住页码跳跃） */
    private static final int CONVEX_MAX_CHUNKS_PER_CALL = 8;
    /** 排序，与站点浏览页一致（更新时间的倒序） */
    private static final String CONVEX_SORT = "updated";
    private static final String CONVEX_DIR = "desc";
    /** 游标流复用窗口：前端连续点「加载更多」时复用，超过这个时间就从头取 */
    private static final long STREAM_TTL_MS = 5 * 60 * 1000L;

    /** 最近一条游标流（市场页同时只有一个列表，不必要多） */
    private volatile CursorStream cursorStream;

    /**
     * 游标分页流：同一份列表的顺次翻页共享它，避免每页都从头重放。
     */
    private static final class CursorStream {
        /** 复用标识：分类 + 每页条数 */
        final String key;
        final String category;
        /** 已取到但还没发出去的条目 */
        final List<SkillMarketItem> buffer = new ArrayList<>();
        /** 本次流里已发过的 slug（上游列表变动时防止重复展示） */
        final Set<String> seen = new HashSet<>();
        String cursor;
        boolean exhausted;
        int servedPages;
        long touchedAt = System.currentTimeMillis();

        CursorStream(String key, String category) {
            this.key = key;
            this.category = category;
        }
    }

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
    public Result<List<SkillMarketItem>> trending(int limit) {
        try {
            String url = BASE_URL + "/api/v1/trending?kind=skills&limit=" + limit;
            String body = httpGet(url);
            ONode root = ONode.ofJson(body);

            if (root.hasKey("error")) {
                return Result.failure(root.get("message").getString());
            }

            List<SkillMarketItem> items = parseItems(root);
            return Result.succeed(items);
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.trending error: {}", e.getMessage());
            return Result.failure("获取热门技能失败: " + e.getMessage());
        }
    }

    @Override
    public Result<List<SkillMarketItem>> search(String query, int limit) {
        return search(query, limit, null);
    }

    /**
     * 按分类搜索。
     *
     * <p>分类由搜索接口原生过滤（实测 {@code q=ai&category=security} 返回条目全部属于 security）。
     * 但该接口要求 q 非空：{@code q} 为空时即使带 category 也返回空数组，
     * 因此「只按分类浏览」不能走这里，见 {@link #page}。</p>
     */
    @Override
    public Result<List<SkillMarketItem>> search(String query, int limit, String category) {
        if (hasText(query) == false) {
            if (hasText(category)) {
                // 分类浏览（无关键词）：走站点列表接口
                Result<SkillMarketPage> browseResult = browsePage(category, 1, Math.max(limit, 1));
                if (browseResult.getCode() != 200) {
                    return Result.failure(browseResult.getDescription());
                }
                return Result.succeed(browseResult.getData().getItems());
            }
            return trending(limit);
        }

        try {
            StringBuilder url = new StringBuilder(SEARCH_URL)
                    .append("?q=").append(URLEncoder.encode(query, "UTF-8"));

            if (hasText(category)) {
                url.append("&category=").append(URLEncoder.encode(category, "UTF-8"));
            }
            if (limit > 0) {
                url.append("&limit=").append(Math.min(limit, MAX_SEARCH_LIMIT));
            }

            String body = httpGet(url.toString());
            ONode root = ONode.ofJson(body);

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

    // ==================== 分类 ====================

    /**
     * 分类清单，来自内置资源（{@link #CATEGORIES_RESOURCE}），运行期不请求网络。
     *
     * <p>分类名是源站英文原文，原样透出、不翻译；站点没有分类条目数，故不带计数。</p>
     */
    @Override
    public List<SkillMarketCategory> categories() {
        List<MarketCategoryResource.Entry> entries =
                MarketCategoryResource.load(ClawhubSkillMarket.class, CATEGORIES_RESOURCE);

        List<SkillMarketCategory> list = new ArrayList<>(entries.size());
        for (MarketCategoryResource.Entry entry : entries) {
            list.add(new SkillMarketCategory().id(entry.getId()).name(entry.getName()));
        }
        return list;
    }

    /**
     * 分页。分类/全量浏览与关键词搜索走不同通道：
     * <ul>
     *     <li>无关键词：站点列表查询 {@code skills:listPublicPageV4} 原生游标分页，分类由服务端过滤；</li>
     *     <li>有关键词：搜索接口按 {@code need = page * pageSize} 取数后本地切片（上游 limit 封顶 100）。</li>
     * </ul>
     */
    @Override
    public Result<SkillMarketPage> page(String query, String category, int page, int pageSize) {
        int currentPage = Math.max(page, 1);
        int size = Math.max(pageSize, 1);

        if (hasText(query) == false) {
            return browsePage(category, currentPage, size);
        }

        int need = currentPage * size;
        int fetch = Math.min(need, MAX_SEARCH_LIMIT);

        Result<List<SkillMarketItem>> result = search(query, fetch, category);
        if (result.getCode() != 200) {
            return Result.failure(result.getDescription());
        }

        List<SkillMarketItem> all = (result.getData() == null)
                ? Collections.<SkillMarketItem>emptyList()
                : result.getData();

        int from = (currentPage - 1) * size;
        if (from >= all.size()) {
            return Result.succeed(new SkillMarketPage()
                    .page(currentPage)
                    .items(Collections.<SkillMarketItem>emptyList())
                    .hasMore(false));
        }

        int to = Math.min(need, all.size());
        // need 达上限时上游已封顶，取不到更多，直接判到底
        boolean hasMore = (need >= MAX_SEARCH_LIMIT) ? false : all.size() >= need;

        return Result.succeed(new SkillMarketPage()
                .page(currentPage)
                .items(new ArrayList<>(all.subList(from, to)))
                .hasMore(hasMore));
    }

    /**
     * 分类/全量浏览（无关键词）：站点列表接口的游标分页。
     *
     * <p>游标是不透明的（服务端索引键），无法从页码反推，所以用 {@link CursorStream} 按页顺序前进：
     * 前端顺序点「加载更多」时每页只需 1~2 次上游调用；页码跳跃（或换了分类/每页条数）则重开一个流，
     * 从头快进到目标页。缓存只保留最近一条流（市场页同时只有一个列表）。</p>
     */
    private Result<SkillMarketPage> browsePage(String category, int page, int pageSize) {
        String key = (hasText(category) ? category : "") + "|" + pageSize;

        try {
            CursorStream stream = cursorStream;
            boolean reuse = stream != null
                    && stream.key.equals(key)
                    && stream.servedPages == page - 1
                    && System.currentTimeMillis() - stream.touchedAt < STREAM_TTL_MS;

            if (reuse == false) {
                stream = new CursorStream(key, category);
                cursorStream = stream;
            }

            synchronized (stream) {
                if (reuse == false) {
                    // 快进：丢掉目标页之前的页
                    for (int p = 1; p < page; p++) {
                        takePage(stream, pageSize);
                    }
                }

                List<SkillMarketItem> items = takePage(stream, pageSize);
                boolean hasMore = stream.buffer.isEmpty() == false || stream.exhausted == false;

                return Result.succeed(new SkillMarketPage()
                        .page(page)
                        .items(items)
                        .hasMore(hasMore));
            }
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.browsePage error: {}", e.getMessage());
            return Result.failure("获取技能列表失败: " + e.getMessage());
        }
    }

    /** 从流里取一页（不够则继续向上游取块），取完即从缓冲区移除 */
    private List<SkillMarketItem> takePage(CursorStream stream, int pageSize) throws Exception {
        int chunks = 0;
        while (stream.buffer.size() < pageSize
                && stream.exhausted == false
                && chunks < CONVEX_MAX_CHUNKS_PER_CALL) {
            fetchChunk(stream);
            chunks++;
        }

        int n = Math.min(pageSize, stream.buffer.size());
        List<SkillMarketItem> items = new ArrayList<>(stream.buffer.subList(0, n));
        stream.buffer.subList(0, n).clear();
        stream.servedPages++;
        stream.touchedAt = System.currentTimeMillis();
        return items;
    }

    /** 向上游取一块（最多 {@link #CONVEX_MAX_CHUNK} 条），结果并入流的缓冲区 */
    private void fetchChunk(CursorStream stream) throws Exception {
        ONode args = new ONode()
                .set("numItems", CONVEX_MAX_CHUNK)
                .set("sort", CONVEX_SORT)
                .set("dir", CONVEX_DIR);

        if (hasText(stream.category)) {
            args.set("categorySlug", stream.category);
        }
        if (stream.cursor != null) {
            args.set("cursor", stream.cursor);
        }

        String payload = new ONode()
                .set("path", CONVEX_LIST_PATH)
                .set("args", args)
                .set("format", "json")
                .toJson();

        ONode root = ONode.ofJson(httpPost(CONVEX_QUERY_URL, payload));
        if ("success".equals(getStringValue(root, "status")) == false) {
            throw new IllegalStateException(String.valueOf(getStringValue(root, "errorMessage")));
        }

        ONode value = root.get("value");
        ONode pageNode = (value == null || value.isNull()) ? null : value.get("page");

        int added = 0;
        if (pageNode != null && pageNode.isArray()) {
            for (ONode itemNode : pageNode.getArray()) {
                SkillMarketItem item = parseConvexItem(itemNode);
                if (item == null || stream.seen.add(item.getSlug()) == false) {
                    continue;
                }
                stream.buffer.add(item);
                added++;
            }
        }

        String nextCursor = (value == null || value.isNull()) ? null : getStringValue(value, "nextCursor");
        boolean hasMore = value != null && value.isNull() == false
                && value.get("hasMore") != null && value.get("hasMore").getBoolean();

        // 空块 / 说是到底 / 游标原地打转，都当到底，避免死循环
        if (hasMore == false || nextCursor == null || nextCursor.isEmpty()
                || nextCursor.equals(stream.cursor) || added == 0) {
            stream.exhausted = true;
        } else {
            stream.cursor = nextCursor;
        }
    }

    /** 把站点列表条目映射成市场条目（字段与站点展示一致：下载量、版本、发布方头像） */
    private SkillMarketItem parseConvexItem(ONode itemNode) {
        ONode skillNode = itemNode.get("skill");
        if (skillNode == null || skillNode.isNull()) {
            return null;
        }

        String slug = getStringValue(skillNode, "slug");
        if (hasText(slug) == false) {
            return null;
        }

        String displayName = getStringValue(skillNode, "displayName");
        if (hasText(displayName) == false) {
            displayName = slug;
        }

        String ownerHandle = getStringValue(itemNode, "ownerHandle");
        if (hasText(ownerHandle) == false) {
            ONode ownerNode = itemNode.get("owner");
            if (ownerNode != null && ownerNode.isNull() == false) {
                ownerHandle = getStringValue(ownerNode, "handle");
            }
        }

        String version = null;
        ONode latestVersionNode = itemNode.get("latestVersion");
        if (latestVersionNode != null && latestVersionNode.isNull() == false) {
            version = getStringValue(latestVersionNode, "version");
        }

        SkillMarketItem item = new SkillMarketItem()
                .slug(slug)
                .name(displayName)
                .displayName(displayName)
                .summary(getStringValue(skillNode, "summary"))
                .ownerHandle(ownerHandle)
                .url(hasText(ownerHandle) ? BASE_URL + "/" + ownerHandle + "/" + slug : BASE_URL + "/skills/" + slug)
                .version(version);

        ONode statsNode = skillNode.get("stats");
        if (statsNode != null && statsNode.isNull() == false) {
            long downloads = getLongValue(statsNode, "downloads");
            item.installs(downloads > 0 ? downloads : getLongValue(statsNode, "installs"));
            item.stars(getLongValue(statsNode, "stars"));
        }

        item.iconUrl(parseIcon(itemNode));

        return item;
    }

    // ==================== 详情 ====================

    @Override
    public Result<SkillMarketDetail> detail(String slug) {
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

            SkillMarketDetail detail = new SkillMarketDetail()
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
            Result<SkillMarketDetail> detailResult = detail(slug);
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
                        .timeout(15000)
                        .ssl(SkillMarketSslSupplier.getInstance());
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

    /** POST JSON（带重试），用于站点列表接口 */
    private String httpPost(String url, String jsonBody) throws Exception {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try {
                HttpUtils http = HttpUtils.http(url)
                        .header("User-Agent", BROWSER_UA)
                        .header("Content-Type", "application/json")
                        .timeout(15000)
                        .ssl(SkillMarketSslSupplier.getInstance());
                ProxyConfig.applyIfNeeded(http);
                http.bodyOfJson(jsonBody);

                try (HttpResponse response = http.exec("POST")) {
                    String body = response.bodyAsString();
                    if (response.code() >= 300) {
                        throw new IllegalStateException("HTTP " + response.code() + ": " + body);
                    }
                    return body;
                }
            } catch (Exception e) {
                lastEx = e;
                LOG.warn("httpPost attempt {} failed: {}", i + 1, e.getMessage());
                if (i < 2) {
                    Thread.sleep(1000);
                }
            }
        }
        throw lastEx;
    }

    private List<SkillMarketItem> parseItems(ONode root) {
        ONode itemsNode = root.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            return parseNodeArray(itemsNode);
        }

        // 搜索接口返回的是 {results:[...]}，不是 items
        ONode resultsNode = root.get("results");
        if (resultsNode != null && resultsNode.isArray()) {
            return parseNodeArray(resultsNode);
        }

        return Collections.emptyList();
    }

    private boolean hasText(String text) {
        return text != null && text.isEmpty() == false;
    }

    private List<SkillMarketItem> parseNodeArray(ONode arrayNode) {
        List<SkillMarketItem> result = new ArrayList<>();
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

            SkillMarketItem item = new SkillMarketItem()
                    .slug(slug)
                    .name(slug)
                    .displayName(getStringValue(node, "displayName"))
                    .summary(getStringValue(node, "summary"))
                    .description(getStringValue(node, "description"))
                    .ownerHandle(ownerHandle)
                    .url(detailUrl)
                    .iconUrl(parseIcon(node));

            // 解析安装量：浏览/热门接口为 metrics.lifetimeInstalls，搜索接口只有平铺的 downloads
            long installs = 0;
            ONode metricsNode = node.get("metrics");
            if (metricsNode != null && !metricsNode.isNull()) {
                installs = getLongValue(metricsNode, "lifetimeInstalls");
            }
            if (installs == 0) {
                installs = getLongValue(node, "downloads");
            }
            item.installs(installs);

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

    /**
     * 解析图标地址：优先搜索接口的 icon 字段，回退发布方头像（publisher.image，多为 GitHub 头像）；
     * 发布方缺失（如 skills.sh 来源）时返回 null，前端显示首字母。
     */
    /** 把图标字段也认作发布方头像：搜索接口是 publisher.image，站点列表接口是 owner.image */
    private String parseIcon(ONode node) {
        String icon = getStringValue(node, "icon");
        if (icon != null && !icon.isEmpty()) {
            return icon;
        }

        for (String ownerKey : new String[]{"publisher", "owner"}) {
            ONode ownerNode = node.get(ownerKey);
            if (ownerNode != null && ownerNode.isNull() == false) {
                String image = getStringValue(ownerNode, "image");
                if (image != null && !image.isEmpty()) {
                    return image;
                }
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
