package org.noear.solon.codecli.market.mcp;

import java.util.List;

/**
 * 连接器市场列表项 — 统一封装连接器在市场列表/搜索中的展示信息。
 *
 * @author noear 2026/11/16 created
 */
public class McpMarketItem {
    /** 市场内唯一标识（官方注册表为 {@code <namespace>/<name>}） */
    private String id;
    /** 短名（去掉命名空间） */
    private String name;
    private String displayName;
    /** 命名空间/发布方（如 ai.smithery、io.github.xxx） */
    private String owner;
    private String description;
    private String version;
    private String repositoryUrl;
    /** 可用传输类型列表（stdio / streamable-http / sse） */
    private List<String> transports;
    /** 更新时间（市场原样返回的 ISO 时间串） */
    private String updatedAt;
    /** 是否已存在于本地配置（由控制器标注） */
    private boolean installed;
    /** 用于"是否已安装"比对的标识（包名 / 远程地址），仅后端使用 */
    private List<String> matchKeys;

    public McpMarketItem() {
    }

    public McpMarketItem id(String id) { this.id = id; return this; }
    public McpMarketItem name(String name) { this.name = name; return this; }
    public McpMarketItem displayName(String displayName) { this.displayName = displayName; return this; }
    public McpMarketItem owner(String owner) { this.owner = owner; return this; }
    public McpMarketItem description(String description) { this.description = description; return this; }
    public McpMarketItem version(String version) { this.version = version; return this; }
    public McpMarketItem repositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; return this; }
    public McpMarketItem transports(List<String> transports) { this.transports = transports; return this; }
    public McpMarketItem updatedAt(String updatedAt) { this.updatedAt = updatedAt; return this; }
    public McpMarketItem installed(boolean installed) { this.installed = installed; return this; }
    public McpMarketItem matchKeys(List<String> matchKeys) { this.matchKeys = matchKeys; return this; }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public String getOwner() { return owner; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public List<String> getTransports() { return transports; }
    public String getUpdatedAt() { return updatedAt; }
    public boolean isInstalled() { return installed; }
    public List<String> getMatchKeys() { return matchKeys; }
}
