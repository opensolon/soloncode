package org.noear.solon.codecli.market.skill.impl;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.ProxyConfig;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ClawHub 市场适配器 — 对接 clawhub.ai API。
 *
 * @author noear 2026/5/28 created
 */
public class ClawhubSkillMarket implements SkillMarket {

    private static final Logger LOG = LoggerFactory.getLogger(ClawhubSkillMarket.class);

    private static final String BASE_URL = "https://clawhub.ai";
    private static final String USER_AGENT = "SolonCode/1.0";
    /** 浏览页（SSR）需要浏览器 UA，否则可能拿到不同的渲染结果 */
    private static final String BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 搜索接口：支持 category 过滤，但 q 必填（q 为空返回空数组）；返回 {results:[...]} */
    private static final String SEARCH_URL = BASE_URL + "/api/v1/search";
    /** 浏览页：分类浏览唯一可用通道（无对应 JSON 接口，只能解析 SSR HTML） */
    private static final String BROWSE_URL = BASE_URL + "/skills";
    /** 搜索接口 limit 上限（传 200/500 均按 100 返回） */
    private static final int MAX_SEARCH_LIMIT = 100;

    /**
     * 分类兜底清单（14 个）。
     *
     * <p>ClawHub 没有分类清单接口（{@code /api/v1/categories} 为 404），分类表在站点前端资源里
     * （{@code catalogMetadata-<hash>.js} 的 category 数组）。运行期优先读源站当前分类表
     * （{@link #loadSourceCategories()}），失败才用这里兜底。</p>
     *
     * <p>兜底值与源站 2026-11-16 的分类表逐项一致。<b>分类名属于源站动态内容，原样展示，不做翻译</b>；
     * 兜底也不是翻译，只是源站取不到时的快照。</p>
     *
     * <p>slug 是浏览页 URL 与搜索接口共用的标识（小写，大小写敏感：
     * {@code ?category=Development} 不生效，必须是小写 slug）。</p>
     *
     * <p>条目数（count）站点未提供，保持 0，前端据此不显示数字。</p>
     */
    private static final String[][] FALLBACK_CATEGORIES = {
            {"integrations", "Integrations"},
            {"automation", "Automation"},
            {"research", "Research"},
            {"development", "Development"},
            {"productivity", "Productivity"},
            {"communication", "Communication"},
            {"creative", "Creative"},
            {"knowledge", "Knowledge"},
            {"agents", "Agents"},
            {"operations", "Operations"},
            {"security", "Security"},
            {"finance", "Finance"},
            {"lifestyle", "Lifestyle"},
            {"other", "Other"}
    };

