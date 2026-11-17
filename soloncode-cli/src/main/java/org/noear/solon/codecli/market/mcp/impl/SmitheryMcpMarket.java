package org.noear.solon.codecli.market.mcp.impl;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.McpTypeResolver;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.market.mcp.McpMarket;
import org.noear.solon.codecli.market.mcp.McpMarketConfig;
import org.noear.solon.codecli.market.mcp.McpMarketDetail;
import org.noear.solon.codecli.market.mcp.McpMarketItem;
import org.noear.solon.codecli.market.mcp.McpLocaleHint;
import org.noear.solon.codecli.market.mcp.McpMarketPage;
import org.noear.solon.codecli.market.mcp.McpMarketParam;
import org.noear.solon.codecli.market.mcp.McpServerDraft;
import org.noear.solon.codecli.market.skill.SkillMarketSslSupplier;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Smithery 注册表适配器 — 对接 registry.smithery.ai。
 *
 * <p>已实测的公开接口（无需鉴权）：</p>
 * <ul>
 *     <li>{@code GET /servers?page=&pageSize=} — 分页列表（按热度排序，2 万+ 条），
 *     返回 {@code servers[]} 与 {@code pagination{currentPage,pageSize,totalPages,totalCount}}</li>
 *     <li>{@code GET /servers/{qualifiedName}} — 条目详情，含 {@code connections[]}（接入方式）、
 *     {@code configSchema}（参数 schema）、{@code tools[]}（工具清单）</li>
 * </ul>
 *
 * <p>两个关键事实决定了本适配器的形态：</p>
 * <ol>
 *     <li><b>该注册表没有可用的服务端搜索</b>：{@code ?q=} 直接返回 500，
 *     {@code ?search=}/{@code ?query=} 被忽略。因此关键词检索改为「抓取热度最高的前若干页
 *     在本地过滤」，索引带 TTL 缓存，避免每次搜索都重新抓取。</li>
 *     <li><b>托管连接（{@code connections[].type=http}）需要鉴权</b>：实测
 *     {@code https://&lt;name&gt;.run.tools} 返回 401 并要求 Bearer；两种传法都可用
 *     （{@code ?api_key=xxx} 与 {@code Authorization: Bearer xxx}，无凭证时提示
 *     {@code Missing Authorization header}）。本项目当前不支持 OAuth 授权流，
 *     草稿统一采用<b>请求头</b>形式 —— 密钥不进 URL，不会被网关日志/复制粘贴泄露。</li>
 * </ol>
 *
 * <p>条目自带 {@code iconUrl}（如 {@code https://api.smithery.ai/servers/brave/icon}），
 * 由宿主经图标代理接口加载，前端不直连境外域名。</p>
 *
 * @author noear 2026/11/16 created
 */
public class SmitheryMcpMarket implements McpMarket {
    private static final Logger LOG = LoggerFactory.getLogger(SmitheryMcpMarket.class);

    private static final String BASE_URL = "https://registry.smithery.ai";
    private static final String USER_AGENT = "SolonCode/1.0";

    /** 分页上限（服务端允许的最大 pageSize） */
    private static final int PAGE_SIZE_MAX = 100;
    /** 本地搜索索引扫描的页数（每页 100 条，按热度取前 N 页） */
    private static final int SEARCH_SCAN_PAGES = 5;
    private static final int SEARCH_PAGE_SIZE = 100;
    /** 本地搜索索引有效期 */
    private static final long SEARCH_INDEX_TTL = 10 * 60 * 1000L;
    /** 详情节点缓存上限 */
    private static final int CACHE_MAX = 500;

    /** 托管连接的鉴权参数：请求头名与值的固定前缀 */
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** 详情节点缓存（qualifiedName -> 详情节点），避免重复抓取 */
    private final Map<String, ONode> serverCache = Collections.synchronizedMap(
            new LinkedHashMap<String, ONode>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ONode> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    /** 本地搜索索引（列表无搜索接口，只能本地过滤） */
    private volatile SearchIndex searchIndex;

    @Override
    public String name() {
        return "smithery.ai";
    }

    @Override
    public String description() {
        return "Smithery 注册表（含图标与热度，条目最多；境外源）";
    }

    // ==================== 列表与搜索 ====================

    @Override
    public Result<McpMarketPage> list(String query, int limit, String cursor, String categoryId) {
        // Smithery 没有分类维度，categoryId 忽略
        int pageSize = limit <= 0 ? 30 : Math.min(limit, PAGE_SIZE_MAX);

        if (Assert.isNotEmpty(query)) {
            return searchByQuery(query.trim(), pageSize, cursor);
        }

        int page = parsePage(cursor);

        try {
            ONode root = ONode.ofJson(httpGet(BASE_URL + "/servers?page=" + page + "&pageSize=" + pageSize));

            List<McpMarketItem> items = new ArrayList<>();
            for (ONode serverNode : arrayOf(root, "servers")) {
                McpMarketItem item = toItem(serverNode);
                if (item != null) {
                    items.add(item);
                }
            }

            String nextCursor = null;
            ONode pagination = root.get("pagination");
            if (pagination != null && pagination.isObject()) {
                int totalPages = intOf(pagination, "totalPages");
                if (totalPages > 0 && page < totalPages && !items.isEmpty()) {
                    nextCursor = String.valueOf(page + 1);
                }
            }

            return Result.succeed(new McpMarketPage().items(items).nextCursor(nextCursor));
        } catch (Exception e) {
            LOG.warn("SmitheryMcpMarket.list error: {}", e.getMessage());
            return Result.failure("获取连接器市场列表失败: " + e.getMessage());
        }
    }

    /**
     * 本地关键词检索：在「热度最高的前 N 页」范围内过滤。
     *
     * <p>该注册表无搜索接口，这是在不引入外部搜索引擎前提下的折中方案：
     * 覆盖最常用的长尾查询，且索引 10 分钟内复用，不会每敲一个字就抓一次远端。</p>
     *
     * <p>命中结果全在本地索引里，故分页也是对索引切片（游标 = 偏移量），
     * 与浏览列表的「页码游标」共用同一个 cursor 参数，前端靠 nextCursor 决定是否显示加载更多。</p>
     */
    private Result<McpMarketPage> searchByQuery(String query, int limit, String cursor) {
        try {
            SearchIndex index = getSearchIndex();

            String[] tokens = query.toLowerCase(Locale.ROOT).split("\\s+");
            List<McpMarketItem> matched = new ArrayList<>();

            for (int i = 0; i < index.items.size(); i++) {
                String haystack = index.haystacks.get(i);
                boolean all = true;
                for (String token : tokens) {
                    if (!token.isEmpty() && !haystack.contains(token)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    matched.add(index.items.get(i));
                }
            }

            int offset = parseOffset(cursor);
            if (offset >= matched.size()) {
                return Result.succeed(new McpMarketPage()
                        .items(Collections.<McpMarketItem>emptyList())
                        .nextCursor(null));
            }

            int end = Math.min(offset + limit, matched.size());
            List<McpMarketItem> pageItems = new ArrayList<>(matched.subList(offset, end));
            String nextCursor = end < matched.size() ? String.valueOf(end) : null;

            return Result.succeed(new McpMarketPage().items(pageItems).nextCursor(nextCursor));
        } catch (Exception e) {
            LOG.warn("SmitheryMcpMarket.search error: {}", e.getMessage());
            return Result.failure("搜索连接器失败: " + e.getMessage());
        }
    }

    /**
     * 获取（必要时重建）本地搜索索引
     */
    private SearchIndex getSearchIndex() {
        SearchIndex current = searchIndex;
        if (current != null && !current.isExpired()) {
            return current;
        }

        synchronized (this) {
            current = searchIndex;
            if (current != null && !current.isExpired()) {
                return current;
            }

            List<McpMarketItem> items = new ArrayList<>();
            List<String> haystacks = new ArrayList<>();

            for (int page = 1; page <= SEARCH_SCAN_PAGES; page++) {
                try {
                    ONode root = ONode.ofJson(
                            httpGet(BASE_URL + "/servers?page=" + page + "&pageSize=" + SEARCH_PAGE_SIZE));

                    List<ONode> servers = arrayOf(root, "servers");
                    if (servers.isEmpty()) {
                        break;
                    }

                    for (ONode serverNode : servers) {
                        McpMarketItem item = toItem(serverNode);
                        if (item == null) {
                            continue;
                        }
                        items.add(item);
                        haystacks.add(buildHaystack(item));
                    }
                } catch (Exception e) {
                    // 抓取中断：用已拿到的部分建索引，下次过期再补全
                    LOG.warn("SmitheryMcpMarket.search index page {} error: {}", page, e.getMessage());
                    break;
                }
            }

            current = new SearchIndex(items, haystacks);
            searchIndex = current;

            LOG.debug("SmitheryMcpMarket: search index rebuilt, size={}", items.size());
            return current;
        }
    }

    private String buildHaystack(McpMarketItem item) {
        StringBuilder buf = new StringBuilder();
        buf.append(nullSafe(item.getId())).append(' ')
                .append(nullSafe(item.getDisplayName())).append(' ')
                .append(nullSafe(item.getDescription())).append(' ')
                .append(nullSafe(item.getOwner()));
        return buf.toString().toLowerCase(Locale.ROOT);
    }

    // ==================== 详情 ====================

    @Override
    public Result<McpMarketDetail> detail(String id) {
        if (Assert.isEmpty(id)) {
            return Result.failure("id is required");
        }

        try {
            ONode serverNode = fetchServer(id);
            if (serverNode == null) {
                return Result.failure("连接器不存在: " + id);
            }

            return Result.succeed(toDetail(serverNode));
        } catch (Exception e) {
            LOG.warn("SmitheryMcpMarket.detail error: {}", e.getMessage());
            return Result.failure("获取连接器详情失败: " + e.getMessage());
        }
    }

    /**
     * 抓取详情节点（带缓存）。qualifiedName 形如 {@code brave} 或 {@code owner/name}，
     * 需要注意不能把中间的斜杠编码掉。
     */
    private ONode fetchServer(String id) throws Exception {
        ONode cached = serverCache.get(id);
        if (cached != null) {
            return cached;
        }

        ONode serverNode = ONode.ofJson(httpGet(BASE_URL + "/servers/" + encodePath(id)));

        // 该注册表对不存在的条目返回 {"error": ...}，而非数组/对象结构
        if (serverNode.get("qualifiedName") == null || serverNode.get("qualifiedName").isNull()) {
            return null;
        }

        serverCache.put(id, serverNode);
        return serverNode;
    }

    // ==================== 配置草稿 ====================

    @Override
    public Result<McpServerDraft> resolve(String id, String configId) {
        Result<McpMarketDetail> detailResult = detail(id);
        if (detailResult.getCode() != 200 || detailResult.getData() == null) {
            return Result.failure(detailResult.getDescription());
        }

        McpMarketDetail detail = detailResult.getData();
        List<McpMarketConfig> configs = detail.getConfigs();
        if (configs == null || configs.isEmpty()) {
            return Result.failure("该连接器未提供可用的接入方式");
        }

        McpMarketConfig config = null;
        for (McpMarketConfig c : configs) {
            if (Assert.isEmpty(configId) || configId.equals(c.getConfigId())) {
                config = c;
                break;
            }
        }
        if (config == null) {
            return Result.failure("接入方式不存在: " + configId);
        }

        if (!McpTypeResolver.isHttpType(config.getType())) {
            // 该注册表里非 http 的接入方式是 .mcpb 压缩包（bundleUrl），
            // 需要运行时下载并本地执行，本项目当前不支持，直接给出明确提示。
            return Result.failure("该连接器仅提供 bundle 形式的本地包，暂不支持直接接入；"
                    + "可改选 HTTP 接入方式，或在宿主环境中手动安装");
        }

        return Result.succeed(toDraft(detail, config));
    }

    private McpServerDraft toDraft(McpMarketDetail detail, McpMarketConfig config) {
        McpServerDraft draft = new McpServerDraft()
                .name(suggestName(detail.getDisplayName() != null ? detail.getDisplayName() : detail.getName()))
                .type(config.getType())
                .description(detail.getDescription())
                .source(name())
                .sourceId(detail.getId())
                .configId(config.getConfigId());

        String url = config.getUrl();

        // 托管域名（*.run.tools / *.smithery.run）必须带鉴权
        boolean hosted = isHosted(url);

        List<McpMarketParam> pending = new ArrayList<>();

        if (hosted) {
            // 鉴权走请求头，不拼进 URL：密钥不会被网关/代理日志记录，
            // 也不会随复制粘贴的地址包而外泄。
            // 前缀交给 valuePrefix（与另两个市场同一套机制）：表单会预填
            // "Authorization=Bearer "，用户直接粘 key 即可，前缀不会丢。
            pending.add(new McpMarketParam()
                    .name(HEADER_AUTHORIZATION)
                    .descriptionHint(McpLocaleHint.of("mcp.marketParamSmitheryKey"))
                    .valuePrefix(BEARER_PREFIX)
                    .required(true)
                    .secret(true)
                    .in(McpMarketParam.IN_HEADER));
        }

        // configSchema 的参数：托管连接的参数以 config 包体下发，无法由本表单的 env/header 承载。
        // 不放进 pendingParams（否则前端会提示“需填写”，但表单里无处可填），只写进 note 说明。
        List<String> platformParams = new ArrayList<>();
        if (config.getParams() != null) {
            for (McpMarketParam param : config.getParams()) {
                if (param.isNeedUserInput() && Assert.isNotEmpty(param.getName())) {
                    platformParams.add(param.getName());
                }
            }
        }

        draft.url(url);
        draft.pendingParams(pending);

        // 只留 pendingParams 表达不了的那一条：参数名已在表单里无处可填（属于平台侧配置），
        // 不写进 note 用户就会以为漏了。其余（“Authorization 怎么填”“去哪个页面拿 key”）
        // 已分别由 pendingParams 行、参数说明和文档链接覆盖，不再复述。
        if (!platformParams.isEmpty()) {
            draft.note(McpLocaleHint.of("mcp.marketNotePlatformParams",
                    "names", String.join("、", platformParams),
                    "site", "smithery.ai"));
        }

        if ((hosted || !platformParams.isEmpty()) && Assert.isNotEmpty(detail.getHomepageUrl())) {
            draft.docUrl(detail.getHomepageUrl());
        }

        return draft;
    }

    // ==================== 解析 ====================

    private McpMarketItem toItem(ONode serverNode) {
        String id = str(serverNode, "qualifiedName");
        if (Assert.isEmpty(id)) {
            return null;
        }

        String displayName = str(serverNode, "displayName");
        String owner = str(serverNode, "namespace");
        String name = id.contains("/") ? id.substring(id.indexOf('/') + 1) : id;

        List<String> transports = new ArrayList<>();
        transports.add(McpTypeResolver.TYPE_STREAMABLE);

        return new McpMarketItem()
                .id(id)
                .name(name)
                .displayName(displayName == null ? name : displayName)
                .owner(owner)
                .description(str(serverNode, "description"))
                .iconUrl(str(serverNode, "iconUrl"))
                .homepageUrl(str(serverNode, "homepage"))
                .verified(bool(serverNode, "verified"))
                .useCount(longOf(serverNode, "useCount"))
                .createdAt(str(serverNode, "createdAt"))
                .transports(transports);
    }

    private McpMarketDetail toDetail(ONode serverNode) {
        McpMarketItem item = toItem(serverNode);

        McpMarketDetail detail = new McpMarketDetail().configs(buildConfigs(serverNode));
        if (item != null) {
            detail.id(item.getId());
            detail.name(item.getName());
            detail.displayName(item.getDisplayName());
            detail.owner(item.getOwner());
            detail.description(item.getDescription());
            detail.iconUrl(item.getIconUrl());
            detail.homepageUrl(item.getHomepageUrl());
            detail.verified(item.isVerified());
            detail.useCount(item.getUseCount());
            detail.createdAt(item.getCreatedAt());
            detail.transports(item.getTransports());
        }

        return detail;
    }

    private List<McpMarketConfig> buildConfigs(ONode serverNode) {
        List<McpMarketConfig> configs = new ArrayList<>();

        int index = 0;
        for (ONode connection : arrayOf(serverNode, "connections")) {
            McpMarketConfig config = toConnectionConfig(connection, index++);
            if (config != null) {
                configs.add(config);
            }
        }

        return configs;
    }

    private McpMarketConfig toConnectionConfig(ONode connection, int index) {
        String rawType = str(connection, "type");
        String deploymentUrl = str(connection, "deploymentUrl");
        String bundleUrl = str(connection, "bundleUrl");

        List<McpMarketParam> params = parseConfigSchema(connection.get("configSchema"), McpMarketParam.IN_QUERY);

        if ("stdio".equalsIgnoreCase(rawType) || (deploymentUrl == null && bundleUrl != null)) {
            // bundle 形式：记录出来供展示，但 resolve 时会明确拒绝
            return new McpMarketConfig()
                    .configId("bundle-" + index)
                    .label("bundle · " + nullSafe(str(connection, "runtime")) + "（暂不支持直接接入）")
                    .type(McpTypeResolver.TYPE_STDIO)
                    .runtime(str(connection, "runtime"))
                    .params(params);
        }

        if (Assert.isEmpty(deploymentUrl)) {
            return null;
        }

        String type = McpTypeResolver.standardize(rawType);
        if (!McpTypeResolver.isHttpType(type)) {
            type = McpTypeResolver.TYPE_STREAMABLE;
        }

        return new McpMarketConfig()
                .configId("http-" + index)
                .label((rawType == null ? "http" : rawType) + " · " + deploymentUrl)
                .type(type)
                .url(deploymentUrl)
                .params(params);
    }

    /**
     * 解析 JSON Schema（configSchema）为参数列表。
     *
     * <p>必填项取自 {@code required[]}，标题/描述取自属性自身，
     * 属性名形如 key/token/secret/password 且无默认值的视为机密项。</p>
     */
    private List<McpMarketParam> parseConfigSchema(ONode schema, String in) {
        List<McpMarketParam> params = new ArrayList<>();
        if (schema == null || schema.isNull() || !schema.isObject()) {
            return params;
        }

        List<String> required = new ArrayList<>();
        for (ONode req : arrayOf(schema, "required")) {
            String name = req.getString();
            if (Assert.isNotEmpty(name)) {
                required.add(name);
            }
        }

        ONode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return params;
        }

        for (String name : properties.getObject().keySet()) {
            ONode prop = properties.get(name);
            if (prop == null || !prop.isObject()) {
                continue;
            }

            String defaultValue = str(prop, "default");
            String description = str(prop, "description");
            String title = str(prop, "title");

            boolean secret = isSecretName(name) && defaultValue == null;

            params.add(new McpMarketParam()
                    .name(name)
                    .description(description != null ? description : title)
                    .required(required.contains(name))
                    .secret(secret)
                    .defaultValue(secret ? null : defaultValue)
                    .in(in));
        }

        return params;
    }

    private boolean isSecretName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("key")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password")
                || lower.contains("apikey");
    }

    /**
     * 是否为需要鉴权的托管域名
     */
    private boolean isHosted(String url) {
        if (Assert.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("//") && (lower.contains(".run.tools") || lower.contains(".smithery.run"));
    }

    // ==================== 工具方法 ====================

    private int parsePage(String cursor) {
        if (Assert.isEmpty(cursor)) {
            return 1;
        }
        try {
            int page = Integer.parseInt(cursor.trim());
            return page < 1 ? 1 : page;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 搜索结果游标：本地索引的偏移量（与浏览列表的页码游标语义不同，两者不会混用）
     */
    private int parseOffset(String cursor) {
        if (Assert.isEmpty(cursor)) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor.trim());
            return offset < 0 ? 0 : offset;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 生成可用的 MCP 服务名（仅允许 {@code [a-zA-Z0-9_-]}）
     */
    private String suggestName(String raw) {
        if (Assert.isEmpty(raw)) {
            return "mcp-server";
        }

        String name = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");

        if (name.length() > 48) {
            name = name.substring(0, 48).replaceAll("-+$", "");
        }

        return name.isEmpty() ? "mcp-server" : name;
    }

    private String encodePath(String id) throws Exception {
        StringBuilder buf = new StringBuilder();
        for (String seg : id.split("/")) {
            if (buf.length() > 0) {
                buf.append('/');
            }
            buf.append(URLEncoder.encode(seg, "UTF-8"));
        }
        return buf.toString();
    }

    private String httpGet(String url) throws Exception {
        HttpUtils http = HttpUtils.http(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(20000)
                .ssl(SkillMarketSslSupplier.getInstance());
        ProxyConfig.applyIfNeeded(http);
        return http.get();
    }

    private List<ONode> arrayOf(ONode node, String key) {
        if (node == null) {
            return Collections.emptyList();
        }
        ONode child = node.get(key);
        if (child == null || child.isNull() || !child.isArray()) {
            return Collections.emptyList();
        }
        return child.getArray();
    }

    private String str(ONode node, String key) {
        if (node == null) {
            return null;
        }
        ONode child = node.get(key);
        if (child == null || child.isNull()) {
            return null;
        }
        String value = child.getString();
        return value == null || value.isEmpty() ? null : value;
    }

    private boolean bool(ONode node, String key) {
        ONode child = node == null ? null : node.get(key);
        return child != null && !child.isNull() && child.getBoolean();
    }

    private int intOf(ONode node, String key) {
        long value = longOf(node, key);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private long longOf(ONode node, String key) {
        ONode child = node == null ? null : node.get(key);
        return child == null || child.isNull() ? 0L : child.getLong();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 本地搜索索引
     */
    private static class SearchIndex {
        final List<McpMarketItem> items;
        final List<String> haystacks;
        final long createdAt = System.currentTimeMillis();

        SearchIndex(List<McpMarketItem> items, List<String> haystacks) {
            this.items = items;
            this.haystacks = haystacks;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > SEARCH_INDEX_TTL;
        }
    }
}
