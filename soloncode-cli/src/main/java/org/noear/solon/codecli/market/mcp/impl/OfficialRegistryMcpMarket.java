package org.noear.solon.codecli.market.mcp.impl;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.McpTypeResolver;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.market.skill.SkillMarketSslSupplier;
import org.noear.solon.codecli.market.mcp.McpMarket;
import org.noear.solon.codecli.market.mcp.McpMarketConfig;
import org.noear.solon.codecli.market.mcp.McpMarketDetail;
import org.noear.solon.codecli.market.mcp.McpMarketItem;
import org.noear.solon.codecli.market.mcp.McpMarketPage;
import org.noear.solon.codecli.market.mcp.McpMarketParam;
import org.noear.solon.codecli.market.mcp.McpServerDraft;
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
import java.util.Map;

/**
 * 官方 MCP 注册表适配器 — 对接 registry.modelcontextprotocol.io（v0.1）。
 *
 * <p>只使用一个公开接口：{@code GET /v0.1/servers?version=latest&limit=&search=&cursor=}。
 * 详情与配置草稿都由列表返回的原始节点在本地解析（列表结果按 id 缓存），
 * 因此不依赖未公开的详情路由，也避免了重复抓取。</p>
 *
 * <p>注意：该注册表位于境外，国内可达性不稳定。客户端不直连，
 * 全部请求经由本适配器（并遵循用户配置的 HTTP 代理）。</p>
 *
 * @author noear 2026/11/16 created
 */
public class OfficialRegistryMcpMarket implements McpMarket {
    private static final Logger LOG = LoggerFactory.getLogger(OfficialRegistryMcpMarket.class);

    private static final String BASE_URL = "https://registry.modelcontextprotocol.io";
    private static final String USER_AGENT = "SolonCode/1.0";

    private static final int PAGE_SIZE_MAX = 100;
    private static final int CACHE_MAX = 1000;