    /** 浏览页 SSR 里的入口脚本（文件名带 hash，每次发版都会变） */
    private static final Pattern SCRIPT_SRC = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"");
    /** 入口脚本里引用的分类表 chunk（catalogMetadata-<hash>.js） */
    private static final Pattern CATALOG_ASSET = Pattern.compile("(catalogMetadata-[A-Za-z0-9_-]+\\.js)");
    /** 分类表条目形态：{slug:`security`,label:`Security`,...} */
    private static final Pattern CATEGORY_ENTRY = Pattern.compile("slug:`([^`]+)`,label:`([^`]+)`");
    /** 分类表数组的首两项（category 数组以 integrations → automation 开头，用它从 purposes/kinds 等其它分类法里区分出来） */
    private static final String CATEGORY_TAXONOMY_HEAD = "integrations";
    private static final String CATEGORY_TAXONOMY_NEXT = "automation";
    private static final String CATEGORY_TAXONOMY_TAIL = "other";
    /** 解析结果少于这个数量就认为没拿到分类表（源站改版），退回兜底清单 */
    private static final int CATEGORY_MIN_SIZE = 5;
    private static final long CATEGORY_CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 浏览页列表条目（href 形如 /owner/slug，块内无嵌套 a 标签） */
    private static final Pattern BROWSE_ITEM = Pattern.compile(
            "<a class=\"skill-list-item[^\"]*\" href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern BROWSE_NAME = Pattern.compile(
            "class=\"skill-list-item-name\"[^>]*>([^<]*)<");
    private static final Pattern BROWSE_OWNER = Pattern.compile(
            "class=\"skill-list-item-owner\">@(?:<!-- -->)?([^<]*)<");
    private static final Pattern BROWSE_SUMMARY = Pattern.compile(
            "class=\"skill-list-item-summary\">(.*?)</p>", Pattern.DOTALL);
    /** 下载量：定位 lucide-download 图标的结束标签后再取数字（svg 的 path 里也有数字，不能用懒匹配直接取） */
    private static final Pattern BROWSE_DOWNLOADS = Pattern.compile(
            "lucide-download[\\s\\S]*?</svg>\\s*(?:<!-- -->)?([\\d.,]+[kKmM]?)");

    /** 源站分类表缓存（取不到也缓存，避免每页都扇出 3 个请求） */
    private volatile List<SkillMarketCategory> cachedCategories;
    private volatile long cachedCategoriesAt;

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
                // 分类浏览（无关键词）：改取浏览页
                Result<SkillMarketPage> browseResult = categoryBrowse(category, 1);
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
     * 分类清单。分类名属于源站动态内容，原样透传、不翻译：
     * 能读到源站分类表就用源站的（含新增/改名），读不到才用 {@link #FALLBACK_CATEGORIES} 快照。
     */
    @Override
    public List<SkillMarketCategory> categories() {
        List<SkillMarketCategory> cached = cachedCategories;
        if (cached != null && System.currentTimeMillis() - cachedCategoriesAt < CATEGORY_CACHE_TTL_MS) {
            return cached;
        }

        synchronized (this) {
            if (cachedCategories != null && System.currentTimeMillis() - cachedCategoriesAt < CATEGORY_CACHE_TTL_MS) {
                return cachedCategories;
            }

            List<SkillMarketCategory> list = loadSourceCategories();
            if (list == null || list.size() < CATEGORY_MIN_SIZE) {
                LOG.debug("ClawhubMarket.categories 未取到源站分类表，使用兜底清单");
                list = fallbackCategories();
            }

            cachedCategories = list;
            cachedCategoriesAt = System.currentTimeMillis();
            return list;
        }
    }

    /**
     * 读源站分类表：浏览页 SSR → 入口脚本 → 分类表 chunk（文件名带 hash，只能逐层跟）。
     *
     * <p>任何一步失败返回 null（由调用方用兜底清单），不抛异常。</p>
     */
    private List<SkillMarketCategory> loadSourceCategories() {
        try {
            String html = httpGet(BROWSE_URL, BROWSER_UA);
            Matcher script = SCRIPT_SRC.matcher(html);
            if (script.find() == false) {
                return null;
            }

            String indexJs = httpGet(BASE_URL + script.group(1), BROWSER_UA);
            Matcher catalog = CATALOG_ASSET.matcher(indexJs);
            if (catalog.find() == false) {
                return null;
            }

            String metaJs = httpGet(BASE_URL + "/assets/" + catalog.group(1), BROWSER_UA);
            return parseCategoryTaxonomy(metaJs);
        } catch (Exception e) {
            LOG.debug("ClawhubMarket.loadSourceCategories error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从分类表 chunk 里切出 category 数组。
     *
     * <p>同一个文件里有 4 套分类法（purposes / kinds / category …），slug 会重复，
     * 按首个 {@code integrations} 定位会落到 purposes 那套（实测踩过）。
     * 所以用「{@code integrations} 紧接 {@code automation}」这对开头来认 category 数组，
     * 再一直收到末项 {@code other}。</p>
     */
    private List<SkillMarketCategory> parseCategoryTaxonomy(String js) {
        List<String[]> pairs = new ArrayList<>();
        Matcher entry = CATEGORY_ENTRY.matcher(js);
        while (entry.find()) {
            pairs.add(new String[]{entry.group(1), entry.group(2)});
        }

        for (int i = 0; i < pairs.size() - 1; i++) {
            if (CATEGORY_TAXONOMY_HEAD.equals(pairs.get(i)[0]) == false
                    || CATEGORY_TAXONOMY_NEXT.equals(pairs.get(i + 1)[0]) == false) {
                continue;
            }

            List<SkillMarketCategory> list = new ArrayList<>();
            for (int j = i; j < pairs.size(); j++) {
                list.add(new SkillMarketCategory().id(pairs.get(j)[0]).name(pairs.get(j)[1]));
                if (CATEGORY_TAXONOMY_TAIL.equals(pairs.get(j)[0])) {
                    break;
                }
            }
            return list;
        }

        return null;
    }

    private static List<SkillMarketCategory> fallbackCategories() {
        List<SkillMarketCategory> list = new ArrayList<>(FALLBACK_CATEGORIES.length);
        for (String[] category : FALLBACK_CATEGORIES) {
            list.add(new SkillMarketCategory().id(category[0]).name(category[1]));
        }
        return list;
    }

    /**
     * 分页。分类浏览与关键词搜索走不同通道：
     * <ul>
     *     <li>有关键词：搜索接口按 {@code need = page * pageSize} 取数后本地切片（上游 limit 封顶 100）；</li>
     *     <li>无关键词仅有分类：取浏览页（每页固定 20 条，page 参数无效，只有第一页有数据）。</li>
     * </ul>
     */
    @Override
    public Result<SkillMarketPage> page(String query, String category, int page, int pageSize) {
        int currentPage = Math.max(page, 1);
        int size = Math.max(pageSize, 1);

        if (hasText(query) == false && hasText(category)) {
            return categoryBrowse(category, currentPage);
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
     * 分类浏览：解析浏览页 SSR HTML。
     *
     * <p>站点未开放按分类取列表的 JSON 接口（{@code /api/v1/skills?category=} 等参数被静默忽略），
     * 只有浏览页做了服务端过滤，且 {@code page} 参数无效、单页固定 20 条。</p>
     */
    private Result<SkillMarketPage> categoryBrowse(String category, int page) {
        if (page > 1) {
            return Result.succeed(new SkillMarketPage()
                    .page(page)
                    .items(Collections.<SkillMarketItem>emptyList())
                    .hasMore(false));
        }

        try {
            String url = BROWSE_URL + "?category=" + URLEncoder.encode(category, "UTF-8");
            List<SkillMarketItem> items = parseBrowseItems(httpGet(url, BROWSER_UA));

            return Result.succeed(new SkillMarketPage()
                    .page(1)
                    .items(items)
                    .hasMore(false));
        } catch (Exception e) {
            LOG.warn("ClawhubMarket.categoryBrowse error: {}", e.getMessage());
            return Result.failure("获取分类技能失败: " + e.getMessage());
        }
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
        return httpGet(url, USER_AGENT);
    }

    private String httpGet(String url, String userAgent) throws Exception {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try {
                HttpUtils http = HttpUtils.http(url)
                        .header("User-Agent", userAgent)
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

    /**
     * 解析浏览页 SSR HTML 的列表条目。
     *
     * <p>条目结构：{@code <a class="skill-list-item..." href="/owner/slug">…}，
     * 内部含名称、所有者、简介、主题、分类、更新时间/收藏数/下载数。
     * 浏览页不输出图标，因此 iconUrl 为 null，前端显示首字母徽标。</p>
     */
    private List<SkillMarketItem> parseBrowseItems(String html) {
        List<SkillMarketItem> items = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return items;
        }

        Matcher matcher = BROWSE_ITEM.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1);
            String block = matcher.group(2);

            String displayName = unescapeHtml(firstMatch(BROWSE_NAME, block));
            if (displayName == null || displayName.isEmpty()) {
                continue;
            }

            // href 形如 /owner/slug
            String[] parts = href.split("/");
            if (parts.length < 3) {
                continue;
            }
            String hrefOwner = parts[1];
            String slug = parts[2];

            String owner = unescapeHtml(firstMatch(BROWSE_OWNER, block));

            items.add(new SkillMarketItem()
                    .slug(slug)
                    .name(displayName)
                    .displayName(displayName)
                    .summary(unescapeHtml(firstMatch(BROWSE_SUMMARY, block)))
                    .ownerHandle(hasText(owner) ? owner : hrefOwner)
                    .url(BASE_URL + href)
                    .installs(parseCount(firstMatch(BROWSE_DOWNLOADS, block))));
        }

        return items;
    }

    /** 取正则第一个分组，未命中返回 null */
    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 解析站点展示的计数（带 k/M 后缀，如 {@code 59.6k}）。
     * 仅用于列表粗略展示，精度不保证。
     */
    private long parseCount(String text) {
        if (hasText(text) == false) {
            return 0;
        }

        String value = text.trim().replace(",", "");
        double multiplier = 1;

        char last = Character.toLowerCase(value.charAt(value.length() - 1));
        if (last == 'k') {
            multiplier = 1000;
            value = value.substring(0, value.length() - 1);
        } else if (last == 'm') {
            multiplier = 1000000;
            value = value.substring(0, value.length() - 1);
        }

        try {
            return (long) (Double.parseDouble(value) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 反转义 SSR 输出里的常见 HTML 实体 */
    private String unescapeHtml(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }

        return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
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
    private String parseIcon(ONode node) {
        String icon = getStringValue(node, "icon");
        if (icon != null && !icon.isEmpty()) {
            return icon;
        }

        ONode publisherNode = node.get("publisher");
        if (publisherNode != null && !publisherNode.isNull()) {
            String image = getStringValue(publisherNode, "image");
            if (image != null && !image.isEmpty()) {
                return image;
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
