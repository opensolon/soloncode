package org.noear.solon.codecli.market.mcp.impl;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.McpTypeResolver;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.market.mcp.McpMarket;
import org.noear.solon.codecli.market.mcp.McpMarketConfig;
import org.noear.solon.codecli.market.mcp.McpMarketDetail;
import org.noear.solon.codecli.market.mcp.McpMarketItem;
import org.noear.solon.codecli.market.mcp.McpMarketPage;
import org.noear.solon.codecli.market.mcp.McpMarketParam;
import org.noear.solon.codecli.market.mcp.McpServerDraft;
import org.noear.solon.codecli.market.skill.SkillMarketSslSupplier;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 腾讯云开发者 MCP 广场适配器 — 对接 cloud.tencent.com/developer/mcp。
 *
 * <p>该广场前端是 Next.js SSR，页面本身只内嵌首屏 30 条；翻页与搜索都走
 * 站点内部的 BFF 接口。已实测出可用契约（全部 <b>POST + JSON</b>，
 * 前缀 {@code https://cloud.tencent.com/developer/api/mcp}）：</p>
 * <ul>
 *     <li>{@code /list} — 一次返回<b>全部</b>条目（约 1060 条、按 12 个分类分组，约 550KB）。
 *     前端的分页其实是本地分页，故本适配器也本地切页，只需一次请求。</li>
 *     <li>{@code /list-by-search} — 服务端搜索，入参 {@code {keyword, categoryId, isHosted}}，
 *     返回扁平数组。中文分词由对方处理（如「地图」可命中百度/谷歌地图）。</li>
 *     <li>{@code /categories} — 分类及其条目数（带 keyword 时返回命中数）。</li>
 *     <li>{@code /detail} — 入参 {@code {mcpId}}，返回 readme(HTML/Markdown)、
 *     {@code mcpParams}（字段名/占位文案/是否必填/是否请求头）、tools 与 srcUrl。</li>
 * </ul>
 *
 * <p>该广场<b>没有</b>结构化的「启动命令」字段，接入配置只能从 {@code readme} 里解析：
 * 优先取 {@code mcpServers} 的 JSON 片段（部分片段带 {@code //} 注释，需先剥离），
 * 退化时识别 {@code npx/uvx/docker/python -m} 命令行。解析失败时明确提示去项目主页手动配置，
 * 不做无根据的猜测。</p>
 *
 * <p>另一条常被误认为「广场接口」的地址 {@code https://tcb-api.cloud.tencent.com/mcp/v1}
 * 实测是<b>云开发托管的 MCP 网关</b>（POST JSON-RPC，需 OAuth Bearer 或
 * {@code X-TencentCloud-SecretId/SecretKey}），只能承载单个已部署的 MCP 服务，
 * 不提供目录数据，不能替代本适配器使用的目录接口。</p>
 *
 * @author noear 2026/11/16 created
 */
public class TencentMcpMarket implements McpMarket {
    private static final Logger LOG = LoggerFactory.getLogger(TencentMcpMarket.class);

    private static final String BASE_URL = "https://cloud.tencent.com/developer/api/mcp";
    private static final String DETAIL_PAGE_URL = "https://cloud.tencent.com/developer/mcp/server/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 目录（全量条目）缓存有效期：广场条目变动不频繁，10 分钟足够 */
    private static final long CATALOG_TTL = 10 * 60 * 1000L;
    /** 详情缓存上限 */
    private static final int CACHE_MAX = 300;

    /** 通用到没有辨识度的服务名（广场里确实存在这类 mcpName） */
    private static final java.util.Set<String> GENERIC_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "mcp", "mcps", "sdk", "server", "servers", "mcp-server", "mcp server", "server-mcp"));

    /** 命令行兜底解析时认可的可执行前缀 */
    private static final String[] SHELL_COMMANDS = {"npx", "uvx", "docker", "python", "python3", "node", "java", "uv"};

    /** 详情缓存（mcpId -> 详情节点） */
    private final Map<String, ONode> detailCache = Collections.synchronizedMap(
            new LinkedHashMap<String, ONode>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ONode> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    /** 目录缓存（全量条目，扁平化后按广场默认顺序） */
    private volatile Catalog catalog;

    @Override
    public String name() {
        return "cloud.tencent.com";
    }

    @Override
    public String description() {
        return "腾讯云开发者 MCP 广场（中文、分类齐全，超千条；国内直连）";
    }

    // ==================== 列表与搜索 ====================

    @Override
    public Result<McpMarketPage> list(String query, int limit, String cursor) {
        int pageSize = limit <= 0 ? 30 : limit;

        try {
            if (Assert.isNotEmpty(query)) {
                return searchByQuery(query.trim(), pageSize);
            }
            return browse(parseOffset(cursor), pageSize);
        } catch (Exception e) {
            LOG.warn("TencentMcpMarket.list error: {}", e.getMessage());
            return Result.failure("获取连接器市场列表失败: " + e.getMessage());
        }
    }

    /**
     * 浏览：目录接口一次给全量，本地按 offset 切页，避免每次翻页都重复拉 550KB。
     */
    private Result<McpMarketPage> browse(int offset, int pageSize) throws Exception {
        List<McpMarketItem> all = getCatalog().items;

        if (offset >= all.size()) {
            return Result.succeed(new McpMarketPage()
                    .items(Collections.<McpMarketItem>emptyList())
                    .nextCursor(null));
        }

        int end = Math.min(offset + pageSize, all.size());
        List<McpMarketItem> pageItems = new ArrayList<>(all.subList(offset, end));
        String nextCursor = end < all.size() ? String.valueOf(end) : null;

        return Result.succeed(new McpMarketPage().items(pageItems).nextCursor(nextCursor));
    }

    /**
     * 搜索：直接使用广场的服务端搜索（中文分词由对方处理），不做本地索引。
     */
    private Result<McpMarketPage> searchByQuery(String query, int limit) throws Exception {
        ONode body = new ONode().set("keyword", query);
        ONode root = post("/list-by-search", body);

        List<McpMarketItem> items = new ArrayList<>();
        for (ONode serverNode : arrayOf(root, "list")) {
            McpMarketItem item = toItem(serverNode);
            if (item != null) {
                items.add(item);
                if (items.size() >= limit) {
                    break;
                }
            }
        }

        return Result.succeed(new McpMarketPage().items(items).nextCursor(null));
    }

    /**
     * 获取（必要时重建）全量目录
     */
    private Catalog getCatalog() throws Exception {
        Catalog current = catalog;
        if (current != null && !current.isExpired()) {
            return current;
        }

        synchronized (this) {
            current = catalog;
            if (current != null && !current.isExpired()) {
                return current;
            }

            ONode root = post("/list", new ONode());

            List<McpMarketItem> items = new ArrayList<>();
            // 返回结构：[{categoryId, description, mcpNum, mcpServers:[...]}]，各分类已按权重排序
            for (ONode group : arrayOf(root, "list")) {
                for (ONode serverNode : arrayOf(group, "mcpServers")) {
                    McpMarketItem item = toItem(serverNode);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }

            current = new Catalog(items);
            catalog = current;

            LOG.debug("TencentMcpMarket: catalog rebuilt, size={}", items.size());
            return current;
        }
    }

    // ==================== 详情 ====================

    @Override
    public Result<McpMarketDetail> detail(String id) {
        if (Assert.isEmpty(id)) {
            return Result.failure("id is required");
        }

        try {
            ONode serverNode = fetchDetail(id);
            if (serverNode == null) {
                return Result.failure("连接器不存在: " + id);
            }
            return Result.succeed(toDetail(serverNode));
        } catch (Exception e) {
            LOG.warn("TencentMcpMarket.detail error: {}", e.getMessage());
            return Result.failure("获取连接器详情失败: " + e.getMessage());
        }
    }

    private ONode fetchDetail(String id) throws Exception {
        ONode cached = detailCache.get(id);
        if (cached != null) {
            return cached;
        }

        ONode body = new ONode().set("mcpId", toMcpId(id));
        ONode serverNode = post("/detail", body);

        // 参数错误时返回 {"msg":"参数错误","code":400}，没有 mcpId 字段
        if (serverNode == null || serverNode.get("mcpId") == null || serverNode.get("mcpId").isNull()) {
            return null;
        }

        detailCache.put(id, serverNode);
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
            // 广场条目多数只有 readme，没有可直接复制的启动片段；
            // 这时给出明确出口（详情页/源仓库），而不是拼一个猜出来的配置。
            StringBuilder msg = new StringBuilder("该连接器未提供可直接导入的启动配置，请按项目文档手动添加");
            if (detail.getHomepageUrl() != null) {
                msg.append("（参考: ").append(detail.getHomepageUrl()).append("）");
            }
            msg.append("；详情页: ").append(DETAIL_PAGE_URL).append(id);
            return Result.failure(msg.toString());
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

        return Result.succeed(toDraft(detail, config));
    }

    private McpServerDraft toDraft(McpMarketDetail detail, McpMarketConfig config) {
        McpServerDraft draft = new McpServerDraft()
                .name(pickName(config, detail))
                .type(config.getType())
                .source(name())
                .sourceId(detail.getId())
                .configId(config.getConfigId());

        List<McpMarketParam> pending = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();

        if (McpTypeResolver.isStdio(config.getType())) {
            draft.command(config.getCommand()).args(config.getArgs());

            Map<String, String> env = new LinkedHashMap<>();
            if (config.getParams() != null) {
                for (McpMarketParam param : config.getParams()) {
                    if (McpMarketParam.IN_HEADER.equals(param.getIn())) {
                        // stdio 没有请求头概念，这类参数在广场里不会出现，兜底当环境变量
                    }
                    if (Assert.isNotEmpty(param.getDefaultValue())) {
                        env.put(param.getName(), param.getDefaultValue());
                    } else {
                        env.put(param.getName(), "");
                        pending.add(param);
                        placeholders.add(param.getName());
                    }
                }
            }
            draft.env(env);
        } else {
            draft.url(config.getUrl());

            Map<String, String> headers = new LinkedHashMap<>();
            if (config.getParams() != null) {
                for (McpMarketParam param : config.getParams()) {
                    if (McpMarketParam.IN_HEADER.equals(param.getIn())) {
                        if (Assert.isNotEmpty(param.getDefaultValue())) {
                            headers.put(param.getName(), param.getDefaultValue());
                        } else {
                            headers.put(param.getName(), "");
                            pending.add(param);
                            placeholders.add(param.getName());
                        }
                    } else {
                        // 广场只有 header 位的远程条目才提供参数；其余按待填提示处理
                        pending.add(param);
                        placeholders.add(param.getName());
                    }
                }
            }
            draft.headers(headers);
        }

        draft.pendingParams(pending);

        if (!placeholders.isEmpty()) {
            draft.note("以下参数需补齐后才能连接：" + String.join("、", placeholders)
                    + "（在广场页面或项目文档中可查取）");
        }

        return draft;
    }

    // ==================== 解析：条目 ====================

    private McpMarketItem toItem(ONode serverNode) {
        String id = str(serverNode, "mcpId");
        if (Assert.isEmpty(id)) {
            return null;
        }

        String mcpName = str(serverNode, "mcpName");
        String title = str(serverNode, "title");

        List<String> transports = new ArrayList<>();
        transports.add(McpTypeResolver.TYPE_STDIO);
        transports.add(McpTypeResolver.TYPE_STREAMABLE);

        List<String> matchKeys = new ArrayList<>();
        if (Assert.isNotEmpty(mcpName)) {
            matchKeys.add(mcpName);
        }

        return new McpMarketItem()
                .id(id)
                .name(mcpName != null ? mcpName : id)
                .displayName(title != null ? title : mcpName)
                .owner(str(serverNode, "srcAuthor"))
                .description(str(serverNode, "abstract"))
                .iconUrl(str(serverNode, "iconUrl"))
                .homepageUrl(str(serverNode, "srcUrl"))
                .verified(bool(serverNode, "isVerified"))
                .useCount(longOf(serverNode, "readNum"))
                .updatedAt(str(serverNode, "updatedAt"))
                .createdAt(str(serverNode, "createdAt"))
                .transports(transports)
                .matchKeys(matchKeys);
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
            detail.updatedAt(item.getUpdatedAt());
            detail.createdAt(item.getCreatedAt());
            detail.transports(item.getTransports());
            detail.matchKeys(item.getMatchKeys());
        }

        return detail;
    }

    /**
     * 从 readme 解析接入方式。解析不出时返回空列表（由 resolve 给出明确提示）。
     */
    private List<McpMarketConfig> buildConfigs(ONode serverNode) {
        List<McpMarketConfig> configs = new ArrayList<>();

        Startup startup = parseStartup(str(serverNode, "readme"));
        if (startup == null) {
            return configs;
        }

        List<McpMarketParam> params = parseMcpParams(serverNode.get("mcpParams"), startup);

        if (startup.url != null) {
            String type = McpTypeResolver.standardize(startup.typeRaw);
            if (!McpTypeResolver.isHttpType(type)) {
                type = McpTypeResolver.TYPE_STREAMABLE;
            }
            configs.add(new McpMarketConfig()
                    .configId("http")
                    .serverKey(startup.key)
                    .label("HTTP · " + startup.url)
                    .type(type)
                    .url(startup.url)
                    .params(params));
        } else if (startup.command != null) {
            configs.add(new McpMarketConfig()
                    .configId("stdio")
                    .serverKey(startup.key)
                    .label("stdio · " + startup.command + " " + String.join(" ", startup.args))
                    .type(McpTypeResolver.TYPE_STDIO)
                    .command(startup.command)
                    .args(startup.args)
                    .params(params));
        }

        return configs;
    }

    /**
     * 广场的 mcpParams 是「服务需要的配置项」清单（字段名 + 占位文案 + 是否必填 + 是否请求头），
     * 与 readme 片段里的 env/headers 互为补充：
     * <ul>
     *     <li>片段里已写死默认值的 → 预填；</li>
     *     <li>片段里是 {@code <...>} / YOUR_ 之类占位的 → 置空并标记待填；</li>
     *     <li>清单里出现但片段里没有的 → 也列为待填，避免漏配。</li>
     * </ul>
     */
    private List<McpMarketParam> parseMcpParams(ONode paramsNode, Startup startup) {
        List<McpMarketParam> params = new ArrayList<>();
        if (paramsNode == null || paramsNode.isNull() || !paramsNode.isArray()) {
            return params;
        }

        for (ONode node : paramsNode.getArray()) {
            if (node == null || node.isNull() || !node.isObject()) {
                continue;
            }

            String name = str(node, "fieldName");
            if (Assert.isEmpty(name)) {
                continue;
            }
            if (Boolean.FALSE.equals(boolObj(node, "userVisible"))) {
                continue;
            }

            String defaultValue = str(node, "defaultValue");
            if (defaultValue != null && isPlaceholderValue(defaultValue)) {
                defaultValue = null;
            }

            boolean isHeader = Boolean.TRUE.equals(boolObj(node, "isHeader"));
            if (startup.url == null) {
                // stdio 只能用环境变量承载
                isHeader = false;
            }

            params.add(new McpMarketParam()
                    .name(name)
                    .description(str(node, "placeholder"))
                    .required(Boolean.TRUE.equals(boolObj(node, "required")))
                    .secret(isSecretName(name))
                    .defaultValue(defaultValue)
                    .in(isHeader ? McpMarketParam.IN_HEADER : McpMarketParam.IN_ENV));
        }

        return params;
    }

    // ==================== 解析：readme 启动片段 ====================

    /**
     * 从 readme 中提取启动方式：先找 {@code mcpServers} JSON 片段，再退化到命令行。
     */
    private Startup parseStartup(String readme) {
        if (Assert.isEmpty(readme)) {
            return null;
        }

        List<String> blocks = splitCodeBlocks(readme);

        // 1) 结构化片段优先（最准确，能带出 env/headers/type）
        for (String block : blocks) {
            if (!block.contains("mcpServers")) {
                continue;
            }
            Startup startup = parseMcpServersJson(block);
            if (startup != null) {
                return startup;
            }
        }

        // 2) 命令行兜底（npx / uvx / docker / python -m ...）
        for (String block : blocks) {
            Startup startup = parseCommandLine(block);
            if (startup != null) {
                return startup;
            }
        }

        return null;
    }

    /**
     * 按 ``` 切分代码块（奇数位为块内容），并剥掉紧跟的 language 标记
     */
    private List<String> splitCodeBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        String[] parts = text.split("```");

        for (int i = 1; i < parts.length; i += 2) {
            String body = parts[i];
            int lineEnd = body.indexOf('\n');
            if (lineEnd > 0) {
                String head = body.substring(0, lineEnd).trim();
                // 语言标记：json / bash / shell / yml / "" 等，不可能是 JSON 起始
                if (head.isEmpty() || head.matches("[a-zA-Z0-9_+-]{1,16}")) {
                    body = body.substring(lineEnd + 1);
                }
            }
            blocks.add(body);
        }

        return blocks;
    }

    /**
     * 解析 {@code {"mcpServers":{"name":{...}}}} 片段
     */
    private Startup parseMcpServersJson(String block) {
        String json = stripJsonComments(block).trim();

        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        json = json.substring(start, end + 1);

        ONode root;
        try {
            root = ONode.ofJson(json);
        } catch (Exception e) {
            return null;
        }

        ONode servers = root.get("mcpServers");
        if (servers == null || servers.isNull() || !servers.isObject()) {
            return null;
        }

        ONode picked = null;
        String pickedKey = null;
        for (Map.Entry<String, ONode> entry : servers.getObject().entrySet()) {
            ONode candidate = entry.getValue();
            if (candidate != null && candidate.isObject()) {
                picked = candidate;
                pickedKey = entry.getKey();
                break;
            }
        }
        if (picked == null) {
            return null;
        }

        Startup startup = new Startup();
        startup.key = pickedKey;
        startup.typeRaw = str(picked, "type");

        String url = str(picked, "url");
        String command = str(picked, "command");

        if (Assert.isNotEmpty(url)) {
            startup.url = url;
            startup.env.putAll(toStringMap(picked.get("headers")));
        } else if (Assert.isNotEmpty(command)) {
            startup.command = command;
            startup.args.addAll(toStringList(picked.get("args")));
            startup.env.putAll(toStringMap(picked.get("env")));
        } else {
            return null;
        }

        return startup;
    }

    /**
     * 命令行兜底：在代码块中找第一条以已知可执行名开头的行
     */
    private Startup parseCommandLine(String block) {
        for (String rawLine : block.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            // 去掉常见提示符与 sudo
            line = line.replaceFirst("^\\$\\s+", "").replaceFirst("^>\\s+", "");
            line = line.replaceFirst("^sudo\\s+", "");

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) {
                continue;
            }

            String head = tokens.get(0);
            boolean known = false;
            for (String cmd : SHELL_COMMANDS) {
                if (cmd.equals(head)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                continue;
            }
            if (tokens.size() < 2) {
                continue;
            }

            Startup startup = new Startup();
            startup.command = head;
            startup.args.addAll(tokens.subList(1, tokens.size()));
            startup.typeRaw = McpTypeResolver.TYPE_STDIO;
            return startup;
        }

        return null;
    }

    /**
     * 极简分词：支持单双引号包裹的参数
     */
    private List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    buf.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            tokens.add(buf.toString());
        }

        return tokens;
    }

    /**
     * 剥离 JSON 里的 {@code //} 与 {@code /* *}{@code /} 注释（字符串内的不动）。
     * 广场里不少片段带行内注释（{@code "env": { "KEY": "xxx"  // 必填 }}），直接解析会失败。
     */
    private String stripJsonComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    if (next != 0) {
                        out.append(next);
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }

    // ==================== HTTP ====================

    /**
     * 广场 BFF 接口统一为 POST + JSON（前端 axios 的 baseURL 为 {@code /developer}）
     */
    private ONode post(String path, ONode body) throws Exception {
        HttpUtils http = HttpUtils.http(BASE_URL + path)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "https://cloud.tencent.com/developer/mcp")
                .header("Origin", "https://cloud.tencent.com")
                .timeout(25000)
                .ssl(SkillMarketSslSupplier.getInstance());
        ProxyConfig.applyIfNeeded(http);

        if (body != null) {
            http.bodyOfJson(body.toJson());
        } else {
            http.bodyOfJson("{}");
        }

        try (HttpResponse response = http.exec("POST")) {
            int code = response.code();
            String text = response.bodyAsString();

            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + shortText(text));
            }

            return ONode.ofJson(text);
        }
    }

    private String shortText(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
    }

    // ==================== 工具方法 ====================

    private Object toMcpId(String id) {
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return id;
        }
    }

    private int parseOffset(String cursor) {
        if (Assert.isEmpty(cursor)) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor.trim());
            return Math.max(offset, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 占位值判定：{@code <...>}、YOUR_xxx、your-xxx、xxx/示例
     */
    private boolean isPlaceholderValue(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return true;
        }
        if (v.startsWith("<") && v.endsWith(">")) {
            return true;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        return lower.startsWith("your_") || lower.startsWith("your-")
                || lower.contains("your_api") || lower.contains("替换") || lower.contains("必填");
    }

    private boolean isSecretName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("key") || lower.contains("token") || lower.contains("secret")
                || lower.contains("password") || lower.contains("apikey");
    }

    /**
     * 选一个可读且可用的配置名。
     *
     * <p>优先级：readme 片段里的服务名（通常是包名，最贴切）→ 广场 mcpName → 标题。
     * 广场里有一批条目的 mcpName 是 {@code mcp}/{@code servers}/{@code sdk} 这类通用词，
     * 直接用会得到毫无辨识度的配置名，故按黑名单跳过。</p>
     */
    private String pickName(McpMarketConfig config, McpMarketDetail detail) {
        for (String raw : new String[]{startupKeyOf(config), detail.getName(), detail.getDisplayName()}) {
            if (Assert.isEmpty(raw) || GENERIC_NAMES.contains(raw.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String name = suggestName(raw);
            if (!"mcp-server".equals(name)) {
                return name;
            }
        }
        return "mcp-server";
    }

    private String startupKeyOf(McpMarketConfig config) {
        return config.getServerKey();
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

    private Map<String, String> toStringMap(ONode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return map;
        }
        for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
            ONode value = entry.getValue();
            map.put(entry.getKey(), value == null || value.isNull() ? "" : value.getString());
        }
        return map;
    }

    private List<String> toStringList(ONode node) {
        List<String> list = new ArrayList<>();
        if (node == null || node.isNull() || !node.isArray()) {
            return list;
        }
        for (ONode item : node.getArray()) {
            if (item != null && !item.isNull()) {
                list.add(item.getString());
            }
        }
        return list;
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

    /**
     * 三态布尔：缺省返回 null（用于区分「没这个字段」与 false）
     */
    private Boolean boolObj(ONode node, String key) {
        ONode child = node == null ? null : node.get(key);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.getBoolean();
    }

    private boolean bool(ONode node, String key) {
        Boolean value = boolObj(node, key);
        return value != null && value;
    }

    private long longOf(ONode node, String key) {
        ONode child = node == null ? null : node.get(key);
        return child == null || child.isNull() ? 0L : child.getLong();
    }

    /**
     * 目录缓存
     */
    private static class Catalog {
        final List<McpMarketItem> items;
        final long createdAt = System.currentTimeMillis();

        Catalog(List<McpMarketItem> items) {
            this.items = items;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CATALOG_TTL;
        }
    }

    /**
     * readme 中解析出的启动方式
     */
    private static class Startup {
        /** mcpServers 里的服务名（通常是包名或语义化短名，最适合做配置名） */
        String key;
        String typeRaw;
        String url;
        String command;
        List<String> args = new ArrayList<>();
        /** 片段里声明的 env 或 headers（可能含占位值，交给上层判定） */
        Map<String, String> env = new LinkedHashMap<>();
    }
}