    /** 原始 server 节点缓存（id -> server），列表与详情共用，避免重复抓取 */
    private final Map<String, ONode> serverCache = Collections.synchronizedMap(
            new LinkedHashMap<String, ONode>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ONode> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    @Override
    public String name() {
        return "modelcontextprotocol.io";
    }

    @Override
    public String description() {
        return "MCP 官方注册表（境外源，国内可能不稳定）";
    }

    // ==================== 列表与搜索 ====================

    @Override
    public Result<McpMarketPage> list(String query, int limit, String cursor) {
        int pageSize = limit <= 0 ? 30 : Math.min(limit, PAGE_SIZE_MAX);

        try {
            StringBuilder url = new StringBuilder(BASE_URL)
                    .append("/v0.1/servers?version=latest&limit=").append(pageSize);

            if (Assert.isNotEmpty(query)) {
                url.append("&search=").append(URLEncoder.encode(query, "UTF-8"));
            }
            if (Assert.isNotEmpty(cursor)) {
                url.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"));
            }

            ONode root = ONode.ofJson(httpGet(url.toString()));

            List<McpMarketItem> items = new ArrayList<>();
            ONode serversNode = root.get("servers");
            if (serversNode != null && serversNode.isArray()) {
                for (ONode entry : serversNode.getArray()) {
                    ONode serverNode = entry.get("server");
                    if (serverNode == null || serverNode.isNull() || !serverNode.isObject()) {
                        continue;
                    }
                    if (isInactive(entry)) {
                        continue;
                    }

                    String id = str(serverNode, "name");
                    if (Assert.isEmpty(id)) {
                        continue;
                    }

                    serverCache.put(id, serverNode);
                    items.add(toItem(serverNode));
                }
            }

            String nextCursor = null;
            ONode metadataNode = root.get("metadata");
            if (metadataNode != null && metadataNode.isObject()) {
                nextCursor = str(metadataNode, "nextCursor");
            }

            return Result.succeed(new McpMarketPage().items(items).nextCursor(nextCursor));
        } catch (Exception e) {
            LOG.warn("OfficialRegistryMcpMarket.list error: {}", e.getMessage());
            return Result.failure("获取连接器市场列表失败: " + e.getMessage());
        }
    }

    // ==================== 详情 ====================

    @Override
    public Result<McpMarketDetail> detail(String id) {
        if (Assert.isEmpty(id)) {
            return Result.failure("id is required");
        }

        try {
            ONode serverNode = serverCache.get(id);
            if (serverNode == null) {
                serverNode = fetchServer(id);
            }
            if (serverNode == null) {
                return Result.failure("连接器不存在: " + id);
            }

            return Result.succeed(toDetail(serverNode));
        } catch (Exception e) {
            LOG.warn("OfficialRegistryMcpMarket.detail error: {}", e.getMessage());
            return Result.failure("获取连接器详情失败: " + e.getMessage());
        }
    }

    /**
     * 缓存未命中时按短名再搜一次，仅接受 id 完全一致的条目。
     */
    private ONode fetchServer(String id) {
        String shortName = id.contains("/") ? id.substring(id.indexOf('/') + 1) : id;

        Result<McpMarketPage> pageResult = list(shortName, 50, null);
        if (pageResult.getCode() != 200 || pageResult.getData() == null) {
            return null;
        }

        ONode cached = serverCache.get(id);
        if (cached != null) {
            return cached;
        }

        for (McpMarketItem item : pageResult.getData().getItems()) {
            if (id.equals(item.getId())) {
                return serverCache.get(item.getId());
            }
        }
        return null;
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

        return Result.succeed(toDraft(detail, config));
    }

    private McpServerDraft toDraft(McpMarketDetail detail, McpMarketConfig config) {
        McpServerDraft draft = new McpServerDraft()
                .name(suggestName(detail.getName()))
                .type(config.getType())
                .source(name())
                .sourceId(detail.getId())
                .configId(config.getConfigId());

        List<McpMarketParam> pending = new ArrayList<>();

        if (McpTypeResolver.isStdio(config.getType())) {
            draft.command(config.getCommand());
            draft.args(config.getArgs() == null ? new ArrayList<>() : config.getArgs());

            // 环境变量：非机密的默认值直接预填，其余留给用户
            Map<String, String> env = new LinkedHashMap<>();
            if (config.getParams() != null) {
                for (McpMarketParam param : config.getParams()) {
                    if (param.isNeedUserInput()) {
                        pending.add(param);
                    } else if (param.getDefaultValue() != null) {
                        env.put(param.getName(), param.getDefaultValue());
                    }
                }
            }
            draft.env(env);
        } else {
            draft.url(config.getUrl());

            Map<String, String> headers = new LinkedHashMap<>();
            if (config.getParams() != null) {
                for (McpMarketParam param : config.getParams()) {
                    if (param.isNeedUserInput()) {
                        pending.add(param);
                    } else if (param.getDefaultValue() != null) {
                        headers.put(param.getName(), param.getDefaultValue());
                    }
                }
            }
            draft.headers(headers);
        }

        draft.pendingParams(pending);
        return draft;
    }

    // ==================== 解析 ====================

    private McpMarketItem toItem(ONode serverNode) {
        String id = str(serverNode, "name");
        String name = id;
        String owner = "";

        int idx = id.indexOf('/');
        if (idx > 0) {
            owner = id.substring(0, idx);
            name = id.substring(idx + 1);
        }

        List<String> transports = new ArrayList<>();
        List<String> matchKeys = new ArrayList<>();

        for (ONode pkg : arrayOf(serverNode, "packages")) {
            if (!transports.contains("stdio")) {
                transports.add("stdio");
            }
            String identifier = str(pkg, "identifier");
            if (Assert.isNotEmpty(identifier)) {
                matchKeys.add(identifier);
            }
        }

        for (ONode remote : arrayOf(serverNode, "remotes")) {
            String remoteType = str(remote, "type");
            if (Assert.isNotEmpty(remoteType) && !transports.contains(remoteType)) {
                transports.add(remoteType);
            }
            String url = str(remote, "url");
            if (Assert.isNotEmpty(url)) {
                matchKeys.add(url);
            }
        }

        String updatedAt = null;
        ONode official = officialMeta(serverNode);
        if (official != null) {
            updatedAt = str(official, "updatedAt");
        }

        String repositoryUrl = null;
        ONode repositoryNode = serverNode.get("repository");
        if (repositoryNode != null && repositoryNode.isObject()) {
            repositoryUrl = str(repositoryNode, "url");
        }

        return new McpMarketItem()
                .id(id)
                .name(name)
                .displayName(name)
                .owner(owner)
                .description(str(serverNode, "description"))
                .version(str(serverNode, "version"))
                .repositoryUrl(repositoryUrl)
                .transports(transports)
                .matchKeys(matchKeys)
                .updatedAt(updatedAt);
    }

    private McpMarketDetail toDetail(ONode serverNode) {
        McpMarketItem item = toItem(serverNode);

        McpMarketDetail detail = new McpMarketDetail().configs(buildConfigs(serverNode));
        detail.id(item.getId());
        detail.name(item.getName());
        detail.displayName(item.getDisplayName());
        detail.owner(item.getOwner());
        detail.description(item.getDescription());
        detail.version(item.getVersion());
        detail.repositoryUrl(item.getRepositoryUrl());
        detail.transports(item.getTransports());
        detail.matchKeys(item.getMatchKeys());
        detail.updatedAt(item.getUpdatedAt());

        return detail;
    }

    private List<McpMarketConfig> buildConfigs(ONode serverNode) {
        List<McpMarketConfig> configs = new ArrayList<>();

        int packageIndex = 0;
        for (ONode pkg : arrayOf(serverNode, "packages")) {
            McpMarketConfig config = toPackageConfig(pkg, packageIndex++);
            if (config != null) {
                configs.add(config);
            }
        }

        int remoteIndex = 0;
        for (ONode remote : arrayOf(serverNode, "remotes")) {
            McpMarketConfig config = toRemoteConfig(remote, remoteIndex++);
            if (config != null) {
                configs.add(config);
            }
        }

        return configs;
    }

    private McpMarketConfig toPackageConfig(ONode pkg, int index) {
        String identifier = str(pkg, "identifier");
        if (Assert.isEmpty(identifier)) {
            return null;
        }

        String registryType = str(pkg, "registryType");
        String runtime = str(pkg, "runtimeHint");

        List<String> args = new ArrayList<>();
        for (ONode arg : arrayOf(pkg, "runtimeArguments")) {
            String argName = str(arg, "name");
            String argValue = str(arg, "value");
            if (Assert.isNotEmpty(argName)) {
                args.add(argName);
            }
            if (Assert.isNotEmpty(argValue)) {
                args.add(argValue);
            }
        }

        String command;
        if ("oci".equalsIgnoreCase(registryType) || "docker".equalsIgnoreCase(registryType)) {
            command = Assert.isNotEmpty(runtime) ? runtime : "docker";
            if ("docker".equalsIgnoreCase(command)) {
                args.add("run");
                args.add("-i");
                args.add("--rm");
            }
        } else {
            command = Assert.isNotEmpty(runtime) ? runtime
                    : ("pypi".equalsIgnoreCase(registryType) ? "uvx" : "npx");
        }
        args.add(identifier);

        List<McpMarketParam> params = new ArrayList<>();
        for (ONode envNode : arrayOf(pkg, "environmentVariables")) {
            String envName = str(envNode, "name");
            if (Assert.isEmpty(envName)) {
                continue;
            }
            params.add(new McpMarketParam()
                    .name(envName)
                    .description(str(envNode, "description"))
                    .required(bool(envNode, "isRequired"))
                    .secret(bool(envNode, "isSecret"))
                    .defaultValue(str(envNode, "default"))
                    .in(McpMarketParam.IN_ENV));
        }

        return new McpMarketConfig()
                .configId("package-" + index)
                .label((registryType == null ? "package" : registryType) + " · " + command + " " + identifier)
                .type(McpTypeResolver.TYPE_STDIO)
                .command(command)
                .runtime(command)
                .args(args)
                .params(params);
    }

    private McpMarketConfig toRemoteConfig(ONode remote, int index) {
        String url = str(remote, "url");
        if (Assert.isEmpty(url)) {
            return null;
        }

        String rawType = str(remote, "type");
        String type = McpTypeResolver.standardize(rawType);
        if (!McpTypeResolver.isHttpType(type)) {
            // 无法识别的传输类型：按可流式 HTTP 处理，交由用户检测连接时验证
            type = McpTypeResolver.TYPE_STREAMABLE;
        }

        List<McpMarketParam> params = new ArrayList<>();
        for (ONode headerNode : arrayOf(remote, "headers")) {
            String headerName = str(headerNode, "name");
            if (Assert.isEmpty(headerName)) {
                continue;
            }
            String value = str(headerNode, "value");
            boolean secret = bool(headerNode, "isSecret") || (value != null && value.contains("{"));

            params.add(new McpMarketParam()
                    .name(headerName)
                    .description(str(headerNode, "description"))
                    .required(bool(headerNode, "isRequired"))
                    .secret(secret)
                    .defaultValue(secret ? null : value)
                    .in(McpMarketParam.IN_HEADER));
        }

        return new McpMarketConfig()
                .configId("remote-" + index)
                .label((rawType == null ? type : rawType) + " · " + url)
                .type(type)
                .url(url)
                .params(params);
    }

    // ==================== 工具方法 ====================

    /**
     * 判断条目是否已被下架/停用（{@code _meta} 中的 status 非 active）。
     */
    private boolean isInactive(ONode entry) {
        ONode metaNode = entry.get("_meta");
        if (metaNode == null || !metaNode.isObject()) {
            return false;
        }
        ONode official = metaNode.get("io.modelcontextprotocol.registry/official");
        if (official == null || !official.isObject()) {
            return false;
        }
        String status = str(official, "status");
        return status != null && !"active".equalsIgnoreCase(status);
    }

    private ONode officialMeta(ONode serverNode) {
        // 列表返回的 _meta 在条目外层，缓存里只有 server 节点，此处尽力而为
        ONode metaNode = serverNode.get("_meta");
        if (metaNode == null || !metaNode.isObject()) {
            return null;
        }
        return metaNode.get("io.modelcontextprotocol.registry/official");
    }

    /**
     * 生成可用的 MCP 服务名（仅允许 {@code [a-zA-Z0-9_-]}）。
     */
    private String suggestName(String raw) {
        if (Assert.isEmpty(raw)) {
            return "mcp-server";
        }

        String name = raw.toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");

        if (name.length() > 48) {
            name = name.substring(0, 48).replaceAll("-+$", "");
        }

        return name.isEmpty() ? "mcp-server" : name;
    }

    private String httpGet(String url) throws Exception {
        HttpUtils http = HttpUtils.http(url)
                .header("User-Agent", USER_AGENT)
                .timeout(20000)
                .ssl(SkillMarketSslSupplier.getInstance());
        ProxyConfig.applyIfNeeded(http);
        return http.get();
    }

    private List<ONode> arrayOf(ONode node, String key) {
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
        ONode child = node.get(key);
        return child != null && !child.isNull() && child.getBoolean();
    }
}
